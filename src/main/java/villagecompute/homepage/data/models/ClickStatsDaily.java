package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ClickStatsDaily entity representing aggregated daily click statistics by type and category.
 *
 * <p>
 * Database mapping: click_stats_daily table
 * </p>
 *
 * <p>
 * This table stores rollup data from {@link LinkClick} for efficient dashboard queries. Retention is indefinite (unlike
 * raw click logs which have 90-day retention).
 * </p>
 *
 * <p>
 * Populated by ClickRollupJobHandler which runs hourly to aggregate recent clicks. Uses ON CONFLICT DO UPDATE for
 * idempotency (can re-run rollup without duplicates).
 * </p>
 *
 * @see LinkClick
 * @see ClickStatsDailyItems
 */
@Entity
@Table(
        name = "click_stats_daily")
public class ClickStatsDaily extends PanacheEntityBase {

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
            name = "category_id")
    public UUID categoryId;

    @Column(
            name = "category_name")
    public String categoryName;

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
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Find stats by date range and type.
     *
     * @param clickType
     *            Type of click event
     * @param startDate
     *            Start date (inclusive)
     * @param endDate
     *            End date (exclusive)
     * @return List of daily stats
     */
    public static List<ClickStatsDaily> findByTypeAndDateRange(String clickType, LocalDate startDate,
            LocalDate endDate) {
        return find("clickType = ?1 AND statDate >= ?2 AND statDate < ?3 ORDER BY statDate DESC", clickType, startDate,
                endDate).list();
    }

    /**
     * Find stats for a specific category and date range.
     *
     * @param categoryId
     *            Category UUID
     * @param startDate
     *            Start date (inclusive)
     * @param endDate
     *            End date (exclusive)
     * @return List of daily stats
     */
    public static List<ClickStatsDaily> findByCategoryAndDateRange(UUID categoryId, LocalDate startDate,
            LocalDate endDate) {
        return find("categoryId = ?1 AND statDate >= ?2 AND statDate < ?3 ORDER BY statDate DESC", categoryId,
                startDate, endDate).list();
    }

    /**
     * Find top categories by clicks for a date range.
     *
     * @param startDate
     *            Start date (inclusive)
     * @param endDate
     *            End date (exclusive)
     * @param limit
     *            Maximum number of results
     * @return List of top categories with click counts
     */
    public static List<ClickStatsDaily> findTopCategories(LocalDate startDate, LocalDate endDate, int limit) {
        return find("SELECT categoryId, categoryName, SUM(totalClicks) as totalClicks, SUM(uniqueUsers) as uniqueUsers "
                + "FROM ClickStatsDaily WHERE categoryId IS NOT NULL AND statDate >= ?1 AND statDate < ?2 "
                + "GROUP BY categoryId, categoryName ORDER BY SUM(totalClicks) DESC", startDate, endDate).page(0, limit)
                .list();
    }

    /**
     * Sum total clicks for a type within date range.
     *
     * @param clickType
     *            Type of click event
     * @param startDate
     *            Start date (inclusive)
     * @param endDate
     *            End date (exclusive)
     * @return Total click count
     */
    public static long sumClicksByType(String clickType, LocalDate startDate, LocalDate endDate) {
        Long sum = find(
                "SELECT SUM(totalClicks) FROM ClickStatsDaily WHERE clickType = ?1 AND statDate >= ?2 AND statDate < ?3",
                clickType, startDate, endDate).project(Long.class).firstResult();
        return sum != null ? sum : 0L;
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
