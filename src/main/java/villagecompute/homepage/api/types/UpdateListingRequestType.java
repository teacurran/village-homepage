package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request type for updating an existing marketplace listing (PATCH /api/marketplace/listings/{id}).
 *
 * <p>
 * All fields are optional to support partial updates. Only non-null fields are updated. Validation applies to provided
 * fields.
 *
 * <p>
 * <b>Update Restrictions:</b>
 * <ul>
 * <li>Only draft listings can be updated via PATCH</li>
 * <li>Active, expired, or removed listings cannot be modified (returns 409 Conflict)</li>
 * <li>Only the listing owner can update (ownership check via user_id)</li>
 * </ul>
 *
 * <p>
 * <b>Field Validation (when provided):</b>
 * <ul>
 * <li>Title: 10-100 characters</li>
 * <li>Description: 50-8000 characters</li>
 * <li>Price: >= 0</li>
 * <li>Category: Must reference existing marketplace_categories.id</li>
 * <li>Location: Must reference existing geo_cities.id</li>
 * <li>Contact Email: Valid email format</li>
 * </ul>
 *
 * @param categoryId
 *            marketplace category UUID (optional)
 * @param geoCityId
 *            city location ID (optional)
 * @param title
 *            listing title (optional, 10-100 chars if provided)
 * @param description
 *            listing description (optional, 50-8000 chars if provided)
 * @param price
 *            price in USD (optional, >= 0 if provided)
 * @param contactEmail
 *            seller's real email (optional, valid email if provided)
 * @param contactPhone
 *            optional seller phone number
 */
public record UpdateListingRequestType(@JsonProperty("category_id") UUID categoryId,
        @JsonProperty("geo_city_id") Long geoCityId, @Size(
                min = 10,
                max = 100) String title,
        @Size(
                min = 50,
                max = 8000) String description,
        @DecimalMin("0.00") BigDecimal price, @JsonProperty("contact_email") @Email String contactEmail,
        @JsonProperty("contact_phone") String contactPhone) {
}
