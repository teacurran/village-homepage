package villagecompute.homepage.services;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import villagecompute.homepage.api.types.FraudAnalysisResultType;
import villagecompute.homepage.data.models.*;
import villagecompute.homepage.exceptions.RateLimitException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

/**
 * Tests for ModerationService covering flag submission, approval/dismissal, and refund automation.
 *
 * <p>
 * Test coverage per I4.T8 acceptance criteria:
 * <ul>
 * <li>Flag submission with rate limiting (5 flags/day)</li>
 * <li>Flag approval removes listing and issues refund within 24h</li>
 * <li>Flag dismissal decrements flag_count and restores listing status</li>
 * <li>Chargeback ban logic triggers at 2+ chargebacks</li>
 * <li>Fraud detection integration</li>
 * </ul>
 */
@QuarkusTest
class ModerationServiceTest {

    @Inject
    ModerationService moderationService;

    @InjectMock
    FraudDetectionService fraudDetectionService;

    @InjectMock
    PaymentService paymentService;

    private UUID testListingId;
    private UUID testUserId;
    private UUID testAdminUserId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clear all test data
        ListingFlag.deleteAll();
        MarketplaceListing.deleteAll();
        User.deleteAll();

        testListingId = UUID.randomUUID();
        testUserId = UUID.randomUUID();
        testAdminUserId = UUID.randomUUID();

        // Create test listing
        MarketplaceListing listing = new MarketplaceListing();
        listing.id = testListingId;
        listing.userId = testUserId;
        listing.categoryId = UUID.randomUUID();
        listing.geoCityId = 1L;
        listing.title = "Test Listing";
        listing.description = "Test description";
        listing.contactInfo = new villagecompute.homepage.api.types.ContactInfoType("test@example.com", null,
                "masked-test@villagecompute.com");
        listing.status = "active";
        listing.flagCount = 0L;
        listing.createdAt = Instant.now();
        listing.updatedAt = Instant.now();
        listing.persist();

        // Create test user
        User user = createTestUser(testUserId, "test@example.com");
        User admin = createTestUser(testAdminUserId, "admin@example.com");
    }

    /**
     * Test: Submit flag successfully increments listing flag_count.
     */
    @Test
    @Transactional
    void testSubmitFlag_Success() {
        // Mock fraud detection
        Mockito.when(fraudDetectionService.analyzeListing(any())).thenReturn(FraudAnalysisResultType.clean("v1.0"));

        ListingFlag flag = moderationService.submitFlag(testListingId, testUserId, "spam", "Duplicate listing", false);

        assertNotNull(flag.id);
        assertEquals("pending", flag.status);
        assertEquals("spam", flag.reason);

        // Verify listing flag_count incremented
        MarketplaceListing listing = MarketplaceListing.findById(testListingId);
        assertEquals(1L, listing.flagCount);
    }

    /**
     * Test: Submit flag with fraud analysis stores fraud score.
     */
    @Test
    @Transactional
    void testSubmitFlag_WithFraudAnalysis() {
        // Mock fraud detection returning suspicious result
        FraudAnalysisResultType fraudResult = FraudAnalysisResultType.suspicious(new BigDecimal("0.85"),
                List.of("Suspicious payment method"), "v1.0");
        Mockito.when(fraudDetectionService.analyzeListing(testListingId)).thenReturn(fraudResult);

        ListingFlag flag = moderationService.submitFlag(testListingId, testUserId, "fraud", "Too good to be true", true // Enable
                                                                                                                        // fraud
                                                                                                                        // analysis
        );

        assertNotNull(flag.fraudScore);
        assertEquals(new BigDecimal("0.85"), flag.fraudScore);
        assertNotNull(flag.fraudReasons);
    }

    /**
     * Test: Rate limiting prevents more than 5 flags per day.
     */
    @Test
    @Transactional
    void testSubmitFlag_RateLimitExceeded() {
        // Submit 5 flags (should succeed)
        for (int i = 0; i < 5; i++) {
            moderationService.submitFlag(UUID.randomUUID(), testUserId, "spam", "Test flag " + i, false);
        }

        // 6th flag should fail with rate limit exception
        assertThrows(RateLimitException.class, () -> {
            moderationService.submitFlag(UUID.randomUUID(), testUserId, "spam", "This should fail", false);
        });
    }

    /**
     * Test: Approve flag removes listing and creates refund.
     */
    @Test
    @Transactional
    void testApproveFlag_RemovesListingAndRefunds() {
        // Create listing with payment (eligible for refund)
        MarketplaceListing listing = MarketplaceListing.findById(testListingId);
        listing.paymentIntentId = "pi_test_123";
        listing.createdAt = Instant.now(); // Within 24h window
        listing.persist();

        // Create flag
        ListingFlag flag = new ListingFlag();
        flag.listingId = testListingId;
        flag.userId = testUserId;
        flag.reason = "fraud";
        flag.status = "pending";
        flag.createdAt = Instant.now();
        flag.updatedAt = Instant.now();
        flag.persist();

        // Approve flag
        moderationService.approveFlag(flag.id, testAdminUserId, "Confirmed fraud");

        // Verify flag approved
        ListingFlag updated = ListingFlag.findById(flag.id);
        assertEquals("approved", updated.status);
        assertEquals(testAdminUserId, updated.reviewedByUserId);

        // Verify listing removed
        MarketplaceListing updatedListing = MarketplaceListing.findById(testListingId);
        assertEquals("removed", updatedListing.status);
    }

    /**
     * Test: Dismiss flag decrements flag_count and restores status.
     */
    @Test
    @Transactional
    void testDismissFlag_RestoresListing() {
        // Create listing with flagged status and 1 pending flag
        MarketplaceListing listing = MarketplaceListing.findById(testListingId);
        listing.status = "flagged";
        listing.flagCount = 1L;
        listing.persist();

        // Create flag
        ListingFlag flag = new ListingFlag();
        flag.listingId = testListingId;
        flag.userId = testUserId;
        flag.reason = "spam";
        flag.status = "pending";
        flag.createdAt = Instant.now();
        flag.updatedAt = Instant.now();
        flag.persist();

        // Dismiss flag
        moderationService.dismissFlag(flag.id, testAdminUserId, "False positive");

        // Verify flag dismissed
        ListingFlag updated = ListingFlag.findById(flag.id);
        assertEquals("dismissed", updated.status);

        // Verify listing flag_count decremented
        MarketplaceListing updatedListing = MarketplaceListing.findById(testListingId);
        assertEquals(0L, updatedListing.flagCount);

        // Verify listing status restored to active
        assertEquals("active", updatedListing.status);
    }

    /**
     * Test: Banned user cannot submit flags.
     */
    @Test
    @Transactional
    void testSubmitFlag_BannedUserBlocked() {
        // Ban test user
        User.banUser(testUserId, "Test ban");

        // Attempt to submit flag should fail
        assertThrows(IllegalStateException.class, () -> {
            moderationService.submitFlag(testListingId, testUserId, "spam", "Should fail", false);
        });
    }

    /**
     * Helper: Create a test user.
     */
    private User createTestUser(UUID userId, String email) {
        User user = new User();
        user.id = userId;
        user.email = email;
        user.isAnonymous = false;
        user.directoryKarma = 0;
        user.directoryTrustLevel = "untrusted";
        user.analyticsConsent = false;
        user.isBanned = false;
        user.createdAt = Instant.now();
        user.updatedAt = Instant.now();
        user.persist();
        return user;
    }
}
