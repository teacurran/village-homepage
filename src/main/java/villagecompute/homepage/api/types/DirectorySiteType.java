package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
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
@Schema(
        description = "Directory site with metadata, screenshots, and submission status")
public record DirectorySiteType(@Schema(
        description = "Unique site identifier",
        example = "550e8400-e29b-41d4-a716-446655440001",
        required = true) @JsonProperty("id") UUID id,

        @Schema(
                description = "Full site URL",
                example = "https://news.ycombinator.com",
                required = true) @JsonProperty("url") String url,

        @Schema(
                description = "Domain name extracted from URL",
                example = "news.ycombinator.com",
                required = true) @JsonProperty("domain") String domain,

        @Schema(
                description = "Site title from OpenGraph or page title",
                example = "Hacker News",
                required = true,
                maxLength = 200) @JsonProperty("title") String title,

        @Schema(
                description = "Site description from OpenGraph or meta tags",
                example = "Social news website focusing on computer science and entrepreneurship",
                nullable = true,
                maxLength = 500) @JsonProperty("description") String description,

        @Schema(
                description = "Auto-captured screenshot URL",
                example = "https://r2.villagecompute.com/screenshots/abc123.png",
                nullable = true) @JsonProperty("screenshot_url") String screenshotUrl,

        @Schema(
                description = "Timestamp when screenshot was captured",
                example = "2026-01-24T10:00:00Z",
                nullable = true) @JsonProperty("screenshot_captured_at") Instant screenshotCapturedAt,

        @Schema(
                description = "OpenGraph image URL",
                example = "https://news.ycombinator.com/og.png",
                nullable = true) @JsonProperty("og_image_url") String ogImageUrl,

        @Schema(
                description = "Favicon URL",
                example = "https://news.ycombinator.com/favicon.ico",
                nullable = true) @JsonProperty("favicon_url") String faviconUrl,

        @Schema(
                description = "Custom uploaded image URL (overrides screenshot/OG)",
                example = "https://r2.villagecompute.com/custom/xyz789.jpg",
                nullable = true) @JsonProperty("custom_image_url") String customImageUrl,

        @Schema(
                description = "User UUID who submitted the site",
                example = "660e8400-e29b-41d4-a716-446655440099",
                required = true) @JsonProperty("submitted_by_user_id") UUID submittedByUserId,

        @Schema(
                description = "Submission status",
                example = "approved",
                enumeration = {
                        "pending", "approved", "rejected", "removed"},
                required = true) @JsonProperty("status") String status,

        @Schema(
                description = "Whether site failed health check (dead link)",
                example = "false",
                required = true) @JsonProperty("is_dead") boolean isDead,

        @Schema(
                description = "Creation timestamp",
                example = "2026-01-10T12:00:00Z",
                required = true) @JsonProperty("created_at") Instant createdAt,

        @Schema(
                description = "Last modification timestamp",
                example = "2026-01-10T12:00:00Z",
                required = true) @JsonProperty("updated_at") Instant updatedAt){
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
