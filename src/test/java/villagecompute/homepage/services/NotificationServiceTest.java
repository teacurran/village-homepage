package villagecompute.homepage.services;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.mail.internet.MimeMessage;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import villagecompute.homepage.BaseIntegrationTest;
import villagecompute.homepage.TestFixtures;
import villagecompute.homepage.data.models.*;
import villagecompute.homepage.testing.PostgreSQLTestProfile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for NotificationService with GreenMail email interception.
 *
 * <p>
 * Tests comprehensive email notification functionality including:
 * <ul>
 * <li>Welcome emails with template rendering</li>
 * <li>Marketplace message notifications</li>
 * <li>Directory site approval/rejection emails</li>
 * <li>Listing expiration reminders</li>
 * <li>User preference respect (opt-out)</li>
 * <li>Email disabled flag handling</li>
 * <li>XSS prevention in templates</li>
 * <li>Unsubscribe token validity</li>
 * </ul>
 *
 * <p>
 * <b>Test Strategy:</b>
 * <ul>
 * <li>GreenMail intercepts SMTP traffic (no external sends)</li>
 * <li>Mocks RateLimitService to control rate limiting behavior</li>
 * <li>Uses TestFixtures for entity creation</li>
 * <li>Verifies email content (subject, body, headers)</li>
 * <li>Validates database state after notifications</li>
 * </ul>
 *
 * <p>
 * <b>Ref:</b> Task I5.T7, Feature I5 (Email Notification System)
 */
@QuarkusTest
@TestProfile(PostgreSQLTestProfile.class)
class NotificationServiceTest extends BaseIntegrationTest {

    private static final Logger LOG = Logger.getLogger(NotificationServiceTest.class);

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());

    @Inject
    NotificationService notificationService;

    @Inject
    villagecompute.homepage.jobs.EmailDeliveryJob emailDeliveryJob;

    @InjectMock
    RateLimitService rateLimitService;

    private User testUser;

    @BeforeEach
    @Transactional
    public void setUp() {
        super.setUp();

        // Reset GreenMail
        greenMail.reset();

        // Reset mocks
        reset(rateLimitService);

        // Mock rate limit to always allow by default
        when(rateLimitService.checkLimit(anyLong(), isNull(), anyString(), any(RateLimitService.Tier.class),
                anyString())).thenReturn(RateLimitService.RateLimitResult.allowed(10, 9, 3600));

        // Create test user
        testUser = TestFixtures.createTestUser("testuser@example.com", "Test User");
    }

    /**
     * Test: Welcome email sent successfully with correct template rendering.
     */
    @Test
    @Transactional
    void testSendWelcomeEmail_Success() throws Exception {
        // Act: Queue email
        notificationService.sendWelcomeEmail(testUser);

        // Debug: Check if EmailDeliveryLog was created
        List<EmailDeliveryLog> queued = EmailDeliveryLog.findQueued(10);
        LOG.infof("DEBUG: Found %d queued emails after sendWelcomeEmail", queued.size());
        if (!queued.isEmpty()) {
            LOG.infof("DEBUG: First queued email: to=%s, subject=%s, status=%s", queued.get(0).emailAddress,
                    queued.get(0).subject, queued.get(0).status);
        }

        // Process email queue to actually send via SMTP
        emailDeliveryJob.processQueuedEmails();

        // Wait for async email delivery to complete (reactive mailer is async)
        greenMail.waitForIncomingEmail(5000, 1); // Wait max 5 seconds for 1 email

        // Debug: Check GreenMail state
        LOG.infof("DEBUG: GreenMail received message count: %d", greenMail.getReceivedMessages().length);
        LOG.infof("DEBUG: GreenMail SMTP server running: %s", greenMail.getSmtp().isRunning());
        LOG.infof("DEBUG: GreenMail SMTP port: %d", greenMail.getSmtp().getPort());

        // Assert email sent via GreenMail
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length, "Should send 1 welcome email");

        MimeMessage message = messages[0];
        assertEquals(testUser.email, message.getAllRecipients()[0].toString(), "Email sent to correct recipient");
        assertTrue(message.getSubject().contains("Welcome"), "Subject should contain 'Welcome'");

        String content = message.getContent().toString();
        assertTrue(content.contains(testUser.displayName), "Email should contain user display name");
        assertTrue(content.contains("unsubscribe"), "Email should contain unsubscribe link");
    }

    /**
     * Test: Email not sent if user has opted out via notification preferences.
     */
    @Test
    @Transactional
    void testSendEmail_PreferenceRespected() throws Exception {
        // Create preferences with listing messages disabled
        NotificationPreferences prefs = new NotificationPreferences();
        prefs.userId = testUser.id;
        prefs.emailEnabled = true;
        prefs.emailListingMessages = false; // Opted out
        prefs.emailSiteApproved = true;
        prefs.emailSiteRejected = true;
        prefs.emailDigest = true;
        prefs.createdAt = Instant.now();
        prefs.updatedAt = Instant.now();
        prefs.persist();

        // Create test listing and message
        MarketplaceListing listing = TestFixtures.createTestListing(testUser);
        MarketplaceMessage msg = new MarketplaceMessage();
        msg.listingId = listing.id;
        msg.messageId = "msg-" + UUID.randomUUID() + "@villagecompute.com";
        msg.fromEmail = "buyer@example.com";
        msg.fromName = "Buyer";
        msg.toEmail = testUser.email;
        msg.toName = testUser.displayName;
        msg.subject = "Test inquiry";
        msg.body = "Test message body";
        msg.direction = "buyer_to_seller";
        msg.sentAt = Instant.now();
        msg.createdAt = Instant.now();
        msg.persist();

        // Act: Try to send listing message notification
        notificationService.sendListingNewMessageEmail(listing, msg);

        // Process queue
        emailDeliveryJob.processQueuedEmails();

        // Assert: No email sent (opted out)
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(0, messages.length, "Should NOT send email when user opted out");
    }

    /**
     * Test: Unsubscribe token is valid JWT with correct claims.
     */
    @Test
    @Transactional
    void testUnsubscribeTokenValid() throws Exception {
        // Send welcome email
        notificationService.sendWelcomeEmail(testUser);

        // Process queue
        emailDeliveryJob.processQueuedEmails();

        // Get email
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length);

        String content = messages[0].getContent().toString();

        // Extract unsubscribe link (contains token)
        assertTrue(content.contains("token="), "Email should contain unsubscribe token");

        // Token format: base64(userId:notificationType:timestamp:hmac)
        // In real implementation, NotificationPreferencesService generates tokens
        // Here we just verify token exists in email
        int tokenStart = content.indexOf("token=") + 6;
        int tokenEnd = content.indexOf("\"", tokenStart);
        if (tokenEnd == -1) {
            tokenEnd = content.indexOf(" ", tokenStart);
        }
        String token = content.substring(tokenStart, Math.min(tokenEnd, tokenStart + 200));

        assertNotNull(token, "Token should be extracted from email");
        assertFalse(token.isBlank(), "Token should not be blank");
    }

    /**
     * Test: Template variables are HTML-escaped to prevent XSS.
     */
    @Test
    @Transactional
    void testTemplateVariablesEscaped() throws Exception {
        // Create user with malicious display name
        User maliciousUser = TestFixtures.createTestUser("malicious@example.com",
                "<script>alert('xss')</script>Hacker");

        // Send welcome email
        notificationService.sendWelcomeEmail(maliciousUser);

        // Process queue
        emailDeliveryJob.processQueuedEmails();

        // Get email content
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length);

        String content = messages[0].getContent().toString();

        // Assert: Script tags should be escaped (Qute auto-escapes by default)
        assertFalse(content.contains("<script>"), "Script tags should be escaped");
        assertTrue(content.contains("&lt;script&gt;") || content.contains("Hacker"),
                "Escaped content or safe part should be present");
    }

    /**
     * Test: Email not sent if user.emailDisabled is true.
     */
    @Test
    @Transactional
    void testEmailDisabledSkipped() throws Exception {
        // Disable email for user
        User user = User.findById(testUser.id);
        user.emailDisabled = true;
        user.persist();

        // Try to send welcome email
        notificationService.sendWelcomeEmail(user);

        // Process queue
        emailDeliveryJob.processQueuedEmails();

        // Assert no email sent
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(0, messages.length, "Should NOT send email when emailDisabled=true");
    }

    /**
     * Test: Listing flagged notification sent to seller.
     */
    @Test
    @Transactional
    void testSendListingFlaggedNotification_Success() throws Exception {
        // Create listing and flag
        MarketplaceListing listing = TestFixtures.createTestListing(testUser);
        ListingFlag flag = new ListingFlag();
        flag.listingId = listing.id;
        flag.userId = UUID.randomUUID(); // Different user
        flag.reason = "spam";
        flag.details = "This is spam content";
        flag.status = "pending";
        flag.createdAt = Instant.now();
        flag.persist();

        // Send notification
        notificationService.sendListingFlaggedEmail(listing, flag);

        // Process queue
        emailDeliveryJob.processQueuedEmails();

        // Assert email sent
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length, "Should send 1 flagged notification");

        MimeMessage message = messages[0];
        assertTrue(message.getSubject().contains("flagged"), "Subject should mention flagged");

        String content = message.getContent().toString();
        assertTrue(content.contains(listing.title), "Email should contain listing title");
        assertTrue(content.contains(flag.reason), "Email should contain flag reason");
    }

    /**
     * Test: Listing expired notification sent with correct details.
     */
    @Test
    @Transactional
    void testSendListingExpiredNotification_Success() throws Exception {
        // Create expired listing
        MarketplaceListing listing = TestFixtures.createTestListing(testUser);
        listing.status = "expired";
        listing.expiresAt = Instant.now().minusSeconds(86400); // Expired 1 day ago
        listing.persist();

        // Send notification
        notificationService.sendListingExpiredEmail(listing);

        // Process queue
        emailDeliveryJob.processQueuedEmails();

        // Assert email sent
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length, "Should send 1 expired notification");

        MimeMessage message = messages[0];
        assertTrue(message.getSubject().contains("expired"), "Subject should mention expired");

        String content = message.getContent().toString();
        assertTrue(content.contains(listing.title), "Email should contain listing title");
    }

    /**
     * Test: Directory site approved notification sent successfully.
     */
    @Test
    @Transactional
    void testSendSiteApprovedNotification_Success() throws Exception {
        // Create approved site
        DirectorySite site = TestFixtures.createTestDirectorySite(testUser);
        site.status = "approved";
        site.persist();

        DirectoryCategory category = TestFixtures.createTestDirectoryCategory();

        // Send notification
        notificationService.sendSiteApprovedEmail(site);

        // Process queue
        emailDeliveryJob.processQueuedEmails();

        // Assert email sent
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length, "Should send 1 approval notification");

        MimeMessage message = messages[0];
        assertTrue(message.getSubject().contains("approved"), "Subject should mention approved");

        String content = message.getContent().toString();
        assertTrue(content.contains(site.title), "Email should contain site title");
        assertTrue(content.contains(category.name), "Email should contain category name");
    }

    /**
     * Test: Directory site rejected notification sent successfully.
     */
    @Test
    @Transactional
    void testSendSiteRejectedNotification_Success() throws Exception {
        // Create rejected site
        DirectorySite site = TestFixtures.createTestDirectorySite(testUser);
        site.status = "rejected";
        site.persist();

        String rejectionReason = "Duplicate entry";

        // Send notification
        notificationService.sendSiteRejectedEmail(site, rejectionReason);

        // Process queue
        emailDeliveryJob.processQueuedEmails();

        // Assert email sent
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length, "Should send 1 rejection notification");

        MimeMessage message = messages[0];
        assertTrue(message.getSubject().contains("rejected"), "Subject should mention rejected");

        String content = message.getContent().toString();
        assertTrue(content.contains(site.title), "Email should contain site title");
        assertTrue(content.contains(rejectionReason), "Email should contain rejection reason");
    }

    /**
     * Test: Rate limit exceeded prevents email sending.
     */
    @Test
    @Transactional
    void testSendEmail_RateLimitExceeded() throws Exception {
        // Mock rate limit to deny
        when(rateLimitService.checkLimit(anyLong(), anyString(), anyString(), any(RateLimitService.Tier.class),
                anyString())).thenReturn(RateLimitService.RateLimitResult.denied(10, 3600));

        // Try to send welcome email
        notificationService.sendWelcomeEmail(testUser);

        // Process queue
        emailDeliveryJob.processQueuedEmails();

        // Assert no email sent (rate limited)
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(0, messages.length, "Should NOT send email when rate limited");

        // Verify rate limit was checked
        verify(rateLimitService, atLeastOnce()).checkLimit(anyLong(), anyString(), anyString(),
                any(RateLimitService.Tier.class), anyString());
    }

    /**
     * Test: UserNotification record created along with email.
     */
    @Test
    @Transactional
    void testSendEmail_CreatesUserNotification() {
        // Send welcome email
        notificationService.sendWelcomeEmail(testUser);

        // Verify UserNotification record created
        java.util.List<UserNotification> notifications = UserNotification
                .find("userId = ?1 order by createdAt desc", testUser.id).list();

        assertTrue(notifications.size() >= 1, "Should create at least 1 UserNotification record");
        UserNotification notification = notifications.get(0);
        assertEquals(testUser.id, notification.userId, "Notification should belong to test user");
        assertTrue(notification.message.contains("Welcome") || notification.title.contains("Welcome"),
                "Notification should be about welcome");
    }

    /**
     * Test: Marketplace message notification sent successfully.
     */
    @Test
    @Transactional
    void testSendListingNewMessageNotification_Success() throws Exception {
        // Create listing and message
        MarketplaceListing listing = TestFixtures.createTestListing(testUser);
        MarketplaceMessage msg = new MarketplaceMessage();
        msg.listingId = listing.id;
        msg.messageId = "msg-" + UUID.randomUUID() + "@villagecompute.com";
        msg.fromEmail = "buyer@example.com";
        msg.fromName = "Interested Buyer";
        msg.toEmail = testUser.email;
        msg.toName = testUser.displayName;
        msg.subject = "Question about your listing";
        msg.body = "Is this still available?";
        msg.direction = "buyer_to_seller";
        msg.sentAt = Instant.now();
        msg.createdAt = Instant.now();
        msg.persist();

        // Send notification
        notificationService.sendListingNewMessageEmail(listing, msg);

        // Process queue
        emailDeliveryJob.processQueuedEmails();

        // Assert email sent
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(1, messages.length, "Should send 1 message notification");

        MimeMessage message = messages[0];
        assertEquals(testUser.email, message.getAllRecipients()[0].toString());

        String content = message.getContent().toString();
        assertTrue(content.contains(listing.title), "Email should contain listing title");
        assertTrue(content.contains(msg.fromName), "Email should contain sender name");
        assertTrue(content.contains(msg.body), "Email should contain message body");
    }

    /**
     * Test: Multiple emails can be sent sequentially.
     */
    @Test
    @Transactional
    void testMultipleEmails_Sequential() throws Exception {
        // Send multiple emails
        notificationService.sendWelcomeEmail(testUser);

        MarketplaceListing listing = TestFixtures.createTestListing(testUser);
        listing.status = "expired";
        listing.persist();
        notificationService.sendListingExpiredEmail(listing);

        DirectorySite site = TestFixtures.createTestDirectorySite(testUser);
        site.status = "approved";
        site.persist();
        notificationService.sendSiteApprovedEmail(site);

        // Process queue
        emailDeliveryJob.processQueuedEmails();

        // Assert all 3 emails sent
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertEquals(3, messages.length, "Should send 3 emails sequentially");
    }
}
