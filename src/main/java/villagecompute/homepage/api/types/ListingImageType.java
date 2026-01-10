package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for listing image data.
 *
 * Contains metadata and signed URLs for all variants (thumbnail, list, full).
 * Signed URLs have 24-hour TTL and should be refreshed on each page load.
 */
public record ListingImageType(
    @JsonProperty("image_id") UUID imageId,
    @JsonProperty("listing_id") UUID listingId,
    @JsonProperty("display_order") int displayOrder,
    @JsonProperty("original_filename") String originalFilename,
    @JsonProperty("status") String status,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("thumbnail_url") String thumbnailUrl,
    @JsonProperty("list_url") String listUrl,
    @JsonProperty("full_url") String fullUrl
) {
}
