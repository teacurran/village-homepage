package villagecompute.homepage.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.MarketplaceListingImage;
import villagecompute.homepage.services.StorageGateway;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Job handler for cleaning up listing images from R2 storage.
 *
 * <p>
 * Triggered when a listing is soft-deleted or expired. Removes both database records
 * and R2 objects for all variants (original, thumbnail, list, full).
 *
 * <p>
 * Workflow:
 * <ol>
 *   <li>Find all images for the listing (all variants)</li>
 *   <li>Delete each image from R2 storage via StorageGateway</li>
 *   <li>Delete database records via cascade or explicit delete</li>
 *   <li>Export cleanup metrics (count, bytes freed)</li>
 * </ol>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 *   <li>P1: GDPR compliance - hard-deletes PII when user deleted or listing removed</li>
 *   <li>P4: Storage cost control - cleanup prevents indefinite retention of removed content</li>
 * </ul>
 */
@ApplicationScoped
public class ListingImageCleanupJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(ListingImageCleanupJobHandler.class);

    @Inject
    StorageGateway storageGateway;

    @Inject
    MeterRegistry meterRegistry;

    @Override
    public JobType handlesType() {
        return JobType.LISTING_IMAGE_CLEANUP;
    }

    @Override
    @Transactional
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            UUID listingId = UUID.fromString((String) payload.get("listingId"));

            LOG.infof("Cleaning up images for listing: listingId=%s", listingId);

            // 1. Find all images for this listing (all variants)
            List<MarketplaceListingImage> images = MarketplaceListingImage.findByListingId(listingId);

            if (images.isEmpty()) {
                LOG.infof("No images to clean up for listingId=%s", listingId);
                sample.stop(Timer.builder("marketplace.images.cleanup.duration")
                    .tag("status", "success")
                    .tag("image_count", "0")
                    .register(meterRegistry));
                return;
            }

            LOG.infof("Found %d image records to clean up", images.size());

            long totalBytesFreed = 0;
            int successCount = 0;
            int failureCount = 0;

            // 2. Delete each image from R2 storage
            for (MarketplaceListingImage image : images) {
                try {
                    storageGateway.delete(StorageGateway.BucketType.LISTINGS, image.storageKey);
                    totalBytesFreed += image.sizeBytes;
                    successCount++;
                    LOG.debugf("Deleted from R2: %s (%d bytes)", image.storageKey, image.sizeBytes);
                } catch (Exception e) {
                    failureCount++;
                    LOG.warnf(e, "Failed to delete image from R2: %s (continuing cleanup)", image.storageKey);
                    // Continue cleanup even if some deletes fail
                }
            }

            // 3. Delete database records
            long deletedCount = MarketplaceListingImage.deleteByListingId(listingId);

            LOG.infof("Cleanup complete: listingId=%s, deleted=%d records, freed=%d bytes, failures=%d",
                listingId, deletedCount, totalBytesFreed, failureCount);

            // 4. Export metrics
            meterRegistry.counter("marketplace.images.cleaned.total",
                "listing_id", listingId.toString()
            ).increment(deletedCount);

            meterRegistry.counter("marketplace.storage.bytes.freed",
                "bucket", "listings"
            ).increment(totalBytesFreed);

            if (failureCount > 0) {
                meterRegistry.counter("marketplace.images.cleanup.failures.total",
                    "listing_id", listingId.toString()
                ).increment(failureCount);
            }

            sample.stop(Timer.builder("marketplace.images.cleanup.duration")
                .tag("status", failureCount > 0 ? "partial" : "success")
                .tag("image_count", String.valueOf(deletedCount))
                .register(meterRegistry));

        } catch (Exception e) {
            sample.stop(Timer.builder("marketplace.images.cleanup.duration")
                .tag("status", "error")
                .tag("image_count", "0")
                .register(meterRegistry));

            meterRegistry.counter("marketplace.images.cleanup.errors.total",
                "error_type", e.getClass().getSimpleName()
            ).increment();

            LOG.errorf(e, "Failed to clean up images for jobId=%d", jobId);
            throw e;
        }
    }
}
