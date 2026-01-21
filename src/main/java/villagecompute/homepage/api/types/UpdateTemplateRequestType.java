package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * Request type for updating profile template and configuration.
 *
 * <p>
 * Template must be one of: public_homepage, your_times, your_report. Template configuration is a flexible JSONB field
 * for template-specific settings.
 * </p>
 *
 * <p>
 * Example request:
 *
 * <pre>{@code
 * {
 *   "template": "public_homepage",
 *   "template_config": {
 *     "schema_version": 1,
 *     "theme": {
 *       "primary_color": "#0066cc",
 *       "font_family": "system-ui"
 *     },
 *     "slots": {
 *       "hero_text": "Welcome to my homepage",
 *       "featured_article_count": 3,
 *       "show_social_links": true
 *     }
 *   }
 * }
 * }</pre>
 */
public record UpdateTemplateRequestType(@JsonProperty("template") @NotBlank(
        message = "Template is required") @Pattern(
                regexp = "^(public_homepage|your_times|your_report)$",
                message = "Template must be public_homepage, your_times, or your_report") String template,

        @JsonProperty("template_config") Map<String, Object> templateConfig) {
}
