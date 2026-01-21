package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request type for adding a curated article to a profile.
 *
 * <p>
 * Supports both feed-sourced articles (feed_item_id provided) and manual entries (feed_item_id null, all original_*
 * fields required).
 * </p>
 *
 * <p>
 * Example request (from feed):
 *
 * <pre>{@code
 * {
 *   "feed_item_id": "770e8400-e29b-41d4-a716-446655440000",
 *   "original_url": "https://example.com/article",
 *   "original_title": "Article Title",
 *   "original_description": "Article description",
 *   "original_image_url": "https://example.com/image.jpg"
 * }
 * }</pre>
 *
 * <p>
 * Example request (manual):
 *
 * <pre>{@code
 * {
 *   "feed_item_id": null,
 *   "original_url": "https://example.com/article",
 *   "original_title": "Article Title",
 *   "original_description": "Article description",
 *   "original_image_url": "https://example.com/image.jpg"
 * }
 * }</pre>
 */
public record AddArticleRequestType(@JsonProperty("feed_item_id") UUID feedItemId,

        @JsonProperty("original_url") @NotBlank(
                message = "Original URL is required") @Size(
                        max = 500,
                        message = "URL must not exceed 500 characters") String originalUrl,

        @JsonProperty("original_title") @NotBlank(
                message = "Original title is required") @Size(
                        max = 200,
                        message = "Title must not exceed 200 characters") String originalTitle,

        @JsonProperty("original_description") @Size(
                max = 2000,
                message = "Description must not exceed 2000 characters") String originalDescription,

        @JsonProperty("original_image_url") @Size(
                max = 500,
                message = "Image URL must not exceed 500 characters") String originalImageUrl) {
}
