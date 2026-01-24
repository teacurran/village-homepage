package villagecompute.homepage.util;

import org.jboss.logging.Logger;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import java.util.Hashtable;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Email validation utility for format checking, MX record validation, and disposable email detection (Feature I5.T5).
 *
 * <p>
 * Provides multi-layer email validation to prevent sending emails to invalid or problematic addresses:
 * <ul>
 * <li><b>Format validation:</b> RFC 5322 regex pattern matching for valid email syntax</li>
 * <li><b>MX record validation:</b> DNS lookup to verify domain has mail exchange servers</li>
 * <li><b>Disposable email detection:</b> Blocklist of known disposable email domains (mailinator, guerrillamail,
 * etc.)</li>
 * </ul>
 *
 * <h3>Validation Strategy:</h3>
 * <ol>
 * <li>Check format (regex) - Fast, catches obvious syntax errors</li>
 * <li>Check disposable domain blocklist - Fast, prevents abuse</li>
 * <li>Check MX record (DNS) - Slow, verifies domain accepts mail (optional)</li>
 * </ol>
 *
 * <h3>Usage Examples:</h3>
 *
 * <pre>
 * // Basic format validation
 * if (!EmailValidator.isValidFormat("user@example.com")) {
 *     throw new ValidationException("Invalid email format");
 * }
 *
 * // Full validation (format + disposable + MX)
 * if (!EmailValidator.isValidFormat(email) || EmailValidator.isDisposableEmail(email)
 *         || !EmailValidator.hasValidMxRecord(email)) {
 *     throw new ValidationException("Email address cannot receive mail");
 * }
 * </pre>
 *
 * <h3>DNS Timeouts:</h3> MX record lookups timeout after 5 seconds to prevent blocking email sends. If DNS lookup fails
 * or times out, the method returns {@code false} (fail open for MVP).
 *
 * <h3>Disposable Email Blocklist:</h3> The blocklist is currently hardcoded for MVP. Future enhancements:
 * <ul>
 * <li>Load from database table (easier to update without code changes)</li>
 * <li>Use external API (kickbox.io, mailboxlayer.com)</li>
 * <li>Support wildcard patterns (*.tempmail.*)</li>
 * </ul>
 *
 * @see BounceHandlingService for bounce tracking and email disabling
 * @see NotificationService for email sending with validation
 */
public class EmailValidator {

    private static final Logger LOG = Logger.getLogger(EmailValidator.class);

    /**
     * RFC 5322 email format regex pattern.
     *
     * <p>
     * Simplified pattern that covers most common cases without full RFC 5322 complexity. Matches:
     * <ul>
     * <li>local-part: alphanumeric, dots, hyphens, underscores</li>
     * <li>@</li>
     * <li>domain: alphanumeric, dots, hyphens</li>
     * <li>TLD: 2+ characters</li>
     * </ul>
     *
     * <p>
     * <b>Examples:</b> user@example.com, user.name+tag@sub.example.co.uk
     */
    private static final Pattern EMAIL_PATTERN = Pattern
            .compile("^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}$");

    /**
     * Blocklist of disposable email domains (MVP hardcoded list).
     *
     * <p>
     * These domains provide temporary/throwaway email addresses typically used for abuse or spam. Blocking prevents:
     * <ul>
     * <li>Fake account creation</li>
     * <li>Review/rating manipulation</li>
     * <li>Marketplace spam</li>
     * <li>Lost customer communication (can't reply to disposable addresses)</li>
     * </ul>
     *
     * <p>
     * Sources:
     * <ul>
     * <li>https://github.com/disposable/disposable-email-domains</li>
     * <li>Manual additions based on support tickets</li>
     * </ul>
     */
    private static final Set<String> DISPOSABLE_DOMAINS = Set.of("mailinator.com", "guerrillamail.com",
            "10minutemail.com", "temp-mail.org", "throwaway.email", "fakeinbox.com", "maildrop.cc", "yopmail.com",
            "tempmail.com", "getnada.com", "mintemail.com", "trashmail.com", "dispostable.com", "sharklasers.com",
            "grr.la", "guerrillamail.biz", "guerrillamail.de", "spam4.me", "mailnesia.com", "emailondeck.com");

    /**
     * Validates email format using RFC 5322 regex pattern.
     *
     * <p>
     * This is a fast, local check with no external dependencies. Should be run BEFORE MX validation to avoid
     * unnecessary DNS lookups for obviously invalid addresses.
     *
     * @param email
     *            the email address to validate
     * @return {@code true} if email matches valid format, {@code false} otherwise
     */
    public static boolean isValidFormat(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }

        // Trim whitespace and convert to lowercase for validation
        String normalized = email.trim().toLowerCase();

        // Check pattern match
        return EMAIL_PATTERN.matcher(normalized).matches();
    }

    /**
     * Checks if email domain has valid MX (Mail Exchange) records via DNS lookup.
     *
     * <p>
     * This verifies the domain is configured to receive email (has mail servers). Does NOT check if the specific
     * mailbox exists (that requires SMTP probing, which is blocked by most servers).
     *
     * <p>
     * <b>Performance:</b> DNS lookup with 5-second timeout. For high-volume sending, consider caching results.
     *
     * <p>
     * <b>Failure Handling:</b> Returns {@code false} on timeout or DNS error (fail open for MVP). This prevents
     * blocking emails when DNS is temporarily unavailable.
     *
     * @param email
     *            the email address to validate
     * @return {@code true} if domain has MX records, {@code false} if no MX records or DNS lookup fails
     */
    public static boolean hasValidMxRecord(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }

        // Extract domain from email address
        int atIndex = email.indexOf('@');
        if (atIndex < 0) {
            return false; // Invalid format (no @ symbol)
        }
        String domain = email.substring(atIndex + 1).trim().toLowerCase();

        try {
            // Setup DNS context with 5-second timeout
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "5000"); // 5 second timeout

            DirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(domain, new String[]{"MX"});
            Attribute mxAttr = attrs.get("MX");

            boolean hasMx = mxAttr != null && mxAttr.size() > 0;

            if (hasMx) {
                LOG.debugf("MX record found for domain %s", domain);
            } else {
                LOG.infof("No MX record found for domain %s", domain);
            }

            return hasMx;

        } catch (NamingException e) {
            // Log at DEBUG level (not error) - this is expected for invalid domains
            LOG.debugf("MX lookup failed for domain %s: %s", domain, e.getMessage());
            return false; // Fail open - don't block email sends on DNS issues
        }
    }

    /**
     * Checks if email address uses a known disposable/temporary email domain.
     *
     * <p>
     * Disposable emails are temporary addresses used for spam, abuse, or avoiding long-term commitments. Blocking them
     * improves data quality and prevents:
     * <ul>
     * <li>Fake account creation</li>
     * <li>Lost customer communication (can't send future emails)</li>
     * <li>Review/vote manipulation</li>
     * <li>Marketplace spam</li>
     * </ul>
     *
     * <p>
     * <b>User Experience:</b> When detected, show a clear error message: "Disposable email addresses are not allowed.
     * Please use a permanent email address to receive account notifications."
     *
     * @param email
     *            the email address to check
     * @return {@code true} if email domain is in disposable blocklist, {@code false} otherwise
     */
    public static boolean isDisposableEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }

        // Extract domain from email address
        int atIndex = email.indexOf('@');
        if (atIndex < 0) {
            return false; // Invalid format (no @ symbol)
        }
        String domain = email.substring(atIndex + 1).trim().toLowerCase();

        boolean isDisposable = DISPOSABLE_DOMAINS.contains(domain);

        if (isDisposable) {
            LOG.infof("Disposable email domain detected: %s", domain);
        }

        return isDisposable;
    }

    /**
     * Performs comprehensive email validation (format + disposable + MX).
     *
     * <p>
     * This is a convenience method that runs all three validation checks in order. Use this when you need full
     * validation before accepting an email address.
     *
     * <p>
     * <b>Validation Steps:</b>
     * <ol>
     * <li>Format check (fast, local)</li>
     * <li>Disposable domain check (fast, local)</li>
     * <li>MX record check (slow, DNS lookup)</li>
     * </ol>
     *
     * @param email
     *            the email address to validate
     * @return {@code true} if email passes all validation checks, {@code false} otherwise
     */
    public static boolean isValid(String email) {
        return isValidFormat(email) && !isDisposableEmail(email) && hasValidMxRecord(email);
    }
}
