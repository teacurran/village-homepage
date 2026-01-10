package villagecompute.homepage.jobs;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import villagecompute.homepage.api.types.StorageUploadResultType;
import villagecompute.homepage.data.models.DirectoryScreenshotVersion;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.services.ScreenshotService;
import villagecompute.homepage.services.StorageGateway;

/**
 * Job handler for capturing Good Sites directory screenshots.
 *
 * <p>
 * <b>Workflow:</b>
 * <ol>
 * <li>Acquire browser pool semaphore slot (max 3 concurrent per P12)</li>
 * <li>Launch headless Chromium via jvppeteer</li>
 * <li>Navigate to site URL with 30-second timeout</li>
 * <li>Capture full viewport screenshot (1280x800)</li>
 * <li>Capture thumbnail variant (320x200)</li>
 * <li>Upload both variants to R2 via StorageGateway</li>
 * <li>Create directory_screenshot_versions record</li>
 * <li>Update DirectorySite.screenshot_url and screenshot_captured_at</li>
 * <li>Release semaphore slot</li>
 * </ol>
 *
 * <p>
 * <b>Policy P12:</b> Max 3 concurrent captures enforced via semaphore in ScreenshotService. Each browser instance
 * consumes ~200-300 MB memory.
 *
 * <p>
 * <b>Policy P4:</b> Indefinite retention - all screenshot versions stored in R2 with full version history. Old versions
 * are never auto-deleted.
 *
 * <p>
 * <b>Failure Handling:</b> On timeout or error, creates a failed version record with error details. DirectorySite keeps
 * og_image_url as fallback for display.
 *
 * <p>
 * <b>Metrics Emitted:</b>
 * <ul>
 * <li>screenshot.captures.total (counter, tagged with status=success|failed|timeout)</li>
 * <li>screenshot.capture.duration (histogram, milliseconds)</li>
 * <li>screenshot.errors.total (counter, tagged with error_type)</li>
 * </ul>
 */
@ApplicationScoped
public class ScreenshotCaptureJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(ScreenshotCaptureJobHandler.class);

    @Inject
    ScreenshotService screenshotService;

    @Inject
    StorageGateway storageGateway;

    @Inject
    MeterRegistry meterRegistry;

    @Override
    public JobType handlesType() {
        return JobType.SCREENSHOT_CAPTURE;
    }

    @Override
    @Transactional
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        Timer.Sample sample = Timer.start(meterRegistry);
        long startTime = System.currentTimeMillis();

        UUID siteId = UUID.fromString((String) payload.get("siteId"));
        String url = (String) payload.get("url");
        boolean isRecapture = (boolean) payload.getOrDefault("isRecapture", false);

        LOG.infof("Starting screenshot capture: jobId=%d, siteId=%s, url=%s, recapture=%s", jobId, siteId, url,
                isRecapture);

        try {
            // 1. Get next version number (increment from latest, or 1 if no versions exist)
            int version = DirectoryScreenshotVersion.getLatestVersion(siteId) + 1;
            LOG.debugf("Capturing screenshot version %d for siteId=%s", version, siteId);

            // 2. Capture full screenshot (1280x800)
            byte[] fullScreenshot = screenshotService.captureScreenshot(url, 1280, 800);
            LOG.debugf("Full screenshot captured: %d bytes", fullScreenshot.length);

            // 3. Capture thumbnail (320x200)
            byte[] thumbnailScreenshot = screenshotService.captureScreenshot(url, 320, 200);
            LOG.debugf("Thumbnail screenshot captured: %d bytes", thumbnailScreenshot.length);

            // 4. Upload full variant to R2
            // Object key format: {site-id}/v{version}/full.webp (per Policy P4)
            StorageUploadResultType fullResult = storageGateway.upload(StorageGateway.BucketType.SCREENSHOTS,
                    siteId.toString() + "/v" + version, "full", fullScreenshot, "image/png");

            LOG.debugf("Full variant uploaded: %s (%d bytes)", fullResult.objectKey(), fullResult.sizeBytes());

            // 5. Upload thumbnail variant to R2
            // Object key format: {site-id}/v{version}/thumbnail.webp
            StorageUploadResultType thumbnailResult = storageGateway.upload(StorageGateway.BucketType.SCREENSHOTS,
                    siteId.toString() + "/v" + version, "thumbnail", thumbnailScreenshot, "image/png");

            LOG.debugf("Thumbnail variant uploaded: %s (%d bytes)", thumbnailResult.objectKey(),
                    thumbnailResult.sizeBytes());

            long duration = System.currentTimeMillis() - startTime;

            // 6. Create version history record
            DirectoryScreenshotVersion versionRecord = new DirectoryScreenshotVersion();
            versionRecord.siteId = siteId;
            versionRecord.version = version;
            versionRecord.fullStorageKey = fullResult.objectKey();
            versionRecord.thumbnailStorageKey = thumbnailResult.objectKey();
            versionRecord.capturedAt = Instant.now();
            versionRecord.captureDurationMs = (int) duration;
            versionRecord.status = "success";
            versionRecord.errorMessage = null;
            versionRecord.createdAt = Instant.now();
            versionRecord.persist();

            LOG.debugf("Version record created: siteId=%s, version=%d, status=success", siteId, version);

            // 7. Update DirectorySite with latest screenshot
            DirectorySite site = DirectorySite.findById(siteId);
            if (site != null) {
                site.screenshotUrl = fullResult.objectKey();
                site.screenshotCapturedAt = Instant.now();
                site.updatedAt = Instant.now();
                site.persist();
                LOG.debugf("DirectorySite updated: screenshotUrl=%s", fullResult.objectKey());
            } else {
                LOG.warnf("DirectorySite not found for siteId=%s, cannot update screenshot_url", siteId);
            }

            // 8. Emit success metrics
            meterRegistry.counter("screenshot.captures.total", "status", "success", "is_recapture",
                    String.valueOf(isRecapture)).increment();

            sample.stop(Timer.builder("screenshot.capture.duration").tag("status", "success").register(meterRegistry));

            LOG.infof("Screenshot capture successful: siteId=%s, version=%d, duration=%dms", siteId, version, duration);

        } catch (ScreenshotService.ScreenshotCaptureException e) {
            handleCaptureFailure(siteId, url, isRecapture, startTime, sample, e);
            // Re-throw for job retry logic
            throw e;
        } catch (Exception e) {
            handleCaptureFailure(siteId, url, isRecapture, startTime, sample, e);
            // Re-throw for job retry logic
            throw e;
        }
    }

    /**
     * Handles screenshot capture failures by creating a failed version record and emitting failure metrics.
     *
     * @param siteId
     *            Site UUID
     * @param url
     *            Site URL
     * @param isRecapture
     *            Whether this is a recapture
     * @param startTime
     *            Capture start timestamp
     * @param sample
     *            Timer sample for metrics
     * @param e
     *            Exception that caused failure
     */
    private void handleCaptureFailure(UUID siteId, String url, boolean isRecapture, long startTime, Timer.Sample sample,
            Exception e) {
        long duration = System.currentTimeMillis() - startTime;

        // Determine failure type from exception
        String status;
        if (e.getMessage() != null && e.getMessage().contains("timeout")) {
            status = "timeout";
        } else {
            status = "failed";
        }

        LOG.errorf(e, "Screenshot capture %s: siteId=%s, url=%s, duration=%dms", status, siteId, url, duration);

        try {
            // Record failed version for audit trail
            int version = DirectoryScreenshotVersion.getLatestVersion(siteId) + 1;

            DirectoryScreenshotVersion versionRecord = new DirectoryScreenshotVersion();
            versionRecord.siteId = siteId;
            versionRecord.version = version;
            versionRecord.thumbnailStorageKey = ""; // Empty for failed captures
            versionRecord.fullStorageKey = ""; // Empty for failed captures
            versionRecord.capturedAt = Instant.now();
            versionRecord.captureDurationMs = (int) duration;
            versionRecord.status = status;
            versionRecord.errorMessage = truncateErrorMessage(e);
            versionRecord.createdAt = Instant.now();
            versionRecord.persist();

            LOG.debugf("Failed version record created: siteId=%s, version=%d, status=%s", siteId, version, status);

        } catch (Exception recordException) {
            LOG.errorf(recordException, "Failed to create failed version record for siteId=%s", siteId);
        }

        // Emit failure metrics
        meterRegistry
                .counter("screenshot.captures.total", "status", status, "is_recapture", String.valueOf(isRecapture))
                .increment();

        meterRegistry
                .counter("screenshot.errors.total", "error_type",
                        e.getCause() != null ? e.getCause().getClass().getSimpleName() : e.getClass().getSimpleName())
                .increment();

        sample.stop(Timer.builder("screenshot.capture.duration").tag("status", "error").register(meterRegistry));
    }

    /**
     * Truncates error message to 500 characters for database storage.
     *
     * @param e
     *            Exception
     * @return Truncated error message
     */
    private String truncateErrorMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            message = e.getClass().getSimpleName();
        }
        if (message.length() > 500) {
            return message.substring(0, 497) + "...";
        }
        return message;
    }
}
