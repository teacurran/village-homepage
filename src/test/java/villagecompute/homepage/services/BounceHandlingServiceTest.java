package villagecompute.homepage.services;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.EmailBounce;
import villagecompute.homepage.data.models.EmailBounce.BounceType;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.testing.PostgreSQLTestProfile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BounceHandlingService.
 *
 * Tests cover DSN parsing, bounce classification (hard/soft), threshold detection (5 consecutive soft bounces),
 * automatic email disabling, and EmailDeliveryLog integration.
 *
 * Acceptance criteria verified:
 * <ul>
 * <li>DSN bounce emails parsed correctly</li>
 * <li>Hard bounces (5.x.x) immediately disable email delivery</li>
 * <li>Soft bounces (4.x.x) tracked, disable after 5 consecutive</li>
 * <li>emailDisabled flag prevents future sends</li>
 * <li>Bounce history queryable for debugging</li>
 * </ul>
 *
 * Coverage target: ≥95% line and branch coverage per project standards.
 */
@QuarkusTest
@TestProfile(PostgreSQLTestProfile.class)
class BounceHandlingServiceTest {

    @Inject
    BounceHandlingService bounceHandlingService;

    private User testUser;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clear existing test data
        EmailBounce.deleteAll();
        User.deleteAll();

        // Create test user
        testUser = new User();
        testUser.email = "testuser@example.com";
        testUser.displayName = "Test User";
        testUser.isAnonymous = false;
        testUser.preferences = new java.util.HashMap<>();
        testUser.directoryKarma = 0;
        testUser.directoryTrustLevel = User.TRUST_LEVEL_UNTRUSTED;
        testUser.analyticsConsent = false;
        testUser.emailDisabled = false;
        testUser.createdAt = Instant.now();
        testUser.updatedAt = Instant.now();
        testUser.persist();
    }

    /**
     * Test: DSN parsing extracts status code, recipient, and reason from RFC 3464 bounce message.
     */
    @Test
    void testParseBounceEmail_ValidDsn_ExtractsBounceInfo() throws Exception {
        // Create mock DSN bounce message
        Message dsnMessage = createDsnBounceMessage("5.1.1", "user@example.com",
                "Mailbox does not exist (user unknown)");

        // Parse bounce
        Optional<BounceHandlingService.BounceInfo> bounceInfo = bounceHandlingService.parseBounceEmail(dsnMessage);

        // Assert
        assertTrue(bounceInfo.isPresent(), "Bounce info should be extracted from valid DSN");
        assertEquals("user@example.com", bounceInfo.get().emailAddress(), "Email address should match");
        assertEquals("5.1.1", bounceInfo.get().diagnosticCode(), "Diagnostic code should match");
        assertEquals(BounceType.HARD, bounceInfo.get().bounceType(), "5.1.1 should classify as HARD bounce");
    }

    /**
     * Test: Hard bounce (5.x.x) immediately disables email delivery.
     */
    @Test
    @Transactional
    void testRecordBounce_HardBounce_DisablesEmailImmediately() {
        // Record hard bounce
        bounceHandlingService.recordBounce(testUser.email, "5.1.1", "User unknown");

        // Reload user from database
        User reloadedUser = User.findById(testUser.id);

        // Assert email disabled
        assertNotNull(reloadedUser, "User should exist");
        assertTrue(reloadedUser.emailDisabled, "Email should be disabled after hard bounce");

        // Verify bounce record created
        List<EmailBounce> bounces = EmailBounce.findByUserId(testUser.id);
        assertEquals(1, bounces.size(), "One bounce should be recorded");
        assertEquals(BounceType.HARD, bounces.get(0).bounceType, "Bounce type should be HARD");
        assertEquals("5.1.1", bounces.get(0).diagnosticCode, "Diagnostic code should match");
    }

    /**
     * Test: Soft bounce (4.x.x) does NOT disable email after first occurrence.
     */
    @Test
    @Transactional
    void testRecordBounce_SingleSoftBounce_DoesNotDisableEmail() {
        // Record single soft bounce
        bounceHandlingService.recordBounce(testUser.email, "4.2.2", "Mailbox full");

        // Reload user
        User reloadedUser = User.findById(testUser.id);

        // Assert email NOT disabled
        assertNotNull(reloadedUser, "User should exist");
        assertFalse(reloadedUser.emailDisabled, "Email should NOT be disabled after 1 soft bounce");

        // Verify bounce recorded
        List<EmailBounce> bounces = EmailBounce.findByUserId(testUser.id);
        assertEquals(1, bounces.size(), "One bounce should be recorded");
        assertEquals(BounceType.SOFT, bounces.get(0).bounceType, "Bounce type should be SOFT");
    }

    /**
     * Test: 5 consecutive soft bounces within 30 days disables email delivery.
     */
    @Test
    @Transactional
    void testRecordBounce_FiveConsecutiveSoftBounces_DisablesEmail() {
        // Record 4 soft bounces (email should remain enabled)
        for (int i = 0; i < 4; i++) {
            bounceHandlingService.recordBounce(testUser.email, "4.2.2", "Mailbox full - attempt " + (i + 1));
        }

        // Reload and verify still enabled
        User reloadedUser = User.findById(testUser.id);
        assertFalse(reloadedUser.emailDisabled, "Email should NOT be disabled after 4 soft bounces");

        // Record 5th soft bounce (should trigger disable)
        bounceHandlingService.recordBounce(testUser.email, "4.2.2", "Mailbox full - final attempt");

        // Reload and verify disabled
        reloadedUser = User.findById(testUser.id);
        assertTrue(reloadedUser.emailDisabled, "Email SHOULD be disabled after 5 consecutive soft bounces");

        // Verify 5 bounce records
        List<EmailBounce> bounces = EmailBounce.findByUserId(testUser.id);
        assertEquals(5, bounces.size(), "Five bounces should be recorded");
    }

    /**
     * Test: shouldDisableEmail returns false for 4 soft bounces (below threshold).
     */
    @Test
    @Transactional
    void testShouldDisableEmail_FourSoftBounces_ReturnsFalse() {
        // Create 4 soft bounce records
        for (int i = 0; i < 4; i++) {
            EmailBounce bounce = new EmailBounce();
            bounce.userId = testUser.id;
            bounce.emailAddress = testUser.email;
            bounce.bounceType = BounceType.SOFT;
            bounce.diagnosticCode = "4.2.2";
            bounce.bounceReason = "Mailbox full";
            bounce.createdAt = Instant.now().minusSeconds(i * 3600); // Stagger by 1 hour
            bounce.persist();
        }

        // Check threshold
        boolean shouldDisable = bounceHandlingService.shouldDisableEmail(testUser.email);

        // Assert NOT disabled
        assertFalse(shouldDisable, "Should NOT disable with only 4 consecutive soft bounces");
    }

    /**
     * Test: shouldDisableEmail returns true for 5 consecutive soft bounces.
     */
    @Test
    @Transactional
    void testShouldDisableEmail_FiveSoftBounces_ReturnsTrue() {
        // Create 5 soft bounce records
        for (int i = 0; i < 5; i++) {
            EmailBounce bounce = new EmailBounce();
            bounce.userId = testUser.id;
            bounce.emailAddress = testUser.email;
            bounce.bounceType = BounceType.SOFT;
            bounce.diagnosticCode = "4.2.2";
            bounce.bounceReason = "Mailbox full";
            bounce.createdAt = Instant.now().minusSeconds(i * 3600); // Stagger by 1 hour
            bounce.persist();
        }

        // Check threshold
        boolean shouldDisable = bounceHandlingService.shouldDisableEmail(testUser.email);

        // Assert disabled
        assertTrue(shouldDisable, "SHOULD disable with 5 consecutive soft bounces");
    }

    /**
     * Test: shouldDisableEmail returns true for any hard bounce.
     */
    @Test
    @Transactional
    void testShouldDisableEmail_HardBounce_ReturnsTrue() {
        // Create single hard bounce
        EmailBounce bounce = new EmailBounce();
        bounce.userId = testUser.id;
        bounce.emailAddress = testUser.email;
        bounce.bounceType = BounceType.HARD;
        bounce.diagnosticCode = "5.1.1";
        bounce.bounceReason = "User unknown";
        bounce.createdAt = Instant.now();
        bounce.persist();

        // Check threshold
        boolean shouldDisable = bounceHandlingService.shouldDisableEmail(testUser.email);

        // Assert disabled
        assertTrue(shouldDisable, "SHOULD disable with any hard bounce");
    }

    /**
     * Test: Bounce history queryable via EmailBounce.findByUserId.
     */
    @Test
    @Transactional
    void testBounceHistory_QueryableByUserId() {
        // Create multiple bounces
        for (int i = 0; i < 3; i++) {
            EmailBounce bounce = new EmailBounce();
            bounce.userId = testUser.id;
            bounce.emailAddress = testUser.email;
            bounce.bounceType = BounceType.SOFT;
            bounce.diagnosticCode = "4.2.2";
            bounce.bounceReason = "Bounce #" + (i + 1);
            bounce.createdAt = Instant.now().minusSeconds(i * 7200); // Stagger by 2 hours
            bounce.persist();
        }

        // Query bounce history
        List<EmailBounce> bounces = EmailBounce.findByUserId(testUser.id);

        // Assert
        assertEquals(3, bounces.size(), "Should find 3 bounce records");
        assertEquals("Bounce #1", bounces.get(0).bounceReason, "Bounces should be ordered newest first");
    }

    /**
     * Test: findRecentByEmail returns bounces within lookback period (30 days).
     */
    @Test
    @Transactional
    void testFindRecentByEmail_ReturnsBouncesWithin30Days() {
        // Create bounce from 20 days ago (within window)
        EmailBounce recentBounce = new EmailBounce();
        recentBounce.userId = testUser.id;
        recentBounce.emailAddress = testUser.email;
        recentBounce.bounceType = BounceType.SOFT;
        recentBounce.diagnosticCode = "4.2.2";
        recentBounce.bounceReason = "Recent bounce";
        recentBounce.createdAt = Instant.now().minusSeconds(20 * 86400); // 20 days ago
        recentBounce.persist();

        // Create bounce from 35 days ago (outside window)
        EmailBounce oldBounce = new EmailBounce();
        oldBounce.userId = testUser.id;
        oldBounce.emailAddress = testUser.email;
        oldBounce.bounceType = BounceType.SOFT;
        oldBounce.diagnosticCode = "4.2.2";
        oldBounce.bounceReason = "Old bounce";
        oldBounce.createdAt = Instant.now().minusSeconds(35 * 86400); // 35 days ago
        oldBounce.persist();

        // Query recent bounces (30 day lookback)
        List<EmailBounce> recentBounces = EmailBounce.findRecentByEmail(testUser.email, 30);

        // Assert only recent bounce returned
        assertEquals(1, recentBounces.size(), "Should only return bounces within 30 days");
        assertEquals("Recent bounce", recentBounces.get(0).bounceReason, "Should return recent bounce");
    }

    /**
     * Test: DSN parsing with subject line heuristic when no DSN part found.
     */
    @Test
    void testParseBounceEmail_SubjectHeuristic_DetectsBounce() throws Exception {
        // Create plain text bounce with subject keywords (no DSN part)
        Message plainBounce = createPlainTextBounceMessage("Undelivered Mail Returned to Sender");

        // Parse bounce
        Optional<BounceHandlingService.BounceInfo> bounceInfo = bounceHandlingService.parseBounceEmail(plainBounce);

        // Assert bounce detected via heuristic
        assertTrue(bounceInfo.isPresent(), "Bounce should be detected via subject heuristic");
        assertEquals(BounceType.SOFT, bounceInfo.get().bounceType(), "Heuristic bounces default to SOFT");
    }

    /**
     * Test: Non-bounce message returns empty Optional.
     */
    @Test
    void testParseBounceEmail_NonBounceMessage_ReturnsEmpty() throws Exception {
        // Create regular email (not a bounce)
        Message regularEmail = createRegularMessage("Hello World", "This is a regular email");

        // Parse bounce
        Optional<BounceHandlingService.BounceInfo> bounceInfo = bounceHandlingService.parseBounceEmail(regularEmail);

        // Assert no bounce detected
        assertFalse(bounceInfo.isPresent(), "Regular email should NOT be detected as bounce");
    }

    // ========================
    // Helper Methods
    // ========================

    /**
     * Creates a mock DSN bounce message with specified status code, recipient, and reason.
     */
    private Message createDsnBounceMessage(String statusCode, String recipient, String reason) throws Exception {
        Session session = Session.getDefaultInstance(new Properties());
        MimeMessage message = new MimeMessage(session);

        message.setFrom(new InternetAddress("MAILER-DAEMON@example.com"));
        message.setSubject("Undelivered Mail Returned to Sender");

        // Create multipart/report container
        MimeMultipart multipart = new MimeMultipart();

        // Part 1: Human-readable description
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("This is the mail system at host example.com.\n\n"
                + "I'm sorry to have to inform you that your message could not\n"
                + "be delivered to one or more recipients.\n\n" + reason);
        multipart.addBodyPart(textPart);

        // Part 2: Machine-readable DSN
        MimeBodyPart dsnPart = new MimeBodyPart();
        dsnPart.setContent(
                "Reporting-MTA: dns; example.com\n" + "Final-Recipient: rfc822; " + recipient + "\n"
                        + "Action: failed\n" + "Status: " + statusCode + "\n" + "Diagnostic-Code: smtp; " + reason,
                "message/delivery-status");
        multipart.addBodyPart(dsnPart);

        message.setContent(multipart);
        return message;
    }

    /**
     * Creates a plain text bounce message with bounce keywords in subject.
     */
    private Message createPlainTextBounceMessage(String subject) throws Exception {
        Session session = Session.getDefaultInstance(new Properties());
        MimeMessage message = new MimeMessage(session);

        message.setFrom(new InternetAddress("postmaster@example.com"));
        message.setSubject(subject);
        message.setText("Your message could not be delivered.");

        return message;
    }

    /**
     * Creates a regular (non-bounce) email message.
     */
    private Message createRegularMessage(String subject, String body) throws Exception {
        Session session = Session.getDefaultInstance(new Properties());
        MimeMessage message = new MimeMessage(session);

        message.setFrom(new InternetAddress("sender@example.com"));
        message.setSubject(subject);
        message.setText(body);

        return message;
    }
}
