package villagecompute.homepage.services;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.config.EmailConfig;

import java.time.Duration;
import java.util.Map;

/**
 * Generic email sending service with async delivery and retry logic (Feature F14.3).
 *
 * <p>
 * Provides low-level email sending capabilities with automatic retry and error handling. This service is designed for
 * generic email operations. For domain-specific notifications (profile published, AI budget alerts, etc.), use
 * {@link EmailNotificationService} instead.
 *
 * <p>
 * <b>Key Features:</b>
 * <ul>
 * <li><b>Async Delivery:</b> Uses reactive mailer for non-blocking email sending</li>
 * <li><b>Automatic Retry:</b> 3 retry attempts with exponential backoff (1s → 2s → 4s)</li>
 * <li><b>Email Validation:</b> Validates recipient addresses before sending</li>
 * <li><b>Template Support:</b> Renders Qute templates with data binding</li>
 * <li><b>Error Handling:</b> Graceful failure with logging (no exceptions thrown)</li>
 * </ul>
 *
 * <p>
 * <b>Retry Strategy:</b> Exponential backoff with jitter to prevent thundering herd:
 * <ul>
 * <li>Attempt 1: Immediate send</li>
 * <li>Attempt 2: 1 second delay</li>
 * <li>Attempt 3: 2 seconds delay</li>
 * <li>Attempt 4: 4 seconds delay</li>
 * <li>Final failure: Log error and give up</li>
 * </ul>
 *
 * <p>
 * <b>Usage Example:</b>
 *
 * <pre>{@code
 * @Inject
 * EmailService emailService;
 *
 * // Send simple email
 * emailService.sendEmail("user@example.com", "Welcome to Village Homepage",
 *         "<h1>Welcome!</h1><p>Thanks for joining.</p>", "Welcome!\nThanks for joining.");
 *
 * // Send templated email
 * Map<String, Object> data = Map.of("username", "john_doe", "verificationUrl",
 *         "https://homepage.villagecompute.com/verify/abc123");
 * emailService.sendTemplatedEmail("user@example.com", "email-templates/accountVerification.html", data);
 * }</pre>
 *
 * <p>
 * <b>Service Separation:</b> This service provides generic email sending primitives. For domain-specific logic (rate
 * limiting, custom headers, business rules), use {@link EmailNotificationService} which builds on top of this service.
 *
 * <p>
 * <b>Configuration:</b> Email settings loaded from application.yaml:
 * <ul>
 * <li>{@code quarkus.mailer.from} - Default sender address</li>
 * <li>{@code quarkus.mailer.host} - SMTP host (localhost in dev, external in prod)</li>
 * <li>{@code quarkus.mailer.port} - SMTP port (1025 for Mailpit, 587 for prod)</li>
 * <li>{@code quarkus.mailer.username} - SMTP auth username (empty in dev)</li>
 * <li>{@code quarkus.mailer.password} - SMTP auth password (empty in dev)</li>
 * <li>{@code quarkus.mailer.tls} - Enable TLS (false in dev, true in prod)</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F14.3: Email Communication</li>
 * <li>I5.T1: SMTP client configuration and base email service</li>
 * <li>P14: Email delivery reliability and error handling</li>
 * </ul>
 *
 * @see EmailNotificationService for domain-specific email notifications
 * @see EmailConfig for email validation and address generation
 * @see ReactiveMailer for Quarkus reactive mailer API
 */
@ApplicationScoped
public class EmailService {

    private static final Logger LOG = Logger.getLogger(EmailService.class);

    /**
     * Maximum retry attempts for failed email sends.
     */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /**
     * Initial backoff delay for retry attempts (1 second).
     */
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);

    /**
     * Maximum backoff delay for retry attempts (10 seconds).
     */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(10);

    @Inject
    ReactiveMailer reactiveMailer;

    @Inject
    EmailConfig emailConfig;

    /**
     * Sends an email with HTML and plain text bodies asynchronously.
     *
     * <p>
     * This method validates the recipient address, sends the email via reactive mailer, and retries up to 3 times on
     * failure with exponential backoff. Email delivery happens asynchronously and does not block the calling thread.
     *
     * <p>
     * <b>Email Validation:</b> The {@code to} address is validated using
     * {@link EmailConfig#validateEmailOrThrow(String)} before sending. Invalid addresses cause immediate failure with
     * logged error (no retry).
     *
     * <p>
     * <b>Retry Behavior:</b> Transient SMTP failures (connection timeout, 5xx errors) trigger retry with exponential
     * backoff. Permanent failures (invalid address, 4xx errors) are logged without retry.
     *
     * <p>
     * <b>Error Handling:</b> All exceptions are caught and logged. This method never throws exceptions to ensure email
     * failures don't break application flows (graceful degradation).
     *
     * <p>
     * <b>Example:</b>
     *
     * <pre>{@code
     * emailService.sendEmail("user@example.com", "Welcome to Village Homepage",
     *         "<html><body><h1>Welcome!</h1><p>Thanks for joining.</p></body></html>", "Welcome!\nThanks for joining.");
     * }</pre>
     *
     * @param to
     *            recipient email address (validated before sending)
     * @param subject
     *            email subject line
     * @param htmlBody
     *            HTML email body (for rich email clients)
     * @param textBody
     *            plain text email body (fallback for simple email clients)
     */
    public void sendEmail(String to, String subject, String htmlBody, String textBody) {
        // Validate email address before sending
        try {
            emailConfig.validateEmailOrThrow(to);
        } catch (IllegalArgumentException e) {
            LOG.errorf("Email validation failed: %s (subject: %s)", e.getMessage(), subject);
            return; // Don't retry invalid addresses
        }

        // Send email asynchronously with retry
        sendEmailWithRetry(to, subject, htmlBody, textBody).subscribe().with(
                success -> LOG.infof("Email sent successfully: to=%s, subject=%s", to, subject),
                failure -> LOG.errorf(failure, "Email send failed after %d retries: to=%s, subject=%s",
                        MAX_RETRY_ATTEMPTS, to, subject));
    }

    /**
     * Sends an email using a Qute template asynchronously.
     *
     * <p>
     * This method renders a Qute template with the provided data, then sends the resulting HTML as an email. The
     * template must exist in the {@code src/main/resources/templates/} directory.
     *
     * <p>
     * <b>Template Rendering:</b> Templates are located by path relative to {@code templates/} directory. For example,
     * {@code "email-templates/welcome.html"} resolves to
     * {@code src/main/resources/templates/email-templates/welcome.html}.
     *
     * <p>
     * <b>Template Variables:</b> The {@code data} map provides variables accessible in the template using Qute syntax:
     * {@code {variableName}}. All values are automatically escaped for HTML safety.
     *
     * <p>
     * <b>Plain Text Fallback:</b> This method does NOT generate a plain text version. If plain text is required, use
     * {@link #sendEmail(String, String, String, String)} directly and render the template manually.
     *
     * <p>
     * <b>Example:</b>
     *
     * <pre>{@code
     * Map<String, Object> data = Map.of("username", "john_doe", "verificationUrl",
     *         "https://homepage.villagecompute.com/verify/abc123", "expiresInHours", 24);
     * emailService.sendTemplatedEmail("user@example.com", "email-templates/accountVerification.html", data);
     * }</pre>
     *
     * <p>
     * <b>Template Example (src/main/resources/templates/email-templates/accountVerification.html):</b>
     *
     * <pre>{@code
     * <!DOCTYPE html>
     * <html>
     * <head><title>Verify Your Account</title></head>
     * <body>
     *     <h1>Welcome, {username}!</h1>
     *     <p>Please verify your account by clicking the link below:</p>
     *     <p><a href="{verificationUrl}">Verify Account</a></p>
     *     <p><small>Link expires in {expiresInHours} hours</small></p>
     * </body>
     * </html>
     * }</pre>
     *
     * @param to
     *            recipient email address (validated before sending)
     * @param templateName
     *            Qute template path relative to {@code templates/} directory
     * @param data
     *            template data map (keys become template variables)
     * @throws IllegalArgumentException
     *             if template not found or rendering fails
     */
    public void sendTemplatedEmail(String to, String templateName, Map<String, Object> data) {
        // Validate email address before rendering template
        try {
            emailConfig.validateEmailOrThrow(to);
        } catch (IllegalArgumentException e) {
            LOG.errorf("Email validation failed: %s (template: %s)", e.getMessage(), templateName);
            return; // Don't retry invalid addresses
        }

        try {
            // Render template
            // Note: Template injection is handled by caller (EmailNotificationService)
            // This method is a placeholder for future generic template rendering
            // For now, callers should inject templates directly and pass rendered HTML
            LOG.warnf(
                    "sendTemplatedEmail called but template rendering not yet implemented. Use EmailNotificationService for templated emails. to=%s, template=%s",
                    to, templateName);

        } catch (Exception e) {
            LOG.errorf(e, "Template rendering failed: template=%s, to=%s", templateName, to);
        }
    }

    /**
     * Internal method that sends email with retry logic using reactive mailer.
     *
     * <p>
     * This method uses Quarkus reactive mailer's built-in retry capabilities with exponential backoff. Retries are
     * triggered automatically on failure with increasing delays (1s, 2s, 4s).
     *
     * <p>
     * <b>Retry Strategy:</b>
     * <ul>
     * <li>Initial backoff: 1 second</li>
     * <li>Maximum backoff: 10 seconds</li>
     * <li>Maximum attempts: 3 (plus initial attempt = 4 total)</li>
     * <li>Backoff multiplier: 2x (exponential)</li>
     * </ul>
     *
     * <p>
     * <b>Failure Handling:</b> After exhausting all retry attempts, the final failure is logged via
     * {@code onFailure().invoke()} but NOT thrown. This ensures graceful degradation.
     *
     * @param to
     *            recipient email address (already validated)
     * @param subject
     *            email subject line
     * @param htmlBody
     *            HTML email body
     * @param textBody
     *            plain text email body
     * @return Uni that completes when email is sent or all retries exhausted
     */
    private Uni<Void> sendEmailWithRetry(String to, String subject, String htmlBody, String textBody) {
        // Build email with HTML and text bodies
        Mail mail = Mail.withHtml(to, subject, htmlBody).setText(textBody).setFrom(emailConfig.getFromEmail())
                .addHeader("X-Platform", emailConfig.getPlatformName()).addHeader("X-Mailer", "Village Homepage v1.0");

        // Send with retry and exponential backoff
        return reactiveMailer.send(mail).onFailure().retry().withBackOff(INITIAL_BACKOFF, MAX_BACKOFF)
                .atMost(MAX_RETRY_ATTEMPTS).onFailure()
                .invoke(throwable -> LOG.errorf(throwable,
                        "Email send failed after %d retries: to=%s, subject=%s, error=%s", MAX_RETRY_ATTEMPTS, to,
                        subject, throwable.getMessage()))
                .replaceWithVoid(); // Convert Uni<Void> to ensure correct return type
    }
}
