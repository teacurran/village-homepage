package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.vertx.core.json.JsonObject;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * LinkClick entity representing a click tracking event.
 *
 * <p>
 * Database mapping: link_clicks table (partitioned by click_date)
 * </p>
 *
 * <p>
 * Policy F14.9: 90-day retention enforced via partition cleanup job. Raw click logs are aggregated into
 * {@link ClickStatsDaily} and {@link ClickStatsDailyItems} for indefinite retention.
 * </p>
 *
 * <p>
 * Click types:
 * <ul>
 * <li>directory_site_click - User clicks on site link from category page</li>
 * <li>directory_category_view - User views category page</li>
 * <li>directory_site_view - User views site detail page</li>
 * <li>directory_vote - User casts vote</li>
 * <li>directory_search - User searches Good Sites</li>
 * <li>directory_bubbled_click - User clicks bubbled site from parent category</li>
 * <li>marketplace_listing - User clicks on marketplace listing</li>
 * <li>marketplace_view - User views marketplace listing detail</li>
 * <li>profile_view - User views another user's profile</li>
 * <li>profile_curated - User clicks on curated article from profile page</li>
 * </ul>
 *
 * <p>
 * Metadata JSONB field stores context-specific data:
 * <ul>
 * <li>category_slug - Category slug for navigation context</li>
 * <li>is_bubbled - Boolean indicating if click was on bubbled site</li>
 * <li>source_category_id - Child category UUID for bubbled sites</li>
 * <li>rank_in_category - Site's rank when clicked</li>
 * <li>score - Site's score when clicked</li>
 * <li>search_query - Search term for directory_search events</li>
 * <li>profile_id - Profile UUID (for profile events)</li>
 * <li>profile_username - Profile username (for context)</li>
 * <li>article_id - Curated article UUID (for profile_curated)</li>
 * <li>article_slot - Slot name (for profile_curated)</li>
 * <li>template - Profile template type (for profile events)</li>
 * </ul>
 *
 * @see ClickStatsDaily
 * @see ClickStatsDailyItems
 */
@Entity
@Table(
        name = "link_clicks")
public class LinkClick extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "click_date",
            nullable = false)
    public LocalDate clickDate;

    @Column(
            name = "click_timestamp",
            nullable = false)
    public Instant clickTimestamp;

    @Column(
            name = "click_type",
            nullable = false)
    public String clickType;

    @Column(
            name = "target_id")
    public UUID targetId;

    @Column(
            name = "target_url",
            length = 2048)
    public String targetUrl;

    @Column(
            name = "user_id")
    public UUID userId;

    @Column(
            name = "session_id")
    public String sessionId;

    @Column(
            name = "ip_address")
    public String ipAddress;

    @Column(
            name = "user_agent",
            length = 512)
    public String userAgent;

    @Column(
            name = "referer",
            length = 2048)
    public String referer;

    @Column(
            name = "category_id")
    public UUID categoryId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "metadata",
            columnDefinition = "jsonb")
    public String metadata;

    /**
     * Get metadata as JsonObject.
     *
     * @return JsonObject or null if metadata is null/empty
     */
    public JsonObject getMetadataAsJson() {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        return new JsonObject(metadata);
    }

    /**
     * Set metadata from JsonObject.
     *
     * @param json
     *            JsonObject to store
     */
    public void setMetadataFromJson(JsonObject json) {
        if (json == null) {
            this.metadata = null;
        } else {
            this.metadata = json.encode();
        }
    }

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt = Instant.now();

    /**
     * Find clicks by type within date range.
     *
     * @param clickType
     *            Type of click event
     * @param startDate
     *            Start date (inclusive)
     * @param endDate
     *            End date (exclusive)
     * @return List of matching clicks
     */
    public static List<LinkClick> findByTypeAndDateRange(String clickType, LocalDate startDate, LocalDate endDate) {
        return find("clickType = ?1 AND clickDate >= ?2 AND clickDate < ?3 ORDER BY clickTimestamp DESC", clickType,
                startDate, endDate).list();
    }

    /**
     * Find clicks for a specific target (site, listing, etc).
     *
     * @param targetId
     *            Target entity UUID
     * @param startDate
     *            Start date (inclusive)
     * @param endDate
     *            End date (exclusive)
     * @return List of matching clicks
     */
    public static List<LinkClick> findByTargetAndDateRange(UUID targetId, LocalDate startDate, LocalDate endDate) {
        return find("targetId = ?1 AND clickDate >= ?2 AND clickDate < ?3 ORDER BY clickTimestamp DESC", targetId,
                startDate, endDate).list();
    }

    /**
     * Find clicks in a category.
     *
     * @param categoryId
     *            Category UUID
     * @param startDate
     *            Start date (inclusive)
     * @param endDate
     *            End date (exclusive)
     * @return List of matching clicks
     */
    public static List<LinkClick> findByCategoryAndDateRange(UUID categoryId, LocalDate startDate, LocalDate endDate) {
        return find("categoryId = ?1 AND clickDate >= ?2 AND clickDate < ?3 ORDER BY clickTimestamp DESC", categoryId,
                startDate, endDate).list();
    }

    /**
     * Count clicks by type for a date.
     *
     * @param clickType
     *            Type of click event
     * @param date
     *            Date to count
     * @return Click count
     */
    public static long countByTypeAndDate(String clickType, LocalDate date) {
        return count("clickType = ?1 AND clickDate = ?2", clickType, date);
    }

    /**
     * Count unique users for a target and date range.
     *
     * @param targetId
     *            Target entity UUID
     * @param startDate
     *            Start date (inclusive)
     * @param endDate
     *            End date (exclusive)
     * @return Unique user count
     */
    public static long countUniqueUsersByTarget(UUID targetId, LocalDate startDate, LocalDate endDate) {
        return find(
                "SELECT COUNT(DISTINCT COALESCE(CAST(userId AS string), sessionId)) FROM LinkClick WHERE targetId = ?1 AND clickDate >= ?2 AND clickDate < ?3",
                targetId, startDate, endDate).project(Long.class).firstResult();
    }

    /**
     * Find bubbled clicks (clicks on sites from parent categories).
     *
     * @param startDate
     *            Start date (inclusive)
     * @param endDate
     *            End date (exclusive)
     * @return List of bubbled clicks
     */
    public static List<LinkClick> findBubbledClicks(LocalDate startDate, LocalDate endDate) {
        return find(
                "clickType = 'directory_bubbled_click' AND clickDate >= ?1 AND clickDate < ?2 ORDER BY clickTimestamp DESC",
                startDate, endDate).list();
    }

    /**
     * Delete clicks older than retention period (for partition cleanup).
     *
     * @param cutoffDate
     *            Date before which clicks should be deleted
     * @return Number of deleted clicks
     */
    public static long deleteOlderThan(LocalDate cutoffDate) {
        return delete("clickDate < ?1", cutoffDate);
    }
}
