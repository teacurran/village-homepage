package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.qute.TemplateData;

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
@TemplateData(
        ignoreSuperclasses = true,
        properties = false)
public record UserProfileType(@JsonProperty("id") UUID id, @JsonProperty("user_id") UUID userId,
        @JsonProperty("username") String username, @JsonProperty("display_name") String displayName,
        @JsonProperty("bio") String bio, @JsonProperty("avatar_url") String avatarUrl,
        @JsonProperty("location_text") String locationText, @JsonProperty("website_url") String websiteUrl,
        @JsonProperty("social_links") Map<String, Object> socialLinks, @JsonProperty("template") String template,
        @JsonProperty("template_config") Map<String, Object> templateConfig,
        @JsonProperty("is_published") boolean isPublished, @JsonProperty("view_count") long viewCount,
        @JsonProperty("created_at") Instant createdAt, @JsonProperty("updated_at") Instant updatedAt) {
}
