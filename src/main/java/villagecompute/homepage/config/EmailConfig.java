package villagecompute.homepage.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.regex.Pattern;

/**
 * Email configuration and validation utilities for Village Homepage (Feature F14.3).
 *
 * <p>
 * Provides centralized email address validation, reply-to address generation for marketplace messaging, and sender name
 * configuration. All email-related utilities follow RFC 5322 email address specification.
 *
 * <p>
 * <b>Email Address Validation:</b> Validates email addresses using a simplified RFC 5322-compatible regex. The
 * validation is intentionally permissive to support international email addresses while blocking obvious typos.
 *
 * <p>
 * <b>Reply-To Address Generation:</b> For marketplace email relay (Feature F12.6), generates unique reply-to addresses
 * in the format: {@code homepage-{type}-{id}@villagecompute.com}
 *
 * <p>
 * Examples:
 * <ul>
 * <li>{@code homepage-listing-12345@villagecompute.com} - Reply to listing #12345</li>
 * <li>{@code homepage-inquiry-67890@villagecompute.com} - Reply to inquiry #67890</li>
 * <li>{@code homepage-message-11223@villagecompute.com} - Reply to message #11223</li>
 * </ul>
 *
 * <p>
 * <b>Sender Name Configuration:</b> Provides platform-wide sender name for email display (e.g., "Village Homepage
 * &lt;noreply@villagecompute.com&gt;").
 *
 * <p>
 * <b>Configuration Properties:</b>
 * <ul>
 * <li>{@code email.notifications.from} - Default sender email address</li>
 * <li>{@code email.notifications.platform-name} - Platform name for display</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F14.3: Email Communication</li>
 * <li>F12.6: Marketplace Email Relay</li>
 * <li>P14: Email address validation and security</li>
 * </ul>
 *
 * @see EmailService for generic email sending
 * @see EmailNotificationService for domain-specific notifications
 */
@ApplicationScoped
public class EmailConfig {

    /**
     * Simplified RFC 5322-compatible email address regex.
     *
     * <p>
     * Pattern: {@code [local-part]@[domain].[tld]}
     * <ul>
     * <li>Local part: Alphanumeric, dots, hyphens, underscores, plus signs (1-64 chars)</li>
     * <li>Domain: Alphanumeric, dots, hyphens (1-253 chars)</li>
     * <li>TLD: Alphabetic characters only (2-24 chars)</li>
     * </ul>
     *
     * <p>
     * This is intentionally permissive to support international email addresses. For stricter validation, use external
     * email verification services.
     */
    private static final Pattern EMAIL_PATTERN = Pattern
            .compile("^[A-Za-z0-9+_.-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}$");

    @ConfigProperty(
            name = "email.notifications.from")
    String fromEmail;

    @ConfigProperty(
            name = "email.notifications.platform-name")
    String platformName;

    /**
     * Validates an email address using simplified RFC 5322 regex.
     *
     * <p>
     * Checks for:
     * <ul>
     * <li>Non-null, non-blank address</li>
     * <li>Valid format: {@code local@domain.tld}</li>
     * <li>Local part length ≤ 64 characters</li>
     * <li>Domain part length ≤ 253 characters</li>
     * <li>TLD length 2-24 characters</li>
     * </ul>
     *
     * <p>
     * <b>Examples:</b>
     * <ul>
     * <li>{@code isValidEmail("user@example.com")} → {@code true}</li>
     * <li>{@code isValidEmail("user+tag@example.co.uk")} → {@code true}</li>
     * <li>{@code isValidEmail("invalid")} → {@code false}</li>
     * <li>{@code isValidEmail("@example.com")} → {@code false}</li>
     * <li>{@code isValidEmail(null)} → {@code false}</li>
     * </ul>
     *
     * @param email
     *            email address to validate
     * @return {@code true} if email is valid, {@code false} otherwise
     */
    public boolean isValidEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email.trim()).matches();
    }

    /**
     * Validates an email address and throws {@link IllegalArgumentException} if invalid.
     *
     * <p>
     * Use this method for fail-fast validation before sending emails. The exception message includes the invalid email
     * address for debugging.
     *
     * <p>
     * <b>Example:</b>
     *
     * <pre>{@code
     * try {
     *     emailConfig.validateEmailOrThrow(userEmail);
     *     emailService.sendEmail(userEmail, subject, body);
     * } catch (IllegalArgumentException e) {
     *     LOG.errorf("Invalid email address: %s", e.getMessage());
     *     return Response.status(400).entity("Invalid email address").build();
     * }
     * }</pre>
     *
     * @param email
     *            email address to validate
     * @throws IllegalArgumentException
     *             if email is invalid (includes email address in message)
     */
    public void validateEmailOrThrow(String email) {
        if (!isValidEmail(email)) {
            throw new IllegalArgumentException("Invalid email address: " + email);
        }
    }

    /**
     * Generates a reply-to email address for marketplace messaging (Feature F12.6).
     *
     * <p>
     * Format: {@code homepage-{type}-{id}@{domain}}
     *
     * <p>
     * The domain is extracted from the configured sender email address ({@code email.notifications.from}). For example,
     * if {@code fromEmail} is {@code noreply@villagecompute.com}, the domain is {@code villagecompute.com}.
     *
     * <p>
     * <b>Supported Types:</b>
     * <ul>
     * <li>{@code listing} - Reply to marketplace listing</li>
     * <li>{@code inquiry} - Reply to listing inquiry</li>
     * <li>{@code message} - Reply to direct message</li>
     * </ul>
     *
     * <p>
     * <b>Examples:</b>
     * <ul>
     * <li>{@code generateReplyToAddress("listing", 12345L)} → {@code homepage-listing-12345@villagecompute.com}</li>
     * <li>{@code generateReplyToAddress("inquiry", 67890L)} → {@code homepage-inquiry-67890@villagecompute.com}</li>
     * <li>{@code generateReplyToAddress("message", 11223L)} → {@code homepage-message-11223@villagecompute.com}</li>
     * </ul>
     *
     * <p>
     * <b>Inbound Email Processing:</b> When replies are received at these addresses, the IMAP polling job (Feature
     * I5.T3) parses the address to determine the type and ID, then routes the message to the appropriate handler.
     *
     * @param type
     *            message type (listing, inquiry, message)
     * @param id
     *            entity ID (listing ID, inquiry ID, message ID)
     * @return reply-to email address in format {@code homepage-{type}-{id}@{domain}}
     * @throws IllegalArgumentException
     *             if type is null/blank or ID is null
     * @throws IllegalStateException
     *             if fromEmail is not configured or doesn't contain '@'
     */
    public String generateReplyToAddress(String type, Long id) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Reply-to address type cannot be null or blank");
        }
        if (id == null) {
            throw new IllegalArgumentException("Reply-to address ID cannot be null");
        }

        // Extract domain from fromEmail
        int atIndex = fromEmail.indexOf('@');
        if (atIndex < 0) {
            throw new IllegalStateException("Invalid fromEmail configuration: " + fromEmail + " (missing '@' symbol)");
        }

        String domain = fromEmail.substring(atIndex + 1);
        return String.format("homepage-%s-%d@%s", type.toLowerCase(), id, domain);
    }

    /**
     * Returns the platform name for email display (e.g., "Village Homepage").
     *
     * <p>
     * Used for:
     * <ul>
     * <li>Email "From" header: "Village Homepage &lt;noreply@villagecompute.com&gt;"</li>
     * <li>Custom X-Platform header for email tracking</li>
     * <li>Email footer branding</li>
     * </ul>
     *
     * @return platform name from configuration
     */
    public String getPlatformName() {
        return platformName;
    }

    /**
     * Returns the default sender email address (e.g., "noreply@villagecompute.com").
     *
     * <p>
     * Used for:
     * <ul>
     * <li>Default "From" address for all outbound emails</li>
     * <li>Reply-to domain extraction</li>
     * <li>Bounce email handling</li>
     * </ul>
     *
     * @return sender email address from configuration
     */
    public String getFromEmail() {
        return fromEmail;
    }
}
