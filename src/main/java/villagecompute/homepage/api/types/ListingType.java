package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import villagecompute.homepage.data.models.MarketplaceListing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API type representing a marketplace listing for JSON responses.
 *
 * <p>
 * Used in user-facing CRUD endpoints for listing display and management. Converts from {@link MarketplaceListing}
 * entity. Includes contact info with masked email for public display.
 *
 * @param id
 *            unique identifier
 * @param userId
 *            owner user UUID
 * @param categoryId
 *            marketplace category UUID
 * @param geoCityId
 *            city location ID (from geo_cities table)
 * @param title
 *            listing title
 * @param description
 *            listing description
 * @param price
 *            price in USD (nullable for free categories)
 * @param contactInfo
 *            contact information with masked email relay
 * @param status
 *            lifecycle status (draft, pending_payment, active, expired, removed, flagged)
 * @param expiresAt
 *            expiration timestamp (30 days from activation)
 * @param lastBumpedAt
 *            last promotion timestamp (future feature)
 * @param reminderSent
 *            whether expiration reminder email sent
 * @param createdAt
 *            creation timestamp
 * @param updatedAt
 *            last modification timestamp
 */
@Schema(
        description = "Marketplace listing with full details including contact information and status")
public record ListingType(@Schema(
        description = "Unique listing identifier",
        example = "550e8400-e29b-41d4-a716-446655440000",
        required = true) @NotNull UUID id,

        @Schema(
                description = "Owner user UUID",
                example = "660e8400-e29b-41d4-a716-446655440001",
                required = true) @JsonProperty("user_id") @NotNull UUID userId,

        @Schema(
                description = "Marketplace category UUID",
                example = "770e8400-e29b-41d4-a716-446655440002",
                required = true) @JsonProperty("category_id") @NotNull UUID categoryId,

        @Schema(
                description = "City location ID from geo_cities table",
                example = "5128581",
                nullable = true) @JsonProperty("geo_city_id") Long geoCityId,

        @Schema(
                description = "Listing title",
                example = "2019 Honda Civic EX",
                required = true,
                maxLength = 100) @NotNull String title,

        @Schema(
                description = "Listing description",
                example = "Well-maintained sedan with low mileage",
                required = true,
                maxLength = 8000) @NotNull String description,

        @Schema(
                description = "Price in USD",
                example = "15999.99",
                nullable = true) BigDecimal price,

        @Schema(
                description = "Contact information with masked email relay",
                required = true) @JsonProperty("contact_info") @NotNull ContactInfoType contactInfo,

        @Schema(
                description = "Lifecycle status",
                example = "active",
                enumeration = {
                        "draft", "pending_payment", "active", "expired", "removed", "flagged"},
                required = true) @NotNull String status,

        @Schema(
                description = "Expiration timestamp (30 days from activation)",
                example = "2026-02-23T10:00:00Z",
                nullable = true) @JsonProperty("expires_at") Instant expiresAt,

        @Schema(
                description = "Last promotion timestamp",
                example = "2026-01-24T10:00:00Z",
                nullable = true) @JsonProperty("last_bumped_at") Instant lastBumpedAt,

        @Schema(
                description = "Whether expiration reminder email sent",
                example = "false",
                required = true) @JsonProperty("reminder_sent") @NotNull Boolean reminderSent,

        @Schema(
                description = "Creation timestamp",
                example = "2026-01-24T10:00:00Z",
                required = true) @JsonProperty("created_at") @NotNull Instant createdAt,

        @Schema(
                description = "Last modification timestamp",
                example = "2026-01-24T12:30:00Z",
                required = true) @JsonProperty("updated_at") @NotNull Instant updatedAt){

    /**
     * Converts a MarketplaceListing entity to API type.
     *
     * @param listing
     *            the entity to convert
     * @return ListingType for JSON response
     */
    public static ListingType fromEntity(MarketplaceListing listing) {
        return new ListingType(listing.id, listing.userId, listing.categoryId, listing.geoCityId, listing.title,
                listing.description, listing.price, listing.contactInfo, listing.status, listing.expiresAt,
                listing.lastBumpedAt, listing.reminderSent, listing.createdAt, listing.updatedAt);
    }

    /**
     * Converts a list of MarketplaceListing entities to API types.
     *
     * @param listings
     *            the entities to convert
     * @return List of ListingType for JSON response
     */
    public static List<ListingType> fromEntities(List<MarketplaceListing> listings) {
        return listings.stream().map(ListingType::fromEntity).toList();
    }
}
