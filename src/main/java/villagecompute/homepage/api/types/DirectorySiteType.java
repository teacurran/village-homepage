package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import villagecompute.homepage.data.models.DirectorySite;

import java.time.Instant;
import java.util.UUID;

/**
 * Response type for directory site data.
 *
 * <p>
 * Used when returning site information to clients. Includes all public fields plus submission metadata.
 * </p>
 *
 * <p>
 * Example response:
 *
 * <pre>{@code
 * {
 *   "id": "550e8400-e29b-41d4-a716-446655440001",
 *   "url": "https://news.ycombinator.com",
 *   "domain": "news.ycombinator.com",
 *   "title": "Hacker News",
 *   "description": "Social news website...",
 *   "screenshot_url": "https://r2.example.com/screenshots/...",
 *   "og_image_url": "https://news.ycombinator.com/og.png",
 *   "submitted_by_user_id": "660e8400-e29b-41d4-a716-446655440099",
 *   "status": "approved",
 *   "is_dead": false,
 *   "created_at": "2025-01-10T12:00:00Z",
 *   "updated_at": "2025-01-10T12:00:00Z"
 * }
 * }</pre>
 */
public record DirectorySiteType(@JsonProperty("id") UUID id,

        @JsonProperty("url") String url,

        @JsonProperty("domain") String domain,

        @JsonProperty("title") String title,

        @JsonProperty("description") String description,

        @JsonProperty("screenshot_url") String screenshotUrl,

        @JsonProperty("screenshot_captured_at") Instant screenshotCapturedAt,

        @JsonProperty("og_image_url") String ogImageUrl,

        @JsonProperty("favicon_url") String faviconUrl,

        @JsonProperty("custom_image_url") String customImageUrl,

        @JsonProperty("submitted_by_user_id") UUID submittedByUserId,

        @JsonProperty("status") String status,

        @JsonProperty("is_dead") boolean isDead,

        @JsonProperty("created_at") Instant createdAt,

        @JsonProperty("updated_at") Instant updatedAt) {
    /**
     * Converts a DirectorySite entity to a response DTO.
     *
     * @param site
     *            Site entity to convert
     * @return Response DTO
     */
    public static DirectorySiteType fromEntity(DirectorySite site) {
        return new DirectorySiteType(site.id, site.url, site.domain, site.title, site.description, site.screenshotUrl,
                site.screenshotCapturedAt, site.ogImageUrl, site.faviconUrl, site.customImageUrl,
                site.submittedByUserId, site.status, site.isDead, site.createdAt, site.updatedAt);
    }
}
