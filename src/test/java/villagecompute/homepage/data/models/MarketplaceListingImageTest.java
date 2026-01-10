package villagecompute.homepage.data.models;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MarketplaceListingImage entity CRUD operations and static finders.
 *
 * <p>
 * Verifies:
 * <ul>
 * <li>Basic CRUD (create, read, update, delete)</li>
 * <li>Static finder methods (findByListingId, countByListingId, etc.)</li>
 * <li>Variant filtering (original, thumbnail, list, full)</li>
 * <li>Display order sorting</li>
 * <li>Status filtering (pending, processed, failed)</li>
 * </ul>
 */
@QuarkusTest
public class MarketplaceListingImageTest {

    @Test
    @Transactional
    public void testCreateAndFindById() {
        // Create test image
        MarketplaceListingImage image = new MarketplaceListingImage();
        image.listingId = UUID.randomUUID();
        image.storageKey = "test/listing-123/original/image.jpg";
        image.variant = "original";
        image.originalFilename = "test-image.jpg";
        image.contentType = "image/jpeg";
        image.sizeBytes = 1024L;
        image.displayOrder = 1;
        image.status = "pending";
        image.createdAt = Instant.now();
        image.persist();

        assertNotNull(image.id);

        // Find by ID
        MarketplaceListingImage found = MarketplaceListingImage.findById(image.id);
        assertNotNull(found);
        assertEquals(image.listingId, found.listingId);
        assertEquals("original", found.variant);
        assertEquals("test-image.jpg", found.originalFilename);
        assertEquals("pending", found.status);
    }

    @Test
    @Transactional
    public void testFindByListingId() {
        UUID listingId = UUID.randomUUID();

        // Create multiple images for same listing
        createTestImage(listingId, "original", 1, "pending");
        createTestImage(listingId, "thumbnail", 1, "processed");
        createTestImage(listingId, "list", 1, "processed");
        createTestImage(listingId, "original", 2, "pending");

        List<MarketplaceListingImage> images = MarketplaceListingImage.findByListingId(listingId);
        assertEquals(4, images.size());

        // Verify sorted by display order
        assertEquals(1, images.get(0).displayOrder);
        assertEquals(1, images.get(1).displayOrder);
    }

    @Test
    @Transactional
    public void testCountByListingId() {
        UUID listingId = UUID.randomUUID();

        // Create images (one failed should not be counted)
        createTestImage(listingId, "original", 1, "processed");
        createTestImage(listingId, "thumbnail", 1, "processed");
        createTestImage(listingId, "original", 2, "failed");

        long count = MarketplaceListingImage.countByListingId(listingId);
        assertEquals(2, count); // Failed image not counted
    }

    @Test
    @Transactional
    public void testCountOriginalsByListingId() {
        UUID listingId = UUID.randomUUID();

        // Create original + variants
        createTestImage(listingId, "original", 1, "processed");
        createTestImage(listingId, "thumbnail", 1, "processed");
        createTestImage(listingId, "list", 1, "processed");
        createTestImage(listingId, "full", 1, "processed");
        createTestImage(listingId, "original", 2, "pending");

        long count = MarketplaceListingImage.countOriginalsByListingId(listingId);
        assertEquals(2, count); // Only originals counted
    }

    @Test
    @Transactional
    public void testFindByListingIdAndVariant() {
        UUID listingId = UUID.randomUUID();

        createTestImage(listingId, "original", 1, "processed");
        createTestImage(listingId, "thumbnail", 1, "processed");
        createTestImage(listingId, "thumbnail", 2, "processed");

        List<MarketplaceListingImage> thumbnails = MarketplaceListingImage.findByListingIdAndVariant(listingId,
                "thumbnail");
        assertEquals(2, thumbnails.size());
        assertTrue(thumbnails.stream().allMatch(img -> "thumbnail".equals(img.variant)));
    }

    @Test
    @Transactional
    public void testFindByListingIdAndDisplayOrder() {
        UUID listingId = UUID.randomUUID();

        // Create all 4 variants for display order 1
        createTestImage(listingId, "original", 1, "processed");
        createTestImage(listingId, "thumbnail", 1, "processed");
        createTestImage(listingId, "list", 1, "processed");
        createTestImage(listingId, "full", 1, "processed");
        createTestImage(listingId, "original", 2, "processed");

        List<MarketplaceListingImage> variants = MarketplaceListingImage.findByListingIdAndDisplayOrder(listingId, 1);
        assertEquals(4, variants.size());
        assertTrue(variants.stream().allMatch(img -> img.displayOrder == 1));
    }

    @Test
    @Transactional
    public void testDeleteByListingId() {
        UUID listingId = UUID.randomUUID();

        createTestImage(listingId, "original", 1, "processed");
        createTestImage(listingId, "thumbnail", 1, "processed");
        createTestImage(listingId, "list", 1, "processed");

        long deletedCount = MarketplaceListingImage.deleteByListingId(listingId);
        assertEquals(3, deletedCount);

        List<MarketplaceListingImage> remaining = MarketplaceListingImage.findByListingId(listingId);
        assertTrue(remaining.isEmpty());
    }

    @Test
    @Transactional
    public void testFindPending() {
        UUID listing1 = UUID.randomUUID();
        UUID listing2 = UUID.randomUUID();

        createTestImage(listing1, "original", 1, "pending");
        createTestImage(listing1, "original", 2, "processed");
        createTestImage(listing2, "original", 1, "pending");

        List<MarketplaceListingImage> pending = MarketplaceListingImage.findPending();
        assertTrue(pending.size() >= 2); // At least our 2 pending images
        assertTrue(pending.stream().allMatch(img -> "pending".equals(img.status)));
    }

    @Test
    @Transactional
    public void testFindFailed() {
        UUID listingId = UUID.randomUUID();

        createTestImage(listingId, "original", 1, "failed");
        createTestImage(listingId, "original", 2, "processed");

        List<MarketplaceListingImage> failed = MarketplaceListingImage.findFailed();
        assertTrue(failed.size() >= 1); // At least our 1 failed image
        assertTrue(failed.stream().allMatch(img -> "failed".equals(img.status)));
    }

    @Test
    @Transactional
    public void testUpdateStatus() {
        MarketplaceListingImage image = createTestImage(UUID.randomUUID(), "original", 1, "pending");

        image.status = "processed";
        image.persist();

        MarketplaceListingImage updated = MarketplaceListingImage.findById(image.id);
        assertEquals("processed", updated.status);
    }

    /**
     * Helper to create test image.
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
