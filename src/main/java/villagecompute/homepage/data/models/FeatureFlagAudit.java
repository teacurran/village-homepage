package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * Audit log entity for feature flag mutations (Policy P14 compliance).
 *
 * <p>
 * Captures before/after state snapshots for every flag modification to maintain traceability. Admin actions include
 * actor identity, reason, and trace ID for correlation with distributed traces.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (BIGSERIAL, PK) - Auto-generated audit entry identifier</li>
 * <li>{@code flag_key} (TEXT) - Reference to the modified feature flag</li>
 * <li>{@code actor_id} (BIGINT) - User or admin ID performing the mutation (nullable for system)</li>
 * <li>{@code actor_type} (TEXT) - Type of actor: 'user', 'admin', 'system'</li>
 * <li>{@code action} (TEXT) - Mutation type: 'create', 'update', 'delete', 'toggle'</li>
 * <li>{@code before_state} (JSONB) - Flag state before mutation (nullable for creates)</li>
 * <li>{@code after_state} (JSONB) - Flag state after mutation</li>
 * <li>{@code reason} (TEXT) - Optional explanation for the change</li>
 * <li>{@code trace_id} (TEXT) - OpenTelemetry trace ID for correlation</li>
 * <li>{@code timestamp} (TIMESTAMPTZ) - Audit event timestamp</li>
 * </ul>
 *
 * @see FeatureFlag for the primary flag entity
 */
@Entity
@Table(
        name = "feature_flag_audit")
public class FeatureFlagAudit extends PanacheEntityBase {

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(
            name = "flag_key",
            nullable = false)
    public String flagKey;

    @Column(
            name = "actor_id")
    public Long actorId;

    @Column(
            name = "actor_type",
            nullable = false)
    public String actorType;

    @Column(
            nullable = false)
    public String action;

    @Column(
            name = "before_state",
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public String beforeState;

    @Column(
            name = "after_state",
            nullable = false,
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public String afterState;

    @Column
    public String reason;

    @Column(
            name = "trace_id")
    public String traceId;

    @Column(
            nullable = false)
    public Instant timestamp;

    /**
     * Finds all audit entries for a specific feature flag.
     *
     * @param flagKey
     *            the flag key to query
     * @return List of audit entries ordered by timestamp descending
     */
    public static List<FeatureFlagAudit> findByFlag(String flagKey) {
        return find("flagKey = ?1 ORDER BY timestamp DESC", flagKey).list();
    }

    /**
     * Finds all audit entries for a specific actor.
     *
     * @param actorId
     *            the actor user ID
     * @param actorType
     *            the actor type (user, admin, system)
     * @return List of audit entries ordered by timestamp descending
     */
    public static List<FeatureFlagAudit> findByActor(Long actorId, String actorType) {
        return find("actorId = ?1 AND actorType = ?2 ORDER BY timestamp DESC", actorId, actorType).list();
    }

    /**
     * Creates a new audit record for a flag mutation.
     *
     * @param flagKey
     *            the flag being mutated
     * @param actorId
     *            the user/admin performing the action (null for system)
     * @param actorType
     *            type of actor (user, admin, system)
     * @param action
     *            mutation type (create, update, delete, toggle)
     * @param beforeState
     *            JSON snapshot before mutation (null for creates)
     * @param afterState
     *            JSON snapshot after mutation
     * @param reason
     *            optional explanation for the change
     * @param traceId
     *            OpenTelemetry trace ID
     * @return the persisted audit record
     */
    public static FeatureFlagAudit recordMutation(String flagKey, Long actorId, String actorType, String action,
            String beforeState, String afterState, String reason, String traceId) {

        FeatureFlagAudit audit = new FeatureFlagAudit();
        audit.flagKey = flagKey;
        audit.actorId = actorId;
        audit.actorType = actorType;
        audit.action = action;
        audit.beforeState = beforeState;
        audit.afterState = afterState;
        audit.reason = reason;
        audit.traceId = traceId;
        audit.timestamp = Instant.now();
        audit.persist();
        return audit;
    }

    /**
     * Valid actor types for audit records.
     */
    public enum ActorType {
        USER, ADMIN, SYSTEM
    }

    /**
     * Valid mutation actions for audit records.
     */
    public enum AuditAction {
        CREATE, UPDATE, DELETE, TOGGLE
    }
}
