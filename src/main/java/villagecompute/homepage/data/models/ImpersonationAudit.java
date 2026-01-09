package villagecompute.homepage.data.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Audit trail entity for admin user impersonation events per Section 3.7.1.
 *
 * <p>
 * All impersonation actions are logged with source/destination user IDs, IP, and user agent for investigative tracing
 * and compliance with admin action audit requirements. Active impersonation sessions have {@code ended_at = null}.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code impersonator_id} (UUID, FK) - User ID performing the impersonation (must have admin role)</li>
 * <li>{@code target_user_id} (UUID, FK) - User ID being impersonated</li>
 * <li>{@code started_at} (TIMESTAMPTZ) - When impersonation session started</li>
 * <li>{@code ended_at} (TIMESTAMPTZ) - When impersonation session ended (null for active sessions)</li>
 * <li>{@code ip_address} (TEXT) - IP address of impersonator</li>
 * <li>{@code user_agent} (TEXT) - User agent string of impersonator</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * </ul>
 *
 * <p>
 * This entity supports Task I2.T8 impersonation guard rails implementation.
 */
@Entity
@Table(
        name = "impersonation_audit")
public class ImpersonationAudit extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(ImpersonationAudit.class);

    public static final String QUERY_FIND_BY_IMPERSONATOR = "ImpersonationAudit.findByImpersonator";
    public static final String QUERY_FIND_BY_TARGET = "ImpersonationAudit.findByTarget";
    public static final String QUERY_FIND_ACTIVE = "ImpersonationAudit.findActive";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "impersonator_id",
            nullable = false)
    public UUID impersonatorId;

    @Column(
            name = "target_user_id",
            nullable = false)
    public UUID targetUserId;

    @Column(
            name = "started_at",
            nullable = false)
    public Instant startedAt;

    @Column(
            name = "ended_at")
    public Instant endedAt;

    @Column(
            name = "ip_address")
    public String ipAddress;

    @Column(
            name = "user_agent")
    public String userAgent;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    /**
     * Finds all impersonation audit entries for a given impersonator.
     *
     * @param impersonatorId
     *            the user ID who performed impersonations
     * @return list of impersonation audit entries, ordered by started_at DESC
     */
    public static List<ImpersonationAudit> findByImpersonator(UUID impersonatorId) {
        if (impersonatorId == null) {
            return List.of();
        }
        return find("#" + QUERY_FIND_BY_IMPERSONATOR + " WHERE impersonator_id = ?1 ORDER BY started_at DESC",
                impersonatorId).list();
    }

    /**
     * Finds all impersonation audit entries for a given target user.
     *
     * @param targetUserId
     *            the user ID who was impersonated
     * @return list of impersonation audit entries, ordered by started_at DESC
     */
    public static List<ImpersonationAudit> findByTarget(UUID targetUserId) {
        if (targetUserId == null) {
            return List.of();
        }
        return find("#" + QUERY_FIND_BY_TARGET + " WHERE target_user_id = ?1 ORDER BY started_at DESC", targetUserId)
                .list();
    }

    /**
     * Finds all active impersonation sessions (ended_at is null).
     *
     * @return list of active impersonation sessions, ordered by started_at DESC
     */
    public static List<ImpersonationAudit> findActive() {
        return find("#" + QUERY_FIND_ACTIVE + " WHERE ended_at IS NULL ORDER BY started_at DESC").list();
    }

    /**
     * Finds the active impersonation session for a specific impersonator and target.
     *
     * @param impersonatorId
     *            the user ID performing the impersonation
     * @param targetUserId
     *            the user ID being impersonated
     * @return Optional containing the active session if found
     */
    public static Optional<ImpersonationAudit> findActiveSession(UUID impersonatorId, UUID targetUserId) {
        if (impersonatorId == null || targetUserId == null) {
            return Optional.empty();
        }
        return find("impersonator_id = ?1 AND target_user_id = ?2 AND ended_at IS NULL", impersonatorId, targetUserId)
                .firstResultOptional();
    }

    /**
     * Creates a new impersonation audit entry for a started session.
     *
     * @param impersonatorId
     *            the user ID performing the impersonation
     * @param targetUserId
     *            the user ID being impersonated
     * @param ipAddress
     *            IP address of the impersonator
     * @param userAgent
     *            user agent string of the impersonator
     * @return persisted impersonation audit entry with generated UUID
     */
    public static ImpersonationAudit startSession(UUID impersonatorId, UUID targetUserId, String ipAddress,
            String userAgent) {
        ImpersonationAudit audit = new ImpersonationAudit();
        audit.impersonatorId = impersonatorId;
        audit.targetUserId = targetUserId;
        audit.startedAt = Instant.now();
        audit.ipAddress = ipAddress;
        audit.userAgent = userAgent;
        audit.createdAt = Instant.now();

        QuarkusTransaction.requiringNew().run(() -> audit.persist());
        LOG.infof("Started impersonation session: impersonator=%s, target=%s, id=%s", impersonatorId, targetUserId,
                audit.id);
        return audit;
    }

    /**
     * Ends this impersonation session by setting the ended_at timestamp.
     */
    public void endSession() {
        this.endedAt = Instant.now();
        QuarkusTransaction.requiringNew().run(() -> this.persist());
        LOG.infof("Ended impersonation session: impersonator=%s, target=%s, id=%s", this.impersonatorId,
                this.targetUserId, this.id);
    }

    /**
     * Immutable snapshot record for JSON serialization in API responses.
     */
    public record ImpersonationAuditSnapshot(@JsonProperty("id") UUID id,
            @JsonProperty("impersonator_id") UUID impersonatorId, @JsonProperty("target_user_id") UUID targetUserId,
            @JsonProperty("started_at") Instant startedAt, @JsonProperty("ended_at") Instant endedAt,
            @JsonProperty("ip_address") String ipAddress, @JsonProperty("user_agent") String userAgent,
            @JsonProperty("created_at") Instant createdAt) {
    }

    /**
     * Creates a snapshot of this impersonation audit entry for API responses.
     *
     * @return immutable snapshot record
     */
    public ImpersonationAuditSnapshot toSnapshot() {
        return new ImpersonationAuditSnapshot(this.id, this.impersonatorId, this.targetUserId, this.startedAt,
                this.endedAt, this.ipAddress, this.userAgent, this.createdAt);
    }
}
