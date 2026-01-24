package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request type for creating a new marketplace listing (POST /api/marketplace/listings).
 *
 * <p>
 * Enforces validation rules for required fields and constraints. Validation is skipped for draft listings (status =
 * "draft"), but enforced for active listings.
 *
 * <p>
 * <b>Validation Rules:</b>
 * <ul>
 * <li>Title: 10-100 characters, required</li>
 * <li>Description: 50-8000 characters, required</li>
 * <li>Price: >= 0, required for most categories (optional for Jobs/Community)</li>
 * <li>Category: Must reference existing marketplace_categories.id</li>
 * <li>Location: Must reference existing geo_cities.id</li>
 * <li>Contact Email: Valid email format, required</li>
 * <li>Contact Phone: Optional</li>
 * </ul>
 *
 * <p>
 * <b>Status Handling:</b>
 * <ul>
 * <li>If status is omitted or "active", listing is activated immediately (if category has $0 posting fee)</li>
 * <li>If status is "draft", listing saved as draft (validation skipped)</li>
 * <li>If category has posting fee > $0, status forced to "pending_payment" regardless of request</li>
 * </ul>
 *
 * @param categoryId
 *            marketplace category UUID
 * @param geoCityId
 *            city location ID (from geo_cities table)
 * @param title
 *            listing title (10-100 chars)
 * @param description
 *            listing description (50-8000 chars)
 * @param price
 *            price in USD (>= 0, nullable for free categories)
 * @param contactEmail
 *            seller's real email (never displayed publicly)
 * @param contactPhone
 *            optional seller phone number
 * @param status
 *            initial status (draft or active, defaults to active)
 */
@Schema(
        description = "Request to create a new marketplace listing")
public record CreateListingRequestType(@Schema(
        description = "Marketplace category UUID",
        example = "770e8400-e29b-41d4-a716-446655440002",
        required = true) @JsonProperty("category_id") @NotNull UUID categoryId,

        @Schema(
                description = "City location ID from geo_cities table",
                example = "5128581",
                required = true) @JsonProperty("geo_city_id") @NotNull Long geoCityId,

        @Schema(
                description = "Listing title (10-100 characters)",
                example = "2019 Honda Civic EX",
                required = true,
                maxLength = 100) @NotNull @Size(
                        min = 10,
                        max = 100) String title,

        @Schema(
                description = "Listing description (50-8000 characters)",
                example = "Well-maintained sedan with low mileage, excellent condition",
                required = true,
                maxLength = 8000) @NotNull @Size(
                        min = 50,
                        max = 8000) String description,

        @Schema(
                description = "Price in USD (must be >= 0)",
                example = "15999.99",
                nullable = true) @DecimalMin("0.00") BigDecimal price,

        @Schema(
                description = "Seller's real email address (never displayed publicly)",
                example = "seller@example.com",
                required = true) @JsonProperty("contact_email") @NotNull @Email String contactEmail,

        @Schema(
                description = "Optional seller phone number",
                example = "+1-802-555-1234",
                nullable = true) @JsonProperty("contact_phone") String contactPhone,

        @Schema(
                description = "Initial status (draft or active, defaults to active)",
                example = "active",
                enumeration = {
                        "draft", "active"},
                nullable = true) String status){

    /**
     * Returns the effective status for the listing.
     *
     * <p>
     * Defaults to "active" if status is null or blank. Valid values: "draft", "active".
     *
     * @return the status to use (default: "active")
     */
    public String getEffectiveStatus() {
        if (status == null || status.isBlank()) {
            return "active";
        }
        return status;
    }
}
