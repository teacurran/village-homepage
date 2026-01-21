# Email Notifications

Comprehensive guide to the Village Homepage email notification system.

## Overview

The email notification system sends transactional and alert emails for key user and system events. All notifications use HTML templates with inline CSS for broad email client compatibility.

### Key Features

- **Qute Template Engine**: Type-safe HTML email rendering
- **Rate Limiting**: Tier-aware spam prevention via RateLimitService
- **Best-Effort Delivery**: Email failures don't break user flows
- **Design System Compliance**: Uses brand colors and consistent styling
- **Localization Ready**: Templates include i18n placeholders for future languages

---

## Architecture

### Components

```
EmailNotificationService
  ├── Mailer (Quarkus Mailer)
  ├── RateLimitService (Tier-aware rate limiting)
  ├── Qute Templates (HTML email templates)
  └── ConfigProperties (Email settings from application.yaml)
```

### Service Layer

**EmailNotificationService** (`services/EmailNotificationService.java`)
- Centralized notification service for all email types
- Injects Qute templates via `@Location` annotation
- Enforces rate limiting before sending
- Logs all send attempts with structured logging

### Template Layer

**Qute Templates** (`src/main/resources/templates/email-templates/`)
- `profilePublished.html` - Profile published notification
- `profileUnpublished.html` - Profile unpublished confirmation
- `aiBudgetAlert.html` - AI budget threshold alerts

### Configuration

**Email Settings** (`src/main/resources/application.yaml`)

```yaml
email:
  notifications:
    from: noreply@villagecompute.com
    platform-name: Village Homepage
    base-url: https://homepage.villagecompute.com
    ops-alert-email: ops@villagecompute.com
```

---

## Notification Types

### 1. Profile Published

**Trigger:** User publishes their profile (makes it public at `/u/{username}`)

**Recipients:** Profile owner

**Rate Limit:** 5 per hour (logged_in), 10 per hour (trusted)

**Template Data:**
- `username` - User's chosen username
- `templateType` - Template type (public_homepage, your_times, your_report)
- `profileUrl` - Public profile URL (`/u/{username}`)
- `editUrl` - Edit profile URL (`/profile/edit`)
- `baseUrl` - Base application URL

**Email Content:**
- Congratulations message
- Public profile URL (copy/paste friendly)
- Template type confirmation
- SEO tips for improving discoverability
- Links to view and edit profile

**Code Integration:**

```java
// ProfileService.publishProfile()
emailNotificationService.sendProfilePublishedNotification(
    profile.userId,
    user.email,
    profile.username,
    profile.template,
    RateLimitService.Tier.fromKarma(user.karma)
);
```

---

### 2. Profile Unpublished

**Trigger:** User unpublishes their profile (takes it private)

**Recipients:** Profile owner

**Rate Limit:** 5 per hour (logged_in), 10 per hour (trusted)

**Template Data:**
- `username` - User's username
- `editUrl` - Edit profile URL
- `baseUrl` - Base application URL

**Email Content:**
- Confirmation that profile is now private
- Explanation that data is retained
- Reminder that profile can be republished anytime
- Link to edit profile
- GDPR data retention notice

**Code Integration:**

```java
// ProfileService.unpublishProfile()
emailNotificationService.sendProfileUnpublishedNotification(
    profile.userId,
    user.email,
    profile.username,
    RateLimitService.Tier.fromKarma(user.karma)
);
```

---

### 3. AI Budget Alert

**Trigger:** AI budget crosses threshold (75%, 90%, 100%)

**Recipients:** Operations team (`ops@villagecompute.com`)

**Rate Limit:** 3 per hour (trusted tier)

**Alert Levels:**

| Level | Threshold | Action | Color |
|-------|-----------|--------|-------|
| WARNING | 75% | REDUCE | Yellow (#ffc107) |
| CRITICAL | 90% | QUEUE | Red (#dc3545) |
| EMERGENCY | 100% | HARD_STOP | Purple (#800080) |

**Template Data:**
- `level` - Alert level (WARNING, CRITICAL, EMERGENCY)
- `percentUsed` - Budget percentage (e.g., "87.5")
- `costDollars` - Cost in dollars (e.g., "437.50")
- `budgetDollars` - Budget in dollars (e.g., "500.00")
- `remainingDollars` - Remaining budget (e.g., "62.50")
- `action` - Recommended action (REDUCE, QUEUE, HARD_STOP)
- `dashboardUrl` - Link to admin analytics dashboard
- `baseUrl` - Base application URL

**Email Content:**
- Visual budget gauge with color-coded progress bar
- Current usage percentage and dollar amounts
- Recommended action with explanation
- Threshold definitions
- Link to admin dashboard
- Policy reference (P10)

**Code Integration:**

```java
// AiTaggingBudgetService.sendBudgetAlert()
emailNotificationService.sendAiBudgetAlert(
    level,           // "WARNING", "CRITICAL", or "EMERGENCY"
    percentUsed,     // 87.5
    costCents,       // 43750 ($437.50)
    budgetCents,     // 50000 ($500.00)
    action           // "REDUCE", "QUEUE", or "HARD_STOP"
);
```

---

## Rate Limiting

### Configuration

Rate limit configurations are stored in the `rate_limit_configs` table and enforced by `RateLimitService`.

**Migration:** `migrations/scripts/20260121_add_email_notification_rate_limits.sql`

```sql
INSERT INTO rate_limit_configs (action_type, tier, max_requests, window_seconds, enabled) VALUES
  ('email.profile_notification', 'logged_in', 5, 3600, true),
  ('email.profile_notification', 'trusted', 10, 3600, true),
  ('email.analytics_alert', 'trusted', 3, 3600, true),
  ('email.gdpr_notification', 'logged_in', 1, 86400, true);
```

### Enforcement

Rate limiting is checked BEFORE email rendering to conserve resources:

```java
RateLimitResult result = rateLimitService.checkLimit(
    userId,
    null,  // IP address (not needed for user-based limits)
    "email.profile_notification",
    userTier,
    "/api/profile/publish"
);

if (!result.allowed()) {
    LOG.warnf("Rate limit exceeded for user %s, skipping email", userId);
    return;  // Don't send email
}
```

### Adjusting Rate Limits

To adjust rate limits, update the `rate_limit_configs` table:

```sql
-- Increase profile notification limit for logged_in users
UPDATE rate_limit_configs
SET max_requests = 10
WHERE action_type = 'email.profile_notification' AND tier = 'logged_in';
```

Changes take effect after 10 minutes (rate limit config cache TTL).

---

## Testing

### Local Testing with Mailpit

All development emails are sent to Mailpit (local email sink).

**Start Mailpit:**

```bash
docker-compose up mailpit
```

**Access Mailpit UI:**

```
http://localhost:8130
```

**Test Email Flows:**

1. **Profile Published:**
   - Create profile with username
   - Publish profile via API or UI
   - Check Mailpit inbox for notification
   - Verify template rendering and data binding

2. **Profile Unpublished:**
   - Unpublish an existing profile
   - Check Mailpit inbox for confirmation
   - Verify data retention message

3. **AI Budget Alert:**
   - Manually trigger alert via AiTaggingBudgetService
   - Or use admin API to set test budget values
   - Check Mailpit inbox for alert
   - Verify alert level styling (colors, icons)

### Unit Tests

**Test File:** `src/test/java/villagecompute/homepage/services/EmailNotificationServiceTest.java`

**Coverage:**
- Email template rendering
- Rate limit enforcement
- Data binding validation
- Error handling

**Run Tests:**

```bash
./mvnw test -Dtest=EmailNotificationServiceTest
```

### Email Client Compatibility

Test emails in multiple clients to ensure rendering consistency:

- Gmail (web and mobile)
- Outlook (web and desktop)
- Apple Mail (macOS and iOS)
- Thunderbird

**Known Limitations:**
- Inline CSS only (no external stylesheets)
- No flexbox/grid (use tables for layout if needed)
- No background images (not supported by all clients)
- Limited font choices (web-safe fonts only)

---

## Monitoring

### Logs

All email operations are logged with structured logging:

```
INFO  [EmailNotificationService] Sent profile published notification: userId=abc-123, email=user@example.com, username=testuser, template=public_homepage
```

**Search for Email Failures:**

```bash
grep "Failed to send.*email" application.log
```

### Metrics

Email notifications are tracked via ObservabilityMetrics (if integrated):

- `email.notifications.sent{type="profile_published"}` - Count of sent notifications
- `email.notifications.failed{type="profile_published"}` - Count of failed sends
- `email.notifications.rate_limited{type="profile_published"}` - Count of rate-limited attempts

### Alerts

Set up alerts for email delivery failures:

```yaml
# Example Prometheus alert rule
- alert: EmailNotificationFailureRate
  expr: rate(email_notifications_failed_total[5m]) > 0.1
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: High email notification failure rate
    description: "{{ $value }} emails/sec failing to send"
```

---

## Troubleshooting

### Emails Not Being Sent

**Symptoms:** Email notifications not appearing in Mailpit or user inbox

**Diagnosis:**

1. Check application logs for send errors:
   ```bash
   grep "Failed to send.*email" application.log
   ```

2. Verify rate limit config exists:
   ```sql
   SELECT * FROM rate_limit_configs WHERE action_type LIKE 'email.%';
   ```

3. Check if rate limit is being exceeded:
   ```bash
   grep "Rate limit exceeded.*email" application.log
   ```

4. Verify email configuration:
   ```yaml
   # application.yaml
   email.notifications.from: noreply@villagecompute.com
   email.notifications.base-url: https://homepage.villagecompute.com
   ```

5. Test Mailer connectivity:
   - For dev: Ensure Mailpit is running (`docker-compose up mailpit`)
   - For prod: Verify SMTP credentials and connectivity

**Solutions:**

- If rate limited: Increase rate limit in `rate_limit_configs` table
- If config missing: Run migration `20260121_add_email_notification_rate_limits.sql`
- If Mailer fails: Check SMTP settings and connectivity
- If template error: Check template syntax in `src/main/resources/templates/email-templates/`

---

### Template Rendering Errors

**Symptoms:** Qute template errors in logs, emails not sent

**Diagnosis:**

```bash
grep "TemplateException" application.log
```

**Common Causes:**

1. **Missing Template Variable:**
   ```
   TemplateException: Property "unknownVar" not found on base object
   ```
   - Check template data bindings match method parameters
   - Verify all `{variableName}` placeholders have corresponding data

2. **Malformed Template Syntax:**
   ```
   TemplateException: Parser error: unexpected token
   ```
   - Check for unclosed tags (`{#if}` without `{/if}`)
   - Verify conditional syntax: `{#if condition}...{/if}`

**Solutions:**

- Review template file for syntax errors
- Check EmailNotificationService method for correct data binding
- Test template rendering with hardcoded data

---

### Rate Limit Issues

**Symptoms:** Legitimate emails being blocked by rate limiting

**Diagnosis:**

```bash
grep "Rate limit exceeded.*email" application.log | tail -n 20
```

**Solutions:**

1. **Increase Rate Limit:**
   ```sql
   UPDATE rate_limit_configs
   SET max_requests = 20
   WHERE action_type = 'email.profile_notification' AND tier = 'logged_in';
   ```

2. **Change Window Duration:**
   ```sql
   UPDATE rate_limit_configs
   SET window_seconds = 7200  -- 2 hours
   WHERE action_type = 'email.profile_notification';
   ```

3. **Disable Rate Limiting (Not Recommended):**
   ```sql
   UPDATE rate_limit_configs
   SET enabled = false
   WHERE action_type = 'email.profile_notification';
   ```

---

## Adding New Notification Types

Follow this checklist to add a new email notification:

### 1. Create HTML Template

**File:** `src/main/resources/templates/email-templates/myNewNotification.html`

**Requirements:**
- Use inline CSS styles
- Max width: 600px
- Include i18n placeholders (`data-i18n` attributes)
- Follow design system colors
- Test in multiple email clients

**Template Structure:**

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Email Subject</title>
</head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
    <!-- Header with colored accent bar -->
    <div style="background: #d4edda; border-left: 4px solid #28a745; padding: 20px; margin-bottom: 20px;">
        <h2 style="margin-top: 0; color: #155724;">Heading</h2>
    </div>

    <!-- Content card -->
    <div style="background: #fff; border: 1px solid #dee2e6; border-radius: 4px; padding: 20px; margin-bottom: 20px;">
        <p>{dataBinding}</p>
    </div>

    <!-- CTA button -->
    <div style="text-align: center; margin: 30px 0;">
        <a href="{url}" style="display: inline-block; background: #007bff; color: white; padding: 12px 30px; text-decoration: none; border-radius: 4px; font-weight: bold;">
            Button Text
        </a>
    </div>

    <!-- Footer -->
    <div style="color: #6c757d; font-size: 12px; text-align: center;">
        <p>Footer text</p>
    </div>
</body>
</html>
```

### 2. Add Method to EmailNotificationService

**File:** `src/main/java/villagecompute/homepage/services/EmailNotificationService.java`

```java
@Inject
@io.quarkus.qute.Location("email-templates/myNewNotification.html")
Template myNewNotification;

/**
 * Sends my new notification to user.
 *
 * @param userId user ID (for rate limiting)
 * @param email user's email address
 * @param customData notification-specific data
 */
public void sendMyNewNotification(UUID userId, String email, String customData) {
    // Check rate limit
    RateLimitService.RateLimitResult result = rateLimitService.checkLimit(
        userId.getMostSignificantBits(),
        null,
        "email.my_new_notification",
        RateLimitService.Tier.LOGGED_IN,
        "/api/my-endpoint"
    );

    if (!result.allowed()) {
        LOG.warnf("Rate limit exceeded for user %s, skipping notification", userId);
        return;
    }

    try {
        String subject = "My Notification Subject";

        // Render template
        String htmlBody = myNewNotification
            .data("customData", customData)
            .data("baseUrl", baseUrl)
            .render();

        // Send email
        mailer.send(
            Mail.withHtml(email, subject, htmlBody)
                .setFrom(fromEmail)
                .addHeader("X-Platform", platformName)
                .addHeader("X-Notification-Type", "my_new_notification")
        );

        LOG.infof("Sent my new notification: userId=%s, email=%s", userId, email);

    } catch (Exception e) {
        LOG.errorf(e, "Failed to send my new notification: userId=%s, email=%s", userId, email);
    }
}
```

### 3. Add Rate Limit Configuration

**File:** `migrations/scripts/YYYYMMDD_add_my_new_notification_rate_limit.sql`

```sql
INSERT INTO rate_limit_configs (action_type, tier, max_requests, window_seconds, enabled, description)
VALUES
  ('email.my_new_notification', 'logged_in', 5, 3600, true, 'My new notification for logged-in users');
```

### 4. Add Unit Tests

**File:** `src/test/java/villagecompute/homepage/services/EmailNotificationServiceTest.java`

```java
@Test
void testSendMyNewNotification_Success() {
    // Arrange
    UUID userId = UUID.randomUUID();
    String email = "user@example.com";
    String customData = "test data";

    // Mock rate limit check to allow request
    when(rateLimitService.checkLimit(anyLong(), isNull(), eq("email.my_new_notification"),
            eq(RateLimitService.Tier.LOGGED_IN), anyString()))
        .thenReturn(RateLimitService.RateLimitResult.allowed(5, 4, 3600));

    // Act
    emailNotificationService.sendMyNewNotification(userId, email, customData);

    // Assert
    verify(mailer, times(1)).send(any(Mail.class));
}
```

### 5. Integrate with Business Logic

Call the notification method from your service where the event occurs:

```java
// MyService.java
@Inject
EmailNotificationService emailNotificationService;

public void performAction(UUID userId) {
    // ... business logic ...

    // Send notification (best-effort, non-blocking)
    try {
        User user = User.findById(userId);
        if (user != null && user.email != null) {
            emailNotificationService.sendMyNewNotification(userId, user.email, "data");
        }
    } catch (Exception e) {
        LOG.errorf(e, "Failed to send notification (non-fatal)");
    }
}
```

### 6. Update Documentation

Add the new notification type to this document with:
- Trigger description
- Recipients
- Rate limit configuration
- Template data reference
- Email content summary
- Code integration example

---

## Policy References

- **P14**: Rate limiting for email notifications
- **F14.2**: Rate limiting service integration
- **F14.3**: Email communication (SMTP/IMAP relay, transactional emails)
- **P1**: GDPR compliance (data retention notices in emails)
- **P10**: AI budget management (budget alert emails)

---

## Support

For issues with email notifications:

1. Check application logs for errors
2. Verify rate limit configurations in database
3. Test email sending in Mailpit (dev environment)
4. Review template syntax for Qute errors
5. Contact ops team if SMTP connectivity issues

**Ops Team Email:** ops@villagecompute.com

**GitHub Issues:** https://github.com/VillageCompute/village-homepage/issues
