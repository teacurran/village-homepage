package villagecompute.homepage.data.models;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ListingPromotion entity covering promotion lifecycle and business rules.
 *
 * <p>
 * Test coverage per I4.T4 acceptance criteria:
 * <ul>
 * <li>Featured promotions expire after 7 days</li>
 * <li>Bump promotions have 24-hour cooldown</li>
 * <li>Payment Intent ID is unique (idempotency)</li>
 * <li>Expired promotions can be queried and deleted</li>
 * </ul>
 */
@QuarkusTest
class ListingPromotionTest {

    @BeforeEach
    @Transactional
    void setUp() {
        // Clear all promotions before each test
        ListingPromotion.deleteAll();
    }

    /**
     * Test: Create featured promotion with automatic expiration calculation.
     */
    @Test
    @Transactional
    void testCreateFeaturedPromotion_SetsExpiration() {
        UUID listingId = UUID.randomUUID();
        Instant now = Instant.now();

        ListingPromotion promotion = new ListingPromotion();
        promotion.listingId = listingId;
        promotion.type = "featured";
        promotion.stripePaymentIntentId = "pi_test_featured_123";
        promotion.amountCents = 500;
        promotion.startsAt = now;

        ListingPromotion created = ListingPromotion.create(promotion);

        assertNotNull(created.id);
        assertEquals("featured", created.type);
        assertNotNull(created.expiresAt);

        // Verify expiration is 7 days from start
        long daysDiff = java.time.Duration.between(created.startsAt, created.expiresAt).toDays();
        assertEquals(7, daysDiff);
    }

    /**
     * Test: Create bump promotion without expiration.
     */
    @Test
    @Transactional
    void testCreateBumpPromotion_NoExpiration() {
        UUID listingId = UUID.randomUUID();

        ListingPromotion promotion = new ListingPromotion();
        promotion.listingId = listingId;
        promotion.type = "bump";
        promotion.stripePaymentIntentId = "pi_test_bump_123";
        promotion.amountCents = 200;

        ListingPromotion created = ListingPromotion.create(promotion);

        assertNotNull(created.id);
        assertEquals("bump", created.type);
        assertNull(created.expiresAt, "Bump promotions should not have expiration");
        assertNotNull(created.startsAt);
    }

    /**
     * Test: Find promotions by listing ID.
     */
    @Test
    @Transactional
    void testFindByListingId_ReturnsPromotions() {
        UUID listingId = UUID.randomUUID();

        // Create two promotions
        ListingPromotion featured = new ListingPromotion();
        featured.listingId = listingId;
        featured.type = "featured";
        featured.stripePaymentIntentId = "pi_test_1";
        featured.amountCents = 500;
        ListingPromotion.create(featured);

        ListingPromotion bump = new ListingPromotion();
        bump.listingId = listingId;
        bump.type = "bump";
        bump.stripePaymentIntentId = "pi_test_2";
        bump.amountCents = 200;
        ListingPromotion.create(bump);

        List<ListingPromotion> promotions = ListingPromotion.findByListingId(listingId);

        assertEquals(2, promotions.size());
    }

    /**
     * Test: Find active featured promotions.
     */
    @Test
    @Transactional
    void testFindActiveFeatured_OnlyReturnsActive() {
        UUID listingId = UUID.randomUUID();
        Instant now = Instant.now();

        // Create active featured promotion
        ListingPromotion active = new ListingPromotion();
        active.listingId = listingId;
        active.type = "featured";
        active.stripePaymentIntentId = "pi_test_active";
        active.amountCents = 500;
        active.startsAt = now.minus(java.time.Duration.ofDays(1));
        active.expiresAt = now.plus(java.time.Duration.ofDays(6));
        ListingPromotion.create(active);

        // Create expired featured promotion
        ListingPromotion expired = new ListingPromotion();
        expired.listingId = listingId;
        expired.type = "featured";
        expired.stripePaymentIntentId = "pi_test_expired";
        expired.amountCents = 500;
        expired.startsAt = now.minus(java.time.Duration.ofDays(8));
        expired.expiresAt = now.minus(java.time.Duration.ofDays(1));
        ListingPromotion.create(expired);

        List<ListingPromotion> activePromotions = ListingPromotion.findActiveFeatured(listingId);

        assertEquals(1, activePromotions.size());
        assertEquals("pi_test_active", activePromotions.get(0).stripePaymentIntentId);
    }

    /**
     * Test: Find expired featured promotions.
     */
    @Test
    @Transactional
    void testFindExpiredFeatured_ReturnsExpired() {
        Instant now = Instant.now();

        // Create expired promotion
        ListingPromotion expired = new ListingPromotion();
        expired.listingId = UUID.randomUUID();
        expired.type = "featured";
        expired.stripePaymentIntentId = "pi_test_expired";
        expired.amountCents = 500;
        expired.startsAt = now.minus(java.time.Duration.ofDays(8));
        expired.expiresAt = now.minus(java.time.Duration.ofHours(1));
        ListingPromotion.create(expired);

        // Create active promotion
        ListingPromotion active = new ListingPromotion();
        active.listingId = UUID.randomUUID();
        active.type = "featured";
        active.stripePaymentIntentId = "pi_test_active";
        active.amountCents = 500;
        active.startsAt = now;
        active.expiresAt = now.plus(java.time.Duration.ofDays(7));
        ListingPromotion.create(active);

        List<ListingPromotion> expiredPromotions = ListingPromotion.findExpiredFeatured();

        assertEquals(1, expiredPromotions.size());
        assertEquals("pi_test_expired", expiredPromotions.get(0).stripePaymentIntentId);
    }

    /**
     * Test: Find promotion by Payment Intent ID.
     */
    @Test
    @Transactional
    void testFindByPaymentIntent_ReturnsPromotion() {
        String paymentIntentId = "pi_test_unique_123";

        ListingPromotion promotion = new ListingPromotion();
        promotion.listingId = UUID.randomUUID();
        promotion.type = "featured";
        promotion.stripePaymentIntentId = paymentIntentId;
        promotion.amountCents = 500;
        ListingPromotion.create(promotion);

        Optional<ListingPromotion> found = ListingPromotion.findByPaymentIntent(paymentIntentId);

        assertTrue(found.isPresent());
        assertEquals(paymentIntentId, found.get().stripePaymentIntentId);
    }

    /**
     * Test: Check recent bump cooldown (24 hours).
     */
    @Test
    @Transactional
    void testHasRecentBump_WithinCooldown_ReturnsTrue() {
        UUID listingId = UUID.randomUUID();
        Instant now = Instant.now();

        // Create bump within last 24 hours
        ListingPromotion recentBump = new ListingPromotion();
        recentBump.listingId = listingId;
        recentBump.type = "bump";
        recentBump.stripePaymentIntentId = "pi_test_recent_bump";
        recentBump.amountCents = 200;
        recentBump.startsAt = now.minus(java.time.Duration.ofHours(12));
        recentBump.createdAt = now.minus(java.time.Duration.ofHours(12));
        recentBump.updatedAt = now.minus(java.time.Duration.ofHours(12));
        recentBump.persist();

        boolean hasRecent = ListingPromotion.hasRecentBump(listingId);

        assertTrue(hasRecent, "Should detect recent bump within 24h cooldown");
    }

    /**
     * Test: Check recent bump cooldown (old bump allowed).
     */
    @Test
    @Transactional
    void testHasRecentBump_OutsideCooldown_ReturnsFalse() {
        UUID listingId = UUID.randomUUID();
        Instant now = Instant.now();

        // Create bump older than 24 hours
        ListingPromotion oldBump = new ListingPromotion();
        oldBump.listingId = listingId;
        oldBump.type = "bump";
        oldBump.stripePaymentIntentId = "pi_test_old_bump";
        oldBump.amountCents = 200;
        oldBump.startsAt = now.minus(java.time.Duration.ofHours(25));
        oldBump.createdAt = now.minus(java.time.Duration.ofHours(25));
        oldBump.updatedAt = now.minus(java.time.Duration.ofHours(25));
        oldBump.persist();

        boolean hasRecent = ListingPromotion.hasRecentBump(listingId);

        assertFalse(hasRecent, "Should allow bump after 24h cooldown");
    }

    /**
     * Test: Delete expired promotion.
     */
    @Test
    @Transactional
    void testDeleteExpired_RemovesPromotion() {
        Instant now = Instant.now();

        ListingPromotion expired = new ListingPromotion();
        expired.listingId = UUID.randomUUID();
        expired.type = "featured";
        expired.stripePaymentIntentId = "pi_test_to_delete";
        expired.amountCents = 500;
        expired.startsAt = now.minus(java.time.Duration.ofDays(8));
        expired.expiresAt = now.minus(java.time.Duration.ofHours(1));
        expired = ListingPromotion.create(expired);

        UUID promotionId = expired.id;
        ListingPromotion.deleteExpired(promotionId);

        ListingPromotion deleted = ListingPromotion.findById(promotionId);
        assertNull(deleted, "Promotion should be deleted");
    }
}
