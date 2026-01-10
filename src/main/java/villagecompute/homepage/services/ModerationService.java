package villagecompute.homepage.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.FraudAnalysisResultType;
import villagecompute.homepage.data.models.*;
import villagecompute.homepage.exceptions.RateLimitException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service for marketplace listing moderation, flagging, and fraud detection.
 *
 * <p>
 * Orchestrates flag submission, admin review workflow, automated refunds, and user bans per Policy P3. Integrates with
 * {@link FraudDetectionService} for AI-powered fraud analysis.
 *
 * <p>
 * <b>Moderation Workflows:</b>
 * <ol>
 * <li>Flag Submission: Rate limit check (5/day), create flag, increment listing flag_count, auto-hide at 3</li>
 * <li>AI Analysis: Optional fraud detection if budget allows, stores fraud score + reasons</li>
 * <li>Admin Approval: Remove listing, issue refund if within 24h, check chargeback ban threshold</li>
 * <li>Admin Dismissal: Mark flag dismissed, decrement listing flag_count, restore status if no pending flags</li>
 * </ol>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P3: Marketplace payment & fraud policy (refund window, chargeback bans at 2+)</li>
 * <li>F12.9: Moderation & fraud detection (auto-hide threshold, AI heuristics)</li>
 * </ul>
 *
 * @see ListingFlag
 * @see FraudDetectionService
 * @see PaymentService
 */
@ApplicationScoped
public class ModerationService {

    private static final Logger LOG = Logger.getLogger(ModerationService.class);

    private static final long FLAG_RATE_LIMIT = 5; // Flags per day per user
    private static final long AUTO_HIDE_THRESHOLD = 3; // Auto-hide at 3 pending flags
    private static final Duration REFUND_WINDOW = Duration.ofHours(24);
    private static final long CHARGEBACK_BAN_THRESHOLD = 2;

    @Inject
    FraudDetectionService fraudDetectionService;

    @Inject
    PaymentService paymentService;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Submits a flag for a marketplace listing.
     *
     * <p>
     * Enforces rate limiting (5 flags/day per user), creates flag record, increments listing flag_count. If fraud
     * analysis is requested and budget allows, performs AI fraud detection and stores results.
     *
     * @param listingId
     *            the listing UUID to flag
     * @param userId
     *            the user UUID submitting the flag
     * @param reason
     *            flag reason (spam, prohibited_item, fraud, etc.)
     * @param details
     *            optional additional details
     * @param runFraudAnalysis
     *            whether to run AI fraud detection
     * @return created ListingFlag entity
     * @throws RateLimitException
     *             if user exceeded flag rate limit
     * @throws IllegalArgumentException
     *             if listing or user not found
     */
    @Transactional
    public ListingFlag submitFlag(UUID listingId, UUID userId, String reason, String details,
            boolean runFraudAnalysis) {
        LOG.infof("Submitting flag: listingId=%s, userId=%s, reason=%s, runFraudAnalysis=%s", listingId, userId, reason,
                runFraudAnalysis);

        // 1. Rate limit check
        long recentFlagsCount = ListingFlag.countRecentByUser(userId);
        if (recentFlagsCount >= FLAG_RATE_LIMIT) {
            throw new RateLimitException(
                    "Flag submission rate limit exceeded. Maximum " + FLAG_RATE_LIMIT + " flags per day.");
        }

        // 2. Validate listing exists
        MarketplaceListing listing = MarketplaceListing.findById(listingId);
        if (listing == null) {
            throw new IllegalArgumentException("Listing not found: " + listingId);
        }

        // 3. Validate user exists
        User user = User.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found: " + userId);
        }

        // 4. Check if user is banned (banned users cannot flag)
        if (user.isBanned) {
            throw new IllegalStateException("Banned users cannot submit flags");
        }

        // 5. Create flag
        ListingFlag flag = new ListingFlag();
        flag.listingId = listingId;
        flag.userId = userId;
        flag.reason = reason;
        flag.details = details;
        flag.status = "pending";
        flag.createdAt = Instant.now();
        flag.updatedAt = Instant.now();
        flag.persist();

        // 6. Increment listing flag_count
        listing.incrementFlagCount();

        // 7. Run fraud analysis if requested
        if (runFraudAnalysis) {
            try {
                FraudAnalysisResultType fraudResult = fraudDetectionService.analyzeListing(listingId);
                flag.updateFraudAnalysis(fraudResult.fraudScore(), serializeFraudReasons(fraudResult));
                LOG.infof("Fraud analysis complete for flag %s: suspicious=%s, confidence=%.2f", flag.id,
                        fraudResult.isSuspicious(), fraudResult.confidence());
            } catch (Exception e) {
                LOG.errorf(e, "Fraud analysis failed for flag %s, continuing without score", flag.id);
            }
        }

        LOG.infof("Flag submitted successfully: flagId=%s, listingId=%s, flagCount=%d", flag.id, listingId,
                listing.flagCount);

        return flag;
    }

    /**
     * Approves a flag and removes the listing.
     *
     * <p>
     * Marks flag as approved, sets listing status to 'removed', issues automatic refund if listing was paid within 24h
     * window, checks chargeback ban threshold (2+) for seller.
     *
     * @param flagId
     *            the flag UUID to approve
     * @param adminUserId
     *            the admin user performing the review
     * @param reviewNotes
     *            optional review notes
     * @throws IllegalArgumentException
     *             if flag not found
     * @throws IllegalStateException
     *             if flag already reviewed
     */
    @Transactional
    public void approveFlag(UUID flagId, UUID adminUserId, String reviewNotes) {
        LOG.infof("Approving flag: flagId=%s, adminUserId=%s", flagId, adminUserId);

        // 1. Load flag
        ListingFlag flag = ListingFlag.findById(flagId);
        if (flag == null) {
            throw new IllegalArgumentException("Flag not found: " + flagId);
        }

        if (!"pending".equals(flag.status)) {
            throw new IllegalStateException("Flag already reviewed: " + flagId);
        }

        // 2. Load listing
        MarketplaceListing listing = MarketplaceListing.findById(flag.listingId);
        if (listing == null) {
            LOG.warnf("Cannot approve flag %s - listing not found: %s", flagId, flag.listingId);
            return;
        }

        // 3. Approve flag
        flag.approve(adminUserId, reviewNotes);

        // 4. Remove listing
        listing.status = "removed";
        listing.persist();

        LOG.infof("Listing %s removed due to approved flag %s", listing.id, flagId);

        // 5. Check if refund eligible (within 24h of listing creation AND has payment)
        if (listing.paymentIntentId != null) {
            Duration timeSinceCreation = Duration.between(listing.createdAt, Instant.now());
            if (timeSinceCreation.compareTo(REFUND_WINDOW) < 0) {
                // Auto-refund within 24h window
                createModerationRefund(listing.id, adminUserId, reviewNotes);
                LOG.infof("Created automatic moderation refund for listing %s", listing.id);
            } else {
                LOG.infof("Listing %s removed but no refund (outside 24h window: %d hours)", listing.id,
                        timeSinceCreation.toHours());
            }
        }

        // 6. Check for chargeback ban (if listing owner has 2+ chargebacks, ban them)
        long chargebacks = PaymentRefund.countChargebacks(listing.userId);
        if (chargebacks >= CHARGEBACK_BAN_THRESHOLD) {
            User.banUser(listing.userId, String.format("Repeated chargebacks (%d)", chargebacks));
            LOG.warnf("Banned user %s for %d chargebacks", listing.userId, chargebacks);
        }
    }

    /**
     * Dismisses a flag as invalid.
     *
     * <p>
     * Marks flag as dismissed, decrements listing flag_count. If no pending flags remain, restores listing status from
     * 'flagged' to 'active'.
     *
     * @param flagId
     *            the flag UUID to dismiss
     * @param adminUserId
     *            the admin user performing the review
     * @param reviewNotes
     *            optional review notes
     * @throws IllegalArgumentException
     *             if flag not found
     * @throws IllegalStateException
     *             if flag already reviewed
     */
    @Transactional
    public void dismissFlag(UUID flagId, UUID adminUserId, String reviewNotes) {
        LOG.infof("Dismissing flag: flagId=%s, adminUserId=%s", flagId, adminUserId);

        // 1. Load flag
        ListingFlag flag = ListingFlag.findById(flagId);
        if (flag == null) {
            throw new IllegalArgumentException("Flag not found: " + flagId);
        }

        if (!"pending".equals(flag.status)) {
            throw new IllegalStateException("Flag already reviewed: " + flagId);
        }

        // 2. Load listing
        MarketplaceListing listing = MarketplaceListing.findById(flag.listingId);
        if (listing == null) {
            LOG.warnf("Cannot dismiss flag %s - listing not found: %s", flagId, flag.listingId);
            return;
        }

        // 3. Dismiss flag
        flag.dismiss(adminUserId, reviewNotes);

        // 4. Decrement listing flag_count
        listing.decrementFlagCount();

        // 5. Restore listing status if no pending flags remain
        long pendingFlags = ListingFlag.countPendingForListing(listing.id);
        if (pendingFlags == 0 && "flagged".equals(listing.status)) {
            listing.status = "active";
            listing.persist();
            LOG.infof("Restored listing %s to active status (all flags dismissed)", listing.id);
        }

        LOG.infof("Flag dismissed successfully: flagId=%s, listingId=%s, remainingFlags=%d", flagId, listing.id,
                pendingFlags);
    }

    /**
     * Creates a moderation refund for a removed listing.
     *
     * <p>
     * Issues automatic refund with reason='moderation_rejection' and status='processed'. Per P3, refunds are processed
     * immediately for moderation removals within 24h window.
     *
     * @param listingId
     *            the listing UUID
     * @param adminUserId
     *            the admin user who approved the removal
     * @param notes
     *            review notes from moderation
     */
    private void createModerationRefund(UUID listingId, UUID adminUserId, String notes) {
        MarketplaceListing listing = MarketplaceListing.findById(listingId);
        if (listing == null || listing.paymentIntentId == null) {
            LOG.warnf("Cannot create moderation refund - listing or payment not found: %s", listingId);
            return;
        }

        // Create refund record
        PaymentRefund refund = new PaymentRefund();
        refund.stripePaymentIntentId = listing.paymentIntentId;
        refund.listingId = listing.id;
        refund.userId = listing.userId;
        refund.amountCents = 0; // Amount will be fetched from Stripe
        refund.reason = "moderation_rejection";
        refund.status = "processed";
        refund.reviewedByUserId = adminUserId;
        refund.reviewedAt = Instant.now();
        refund.notes = notes != null ? notes : "Listing removed by moderation";
        refund.createdAt = Instant.now();
        refund.updatedAt = Instant.now();
        refund.persist();

        LOG.infof("Created moderation refund: refundId=%s, listingId=%s, paymentIntent=%s", refund.id, listingId,
                listing.paymentIntentId);

        // Note: Actual Stripe refund API call would be handled by PaymentService webhook or async job
        // This method just creates the database record per Policy P3
    }

    /**
     * Serializes fraud analysis result to JSONB format for storage.
     *
     * @param fraudResult
     *            fraud analysis result
     * @return JSON string for storage in listing_flags.fraud_reasons
     */
    private String serializeFraudReasons(FraudAnalysisResultType fraudResult) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("reasons", fraudResult.reasons());
            data.put("prompt_version", fraudResult.promptVersion());
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            LOG.errorf(e, "Failed to serialize fraud reasons");
            return "{}";
        }
    }
}
