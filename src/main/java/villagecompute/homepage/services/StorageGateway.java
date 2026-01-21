package villagecompute.homepage.services;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import villagecompute.homepage.api.types.SignedUrlType;
import villagecompute.homepage.api.types.StorageObjectType;
import villagecompute.homepage.api.types.StorageUploadResultType;

/**
 * StorageGateway service for Cloudflare R2 object storage operations.
 *
 * <p>
 * This service provides a unified abstraction over S3-compatible object storage (MinIO for dev, Cloudflare R2 for
 * prod), with support for:
 * <ul>
 * <li>WebP conversion with thumbnail/full variants (Policy P4)</li>
 * <li>Signed URL generation with TTL-based access control</li>
 * <li>Bucket prefixing strategy for screenshots, listings, profiles</li>
 * <li>Retention metadata mapping for indefinite storage (Policy P4)</li>
 * <li>OpenTelemetry tracing and Micrometer metrics instrumentation</li>
 * </ul>
 *
 * <p>
 * <b>Policy P4:</b> Implements indefinite screenshot retention with versioning and CDN-backed delivery.
 *
 * <p>
 * <b>Usage Example:</b>
 *
 * <pre>
 * StorageUploadResultType result = storageGateway.upload(BucketType.SCREENSHOTS, "site-123", "thumbnail", imageBytes,
 *         "image/jpeg");
 * SignedUrlType url = storageGateway.generateSignedUrl(BucketType.SCREENSHOTS, result.objectKey(), 1440);
 * </pre>
 *
 * @see villagecompute.homepage.api.types.StorageUploadResultType
 * @see villagecompute.homepage.api.types.SignedUrlType
 * @see villagecompute.homepage.api.types.StorageObjectType
 */
@ApplicationScoped
public class StorageGateway {

    private static final Logger LOG = Logger.getLogger(StorageGateway.class);

    @Inject
    S3Client s3Client;

    @Inject
    S3Presigner s3Presigner;

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(
            name = "villagecompute.storage.buckets.screenshots")
    String screenshotsBucket;

    @ConfigProperty(
            name = "villagecompute.storage.buckets.listings")
    String listingsBucket;

    @ConfigProperty(
            name = "villagecompute.storage.buckets.profiles")
    String profilesBucket;

    @ConfigProperty(
            name = "villagecompute.storage.buckets.gdpr-exports")
    String gdprExportsBucket;

    @ConfigProperty(
            name = "villagecompute.storage.webp.quality")
    Integer webpQuality;

    @ConfigProperty(
            name = "villagecompute.storage.webp.thumbnail-width")
    Integer thumbnailWidth;

    @ConfigProperty(
            name = "villagecompute.storage.webp.thumbnail-height")
    Integer thumbnailHeight;

    @ConfigProperty(
            name = "villagecompute.storage.webp.full-width")
    Integer fullWidth;

    @ConfigProperty(
            name = "villagecompute.storage.webp.full-height")
    Integer fullHeight;

    /**
     * Bucket types per asset domain (Policy P4).
     */
    public enum BucketType {
        SCREENSHOTS, LISTINGS, PROFILES, GDPR_EXPORTS
    }

    /**
     * Uploads file to specified bucket with WebP conversion and variant handling.
     *
     * <p>
     * Generates object key with appropriate prefix for bucket organization. Records upload metrics and traces for
     * observability.
     *
     * <p>
     * <b>Note:</b> WebP conversion is currently stubbed and will be implemented in a future iteration. Images are
     * stored as-is for now.
     *
     * @param bucket
     *            target bucket (screenshots, listings, profiles)
     * @param entityId
     *            entity identifier (site_id, listing_id, user_id)
     * @param variant
     *            image variant: "thumbnail", "full", "original"
     * @param fileBytes
     *            raw image bytes
     * @param originalContentType
     *            original MIME type (e.g., "image/jpeg")
     * @return upload result with object key and metadata
     * @throws RuntimeException
     *             if upload fails (network error, invalid credentials, etc.)
     */
    public StorageUploadResultType upload(BucketType bucket, String entityId, String variant, byte[] fileBytes,
            String originalContentType) {
        Span span = tracer.spanBuilder("storage.upload").setAttribute("bucket", bucket.name())
                .setAttribute("entity_id", entityId).setAttribute("variant", variant)
                .setAttribute("size_bytes", fileBytes.length).startSpan();

        long startTime = System.currentTimeMillis();

        try (Scope scope = span.makeCurrent()) {
            String bucketName = getBucketName(bucket);
            String objectKey = buildObjectKey(bucket, entityId, variant, "image.webp");

            // TODO: Implement actual WebP conversion in future iteration
            byte[] processedBytes = convertToWebP(fileBytes, variant);

            Map<String, String> metadata = buildMetadata(originalContentType, "indefinite", null);

            PutObjectRequest putRequest = PutObjectRequest.builder().bucket(bucketName).key(objectKey)
                    .contentType("image/webp") // Target format after conversion
                    .metadata(metadata).build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(processedBytes));

            long latencyMs = System.currentTimeMillis() - startTime;

            LOG.infof("Uploaded %s to %s/%s (%d bytes, %dms)", variant, bucketName, objectKey, processedBytes.length,
                    latencyMs);

            recordUploadMetrics(bucket, processedBytes.length, latencyMs, true);
            span.setAttribute("upload_success", true);

            return new StorageUploadResultType(objectKey, bucketName, (long) processedBytes.length, "image/webp",
                    variant, null, Instant.now().toString());

        } catch (S3Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            recordUploadMetrics(bucket, fileBytes.length, latencyMs, false);

            span.recordException(e);
            span.setAttribute("upload_success", false);
            LOG.errorf(e, "Failed to upload to %s/%s: %s", bucket, entityId, e.awsErrorDetails().errorMessage());
            throw new RuntimeException("Storage upload failed: " + e.awsErrorDetails().errorMessage(), e);

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            recordUploadMetrics(bucket, fileBytes.length, latencyMs, false);

            span.recordException(e);
            span.setAttribute("upload_success", false);
            LOG.errorf(e, "Failed to upload to %s/%s: %s", bucket, entityId, e.getMessage());
            throw new RuntimeException("Storage upload failed: " + e.getMessage(), e);

        } finally {
            span.end();
        }
    }

    /**
     * Downloads file from bucket by object key.
     *
     * <p>
     * Returns raw bytes for further processing. Typically used internally by job handlers for image processing
     * pipelines.
     *
     * @param bucket
     *            source bucket
     * @param objectKey
     *            full object key including prefix
     * @return raw file bytes
     * @throws RuntimeException
     *             if download fails (object not found, network error, etc.)
     */
    public byte[] download(BucketType bucket, String objectKey) {
        Span span = tracer.spanBuilder("storage.download").setAttribute("bucket", bucket.name())
                .setAttribute("object_key", objectKey).startSpan();

        long startTime = System.currentTimeMillis();

        try (Scope scope = span.makeCurrent()) {
            String bucketName = getBucketName(bucket);

            GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucketName).key(objectKey).build();

            byte[] bytes = s3Client.getObjectAsBytes(getRequest).asByteArray();

            long latencyMs = System.currentTimeMillis() - startTime;

            LOG.debugf("Downloaded %s/%s (%d bytes, %dms)", bucketName, objectKey, bytes.length, latencyMs);

            recordDownloadMetrics(bucket, bytes.length, latencyMs, true);
            span.setAttribute("download_success", true);
            span.setAttribute("size_bytes", bytes.length);

            return bytes;

        } catch (S3Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            recordDownloadMetrics(bucket, 0, latencyMs, false);

            span.recordException(e);
            span.setAttribute("download_success", false);
            LOG.errorf(e, "Failed to download %s: %s", objectKey, e.awsErrorDetails().errorMessage());
            throw new RuntimeException("Storage download failed: " + e.awsErrorDetails().errorMessage(), e);

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            recordDownloadMetrics(bucket, 0, latencyMs, false);

            span.recordException(e);
            span.setAttribute("download_success", false);
            LOG.errorf(e, "Failed to download %s: %s", objectKey, e.getMessage());
            throw new RuntimeException("Storage download failed: " + e.getMessage(), e);

        } finally {
            span.end();
        }
    }

    /**
     * Generates pre-signed URL for temporary direct access to private object.
     *
     * <p>
     * TTL based on bucket type and privacy requirements (24 hours for private drafts, 7 days for public assets per
     * Policy P4).
     *
     * @param bucket
     *            source bucket
     * @param objectKey
     *            full object key including prefix
     * @param ttlMinutes
     *            time-to-live in minutes
     * @return signed URL with embedded expiration
     * @throws RuntimeException
     *             if URL generation fails
     */
    public SignedUrlType generateSignedUrl(BucketType bucket, String objectKey, int ttlMinutes) {
        Span span = tracer.spanBuilder("storage.generate_signed_url").setAttribute("bucket", bucket.name())
                .setAttribute("ttl_minutes", ttlMinutes).startSpan();

        try (Scope scope = span.makeCurrent()) {
            String bucketName = getBucketName(bucket);
            Duration ttl = Duration.ofMinutes(ttlMinutes);

            GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucketName).key(objectKey).build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder().signatureDuration(ttl)
                    .getObjectRequest(getRequest).build();

            PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
            String url = presigned.url().toString();
            Instant expiresAt = Instant.now().plus(ttl);

            LOG.debugf("Generated signed URL for %s (expires in %d minutes)", objectKey, ttlMinutes);

            recordSignedUrlMetrics(bucket, true);
            span.setAttribute("url_generated", true);

            return new SignedUrlType(url, expiresAt.toString(), ttlMinutes, objectKey);

        } catch (Exception e) {
            recordSignedUrlMetrics(bucket, false);

            span.recordException(e);
            span.setAttribute("url_generated", false);
            LOG.errorf(e, "Failed to generate signed URL for %s", objectKey);
            throw new RuntimeException("Failed to generate signed URL: " + e.getMessage(), e);

        } finally {
            span.end();
        }
    }

    /**
     * Deletes object from bucket (for GDPR compliance and user-requested removals).
     *
     * <p>
     * Logs deletion for audit trail per Policy P1. Note: This is a hard delete and cannot be undone.
     *
     * @param bucket
     *            source bucket
     * @param objectKey
     *            full object key including prefix
     * @throws RuntimeException
     *             if deletion fails
     */
    public void delete(BucketType bucket, String objectKey) {
        Span span = tracer.spanBuilder("storage.delete").setAttribute("bucket", bucket.name())
                .setAttribute("object_key", objectKey).startSpan();

        try (Scope scope = span.makeCurrent()) {
            String bucketName = getBucketName(bucket);

            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder().bucket(bucketName).key(objectKey).build();

            s3Client.deleteObject(deleteRequest);

            LOG.warnf("DELETED object %s/%s (audit trail)", bucketName, objectKey);

            recordDeleteMetrics(bucket, true);
            span.setAttribute("delete_success", true);

        } catch (S3Exception e) {
            recordDeleteMetrics(bucket, false);

            span.recordException(e);
            span.setAttribute("delete_success", false);
            LOG.errorf(e, "Failed to delete %s: %s", objectKey, e.awsErrorDetails().errorMessage());
            throw new RuntimeException("Storage deletion failed: " + e.awsErrorDetails().errorMessage(), e);

        } catch (Exception e) {
            recordDeleteMetrics(bucket, false);

            span.recordException(e);
            span.setAttribute("delete_success", false);
            LOG.errorf(e, "Failed to delete %s: %s", objectKey, e.getMessage());
            throw new RuntimeException("Storage deletion failed: " + e.getMessage(), e);

        } finally {
            span.end();
        }
    }

    /**
     * Lists all objects with given prefix (for admin dashboards and audit operations).
     *
     * <p>
     * Returns object keys and metadata. Use with caution on large buckets - consider pagination for production use.
     *
     * @param bucket
     *            source bucket
     * @param prefix
     *            object key prefix (e.g., "screenshots/123/")
     * @param maxResults
     *            maximum number of objects to return
     * @return list of storage objects with metadata
     * @throws RuntimeException
     *             if listing fails
     */
    public List<StorageObjectType> listObjects(BucketType bucket, String prefix, int maxResults) {
        Span span = tracer.spanBuilder("storage.list_objects").setAttribute("bucket", bucket.name())
                .setAttribute("prefix", prefix).setAttribute("max_results", maxResults).startSpan();

        try (Scope scope = span.makeCurrent()) {
            String bucketName = getBucketName(bucket);

            ListObjectsV2Request listRequest = ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix)
                    .maxKeys(maxResults).build();

            ListObjectsV2Response response = s3Client.listObjectsV2(listRequest);

            List<StorageObjectType> objects = new ArrayList<>();
            for (S3Object s3Object : response.contents()) {
                objects.add(new StorageObjectType(s3Object.key(), s3Object.size(), "unknown", // ContentType not
                                                                                              // included in
                                                                                              // listObjectsV2
                        s3Object.lastModified().toString(), new HashMap<>() // Metadata requires separate HEAD request
                ));
            }

            LOG.debugf("Listed %d objects in %s with prefix %s", objects.size(), bucketName, prefix);

            span.setAttribute("object_count", objects.size());

            return objects;

        } catch (S3Exception e) {
            span.recordException(e);
            LOG.errorf(e, "Failed to list objects in %s: %s", bucket, e.awsErrorDetails().errorMessage());
            throw new RuntimeException("Storage listing failed: " + e.awsErrorDetails().errorMessage(), e);

        } catch (Exception e) {
            span.recordException(e);
            LOG.errorf(e, "Failed to list objects in %s: %s", bucket, e.getMessage());
            throw new RuntimeException("Storage listing failed: " + e.getMessage(), e);

        } finally {
            span.end();
        }
    }

    /**
     * Converts uploaded image to WebP format with appropriate dimensions.
     *
     * <p>
     * <b>TODO:</b> Implement actual WebP conversion using image processing library. For now, this is a pass-through
     * that preserves the original image format.
     *
     * <p>
     * Future implementation should use a library like Thumbnailator or ImageMagick wrapper to:
     * <ul>
     * <li>Resize to target dimensions (320x200 for thumbnails, 1280x800 for full)</li>
     * <li>Convert to WebP format with configured quality (default 85)</li>
     * <li>Preserve aspect ratio with letterboxing if needed</li>
     * <li>Strip EXIF metadata for privacy (Policy P4)</li>
     * </ul>
     *
     * @param originalBytes
     *            raw image bytes from upload
     * @param variant
     *            target variant ("thumbnail", "full", "original")
     * @return converted image bytes (currently returns original, unmodified)
     */
    private byte[] convertToWebP(byte[] originalBytes, String variant) {
        LOG.warnf("WebP conversion not yet implemented - returning original image (Policy P4 stub)");
        // TODO: Use image processing library (e.g., Thumbnailator, ImageMagick wrapper)
        // int targetWidth = variant.equals("thumbnail") ? thumbnailWidth : fullWidth;
        // int targetHeight = variant.equals("thumbnail") ? thumbnailHeight : fullHeight;
        // return ImageProcessor.convertToWebP(originalBytes, targetWidth, targetHeight, webpQuality);
        return originalBytes;
    }

    /**
     * Generates object key with appropriate prefix for bucket organization.
     *
     * <p>
     * Examples:
     * <ul>
     * <li>site-123/v1/thumbnail.webp (in screenshots bucket)</li>
     * <li>listing-456/full/image.webp (in listings bucket)</li>
     * <li>user-789/avatar.webp (in profiles bucket)</li>
     * </ul>
     *
     * @param bucket
     *            target bucket
     * @param entityId
     *            entity identifier
     * @param variant
     *            image variant type
     * @param filename
     *            target filename
     * @return full object key with prefix
     */
    private String buildObjectKey(BucketType bucket, String entityId, String variant, String filename) {
        return String.format("%s/%s/%s", entityId, variant, filename);
    }

    /**
     * Builds retention metadata for S3 object tagging.
     *
     * <p>
     * Metadata is used by Cloudflare lifecycle policies to manage tiering and retention per Policy P4.
     *
     * @param contentType
     *            original MIME type
     * @param retentionPolicy
     *            retention policy identifier ("indefinite", "90-days", etc.)
     * @param version
     *            version identifier (optional)
     * @return metadata map
     */
    private Map<String, String> buildMetadata(String contentType, String retentionPolicy, String version) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("content-type-original", contentType);
        metadata.put("retention-policy", retentionPolicy);
        metadata.put("uploaded-at", Instant.now().toString());
        metadata.put("service", "village-homepage");
        if (version != null) {
            metadata.put("version", version);
        }
        return metadata;
    }

    /**
     * Resolves bucket name from configuration.
     *
     * @param bucket
     *            bucket type
     * @return configured bucket name
     */
    private String getBucketName(BucketType bucket) {
        return switch (bucket) {
            case SCREENSHOTS -> screenshotsBucket;
            case LISTINGS -> listingsBucket;
            case PROFILES -> profilesBucket;
            case GDPR_EXPORTS -> gdprExportsBucket;
        };
    }

    /**
     * Records upload metrics for monitoring and alerting.
     */
    private void recordUploadMetrics(BucketType bucket, long bytes, long latencyMs, boolean success) {
        String status = success ? "success" : "failure";

        Counter.builder("storage.uploads.total").tag("bucket", bucket.name().toLowerCase()).tag("status", status)
                .register(meterRegistry).increment();

        if (success) {
            Counter.builder("storage.bytes.uploaded").tag("bucket", bucket.name().toLowerCase()).register(meterRegistry)
                    .increment(bytes);
        }

        Timer.builder("storage.upload.duration").tag("bucket", bucket.name().toLowerCase()).tag("status", status)
                .register(meterRegistry).record(Duration.ofMillis(latencyMs));
    }

    /**
     * Records download metrics for monitoring and alerting.
     */
    private void recordDownloadMetrics(BucketType bucket, long bytes, long latencyMs, boolean success) {
        String status = success ? "success" : "failure";

        Counter.builder("storage.downloads.total").tag("bucket", bucket.name().toLowerCase()).tag("status", status)
                .register(meterRegistry).increment();

        if (success) {
            Counter.builder("storage.bytes.downloaded").tag("bucket", bucket.name().toLowerCase())
                    .register(meterRegistry).increment(bytes);
        }

        Timer.builder("storage.download.duration").tag("bucket", bucket.name().toLowerCase()).tag("status", status)
                .register(meterRegistry).record(Duration.ofMillis(latencyMs));
    }

    /**
     * Records signed URL generation metrics for monitoring.
     */
    private void recordSignedUrlMetrics(BucketType bucket, boolean success) {
        String status = success ? "success" : "failure";

        Counter.builder("storage.signed_urls.total").tag("bucket", bucket.name().toLowerCase()).tag("status", status)
                .register(meterRegistry).increment();
    }

    /**
     * Records deletion metrics for audit trail and monitoring.
     */
    private void recordDeleteMetrics(BucketType bucket, boolean success) {
        String status = success ? "success" : "failure";

        Counter.builder("storage.deletes.total").tag("bucket", bucket.name().toLowerCase()).tag("status", status)
                .register(meterRegistry).increment();
    }
}
