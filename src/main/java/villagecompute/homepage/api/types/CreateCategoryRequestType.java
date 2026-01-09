package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * API type for creating a new marketplace category (admin-only).
 *
 * <p>
 * Used in {@code POST /admin/api/marketplace/categories} endpoint. Validates slug format and hierarchy constraints.
 *
 * @param parentId
 *            parent category UUID (null for root category)
 * @param name
 *            display name (1-100 characters)
 * @param slug
 *            URL-friendly identifier (lowercase, hyphens only, e.g., "for-sale")
 * @param sortOrder
 *            display order within same parent level (default 0)
 * @param isActive
 *            active/disabled status (default true)
 * @param feeSchedule
 *            fee configuration (default: all fees zero)
 */
public record CreateCategoryRequestType(@JsonProperty("parent_id") UUID parentId, @NotBlank @Size(
        min = 1,
        max = 100) String name,
        @NotBlank @Size(
                min = 1,
                max = 100) @Pattern(
                        regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                        message = "Slug must be lowercase letters, numbers, and hyphens (e.g., 'for-sale')") String slug,
        @JsonProperty("sort_order") Integer sortOrder, @JsonProperty("is_active") Boolean isActive,
        @JsonProperty("fee_schedule") @Valid @NotNull FeeScheduleType feeSchedule) {

    /**
     * Creates a CreateCategoryRequestType with default values.
     *
     * @param name
     *            display name
     * @param slug
     *            URL slug
     * @return CreateCategoryRequestType with defaults (root category, sortOrder=0, active=true, free fees)
     */
    public static CreateCategoryRequestType withDefaults(String name, String slug) {
        return new CreateCategoryRequestType(null, name, slug, 0, true, FeeScheduleType.free());
    }
}
