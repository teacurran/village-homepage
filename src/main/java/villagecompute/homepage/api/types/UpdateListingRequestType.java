package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(
        description = "Request to update an existing marketplace listing (partial update)")
public record UpdateListingRequestType(@Schema(
        description = "Marketplace category UUID",
        example = "770e8400-e29b-41d4-a716-446655440002",
        nullable = true) @JsonProperty("category_id") UUID categoryId,

        @Schema(
                description = "City location ID from geo_cities table",
                example = "5128581",
                nullable = true) @JsonProperty("geo_city_id") Long geoCityId,

        @Schema(
                description = "Listing title (10-100 characters)",
                example = "2019 Honda Civic EX",
                nullable = true,
                maxLength = 100) @Size(
                        min = 10,
                        max = 100) String title,

        @Schema(
                description = "Listing description (50-8000 characters)",
                example = "Well-maintained sedan with low mileage",
                nullable = true,
                maxLength = 8000) @Size(
                        min = 50,
                        max = 8000) String description,

        @Schema(
                description = "Price in USD (must be >= 0)",
                example = "15999.99",
                nullable = true) @DecimalMin("0.00") BigDecimal price,

        @Schema(
                description = "Seller's real email address",
                example = "seller@example.com",
                nullable = true) @JsonProperty("contact_email") @Email String contactEmail,

        @Schema(
                description = "Optional seller phone number",
                example = "+1-802-555-1234",
                nullable = true) @JsonProperty("contact_phone") String contactPhone) {
}
