package villagecompute.homepage.data.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Audit trail entity for anonymous-to-authenticated account merges per Policy P1 (GDPR/CCPA compliance).
 *
 * <p>
 * Records explicit user consent with timestamp, IP address, user agent, and merged data summary for 90-day retention.
 * Soft-deleted anonymous records are purged 90 days after consent via the {@code ACCOUNT_MERGE_CLEANUP} scheduled job.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code anonymous_user_id} (UUID, FK) - Reference to soft-deleted anonymous user</li>
 * <li>{@code authenticated_user_id} (UUID, FK) - Reference to target authenticated user</li>
 * <li>{@code merged_data_summary} (JSONB) - Summary of merged data (preferences, layout, topics)</li>
 * <li>{@code consent_given} (BOOLEAN) - Whether user consented to merge</li>
 * <li>{@code consent_timestamp} (TIMESTAMPTZ) - When consent was recorded</li>
 * <li>{@code consent_policy_version} (TEXT) - Privacy policy version at time of consent</li>
 * <li>{@code ip_address} (INET) - User's IP address at time of consent (P1 compliance)</li>
 * <li>{@code user_agent} (TEXT) - User's browser/client user agent</li>
 * <li>{@code purge_after} (TIMESTAMPTZ) - When anonymous user record should be hard-deleted (typically +90 days)</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * </ul>
 *
 * @see User for user entity
 * @see villagecompute.homepage.jobs.AccountMergeCleanupJobHandler for cleanup job implementation
 */
@Entity
@Table(
        name = "account_merge_audit")
@NamedQuery(
        name = AccountMergeAudit.QUERY_FIND_PENDING_PURGE,
        query = AccountMergeAudit.JPQL_FIND_PENDING_PURGE)
@NamedQuery(
        name = AccountMergeAudit.QUERY_FIND_BY_AUTHENTICATED_USER,
        query = AccountMergeAudit.JPQL_FIND_BY_AUTHENTICATED_USER)
public class AccountMergeAudit extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(AccountMergeAudit.class);

    public static final String JPQL_FIND_PENDING_PURGE = "SELECT a FROM AccountMergeAudit a WHERE a.purgeAfter <= CURRENT_TIMESTAMP ORDER BY a.purgeAfter ASC";
    public static final String QUERY_FIND_PENDING_PURGE = "AccountMergeAudit.findPendingPurge";

    public static final String JPQL_FIND_BY_AUTHENTICATED_USER = "SELECT a FROM AccountMergeAudit a WHERE a.authenticatedUserId = :authenticatedUserId ORDER BY a.consentTimestamp DESC";
    public static final String QUERY_FIND_BY_AUTHENTICATED_USER = "AccountMergeAudit.findByAuthenticatedUser";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "anonymous_user_id",
            nullable = false)
    public UUID anonymousUserId;

    @Column(
            name = "authenticated_user_id",
            nullable = false)
    public UUID authenticatedUserId;

    @Column(
            name = "merged_data_summary",
            nullable = false,
            columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> mergedDataSummary;

    @Column(
            name = "consent_given",
            nullable = false)
    public boolean consentGiven;

    @Column(
            name = "consent_timestamp",
            nullable = false)
    public Instant consentTimestamp;

    @Column(
            name = "consent_policy_version",
            nullable = false)
    public String consentPolicyVersion;

    @Column(
            name = "ip_address",
            nullable = false)
    public String ipAddress;

    @Column(
            name = "user_agent",
            nullable = false)
    public String userAgent;

    @Column(
            name = "purge_after",
            nullable = false)
    public Instant purgeAfter;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    /**
     * Finds all audit records where the purge_after timestamp has passed and anonymous user should be hard-deleted.
     *
     * <p>
     * Used by {@code AccountMergeCleanupJobHandler} to identify records eligible for cleanup.
     *
     * @return list of audit records ready for purge
     */
    public static List<AccountMergeAudit> findPendingPurge() {
        return find(JPQL_FIND_PENDING_PURGE).list();
    }

    /**
     * Finds all merge audit records for a specific authenticated user.
     *
     * <p>
     * Used for compliance audits and data export requests (GDPR right to access).
     *
     * @param authenticatedUserId
     *            the authenticated user's ID
     * @return list of merge audit records for this user, ordered by consent timestamp descending
     */
    public static List<AccountMergeAudit> findByAuthenticatedUser(UUID authenticatedUserId) {
        if (authenticatedUserId == null) {
            return List.of();
        }
        return find(JPQL_FIND_BY_AUTHENTICATED_USER,
                io.quarkus.panache.common.Parameters.with("authenticatedUserId", authenticatedUserId)).list();
    }

    /**
     * Creates a new merge audit record with 90-day retention period.
     *
     * @param anonymousUserId
     *            ID of the anonymous user being merged
     * @param authenticatedUserId
     *            ID of the target authenticated user
     * @param mergedDataSummary
     *            summary of merged data (preferences, layout, topics)
     * @param consentGiven
     *            whether user consented to the merge
     * @param ipAddress
     *            user's IP address at time of consent
     * @param userAgent
     *            user's browser/client user agent
     * @param policyVersion
     *            privacy policy version at time of consent
     * @return persisted audit record
     */
    public static AccountMergeAudit create(UUID anonymousUserId, UUID authenticatedUserId,
            Map<String, Object> mergedDataSummary, boolean consentGiven, String ipAddress, String userAgent,
            String policyVersion) {
        AccountMergeAudit audit = new AccountMergeAudit();
        audit.anonymousUserId = anonymousUserId;
        audit.authenticatedUserId = authenticatedUserId;
        audit.mergedDataSummary = mergedDataSummary;
        audit.consentGiven = consentGiven;
        audit.consentTimestamp = Instant.now();
        audit.consentPolicyVersion = policyVersion;
        audit.ipAddress = ipAddress;
        audit.userAgent = userAgent;

        // Policy P1: 90-day retention period for soft-deleted anonymous accounts
        audit.purgeAfter = audit.consentTimestamp.plusSeconds(90 * 24 * 60 * 60);
        audit.createdAt = Instant.now();

        audit.persist();
        LOG.infof(
                "Created account merge audit for anonymous user %s → authenticated user %s (consent: %s, purge after: %s)",
                anonymousUserId, authenticatedUserId, consentGiven, audit.purgeAfter);
        return audit;
    }

    /**
     * Immutable snapshot record for JSON serialization in compliance reports.
     */
    public record AuditSnapshot(@JsonProperty("audit_id") UUID auditId,
            @JsonProperty("anonymous_user_id") UUID anonymousUserId,
            @JsonProperty("authenticated_user_id") UUID authenticatedUserId,
            @JsonProperty("merged_data_summary") Map<String, Object> mergedDataSummary,
            @JsonProperty("consent_given") boolean consentGiven,
            @JsonProperty("consent_timestamp") Instant consentTimestamp,
            @JsonProperty("consent_policy_version") String consentPolicyVersion,
            @JsonProperty("ip_address") String ipAddress, @JsonProperty("purge_after") Instant purgeAfter) {
    }

    /**
     * Creates a snapshot of this audit record for compliance reporting.
     *
     * @return immutable snapshot record
     */
    public AuditSnapshot toSnapshot() {
        return new AuditSnapshot(this.id, this.anonymousUserId, this.authenticatedUserId, this.mergedDataSummary,
                this.consentGiven, this.consentTimestamp, this.consentPolicyVersion, this.ipAddress, this.purgeAfter);
    }
}
