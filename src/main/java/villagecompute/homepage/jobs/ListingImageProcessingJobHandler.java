package villagecompute.homepage.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.StorageUploadResultType;
import villagecompute.homepage.data.models.MarketplaceListingImage;
import villagecompute.homepage.services.StorageGateway;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Job handler for processing uploaded listing images.
 *
 * <p>
 * Workflow:
 * <ol>
 *   <li>Download original image from R2 storage</li>
 *   <li>Generate 3 variants: thumbnail (150x150), list (300x225), full (1200x900)</li>
 *   <li>Upload each variant to R2 via StorageGateway</li>
 *   <li>Create database records for each variant</li>
 *   <li>Mark original image as 'processed'</li>
 * </ol>
 *
 * <p>
 * <b>NOTE:</b> WebP conversion and actual resizing are currently stubbed in StorageGateway.convertToWebP().
 * This handler uploads original bytes unchanged for all variants. Future work will implement
 * actual image resize and WebP conversion.
 *
 * <p>
 * <b>Retry Strategy:</b> Failed jobs retry up to 3 times with exponential backoff.
 * After max retries, image status is marked 'failed' for manual review.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 *   <li>P4: Indefinite retention in R2 (cleanup only on listing removal/expiration)</li>
 *   <li>P12: Uses BULK queue (no semaphore limits like SCREENSHOT queue)</li>
 * </ul>
 */
@ApplicationScoped
public class ListingImageProcessingJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(ListingImageProcessingJobHandler.class);

    @Inject
    StorageGateway storageGateway;

    @Inject
    MeterRegistry meterRegistry;

    @Override
    public JobType handlesType() {
        return JobType.LISTING_IMAGE_PROCESSING;
    }

    @Override
    @Transactional
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            UUID imageId = UUID.fromString((String) payload.get("imageId"));
            UUID listingId = UUID.fromString((String) payload.get("listingId"));
            String originalKey = (String) payload.get("originalKey");
            String originalFilename = (String) payload.getOrDefault("originalFilename", "image.jpg");
            int displayOrder = ((Number) payload.getOrDefault("displayOrder", 1)).intValue();

            LOG.infof("Processing image: imageId=%s, listingId=%s, originalKey=%s", imageId, listingId, originalKey);

            // 1. Download original image from R2
            byte[] originalBytes = storageGateway.download(StorageGateway.BucketType.LISTINGS, originalKey);

            if (originalBytes == null || originalBytes.length == 0) {
                throw new IllegalStateException("Failed to download original image from R2: " + originalKey);
            }

            LOG.infof("Downloaded original image: %d bytes", originalBytes.length);

            // 2. Generate thumbnail variant (150x150)
            // TODO: Implement actual resize when WebP conversion is ready (currently stubbed in StorageGateway.convertToWebP())
            // For now, upload original bytes unchanged - infrastructure testing only
            byte[] thumbnailBytes = originalBytes;
            StorageUploadResultType thumbnailResult = storageGateway.upload(
                StorageGateway.BucketType.LISTINGS,
                listingId.toString(),
                "thumbnail",
                thumbnailBytes,
                "image/jpeg"  // Will become image/webp when conversion implemented
            );

            LOG.debugf("Uploaded thumbnail variant: %s (%d bytes)", thumbnailResult.objectKey(), thumbnailResult.sizeBytes());

            // 3. Generate list variant (300x225)
            // TODO: Implement actual resize (currently using original bytes)
            byte[] listBytes = originalBytes;
            StorageUploadResultType listResult = storageGateway.upload(
                StorageGateway.BucketType.LISTINGS,
                listingId.toString(),
                "list",
                listBytes,
                "image/jpeg"
            );

            LOG.debugf("Uploaded list variant: %s (%d bytes)", listResult.objectKey(), listResult.sizeBytes());

            // 4. Generate full variant (1200x900)
            // TODO: Implement actual resize (currently using original bytes)
            byte[] fullBytes = originalBytes;
            StorageUploadResultType fullResult = storageGateway.upload(
                StorageGateway.BucketType.LISTINGS,
                listingId.toString(),
                "full",
                fullBytes,
                "image/jpeg"
            );

            LOG.debugf("Uploaded full variant: %s (%d bytes)", fullResult.objectKey(), fullResult.sizeBytes());

            // 5. Create records for each variant
            createImageRecord(listingId, thumbnailResult, "thumbnail", displayOrder, originalFilename);
            createImageRecord(listingId, listResult, "list", displayOrder, originalFilename);
            createImageRecord(listingId, fullResult, "full", displayOrder, originalFilename);

            // 6. Mark original image as processed
            MarketplaceListingImage originalImage = MarketplaceListingImage.findById(imageId);
            if (originalImage != null) {
                originalImage.status = "processed";
                originalImage.persist();
                LOG.infof("Marked original image as processed: imageId=%s", imageId);
            }

            // 7. Export metrics
            meterRegistry.counter("marketplace.images.processed.total",
                "listing_id", listingId.toString()
            ).increment();

            sample.stop(Timer.builder("marketplace.images.processing.duration")
                .tag("status", "success")
                .register(meterRegistry));

            LOG.infof("Successfully processed image variants for imageId=%s (3 variants created)", imageId);

        } catch (Exception e) {
            sample.stop(Timer.builder("marketplace.images.processing.duration")
                .tag("status", "error")
                .register(meterRegistry));

            meterRegistry.counter("marketplace.images.processing.errors.total",
                "error_type", e.getClass().getSimpleName()
            ).increment();

            LOG.errorf(e, "Failed to process image for jobId=%d", jobId);
            throw e;
        }
    }

    /**
     * Create database record for an image variant.
     */
    private void createImageRecord(UUID listingId, StorageUploadResultType uploadResult,
                                    String variant, int displayOrder, String originalFilename) {
        MarketplaceListingImage image = new MarketplaceListingImage();
        image.listingId = listingId;
        image.storageKey = uploadResult.objectKey();
        image.variant = variant;
        image.contentType = uploadResult.contentType();
        image.sizeBytes = uploadResult.sizeBytes();
        image.displayOrder = displayOrder;
        image.originalFilename = originalFilename;
        image.status = "processed";
        image.createdAt = Instant.now();
        image.persist();

        LOG.debugf("Created %s variant record: storageKey=%s, displayOrder=%d",
            variant, uploadResult.objectKey(), displayOrder);
    }
}
