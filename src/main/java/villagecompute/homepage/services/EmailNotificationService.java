package villagecompute.homepage.services;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Centralized email notification service for all transactional and alert emails (Policy F14.3).
 *
 * <p>
 * Provides template-based email notifications with rate limiting and error handling. All notification methods follow
 * the same pattern:
 * <ol>
 * <li>Check rate limit via RateLimitService</li>
 * <li>Render Qute template with data binding</li>
 * <li>Send email via Mailer with proper headers</li>
 * <li>Log success/failure (errors don't throw exceptions)</li>
 * </ol>
 *
 * <p>
 * <b>Notification Types:</b>
 * <ul>
 * <li><b>Profile Published:</b> Sent when user makes profile public for first time</li>
 * <li><b>Profile Unpublished:</b> Sent when user takes profile private</li>
 * <li><b>AI Budget Alert:</b> Sent to ops team when AI budget thresholds crossed (75%, 90%, 100%)</li>
 * </ul>
 *
 * <p>
 * <b>Rate Limiting:</b> All notifications are rate-limited per user tier to prevent spam:
 * <ul>
 * <li>Profile notifications: 5 per hour (logged_in), 10 per hour (trusted)</li>
 * <li>Analytics alerts: 3 per hour (trusted/ops team)</li>
 * <li>GDPR notifications: 1 per day (logged_in)</li>
 * </ul>
 *
 * <p>
 * <b>Error Handling:</b> Email failures are logged but don't throw exceptions. Notification delivery is best-effort
 * and should never break user flows.
 *
 * <p>
 * <b>Configuration:</b> Email settings loaded from application.yaml:
 * <ul>
 * <li>email.notifications.from: Sender email address</li>
 * <li>email.notifications.platform-name: Platform name for headers</li>
 * <li>email.notifications.base-url: Base URL for email links</li>
 * <li>email.notifications.ops-alert-email: Operations team email for alerts</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F14.3: Email Communication</li>
 * <li>P14: Rate limiting for email notifications</li>
 * <li>F14.2: Rate limiting service integration</li>
 * </ul>
 *
 * @see RateLimitService for rate limiting enforcement
 * @see MessageRelayService for marketplace email relay
 */
@ApplicationScoped
public class EmailNotificationService {

    private static final Logger LOG = Logger.getLogger(EmailNotificationService.class);

    @Inject
    Mailer mailer;

    @Inject
    RateLimitService rateLimitService;

    @ConfigProperty(name = "email.notifications.from")
    String fromEmail;

    @ConfigProperty(name = "email.notifications.platform-name")
    String platformName;

    @ConfigProperty(name = "email.notifications.base-url")
    String baseUrl;

    @ConfigProperty(name = "email.notifications.ops-alert-email")
    String opsAlertEmail;

    @Inject
    @io.quarkus.qute.Location("email-templates/profilePublished.html")
    Template profilePublished;

    @Inject
    @io.quarkus.qute.Location("email-templates/profileUnpublished.html")
    Template profileUnpublished;

    @Inject
    @io.quarkus.qute.Location("email-templates/aiBudgetAlert.html")
    Template aiBudgetAlert;

    /**
     * Sends profile published notification to user.
     *
     * <p>
     * Notifies user that their customized homepage is now public and visible at /u/{username}. Email includes profile
     * URL, template type, edit link, and SEO tips.
     *
     * <p>
     * Rate limited to 5 per hour for logged_in users, 10 per hour for trusted users. Rate limit key uses userId to
     * prevent user from bypassing limit by publishing/unpublishing rapidly.
     *
     * @param userId
     *            user ID (for rate limiting)
     * @param email
     *            user's email address
     * @param username
     *            user's public username
     * @param templateType
     *            template type (public_homepage, your_times, your_report)
     * @param userTier
     *            user tier for rate limiting (logged_in or trusted)
     */
    public void sendProfilePublishedNotification(UUID userId, String email, String username, String templateType,
            RateLimitService.Tier userTier) {

        // Check rate limit
        RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(userId.getMostSignificantBits(),
                null, // No IP address needed for user-based rate limiting
                "email.profile_notification", userTier,
                "/api/profile/publish" // Endpoint for violation logging
        );

        if (!rateLimitResult.allowed()) {
            LOG.warnf("Rate limit exceeded for user %s, skipping profile published email (remaining: %d)", userId,
                    rateLimitResult.remaining());
            return;
        }

        try {
            String profileUrl = String.format("%s/u/%s", baseUrl, username);
            String editUrl = String.format("%s/profile/edit", baseUrl);

            String subject = String.format("Your profile is now public - %s", username);

            // Render template
            String htmlBody = profilePublished.data("username", username).data("templateType", templateType)
                    .data("profileUrl", profileUrl).data("editUrl", editUrl).data("baseUrl", baseUrl).render();

            // Send email
            mailer.send(Mail.withHtml(email, subject, htmlBody).setFrom(fromEmail)
                    .addHeader("X-Platform", platformName).addHeader("X-Notification-Type", "profile_published")
                    .addHeader("X-User-ID", userId.toString()));

            LOG.infof("Sent profile published notification: userId=%s, email=%s, username=%s, template=%s", userId,
                    email, username, templateType);

        } catch (Exception e) {
            // Log but don't throw - email failure shouldn't break profile publish flow
            LOG.errorf(e, "Failed to send profile published email: userId=%s, email=%s", userId, email);
        }
    }

    /**
     * Sends profile unpublished confirmation to user.
     *
     * <p>
     * Confirms that profile is now private and no longer accessible at /u/{username}. Email explains that data is
     * retained and can be republished anytime.
     *
     * <p>
     * Rate limited to 5 per hour for logged_in users, 10 per hour for trusted users.
     *
     * @param userId
     *            user ID (for rate limiting)
     * @param email
     *            user's email address
     * @param username
     *            user's public username
     * @param userTier
     *            user tier for rate limiting (logged_in or trusted)
     */
    public void sendProfileUnpublishedNotification(UUID userId, String email, String username,
            RateLimitService.Tier userTier) {

        // Check rate limit
        RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(userId.getMostSignificantBits(),
                null, // No IP address needed for user-based rate limiting
                "email.profile_notification", userTier,
                "/api/profile/unpublish" // Endpoint for violation logging
        );

        if (!rateLimitResult.allowed()) {
            LOG.warnf("Rate limit exceeded for user %s, skipping profile unpublished email (remaining: %d)", userId,
                    rateLimitResult.remaining());
            return;
        }

        try {
            String editUrl = String.format("%s/profile/edit", baseUrl);

            String subject = String.format("Your profile is now private - %s", username);

            // Render template
            String htmlBody = profileUnpublished.data("username", username).data("editUrl", editUrl)
                    .data("baseUrl", baseUrl).render();

            // Send email
            mailer.send(Mail.withHtml(email, subject, htmlBody).setFrom(fromEmail)
                    .addHeader("X-Platform", platformName).addHeader("X-Notification-Type", "profile_unpublished")
                    .addHeader("X-User-ID", userId.toString()));

            LOG.infof("Sent profile unpublished notification: userId=%s, email=%s, username=%s", userId, email,
                    username);

        } catch (Exception e) {
            // Log but don't throw - email failure shouldn't break profile unpublish flow
            LOG.errorf(e, "Failed to send profile unpublished email: userId=%s, email=%s", userId, email);
        }
    }

    /**
     * Sends AI budget alert to operations team.
     *
     * <p>
     * Alerts ops team when AI budget crosses thresholds (75%, 90%, 100%). Email shows current usage, recommended
     * action, and link to admin dashboard.
     *
     * <p>
     * Alert levels and actions:
     * <ul>
     * <li><b>WARNING (75%):</b> Reduce batch sizes to avoid hitting limit</li>
     * <li><b>CRITICAL (90%):</b> Queue non-urgent operations for next month</li>
     * <li><b>EMERGENCY (100%):</b> Hard stop - all AI operations halted</li>
     * </ul>
     *
     * <p>
     * Rate limited to 3 per hour for ops team to prevent alert spam during budget exhaustion.
     *
     * @param level
     *            alert level (WARNING, CRITICAL, EMERGENCY)
     * @param percentUsed
     *            budget percentage used (e.g., 87.5 for 87.5%)
     * @param costCents
     *            estimated cost in cents (e.g., 43750 for $437.50)
     * @param budgetCents
     *            monthly budget in cents (e.g., 50000 for $500)
     * @param action
     *            recommended action (REDUCE, QUEUE, HARD_STOP)
     */
    public void sendAiBudgetAlert(String level, double percentUsed, int costCents, int budgetCents, String action) {

        // Check rate limit (use ops email as subject ID)
        RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(null, // No user ID for ops
                                                                                              // alerts
                opsAlertEmail, // Use ops email as rate limit subject
                "email.analytics_alert", RateLimitService.Tier.TRUSTED, // Ops team is trusted tier
                "/internal/ai-budget-check" // Endpoint for violation logging
        );

        if (!rateLimitResult.allowed()) {
            LOG.warnf("Rate limit exceeded for AI budget alerts, skipping alert (level: %s, percentUsed: %.2f%%)",
                    level, percentUsed);
            return;
        }

        try {
            String dashboardUrl = String.format("%s/admin/analytics", baseUrl);

            // Convert cents to dollars for display
            BigDecimal costDollars = BigDecimal.valueOf(costCents).divide(BigDecimal.valueOf(100), 2,
                    RoundingMode.HALF_UP);
            BigDecimal budgetDollars = BigDecimal.valueOf(budgetCents).divide(BigDecimal.valueOf(100), 2,
                    RoundingMode.HALF_UP);
            BigDecimal remainingDollars = budgetDollars.subtract(costDollars);

            String subject = String.format("[%s] AI Budget Alert: %.1f%% used", level, percentUsed);

            // Render template
            String htmlBody = aiBudgetAlert.data("level", level).data("percentUsed", String.format("%.1f", percentUsed))
                    .data("costDollars", costDollars.toString()).data("budgetDollars", budgetDollars.toString())
                    .data("remainingDollars", remainingDollars.toString()).data("action", action)
                    .data("dashboardUrl", dashboardUrl).data("baseUrl", baseUrl).render();

            // Send email
            mailer.send(Mail.withHtml(opsAlertEmail, subject, htmlBody).setFrom(fromEmail)
                    .addHeader("X-Platform", platformName).addHeader("X-Notification-Type", "ai_budget_alert")
                    .addHeader("X-Alert-Level", level).addHeader("X-Budget-Percent", String.format("%.2f", percentUsed))
                    .addHeader("Priority", level.equals("EMERGENCY") ? "urgent" : "normal"));

            LOG.infof("Sent AI budget alert: level=%s, percentUsed=%.2f%%, costCents=%d, budgetCents=%d, action=%s",
                    level, percentUsed, costCents, budgetCents, action);

        } catch (Exception e) {
            // Log but don't throw - email failure shouldn't break AI budget tracking
            LOG.errorf(e, "Failed to send AI budget alert: level=%s, percentUsed=%.2f%%", level, percentUsed);
        }
    }

    /**
     * Email data record for profile published notification.
     *
     * <p>
     * Type-safe data binding for Qute template rendering. Ensures compile-time checking of template variables.
     */
    public record ProfilePublishedEmailData(String username, String templateType, String profileUrl, String editUrl,
            String baseUrl) {
    }

    /**
     * Email data record for profile unpublished notification.
     *
     * <p>
     * Type-safe data binding for Qute template rendering.
     */
    public record ProfileUnpublishedEmailData(String username, String editUrl, String baseUrl) {
    }

    /**
     * Email data record for AI budget alert notification.
     *
     * <p>
     * Type-safe data binding for Qute template rendering.
     */
    public record AiBudgetAlertEmailData(String level, String percentUsed, String costDollars, String budgetDollars,
            String remainingDollars, String action, String dashboardUrl, String baseUrl) {
    }
}
