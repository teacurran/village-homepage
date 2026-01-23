package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Listing flag entity for moderation queue and fraud detection.
 *
 * <p>
 * Tracks user-submitted flags for marketplace listings requiring review. Flags follow this lifecycle:
 * <ul>
 * <li><b>pending:</b> Awaiting admin review, contributes to auto-hide threshold (3 flags)</li>
 * <li><b>approved:</b> Admin confirmed violation, listing removed</li>
 * <li><b>dismissed:</b> Admin reviewed and found no violation</li>
 * </ul>
 *
 * <p>
 * Per Policy P3 and Feature F12.9, listings are automatically hidden when flag_count reaches 3. AI fraud detection
 * populates fraud_score and fraud_reasons when budget allows.
 *
 * @see MarketplaceListing#incrementFlagCount()
 * @see villagecompute.homepage.services.FraudDetectionService
 * @see villagecompute.homepage.services.ModerationService
 */
@Entity
@Table(
        name = "listing_flags")
@NamedQuery(
        name = ListingFlag.QUERY_FIND_BY_LISTING,
        query = ListingFlag.JPQL_FIND_BY_LISTING)
@NamedQuery(
        name = ListingFlag.QUERY_FIND_PENDING,
        query = ListingFlag.JPQL_FIND_PENDING)
@NamedQuery(
        name = ListingFlag.QUERY_FIND_BY_STATUS,
        query = ListingFlag.JPQL_FIND_BY_STATUS)
public class ListingFlag extends PanacheEntityBase {

    public static final String JPQL_FIND_BY_LISTING = "SELECT lf FROM ListingFlag lf WHERE lf.listingId = :listingId ORDER BY lf.createdAt DESC";
    public static final String QUERY_FIND_BY_LISTING = "ListingFlag.findByListing";

    public static final String JPQL_FIND_PENDING = "SELECT lf FROM ListingFlag lf WHERE lf.status = 'pending' ORDER BY lf.createdAt DESC";
    public static final String QUERY_FIND_PENDING = "ListingFlag.findPending";

    public static final String JPQL_FIND_BY_STATUS = "SELECT lf FROM ListingFlag lf WHERE lf.status = :status ORDER BY lf.createdAt DESC";
    public static final String QUERY_FIND_BY_STATUS = "ListingFlag.findByStatus";

    @Id
    @GeneratedValue(
            strategy = GenerationType.AUTO)
    public UUID id;

    @Column(
            name = "listing_id",
            nullable = false)
    public UUID listingId;

    @Column(
            name = "user_id",
            nullable = false)
    public UUID userId;

    @Column(
            nullable = false)
    public String reason;

    @Column
    public String details;

    @Column(
            nullable = false)
    public String status = "pending";

    @Column(
            name = "reviewed_by_user_id")
    public UUID reviewedByUserId;

    @Column(
            name = "reviewed_at")
    public Instant reviewedAt;

    @Column(
            name = "review_notes")
    public String reviewNotes;

    @Column(
            name = "fraud_score",
            precision = 3,
            scale = 2)
    public BigDecimal fraudScore;

    @Column(
            name = "fraud_reasons",
            columnDefinition = "jsonb")
    public String fraudReasons;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt = Instant.now();

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt = Instant.now();

    /**
     * Finds all flags for a specific listing.
     *
     * @param listingId
     *            the listing UUID
     * @return list of flags for the listing
     */
    public static List<ListingFlag> findByListingId(UUID listingId) {
        if (listingId == null) {
            return List.of();
        }
        return list(JPQL_FIND_BY_LISTING, io.quarkus.panache.common.Parameters.with("listingId", listingId));
    }

    /**
     * Finds all pending flags across all listings.
     *
     * @return list of pending flags, newest first
     */
    public static List<ListingFlag> findPending() {
        return list(JPQL_FIND_PENDING);
    }

    /**
     * Finds flags by status with pagination.
     *
     * @param status
     *            the flag status (pending, approved, dismissed)
     * @param offset
     *            the offset for pagination
     * @param limit
     *            the maximum number of results
     * @return paginated list of flags
     */
    public static List<ListingFlag> findByStatus(String status, int offset, int limit) {
        if (status == null) {
            return List.of();
        }
        return find(JPQL_FIND_BY_STATUS, io.quarkus.panache.common.Parameters.with("status", status))
                .page(offset / limit, limit).list();
    }

    /**
     * Counts pending flags for a specific listing.
     *
     * <p>
     * Used to determine if listing should be auto-hidden at threshold of 3.
     *
     * @param listingId
     *            the listing UUID
     * @return count of pending flags
     */
    public static long countPendingForListing(UUID listingId) {
        if (listingId == null) {
            return 0;
        }
        return count("listingId = ?1 AND status = 'pending'", listingId);
    }

    /**
     * Counts flags by status.
     *
     * @param status
     *            the flag status
     * @return count of flags with the given status
     */
    public static long countByStatus(String status) {
        if (status == null) {
            return 0;
        }
        return count("status = ?1", status);
    }

    /**
     * Counts flags submitted by a user in the last 24 hours.
     *
     * <p>
     * Used for rate limiting (5 flags/day per user).
     *
     * @param userId
     *            the user UUID
     * @return count of flags submitted in last 24h
     */
    public static long countRecentByUser(UUID userId) {
        if (userId == null) {
            return 0;
        }
        Instant oneDayAgo = Instant.now().minusSeconds(86400);
        return count("userId = ?1 AND createdAt > ?2", userId, oneDayAgo);
    }

    /**
     * Approves this flag and marks it as reviewed.
     *
     * <p>
     * This does NOT update the listing status - that is handled by ModerationService.
     *
     * @param adminUserId
     *            the admin user performing the review
     * @param notes
     *            optional review notes
     */
    public void approve(UUID adminUserId, String notes) {
        this.status = "approved";
        this.reviewedByUserId = adminUserId;
        this.reviewedAt = Instant.now();
        this.reviewNotes = notes;
        this.updatedAt = Instant.now();
        this.persist();
    }

    /**
     * Dismisses this flag as invalid.
     *
     * <p>
     * This does NOT decrement the listing flag_count - that is handled by ModerationService.
     *
     * @param adminUserId
     *            the admin user performing the review
     * @param notes
     *            optional review notes
     */
    public void dismiss(UUID adminUserId, String notes) {
        this.status = "dismissed";
        this.reviewedByUserId = adminUserId;
        this.reviewedAt = Instant.now();
        this.reviewNotes = notes;
        this.updatedAt = Instant.now();
        this.persist();
    }

    /**
     * Updates fraud analysis results from AI service.
     *
     * @param score
     *            fraud probability (0.00 to 1.00)
     * @param reasons
     *            JSON array of detected fraud indicators
     */
    public void updateFraudAnalysis(BigDecimal score, String reasons) {
        this.fraudScore = score;
        this.fraudReasons = reasons;
        this.updatedAt = Instant.now();
        this.persist();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
