package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rate limit configuration entity implementing the Panache ActiveRecord pattern.
 *
 * <p>
 * Stores rate limiting rules per action type and user tier. All database access is performed through static finder
 * methods per VillageCompute Java Project Standards.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary key identifier</li>
 * <li>{@code action_type} (TEXT) - Action identifier (e.g., "login", "search", "vote")</li>
 * <li>{@code tier} (TEXT) - User tier: "anonymous", "logged_in", or "trusted"</li>
 * <li>{@code limit_count} (INT) - Maximum allowed requests within window</li>
 * <li>{@code window_seconds} (INT) - Time window in seconds for limit enforcement</li>
 * <li>{@code updated_by_user_id} (BIGINT) - User ID who last modified this config (nullable)</li>
 * <li>{@code updated_at} (TIMESTAMPTZ) - Last modification timestamp</li>
 * </ul>
 *
 * <p>
 * <b>Unique Constraint:</b> (action_type, tier) ensures one rule per action/tier combination.
 *
 * @see RateLimitViolation for violation tracking
 */
@Entity
@Table(
        name = "rate_limit_config")
public class RateLimitConfig extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(RateLimitConfig.class);

    @Id
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "action_type",
            nullable = false)
    public String actionType;

    @Column(
            nullable = false)
    public String tier;

    @Column(
            name = "limit_count",
            nullable = false)
    public int limitCount;

    @Column(
            name = "window_seconds",
            nullable = false)
    public int windowSeconds;

    @Column(
            name = "updated_by_user_id")
    public Long updatedByUserId;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Finds a rate limit configuration for the given action type and tier.
     *
     * @param actionType
     *            the action identifier (e.g., "login", "search")
     * @param tier
     *            the user tier ("anonymous", "logged_in", "trusted")
     * @return Optional containing the config if found, empty otherwise
     */
    public static Optional<RateLimitConfig> findByActionAndTier(String actionType, String tier) {
        if (actionType == null || actionType.isBlank() || tier == null || tier.isBlank()) {
            return Optional.empty();
        }
        return find("actionType = ?1 AND tier = ?2", actionType, tier).firstResultOptional();
    }

    /**
     * Retrieves all rate limit configurations (admin list view).
     *
     * @return List of all configs, ordered by action_type, tier
     */
    public static List<RateLimitConfig> findAllConfigs() {
        return find("ORDER BY actionType, tier").list();
    }

    /**
     * Retrieves all configurations for a specific action type across all tiers.
     *
     * @param actionType
     *            the action identifier
     * @return List of configs for the action, ordered by tier
     */
    public static List<RateLimitConfig> findByAction(String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return List.of();
        }
        return find("actionType = ?1 ORDER BY tier", actionType).list();
    }

    /**
     * Updates the rate limit configuration and persists changes.
     *
     * @param limitCount
     *            new limit count (null to keep current)
     * @param windowSeconds
     *            new window in seconds (null to keep current)
     * @param updatedByUserId
     *            ID of user making the update
     */
    public void update(Integer limitCount, Integer windowSeconds, Long updatedByUserId) {
        if (limitCount != null) {
            if (limitCount <= 0) {
                throw new IllegalArgumentException("limit_count must be positive");
            }
            this.limitCount = limitCount;
        }
        if (windowSeconds != null) {
            if (windowSeconds <= 0) {
                throw new IllegalArgumentException("window_seconds must be positive");
            }
            this.windowSeconds = windowSeconds;
        }
        if (updatedByUserId != null) {
            this.updatedByUserId = updatedByUserId;
        }
        this.updatedAt = Instant.now();
    }

    /**
     * Creates a new rate limit configuration with default UUID.
     *
     * @param actionType
     *            action identifier
     * @param tier
     *            user tier
     * @param limitCount
     *            max requests
     * @param windowSeconds
     *            time window
     * @param updatedByUserId
     *            creator user ID
     * @return new persisted entity
     */
    public static RateLimitConfig create(String actionType, String tier, int limitCount, int windowSeconds,
            Long updatedByUserId) {
        RateLimitConfig config = new RateLimitConfig();
        config.id = UUID.randomUUID();
        config.actionType = actionType;
        config.tier = tier;
        config.limitCount = limitCount;
        config.windowSeconds = windowSeconds;
        config.updatedByUserId = updatedByUserId;
        config.updatedAt = Instant.now();
        config.persist();
        return config;
    }
}
