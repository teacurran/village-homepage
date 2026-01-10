package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import villagecompute.homepage.data.models.ListingFlag;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response type for flag details in admin moderation queue.
 *
 * <p>
 * Includes all flag metadata plus associated listing details for context.
 */
public record FlagType(@JsonProperty("id") UUID id,

        @JsonProperty("listing_id") UUID listingId,

        @JsonProperty("listing_title") String listingTitle,

        @JsonProperty("listing_status") String listingStatus,

        @JsonProperty("user_id") UUID userId,

        @JsonProperty("user_email") String userEmail,

        @JsonProperty("reason") String reason,

        @JsonProperty("details") String details,

        @JsonProperty("status") String status,

        @JsonProperty("reviewed_by_user_id") UUID reviewedByUserId,

        @JsonProperty("reviewed_by_email") String reviewedByEmail,

        @JsonProperty("reviewed_at") Instant reviewedAt,

        @JsonProperty("review_notes") String reviewNotes,

        @JsonProperty("fraud_score") BigDecimal fraudScore,

        @JsonProperty("fraud_reasons") String fraudReasons,

        @JsonProperty("created_at") Instant createdAt,

        @JsonProperty("updated_at") Instant updatedAt) {
    /**
     * Converts a ListingFlag entity to API type.
     *
     * <p>
     * Note: This factory method only populates flag fields. Caller must populate listing and user details separately
     * via database joins or additional queries.
     *
     * @param flag
     *            the flag entity
     * @return flag API type
     */
    public static FlagType from(ListingFlag flag) {
        return new FlagType(flag.id, flag.listingId, null, // Must be populated by caller
                null, // Must be populated by caller
                flag.userId, null, // Must be populated by caller
                flag.reason, flag.details, flag.status, flag.reviewedByUserId, null, // Must be populated by caller
                flag.reviewedAt, flag.reviewNotes, flag.fraudScore, flag.fraudReasons, flag.createdAt, flag.updatedAt);
    }

    /**
     * Full constructor with all populated fields.
     */
    public static FlagType from(ListingFlag flag, String listingTitle, String listingStatus, String userEmail,
            String reviewedByEmail) {
        return new FlagType(flag.id, flag.listingId, listingTitle, listingStatus, flag.userId, userEmail, flag.reason,
                flag.details, flag.status, flag.reviewedByUserId, reviewedByEmail, flag.reviewedAt, flag.reviewNotes,
                flag.fraudScore, flag.fraudReasons, flag.createdAt, flag.updatedAt);
    }
}
