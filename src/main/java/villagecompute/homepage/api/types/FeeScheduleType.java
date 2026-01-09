package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * Fee schedule configuration for marketplace categories (Policy P3).
 *
 * <p>
 * Stored as JSONB in marketplace_categories.fee_schedule column. Defines monetization rules for:
 * <ul>
 * <li>posting_fee: Cost to create a basic listing in this category (typically $0 for most categories)</li>
 * <li>featured_fee: Cost to promote listing to featured/top placement</li>
 * <li>bump_fee: Cost to bump listing back to top of recent listings</li>
 * </ul>
 *
 * <p>
 * All fees in USD. Zero values indicate free operations. Stripe integration handles payment processing.
 *
 * @param postingFee
 *            cost to create basic listing (USD, default 0)
 * @param featuredFee
 *            cost to feature listing (USD, default 0)
 * @param bumpFee
 *            cost to bump listing (USD, default 0)
 */
public record FeeScheduleType(@JsonProperty("posting_fee") @NotNull @PositiveOrZero BigDecimal postingFee,
        @JsonProperty("featured_fee") @NotNull @PositiveOrZero BigDecimal featuredFee,
        @JsonProperty("bump_fee") @NotNull @PositiveOrZero BigDecimal bumpFee) {

    /**
     * Factory method for free category (all fees zero).
     *
     * @return FeeScheduleType with all fees set to 0
     */
    public static FeeScheduleType free() {
        return new FeeScheduleType(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Factory method for standard monetized category.
     *
     * <p>
     * Standard pricing: free posting, $5 featured, $2 bump.
     *
     * @return FeeScheduleType with standard pricing
     */
    public static FeeScheduleType standard() {
        return new FeeScheduleType(BigDecimal.ZERO, new BigDecimal("5.00"), new BigDecimal("2.00"));
    }

    /**
     * Factory method for premium category (housing, jobs).
     *
     * <p>
     * Premium pricing: free posting, $10 featured, $5 bump.
     *
     * @return FeeScheduleType with premium pricing
     */
    public static FeeScheduleType premium() {
        return new FeeScheduleType(BigDecimal.ZERO, new BigDecimal("10.00"), new BigDecimal("5.00"));
    }
}
