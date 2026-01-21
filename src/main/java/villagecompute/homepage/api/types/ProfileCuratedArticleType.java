package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response type for curated article data.
 *
 * <p>
 * Used for GET /api/profiles/{id}/articles and article list responses. Includes effective display values (custom or
 * original).
 * </p>
 *
 * <p>
 * Example response:
 *
 * <pre>{@code
 * {
 *   "id": "550e8400-e29b-41d4-a716-446655440000",
 *   "profile_id": "660e8400-e29b-41d4-a716-446655440000",
 *   "feed_item_id": "770e8400-e29b-41d4-a716-446655440000",
 *   "original_url": "https://example.com/article",
 *   "original_title": "Original Article Title",
 *   "original_description": "Original description",
 *   "original_image_url": "https://example.com/image.jpg",
 *   "custom_headline": "My Custom Headline",
 *   "custom_blurb": "My custom description",
 *   "custom_image_url": null,
 *   "effective_headline": "My Custom Headline",
 *   "effective_description": "My custom description",
 *   "effective_image_url": "https://example.com/image.jpg",
 *   "slot_assignment": {},
 *   "is_active": true,
 *   "created_at": "2026-01-15T10:00:00Z",
 *   "updated_at": "2026-01-15T12:00:00Z"
 * }
 * }</pre>
 */
public record ProfileCuratedArticleType(@JsonProperty("id") UUID id, @JsonProperty("profile_id") UUID profileId,
        @JsonProperty("feed_item_id") UUID feedItemId, @JsonProperty("original_url") String originalUrl,
        @JsonProperty("original_title") String originalTitle,
        @JsonProperty("original_description") String originalDescription,
        @JsonProperty("original_image_url") String originalImageUrl,
        @JsonProperty("custom_headline") String customHeadline, @JsonProperty("custom_blurb") String customBlurb,
        @JsonProperty("custom_image_url") String customImageUrl,
        @JsonProperty("effective_headline") String effectiveHeadline,
        @JsonProperty("effective_description") String effectiveDescription,
        @JsonProperty("effective_image_url") String effectiveImageUrl,
        @JsonProperty("slot_assignment") Map<String, Object> slotAssignment,
        @JsonProperty("is_active") boolean isActive, @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {
}
