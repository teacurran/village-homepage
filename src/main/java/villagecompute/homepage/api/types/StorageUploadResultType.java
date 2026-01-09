package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Type representing the result of a successful storage upload operation.
 *
 * <p>
 * Returned by StorageGateway after uploading a file to S3/R2. Contains object key, size, and
 * metadata for tracking uploads.
 *
 * <p>
 * <b>Policy P4:</b> All uploaded objects are tracked with indefinite retention metadata.
 *
 * @param objectKey
 *            full object key including bucket prefix (e.g., "screenshots/123/v1/thumbnail.webp")
 * @param bucket
 *            bucket name where object was stored
 * @param sizeBytes
 *            size of uploaded object in bytes
 * @param contentType
 *            MIME type of uploaded object (typically "image/webp")
 * @param variant
 *            image variant type: "thumbnail", "full", "original"
 * @param version
 *            version identifier for versioned uploads (optional)
 * @param uploadedAt
 *            ISO 8601 timestamp when upload completed
 */
public record StorageUploadResultType(@NotBlank @JsonProperty("object_key") String objectKey,
        @NotBlank String bucket, @NotNull @JsonProperty("size_bytes") Long sizeBytes,
        @NotBlank @JsonProperty("content_type") String contentType, @NotBlank String variant, String version,
        @NotBlank @JsonProperty("uploaded_at") String uploadedAt) {
}
