package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.search.engine.backend.types.Sortable;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.DocumentId;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.ContactInfoType;
import villagecompute.homepage.api.types.ListingCategorizationResultType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
@NamedQuery(
        name = MarketplaceListing.QUERY_FIND_BY_USER_ID,
        query = MarketplaceListing.JPQL_FIND_BY_USER_ID)
@NamedQuery(
        name = MarketplaceListing.QUERY_FIND_BY_CATEGORY_ID,
        query = MarketplaceListing.JPQL_FIND_BY_CATEGORY_ID)
@NamedQuery(
        name = MarketplaceListing.QUERY_FIND_BY_STATUS,
        query = MarketplaceListing.JPQL_FIND_BY_STATUS)
@NamedQuery(
        name = MarketplaceListing.QUERY_FIND_ACTIVE,
        query = MarketplaceListing.JPQL_FIND_ACTIVE)
@NamedQuery(
        name = MarketplaceListing.QUERY_FIND_EXPIRED,
        query = MarketplaceListing.JPQL_FIND_EXPIRED)
@NamedQuery(
        name = MarketplaceListing.QUERY_FIND_EXPIRING_WITHIN_DAYS,
        query = MarketplaceListing.JPQL_FIND_EXPIRING_WITHIN_DAYS)
@NamedQuery(
        name = MarketplaceListing.QUERY_FIND_WITHOUT_AI_SUGGESTIONS,
        query = MarketplaceListing.JPQL_FIND_WITHOUT_AI_SUGGESTIONS)
public class MarketplaceListing extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(MarketplaceListing.class);

    public static final String JPQL_FIND_BY_USER_ID = "FROM MarketplaceListing WHERE userId = ?1 ORDER BY createdAt DESC";
    public static final String QUERY_FIND_BY_USER_ID = "MarketplaceListing.findByUserId";

    public static final String JPQL_FIND_BY_CATEGORY_ID = "FROM MarketplaceListing WHERE categoryId = ?1 AND status = 'active' ORDER BY createdAt DESC";
    public static final String QUERY_FIND_BY_CATEGORY_ID = "MarketplaceListing.findByCategoryId";

    public static final String JPQL_FIND_BY_STATUS = "FROM MarketplaceListing WHERE status = ?1 ORDER BY createdAt DESC";
    public static final String QUERY_FIND_BY_STATUS = "MarketplaceListing.findByStatus";

    public static final String JPQL_FIND_ACTIVE = "FROM MarketplaceListing WHERE status = 'active' ORDER BY createdAt DESC";
    public static final String QUERY_FIND_ACTIVE = "MarketplaceListing.findActive";

    public static final String JPQL_FIND_EXPIRED = "FROM MarketplaceListing WHERE status = 'active' AND expiresAt <= ?1";
    public static final String QUERY_FIND_EXPIRED = "MarketplaceListing.findExpired";

    public static final String JPQL_FIND_EXPIRING_WITHIN_DAYS = "FROM MarketplaceListing WHERE status = 'active' AND expiresAt > ?1 AND expiresAt <= ?2 AND reminderSent = false";
    public static final String QUERY_FIND_EXPIRING_WITHIN_DAYS = "MarketplaceListing.findExpiringWithinDays";

    public static final String JPQL_FIND_WITHOUT_AI_SUGGESTIONS = "FROM MarketplaceListing WHERE status = 'active' AND aiCategorySuggestion IS NULL ORDER BY createdAt ASC";
    public static final String QUERY_FIND_WITHOUT_AI_SUGGESTIONS = "MarketplaceListing.findWithoutAiSuggestions";

    /**
     * JPQL constant for dynamic category filtering in search queries.
     *
     * <p>
     * Used when building programmatic search filters that include category. This constant supports dynamic query
     * construction with named parameters for flexible marketplace search functionality.
     *
     * <p>
     * Parameters:
     * <ul>
     * <li>{@code :categoryId} - The category UUID to filter by</li>
     * </ul>
     */
    public static final String JPQL_FIND_BY_CATEGORY = "FROM MarketplaceListing ml "
            + "WHERE ml.categoryId = :categoryId AND ml.status = 'active' " + "ORDER BY ml.createdAt DESC";

    /**
     * Native SQL constant for PostGIS spatial radius filtering.
     *
     * <p>
     * Finds active listings within specified radius of a geographic point. This query uses native PostGIS spatial
     * functions to perform accurate distance calculations on the Earth's surface.
     *
     * <p>
     * The query joins with the geo_cities table to access PostGIS location data, as MarketplaceListing references
     * cities via geoCityId foreign key rather than storing location directly. The radius is specified in meters per
     * PostGIS standard.
     *
     * <p>
     * Performance: Query uses GIST spatial index on geo_cities.location (created in migration
     * 20250110001800_create_geo_tables.sql) to achieve sub-100ms response times per Policy P11 for radius queries up to
     * 100 miles.
     *
     * <p>
     * This is a native SQL query constant (not JPQL) due to lack of JPA entity mapping for geo_cities reference table.
     * Use with EntityManager.createNativeQuery() for execution.
     *
     * <p>
     * Parameters:
     * <ul>
     * <li>{@code :longitude} - Center point longitude in decimal degrees (e.g., -122.3321 for Seattle)</li>
     * <li>{@code :latitude} - Center point latitude in decimal degrees (e.g., 47.6062 for Seattle)</li>
     * <li>{@code :radiusMeters} - Search radius in meters (e.g., 80467 meters = 50 miles)</li>
     * </ul>
     *
     * <p>
     * Example usage:
     *
     * <pre>
     * // Find listings within 50 miles of Seattle (80467 meters)
     * List&lt;MarketplaceListing&gt; results = getEntityManager()
     *         .createNativeQuery(JPQL_FIND_WITHIN_RADIUS, MarketplaceListing.class).setParameter("longitude", -122.3321)
     *         .setParameter("latitude", 47.6062).setParameter("radiusMeters", 80467.0).getResultList();
     * </pre>
     *
     * <p>
     * Note: The ST_DWithin function performs geography-based distance calculations (accurate for Earth's curvature),
     * while ST_MakePoint constructs a point geometry from longitude/latitude coordinates. The ::geography cast ensures
     * meter-based distance calculations.
     */
    public static final String JPQL_FIND_WITHIN_RADIUS = "SELECT ml.* FROM marketplace_listings ml "
            + "JOIN geo_cities gc ON ml.geo_city_id = gc.id " + "WHERE ml.status = 'active' "
            + "AND ST_DWithin(gc.location, ST_MakePoint(:longitude, :latitude)::geography, :radiusMeters) "
            + "ORDER BY ml.created_at DESC";

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
    public String status = "draft";

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
    public boolean reminderSent = false;

    @Column(
            name = "flag_count",
            nullable = false)
    public Long flagCount = 0L;

    @Column(
            name = "created_at",
            nullable = false)
    @GenericField(
            sortable = Sortable.YES)
    public Instant createdAt = Instant.now();

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt = Instant.now();

    /**
     * AI-generated category suggestion stored as JSONB.
     *
     * <p>
     * This field contains the AI's recommended category and subcategory for the listing, along with confidence score
     * and reasoning. The suggestion is NOT automatically applied to {@code categoryId} - it requires human review first
     * (either by admin or user).
     *
     * <p>
     * <b>Structure:</b> Contains {@link ListingCategorizationResultType} with:
     * <ul>
     * <li>{@code category} - Primary marketplace category (e.g., "For Sale", "Housing")
     * <li>{@code subcategory} - Specific subcategory (e.g., "Electronics", "Rent")
     * <li>{@code confidenceScore} - AI confidence 0.0-1.0 (scores < 0.7 flagged for review)
     * <li>{@code reasoning} - Brief explanation of categorization decision
     * </ul>
     *
     * <p>
     * <b>Workflow:</b>
     * <ul>
     * <li>New listings start with NULL ai_category_suggestion
     * <li>Hourly scheduled job finds listings WHERE ai_category_suggestion IS NULL
     * <li>AI categorization batch processes 50 listings per API call
     * <li>Results stored in this field for admin review
     * <li>Admin (or user) can accept suggestion → updates categoryId
     * </ul>
     *
     * <p>
     * <b>Index:</b> Partial index {@code idx_marketplace_listings_needs_categorization} on (ai_category_suggestion IS
     * NULL) for efficient job queries.
     *
     * @see ListingCategorizationResultType for JSONB structure
     * @see villagecompute.homepage.services.AiCategorizationService for categorization logic
     * @see villagecompute.homepage.jobs.AiCategorizationJobHandler for scheduled processing
     */
    @Column(
            name = "ai_category_suggestion",
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public ListingCategorizationResultType aiCategorySuggestion;

    /**
     * OpenAI embedding vector (1536 dimensions) for semantic search on listing descriptions.
     *
     * <p>
     * Stored as PostgreSQL vector type via pgvector extension. Used for cosine similarity semantic search to find
     * semantically related marketplace listings beyond keyword matching. Generated by
     * {@link villagecompute.homepage.services.SemanticSearchService} when listing is indexed.
     *
     * <p>
     * Nullable - embeddings may not be generated immediately upon listing creation. Embeddings generated subject to AI
     * budget constraints per Policy P2/P10.
     *
     * @see villagecompute.homepage.services.SemanticSearchService#indexListingEmbedding(MarketplaceListing)
     */
    @Column(
            name = "description_embedding",
            columnDefinition = "vector(1536)")
    @Type(villagecompute.homepage.util.PgVectorType.class)
    public float[] descriptionEmbedding;

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
        return find(JPQL_FIND_BY_USER_ID, userId).list();
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
        return find(JPQL_FIND_BY_CATEGORY_ID, categoryId).list();
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
        return find(JPQL_FIND_BY_STATUS, status).list();
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
        return find(JPQL_FIND_ACTIVE).list();
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
        return find(JPQL_FIND_EXPIRED, Instant.now()).list();
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
        return find(JPQL_FIND_EXPIRING_WITHIN_DAYS, now, futureThreshold).list();
    }

    /**
     * Finds active listings without AI category suggestions.
     *
     * <p>
     * Used by {@link villagecompute.homepage.jobs.AiCategorizationJobHandler} scheduled job to identify listings that
     * need AI categorization. Query uses partial index {@code idx_marketplace_listings_needs_categorization} for
     * optimal performance.
     *
     * <p>
     * Returns listings ordered by creation date (oldest first) to prioritize older uncategorized listings.
     *
     * @return List of active listings without AI suggestions, ordered by createdAt ASC
     */
    public static List<MarketplaceListing> findWithoutAiSuggestions() {
        return find("#" + QUERY_FIND_WITHOUT_AI_SUGGESTIONS).list();
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
     * <b>Image Cleanup:</b> Enqueues LISTING_IMAGE_CLEANUP job to delete associated images from R2 storage (Policy P1
     * GDPR compliance).
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
                villagecompute.homepage.services.DelayedJobService jobService = jakarta.enterprise.inject.spi.CDI
                        .current().select(villagecompute.homepage.services.DelayedJobService.class).get();
                jobService.enqueue(villagecompute.homepage.jobs.JobType.LISTING_IMAGE_CLEANUP,
                        java.util.Map.of("listingId", listingId.toString()));
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
     * <b>Image Cleanup:</b> Enqueues LISTING_IMAGE_CLEANUP job to delete associated images from R2 storage after
     * expiration (Policy P4 storage cost control).
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
                villagecompute.homepage.services.DelayedJobService jobService = jakarta.enterprise.inject.spi.CDI
                        .current().select(villagecompute.homepage.services.DelayedJobService.class).get();
                jobService.enqueue(villagecompute.homepage.jobs.JobType.LISTING_IMAGE_CLEANUP,
                        java.util.Map.of("listingId", listingId.toString()));
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
     * Finds a listing by its masked email address.
     *
     * <p>
     * Used by InboundEmailProcessor to lookup listings when processing incoming email sent to masked relay addresses
     * (e.g., listing-{uuid}@villagecompute.com). Queries the JSONB contact_info field using PostgreSQL JSONB operators.
     *
     * <p>
     * <b>Query:</b> {@code SELECT * FROM marketplace_listings WHERE contact_info->>'masked_email' = ?}
     *
     * @param maskedEmail
     *            the masked email address (e.g., "listing-a3f8b9c0-1234-5678-abcd-1234567890ab@villagecompute.com")
     * @return Optional containing listing if found, empty otherwise
     */
    public static Optional<MarketplaceListing> findByMaskedEmail(String maskedEmail) {
        if (maskedEmail == null || maskedEmail.isBlank()) {
            return Optional.empty();
        }
        return find("jsonb_extract_path_text(contact_info, 'masked_email') = ?1", maskedEmail).firstResultOptional();
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

    /**
     * Increments the flag count for this listing.
     *
     * <p>
     * Called when a new flag is submitted. If flag_count reaches 3, the database trigger automatically transitions
     * status to 'flagged'. This method only increments the counter.
     *
     * <p>
     * Note: The auto-hide behavior at 3 flags is handled by the database trigger {@code check_flag_threshold} defined
     * in migration 20250110002500_create_listing_flags.sql.
     */
    public void incrementFlagCount() {
        this.flagCount = this.flagCount != null ? this.flagCount + 1 : 1;
        this.updatedAt = Instant.now();
        this.persist();
    }

    /**
     * Decrements the flag count for this listing.
     *
     * <p>
     * Called when a flag is dismissed by admin. Flag count cannot go below zero.
     */
    public void decrementFlagCount() {
        if (this.flagCount != null && this.flagCount > 0) {
            this.flagCount = this.flagCount - 1;
            this.updatedAt = Instant.now();
            this.persist();
        }
    }

    /**
     * Finds all flagged listings across all categories.
     *
     * <p>
     * Returns listings with status='flagged' for admin moderation queue. Ordered by most recently flagged first.
     *
     * @return list of flagged listings
     */
    public static java.util.List<MarketplaceListing> findFlagged() {
        return list("status = 'flagged' ORDER BY updatedAt DESC");
    }
}
