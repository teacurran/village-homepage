package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.qute.TemplateData;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response type for user profile data.
 *
 * <p>
 * Used for GET /api/profiles/{id} and public profile page data retrieval. Includes all profile fields except sensitive
 * data (deleted_at).
 * </p>
 *
 * <p>
 * Example response:
 *
 * <pre>{@code
 * {
 *   "id": "550e8400-e29b-41d4-a716-446655440000",
 *   "user_id": "660e8400-e29b-41d4-a716-446655440000",
 *   "username": "johndoe",
 *   "display_name": "John Doe",
 *   "bio": "Software engineer and coffee enthusiast",
 *   "avatar_url": "https://example.com/avatar.jpg",
 *   "location_text": "Groton, Vermont",
 *   "website_url": "https://johndoe.com",
 *   "social_links": {
 *     "twitter": "johndoe",
 *     "github": "johndoe"
 *   },
 *   "template": "public_homepage",
 *   "template_config": {
 *     "theme": {
 *       "primary_color": "#0066cc"
 *     }
 *   },
 *   "is_published": true,
 *   "view_count": 1234,
 *   "created_at": "2026-01-15T10:00:00Z",
 *   "updated_at": "2026-01-15T12:00:00Z"
 * }
 * }</pre>
 */
@Schema(
        description = "User public profile with customizable template and social links")
@TemplateData(
        ignoreSuperclasses = true,
        properties = false)
public record UserProfileType(@Schema(
        description = "Unique profile identifier",
        example = "550e8400-e29b-41d4-a716-446655440000",
        required = true) @JsonProperty("id") UUID id,

        @Schema(
                description = "Associated user UUID",
                example = "660e8400-e29b-41d4-a716-446655440001",
                required = true) @JsonProperty("user_id") UUID userId,

        @Schema(
                description = "Unique username for profile URL",
                example = "johndoe",
                required = true,
                maxLength = 50) @JsonProperty("username") String username,

        @Schema(
                description = "Display name shown on profile",
                example = "John Doe",
                nullable = true,
                maxLength = 100) @JsonProperty("display_name") String displayName,

        @Schema(
                description = "Profile bio/description",
                example = "Software engineer and coffee enthusiast",
                nullable = true,
                maxLength = 500) @JsonProperty("bio") String bio,

        @Schema(
                description = "Avatar image URL",
                example = "https://cdn.villagecompute.com/avatars/johndoe.jpg",
                nullable = true) @JsonProperty("avatar_url") String avatarUrl,

        @Schema(
                description = "Location text (free-form)",
                example = "Groton, Vermont",
                nullable = true,
                maxLength = 100) @JsonProperty("location_text") String locationText,

        @Schema(
                description = "Personal website URL",
                example = "https://johndoe.com",
                nullable = true) @JsonProperty("website_url") String websiteUrl,

        @Schema(
                description = "Social media links as key-value pairs",
                nullable = true) @JsonProperty("social_links") Map<String, Object> socialLinks,

        @Schema(
                description = "Profile template identifier",
                example = "public_homepage",
                required = true) @JsonProperty("template") String template,

        @Schema(
                description = "Template configuration JSON",
                nullable = true) @JsonProperty("template_config") Map<String, Object> templateConfig,

        @Schema(
                description = "Whether profile is published and visible",
                example = "true",
                required = true) @JsonProperty("is_published") boolean isPublished,

        @Schema(
                description = "Total profile view count",
                example = "1234",
                required = true) @JsonProperty("view_count") long viewCount,

        @Schema(
                description = "Creation timestamp",
                example = "2026-01-15T10:00:00Z",
                required = true) @JsonProperty("created_at") Instant createdAt,

        @Schema(
                description = "Last modification timestamp",
                example = "2026-01-15T12:00:00Z",
                required = true) @JsonProperty("updated_at") Instant updatedAt) {
}
