package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Parameters;
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
 * RSS source entity implementing the Panache ActiveRecord pattern for feed registry.
 *
 * <p>
 * Supports both system-managed feeds (curated by VillageCompute) and user-custom feeds per Policy P1 (GDPR/CCPA).
 * System feeds (TechCrunch, BBC News, etc.) are managed by admins; user-custom feeds are included in data export.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code name} (TEXT) - Feed display name</li>
 * <li>{@code url} (TEXT, UNIQUE) - RSS/Atom feed URL</li>
 * <li>{@code category} (TEXT) - Optional category (Technology, Business, Science, etc.)</li>
 * <li>{@code is_system} (BOOLEAN) - System-managed vs user-custom feed</li>
 * <li>{@code user_id} (UUID, FK) - Owner for user-custom feeds (null for system feeds)</li>
 * <li>{@code refresh_interval_minutes} (INT) - Refresh interval (15-1440 minutes)</li>
 * <li>{@code is_active} (BOOLEAN) - Active/disabled status</li>
 * <li>{@code last_fetched_at} (TIMESTAMPTZ) - Last successful fetch timestamp</li>
 * <li>{@code error_count} (INT) - Consecutive error count (auto-disable at 5)</li>
 * <li>{@code last_error_message} (TEXT) - Last error message for debugging</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * <li>{@code updated_at} (TIMESTAMPTZ) - Last modification timestamp</li>
 * </ul>
 *
 * <p>
 * <b>Health Monitoring:</b> Feeds with 5+ consecutive errors are auto-disabled by the refresh job (I3.T2). Error count
 * resets to 0 on successful fetch. See {@code docs/ops/feed-governance.md} for operational procedures.
 *
 * @see FeedItem for aggregated articles
 * @see UserFeedSubscription for user subscriptions
 */
@Entity
@Table(
        name = "rss_sources")
@NamedQuery(
        name = RssSource.QUERY_FIND_BY_URL,
        query = RssSource.JPQL_FIND_BY_URL)
@NamedQuery(
        name = RssSource.QUERY_FIND_ACTIVE,
        query = RssSource.JPQL_FIND_ACTIVE)
@NamedQuery(
        name = RssSource.QUERY_FIND_BY_CATEGORY,
        query = RssSource.JPQL_FIND_BY_CATEGORY)
@NamedQuery(
        name = RssSource.QUERY_FIND_SYSTEM_FEEDS,
        query = RssSource.JPQL_FIND_SYSTEM_FEEDS)
@NamedQuery(
        name = RssSource.QUERY_FIND_USER_FEEDS,
        query = RssSource.JPQL_FIND_USER_FEEDS)
@NamedQuery(
        name = RssSource.QUERY_FIND_DUE_FOR_REFRESH,
        query = RssSource.JPQL_FIND_DUE_FOR_REFRESH)
public class RssSource extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(RssSource.class);

    public static final String JPQL_FIND_BY_URL = "FROM RssSource WHERE url = :url";
    public static final String QUERY_FIND_BY_URL = "RssSource.findByUrl";

    public static final String JPQL_FIND_ACTIVE = "FROM RssSource WHERE isActive = true";
    public static final String QUERY_FIND_ACTIVE = "RssSource.findActive";

    public static final String JPQL_FIND_BY_CATEGORY = "FROM RssSource WHERE category = :category";
    public static final String QUERY_FIND_BY_CATEGORY = "RssSource.findByCategory";

    public static final String JPQL_FIND_SYSTEM_FEEDS = "FROM RssSource WHERE isSystem = true";
    public static final String QUERY_FIND_SYSTEM_FEEDS = "RssSource.findSystemFeeds";

    public static final String JPQL_FIND_USER_FEEDS = "FROM RssSource WHERE userId = :userId AND isSystem = false";
    public static final String QUERY_FIND_USER_FEEDS = "RssSource.findUserFeeds";

    public static final String JPQL_FIND_DUE_FOR_REFRESH = "FROM RssSource WHERE isActive = true";
    public static final String QUERY_FIND_DUE_FOR_REFRESH = "RssSource.findDueForRefresh";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            nullable = false)
    public String name;

    @Column(
            nullable = false)
    public String url;

    @Column
    public String category;

    @Column(
            name = "is_system",
            nullable = false)
    public boolean isSystem;

    @Column(
            name = "user_id")
    public UUID userId;

    @Column(
            name = "refresh_interval_minutes",
            nullable = false)
    public int refreshIntervalMinutes;

    @Column(
            name = "is_active",
            nullable = false)
    public boolean isActive;

    @Column(
            name = "last_fetched_at")
    public Instant lastFetchedAt;

    @Column(
            name = "error_count",
            nullable = false)
    public int errorCount;

    @Column(
            name = "last_error_message")
    public String lastErrorMessage;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Finds an RSS source by its feed URL.
     *
     * @param url
     *            the RSS/Atom feed URL
     * @return Optional containing the source if found
     */
    public static Optional<RssSource> findByUrl(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return find("#" + QUERY_FIND_BY_URL, Parameters.with("url", url)).firstResultOptional();
    }

    /**
     * Finds all active RSS sources.
     *
     * @return List of active sources
     */
    public static List<RssSource> findActive() {
        return find("#" + QUERY_FIND_ACTIVE).list();
    }

    /**
     * Finds RSS sources by category.
     *
     * @param category
     *            the category name (Technology, Business, etc.)
     * @return List of sources in the specified category
     */
    public static List<RssSource> findByCategory(String category) {
        if (category == null || category.isBlank()) {
            return List.of();
        }
        return find("#" + QUERY_FIND_BY_CATEGORY, Parameters.with("category", category)).list();
    }

    /**
     * Finds all system-managed feeds.
     *
     * @return List of system feeds (curated by VillageCompute)
     */
    public static List<RssSource> findSystemFeeds() {
        return find("#" + QUERY_FIND_SYSTEM_FEEDS).list();
    }

    /**
     * Finds user-custom feeds for a specific user.
     *
     * @param userId
     *            the user's UUID
     * @return List of user-created feeds
     */
    public static List<RssSource> findUserFeeds(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return find("#" + QUERY_FIND_USER_FEEDS, Parameters.with("userId", userId)).list();
    }

    /**
     * Finds active feeds due for refresh based on last_fetched_at and refresh_interval_minutes.
     *
     * @return List of sources ready for refresh (used by DEFAULT queue job picker)
     */
    public static List<RssSource> findDueForRefresh() {
        // Fetch all active sources and filter in Java for database compatibility
        // In production with PostgreSQL, this could be optimized with a native query using interval arithmetic
        List<RssSource> activeSources = find("#" + QUERY_FIND_DUE_FOR_REFRESH).list();
        Instant now = Instant.now();

        return activeSources.stream().filter(source -> {
            if (source.lastFetchedAt == null) {
                return true; // Never fetched - due for refresh
            }
            Instant nextRefreshTime = source.lastFetchedAt.plusSeconds(source.refreshIntervalMinutes * 60L);
            return nextRefreshTime.isBefore(now);
        }).toList();
    }

    /**
     * Creates a new RSS source with audit timestamps.
     *
     * @param source
     *            the source to persist
     * @return the persisted source with generated ID
     */
    public static RssSource create(RssSource source) {
        QuarkusTransaction.requiringNew().run(() -> {
            source.createdAt = Instant.now();
            source.updatedAt = Instant.now();
            source.persist();
            LOG.infof("Created RSS source: id=%s, name=%s, url=%s, isSystem=%b", source.id, source.name, source.url,
                    source.isSystem);
        });
        return source;
    }

    /**
     * Updates an existing RSS source with audit timestamp.
     *
     * @param source
     *            the source to update
     */
    public static void update(RssSource source) {
        QuarkusTransaction.requiringNew().run(() -> {
            source.updatedAt = Instant.now();
            source.persist();
            LOG.infof("Updated RSS source: id=%s, name=%s, isActive=%b", source.id, source.name, source.isActive);
        });
    }

    /**
     * Records a fetch error and increments error count. Auto-disables feed if error_count reaches 5.
     *
     * @param source
     *            the source that encountered an error
     * @param errorMessage
     *            the error message to log
     */
    public static void recordError(RssSource source, String errorMessage) {
        QuarkusTransaction.requiringNew().run(() -> {
            // Re-fetch the entity in the new transaction to avoid detached entity error
            RssSource managedSource = RssSource.findById(source.id);
            if (managedSource != null) {
                managedSource.errorCount++;
                managedSource.lastErrorMessage = errorMessage;
                managedSource.updatedAt = Instant.now();

                if (managedSource.errorCount >= 5 && managedSource.isActive) {
                    managedSource.isActive = false;
                    LOG.warnf("Auto-disabled RSS source after 5 errors: id=%s, name=%s, url=%s", managedSource.id,
                            managedSource.name, managedSource.url);
                }

                managedSource.persist();
            }
        });
    }

    /**
     * Records a successful fetch and resets error count.
     *
     * @param source
     *            the source that was successfully fetched
     */
    public static void recordSuccess(RssSource source) {
        QuarkusTransaction.requiringNew().run(() -> {
            // Re-fetch the entity in the new transaction to avoid detached entity error
            RssSource managedSource = RssSource.findById(source.id);
            if (managedSource != null) {
                managedSource.lastFetchedAt = Instant.now();
                managedSource.errorCount = 0;
                managedSource.lastErrorMessage = null;
                managedSource.updatedAt = Instant.now();
                managedSource.persist();
            }
        });
    }
}
