package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Payment refund entity for refund tracking and chargeback management per Policy P3.
 *
 * <p>
 * Tracks all refund requests through their lifecycle:
 * <ul>
 * <li><b>Automatic refunds:</b> Technical failures and moderation rejections within 24h (status: processed)</li>
 * <li><b>Manual refunds:</b> User requests after 24h (status: pending → approved/rejected → processed)</li>
 * <li><b>Chargebacks:</b> Customer dispute tracking (reason: chargeback, triggers user ban at 2+)</li>
 * </ul>
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code stripe_payment_intent_id} (TEXT) - Stripe Payment Intent ID</li>
 * <li>{@code stripe_refund_id} (TEXT) - Stripe Refund ID (NULL until processed)</li>
 * <li>{@code listing_id} (UUID, FK) - Associated listing (SET NULL on delete)</li>
 * <li>{@code user_id} (UUID, FK) - User who received refund (CASCADE delete)</li>
 * <li>{@code amount_cents} (BIGINT) - Refund amount in cents</li>
 * <li>{@code reason} (TEXT) - Refund reason: technical_failure, moderation_rejection, user_request, chargeback</li>
 * <li>{@code status} (TEXT) - Workflow status: pending, approved, rejected, processed</li>
 * <li>{@code reviewed_by_user_id} (UUID, FK) - Admin who reviewed (NULL for automatic)</li>
 * <li>{@code reviewed_at} (TIMESTAMPTZ) - Admin review timestamp</li>
 * <li>{@code notes} (TEXT) - Admin notes or failure reasons</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * <li>{@code updated_at} (TIMESTAMPTZ) - Last modification timestamp</li>
 * </ul>
 *
 * <p>
 * <b>Refund Workflow:</b>
 * <ol>
 * <li>Automatic (within 24h): created with status='processed', stripe_refund_id set immediately</li>
 * <li>Manual: created with status='pending', admin reviews (approve/reject), then processed if approved</li>
 * <li>Chargeback: created with status='processed', reason='chargeback', triggers user ban check</li>
 * </ol>
 *
 * <p>
 * <b>Database Access Pattern:</b> All queries via static methods (Panache ActiveRecord). No separate repository class.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P3: Marketplace payment & fraud policy (24h refund window, chargeback handling, user bans)</li>
 * <li>F12.8: Listing fees & monetization (refund eligibility)</li>
 * </ul>
 *
 * @see MarketplaceListing for listing entity
 */
@Entity
@Table(
        name = "payment_refunds")
@NamedQuery(
        name = PaymentRefund.QUERY_FIND_PENDING,
        query = PaymentRefund.JPQL_FIND_PENDING)
@NamedQuery(
        name = PaymentRefund.QUERY_FIND_BY_USER_ID,
        query = PaymentRefund.JPQL_FIND_BY_USER_ID)
@NamedQuery(
        name = PaymentRefund.QUERY_FIND_BY_LISTING_ID,
        query = PaymentRefund.JPQL_FIND_BY_LISTING_ID)
@NamedQuery(
        name = PaymentRefund.QUERY_FIND_BY_PAYMENT_INTENT,
        query = PaymentRefund.JPQL_FIND_BY_PAYMENT_INTENT)
@NamedQuery(
        name = PaymentRefund.QUERY_COUNT_CHARGEBACKS,
        query = PaymentRefund.JPQL_COUNT_CHARGEBACKS)
public class PaymentRefund extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(PaymentRefund.class);

    public static final String JPQL_FIND_PENDING = "FROM PaymentRefund WHERE status = 'pending' ORDER BY createdAt ASC";
    public static final String QUERY_FIND_PENDING = "PaymentRefund.findPending";

    public static final String JPQL_FIND_BY_USER_ID = "FROM PaymentRefund WHERE userId = ?1 ORDER BY createdAt DESC";
    public static final String QUERY_FIND_BY_USER_ID = "PaymentRefund.findByUserId";

    public static final String JPQL_FIND_BY_LISTING_ID = "FROM PaymentRefund WHERE listingId = ?1 ORDER BY createdAt DESC";
    public static final String QUERY_FIND_BY_LISTING_ID = "PaymentRefund.findByListingId";

    public static final String JPQL_FIND_BY_PAYMENT_INTENT = "FROM PaymentRefund WHERE stripePaymentIntentId = ?1";
    public static final String QUERY_FIND_BY_PAYMENT_INTENT = "PaymentRefund.findByPaymentIntent";

    public static final String JPQL_COUNT_CHARGEBACKS = "SELECT COUNT(p) FROM PaymentRefund p WHERE p.userId = ?1 AND p.reason = 'chargeback'";
    public static final String QUERY_COUNT_CHARGEBACKS = "PaymentRefund.countChargebacks";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "stripe_payment_intent_id",
            nullable = false)
    public String stripePaymentIntentId;

    @Column(
            name = "stripe_refund_id")
    public String stripeRefundId;

    @Column(
            name = "listing_id")
    public UUID listingId;

    @Column(
            name = "user_id",
            nullable = false)
    public UUID userId;

    @Column(
            name = "amount_cents",
            nullable = false)
    public long amountCents;

    @Column(
            nullable = false)
    public String reason;

    @Column(
            nullable = false)
    public String status;

    @Column(
            name = "reviewed_by_user_id")
    public UUID reviewedByUserId;

    @Column(
            name = "reviewed_at")
    public Instant reviewedAt;

    @Column
    public String notes;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Finds all pending refunds (awaiting admin review), ordered by creation date.
     *
     * @return List of pending refunds
     */
    public static List<PaymentRefund> findPending() {
        return find(JPQL_FIND_PENDING).list();
    }

    /**
     * Finds all refunds for a specific user, ordered by creation date descending.
     *
     * @param userId
     *            the user UUID
     * @return List of refunds for the user
     */
    public static List<PaymentRefund> findByUserId(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return find(JPQL_FIND_BY_USER_ID, userId).list();
    }

    /**
     * Finds all refunds for a specific listing.
     *
     * @param listingId
     *            the listing UUID
     * @return List of refunds for the listing
     */
    public static List<PaymentRefund> findByListingId(UUID listingId) {
        if (listingId == null) {
            return List.of();
        }
        return find(JPQL_FIND_BY_LISTING_ID, listingId).list();
    }

    /**
     * Finds a refund by Stripe Payment Intent ID.
     *
     * <p>
     * Used for webhook processing and idempotency checks.
     *
     * @param paymentIntentId
     *            Stripe Payment Intent ID
     * @return Optional containing the refund if found
     */
    public static Optional<PaymentRefund> findByPaymentIntent(String paymentIntentId) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            return Optional.empty();
        }
        return find(JPQL_FIND_BY_PAYMENT_INTENT, paymentIntentId).firstResultOptional();
    }

    /**
     * Counts chargebacks for a specific user.
     *
     * <p>
     * Per Policy P3, users with 2+ chargebacks are banned from the platform.
     *
     * @param userId
     *            the user UUID
     * @return count of chargebacks for the user
     */
    public static long countChargebacks(UUID userId) {
        if (userId == null) {
            return 0;
        }
        return find(JPQL_COUNT_CHARGEBACKS, userId).count();
    }

    /**
     * Creates a new payment refund with audit timestamps.
     *
     * @param refund
     *            the refund to persist
     * @return the persisted refund with generated ID
     */
    public static PaymentRefund create(PaymentRefund refund) {
        QuarkusTransaction.requiringNew().run(() -> {
            refund.createdAt = Instant.now();
            refund.updatedAt = Instant.now();
            refund.persist();

            LOG.infof("Created payment refund: id=%s, userId=%s, listingId=%s, reason=%s, status=%s, amount=%d cents",
                    refund.id, refund.userId, refund.listingId, refund.reason, refund.status, refund.amountCents);
        });
        return refund;
    }

    /**
     * Updates an existing payment refund with audit timestamp.
     *
     * @param refund
     *            the refund to update
     */
    public static void update(PaymentRefund refund) {
        QuarkusTransaction.requiringNew().run(() -> {
            PaymentRefund managed = findById(refund.id);
            if (managed == null) {
                throw new IllegalStateException("Refund not found: " + refund.id);
            }

            managed.stripeRefundId = refund.stripeRefundId;
            managed.status = refund.status;
            managed.reviewedByUserId = refund.reviewedByUserId;
            managed.reviewedAt = refund.reviewedAt;
            managed.notes = refund.notes;
            managed.updatedAt = Instant.now();

            managed.persist();
            LOG.infof("Updated payment refund: id=%s, status=%s, stripeRefundId=%s", managed.id, managed.status,
                    managed.stripeRefundId);
        });
    }

    /**
     * Approves a pending refund and transitions status to 'approved'.
     *
     * <p>
     * After approval, the refund must be processed via Stripe API to transition to 'processed'.
     *
     * @param refundId
     *            the refund UUID
     * @param adminUserId
     *            the admin user who approved the refund
     * @param notes
     *            optional admin notes
     */
    public static void approve(UUID refundId, UUID adminUserId, String notes) {
        QuarkusTransaction.requiringNew().run(() -> {
            PaymentRefund refund = findById(refundId);
            if (refund == null) {
                throw new IllegalStateException("Refund not found: " + refundId);
            }

            if (!"pending".equals(refund.status)) {
                throw new IllegalStateException("Refund is not pending: " + refundId);
            }

            refund.status = "approved";
            refund.reviewedByUserId = adminUserId;
            refund.reviewedAt = Instant.now();
            refund.notes = notes;
            refund.updatedAt = Instant.now();
            refund.persist();

            LOG.infof("Approved payment refund: id=%s, reviewedBy=%s", refund.id, adminUserId);
        });
    }

    /**
     * Rejects a pending refund and transitions status to 'rejected'.
     *
     * @param refundId
     *            the refund UUID
     * @param adminUserId
     *            the admin user who rejected the refund
     * @param notes
     *            admin notes explaining rejection reason
     */
    public static void reject(UUID refundId, UUID adminUserId, String notes) {
        QuarkusTransaction.requiringNew().run(() -> {
            PaymentRefund refund = findById(refundId);
            if (refund == null) {
                throw new IllegalStateException("Refund not found: " + refundId);
            }

            if (!"pending".equals(refund.status)) {
                throw new IllegalStateException("Refund is not pending: " + refundId);
            }

            refund.status = "rejected";
            refund.reviewedByUserId = adminUserId;
            refund.reviewedAt = Instant.now();
            refund.notes = notes;
            refund.updatedAt = Instant.now();
            refund.persist();

            LOG.infof("Rejected payment refund: id=%s, reviewedBy=%s, reason=%s", refund.id, adminUserId, notes);
        });
    }

    /**
     * Marks a refund as processed after successful Stripe API call.
     *
     * @param refundId
     *            the refund UUID
     * @param stripeRefundId
     *            the Stripe Refund ID
     */
    public static void markProcessed(UUID refundId, String stripeRefundId) {
        QuarkusTransaction.requiringNew().run(() -> {
            PaymentRefund refund = findById(refundId);
            if (refund == null) {
                throw new IllegalStateException("Refund not found: " + refundId);
            }

            refund.status = "processed";
            refund.stripeRefundId = stripeRefundId;
            refund.updatedAt = Instant.now();
            refund.persist();

            LOG.infof("Marked refund as processed: id=%s, stripeRefundId=%s", refund.id, stripeRefundId);
        });
    }
}
