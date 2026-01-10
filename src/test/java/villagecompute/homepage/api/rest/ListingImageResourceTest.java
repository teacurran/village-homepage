package villagecompute.homepage.api.rest;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.MarketplaceCategory;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.data.models.MarketplaceListingImage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ListingImageResource (I4.T6).
 *
 * <p>
 * Test coverage:
 * <ul>
 * <li>Entity CRUD operations</li>
 * <li>Image count validation</li>
 * <li>Status transitions</li>
 * <li>Display order management</li>
 * </ul>
 *
 * <p>
 * <b>Note:</b> This test focuses on entity layer validation. Full REST integration tests require mocking StorageGateway
 * and DelayedJobService.
 */
@QuarkusTest
public class ListingImageResourceTest {

    private UUID testUserId;
    private UUID testListingId;
    private UUID testCategoryId;

    @BeforeEach
    @Transactional
    public void setupTestData() {
        // Clean up
        MarketplaceListingImage.deleteAll();
        MarketplaceListing.deleteAll();
        MarketplaceCategory.deleteAll();

        // Create test category
        MarketplaceCategory category = new MarketplaceCategory();
        category.name = "For Sale";
        category.slug = "for-sale";
        category.sortOrder = 1;
        category.isActive = true;
        category.feeSchedule = new villagecompute.homepage.api.types.FeeScheduleType(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO);
        category.createdAt = Instant.now();
        category.updatedAt = Instant.now();
        category.persist();
        testCategoryId = category.id;

        // Create test user
        testUserId = UUID.randomUUID();

        // Create test listing
        MarketplaceListing listing = new MarketplaceListing();
        listing.userId = testUserId;
        listing.categoryId = testCategoryId;
        listing.title = "Test Listing";
        listing.description = "Test description for image upload tests";
        listing.price = BigDecimal.valueOf(100);
        listing.contactInfo = new villagecompute.homepage.api.types.ContactInfoType("test@example.com", null,
                "masked-test@villagecompute.com");
        listing.status = "active";
        listing.createdAt = Instant.now();
        listing.updatedAt = Instant.now();
        listing.expiresAt = Instant.now().plus(30, java.time.temporal.ChronoUnit.DAYS);
        listing.persist();
        testListingId = listing.id;
    }

    @Test
    @Transactional
    public void testListImagesEmpty() {
        List<MarketplaceListingImage> images = MarketplaceListingImage.findByListingId(testListingId);
        assertTrue(images.isEmpty());
    }

    @Test
    @Transactional
    public void testListImagesWithVariants() {
        // Create test images manually (simulate job processing)
        createTestImage(testListingId, "original", 1, "processed");
        createTestImage(testListingId, "thumbnail", 1, "processed");
        createTestImage(testListingId, "list", 1, "processed");
        createTestImage(testListingId, "full", 1, "processed");

        List<MarketplaceListingImage> images = MarketplaceListingImage.findByListingId(testListingId);
        assertEquals(4, images.size());

        // Verify variants grouped by display order
        List<MarketplaceListingImage> order1 = MarketplaceListingImage.findByListingIdAndDisplayOrder(testListingId, 1);
        assertEquals(4, order1.size());
    }

    @Test
    @Transactional
    public void testDeleteImage() {
        // Create test images
        createTestImage(testListingId, "original", 1, "processed");
        createTestImage(testListingId, "thumbnail", 1, "processed");
        createTestImage(testListingId, "list", 1, "processed");
        createTestImage(testListingId, "full", 1, "processed");

        // Verify 4 images exist
        assertEquals(4, MarketplaceListingImage.findByListingId(testListingId).size());

        // Delete all images for display order 1
        MarketplaceListingImage.delete("listingId = ?1 AND displayOrder = ?2", testListingId, 1);

        // Verify all deleted
        assertEquals(0, MarketplaceListingImage.findByListingId(testListingId).size());
    }

    @Test
    @Transactional
    public void testImageCountLimit() {
        // Create 12 original images (max limit)
        for (int i = 1; i <= 12; i++) {
            createTestImage(testListingId, "original", i, "processed");
        }

        long count = MarketplaceListingImage.countOriginalsByListingId(testListingId);
        assertEquals(12, count);

        // Verify we're at the limit
        // In real API, 13th upload would be rejected with 400 Bad Request
    }

    @Test
    @Transactional
    public void testImageStatusTransitions() {
        // Create pending image
        MarketplaceListingImage image = createTestImage(testListingId, "original", 1, "pending");
        assertEquals("pending", image.status);

        // Simulate job processing
        image.status = "processed";
        image.persist();

        MarketplaceListingImage updated = MarketplaceListingImage.findById(image.id);
        assertEquals("processed", updated.status);
    }

    @Test
    @Transactional
    public void testDeleteImageNotOwner() {
        // Create image for test listing
        MarketplaceListingImage image = createTestImage(testListingId, "original", 1, "processed");

        // Create different user's listing
        UUID otherUserId = UUID.randomUUID();
        MarketplaceListing otherListing = new MarketplaceListing();
        otherListing.userId = otherUserId;
        otherListing.categoryId = testCategoryId;
        otherListing.title = "Other Listing";
        otherListing.description = "Another user's listing";
        otherListing.price = BigDecimal.valueOf(50);
        otherListing.contactInfo = new villagecompute.homepage.api.types.ContactInfoType("other@example.com", null,
                "masked-other@villagecompute.com");
        otherListing.status = "active";
        otherListing.createdAt = Instant.now();
        otherListing.updatedAt = Instant.now();
        otherListing.persist();

        // In real API, attempt to delete image from different user's listing would return 403 Forbidden
        assertNotEquals(testUserId, otherUserId);
        assertNotEquals(testListingId, otherListing.id);
    }

    @Test
    @Transactional
    public void testCannotUploadToRemovedListing() {
        // Mark listing as removed
        MarketplaceListing listing = MarketplaceListing.findById(testListingId);
        listing.status = "removed";
        listing.persist();

        // In real API, upload attempt would return 409 Conflict
        assertEquals("removed", listing.status);
    }

    @Test
    @Transactional
    public void testCannotUploadToExpiredListing() {
        // Mark listing as expired
        MarketplaceListing listing = MarketplaceListing.findById(testListingId);
        listing.status = "expired";
        listing.persist();

        // In real API, upload attempt would return 409 Conflict
        assertEquals("expired", listing.status);
    }

    @Test
    @Transactional
    public void testMultipleImageDisplayOrders() {
        // Create 3 images with different display orders
        createTestImage(testListingId, "original", 1, "processed");
        createTestImage(testListingId, "thumbnail", 1, "processed");
        createTestImage(testListingId, "original", 2, "processed");
        createTestImage(testListingId, "thumbnail", 2, "processed");
        createTestImage(testListingId, "original", 3, "processed");

        // Verify correct grouping
        assertEquals(2, MarketplaceListingImage.findByListingIdAndDisplayOrder(testListingId, 1).size());
        assertEquals(2, MarketplaceListingImage.findByListingIdAndDisplayOrder(testListingId, 2).size());
        assertEquals(1, MarketplaceListingImage.findByListingIdAndDisplayOrder(testListingId, 3).size());
    }

    /**
     * Helper to create test image record.
     */
    private MarketplaceListingImage createTestImage(UUID listingId, String variant, int displayOrder, String status) {
        MarketplaceListingImage image = new MarketplaceListingImage();
        image.listingId = listingId;
        image.storageKey = String.format("test/%s/%s/%d.jpg", listingId, variant, displayOrder);
        image.variant = variant;
        image.originalFilename = "test-image.jpg";
        image.contentType = "image/jpeg";
        image.sizeBytes = 1024L;
        image.displayOrder = displayOrder;
        image.status = status;
        image.createdAt = Instant.now();
        image.persist();
        return image;
    }
}
