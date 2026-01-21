package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Request type for updating profile fields.
 *
 * <p>
 * All fields are optional - only provided fields will be updated. Username cannot be changed after creation (reserved
 * namespace enforcement).
 * </p>
 *
 * <p>
 * Example request:
 *
 * <pre>{@code
 * {
 *   "display_name": "John Doe",
 *   "bio": "Software engineer and coffee enthusiast",
 *   "location_text": "Groton, Vermont",
 *   "website_url": "https://johndoe.com",
 *   "avatar_url": "https://example.com/avatar.jpg",
 *   "social_links": {
 *     "twitter": "johndoe",
 *     "github": "johndoe",
 *     "linkedin": "johndoe"
 *   }
 * }
 * }</pre>
 */
public record UpdateProfileRequestType(@JsonProperty("display_name") @Size(
        max = 100,
        message = "Display name must not exceed 100 characters") String displayName,

        @JsonProperty("bio") @Size(
                max = 500,
                message = "Bio must not exceed 500 characters") String bio,

        @JsonProperty("location_text") @Size(
                max = 100,
                message = "Location must not exceed 100 characters") String locationText,

        @JsonProperty("website_url") @Size(
                max = 200,
                message = "Website URL must not exceed 200 characters") String websiteUrl,

        @JsonProperty("avatar_url") @Size(
                max = 500,
                message = "Avatar URL must not exceed 500 characters") String avatarUrl,

        @JsonProperty("social_links") Map<String, Object> socialLinks) {
}
