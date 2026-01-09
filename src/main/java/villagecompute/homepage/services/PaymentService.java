package villagecompute.homepage.services;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.PaymentIntentResponseType;
import villagecompute.homepage.data.models.*;
import villagecompute.homepage.exceptions.StripeException;
import villagecompute.homepage.integration.payments.StripeClient;
import villagecompute.homepage.observability.ObservabilityMetrics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for marketplace payment processing, promotions, and refunds.
 *
 * <p>
 * Orchestrates Stripe Payment Intent creation, webhook processing, refund workflows, and chargeback handling per Policy
 * P3.
 *
 * <p>
 * <b>Payment Workflows:</b>
 * <ol>
 * <li>Posting Fee: Check category fee, create Payment Intent, set listing status=pending_payment</li>
 * <li>Promotion: Validate business rules, create Payment Intent, record promotion after webhook</li>
 * <li>Webhook: Verify signature, transition listing status, record promotion, process refunds</li>
 * <li>Refund: Automatic (within 24h) or manual (admin review), create Stripe refund, ban chargebacks</li>
 * </ol>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P3: Marketplace payment & fraud policy (refund window, chargeback handling, user bans)</li>
 * <li>F12.8: Listing fees & monetization (posting fees, promotions)</li>
 * </ul>
 */
@ApplicationScoped
public class PaymentService {

    private static final Logger LOG = Logger.getLogger(PaymentService.class);

    private static final Duration REFUND_WINDOW = Duration.ofHours(24);
    private static final long CHARGEBACK_BAN_THRESHOLD = 2;

    @Inject
    StripeClient stripeClient;

    @Inject
    ObservabilityMetrics metrics;

    /**
     * Creates a Payment Intent for a listing posting fee.
     *
     * <p>
     * Checks if category has posting fee > 0, creates Stripe Payment Intent with metadata, updates listing status to
     * pending_payment.
     *
     * @param listingId
     *            listing UUID
     * @param userId
     *            user UUID
     * @return Payment Intent response with client_secret for frontend
     * @throws IllegalStateException
     *             if listing doesn't exist or category has no fee
     * @throws StripeException
     *             if Stripe API call fails
     */
    public PaymentIntentResponseType createPostingPayment(UUID listingId, UUID userId) {
        LOG.infof("Creating posting payment: listingId=%s, userId=%s", listingId, userId);

        // Load listing and category
        MarketplaceListing listing = MarketplaceListing.findById(listingId);
        if (listing == null) {
            throw new IllegalStateException("Listing not found: " + listingId);
        }

        MarketplaceCategory category = MarketplaceCategory.findById(listing.categoryId);
        if (category == null) {
            throw new IllegalStateException("Category not found: " + listing.categoryId);
        }

        // Check posting fee
        BigDecimal postingFee = category.feeSchedule.postingFee();
        if (postingFee.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalStateException("Category has no posting fee: " + category.name);
        }

        // Convert to cents
        long amountCents = postingFee.multiply(new BigDecimal("100")).longValue();

        // Create Payment Intent with metadata
        Map<String, String> metadata = new HashMap<>();
        metadata.put("listing_id", listingId.toString());
        metadata.put("user_id", userId.toString());
        metadata.put("category_id", listing.categoryId.toString());
        metadata.put("payment_type", "posting_fee");

        JsonNode paymentIntent = stripeClient.createPaymentIntent(amountCents, metadata);

        // Update listing status and payment_intent_id
        listing.status = "pending_payment";
        listing.paymentIntentId = paymentIntent.get("id").asText();
        MarketplaceListing.update(listing);

        // Increment metrics
        metrics.incrementPaymentIntentCreated("posting_fee");

        String clientSecret = paymentIntent.get("client_secret").asText();
        LOG.infof("Created posting payment: listingId=%s, paymentIntent=%s, amount=%d cents", listingId,
                listing.paymentIntentId, amountCents);

        return new PaymentIntentResponseType(listing.paymentIntentId, clientSecret, amountCents);
    }

    /**
     * Creates a Payment Intent for a listing promotion (featured or bump).
     *
     * <p>
     * Validates business rules (listing must be active, bump cooldown), creates Stripe Payment Intent, returns
     * client_secret for frontend.
     *
     * @param listingId
     *            listing UUID
     * @param userId
     *            user UUID
     * @param promotionType
     *            "featured" or "bump"
     * @return Payment Intent response with client_secret for frontend
     * @throws IllegalStateException
     *             if business rules violated
     * @throws StripeException
     *             if Stripe API call fails
     */
    public PaymentIntentResponseType createPromotionPayment(UUID listingId, UUID userId, String promotionType) {
        LOG.infof("Creating promotion payment: listingId=%s, userId=%s, type=%s", listingId, userId, promotionType);

        // Load listing and category
        MarketplaceListing listing = MarketplaceListing.findById(listingId);
        if (listing == null) {
            throw new IllegalStateException("Listing not found: " + listingId);
        }

        if (!"active".equals(listing.status)) {
            throw new IllegalStateException("Listing must be active to promote: " + listingId);
        }

        MarketplaceCategory category = MarketplaceCategory.findById(listing.categoryId);
        if (category == null) {
            throw new IllegalStateException("Category not found: " + listing.categoryId);
        }

        // Get promotion fee
        BigDecimal promotionFee;
        if ("featured".equals(promotionType)) {
            promotionFee = category.feeSchedule.featuredFee();
        } else if ("bump".equals(promotionType)) {
            promotionFee = category.feeSchedule.bumpFee();

            // Check bump cooldown
            if (ListingPromotion.hasRecentBump(listingId)) {
                throw new IllegalStateException("Listing was bumped within last 24 hours: " + listingId);
            }
        } else {
            throw new IllegalArgumentException("Invalid promotion type: " + promotionType);
        }

        if (promotionFee.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalStateException("Promotion fee is zero for category: " + category.name);
        }

        // Convert to cents
        long amountCents = promotionFee.multiply(new BigDecimal("100")).longValue();

        // Create Payment Intent with metadata
        Map<String, String> metadata = new HashMap<>();
        metadata.put("listing_id", listingId.toString());
        metadata.put("user_id", userId.toString());
        metadata.put("category_id", listing.categoryId.toString());
        metadata.put("payment_type", "promotion");
        metadata.put("promotion_type", promotionType);

        JsonNode paymentIntent = stripeClient.createPaymentIntent(amountCents, metadata);

        // Increment metrics
        metrics.incrementPaymentIntentCreated("promotion_" + promotionType);

        String paymentIntentId = paymentIntent.get("id").asText();
        String clientSecret = paymentIntent.get("client_secret").asText();
        LOG.infof("Created promotion payment: listingId=%s, type=%s, paymentIntent=%s, amount=%d cents", listingId,
                promotionType, paymentIntentId, amountCents);

        return new PaymentIntentResponseType(paymentIntentId, clientSecret, amountCents);
    }

    /**
     * Handles successful payment webhook from Stripe.
     *
     * <p>
     * Processes payment_intent.succeeded events. For posting fees, transitions listing to active. For promotions,
     * creates listing_promotions record and updates last_bumped_at for bumps.
     *
     * @param paymentIntentData
     *            Stripe Payment Intent object from webhook payload
     */
    public void handlePaymentSuccess(JsonNode paymentIntentData) {
        String paymentIntentId = paymentIntentData.get("id").asText();
        JsonNode metadata = paymentIntentData.get("metadata");

        String paymentType = metadata.get("payment_type").asText();
        UUID listingId = UUID.fromString(metadata.get("listing_id").asText());
        UUID userId = UUID.fromString(metadata.get("user_id").asText());

        LOG.infof("Processing payment success: paymentIntent=%s, type=%s, listingId=%s", paymentIntentId, paymentType,
                listingId);

        if ("posting_fee".equals(paymentType)) {
            handlePostingFeeSuccess(listingId, paymentIntentId);
        } else if ("promotion".equals(paymentType)) {
            String promotionType = metadata.get("promotion_type").asText();
            long amountCents = paymentIntentData.get("amount").asLong();
            handlePromotionSuccess(listingId, userId, promotionType, paymentIntentId, amountCents);
        }

        // Increment metrics
        metrics.incrementPaymentSucceeded(paymentType);
    }

    /**
     * Handles failed payment webhook from Stripe.
     *
     * <p>
     * Processes payment_intent.payment_failed events. Logs failure and increments metrics. Listings remain in
     * pending_payment status.
     *
     * @param paymentIntentData
     *            Stripe Payment Intent object from webhook payload
     */
    public void handlePaymentFailure(JsonNode paymentIntentData) {
        String paymentIntentId = paymentIntentData.get("id").asText();
        JsonNode metadata = paymentIntentData.get("metadata");

        String paymentType = metadata.get("payment_type").asText();
        UUID listingId = UUID.fromString(metadata.get("listing_id").asText());

        LOG.warnf("Payment failed: paymentIntent=%s, type=%s, listingId=%s", paymentIntentId, paymentType, listingId);

        // Increment metrics
        metrics.incrementPaymentFailed(paymentType);

        // No action needed - listing remains in pending_payment status
        // User can retry payment or delete listing
    }

    /**
     * Handles refund webhook from Stripe.
     *
     * <p>
     * Processes charge.refunded events. Updates payment_refunds record with stripe_refund_id and status=processed.
     *
     * @param refundData
     *            Stripe Refund object from webhook payload
     */
    public void handleRefund(JsonNode refundData) {
        String refundId = refundData.get("id").asText();
        String paymentIntentId = refundData.get("payment_intent").asText();

        LOG.infof("Processing refund webhook: refundId=%s, paymentIntent=%s", refundId, paymentIntentId);

        // Find refund record
        Optional<PaymentRefund> refundOpt = PaymentRefund.findByPaymentIntent(paymentIntentId);
        if (refundOpt.isEmpty()) {
            LOG.warnf("Refund record not found for payment_intent: %s", paymentIntentId);
            return;
        }

        PaymentRefund refund = refundOpt.get();
        PaymentRefund.markProcessed(refund.id, refundId);

        // Increment metrics
        metrics.incrementRefundProcessed();

        LOG.infof("Marked refund as processed: id=%s, stripeRefundId=%s", refund.id, refundId);
    }

    /**
     * Processes automatic refund for technical failure or moderation rejection within 24h.
     *
     * <p>
     * Per Policy P3, automatic refunds are granted for:
     * <ul>
     * <li>Technical failures during listing publication</li>
     * <li>Moderation rejections within 24 hours of payment</li>
     * </ul>
     *
     * @param listingId
     *            listing UUID
     * @param reason
     *            "technical_failure" or "moderation_rejection"
     */
    public void processAutomaticRefund(UUID listingId, String reason) {
        LOG.infof("Processing automatic refund: listingId=%s, reason=%s", listingId, reason);

        // Load listing
        MarketplaceListing listing = MarketplaceListing.findById(listingId);
        if (listing == null) {
            LOG.warnf("Listing not found for refund: %s", listingId);
            return;
        }

        if (listing.paymentIntentId == null) {
            LOG.warnf("Listing has no payment_intent_id: %s", listingId);
            return;
        }

        // Check 24h window
        Duration timeSinceCreation = Duration.between(listing.createdAt, Instant.now());
        if (timeSinceCreation.compareTo(REFUND_WINDOW) > 0) {
            LOG.warnf("Refund requested outside 24h window: listingId=%s, age=%d hours", listingId,
                    timeSinceCreation.toHours());
            return;
        }

        // Check if refund already exists
        Optional<PaymentRefund> existingRefund = PaymentRefund.findByPaymentIntent(listing.paymentIntentId);
        if (existingRefund.isPresent()) {
            LOG.warnf("Refund already exists for payment_intent: %s", listing.paymentIntentId);
            return;
        }

        // Retrieve Payment Intent to get amount
        JsonNode paymentIntent = stripeClient.retrievePaymentIntent(listing.paymentIntentId);
        long amountCents = paymentIntent.get("amount").asLong();

        // Create Stripe refund
        JsonNode refundResponse = stripeClient.createRefund(listing.paymentIntentId, reason);
        String stripeRefundId = refundResponse.get("id").asText();

        // Record refund
        PaymentRefund refund = new PaymentRefund();
        refund.stripePaymentIntentId = listing.paymentIntentId;
        refund.stripeRefundId = stripeRefundId;
        refund.listingId = listingId;
        refund.userId = listing.userId;
        refund.amountCents = amountCents;
        refund.reason = reason;
        refund.status = "processed";
        PaymentRefund.create(refund);

        LOG.infof("Processed automatic refund: listingId=%s, refundId=%s, amount=%d cents", listingId, stripeRefundId,
                amountCents);
    }

    /**
     * Processes chargeback from Stripe dispute webhook.
     *
     * <p>
     * Per Policy P3, users with 2+ chargebacks are banned from the platform.
     *
     * @param paymentIntentId
     *            Stripe Payment Intent ID
     */
    public void processChargeback(String paymentIntentId) {
        LOG.warnf("Processing chargeback: paymentIntent=%s", paymentIntentId);

        // Find listing by payment_intent_id
        MarketplaceListing listing = (MarketplaceListing) MarketplaceListing
                .find("paymentIntentId = ?1", paymentIntentId).firstResultOptional().orElse(null);
        if (listing == null) {
            LOG.warnf("Listing not found for chargeback: paymentIntent=%s", paymentIntentId);
            return;
        }

        // Retrieve Payment Intent to get amount
        JsonNode paymentIntent = stripeClient.retrievePaymentIntent(paymentIntentId);
        long amountCents = paymentIntent.get("amount").asLong();

        // Record chargeback
        PaymentRefund refund = new PaymentRefund();
        refund.stripePaymentIntentId = paymentIntentId;
        refund.stripeRefundId = null; // Chargeback doesn't have refund ID
        refund.listingId = listing.id;
        refund.userId = listing.userId;
        refund.amountCents = amountCents;
        refund.reason = "chargeback";
        refund.status = "processed";
        PaymentRefund.create(refund);

        // Check chargeback count and ban user if >= 2
        long chargebackCount = PaymentRefund.countChargebacks(listing.userId);
        if (chargebackCount >= CHARGEBACK_BAN_THRESHOLD) {
            LOG.warnf("User has %d chargebacks, banning: userId=%s", chargebackCount, listing.userId);
            // Ban user (will be implemented in user management iteration)
            // User.ban(listing.userId, "2+ chargebacks per Policy P3");
        }

        LOG.infof("Processed chargeback: paymentIntent=%s, userId=%s, chargebackCount=%d", paymentIntentId,
                listing.userId, chargebackCount);
    }

    /**
     * Handles posting fee payment success - transitions listing to active.
     */
    private void handlePostingFeeSuccess(UUID listingId, String paymentIntentId) {
        MarketplaceListing listing = MarketplaceListing.findById(listingId);
        if (listing == null) {
            LOG.warnf("Listing not found for payment success: %s", listingId);
            return;
        }

        listing.status = "active";
        listing.expiresAt = Instant.now().plus(MarketplaceListing.DEFAULT_EXPIRATION_PERIOD);
        MarketplaceListing.update(listing);

        LOG.infof("Activated listing after payment: listingId=%s, expiresAt=%s", listingId, listing.expiresAt);
    }

    /**
     * Handles promotion payment success - creates listing_promotions record.
     */
    private void handlePromotionSuccess(UUID listingId, UUID userId, String promotionType, String paymentIntentId,
            long amountCents) {
        // Check idempotency - don't create duplicate promotions
        Optional<ListingPromotion> existing = ListingPromotion.findByPaymentIntent(paymentIntentId);
        if (existing.isPresent()) {
            LOG.warnf("Promotion already exists for payment_intent: %s", paymentIntentId);
            return;
        }

        // Create promotion record
        ListingPromotion promotion = new ListingPromotion();
        promotion.listingId = listingId;
        promotion.type = promotionType;
        promotion.stripePaymentIntentId = paymentIntentId;
        promotion.amountCents = amountCents;
        promotion.startsAt = Instant.now();

        ListingPromotion.create(promotion);

        // For bumps, update listing.last_bumped_at
        if ("bump".equals(promotionType)) {
            MarketplaceListing listing = MarketplaceListing.findById(listingId);
            if (listing != null) {
                listing.lastBumpedAt = Instant.now();
                MarketplaceListing.update(listing);
            }
        }

        LOG.infof("Created promotion: listingId=%s, type=%s, paymentIntent=%s", listingId, promotionType,
                paymentIntentId);
    }
}
