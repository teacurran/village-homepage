package villagecompute.homepage.data.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * KarmaAudit entity implementing the Panache ActiveRecord pattern for tracking directory karma adjustments.
 *
 * <p>
 * Provides complete audit trail of all karma changes including:
 * <ul>
 * <li>Automatic adjustments from submissions and votes</li>
 * <li>Manual admin adjustments</li>
 * <li>Trust level promotions/demotions</li>
 * <li>Before/after snapshots</li>
 * </ul>
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code user_id} (UUID, FK) - User whose karma was adjusted</li>
 * <li>{@code old_karma} (INT) - Karma value before adjustment</li>
 * <li>{@code new_karma} (INT) - Karma value after adjustment</li>
 * <li>{@code delta} (INT) - Change in karma (positive or negative)</li>
 * <li>{@code old_trust_level} (TEXT) - Trust level before adjustment</li>
 * <li>{@code new_trust_level} (TEXT) - Trust level after adjustment</li>
 * <li>{@code reason} (TEXT) - Human-readable reason for adjustment</li>
 * <li>{@code trigger_type} (TEXT) - Event type that triggered adjustment</li>
 * <li>{@code trigger_entity_type} (TEXT) - Entity type that triggered adjustment</li>
 * <li>{@code trigger_entity_id} (UUID) - Entity ID that triggered adjustment</li>
 * <li>{@code adjusted_by_user_id} (UUID, FK) - Admin who made manual adjustment (null for automatic)</li>
 * <li>{@code metadata} (JSONB) - Additional context</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Timestamp of adjustment</li>
 * </ul>
 *
 * @see User
 */
@Entity
@Table(
        name = "karma_audit")
public class KarmaAudit extends PanacheEntityBase {

    public static final String TRIGGER_SUBMISSION_APPROVED = "submission_approved";
    public static final String TRIGGER_SUBMISSION_REJECTED = "submission_rejected";
    public static final String TRIGGER_VOTE_RECEIVED = "vote_received";
    public static final String TRIGGER_ADMIN_ADJUSTMENT = "admin_adjustment";
    public static final String TRIGGER_SYSTEM_ADJUSTMENT = "system_adjustment";

    public static final String ENTITY_TYPE_SITE_CATEGORY = "site_category";
    public static final String ENTITY_TYPE_VOTE = "vote";
    public static final String ENTITY_TYPE_MANUAL = "manual";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "user_id",
            nullable = false)
    public UUID userId;

    @Column(
            name = "old_karma",
            nullable = false)
    public int oldKarma;

    @Column(
            name = "new_karma",
            nullable = false)
    public int newKarma;

    @Column(
            nullable = false)
    public int delta;

    @Column(
            name = "old_trust_level",
            nullable = false)
    public String oldTrustLevel;

    @Column(
            name = "new_trust_level",
            nullable = false)
    public String newTrustLevel;

    @Column(
            nullable = false)
    public String reason;

    @Column(
            name = "trigger_type",
            nullable = false)
    public String triggerType;

    @Column(
            name = "trigger_entity_type")
    public String triggerEntityType;

    @Column(
            name = "trigger_entity_id")
    public UUID triggerEntityId;

    @Column(
            name = "adjusted_by_user_id")
    public UUID adjustedByUserId;

    @Column(
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> metadata;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    /**
     * Find all karma adjustments for a user, ordered by most recent first.
     *
     * @param userId
     *            User ID to search for
     * @return List of karma adjustments for the user
     */
    public static List<KarmaAudit> findByUserId(UUID userId) {
        return find("userId = ?1 ORDER BY createdAt DESC", userId).list();
    }

    /**
     * Find recent karma adjustments for a user (last N records).
     *
     * @param userId
     *            User ID to search for
     * @param limit
     *            Maximum number of records to return
     * @return List of recent karma adjustments
     */
    public static List<KarmaAudit> findRecentByUserId(UUID userId, int limit) {
        return find("userId = ?1 ORDER BY createdAt DESC", userId).page(0, limit).list();
    }

    /**
     * Find all karma adjustments by trigger type.
     *
     * @param triggerType
     *            Trigger type to filter by
     * @return List of karma adjustments with the specified trigger type
     */
    public static List<KarmaAudit> findByTriggerType(String triggerType) {
        return find("triggerType = ?1 ORDER BY createdAt DESC", triggerType).list();
    }

    /**
     * Find all karma adjustments made by a specific admin.
     *
     * @param adminUserId
     *            Admin user ID
     * @return List of manual adjustments made by the admin
     */
    public static List<KarmaAudit> findByAdjustedBy(UUID adminUserId) {
        return find("adjustedByUserId = ?1 ORDER BY createdAt DESC", adminUserId).list();
    }

    /**
     * Find karma adjustments related to a specific entity (site category or vote).
     *
     * @param entityType
     *            Entity type (site_category or vote)
     * @param entityId
     *            Entity ID
     * @return List of karma adjustments related to the entity
     */
    public static List<KarmaAudit> findByEntity(String entityType, UUID entityId) {
        return find("triggerEntityType = ?1 AND triggerEntityId = ?2 ORDER BY createdAt DESC", entityType, entityId)
                .list();
    }

    /**
     * Count karma adjustments for a user.
     *
     * @param userId
     *            User ID
     * @return Total number of karma adjustments for the user
     */
    public static long countByUserId(UUID userId) {
        return count("userId = ?1", userId);
    }

    /**
     * Creates a new karma audit record for tracking an adjustment.
     *
     * @param userId
     *            User whose karma was adjusted
     * @param oldKarma
     *            Karma value before adjustment
     * @param newKarma
     *            Karma value after adjustment
     * @param oldTrustLevel
     *            Trust level before adjustment
     * @param newTrustLevel
     *            Trust level after adjustment
     * @param reason
     *            Human-readable reason for adjustment
     * @param triggerType
     *            Type of event that triggered adjustment
     * @param triggerEntityType
     *            Type of entity that triggered adjustment (optional)
     * @param triggerEntityId
     *            ID of entity that triggered adjustment (optional)
     * @param adjustedByUserId
     *            Admin who made manual adjustment (optional)
     * @param metadata
     *            Additional context (optional)
     * @return Persisted karma audit record
     */
    public static KarmaAudit create(UUID userId, int oldKarma, int newKarma, String oldTrustLevel, String newTrustLevel,
            String reason, String triggerType, String triggerEntityType, UUID triggerEntityId, UUID adjustedByUserId,
            Map<String, Object> metadata) {

        KarmaAudit audit = new KarmaAudit();
        audit.userId = userId;
        audit.oldKarma = oldKarma;
        audit.newKarma = newKarma;
        audit.delta = newKarma - oldKarma;
        audit.oldTrustLevel = oldTrustLevel;
        audit.newTrustLevel = newTrustLevel;
        audit.reason = reason;
        audit.triggerType = triggerType;
        audit.triggerEntityType = triggerEntityType;
        audit.triggerEntityId = triggerEntityId;
        audit.adjustedByUserId = adjustedByUserId;
        audit.metadata = metadata;
        audit.createdAt = Instant.now();

        audit.persist();
        return audit;
    }

    /**
     * Immutable snapshot record for JSON serialization.
     */
    public record KarmaAuditSnapshot(@JsonProperty("id") UUID id, @JsonProperty("user_id") UUID userId,
            @JsonProperty("old_karma") int oldKarma, @JsonProperty("new_karma") int newKarma,
            @JsonProperty("delta") int delta, @JsonProperty("old_trust_level") String oldTrustLevel,
            @JsonProperty("new_trust_level") String newTrustLevel, @JsonProperty("reason") String reason,
            @JsonProperty("trigger_type") String triggerType,
            @JsonProperty("trigger_entity_type") String triggerEntityType,
            @JsonProperty("trigger_entity_id") UUID triggerEntityId,
            @JsonProperty("adjusted_by_user_id") UUID adjustedByUserId,
            @JsonProperty("metadata") Map<String, Object> metadata, @JsonProperty("created_at") Instant createdAt) {
    }

    /**
     * Creates a snapshot of this karma audit record for API responses.
     *
     * @return immutable snapshot record
     */
    public KarmaAuditSnapshot toSnapshot() {
        return new KarmaAuditSnapshot(this.id, this.userId, this.oldKarma, this.newKarma, this.delta,
                this.oldTrustLevel, this.newTrustLevel, this.reason, this.triggerType, this.triggerEntityType,
                this.triggerEntityId, this.adjustedByUserId, this.metadata, this.createdAt);
    }
}
