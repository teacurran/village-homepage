package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.search.engine.backend.types.Sortable;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.DocumentId;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.ContactInfoType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Marketplace listing entity for Craigslist-style classifieds per Features F12.4-F12.7.
 *
 * <p>
 * Supports user-created classified ads with location-based filtering, category organization, and status lifecycle
 * management. Listings auto-expire after 30 days and support draft functionality for incomplete submissions.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code user_id} (UUID, FK) - Owner user reference (CASCADE delete on user deletion per P1)</li>
 * <li>{@code category_id} (UUID, FK) - Marketplace category reference (RESTRICT delete)</li>
 * <li>{@code geo_city_id} (BIGINT, FK) - City location for radius filtering (PostGIS, nullable)</li>
 * <li>{@code title} (TEXT) - Listing title (10-100 chars, required)</li>
 * <li>{@code description} (TEXT) - Listing description (50-8000 chars, required)</li>
 * <li>{@code price} (DECIMAL) - Price in USD (nullable for free categories, >= 0)</li>
 * <li>{@code contact_info} (JSONB) - Contact email/phone with masked relay email</li>
 * <li>{@code status} (TEXT) - Lifecycle state (draft, pending_payment, active, expired, removed, flagged)</li>
 * <li>{@code expires_at} (TIMESTAMPTZ) - Expiration timestamp (set when activated, 30 days from activation)</li>
 * <li>{@code last_bumped_at} (TIMESTAMPTZ) - Last promotion timestamp (future feature)</li>
 * <li>{@code reminder_sent} (BOOLEAN) - Whether expiration reminder email sent</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * <li>{@code updated_at} (TIMESTAMPTZ) - Last modification timestamp</li>
 * </ul>
 *
 * <p>
 * <b>Status Lifecycle:</b> Listings progress through these states:
 *
 * <pre>
 * draft → pending_payment → active → expired
 *        ↓                 ↓        ↓
 *       removed         removed   removed
 *                         ↓
 *                      flagged
 * </pre>
 *
 * <ul>
 * <li>{@code draft} - User composing listing, not yet submitted (no validation)</li>
 * <li>{@code pending_payment} - Submitted, awaiting payment for category posting fee (if required)</li>
 * <li>{@code active} - Live, searchable, visible to public (expires_at set to NOW() + 30 days)</li>
 * <li>{@code expired} - Reached 30-day expiration, no longer searchable (ListingExpirationJobHandler)</li>
 * <li>{@code removed} - Manually deleted by user or admin (soft-delete for audit trail)</li>
 * <li>{@code flagged} - Flagged for moderation (auto-hide at 3 flags, future I4.T6)</li>
 * </ul>
 *
 * <p>
 * <b>Expiration Logic:</b>
 * <ul>
 * <li>When listing activated (status → active), {@code expires_at} set to NOW() + 30 days</li>
 * <li>Daily job {@link villagecompute.homepage.jobs.ListingExpirationJobHandler} queries active listings with
 * expires_at <= NOW()</li>
 * <li>Expired listings marked status = 'expired', no longer visible in public searches</li>
 * <li>Reminder job {@link villagecompute.homepage.jobs.ListingReminderJobHandler} sends email 2-3 days before
 * expiration</li>
 * </ul>
 *
 * <p>
 * <b>Draft Functionality:</b> Users can save incomplete listings as drafts. Draft listings:
 * <ul>
 * <li>Skip field validation (title/description length not enforced)</li>
 * <li>Visible only to owner (not searchable or browsable)</li>
 * <li>Can be updated via PATCH endpoint</li>
 * <li>Can be published via POST /listings/{id}/publish (transitions to active or pending_payment)</li>
 * </ul>
 *
 * <p>
 * <b>Validation Rules:</b>
 * <ul>
 * <li>Title: 10-100 characters (enforced at API layer)</li>
 * <li>Description: 50-8000 characters (enforced at API layer)</li>
 * <li>Price: >= 0 (CHECK constraint in database)</li>
 * <li>Category: Must reference existing marketplace_categories.id (FK constraint)</li>
 * <li>Location: Must reference existing geo_cities.id (FK constraint)</li>
 * <li>Contact: Email required, phone optional (enforced at API layer)</li>
 * </ul>
 *
 * <p>
 * <b>Database Access Pattern:</b> All queries via static methods (Panache ActiveRecord). No separate repository class.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F12.4: Listing creation and draft functionality</li>
 * <li>F12.5: Image upload support (up to 12 images, future I4.T8)</li>
 * <li>F12.6: Contact masking via email relay</li>
 * <li>F12.7: 30-day expiration with reminder emails</li>
 * <li>P1: GDPR compliance (CASCADE delete on user, user owns listings)</li>
 * <li>P3: Stripe payment integration (pending_payment status)</li>
 * <li>P6: PostGIS location for radius filtering</li>
 * <li>P14: Soft-delete to 'removed' status before hard purge</li>
 * </ul>
 *
 * @see MarketplaceCategory for category entity
 * @see ContactInfoType for contact_info JSONB structure
 */
@Entity
@Table(
        name = "marketplace_listings")
@Indexed
public class MarketplaceListing extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(MarketplaceListing.class);

    public static final String QUERY_FIND_BY_USER_ID = "MarketplaceListing.findByUserId";
    public static final String QUERY_FIND_BY_CATEGORY_ID = "MarketplaceListing.findByCategoryId";
    public static final String QUERY_FIND_BY_STATUS = "MarketplaceListing.findByStatus";
    public static final String QUERY_FIND_ACTIVE = "MarketplaceListing.findActive";
    public static final String QUERY_FIND_EXPIRED = "MarketplaceListing.findExpired";
    public static final String QUERY_FIND_EXPIRING_WITHIN_DAYS = "MarketplaceListing.findExpiringWithinDays";

    /** Default expiration period: 30 days from activation. */
    public static final Duration DEFAULT_EXPIRATION_PERIOD = Duration.ofDays(30);

    @Id
    @GeneratedValue
    @DocumentId
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "user_id",
            nullable = false)
    public UUID userId;

    @Column(
            name = "category_id",
            nullable = false)
    @GenericField
    public UUID categoryId;

    @Column(
            name = "geo_city_id")
    public Long geoCityId;

    @Column(
            nullable = false)
    @FullTextField(
            analyzer = "english")
    @KeywordField(
            name = "title_keyword",
            normalizer = "lowercase",
            sortable = Sortable.YES)
    public String title;

    @Column(
            nullable = false)
    @FullTextField(
            analyzer = "english")
    public String description;

    @Column
    @GenericField(
            sortable = Sortable.YES)
    public BigDecimal price;

    @Column(
            name = "contact_info",
            nullable = false,
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public ContactInfoType contactInfo;

    @Column(
            nullable = false)
    public String status;

    @Column(
            name = "expires_at")
    public Instant expiresAt;

    @Column(
            name = "last_bumped_at")
    public Instant lastBumpedAt;

    @Column(
            name = "payment_intent_id")
    public String paymentIntentId;

    @Column(
            name = "reminder_sent",
            nullable = false)
    public boolean reminderSent;

    @Column(
            name = "created_at",
            nullable = false)
    @GenericField(
            sortable = Sortable.YES)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Finds all listings for a specific user, ordered by creation date descending.
     *
     * <p>
     * Returns listings in all statuses (draft, active, expired, removed). Used for "My Listings" page showing user's
     * own listings.
     *
     * @param userId
     *            the user UUID
     * @return List of user's listings ordered by creation date (newest first)
     */
    public static List<MarketplaceListing> findByUserId(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return find("userId = ?1 ORDER BY createdAt DESC", userId).list();
    }

    /**
     * Finds all active listings in a specific category, ordered by creation date descending.
     *
     * <p>
     * Returns only active listings visible to public. Used for category browsing pages.
     *
     * @param categoryId
     *            the category UUID
     * @return List of active listings in category ordered by creation date (newest first)
     */
    public static List<MarketplaceListing> findByCategoryId(UUID categoryId) {
        if (categoryId == null) {
            return List.of();
        }
        return find("categoryId = ?1 AND status = 'active' ORDER BY createdAt DESC", categoryId).list();
    }

    /**
     * Finds all listings with a specific status, ordered by creation date descending.
     *
     * @param status
     *            the status value (draft, pending_payment, active, expired, removed, flagged)
     * @return List of listings with given status
     */
    public static List<MarketplaceListing> findByStatus(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        return find("status = ?1 ORDER BY createdAt DESC", status).list();
    }

    /**
     * Finds all active listings (visible to public), ordered by creation date descending.
     *
     * <p>
     * Used for homepage "recent listings" display and search results.
     *
     * @return List of active listings ordered by creation date (newest first)
     */
    public static List<MarketplaceListing> findActive() {
        return find("status = 'active' ORDER BY createdAt DESC").list();
    }

    /**
     * Finds all active listings that have expired (expires_at <= NOW()).
     *
     * <p>
     * Used by {@link villagecompute.homepage.jobs.ListingExpirationJobHandler} to mark expired listings. Query uses
     * composite index (status, expires_at) for optimal performance.
     *
     * @return List of expired listings to be marked as 'expired' status
     */
    public static List<MarketplaceListing> findExpired() {
        return find("status = 'active' AND expiresAt <= ?1", Instant.now()).list();
    }

    /**
     * Finds active listings expiring within N days that haven't received reminder email yet.
     *
     * <p>
     * Used by {@link villagecompute.homepage.jobs.ListingReminderJobHandler} to send expiration reminders. Query uses
     * composite index (status, expires_at, reminder_sent) for optimal performance.
     *
     * @param days
     *            the number of days ahead to check (typically 3 for "expiring in 2-3 days")
     * @return List of listings expiring soon that need reminder emails
     */
    public static List<MarketplaceListing> findExpiringWithinDays(int days) {
        Instant now = Instant.now();
        Instant futureThreshold = now.plus(Duration.ofDays(days));
        return find("status = 'active' AND expiresAt > ?1 AND expiresAt <= ?2 AND reminderSent = false", now,
                futureThreshold).list();
    }

    /**
     * Creates a new marketplace listing with audit timestamps.
     *
     * <p>
     * Sets createdAt and updatedAt to current time. If status is 'active', also sets expires_at to NOW() + 30 days.
     *
     * @param listing
     *            the listing to persist
     * @return the persisted listing with generated ID
     */
    public static MarketplaceListing create(MarketplaceListing listing) {
        QuarkusTransaction.requiringNew().run(() -> {
            listing.createdAt = Instant.now();
            listing.updatedAt = Instant.now();

            // Set expiration timestamp if listing is activated
            if ("active".equals(listing.status) && listing.expiresAt == null) {
                listing.expiresAt = Instant.now().plus(DEFAULT_EXPIRATION_PERIOD);
            }

            listing.persist();
            LOG.infof("Created marketplace listing: id=%s, userId=%s, categoryId=%s, status=%s, expiresAt=%s",
                    listing.id, listing.userId, listing.categoryId, listing.status, listing.expiresAt);
        });
        return listing;
    }

    /**
     * Updates an existing marketplace listing with audit timestamp.
     *
     * <p>
     * Refetches entity to ensure it's managed in transaction context, updates fields, and sets updatedAt to current
     * time. If status transitions to 'active' and expires_at is not yet set, calculates 30-day expiration.
     *
     * @param listing
     *            the listing to update
     */
    public static void update(MarketplaceListing listing) {
        QuarkusTransaction.requiringNew().run(() -> {
            // Refetch the entity to ensure it's managed in this transaction
            MarketplaceListing managed = findById(listing.id);
            if (managed == null) {
                throw new IllegalStateException("Listing not found: " + listing.id);
            }

            // Update fields
            managed.categoryId = listing.categoryId;
            managed.geoCityId = listing.geoCityId;
            managed.title = listing.title;
            managed.description = listing.description;
            managed.price = listing.price;
            managed.contactInfo = listing.contactInfo;
            managed.status = listing.status;
            managed.expiresAt = listing.expiresAt;
            managed.lastBumpedAt = listing.lastBumpedAt;
            managed.reminderSent = listing.reminderSent;
            managed.updatedAt = Instant.now();

            // Set expiration timestamp if transitioning to active status
            if ("active".equals(managed.status) && managed.expiresAt == null) {
                managed.expiresAt = Instant.now().plus(DEFAULT_EXPIRATION_PERIOD);
            }

            // persist() will update the managed entity
            managed.persist();

            LOG.infof("Updated marketplace listing: id=%s, status=%s, expiresAt=%s", managed.id, managed.status,
                    managed.expiresAt);
        });
    }

    /**
     * Soft-deletes a marketplace listing by setting status to 'removed'.
     *
     * <p>
     * Preserves audit trail and prevents referential integrity issues with images/flags/analytics. Per Policy P14,
     * soft-deleted listings retained for 90 days before hard purge.
     *
     * <p>
     * <b>Image Cleanup:</b> Enqueues LISTING_IMAGE_CLEANUP job to delete associated images from R2 storage
     * (Policy P1 GDPR compliance).
     *
     * @param listingId
     *            the listing UUID to soft-delete
     */
    public static void softDelete(UUID listingId) {
        QuarkusTransaction.requiringNew().run(() -> {
            MarketplaceListing listing = findById(listingId);
            if (listing == null) {
                throw new IllegalStateException("Listing not found: " + listingId);
            }

            listing.status = "removed";
            listing.updatedAt = Instant.now();
            listing.persist();

            LOG.infof("Soft-deleted marketplace listing: id=%s, userId=%s", listing.id, listing.userId);

            // Enqueue image cleanup job (P1 GDPR compliance)
            try {
                villagecompute.homepage.services.DelayedJobService jobService =
                    jakarta.enterprise.inject.spi.CDI.current().select(villagecompute.homepage.services.DelayedJobService.class).get();
                jobService.enqueue(
                    villagecompute.homepage.jobs.JobType.LISTING_IMAGE_CLEANUP,
                    java.util.Map.of("listingId", listingId.toString())
                );
                LOG.infof("Enqueued image cleanup job for listing: id=%s", listingId);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to enqueue image cleanup job for listing %s (images may remain in R2)", listingId);
            }
        });
    }

    /**
     * Marks a listing as expired.
     *
     * <p>
     * Called by {@link villagecompute.homepage.jobs.ListingExpirationJobHandler} to transition active listings past
     * their expires_at timestamp to 'expired' status.
     *
     * <p>
     * <b>Image Cleanup:</b> Enqueues LISTING_IMAGE_CLEANUP job to delete associated images from R2 storage
     * after expiration (Policy P4 storage cost control).
     *
     * @param listingId
     *            the listing UUID to mark as expired
     */
    public static void markExpired(UUID listingId) {
        QuarkusTransaction.requiringNew().run(() -> {
            MarketplaceListing listing = findById(listingId);
            if (listing == null) {
                LOG.warnf("Attempted to expire non-existent listing: %s", listingId);
                return;
            }

            listing.status = "expired";
            listing.updatedAt = Instant.now();
            listing.persist();

            LOG.infof("Marked listing as expired: id=%s, userId=%s, categoryId=%s", listing.id, listing.userId,
                    listing.categoryId);

            // Enqueue image cleanup job (P4 storage cost control)
            try {
                villagecompute.homepage.services.DelayedJobService jobService =
                    jakarta.enterprise.inject.spi.CDI.current().select(villagecompute.homepage.services.DelayedJobService.class).get();
                jobService.enqueue(
                    villagecompute.homepage.jobs.JobType.LISTING_IMAGE_CLEANUP,
                    java.util.Map.of("listingId", listingId.toString())
                );
                LOG.infof("Enqueued image cleanup job for expired listing: id=%s", listingId);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to enqueue image cleanup job for listing %s (images may remain in R2)", listingId);
            }
        });
    }

    /**
     * Marks that a reminder email has been sent for this listing.
     *
     * <p>
     * Called by {@link villagecompute.homepage.jobs.ListingReminderJobHandler} after sending expiration reminder email
     * to prevent duplicate reminders.
     *
     * @param listingId
     *            the listing UUID
     */
    public static void markReminderSent(UUID listingId) {
        QuarkusTransaction.requiringNew().run(() -> {
            MarketplaceListing listing = findById(listingId);
            if (listing == null) {
                LOG.warnf("Attempted to mark reminder for non-existent listing: %s", listingId);
                return;
            }

            listing.reminderSent = true;
            listing.updatedAt = Instant.now();
            listing.persist();

            LOG.debugf("Marked reminder sent for listing: id=%s", listing.id);
        });
    }

    /**
     * Checks if a user owns a specific listing.
     *
     * <p>
     * Used by REST endpoints to enforce ownership checks for PATCH/DELETE operations.
     *
     * @param listingId
     *            the listing UUID
     * @param userId
     *            the user UUID
     * @return true if user owns the listing, false otherwise
     */
    public static boolean isOwnedByUser(UUID listingId, UUID userId) {
        if (listingId == null || userId == null) {
            return false;
        }
        return count("id = ?1 AND userId = ?2", listingId, userId) > 0;
    }
}
