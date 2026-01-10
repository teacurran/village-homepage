package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * API type for updating a directory category (admin-only).
 *
 * <p>
 * Used in {@code PATCH /admin/api/directory/categories/{id}} endpoint for Good Sites web directory. All fields are
 * optional; only provided fields are updated.
 *
 * @param name
 *            display name (1-100 characters)
 * @param slug
 *            URL-friendly identifier (lowercase, hyphens only)
 * @param description
 *            category description for directory pages
 * @param iconUrl
 *            URL to 32px category icon
 * @param sortOrder
 *            display order within same parent level
 * @param isActive
 *            active/disabled status
 */
public record UpdateDirectoryCategoryRequestType(@Size(
        min = 1,
        max = 100) String name,
        @Size(
                min = 1,
                max = 100) @Pattern(
                        regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                        message = "Slug must be lowercase letters, numbers, and hyphens (e.g., 'computers-opensource')") String slug,
        @Size(
                max = 500) String description,
        @JsonProperty("icon_url") String iconUrl, @JsonProperty("sort_order") Integer sortOrder,
        @JsonProperty("is_active") Boolean isActive) {
}
