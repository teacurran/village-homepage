package villagecompute.homepage.services;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.transaction.Transactional;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EmailNotificationService.
 *
 * <p>
 * Tests email template rendering, rate limiting enforcement, and error handling for all notification types.
 */
@QuarkusTest
class EmailNotificationServiceTest {

    @Inject
    EmailNotificationService emailNotificationService;

    @InjectMock
    Mailer mailer;

    @InjectMock
    RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        // Reset mocks before each test
        reset(mailer, rateLimitService);
    }

    @Test
    void testSendProfilePublishedNotification_Success() {
        // Arrange
        UUID userId = UUID.randomUUID();
        String email = "user@example.com";
        String username = "testuser";
        String templateType = "public_homepage";
        RateLimitService.Tier tier = RateLimitService.Tier.LOGGED_IN;

        // Mock rate limit check to allow request
        when(rateLimitService.checkLimit(anyLong(), isNull(), eq("email.profile_notification"), eq(tier), anyString()))
                .thenReturn(RateLimitService.RateLimitResult.allowed(5, 4, 3600));

        // Act
        emailNotificationService.sendProfilePublishedNotification(userId, email, username, templateType, tier);

        // Assert
        verify(rateLimitService, times(1)).checkLimit(anyLong(), isNull(), eq("email.profile_notification"), eq(tier),
                anyString());

        ArgumentCaptor<Mail> mailCaptor = ArgumentCaptor.forClass(Mail.class);
        verify(mailer, times(1)).send(mailCaptor.capture());

        Mail sentMail = mailCaptor.getValue();
        assertNotNull(sentMail);
        assertTrue(sentMail.getSubject().contains("Your profile is now public"));
        assertTrue(sentMail.getHtml().contains(username));
        assertTrue(sentMail.getHtml().contains(templateType));
    }

    @Test
    void testSendProfilePublishedNotification_RateLimitExceeded() {
        // Arrange
        UUID userId = UUID.randomUUID();
        String email = "user@example.com";
        String username = "testuser";
        String templateType = "public_homepage";
        RateLimitService.Tier tier = RateLimitService.Tier.LOGGED_IN;

        // Mock rate limit check to deny request
        when(rateLimitService.checkLimit(anyLong(), isNull(), eq("email.profile_notification"), eq(tier), anyString()))
                .thenReturn(RateLimitService.RateLimitResult.denied(5, 3600));

        // Act
        emailNotificationService.sendProfilePublishedNotification(userId, email, username, templateType, tier);

        // Assert
        verify(rateLimitService, times(1)).checkLimit(anyLong(), isNull(), eq("email.profile_notification"), eq(tier),
                anyString());

        // Verify email was NOT sent due to rate limit
        verify(mailer, never()).send(any(Mail.class));
    }

    @Test
    void testSendProfileUnpublishedNotification_Success() {
        // Arrange
        UUID userId = UUID.randomUUID();
        String email = "user@example.com";
        String username = "testuser";
        RateLimitService.Tier tier = RateLimitService.Tier.LOGGED_IN;

        // Mock rate limit check to allow request
        when(rateLimitService.checkLimit(anyLong(), isNull(), eq("email.profile_notification"), eq(tier), anyString()))
                .thenReturn(RateLimitService.RateLimitResult.allowed(5, 4, 3600));

        // Act
        emailNotificationService.sendProfileUnpublishedNotification(userId, email, username, tier);

        // Assert
        verify(rateLimitService, times(1)).checkLimit(anyLong(), isNull(), eq("email.profile_notification"), eq(tier),
                anyString());

        ArgumentCaptor<Mail> mailCaptor = ArgumentCaptor.forClass(Mail.class);
        verify(mailer, times(1)).send(mailCaptor.capture());

        Mail sentMail = mailCaptor.getValue();
        assertNotNull(sentMail);
        assertTrue(sentMail.getSubject().contains("Your profile is now private"));
        assertTrue(sentMail.getHtml().contains(username));
    }

    @Test
    void testSendProfileUnpublishedNotification_RateLimitExceeded() {
        // Arrange
        UUID userId = UUID.randomUUID();
        String email = "user@example.com";
        String username = "testuser";
        RateLimitService.Tier tier = RateLimitService.Tier.LOGGED_IN;

        // Mock rate limit check to deny request
        when(rateLimitService.checkLimit(anyLong(), isNull(), eq("email.profile_notification"), eq(tier), anyString()))
                .thenReturn(RateLimitService.RateLimitResult.denied(5, 3600));

        // Act
        emailNotificationService.sendProfileUnpublishedNotification(userId, email, username, tier);

        // Assert
        verify(rateLimitService, times(1)).checkLimit(anyLong(), isNull(), eq("email.profile_notification"), eq(tier),
                anyString());

        // Verify email was NOT sent due to rate limit
        verify(mailer, never()).send(any(Mail.class));
    }

    @Test
    void testSendAiBudgetAlert_WarningLevel() {
        // Arrange
        String level = "WARNING";
        double percentUsed = 77.5;
        int costCents = 38750; // $387.50
        int budgetCents = 50000; // $500
        String action = "REDUCE";

        // Mock rate limit check to allow request
        when(rateLimitService.checkLimit(isNull(), anyString(), eq("email.analytics_alert"),
                eq(RateLimitService.Tier.TRUSTED), anyString()))
                .thenReturn(RateLimitService.RateLimitResult.allowed(3, 2, 3600));

        // Act
        emailNotificationService.sendAiBudgetAlert(level, percentUsed, costCents, budgetCents, action);

        // Assert
        verify(rateLimitService, times(1)).checkLimit(isNull(), anyString(), eq("email.analytics_alert"),
                eq(RateLimitService.Tier.TRUSTED), anyString());

        ArgumentCaptor<Mail> mailCaptor = ArgumentCaptor.forClass(Mail.class);
        verify(mailer, times(1)).send(mailCaptor.capture());

        Mail sentMail = mailCaptor.getValue();
        assertNotNull(sentMail);
        assertTrue(sentMail.getSubject().contains("[WARNING]"));
        assertTrue(sentMail.getSubject().contains("77.5%"));
        assertTrue(sentMail.getHtml().contains("WARNING"));
        assertTrue(sentMail.getHtml().contains("387.50"));
        assertTrue(sentMail.getHtml().contains("500"));
        assertTrue(sentMail.getHtml().contains("REDUCE"));
    }

    @Test
    void testSendAiBudgetAlert_CriticalLevel() {
        // Arrange
        String level = "CRITICAL";
        double percentUsed = 92.0;
        int costCents = 46000; // $460
        int budgetCents = 50000; // $500
        String action = "QUEUE";

        // Mock rate limit check to allow request
        when(rateLimitService.checkLimit(isNull(), anyString(), eq("email.analytics_alert"),
                eq(RateLimitService.Tier.TRUSTED), anyString()))
                .thenReturn(RateLimitService.RateLimitResult.allowed(3, 2, 3600));

        // Act
        emailNotificationService.sendAiBudgetAlert(level, percentUsed, costCents, budgetCents, action);

        // Assert
        ArgumentCaptor<Mail> mailCaptor = ArgumentCaptor.forClass(Mail.class);
        verify(mailer, times(1)).send(mailCaptor.capture());

        Mail sentMail = mailCaptor.getValue();
        assertTrue(sentMail.getSubject().contains("[CRITICAL]"));
        assertTrue(sentMail.getHtml().contains("CRITICAL"));
        assertTrue(sentMail.getHtml().contains("QUEUE"));
    }

    @Test
    void testSendAiBudgetAlert_EmergencyLevel() {
        // Arrange
        String level = "EMERGENCY";
        double percentUsed = 100.0;
        int costCents = 50000; // $500
        int budgetCents = 50000; // $500
        String action = "HARD_STOP";

        // Mock rate limit check to allow request
        when(rateLimitService.checkLimit(isNull(), anyString(), eq("email.analytics_alert"),
                eq(RateLimitService.Tier.TRUSTED), anyString()))
                .thenReturn(RateLimitService.RateLimitResult.allowed(3, 2, 3600));

        // Act
        emailNotificationService.sendAiBudgetAlert(level, percentUsed, costCents, budgetCents, action);

        // Assert
        ArgumentCaptor<Mail> mailCaptor = ArgumentCaptor.forClass(Mail.class);
        verify(mailer, times(1)).send(mailCaptor.capture());

        Mail sentMail = mailCaptor.getValue();
        assertTrue(sentMail.getSubject().contains("[EMERGENCY]"));
        assertTrue(sentMail.getHtml().contains("EMERGENCY"));
        assertTrue(sentMail.getHtml().contains("HARD_STOP"));
        assertTrue(sentMail.getHtml().contains("100.0"));
    }

    @Test
    void testSendAiBudgetAlert_RateLimitExceeded() {
        // Arrange
        String level = "WARNING";
        double percentUsed = 77.5;
        int costCents = 38750;
        int budgetCents = 50000;
        String action = "REDUCE";

        // Mock rate limit check to deny request
        when(rateLimitService.checkLimit(isNull(), anyString(), eq("email.analytics_alert"),
                eq(RateLimitService.Tier.TRUSTED), anyString()))
                .thenReturn(RateLimitService.RateLimitResult.denied(3, 3600));

        // Act
        emailNotificationService.sendAiBudgetAlert(level, percentUsed, costCents, budgetCents, action);

        // Assert
        verify(rateLimitService, times(1)).checkLimit(isNull(), anyString(), eq("email.analytics_alert"),
                eq(RateLimitService.Tier.TRUSTED), anyString());

        // Verify email was NOT sent due to rate limit
        verify(mailer, never()).send(any(Mail.class));
    }

    @Test
    void testEmailDataRecords_TypeSafety() {
        // Test that data records can be created and accessed
        EmailNotificationService.ProfilePublishedEmailData publishedData = new EmailNotificationService.ProfilePublishedEmailData(
                "testuser", "public_homepage", "https://example.com/u/testuser", "https://example.com/profile/edit",
                "https://example.com");

        assertEquals("testuser", publishedData.username());
        assertEquals("public_homepage", publishedData.templateType());
        assertEquals("https://example.com/u/testuser", publishedData.profileUrl());

        EmailNotificationService.ProfileUnpublishedEmailData unpublishedData = new EmailNotificationService.ProfileUnpublishedEmailData(
                "testuser", "https://example.com/profile/edit", "https://example.com");

        assertEquals("testuser", unpublishedData.username());
        assertEquals("https://example.com/profile/edit", unpublishedData.editUrl());

        EmailNotificationService.AiBudgetAlertEmailData alertData = new EmailNotificationService.AiBudgetAlertEmailData(
                "WARNING", "77.5", "387.50", "500.00", "112.50", "REDUCE", "https://example.com/admin/analytics",
                "https://example.com");

        assertEquals("WARNING", alertData.level());
        assertEquals("77.5", alertData.percentUsed());
        assertEquals("REDUCE", alertData.action());
    }
}
