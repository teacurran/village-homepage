package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * API type for creating a new directory category (admin-only).
 *
 * <p>
 * Used in {@code POST /admin/api/directory/categories} endpoint for Good Sites web directory. Validates slug format and
 * hierarchy constraints.
 *
 * @param parentId
 *            parent category UUID (null for root category)
 * @param name
 *            display name (1-100 characters, e.g., "Computers & Internet")
 * @param slug
 *            URL-friendly identifier (lowercase, hyphens only, e.g., "computers-opensource")
 * @param description
 *            category description for directory pages (optional)
 * @param iconUrl
 *            URL to 32px category icon (optional, can be added later)
 * @param sortOrder
 *            display order within same parent level (default 0)
 * @param isActive
 *            active/disabled status (default true)
 */
public record CreateDirectoryCategoryRequestType(@JsonProperty("parent_id") UUID parentId, @NotBlank @Size(
        min = 1,
        max = 100) String name,
        @NotBlank @Size(
                min = 1,
                max = 100) @Pattern(
                        regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                        message = "Slug must be lowercase letters, numbers, and hyphens (e.g., 'computers-opensource')") String slug,
        @Size(
                max = 500) String description,
        @JsonProperty("icon_url") String iconUrl, @JsonProperty("sort_order") Integer sortOrder,
        @JsonProperty("is_active") Boolean isActive) {

    /**
     * Creates a CreateDirectoryCategoryRequestType with default values.
     *
     * @param name
     *            display name
     * @param slug
     *            URL slug
     * @return CreateDirectoryCategoryRequestType with defaults (root category, sortOrder=0, active=true, no description
     *         or icon)
     */
    public static CreateDirectoryCategoryRequestType withDefaults(String name, String slug) {
        return new CreateDirectoryCategoryRequestType(null, name, slug, null, null, 0, true);
    }
}
