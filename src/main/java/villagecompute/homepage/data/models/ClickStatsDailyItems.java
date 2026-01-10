package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ClickStatsDailyItems entity representing aggregated daily click statistics by individual items.
 *
 * <p>
 * Database mapping: click_stats_daily_items table
 * </p>
 *
 * <p>
 * This table stores item-level rollup data (sites, listings, profiles) from {@link LinkClick}. Includes additional
 * metrics like average rank, score, and bubbled click counts.
 * </p>
 *
 * <p>
 * Populated by ClickRollupJobHandler alongside {@link ClickStatsDaily}.
 * </p>
 *
 * @see LinkClick
 * @see ClickStatsDaily
 */
@Entity
@Table(
        name = "click_stats_daily_items")
public class ClickStatsDailyItems extends PanacheEntityBase {

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "stat_date",
            nullable = false)
    public LocalDate statDate;

    @Column(
            name = "click_type",
            nullable = false)
    public String clickType;

    @Column(
            name = "target_id",
            nullable = false)
    public UUID targetId;

    @Column(
            name = "total_clicks",
            nullable = false)
    public long totalClicks;

    @Column(
            name = "unique_users",
            nullable = false)
    public long uniqueUsers;

    @Column(
            name = "unique_sessions",
            nullable = false)
    public long uniqueSessions;

    @Column(
            name = "avg_rank",
            precision = 10,
            scale = 2)
    public BigDecimal avgRank;

    @Column(
            name = "avg_score",
            precision = 10,
            scale = 2)
    public BigDecimal avgScore;

    @Column(
            name = "bubbled_clicks",
            nullable = false)
    public long bubbledClicks;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Find stats for a specific item and date range.
     *
     * @param targetId
     *            Target entity UUID
     * @param startDate
     *            Start date (inclusive)
     * @param endDate
     *            End date (exclusive)
     * @return List of daily stats
     */
    public static List<ClickStatsDailyItems> findByTargetAndDateRange(UUID targetId, LocalDate startDate,
            LocalDate endDate) {
        return find("targetId = ?1 AND statDate >= ?2 AND statDate < ?3 ORDER BY statDate DESC", targetId, startDate,
                endDate).list();
    }

    /**
     * Find top items by clicks for a date range and type.
     *
     * @param clickType
     *            Type of click event
     * @param startDate
     *            Start date (inclusive)
     * @param endDate
     *            End date (exclusive)
     * @param limit
     *            Maximum number of results
     * @return List of top items with aggregated click counts
     */
    public static List<ClickStatsDailyItems> findTopItemsByType(String clickType, LocalDate startDate,
            LocalDate endDate, int limit) {
        return find("SELECT targetId, SUM(totalClicks) as totalClicks, SUM(uniqueUsers) as uniqueUsers, "
                + "AVG(avgRank) as avgRank, AVG(avgScore) as avgScore, SUM(bubbledClicks) as bubbledClicks "
                + "FROM ClickStatsDailyItems WHERE clickType = ?1 AND statDate >= ?2 AND statDate < ?3 "
                + "GROUP BY targetId ORDER BY SUM(totalClicks) DESC", clickType, startDate, endDate).page(0, limit)
                .list();
    }

    /**
     * Sum total clicks for a target within date range.
     *
     * @param targetId
     *            Target entity UUID
     * @param startDate
     *            Start date (inclusive)
     * @param endDate
     *            End date (exclusive)
     * @return Total click count
     */
    public static long sumClicksByTarget(UUID targetId, LocalDate startDate, LocalDate endDate) {
        Long sum = find(
                "SELECT SUM(totalClicks) FROM ClickStatsDailyItems WHERE targetId = ?1 AND statDate >= ?2 AND statDate < ?3",
                targetId, startDate, endDate).project(Long.class).firstResult();
        return sum != null ? sum : 0L;
    }

    /**
     * Find items with high bubbled click ratios (for bubbling effectiveness analysis).
     *
     * @param startDate
     *            Start date (inclusive)
     * @param endDate
     *            End date (exclusive)
     * @param minClicks
     *            Minimum total clicks to include
     * @param limit
     *            Maximum number of results
     * @return List of items sorted by bubbled click ratio
     */
    public static List<ClickStatsDailyItems> findTopBubbledItems(LocalDate startDate, LocalDate endDate, int minClicks,
            int limit) {
        return find(
                "SELECT targetId, SUM(totalClicks) as totalClicks, SUM(bubbledClicks) as bubbledClicks, "
                        + "(SUM(bubbledClicks) * 100.0 / SUM(totalClicks)) as bubbledRatio "
                        + "FROM ClickStatsDailyItems WHERE statDate >= ?1 AND statDate < ?2 "
                        + "GROUP BY targetId HAVING SUM(totalClicks) >= ?3 " + "ORDER BY bubbledRatio DESC",
                startDate, endDate, minClicks).page(0, limit).list();
    }

    /**
     * Delete stats older than retention period.
     *
     * @param cutoffDate
     *            Date before which stats should be deleted
     * @return Number of deleted records
     */
    public static long deleteOlderThan(LocalDate cutoffDate) {
        return delete("statDate < ?1", cutoffDate);
    }
}
