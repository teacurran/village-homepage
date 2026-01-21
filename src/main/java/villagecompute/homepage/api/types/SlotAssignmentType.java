package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Request type for assigning a curated article to a template slot.
 *
 * <p>
 * Used for PUT /api/profiles/{id}/articles/{articleId}/slot endpoint. Clients specify the target slot name, position,
 * and optional custom styling.
 * </p>
 *
 * <p>
 * Example request:
 *
 * <pre>{@code
 * {
 *   "template": "your_times",
 *   "slot": "headline",
 *   "position": 0,
 *   "custom_styles": {
 *     "font_size": "large",
 *     "text_align": "center"
 *   }
 * }
 * }</pre>
 *
 * <p>
 * Slot names vary by template:
 * <ul>
 * <li><b>public_homepage:</b> "grid" (flexible positioning)</li>
 * <li><b>your_times:</b> "headline" (1), "secondary" (3), "sidebar" (2)</li>
 * <li><b>your_report:</b> "top_stories", "business", "technology", etc. (section names)</li>
 * </ul>
 *
 * <p>
 * <b>Feature:</b> F11.4-F11.7 - Profile Curated Articles
 * </p>
 *
 * @param template
 *            template type (public_homepage, your_times, your_report)
 * @param slot
 *            slot name (template-specific)
 * @param position
 *            position within slot (0-indexed)
 * @param customStyles
 *            optional custom CSS/layout properties
 */
public record SlotAssignmentType(@NotNull @JsonProperty("template") String template,
        @NotNull @JsonProperty("slot") String slot, @NotNull @Min(0) @JsonProperty("position") Integer position,
        @JsonProperty("custom_styles") Map<String, Object> customStyles) {

    /**
     * Converts to Map for storage in ProfileCuratedArticle.slotAssignment JSONB field.
     *
     * @return map representation
     */
    public Map<String, Object> toMap() {
        return Map.of("template", template, "slot", slot, "position", position, "custom_styles",
                customStyles != null ? customStyles : Map.of());
    }
}
