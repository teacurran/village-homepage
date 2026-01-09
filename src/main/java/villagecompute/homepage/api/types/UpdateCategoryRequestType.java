package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * API type for updating a marketplace category (admin-only).
 *
 * <p>
 * Used in {@code PATCH /admin/api/marketplace/categories/{id}} endpoint. All fields are optional; only provided fields
 * are updated.
 *
 * @param name
 *            display name (1-100 characters)
 * @param slug
 *            URL-friendly identifier (lowercase, hyphens only)
 * @param sortOrder
 *            display order within same parent level
 * @param isActive
 *            active/disabled status
 * @param feeSchedule
 *            fee configuration
 */
public record UpdateCategoryRequestType(@Size(
        min = 1,
        max = 100) String name,
        @Size(
                min = 1,
                max = 100) @Pattern(
                        regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                        message = "Slug must be lowercase letters, numbers, and hyphens (e.g., 'for-sale')") String slug,
        @JsonProperty("sort_order") Integer sortOrder, @JsonProperty("is_active") Boolean isActive,
        @JsonProperty("fee_schedule") @Valid FeeScheduleType feeSchedule) {
}
