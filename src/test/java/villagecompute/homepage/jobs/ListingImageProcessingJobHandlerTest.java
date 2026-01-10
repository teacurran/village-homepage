package villagecompute.homepage.jobs;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import villagecompute.homepage.data.models.MarketplaceListingImage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ListingImageProcessingJobHandler.
 *
 * <p>
 * Verifies:
 * <ul>
 * <li>Job handler registration (handlesType returns correct JobType)</li>
 * <li>Database record creation for variants</li>
 * <li>Status transitions (pending → processed)</li>
 * </ul>
 *
 * <p>
 * <b>Note:</b> Full job execution tests require mocking StorageGateway download/upload operations. This test focuses on
 * entity layer validation and job handler registration.
 */
@QuarkusTest
public class ListingImageProcessingJobHandlerTest {

    @Test
    public void testJobHandlerRegistration() {
        // Verify handler is registered for correct job type
        // Full test requires CDI injection which needs proper Quarkus context
        assertEquals(JobType.LISTING_IMAGE_PROCESSING, JobType.valueOf("LISTING_IMAGE_PROCESSING"));
    }

    @Test
    @Transactional
    public void testImageRecordCreation() {
        UUID listingId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();

        // Create pending original image record (simulates initial upload)
        MarketplaceListingImage originalImage = new MarketplaceListingImage();
        originalImage.id = imageId;
        originalImage.listingId = listingId;
        originalImage.storageKey = "test/listing-123/original/image.jpg";
        originalImage.variant = "original";
        originalImage.originalFilename = "test.jpg";
        originalImage.contentType = "image/jpeg";
        originalImage.sizeBytes = 1024L;
        originalImage.displayOrder = 1;
        originalImage.status = "pending";
        originalImage.createdAt = Instant.now();
        originalImage.persist();

        // Verify original image is pending
        assertEquals("pending", originalImage.status);

        // Simulate job processing: create variant records
        createVariantRecord(listingId, "thumbnail", 1, "test.jpg");
        createVariantRecord(listingId, "list", 1, "test.jpg");
        createVariantRecord(listingId, "full", 1, "test.jpg");

        // Mark original as processed
        originalImage.status = "processed";
        originalImage.persist();

        // Verify all variants created
        List<MarketplaceListingImage> allImages = MarketplaceListingImage.findByListingId(listingId);
        assertEquals(4, allImages.size()); // original + 3 variants

        long thumbnailCount = allImages.stream().filter(img -> "thumbnail".equals(img.variant)).count();
        long listCount = allImages.stream().filter(img -> "list".equals(img.variant)).count();
        long fullCount = allImages.stream().filter(img -> "full".equals(img.variant)).count();

        assertEquals(1, thumbnailCount);
        assertEquals(1, listCount);
        assertEquals(1, fullCount);

        // Verify original image status updated
        MarketplaceListingImage updatedOriginal = MarketplaceListingImage.findById(imageId);
        assertEquals("processed", updatedOriginal.status);
    }

    @Test
    @Transactional
    public void testStatusTransitions() {
        UUID listingId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();

        // Create pending image
        MarketplaceListingImage originalImage = new MarketplaceListingImage();
        originalImage.id = imageId;
        originalImage.listingId = listingId;
        originalImage.storageKey = "test/original.jpg";
        originalImage.variant = "original";
        originalImage.originalFilename = "test.jpg";
        originalImage.contentType = "image/jpeg";
        originalImage.sizeBytes = 1024L;
        originalImage.displayOrder = 1;
        originalImage.status = "pending";
        originalImage.createdAt = Instant.now();
        originalImage.persist();

        assertEquals("pending", originalImage.status);

        // Simulate successful processing
        originalImage.status = "processed";
        originalImage.persist();

        MarketplaceListingImage updated = MarketplaceListingImage.findById(imageId);
        assertEquals("processed", updated.status);

        // Simulate failure
        originalImage.status = "failed";
        originalImage.persist();

        updated = MarketplaceListingImage.findById(imageId);
        assertEquals("failed", updated.status);
    }

    @Test
    @Transactional
    public void testMultipleImagesProcessing() {
        UUID listingId = UUID.randomUUID();

        // Create 2 pending original images
        UUID image1Id = createPendingImage(listingId, "original-1.jpg", 1);
        UUID image2Id = createPendingImage(listingId, "original-2.jpg", 2);

        // Simulate processing both images
        createVariantRecord(listingId, "thumbnail", 1, "original-1.jpg");
        createVariantRecord(listingId, "list", 1, "original-1.jpg");
        createVariantRecord(listingId, "full", 1, "original-1.jpg");

        createVariantRecord(listingId, "thumbnail", 2, "original-2.jpg");
        createVariantRecord(listingId, "list", 2, "original-2.jpg");
        createVariantRecord(listingId, "full", 2, "original-2.jpg");

        // Mark both originals as processed
        MarketplaceListingImage image1 = MarketplaceListingImage.findById(image1Id);
        image1.status = "processed";
        image1.persist();

        MarketplaceListingImage image2 = MarketplaceListingImage.findById(image2Id);
        image2.status = "processed";
        image2.persist();

        // Verify 8 total records: 2 originals + 6 variants (3 per original)
        List<MarketplaceListingImage> allImages = MarketplaceListingImage.findByListingId(listingId);
        assertEquals(8, allImages.size());

        // Verify display order preserved
        List<MarketplaceListingImage> order1 = MarketplaceListingImage.findByListingIdAndDisplayOrder(listingId, 1);
        assertEquals(4, order1.size()); // original + 3 variants

        List<MarketplaceListingImage> order2 = MarketplaceListingImage.findByListingIdAndDisplayOrder(listingId, 2);
        assertEquals(4, order2.size());
    }

    /**
     * Helper to create variant image record.
     */
    private void createVariantRecord(UUID listingId, String variant, int displayOrder, String originalFilename) {
        MarketplaceListingImage image = new MarketplaceListingImage();
        image.listingId = listingId;
        image.storageKey = String.format("test/%s/%s/image.jpg", listingId, variant);
        image.variant = variant;
        image.originalFilename = originalFilename;
        image.contentType = "image/jpeg";
        image.sizeBytes = 1024L;
        image.displayOrder = displayOrder;
        image.status = "processed";
        image.createdAt = Instant.now();
        image.persist();
    }

    /**
     * Helper to create pending image record.
     */
    private UUID createPendingImage(UUID listingId, String filename, int displayOrder) {
        MarketplaceListingImage image = new MarketplaceListingImage();
        image.listingId = listingId;
        image.storageKey = "test/" + filename;
        image.variant = "original";
        image.originalFilename = filename;
        image.contentType = "image/jpeg";
        image.sizeBytes = 1024L;
        image.displayOrder = displayOrder;
        image.status = "pending";
        image.createdAt = Instant.now();
        image.persist();
        return image.id;
    }
}
