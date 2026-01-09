package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Response type for refund details (admin endpoints).
 *
 * <p>
 * Used by admin endpoints to display refund information including status, reason, and review details.
 *
 * <p>
 * <b>Status Values:</b>
 * <ul>
 * <li>pending: awaiting admin review (manual refunds only)</li>
 * <li>approved: admin approved, awaiting Stripe processing</li>
 * <li>rejected: admin rejected refund request</li>
 * <li>processed: refund completed via Stripe API</li>
 * </ul>
 *
 * <p>
 * <b>Reason Values:</b>
 * <ul>
 * <li>technical_failure: system error during listing creation</li>
 * <li>moderation_rejection: listing rejected by moderation within 24h</li>
 * <li>user_request: user requested refund (manual review required if >24h)</li>
 * <li>chargeback: customer disputed charge with credit card company</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P3: Marketplace payment & fraud policy (refund workflow)</li>
 * </ul>
 *
 * @param id
 *            refund UUID
 * @param stripePaymentIntentId
 *            Stripe Payment Intent ID
 * @param stripeRefundId
 *            Stripe Refund ID (null until processed)
 * @param listingId
 *            associated listing UUID (nullable if listing deleted)
 * @param userId
 *            user who received refund
 * @param amountCents
 *            refund amount in cents
 * @param reason
 *            refund reason code
 * @param status
 *            refund status
 * @param reviewedByUserId
 *            admin who reviewed (null for automatic refunds)
 * @param reviewedAt
 *            admin review timestamp (ISO 8601)
 * @param notes
 *            admin notes or failure reason
 * @param createdAt
 *            refund creation timestamp (ISO 8601)
 * @param updatedAt
 *            last modification timestamp (ISO 8601)
 */
public record RefundType(UUID id, @JsonProperty("stripe_payment_intent_id") String stripePaymentIntentId,
        @JsonProperty("stripe_refund_id") String stripeRefundId, @JsonProperty("listing_id") UUID listingId,
        @JsonProperty("user_id") UUID userId, @JsonProperty("amount_cents") long amountCents, String reason,
        String status, @JsonProperty("reviewed_by_user_id") UUID reviewedByUserId,
        @JsonProperty("reviewed_at") String reviewedAt, String notes, @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {
}
