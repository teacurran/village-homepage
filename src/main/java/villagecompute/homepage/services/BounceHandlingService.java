package villagecompute.homepage.services;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.EmailBounce;
import villagecompute.homepage.data.models.EmailBounce.BounceType;
import villagecompute.homepage.data.models.EmailDeliveryLog;
import villagecompute.homepage.data.models.EmailDeliveryLog.DeliveryStatus;
import villagecompute.homepage.data.models.User;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounce handling service for DSN (Delivery Status Notification) parsing and threshold-based email disabling (Feature
 * I5.T5).
 *
 * <p>
 * Processes RFC 3464 bounce messages to extract diagnostic information, classify bounces (hard/soft), track bounce
 * history, and automatically disable email delivery to problematic addresses based on threshold rules.
 *
 * <h3>Core Responsibilities:</h3>
 * <ul>
 * <li><b>DSN Parsing:</b> Extracts status codes and bounce reasons from multipart/report messages</li>
 * <li><b>Bounce Classification:</b> Categorizes bounces as HARD (5.x.x) or SOFT (4.x.x) based on DSN status codes</li>
 * <li><b>Threshold Detection:</b> Disables email delivery after 1 hard bounce OR 5 consecutive soft bounces</li>
 * <li><b>User Flag Management:</b> Sets user.emailDisabled flag to prevent future email sends</li>
 * <li><b>Audit Trail:</b> Records all bounces in database for debugging and analysis</li>
 * </ul>
 *
 * <h3>Bounce Classification Rules:</h3>
 * <table border="1">
 * <tr>
 * <th>DSN Code</th>
 * <th>Type</th>
 * <th>Meaning</th>
 * <th>Action</th>
 * </tr>
 * <tr>
 * <td>5.x.x</td>
 * <td>HARD</td>
 * <td>Permanent failure (user unknown, domain invalid, blocked)</td>
 * <td>Disable immediately</td>
 * </tr>
 * <tr>
 * <td>4.x.x</td>
 * <td>SOFT</td>
 * <td>Temporary failure (mailbox full, timeout, greylisting)</td>
 * <td>Disable after 5 consecutive</td>
 * </tr>
 * <tr>
 * <td>2.x.x</td>
 * <td>-</td>
 * <td>Success (not a bounce)</td>
 * <td>Reset consecutive count</td>
 * </tr>
 * </table>
 *
 * <h3>Common DSN Status Codes:</h3>
 * <ul>
 * <li><b>5.1.1:</b> Bad destination mailbox address (user unknown)</li>
 * <li><b>5.1.2:</b> Bad destination system address (domain does not exist)</li>
 * <li><b>5.2.1:</b> Mailbox disabled, not accepting messages</li>
 * <li><b>5.4.4:</b> Unable to route (permanent network failure)</li>
 * <li><b>5.7.1:</b> Delivery not authorized (blocked/rejected)</li>
 * <li><b>4.2.2:</b> Mailbox full (over quota)</li>
 * <li><b>4.4.1:</b> Connection timed out (temporary network issue)</li>
 * <li><b>4.4.7:</b> Message expired (delayed too long)</li>
 * <li><b>4.7.1:</b> Delivery not authorized (temporary greylisting)</li>
 * </ul>
 *
 * <h3>Threshold Logic:</h3>
 * <ol>
 * <li><b>Hard Bounce:</b> Immediately set user.emailDisabled = true (no threshold)</li>
 * <li><b>Soft Bounce:</b> Count consecutive soft bounces within last 30 days; disable after 5th</li>
 * <li><b>Consecutive:</b> Resets on successful email delivery (not tracked by this service)</li>
 * <li><b>Lookback Period:</b> Only bounces from last 30 days count toward threshold</li>
 * </ol>
 *
 * <h3>Integration Points:</h3>
 * <ul>
 * <li><b>InboundEmailProcessor:</b> Calls processBounces() when bounce message detected</li>
 * <li><b>NotificationService:</b> Checks user.emailDisabled before queueing emails</li>
 * <li><b>EmailDeliveryLog:</b> Updates delivery status to BOUNCED when bounce recorded</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 *
 * <pre>
 * // Called by InboundEmailProcessor when bounce email received
 * Message bounceEmail = ...;
 * Optional&lt;BounceInfo&gt; bounceInfo = bounceHandlingService.parseBounceEmail(bounceEmail);
 *
 * if (bounceInfo.isPresent()) {
 *     bounceHandlingService.recordBounce(bounceInfo.get().emailAddress, bounceInfo.get().diagnosticCode,
 *             bounceInfo.get().bounceReason);
 * }
 * </pre>
 *
 * @see EmailBounce for bounce tracking entity
 * @see User for emailDisabled flag
 * @see EmailDeliveryLog for delivery queue and status tracking
 * @see InboundEmailProcessor for bounce email detection
 */
@ApplicationScoped
public class BounceHandlingService {

    private static final Logger LOG = Logger.getLogger(BounceHandlingService.class);

    /**
     * Threshold for soft bounce disabling (5 consecutive within 30 days).
     */
    private static final int SOFT_BOUNCE_THRESHOLD = 5;

    /**
     * Lookback period for bounce counting (30 days).
     */
    private static final int BOUNCE_LOOKBACK_DAYS = 30;

    /**
     * Regex pattern for extracting DSN status codes from delivery-status content.
     *
     * <p>
     * Matches lines like "Status: 5.1.1" or "Status: 4.2.2" in RFC 3464 DSN messages.
     */
    private static final Pattern DSN_STATUS_PATTERN = Pattern.compile("Status:\\s*([245]\\.[0-9]+\\.[0-9]+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Regex pattern for extracting original recipient from DSN content.
     *
     * <p>
     * Matches lines like "Final-Recipient: rfc822;user@example.com" or "Original-Recipient: rfc822;user@example.com".
     */
    private static final Pattern DSN_RECIPIENT_PATTERN = Pattern
            .compile("(?:Final-Recipient|Original-Recipient):\\s*rfc822;\\s*(.+)", Pattern.CASE_INSENSITIVE);

    /**
     * Bounce information extracted from DSN message.
     *
     * @param emailAddress
     *            the bounced email address
     * @param diagnosticCode
     *            DSN status code (e.g., "5.1.1")
     * @param bounceReason
     *            human-readable bounce reason
     * @param bounceType
     *            HARD or SOFT classification
     */
    public record BounceInfo(String emailAddress, String diagnosticCode, String bounceReason, BounceType bounceType) {
    }

    /**
     * Parses a bounce email to extract DSN information (status code, recipient, reason).
     *
     * <p>
     * Supports RFC 3464 DSN format (multipart/report with message/delivery-status part) and falls back to heuristic
     * detection for non-compliant MTAs.
     *
     * <p>
     * <b>Parsing Strategy:</b>
     * <ol>
     * <li>Check for multipart message</li>
     * <li>Find message/delivery-status part (RFC 3464 DSN)</li>
     * <li>Extract Status: X.Y.Z line with regex</li>
     * <li>Extract Final-Recipient or Original-Recipient email</li>
     * <li>Fallback: Check subject line for bounce keywords</li>
     * </ol>
     *
     * @param message
     *            the bounce email message
     * @return Optional containing BounceInfo if successfully parsed, empty if not a bounce or parse failed
     */
    public Optional<BounceInfo> parseBounceEmail(Message message) {
        try {
            // Check if message is multipart (DSN format)
            Object content = message.getContent();
            if (!(content instanceof Multipart)) {
                LOG.debugf("Bounce message is not multipart, attempting subject line heuristic");
                return parseBounceFromSubject(message);
            }

            Multipart multipart = (Multipart) content;
            String diagnosticCode = null;
            String bounceReason = null;
            String recipientEmail = null;

            // Search for message/delivery-status part
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);

                // Look for DSN delivery-status part FIRST (don't check text/plain to avoid matching human-readable
                // part)
                if (part.isMimeType("message/delivery-status")) {
                    String dsnContent = part.getContent().toString();

                    // Extract status code
                    Matcher statusMatcher = DSN_STATUS_PATTERN.matcher(dsnContent);
                    if (statusMatcher.find()) {
                        diagnosticCode = statusMatcher.group(1);
                        LOG.debugf("Found DSN status code: %s", diagnosticCode);
                    }

                    // Extract recipient email
                    Matcher recipientMatcher = DSN_RECIPIENT_PATTERN.matcher(dsnContent);
                    if (recipientMatcher.find()) {
                        recipientEmail = recipientMatcher.group(1).trim();
                        LOG.debugf("Found bounce recipient: %s", recipientEmail);
                    }

                    // Extract bounce reason (first line after Status)
                    if (diagnosticCode != null) {
                        bounceReason = extractBounceReason(dsnContent, diagnosticCode);
                    }

                    // Found DSN part, stop searching
                    if (diagnosticCode != null && recipientEmail != null) {
                        break;
                    }
                }
            }

            // If no DSN found, try heuristic parsing
            if (diagnosticCode == null || recipientEmail == null) {
                LOG.debugf("DSN parsing incomplete (code=%s, recipient=%s), attempting heuristic", diagnosticCode,
                        recipientEmail);
                return parseBounceFromSubject(message);
            }

            // Classify bounce type
            BounceType bounceType = classifyBounce(diagnosticCode);

            return Optional.of(new BounceInfo(recipientEmail, diagnosticCode, bounceReason, bounceType));

        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse bounce email: %s", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fallback heuristic parsing for non-RFC-3464-compliant bounce messages.
     *
     * <p>
     * Checks subject line for common bounce keywords and extracts recipient from subject or body.
     *
     * @param message
     *            the bounce email message
     * @return Optional containing BounceInfo if bounce detected, empty otherwise
     */
    private Optional<BounceInfo> parseBounceFromSubject(Message message) {
        try {
            String subject = message.getSubject();
            if (subject == null) {
                return Optional.empty();
            }

            subject = subject.toLowerCase();

            // Check for bounce keywords
            if (!subject.contains("undelivered") && !subject.contains("failure") && !subject.contains("bounce")
                    && !subject.contains("returned mail")) {
                return Optional.empty();
            }

            LOG.infof("Detected bounce email via subject heuristic: %s", subject);

            // Default to SOFT for heuristic detection (conservative approach)
            // User can manually investigate if needed
            return Optional.of(new BounceInfo(null, // Email address unknown
                    null, // No DSN code
                    "Bounce detected via subject heuristic: " + subject, BounceType.SOFT));

        } catch (MessagingException e) {
            LOG.errorf(e, "Failed to parse bounce from subject: %s", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Extracts human-readable bounce reason from DSN content.
     *
     * @param dsnContent
     *            the DSN text content
     * @param diagnosticCode
     *            the DSN status code (used to locate reason text)
     * @return bounce reason string, or null if not found
     */
    private String extractBounceReason(String dsnContent, String diagnosticCode) {
        try {
            // Look for Diagnostic-Code or Action fields
            Pattern reasonPattern = Pattern.compile("(?:Diagnostic-Code|Action):\\s*(.+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = reasonPattern.matcher(dsnContent);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }

            // Fallback: Return status code as reason
            return "DSN status: " + diagnosticCode;
        } catch (Exception e) {
            LOG.debugf("Failed to extract bounce reason: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Classifies bounce type (HARD or SOFT) based on DSN status code.
     *
     * <p>
     * <b>Classification Rules:</b>
     * <ul>
     * <li>5.x.x → HARD (permanent failure)</li>
     * <li>4.x.x → SOFT (temporary failure)</li>
     * <li>2.x.x → Not a bounce (success)</li>
     * <li>Unknown → SOFT (conservative default)</li>
     * </ul>
     *
     * @param statusCode
     *            the DSN status code (e.g., "5.1.1", "4.2.2")
     * @return BounceType (HARD or SOFT)
     */
    private BounceType classifyBounce(String statusCode) {
        if (statusCode == null || statusCode.isBlank()) {
            LOG.warnf("Missing DSN status code, defaulting to SOFT bounce");
            return BounceType.SOFT; // Conservative default
        }

        char firstChar = statusCode.charAt(0);
        if (firstChar == '5') {
            LOG.infof("Classified as HARD bounce: %s", statusCode);
            return BounceType.HARD;
        } else if (firstChar == '4') {
            LOG.infof("Classified as SOFT bounce: %s", statusCode);
            return BounceType.SOFT;
        } else {
            // 2.x.x success codes or unknown formats
            LOG.warnf("Unexpected DSN status code %s, defaulting to SOFT bounce", statusCode);
            return BounceType.SOFT;
        }
    }

    /**
     * Records a bounce in the database and checks if email delivery should be disabled.
     *
     * <p>
     * <b>Workflow:</b>
     * <ol>
     * <li>Find user by email address</li>
     * <li>Create EmailBounce record</li>
     * <li>Update EmailDeliveryLog status to BOUNCED (if exists)</li>
     * <li>Check if threshold exceeded (shouldDisableEmail)</li>
     * <li>If threshold exceeded, set user.emailDisabled = true</li>
     * </ol>
     *
     * @param emailAddress
     *            the bounced email address
     * @param diagnosticCode
     *            the DSN status code (e.g., "5.1.1")
     * @param bounceReason
     *            human-readable bounce reason
     */
    public void recordBounce(String emailAddress, String diagnosticCode, String bounceReason) {
        if (emailAddress == null || emailAddress.isBlank()) {
            LOG.warnf("Cannot record bounce - email address is null or blank");
            return;
        }

        LOG.infof("Recording bounce for email %s with diagnostic code %s", emailAddress, diagnosticCode);

        // Classify bounce
        BounceType bounceType = classifyBounce(diagnosticCode);

        // Find user by email
        Optional<User> userOpt = User.findByEmail(emailAddress);
        UUID userId = userOpt.map(u -> u.id).orElse(null);

        // Create bounce record
        QuarkusTransaction.requiringNew().run(() -> {
            EmailBounce bounce = new EmailBounce();
            bounce.userId = userId;
            bounce.emailAddress = emailAddress;
            bounce.bounceType = bounceType;
            bounce.diagnosticCode = diagnosticCode;
            bounce.bounceReason = bounceReason;
            bounce.createdAt = Instant.now();
            bounce.persist();

            LOG.infof("Created EmailBounce record: id=%s, type=%s, email=%s", bounce.id, bounceType, emailAddress);
        });

        // Update EmailDeliveryLog status to BOUNCED (if exists)
        updateDeliveryLogStatus(emailAddress, userId);

        // Check if email should be disabled
        if (shouldDisableEmail(emailAddress)) {
            if (userOpt.isPresent()) {
                disableEmailDelivery(userOpt.get());
            } else {
                LOG.warnf("Cannot disable email for %s - user not found (may be ops alert or system email)",
                        emailAddress);
            }
        }
    }

    /**
     * Updates EmailDeliveryLog status to BOUNCED for the most recent delivery to this email address.
     *
     * @param emailAddress
     *            the bounced email address
     * @param userId
     *            the user ID (optional, for faster lookup)
     */
    private void updateDeliveryLogStatus(String emailAddress, UUID userId) {
        QuarkusTransaction.requiringNew().run(() -> {
            // Find most recent delivery log for this email
            List<EmailDeliveryLog> logs;
            if (userId != null) {
                logs = EmailDeliveryLog.findByUserId(userId);
            } else {
                logs = EmailDeliveryLog.find("emailAddress = ?1 ORDER BY createdAt DESC", emailAddress).page(0, 1)
                        .list();
            }

            if (!logs.isEmpty()) {
                EmailDeliveryLog log = logs.get(0);
                log.status = DeliveryStatus.BOUNCED;
                log.errorMessage = "Bounce detected - DSN status indicates delivery failure";
                log.persist();

                LOG.infof("Updated EmailDeliveryLog %s status to BOUNCED", log.id);
            } else {
                LOG.debugf("No EmailDeliveryLog found for bounced email %s", emailAddress);
            }
        });
    }

    /**
     * Checks if email delivery should be disabled based on bounce threshold.
     *
     * <p>
     * <b>Threshold Rules:</b>
     * <ul>
     * <li>Any HARD bounce → Disable immediately</li>
     * <li>5 consecutive SOFT bounces within 30 days → Disable</li>
     * <li>Less than 5 consecutive SOFT → Keep enabled</li>
     * </ul>
     *
     * @param emailAddress
     *            the email address to check
     * @return true if email should be disabled, false otherwise
     */
    public boolean shouldDisableEmail(String emailAddress) {
        List<EmailBounce> recentBounces = EmailBounce.findRecentByEmail(emailAddress, BOUNCE_LOOKBACK_DAYS);

        if (recentBounces.isEmpty()) {
            return false;
        }

        // Check for any hard bounces (immediate disable)
        boolean hasHardBounce = recentBounces.stream().anyMatch(b -> b.bounceType == BounceType.HARD);

        if (hasHardBounce) {
            LOG.infof("Email %s has hard bounce - disabling email delivery", emailAddress);
            return true;
        }

        // Count consecutive soft bounces from most recent
        int consecutiveSoft = 0;
        for (EmailBounce bounce : recentBounces) {
            if (bounce.bounceType == BounceType.SOFT) {
                consecutiveSoft++;
            } else {
                break; // Reset count on non-soft bounce
            }
        }

        if (consecutiveSoft >= SOFT_BOUNCE_THRESHOLD) {
            LOG.infof("Email %s has %d consecutive soft bounces - disabling email delivery", emailAddress,
                    consecutiveSoft);
            return true;
        }

        LOG.debugf("Email %s has %d consecutive soft bounces (threshold: %d) - keeping enabled", emailAddress,
                consecutiveSoft, SOFT_BOUNCE_THRESHOLD);
        return false;
    }

    /**
     * Disables email delivery for a user by setting emailDisabled flag.
     *
     * <p>
     * This prevents NotificationService from queueing future emails to this user's address. The flag can be manually
     * cleared by admin if user updates email address or resolves deliverability issues.
     *
     * @param user
     *            the user to disable email delivery for
     */
    public void disableEmailDelivery(User user) {
        if (user == null) {
            LOG.warnf("Cannot disable email delivery - user is null");
            return;
        }

        if (user.emailDisabled) {
            LOG.debugf("Email delivery already disabled for user %s", user.id);
            return;
        }

        QuarkusTransaction.requiringNew().run(() -> {
            // Reload user to avoid detached entity issue with nested transactions
            User managedUser = User.findById(user.id);
            if (managedUser != null) {
                managedUser.emailDisabled = true;
                managedUser.updatedAt = Instant.now();
                managedUser.persist();

                LOG.warnf("DISABLED EMAIL DELIVERY: userId=%s, email=%s, reason=bounce_threshold_exceeded",
                        managedUser.id, managedUser.email);
            }
        });

        // TODO: Send notification to user explaining email disable + instructions to update email
        // TODO: Emit metric for monitoring: email.disabled_users.total
    }

    /**
     * Processes all pending bounces (called by InboundEmailJob).
     *
     * <p>
     * This method is called by the InboundEmailProcessor after detecting bounce emails in the IMAP inbox. It serves as
     * the entry point for bounce processing workflow.
     *
     * <p>
     * <b>Note:</b> The actual bounce parsing and recording is handled by InboundEmailProcessor which calls
     * parseBounceEmail() and recordBounce() directly. This method is reserved for future batch processing if needed.
     */
    public void processBounces() {
        LOG.debugf("processBounces() called - actual processing handled by InboundEmailProcessor");
        // Placeholder for future batch bounce processing logic
    }
}
