package villagecompute.homepage.data.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Feature flag entity implementing the Panache ActiveRecord pattern.
 *
 * <p>
 * Supports Policy P7 requirements for stable cohort assignment, whitelist overrides, and analytics toggles. All
 * database access is performed through static finder methods per VillageCompute Java Project Standards.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code flag_key} (TEXT, PK) - Unique feature flag identifier (e.g., "stocks_widget")</li>
 * <li>{@code description} (TEXT) - Human-readable explanation of feature purpose</li>
 * <li>{@code enabled} (BOOLEAN) - Master kill switch (overrides rollout percentage)</li>
 * <li>{@code rollout_percentage} (SMALLINT) - Cohort rollout 0-100%</li>
 * <li>{@code whitelist} (JSONB) - User IDs or session hashes for forced enablement</li>
 * <li>{@code analytics_enabled} (BOOLEAN) - Whether to log evaluations (Policy P14 consent)</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * <li>{@code updated_at} (TIMESTAMPTZ) - Last modification timestamp</li>
 * </ul>
 *
 * @see FeatureFlagAudit for mutation audit logging
 */
@Entity
@Table(
        name = "feature_flags")
public class FeatureFlag extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(FeatureFlag.class);

    @Id
    @Column(
            name = "flag_key",
            nullable = false,
            unique = true)
    public String flagKey;

    @Column(
            nullable = false)
    public String description;

    @Column(
            nullable = false)
    public boolean enabled = false;

    @Column(
            name = "rollout_percentage",
            nullable = false)
    public short rolloutPercentage = 0;

    @Column(
            nullable = false,
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public List<String> whitelist = List.of();

    @Column(
            name = "analytics_enabled",
            nullable = false)
    public boolean analyticsEnabled = false;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt = Instant.now();

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt = Instant.now();

    /**
     * Finds a feature flag by its unique key.
     *
     * @param key
     *            the flag key (case-sensitive)
     * @return Optional containing the flag if found, empty otherwise
     */
    public static Optional<FeatureFlag> findByKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return find("flagKey = ?1", key).firstResultOptional();
    }

    /**
     * Retrieves all feature flags (admin list view).
     *
     * @return List of all flags, ordered by flag_key
     */
    public static List<FeatureFlag> findAllFlags() {
        return find("ORDER BY flagKey").list();
    }

    /**
     * Retrieves all enabled feature flags.
     *
     * @return List of enabled flags, ordered by flag_key
     */
    public static List<FeatureFlag> findAllEnabled() {
        return find("enabled = ?1 ORDER BY flagKey", true).list();
    }

    /**
     * Checks if a subject (user or session) is whitelisted for this flag.
     *
     * @param subjectIdentifier
     *            user ID or session hash
     * @return true if the subject is in the whitelist
     */
    public boolean isWhitelisted(String subjectIdentifier) {
        if (subjectIdentifier == null || whitelist == null) {
            return false;
        }
        return whitelist.contains(subjectIdentifier);
    }

    /**
     * Updates the flag configuration and persists changes.
     *
     * @param description
     *            new description (null to keep current)
     * @param enabled
     *            new enabled state (null to keep current)
     * @param rolloutPercentage
     *            new rollout percentage (null to keep current)
     * @param whitelist
     *            new whitelist (null to keep current)
     * @param analyticsEnabled
     *            new analytics toggle (null to keep current)
     */
    public void update(String description, Boolean enabled, Short rolloutPercentage, List<String> whitelist,
            Boolean analyticsEnabled) {
        if (description != null) {
            this.description = description;
        }
        if (enabled != null) {
            this.enabled = enabled;
        }
        if (rolloutPercentage != null) {
            if (rolloutPercentage < 0 || rolloutPercentage > 100) {
                throw new IllegalArgumentException("rollout_percentage must be between 0 and 100");
            }
            this.rolloutPercentage = rolloutPercentage;
        }
        if (whitelist != null) {
            this.whitelist = whitelist;
        }
        if (analyticsEnabled != null) {
            this.analyticsEnabled = analyticsEnabled;
        }
        this.updatedAt = Instant.now();
    }

    /**
     * Serializes this flag to JSON for audit logging.
     *
     * @param mapper
     *            Jackson ObjectMapper instance
     * @return JSON representation of the flag state
     */
    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(new FlagSnapshot(this.flagKey, this.description, this.enabled,
                    this.rolloutPercentage, this.whitelist, this.analyticsEnabled, this.updatedAt));
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to serialize flag %s to JSON: %s", this.flagKey, e.getMessage());
            return "{}";
        }
    }

    /**
     * Immutable snapshot record for JSON serialization in audit logs.
     */
    public record FlagSnapshot(@JsonProperty("flag_key") String flagKey, String description, boolean enabled,
            @JsonProperty("rollout_percentage") short rolloutPercentage, List<String> whitelist,
            @JsonProperty("analytics_enabled") boolean analyticsEnabled,
            @JsonProperty("updated_at") Instant updatedAt) {
    }
}
