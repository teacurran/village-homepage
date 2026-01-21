package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * Request type for updating article customization fields.
 *
 * <p>
 * All fields are optional - only provided fields will be updated. Pass null to clear a customization (revert to
 * original value).
 * </p>
 *
 * <p>
 * Example request:
 *
 * <pre>{@code
 * {
 *   "custom_headline": "My Custom Headline",
 *   "custom_blurb": "My custom description",
 *   "custom_image_url": "https://example.com/custom-image.jpg"
 * }
 * }</pre>
 */
public record UpdateArticleCustomizationType(@JsonProperty("custom_headline") @Size(
        max = 200,
        message = "Custom headline must not exceed 200 characters") String customHeadline,

        @JsonProperty("custom_blurb") @Size(
                max = 2000,
                message = "Custom blurb must not exceed 2000 characters") String customBlurb,

        @JsonProperty("custom_image_url") @Size(
                max = 500,
                message = "Custom image URL must not exceed 500 characters") String customImageUrl) {
}
