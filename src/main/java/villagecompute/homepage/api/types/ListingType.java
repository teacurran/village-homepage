package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
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
public record ListingType(@NotNull UUID id, @JsonProperty("user_id") @NotNull UUID userId,
        @JsonProperty("category_id") @NotNull UUID categoryId, @JsonProperty("geo_city_id") Long geoCityId,
        @NotNull String title, @NotNull String description, BigDecimal price,
        @JsonProperty("contact_info") @NotNull ContactInfoType contactInfo, @NotNull String status,
        @JsonProperty("expires_at") Instant expiresAt, @JsonProperty("last_bumped_at") Instant lastBumpedAt,
        @JsonProperty("reminder_sent") @NotNull Boolean reminderSent,
        @JsonProperty("created_at") @NotNull Instant createdAt,
        @JsonProperty("updated_at") @NotNull Instant updatedAt) {

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
