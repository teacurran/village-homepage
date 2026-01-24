package villagecompute.homepage.services;

import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.data.models.EmailDeliveryLog;
import villagecompute.homepage.data.models.ListingFlag;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.data.models.MarketplaceMessage;
import villagecompute.homepage.data.models.NotificationPreferences;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.data.models.UserNotification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Notification service for sending templated emails with user preference checking and rate limiting (Feature I5.T3).
 *
 * <p>
 * Centralized service for all user-facing email notifications. Each notification method:
 * <ol>
 * <li>Checks user notification preferences (opt-in/opt-out)</li>
 * <li>Checks rate limits via RateLimitService</li>
 * <li>Generates unsubscribe token (HMAC-SHA256 signed)</li>
 * <li>Renders HTML and text templates with data binding</li>
 * <li>Queues email delivery via EmailDeliveryLog (async)</li>
 * <li>Creates UserNotification record (in-app notification)</li>
 * </ol>
 *
 * <h3>Notification Types:</h3>
 * <ul>
 * <li><b>Welcome Email:</b> Sent on user account creation</li>
 * <li><b>Listing New Message:</b> Marketplace message notification</li>
 * <li><b>Listing Flagged:</b> Content moderation alert</li>
 * <li><b>Listing Expired:</b> Expiration reminder</li>
 * <li><b>Site Approved:</b> Directory submission approved</li>
 * <li><b>Site Rejected:</b> Directory submission rejected</li>
 * <li><b>Password Reset:</b> Password reset link</li>
 * <li><b>Email Verification:</b> Email verification link</li>
 * <li><b>Notification Digest:</b> Daily digest of in-app notifications</li>
 * <li><b>Admin Flag Review:</b> Ops team moderation alert</li>
 * </ul>
 *
 * <h3>Async Email Delivery:</h3>
 * <p>
 * All emails are queued via {@link EmailDeliveryLog} and processed asynchronously by {@link EmailDeliveryJob}. This
 * ensures user requests never block on SMTP operations.
 *
 * <h3>Rate Limiting:</h3>
 * <p>
 * All notification methods check rate limits before sending. Rate limits are configurable per user tier and action type
 * via {@link RateLimitService}.
 *
 * <h3>Unsubscribe Tokens:</h3>
 * <p>
 * Unsubscribe tokens use HMAC-SHA256 with secret key from config. Token format:
 * {@code base64(userId:notificationType:timestamp:hmac)}. Tokens expire after 30 days.
 *
 * <h3>Error Handling:</h3>
 * <p>
 * All notification methods catch exceptions and log errors without throwing. Email failures should never break user
 * flows (graceful degradation).
 *
 * <h3>Policy References:</h3>
 * <ul>
 * <li>F14.3: Email Communication</li>
 * <li>P14: Rate limiting and consent management</li>
 * <li>I5.T3: Notification service implementation</li>
 * </ul>
 *
 * @see EmailService for actual email sending with retry logic
 * @see EmailDeliveryJob for background processing of queued emails
 * @see NotificationPreferences for user opt-in/opt-out settings
 * @see RateLimitService for rate limiting enforcement
 */
@ApplicationScoped
public class NotificationService {

    private static final Logger LOG = Logger.getLogger(NotificationService.class);

    @Inject
    EmailService emailService;

    @Inject
    RateLimitService rateLimitService;

    @Inject
    NotificationPreferencesService notificationPreferencesService;

    @ConfigProperty(
            name = "email.notifications.from")
    String fromEmail;

    @ConfigProperty(
            name = "email.notifications.platform-name")
    String platformName;

    @ConfigProperty(
            name = "email.notifications.base-url")
    String baseUrl;

    @ConfigProperty(
            name = "email.notifications.ops-alert-email")
    String opsAlertEmail;

    // Template injections (HTML + TXT for each notification type)

    @Inject
    @io.quarkus.qute.Location("email/welcome.html")
    Template welcomeHtml;

    @Inject
    @io.quarkus.qute.Location("email/welcome.txt")
    Template welcomeTxt;

    @Inject
    @io.quarkus.qute.Location("email/listing-new-message.html")
    Template listingNewMessageHtml;

    @Inject
    @io.quarkus.qute.Location("email/listing-new-message.txt")
    Template listingNewMessageTxt;

    @Inject
    @io.quarkus.qute.Location("email/listing-flagged.html")
    Template listingFlaggedHtml;

    @Inject
    @io.quarkus.qute.Location("email/listing-flagged.txt")
    Template listingFlaggedTxt;

    @Inject
    @io.quarkus.qute.Location("email/listing-expired.html")
    Template listingExpiredHtml;

    @Inject
    @io.quarkus.qute.Location("email/listing-expired.txt")
    Template listingExpiredTxt;

    @Inject
    @io.quarkus.qute.Location("email/site-approved.html")
    Template siteApprovedHtml;

    @Inject
    @io.quarkus.qute.Location("email/site-approved.txt")
    Template siteApprovedTxt;

    @Inject
    @io.quarkus.qute.Location("email/site-rejected.html")
    Template siteRejectedHtml;

    @Inject
    @io.quarkus.qute.Location("email/site-rejected.txt")
    Template siteRejectedTxt;

    @Inject
    @io.quarkus.qute.Location("email/password-reset.html")
    Template passwordResetHtml;

    @Inject
    @io.quarkus.qute.Location("email/password-reset.txt")
    Template passwordResetTxt;

    @Inject
    @io.quarkus.qute.Location("email/email-verification.html")
    Template emailVerificationHtml;

    @Inject
    @io.quarkus.qute.Location("email/email-verification.txt")
    Template emailVerificationTxt;

    @Inject
    @io.quarkus.qute.Location("email/notification-digest.html")
    Template notificationDigestHtml;

    @Inject
    @io.quarkus.qute.Location("email/notification-digest.txt")
    Template notificationDigestTxt;

    @Inject
    @io.quarkus.qute.Location("email/admin-flag-review.html")
    Template adminFlagReviewHtml;

    @Inject
    @io.quarkus.qute.Location("email/admin-flag-review.txt")
    Template adminFlagReviewTxt;

    /**
     * Checks if email delivery is enabled for a user.
     *
     * <p>
     * Email delivery can be disabled if the user's email address has excessive bounces:
     * <ul>
     * <li>Hard bounce (5.x.x DSN) - Immediate disable</li>
     * <li>5 consecutive soft bounces (4.x.x DSN) within 30 days - Automatic disable</li>
     * </ul>
     *
     * <p>
     * This check should be performed BEFORE preference and rate limit checks to avoid wasting resources on emails that
     * will never be delivered.
     *
     * @param user
     *            the user to check
     * @return true if email delivery is enabled, false if disabled due to bounces
     */
    private boolean isEmailEnabled(User user) {
        if (user == null) {
            return false;
        }

        if (user.emailDisabled) {
            LOG.infof("Email delivery disabled for user %s (%s) due to bounces, skipping notification", user.id,
                    user.email);
            return false;
        }

        return true;
    }

    /**
     * Sends welcome email to newly registered user.
     *
     * <p>
     * Welcomes user to the platform with quick start guide and profile setup link.
     *
     * @param user
     *            the newly registered user
     */
    public void sendWelcomeEmail(User user) {
        try {
            // Check if email delivery is enabled (bounce protection)
            if (!isEmailEnabled(user)) {
                return;
            }

            // Welcome emails are always sent (no preference check, first-time user)

            // Check rate limit
            RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(
                    user.id.getMostSignificantBits(), null, "email.welcome_notification",
                    RateLimitService.Tier.LOGGED_IN, "/api/auth/signup");

            if (!rateLimitResult.allowed()) {
                LOG.warnf("Rate limit exceeded for user %s, skipping welcome email", user.id);
                return;
            }

            // Generate unsubscribe token (not applicable for welcome, but included for consistency)
            String unsubscribeUrl = baseUrl + "/preferences/notifications";

            // Build template data
            Map<String, Object> data = Map.of("userName", user.displayName, "profileUrl", baseUrl + "/profile/edit",
                    "baseUrl", baseUrl, "unsubscribeUrl", unsubscribeUrl);

            // Render templates
            String subject = "Welcome to " + platformName + "!";
            String htmlBody = welcomeHtml.data(data).render();
            String textBody = welcomeTxt.data(data).render();

            // Queue email delivery
            queueEmailDelivery(user.id, user.email, "welcome", subject, htmlBody, textBody);

            // Create in-app notification
            createUserNotification(user.id, "welcome", "Welcome to " + platformName,
                    "Your account has been created successfully", "/profile/edit");

            LOG.infof("Welcome email queued for user %s", user.id);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to queue welcome email for user %s", user.id);
        }
    }

    /**
     * Sends new message notification to listing owner.
     *
     * <p>
     * Notifies listing owner when someone sends a message about their listing.
     *
     * @param listing
     *            the marketplace listing
     * @param message
     *            the new message
     */
    public void sendListingNewMessageEmail(MarketplaceListing listing, MarketplaceMessage message) {
        try {
            User listingOwner = User.findById(listing.userId);
            if (listingOwner == null) {
                LOG.errorf("User not found for listing %s", listing.id);
                return;
            }

            // Check if email delivery is enabled (bounce protection)
            if (!isEmailEnabled(listingOwner)) {
                return;
            }

            // Use sender name from message (message has fromEmail and fromName)
            String senderName = message.fromName != null ? message.fromName : message.fromEmail;

            // Check preferences
            if (!checkNotificationPreference(listingOwner, "email_listing_messages")) {
                LOG.debugf("User %s opted out of listing message emails", listingOwner.id);
                return;
            }

            // Check rate limit
            RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(
                    listingOwner.id.getMostSignificantBits(), null, "email.listing_notification",
                    RateLimitService.Tier.LOGGED_IN, "/api/marketplace/messages");

            if (!rateLimitResult.allowed()) {
                LOG.warnf("Rate limit exceeded for user %s, skipping listing message email", listingOwner.id);
                return;
            }

            // Generate unsubscribe token
            String unsubscribeToken = notificationPreferencesService.generateUnsubscribeToken(listingOwner,
                    "email_listing_messages");
            String unsubscribeUrl = baseUrl + "/api/notifications/unsubscribe?token=" + unsubscribeToken;

            // Build template data
            String messagePreview = message.body.substring(0, Math.min(100, message.body.length()));
            Map<String, Object> data = Map.of("listingTitle", listing.title, "senderName", senderName, "messagePreview",
                    messagePreview, "replyUrl", baseUrl + "/marketplace/messages/" + message.id, "baseUrl", baseUrl,
                    "unsubscribeUrl", unsubscribeUrl);

            // Render templates
            String subject = "New message about your listing: " + listing.title;
            String htmlBody = listingNewMessageHtml.data(data).render();
            String textBody = listingNewMessageTxt.data(data).render();

            // Queue email delivery
            queueEmailDelivery(listingOwner.id, listingOwner.email, "listing-new-message", subject, htmlBody, textBody);

            // Create in-app notification
            createUserNotification(listingOwner.id, "listing_new_message", "New message on your listing",
                    senderName + " sent you a message", "/marketplace/messages/" + message.id);

            LOG.infof("Listing new message email queued for user %s (listing %s)", listingOwner.id, listing.id);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to queue listing new message email for listing %s",
                    listing != null ? listing.id : "null");
        }
    }

    /**
     * Sends listing flagged notification to listing owner.
     *
     * <p>
     * Notifies listing owner when their listing is flagged for review.
     *
     * @param listing
     *            the flagged marketplace listing
     * @param flag
     *            the flag record
     */
    public void sendListingFlaggedEmail(MarketplaceListing listing, ListingFlag flag) {
        try {
            User listingOwner = User.findById(listing.userId);
            if (listingOwner == null) {
                LOG.errorf("User not found for listing %s", listing.id);
                return;
            }

            // Check if email delivery is enabled (bounce protection)
            if (!isEmailEnabled(listingOwner)) {
                return;
            }

            // Listing flagged notifications are always sent (important moderation alert)

            // Check rate limit
            RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(
                    listingOwner.id.getMostSignificantBits(), null, "email.listing_notification",
                    RateLimitService.Tier.LOGGED_IN, "/api/marketplace/listings/flags");

            if (!rateLimitResult.allowed()) {
                LOG.warnf("Rate limit exceeded for user %s, skipping listing flagged email", listingOwner.id);
                return;
            }

            // Generate unsubscribe token
            String unsubscribeUrl = baseUrl + "/preferences/notifications";

            // Build template data
            Map<String, Object> data = Map.of("listingTitle", listing.title, "flagReason", flag.reason, "flagDetails",
                    flag.details != null ? flag.details : "", "listingUrl",
                    baseUrl + "/marketplace/listing/" + listing.id, "baseUrl", baseUrl, "unsubscribeUrl",
                    unsubscribeUrl);

            // Render templates
            String subject = "Your listing has been flagged for review: " + listing.title;
            String htmlBody = listingFlaggedHtml.data(data).render();
            String textBody = listingFlaggedTxt.data(data).render();

            // Queue email delivery
            queueEmailDelivery(listingOwner.id, listingOwner.email, "listing-flagged", subject, htmlBody, textBody);

            // Create in-app notification
            createUserNotification(listingOwner.id, "listing_flagged", "Listing flagged for review",
                    "Your listing '" + listing.title + "' has been flagged", "/marketplace/listing/" + listing.id);

            LOG.infof("Listing flagged email queued for user %s (listing %s)", listingOwner.id, listing.id);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to queue listing flagged email for listing %s",
                    listing != null ? listing.id : "null");
        }
    }

    /**
     * Sends listing expired notification to listing owner.
     *
     * <p>
     * Notifies listing owner when their listing expires and needs renewal.
     *
     * @param listing
     *            the expired marketplace listing
     */
    public void sendListingExpiredEmail(MarketplaceListing listing) {
        try {
            User listingOwner = User.findById(listing.userId);
            if (listingOwner == null) {
                LOG.errorf("User not found for listing %s", listing.id);
                return;
            }

            // Check if email delivery is enabled (bounce protection)
            if (!isEmailEnabled(listingOwner)) {
                return;
            }

            // Listing expiration notifications are always sent (important business alert)

            // Check rate limit
            RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(
                    listingOwner.id.getMostSignificantBits(), null, "email.listing_notification",
                    RateLimitService.Tier.LOGGED_IN, "/api/marketplace/listings/expired");

            if (!rateLimitResult.allowed()) {
                LOG.warnf("Rate limit exceeded for user %s, skipping listing expired email", listingOwner.id);
                return;
            }

            // Generate unsubscribe token
            String unsubscribeUrl = baseUrl + "/preferences/notifications";

            // Build template data
            Map<String, Object> data = Map.of("listingTitle", listing.title, "renewUrl",
                    baseUrl + "/marketplace/listing/" + listing.id + "/renew", "baseUrl", baseUrl, "unsubscribeUrl",
                    unsubscribeUrl);

            // Render templates
            String subject = "Your listing has expired: " + listing.title;
            String htmlBody = listingExpiredHtml.data(data).render();
            String textBody = listingExpiredTxt.data(data).render();

            // Queue email delivery
            queueEmailDelivery(listingOwner.id, listingOwner.email, "listing-expired", subject, htmlBody, textBody);

            // Create in-app notification
            createUserNotification(listingOwner.id, "listing_expired", "Listing expired",
                    "Your listing '" + listing.title + "' has expired",
                    "/marketplace/listing/" + listing.id + "/renew");

            LOG.infof("Listing expired email queued for user %s (listing %s)", listingOwner.id, listing.id);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to queue listing expired email for listing %s",
                    listing != null ? listing.id : "null");
        }
    }

    /**
     * Sends site approved notification to site submitter.
     *
     * <p>
     * Notifies user when their directory site submission is approved and published.
     *
     * @param site
     *            the approved directory site
     */
    public void sendSiteApprovedEmail(DirectorySite site) {
        try {
            User submitter = User.findById(site.submittedByUserId);
            if (submitter == null) {
                LOG.errorf("User not found for site %s", site.id);
                return;
            }

            // Check if email delivery is enabled (bounce protection)
            if (!isEmailEnabled(submitter)) {
                return;
            }

            // Check preferences
            if (!checkNotificationPreference(submitter, "email_site_approved")) {
                LOG.debugf("User %s opted out of site approved emails", submitter.id);
                return;
            }

            // Check rate limit
            RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(
                    submitter.id.getMostSignificantBits(), null, "email.directory_notification",
                    RateLimitService.Tier.LOGGED_IN, "/api/directory/sites/approved");

            if (!rateLimitResult.allowed()) {
                LOG.warnf("Rate limit exceeded for user %s, skipping site approved email", submitter.id);
                return;
            }

            // Generate unsubscribe token
            String unsubscribeToken = notificationPreferencesService.generateUnsubscribeToken(submitter,
                    "email_site_approved");
            String unsubscribeUrl = baseUrl + "/api/notifications/unsubscribe?token=" + unsubscribeToken;

            // Build template data
            Map<String, Object> data = Map.of("siteTitle", site.title, "siteUrl", site.url, "directoryUrl",
                    baseUrl + "/directory/site/" + site.id, "baseUrl", baseUrl, "unsubscribeUrl", unsubscribeUrl);

            // Render templates
            String subject = "Your site has been approved: " + site.title;
            String htmlBody = siteApprovedHtml.data(data).render();
            String textBody = siteApprovedTxt.data(data).render();

            // Queue email delivery
            queueEmailDelivery(submitter.id, submitter.email, "site-approved", subject, htmlBody, textBody);

            // Create in-app notification
            createUserNotification(submitter.id, "site_approved", "Site approved",
                    "Your site '" + site.title + "' has been approved", "/directory/site/" + site.id);

            LOG.infof("Site approved email queued for user %s (site %s)", submitter.id, site.id);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to queue site approved email for site %s", site != null ? site.id : "null");
        }
    }

    /**
     * Sends site rejected notification to site submitter.
     *
     * <p>
     * Notifies user when their directory site submission is rejected with reason.
     *
     * @param site
     *            the rejected directory site
     * @param reason
     *            rejection reason
     */
    public void sendSiteRejectedEmail(DirectorySite site, String reason) {
        try {
            User submitter = User.findById(site.submittedByUserId);
            if (submitter == null) {
                LOG.errorf("User not found for site %s", site.id);
                return;
            }

            // Check if email delivery is enabled (bounce protection)
            if (!isEmailEnabled(submitter)) {
                return;
            }

            // Check preferences
            if (!checkNotificationPreference(submitter, "email_site_rejected")) {
                LOG.debugf("User %s opted out of site rejected emails", submitter.id);
                return;
            }

            // Check rate limit
            RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(
                    submitter.id.getMostSignificantBits(), null, "email.directory_notification",
                    RateLimitService.Tier.LOGGED_IN, "/api/directory/sites/rejected");

            if (!rateLimitResult.allowed()) {
                LOG.warnf("Rate limit exceeded for user %s, skipping site rejected email", submitter.id);
                return;
            }

            // Generate unsubscribe token
            String unsubscribeToken = notificationPreferencesService.generateUnsubscribeToken(submitter,
                    "email_site_rejected");
            String unsubscribeUrl = baseUrl + "/api/notifications/unsubscribe?token=" + unsubscribeToken;

            // Build template data
            Map<String, Object> data = Map.of("siteTitle", site.title, "siteUrl", site.url, "rejectionReason", reason,
                    "submitUrl", baseUrl + "/directory/submit", "baseUrl", baseUrl, "unsubscribeUrl", unsubscribeUrl);

            // Render templates
            String subject = "Your site submission was not approved: " + site.title;
            String htmlBody = siteRejectedHtml.data(data).render();
            String textBody = siteRejectedTxt.data(data).render();

            // Queue email delivery
            queueEmailDelivery(submitter.id, submitter.email, "site-rejected", subject, htmlBody, textBody);

            // Create in-app notification
            createUserNotification(submitter.id, "site_rejected", "Site submission rejected",
                    "Your site '" + site.title + "' was not approved", "/directory/submit");

            LOG.infof("Site rejected email queued for user %s (site %s)", submitter.id, site.id);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to queue site rejected email for site %s", site != null ? site.id : "null");
        }
    }

    /**
     * Sends password reset email with secure token link.
     *
     * <p>
     * Sends password reset link to user's email address. Token expires after configured TTL.
     *
     * @param user
     *            the user requesting password reset
     * @param resetToken
     *            the password reset token (generated by AuthService)
     */
    public void sendPasswordResetEmail(User user, String resetToken) {
        try {
            // Check if email delivery is enabled (bounce protection)
            if (!isEmailEnabled(user)) {
                return;
            }

            // Password reset emails are always sent (security critical)

            // Check rate limit (stricter limits for security emails)
            RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(
                    user.id.getMostSignificantBits(), null, "email.security_notification",
                    RateLimitService.Tier.LOGGED_IN, "/api/auth/password-reset");

            if (!rateLimitResult.allowed()) {
                LOG.warnf("Rate limit exceeded for user %s, skipping password reset email", user.id);
                return;
            }

            // Build template data
            String resetUrl = baseUrl + "/auth/reset-password?token=" + resetToken;
            Map<String, Object> data = Map.of("userName", user.displayName, "resetUrl", resetUrl, "baseUrl", baseUrl);

            // Render templates
            String subject = "Reset your " + platformName + " password";
            String htmlBody = passwordResetHtml.data(data).render();
            String textBody = passwordResetTxt.data(data).render();

            // Queue email delivery
            queueEmailDelivery(user.id, user.email, "password-reset", subject, htmlBody, textBody);

            // Create in-app notification
            createUserNotification(user.id, "password_reset", "Password reset requested",
                    "A password reset link has been sent to your email", "/auth/reset-password");

            LOG.infof("Password reset email queued for user %s", user.id);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to queue password reset email for user %s", user != null ? user.id : "null");
        }
    }

    /**
     * Sends email verification link to user.
     *
     * <p>
     * Sends verification link to confirm email address ownership. Token expires after configured TTL.
     *
     * @param user
     *            the user requesting email verification
     * @param verificationToken
     *            the email verification token (generated by AuthService)
     */
    public void sendEmailVerificationEmail(User user, String verificationToken) {
        try {
            // Check if email delivery is enabled (bounce protection)
            if (!isEmailEnabled(user)) {
                return;
            }

            // Email verification emails are always sent (security critical)

            // Check rate limit
            RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(
                    user.id.getMostSignificantBits(), null, "email.security_notification",
                    RateLimitService.Tier.LOGGED_IN, "/api/auth/verify-email");

            if (!rateLimitResult.allowed()) {
                LOG.warnf("Rate limit exceeded for user %s, skipping email verification email", user.id);
                return;
            }

            // Build template data
            String verificationUrl = baseUrl + "/auth/verify-email?token=" + verificationToken;
            Map<String, Object> data = Map.of("userName", user.displayName, "verificationUrl", verificationUrl,
                    "baseUrl", baseUrl);

            // Render templates
            String subject = "Verify your " + platformName + " email address";
            String htmlBody = emailVerificationHtml.data(data).render();
            String textBody = emailVerificationTxt.data(data).render();

            // Queue email delivery
            queueEmailDelivery(user.id, user.email, "email-verification", subject, htmlBody, textBody);

            // Create in-app notification
            createUserNotification(user.id, "email_verification", "Email verification sent",
                    "A verification link has been sent to your email", "/auth/verify-email");

            LOG.infof("Email verification email queued for user %s", user.id);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to queue email verification email for user %s", user != null ? user.id : "null");
        }
    }

    /**
     * Sends daily digest of unread notifications to user.
     *
     * <p>
     * Sends summary of all unread in-app notifications from the past 24 hours.
     *
     * @param user
     *            the user to send digest to
     * @param notifications
     *            list of unread notifications
     */
    public void sendNotificationDigest(User user, List<UserNotification> notifications) {
        try {
            // Check if email delivery is enabled (bounce protection)
            if (!isEmailEnabled(user)) {
                return;
            }

            // Check preferences
            if (!checkNotificationPreference(user, "email_digest")) {
                LOG.debugf("User %s opted out of notification digest emails", user.id);
                return;
            }

            // Check rate limit
            RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(
                    user.id.getMostSignificantBits(), null, "email.digest_notification",
                    RateLimitService.Tier.LOGGED_IN, "/api/notifications/digest");

            if (!rateLimitResult.allowed()) {
                LOG.warnf("Rate limit exceeded for user %s, skipping notification digest email", user.id);
                return;
            }

            // Generate unsubscribe token
            String unsubscribeToken = notificationPreferencesService.generateUnsubscribeToken(user, "email_digest");
            String unsubscribeUrl = baseUrl + "/api/notifications/unsubscribe?token=" + unsubscribeToken;

            // Build template data
            Map<String, Object> data = Map.of("userName", user.displayName, "notificationCount", notifications.size(),
                    "notifications", notifications, "notificationsUrl", baseUrl + "/notifications", "baseUrl", baseUrl,
                    "unsubscribeUrl", unsubscribeUrl);

            // Render templates
            String subject = "You have " + notifications.size() + " unread notifications";
            String htmlBody = notificationDigestHtml.data(data).render();
            String textBody = notificationDigestTxt.data(data).render();

            // Queue email delivery
            queueEmailDelivery(user.id, user.email, "notification-digest", subject, htmlBody, textBody);

            LOG.infof("Notification digest email queued for user %s (%d notifications)", user.id, notifications.size());

        } catch (Exception e) {
            LOG.errorf(e, "Failed to queue notification digest email for user %s", user != null ? user.id : "null");
        }
    }

    /**
     * Sends admin flag review alert to ops team.
     *
     * <p>
     * Alerts ops team when a listing or site is flagged for moderation review.
     *
     * @param admin
     *            the admin user to notify (or null for ops team email)
     * @param flag
     *            the flag record
     */
    public void sendAdminFlagReviewEmail(User admin, ListingFlag flag) {
        try {
            // Check if email delivery is enabled for admin user (bounce protection)
            if (admin != null && !isEmailEnabled(admin)) {
                return;
            }

            // Admin alerts are always sent (important moderation action)

            String recipientEmail = admin != null ? admin.email : opsAlertEmail;
            UUID recipientId = admin != null ? admin.id : null;

            // Check rate limit (use ops email as subject for team alerts)
            RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(null, recipientEmail,
                    "email.admin_alert", RateLimitService.Tier.TRUSTED, "/admin/flags/review");

            if (!rateLimitResult.allowed()) {
                LOG.warnf("Rate limit exceeded for admin alerts, skipping flag review email");
                return;
            }

            // Build template data
            String reviewUrl = baseUrl + "/admin/flags/" + flag.id;
            Map<String, Object> data = Map.of("flagReason", flag.reason, "flagDetails",
                    flag.details != null ? flag.details : "", "flaggedBy",
                    flag.userId != null ? flag.userId.toString() : "Anonymous", "reviewUrl", reviewUrl, "baseUrl",
                    baseUrl);

            // Render templates
            String subject = "[ADMIN] New flag requires review: " + flag.reason;
            String htmlBody = adminFlagReviewHtml.data(data).render();
            String textBody = adminFlagReviewTxt.data(data).render();

            // Queue email delivery
            queueEmailDelivery(recipientId, recipientEmail, "admin-flag-review", subject, htmlBody, textBody);

            // Create in-app notification (if admin user provided)
            if (admin != null) {
                createUserNotification(admin.id, "admin_flag_review", "New flag requires review",
                        "A new " + flag.reason + " flag has been submitted", "/admin/flags/" + flag.id);
            }

            LOG.infof("Admin flag review email queued for %s (flag %s)", recipientEmail, flag.id);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to queue admin flag review email for flag %s", flag != null ? flag.id : "null");
        }
    }

    /**
     * Checks if user has opted in to receive a specific notification type.
     *
     * <p>
     * Checks BOTH global email toggle AND specific notification type flag. Returns false if user opted out or
     * preferences don't exist (creates defaults if missing).
     *
     * @param user
     *            the user to check
     * @param notificationType
     *            the notification type key (e.g., "email_listing_messages")
     * @return true if user opted in, false if opted out
     */
    private boolean checkNotificationPreference(User user, String notificationType) {
        Optional<NotificationPreferences> prefsOpt = NotificationPreferences.findByUserId(user.id);

        if (prefsOpt.isEmpty()) {
            // New user, create default preferences (all enabled except digest)
            NotificationPreferences prefs = NotificationPreferences.create(user.id);
            prefsOpt = Optional.of(prefs);
        }

        NotificationPreferences prefs = prefsOpt.get();

        // Check global toggle first
        if (!prefs.emailEnabled) {
            LOG.debugf("Email disabled for user %s", user.id);
            return false;
        }

        // Check specific type
        return switch (notificationType) {
            case "email_listing_messages" -> prefs.emailListingMessages;
            case "email_site_approved" -> prefs.emailSiteApproved;
            case "email_site_rejected" -> prefs.emailSiteRejected;
            case "email_digest" -> prefs.emailDigest;
            default -> true; // Unknown types default to enabled
        };
    }

    /**
     * Creates in-app notification record for user.
     *
     * <p>
     * Creates {@link UserNotification} record for display in user's notification center. Called by all notification
     * methods after queueing email delivery.
     *
     * @param userId
     *            the user ID
     * @param type
     *            notification type key (matches preference key)
     * @param title
     *            notification title (short summary)
     * @param message
     *            notification message (details)
     * @param actionUrl
     *            URL for notification action (e.g., view listing, reply to message)
     */
    private void createUserNotification(UUID userId, String type, String title, String message, String actionUrl) {
        try {
            UserNotification notification = new UserNotification();
            notification.userId = userId;
            notification.type = type;
            notification.title = title;
            notification.message = message;
            notification.actionUrl = actionUrl;
            notification.createdAt = Instant.now();
            UserNotification.create(notification);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to create user notification: userId=%s, type=%s", userId, type);
        }
    }

    /**
     * Queues email delivery via EmailDeliveryLog for async processing.
     *
     * <p>
     * Creates {@link EmailDeliveryLog} record with status = QUEUED. EmailDeliveryJob processes queue every 1 minute and
     * sends emails via EmailService.
     *
     * @param userId
     *            the user ID (nullable for ops alerts)
     * @param emailAddress
     *            recipient email address
     * @param templateName
     *            template identifier for logging
     * @param subject
     *            email subject line
     * @param htmlBody
     *            rendered HTML email body
     * @param textBody
     *            rendered plain text email body
     */
    private void queueEmailDelivery(UUID userId, String emailAddress, String templateName, String subject,
            String htmlBody, String textBody) {
        try {
            EmailDeliveryLog log = new EmailDeliveryLog();
            log.userId = userId;
            log.emailAddress = emailAddress;
            log.templateName = templateName;
            log.subject = subject;
            log.htmlBody = htmlBody;
            log.textBody = textBody;
            log.status = EmailDeliveryLog.DeliveryStatus.QUEUED;
            log.createdAt = Instant.now();
            log.persist();

            LOG.debugf("Email queued for delivery: userId=%s, template=%s, subject=%s", userId, templateName, subject);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to queue email delivery: userId=%s, template=%s", userId, templateName);
        }
    }
}
