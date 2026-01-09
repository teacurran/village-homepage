package villagecompute.homepage.api.types;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Type representing metadata for a stored object in S3/R2.
 *
 * <p>
 * Used by StorageGateway when listing objects for admin dashboards and audit operations.
 *
 * <p>
 * <b>Policy P4:</b> Object metadata includes retention policy and version information for
 * indefinite retention compliance.
 *
 * @param objectKey
 *            full object key including bucket prefix
 * @param sizeBytes
 *            size of object in bytes
 * @param contentType
 *            MIME type of object
 * @param lastModified
 *            ISO 8601 timestamp when object was last modified
 * @param metadata
 *            custom metadata tags (retention-policy, version, etc.)
 */
public record StorageObjectType(@NotBlank @JsonProperty("object_key") String objectKey,
        @NotNull @JsonProperty("size_bytes") Long sizeBytes, @NotBlank @JsonProperty("content_type") String contentType,
        @NotBlank @JsonProperty("last_modified") String lastModified, @NotNull Map<String, String> metadata) {
}
