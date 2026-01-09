package villagecompute.homepage.data.models;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.api.types.ContactInfoType;
import villagecompute.homepage.api.types.FeeScheduleType;
import villagecompute.homepage.testing.H2TestResource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for MarketplaceListing entity covering CRUD operations, status lifecycle, and expiration logic.
 *
 * <p>
 * Test coverage per I4.T3 acceptance criteria:
 * <ul>
 * <li>Listings persist with statuses (draft, pending_payment, active, expired, removed, flagged)</li>
 * <li>Expiration/reminder jobs run and find correct listings</li>
 * <li>Validations enforce limits (via Type validation in REST layer)</li>
 * <li>Ownership checks prevent unauthorized modifications</li>
 * <li>Contact info JSONB serialization/deserialization</li>
 * <li>Expires_at auto-set when status transitions to active</li>
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(H2TestResource.class)
class MarketplaceListingTest {

    private UUID testUserId;
    private UUID testCategoryId;
    private Long testGeoCityId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clear all listings and categories before each test
        MarketplaceListing.deleteAll();
        MarketplaceCategory.deleteAll();

        // Create test user (placeholder UUID)
        testUserId = UUID.randomUUID();

        // Create test category with unique slug per test
        MarketplaceCategory category = new MarketplaceCategory();
        category.name = "Test Category";
        category.slug = "test-category-" + UUID.randomUUID();
        category.sortOrder = 1;
        category.isActive = true;
        category.feeSchedule = FeeScheduleType.free();
        category = MarketplaceCategory.create(category);
        testCategoryId = category.id;

        // Use placeholder geo city ID
        testGeoCityId = 12345L;
    }

    /**
     * Test: Create draft listing (no expiration set).
     */
    @Test
    @Transactional
    void testCreateDraftListing_NoExpiration() {
        MarketplaceListing listing = new MarketplaceListing();
        listing.userId = testUserId;
        listing.categoryId = testCategoryId;
        listing.geoCityId = testGeoCityId;
        listing.title = "Test Listing";
        listing.description = "Test description for draft listing. This is long enough to meet minimum requirements.";
        listing.price = new BigDecimal("99.99");
        listing.contactInfo = ContactInfoType.forListing("seller@example.com", "+1-555-1234");
        listing.status = "draft";
        listing.reminderSent = false;

        MarketplaceListing created = MarketplaceListing.create(listing);

        assertNotNull(created.id);
        assertEquals("draft", created.status);
        assertNull(created.expiresAt); // Draft listings don't expire
        assertFalse(created.reminderSent);
        assertNotNull(created.createdAt);
        assertNotNull(created.updatedAt);
        assertNotNull(created.contactInfo);
        assertEquals("seller@example.com", created.contactInfo.email());
        assertTrue(created.contactInfo.maskedEmail().startsWith("listing-"));
    }

    /**
     * Test: Create active listing (expiration auto-set to 30 days).
     */
    @Test
    @Transactional
    void testCreateActiveListing_ExpirationAutoSet() {
        MarketplaceListing listing = new MarketplaceListing();
        listing.userId = testUserId;
        listing.categoryId = testCategoryId;
        listing.geoCityId = testGeoCityId;
        listing.title = "Active Test Listing";
        listing.description = "Test description for active listing. This is long enough to meet minimum requirements.";
        listing.price = new BigDecimal("49.99");
        listing.contactInfo = ContactInfoType.forListing("seller@example.com", null);
        listing.status = "active";
        listing.reminderSent = false;

        MarketplaceListing created = MarketplaceListing.create(listing);

        assertNotNull(created.id);
        assertEquals("active", created.status);
        assertNotNull(created.expiresAt); // Active listings auto-expire after 30 days

        // Verify expires_at is approximately 30 days from now
        Instant expectedExpiration = Instant.now().plus(Duration.ofDays(30));
        long diffSeconds = Math.abs(Duration.between(created.expiresAt, expectedExpiration).getSeconds());
        assertTrue(diffSeconds < 5, "Expiration should be ~30 days from now (diff: " + diffSeconds + "s)");
    }

    /**
     * Test: Update draft listing.
     */
    @Test
    @Transactional
    void testUpdateDraftListing_Success() {
        // Create draft
        MarketplaceListing listing = new MarketplaceListing();
        listing.userId = testUserId;
        listing.categoryId = testCategoryId;
        listing.geoCityId = testGeoCityId;
        listing.title = "Original Title";
        listing.description = "Original description for draft listing. This is long enough to meet requirements.";
        listing.price = new BigDecimal("10.00");
        listing.contactInfo = ContactInfoType.forListing("seller@example.com", null);
        listing.status = "draft";
        listing.reminderSent = false;
        listing = MarketplaceListing.create(listing);

        // Update fields
        listing.title = "Updated Title";
        listing.price = new BigDecimal("20.00");
        MarketplaceListing.update(listing);

        // Verify update
        MarketplaceListing updated = MarketplaceListing.findById(listing.id);
        assertNotNull(updated);
        assertEquals("Updated Title", updated.title);
        assertEquals(new BigDecimal("20.00"), updated.price);
        assertTrue(updated.updatedAt.isAfter(updated.createdAt));
    }

    /**
     * Test: Transition draft → active sets expiration.
     */
    @Test
    @Transactional
    void testTransitionDraftToActive_SetsExpiration() {
        // Create draft
        MarketplaceListing listing = new MarketplaceListing();
        listing.userId = testUserId;
        listing.categoryId = testCategoryId;
        listing.geoCityId = testGeoCityId;
        listing.title = "Draft Listing";
        listing.description = "Draft description for testing. This is long enough to meet minimum requirements.";
        listing.price = new BigDecimal("15.00");
        listing.contactInfo = ContactInfoType.forListing("seller@example.com", null);
        listing.status = "draft";
        listing.reminderSent = false;
        listing = MarketplaceListing.create(listing);

        assertNull(listing.expiresAt);

        // Transition to active
        listing.status = "active";
        MarketplaceListing.update(listing);

        // Verify expiration set
        MarketplaceListing updated = MarketplaceListing.findById(listing.id);
        assertNotNull(updated);
        assertEquals("active", updated.status);
        assertNotNull(updated.expiresAt);
    }

    /**
     * Test: Find listings by user ID.
     */
    @Test
    @Transactional
    void testFindByUserId_ReturnsUserListings() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        // Create 3 listings for user1
        for (int i = 1; i <= 3; i++) {
            MarketplaceListing listing = new MarketplaceListing();
            listing.userId = user1;
            listing.categoryId = testCategoryId;
            listing.geoCityId = testGeoCityId;
            listing.title = "User1 Listing " + i;
            listing.description = "Description for user1 listing " + i
                    + ". This is long enough to meet minimum requirements.";
            listing.price = new BigDecimal("10.00");
            listing.contactInfo = ContactInfoType.forListing("user1@example.com", null);
            listing.status = "draft";
            listing.reminderSent = false;
            MarketplaceListing.create(listing);
        }

        // Create 1 listing for user2
        MarketplaceListing listing = new MarketplaceListing();
        listing.userId = user2;
        listing.categoryId = testCategoryId;
        listing.geoCityId = testGeoCityId;
        listing.title = "User2 Listing";
        listing.description = "Description for user2 listing. This is long enough to meet minimum requirements.";
        listing.price = new BigDecimal("20.00");
        listing.contactInfo = ContactInfoType.forListing("user2@example.com", null);
        listing.status = "active";
        listing.reminderSent = false;
        MarketplaceListing.create(listing);

        // Verify user1 has 3 listings
        List<MarketplaceListing> user1Listings = MarketplaceListing.findByUserId(user1);
        assertEquals(3, user1Listings.size());

        // Verify user2 has 1 listing
        List<MarketplaceListing> user2Listings = MarketplaceListing.findByUserId(user2);
        assertEquals(1, user2Listings.size());
    }

    /**
     * Test: Find active listings.
     */
    @Test
    @Transactional
    void testFindActive_ReturnsOnlyActiveListings() {
        // Create 2 active listings
        for (int i = 1; i <= 2; i++) {
            MarketplaceListing listing = new MarketplaceListing();
            listing.userId = testUserId;
            listing.categoryId = testCategoryId;
            listing.geoCityId = testGeoCityId;
            listing.title = "Active Listing " + i;
            listing.description = "Description for active listing " + i
                    + ". This is long enough to meet minimum requirements.";
            listing.price = new BigDecimal("10.00");
            listing.contactInfo = ContactInfoType.forListing("seller@example.com", null);
            listing.status = "active";
            listing.reminderSent = false;
            MarketplaceListing.create(listing);
        }

        // Create 1 draft listing
        MarketplaceListing draft = new MarketplaceListing();
        draft.userId = testUserId;
        draft.categoryId = testCategoryId;
        draft.geoCityId = testGeoCityId;
        draft.title = "Draft Listing";
        draft.description = "Description for draft listing. This is long enough to meet minimum requirements.";
        draft.price = new BigDecimal("15.00");
        draft.contactInfo = ContactInfoType.forListing("seller@example.com", null);
        draft.status = "draft";
        draft.reminderSent = false;
        MarketplaceListing.create(draft);

        // Verify only active listings returned
        List<MarketplaceListing> activeListings = MarketplaceListing.findActive();
        assertEquals(2, activeListings.size());
        assertTrue(activeListings.stream().allMatch(l -> "active".equals(l.status)));
    }

    /**
     * Test: Find expired listings (expires_at <= NOW).
     */
    @Test
    @Transactional
    void testFindExpired_ReturnsExpiredListings() {
        // Create active listing with past expiration
        MarketplaceListing expired = new MarketplaceListing();
        expired.userId = testUserId;
        expired.categoryId = testCategoryId;
        expired.geoCityId = testGeoCityId;
        expired.title = "Expired Listing";
        expired.description = "Description for expired listing. This is long enough to meet minimum requirements.";
        expired.price = new BigDecimal("10.00");
        expired.contactInfo = ContactInfoType.forListing("seller@example.com", null);
        expired.status = "active";
        expired.expiresAt = Instant.now().minus(Duration.ofDays(1)); // 1 day ago
        expired.reminderSent = false;
        expired = MarketplaceListing.create(expired);

        // Create active listing with future expiration
        MarketplaceListing active = new MarketplaceListing();
        active.userId = testUserId;
        active.categoryId = testCategoryId;
        active.geoCityId = testGeoCityId;
        active.title = "Active Listing";
        active.description = "Description for active listing. This is long enough to meet minimum requirements.";
        active.price = new BigDecimal("20.00");
        active.contactInfo = ContactInfoType.forListing("seller@example.com", null);
        active.status = "active";
        active.expiresAt = Instant.now().plus(Duration.ofDays(10)); // 10 days future
        active.reminderSent = false;
        MarketplaceListing.create(active);

        // Verify only expired listing returned
        List<MarketplaceListing> expiredListings = MarketplaceListing.findExpired();
        assertEquals(1, expiredListings.size());
        assertEquals(expired.id, expiredListings.get(0).id);
    }

    /**
     * Test: Find listings expiring within N days.
     */
    @Test
    @Transactional
    void testFindExpiringWithinDays_ReturnsExpiringListings() {
        // Create listing expiring in 2 days (should be found)
        MarketplaceListing expiringSoon = new MarketplaceListing();
        expiringSoon.userId = testUserId;
        expiringSoon.categoryId = testCategoryId;
        expiringSoon.geoCityId = testGeoCityId;
        expiringSoon.title = "Expiring Soon";
        expiringSoon.description = "Description for expiring listing. This is long enough to meet minimum requirements.";
        expiringSoon.price = new BigDecimal("10.00");
        expiringSoon.contactInfo = ContactInfoType.forListing("seller@example.com", null);
        expiringSoon.status = "active";
        expiringSoon.expiresAt = Instant.now().plus(Duration.ofDays(2));
        expiringSoon.reminderSent = false;
        expiringSoon = MarketplaceListing.create(expiringSoon);

        // Create listing expiring in 10 days (should NOT be found)
        MarketplaceListing expiringLater = new MarketplaceListing();
        expiringLater.userId = testUserId;
        expiringLater.categoryId = testCategoryId;
        expiringLater.geoCityId = testGeoCityId;
        expiringLater.title = "Expiring Later";
        expiringLater.description = "Description for later listing. This is long enough to meet minimum requirements.";
        expiringLater.price = new BigDecimal("20.00");
        expiringLater.contactInfo = ContactInfoType.forListing("seller@example.com", null);
        expiringLater.status = "active";
        expiringLater.expiresAt = Instant.now().plus(Duration.ofDays(10));
        expiringLater.reminderSent = false;
        MarketplaceListing.create(expiringLater);

        // Find listings expiring within 3 days
        List<MarketplaceListing> expiringListings = MarketplaceListing.findExpiringWithinDays(3);
        assertEquals(1, expiringListings.size());
        assertEquals(expiringSoon.id, expiringListings.get(0).id);
    }

    /**
     * Test: Mark listing as expired.
     */
    @Test
    @Transactional
    void testMarkExpired_UpdatesStatus() {
        // Create active listing
        MarketplaceListing listing = new MarketplaceListing();
        listing.userId = testUserId;
        listing.categoryId = testCategoryId;
        listing.geoCityId = testGeoCityId;
        listing.title = "Active Listing";
        listing.description = "Description for active listing. This is long enough to meet minimum requirements.";
        listing.price = new BigDecimal("10.00");
        listing.contactInfo = ContactInfoType.forListing("seller@example.com", null);
        listing.status = "active";
        listing.reminderSent = false;
        listing = MarketplaceListing.create(listing);

        assertEquals("active", listing.status);

        // Mark as expired
        MarketplaceListing.markExpired(listing.id);

        // Verify status updated
        MarketplaceListing updated = MarketplaceListing.findById(listing.id);
        assertNotNull(updated);
        assertEquals("expired", updated.status);
    }

    /**
     * Test: Soft-delete listing.
     */
    @Test
    @Transactional
    void testSoftDelete_UpdatesStatusToRemoved() {
        // Create listing
        MarketplaceListing listing = new MarketplaceListing();
        listing.userId = testUserId;
        listing.categoryId = testCategoryId;
        listing.geoCityId = testGeoCityId;
        listing.title = "Listing to Delete";
        listing.description = "Description for deleted listing. This is long enough to meet minimum requirements.";
        listing.price = new BigDecimal("10.00");
        listing.contactInfo = ContactInfoType.forListing("seller@example.com", null);
        listing.status = "active";
        listing.reminderSent = false;
        listing = MarketplaceListing.create(listing);

        assertEquals("active", listing.status);

        // Soft-delete
        MarketplaceListing.softDelete(listing.id);

        // Verify status updated (not hard-deleted)
        MarketplaceListing deleted = MarketplaceListing.findById(listing.id);
        assertNotNull(deleted);
        assertEquals("removed", deleted.status);
    }

    /**
     * Test: Ownership check.
     */
    @Test
    @Transactional
    void testIsOwnedByUser_ReturnsCorrectResult() {
        UUID owner = UUID.randomUUID();
        UUID nonOwner = UUID.randomUUID();

        // Create listing for owner
        MarketplaceListing listing = new MarketplaceListing();
        listing.userId = owner;
        listing.categoryId = testCategoryId;
        listing.geoCityId = testGeoCityId;
        listing.title = "Owner Listing";
        listing.description = "Description for owner listing. This is long enough to meet minimum requirements.";
        listing.price = new BigDecimal("10.00");
        listing.contactInfo = ContactInfoType.forListing("owner@example.com", null);
        listing.status = "draft";
        listing.reminderSent = false;
        listing = MarketplaceListing.create(listing);

        // Verify ownership
        assertTrue(MarketplaceListing.isOwnedByUser(listing.id, owner));
        assertFalse(MarketplaceListing.isOwnedByUser(listing.id, nonOwner));
    }
}
