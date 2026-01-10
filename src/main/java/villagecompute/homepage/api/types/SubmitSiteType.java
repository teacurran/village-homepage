package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

/**
 * Request type for submitting a site to the Good Sites directory.
 *
 * <p>
 * Supports submission to multiple categories simultaneously (common for cross-cutting sites like news portals or
 * developer tools). Title and description are optional - if not provided, they will be fetched from OpenGraph metadata.
 * </p>
 *
 * <p>
 * Example request:
 *
 * <pre>{@code
 * {
 *   "url": "https://news.ycombinator.com",
 *   "category_ids": ["550e8400-e29b-41d4-a716-446655440000"],
 *   "title": "Hacker News",
 *   "description": "Social news website focusing on computer science",
 *   "custom_image_url": null
 * }
 * }</pre>
 */
public record SubmitSiteType(@JsonProperty("url") @NotBlank(
        message = "URL is required") @Pattern(
                regexp = "^https?://.+",
                message = "URL must start with http:// or https://") String url,

        @JsonProperty("category_ids") @NotEmpty(
                message = "At least one category is required") @Size(
                        min = 1,
                        max = 3,
                        message = "Must specify 1-3 categories") List<UUID> categoryIds,

        @JsonProperty("title") @Size(
                max = 200,
                message = "Title must not exceed 200 characters") String title,

        @JsonProperty("description") @Size(
                max = 2000,
                message = "Description must not exceed 2000 characters") String description,

        @JsonProperty("custom_image_url") @Pattern(
                regexp = "^https://.+",
                message = "Custom image URL must be HTTPS") String customImageUrl) {
    /**
     * Validates category count constraints.
     *
     * @throws IllegalArgumentException
     *             if validation fails
     */
    public void validate() {
        if (categoryIds == null || categoryIds.isEmpty()) {
            throw new IllegalArgumentException("At least one category is required");
        }
        if (categoryIds.size() > 3) {
            throw new IllegalArgumentException("Maximum 3 categories per submission");
        }
    }
}
