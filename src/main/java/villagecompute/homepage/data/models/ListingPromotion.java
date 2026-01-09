package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Listing promotion entity for marketplace monetization per Feature F12.8.
 *
 * <p>
 * Tracks paid promotional features for marketplace listings:
 * <ul>
 * <li><b>Featured:</b> $5 for 7 days, highlighted in search results, top of category</li>
 * <li><b>Bump:</b> $2 per bump, resets listing to top of chronological order, limited to 1/24h</li>
 * </ul>
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code listing_id} (UUID, FK) - Associated listing (CASCADE delete)</li>
 * <li>{@code type} (TEXT) - Promotion type: 'featured' or 'bump'</li>
 * <li>{@code stripe_payment_intent_id} (TEXT) - Stripe Payment Intent ID for payment tracking</li>
 * <li>{@code amount_cents} (BIGINT) - Payment amount in cents</li>
 * <li>{@code starts_at} (TIMESTAMPTZ) - Promotion activation timestamp</li>
 * <li>{@code expires_at} (TIMESTAMPTZ) - Promotion expiration (7 days for featured, NULL for bump)</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * <li>{@code updated_at} (TIMESTAMPTZ) - Last modification timestamp</li>
 * </ul>
 *
 * <p>
 * <b>Business Rules:</b>
 * <ul>
 * <li>Featured promotions: active for 7 days, expires_at = starts_at + 7 days</li>
 * <li>Bump promotions: instant effect, expires_at = NULL, limited to 1 per 24 hours per listing</li>
 * <li>Payment Intent ID must be unique (enforced by database index for idempotency)</li>
 * </ul>
 *
 * <p>
 * <b>Database Access Pattern:</b> All queries via static methods (Panache ActiveRecord). No separate repository class.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F12.8: Listing fees & monetization (promotion pricing and duration)</li>
 * <li>P3: Marketplace payment & fraud policy (Stripe integration)</li>
 * </ul>
 *
 * @see MarketplaceListing for listing entity
 */
@Entity
@Table(
        name = "listing_promotions")
public class ListingPromotion extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(ListingPromotion.class);

    public static final String QUERY_FIND_BY_LISTING_ID = "ListingPromotion.findByListingId";
    public static final String QUERY_FIND_ACTIVE_FEATURED = "ListingPromotion.findActiveFeatured";
    public static final String QUERY_FIND_EXPIRED_FEATURED = "ListingPromotion.findExpiredFeatured";
    public static final String QUERY_FIND_BY_PAYMENT_INTENT = "ListingPromotion.findByPaymentIntent";
    public static final String QUERY_CHECK_RECENT_BUMP = "ListingPromotion.checkRecentBump";

    /** Featured promotion duration: 7 days from activation. */
    public static final Duration FEATURED_DURATION = Duration.ofDays(7);

    /** Bump cooldown period: 24 hours between bumps. */
    public static final Duration BUMP_COOLDOWN = Duration.ofHours(24);

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "listing_id",
            nullable = false)
    public UUID listingId;

    @Column(
            nullable = false)
    public String type;

    @Column(
            name = "stripe_payment_intent_id",
            nullable = false,
            unique = true)
    public String stripePaymentIntentId;

    @Column(
            name = "amount_cents",
            nullable = false)
    public long amountCents;

    @Column(
            name = "starts_at",
            nullable = false)
    public Instant startsAt;

    @Column(
            name = "expires_at")
    public Instant expiresAt;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Finds all promotions for a specific listing, ordered by creation date descending.
     *
     * @param listingId
     *            the listing UUID
     * @return List of promotions for the listing
     */
    public static List<ListingPromotion> findByListingId(UUID listingId) {
        if (listingId == null) {
            return List.of();
        }
        return find("listingId = ?1 ORDER BY createdAt DESC", listingId).list();
    }

    /**
     * Finds active featured promotions for a listing.
     *
     * <p>
     * Active means: starts_at <= NOW() AND expires_at > NOW()
     *
     * @param listingId
     *            the listing UUID
     * @return List of active featured promotions
     */
    public static List<ListingPromotion> findActiveFeatured(UUID listingId) {
        if (listingId == null) {
            return List.of();
        }
        Instant now = Instant.now();
        return find("listingId = ?1 AND type = 'featured' AND startsAt <= ?2 AND expiresAt > ?2", listingId, now)
                .list();
    }

    /**
     * Finds all expired featured promotions (for cleanup job).
     *
     * <p>
     * Used by {@link villagecompute.homepage.jobs.PromotionExpirationJobHandler} to identify promotions past their
     * expiration timestamp.
     *
     * @return List of expired featured promotions
     */
    public static List<ListingPromotion> findExpiredFeatured() {
        Instant now = Instant.now();
        return find("type = 'featured' AND expiresAt IS NOT NULL AND expiresAt <= ?1", now).list();
    }

    /**
     * Finds a promotion by Stripe Payment Intent ID.
     *
     * <p>
     * Used for webhook processing to correlate Payment Intent with promotion.
     *
     * @param paymentIntentId
     *            Stripe Payment Intent ID
     * @return Optional containing the promotion if found
     */
    public static Optional<ListingPromotion> findByPaymentIntent(String paymentIntentId) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            return Optional.empty();
        }
        return find("stripePaymentIntentId = ?1", paymentIntentId).firstResultOptional();
    }

    /**
     * Checks if a listing was bumped within the last 24 hours.
     *
     * <p>
     * Per business rules, bumps are limited to once per 24 hours per listing.
     *
     * @param listingId
     *            the listing UUID
     * @return true if listing was bumped within last 24 hours
     */
    public static boolean hasRecentBump(UUID listingId) {
        if (listingId == null) {
            return false;
        }
        Instant cutoff = Instant.now().minus(BUMP_COOLDOWN);
        return count("listingId = ?1 AND type = 'bump' AND createdAt >= ?2", listingId, cutoff) > 0;
    }

    /**
     * Creates a new listing promotion with audit timestamps.
     *
     * <p>
     * For featured promotions, sets expires_at = starts_at + 7 days. For bump promotions, expires_at = NULL.
     *
     * @param promotion
     *            the promotion to persist
     * @return the persisted promotion with generated ID
     */
    public static ListingPromotion create(ListingPromotion promotion) {
        QuarkusTransaction.requiringNew().run(() -> {
            promotion.createdAt = Instant.now();
            promotion.updatedAt = Instant.now();

            // Set expiration for featured promotions
            if ("featured".equals(promotion.type)) {
                if (promotion.startsAt == null) {
                    promotion.startsAt = Instant.now();
                }
                promotion.expiresAt = promotion.startsAt.plus(FEATURED_DURATION);
            } else if ("bump".equals(promotion.type)) {
                if (promotion.startsAt == null) {
                    promotion.startsAt = Instant.now();
                }
                promotion.expiresAt = null;
            }

            promotion.persist();
            LOG.infof(
                    "Created listing promotion: id=%s, listingId=%s, type=%s, paymentIntent=%s, startsAt=%s, expiresAt=%s",
                    promotion.id, promotion.listingId, promotion.type, promotion.stripePaymentIntentId,
                    promotion.startsAt, promotion.expiresAt);
        });
        return promotion;
    }

    /**
     * Updates an existing listing promotion with audit timestamp.
     *
     * @param promotion
     *            the promotion to update
     */
    public static void update(ListingPromotion promotion) {
        QuarkusTransaction.requiringNew().run(() -> {
            ListingPromotion managed = findById(promotion.id);
            if (managed == null) {
                throw new IllegalStateException("Promotion not found: " + promotion.id);
            }

            managed.startsAt = promotion.startsAt;
            managed.expiresAt = promotion.expiresAt;
            managed.updatedAt = Instant.now();

            managed.persist();
            LOG.infof("Updated listing promotion: id=%s, startsAt=%s, expiresAt=%s", managed.id, managed.startsAt,
                    managed.expiresAt);
        });
    }

    /**
     * Deletes expired featured promotions (cleanup job).
     *
     * <p>
     * Called by {@link villagecompute.homepage.jobs.PromotionExpirationJobHandler} to remove expired promotion records.
     *
     * @param promotionId
     *            the promotion UUID to delete
     */
    public static void deleteExpired(UUID promotionId) {
        QuarkusTransaction.requiringNew().run(() -> {
            ListingPromotion promotion = findById(promotionId);
            if (promotion == null) {
                LOG.warnf("Attempted to delete non-existent promotion: %s", promotionId);
                return;
            }

            promotion.delete();
            LOG.infof("Deleted expired promotion: id=%s, listingId=%s, type=%s", promotion.id, promotion.listingId,
                    promotion.type);
        });
    }
}
