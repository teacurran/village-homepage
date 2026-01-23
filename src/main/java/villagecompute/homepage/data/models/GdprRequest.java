package villagecompute.homepage.data.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Audit trail entity for GDPR data access and erasure requests per Policy P1.
 *
 * <p>
 * Tracks user-initiated data export (GDPR Article 15 - Right to access) and account deletion (GDPR Article 17 - Right
 * to erasure) requests with full audit trail including IP address, user agent, and status transitions.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code user_id} (UUID, FK) - Reference to user requesting export/deletion</li>
 * <li>{@code request_type} (TEXT) - EXPORT or DELETION</li>
 * <li>{@code status} (TEXT) - PENDING, PROCESSING, COMPLETED, FAILED</li>
 * <li>{@code job_id} (BIGINT, FK) - Reference to delayed_jobs table</li>
 * <li>{@code requested_at} (TIMESTAMPTZ) - When request was submitted</li>
 * <li>{@code completed_at} (TIMESTAMPTZ) - When request completed</li>
 * <li>{@code signed_url} (TEXT) - R2 signed URL for export downloads (exports only)</li>
 * <li>{@code signed_url_expires_at} (TIMESTAMPTZ) - Expiration of signed URL</li>
 * <li>{@code error_message} (TEXT) - Error details if failed</li>
 * <li>{@code ip_address} (INET) - User's IP address at time of request (P1 compliance)</li>
 * <li>{@code user_agent} (TEXT) - User's browser/client user agent</li>
 * <li>{@code created_at} (TIMESTAMPTZ) - Record creation timestamp</li>
 * <li>{@code updated_at} (TIMESTAMPTZ) - Record last update timestamp</li>
 * </ul>
 *
 * @see User for user entity
 * @see villagecompute.homepage.jobs.GdprExportJobHandler for export job implementation
 * @see villagecompute.homepage.jobs.GdprDeletionJobHandler for deletion job implementation
 */
@Entity
@Table(
        name = "gdpr_requests")
@NamedQuery(
        name = GdprRequest.QUERY_FIND_BY_USER,
        query = GdprRequest.JPQL_FIND_BY_USER)
@NamedQuery(
        name = GdprRequest.QUERY_FIND_BY_STATUS,
        query = GdprRequest.JPQL_FIND_BY_STATUS)
@NamedQuery(
        name = GdprRequest.QUERY_FIND_PENDING_EXPORTS,
        query = GdprRequest.JPQL_FIND_PENDING_EXPORTS)
public class GdprRequest extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(GdprRequest.class);

    public static final String JPQL_FIND_BY_USER = "FROM GdprRequest WHERE userId = ?1 ORDER BY requestedAt DESC";
    public static final String QUERY_FIND_BY_USER = "GdprRequest.findByUser";

    public static final String JPQL_FIND_BY_STATUS = "FROM GdprRequest WHERE status = ?1 ORDER BY requestedAt DESC";
    public static final String QUERY_FIND_BY_STATUS = "GdprRequest.findByStatus";

    public static final String JPQL_FIND_PENDING_EXPORTS = "FROM GdprRequest WHERE requestType = ?1 AND status = ?2 AND signedUrlExpiresAt < CURRENT_TIMESTAMP";
    public static final String QUERY_FIND_PENDING_EXPORTS = "GdprRequest.findPendingExports";

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
            name = "request_type",
            nullable = false)
    @Enumerated(EnumType.STRING)
    public RequestType requestType;

    @Column(
            name = "status",
            nullable = false)
    @Enumerated(EnumType.STRING)
    public RequestStatus status;

    @Column(
            name = "job_id")
    public Long jobId;

    @Column(
            name = "requested_at",
            nullable = false)
    public Instant requestedAt;

    @Column(
            name = "completed_at")
    public Instant completedAt;

    @Column(
            name = "signed_url")
    public String signedUrl;

    @Column(
            name = "signed_url_expires_at")
    public Instant signedUrlExpiresAt;

    @Column(
            name = "error_message")
    public String errorMessage;

    @Column(
            name = "ip_address",
            nullable = false)
    public String ipAddress;

    @Column(
            name = "user_agent",
            nullable = false)
    public String userAgent;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * GDPR request types per Policy P1.
     */
    public enum RequestType {
        /**
         * GDPR Article 15 - Right to access personal data (data portability).
         */
        EXPORT,

        /**
         * GDPR Article 17 - Right to erasure (right to be forgotten).
         */
        DELETION
    }

    /**
     * Request lifecycle statuses.
     */
    public enum RequestStatus {
        /**
         * Request created, job enqueued, awaiting processing.
         */
        PENDING,

        /**
         * Job actively executing export/deletion operation.
         */
        PROCESSING,

        /**
         * Request successfully completed (export ready or deletion finished).
         */
        COMPLETED,

        /**
         * Request failed due to error (see error_message field).
         */
        FAILED
    }

    /**
     * Finds all GDPR requests for a specific user, ordered by most recent first.
     *
     * @param userId
     *            the user's ID
     * @return list of GDPR requests for this user
     */
    public static List<GdprRequest> findByUser(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return find(JPQL_FIND_BY_USER, userId).list();
    }

    /**
     * Finds all GDPR requests with a specific status.
     *
     * @param status
     *            the request status
     * @return list of requests with this status
     */
    public static List<GdprRequest> findByStatus(RequestStatus status) {
        if (status == null) {
            return List.of();
        }
        return find(JPQL_FIND_BY_STATUS, status).list();
    }

    /**
     * Finds pending export requests where signed URLs have expired and need regeneration.
     *
     * @return list of export requests with expired signed URLs
     */
    public static List<GdprRequest> findExpiredExports() {
        return find(JPQL_FIND_PENDING_EXPORTS, RequestType.EXPORT, RequestStatus.COMPLETED).list();
    }

    /**
     * Finds the most recent export request for a user, if any.
     *
     * @param userId
     *            the user's ID
     * @return the most recent export request, or empty if none exists
     */
    public static Optional<GdprRequest> findLatestExport(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return find("userId = ?1 AND requestType = ?2 ORDER BY requestedAt DESC", userId, RequestType.EXPORT)
                .firstResultOptional();
    }

    /**
     * Finds the most recent deletion request for a user, if any.
     *
     * @param userId
     *            the user's ID
     * @return the most recent deletion request, or empty if none exists
     */
    public static Optional<GdprRequest> findLatestDeletion(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return find("userId = ?1 AND requestType = ?2 ORDER BY requestedAt DESC", userId, RequestType.DELETION)
                .firstResultOptional();
    }

    /**
     * Creates a new GDPR request with PENDING status.
     *
     * @param userId
     *            the user requesting export/deletion
     * @param requestType
     *            EXPORT or DELETION
     * @param ipAddress
     *            user's IP address at time of request
     * @param userAgent
     *            user's browser/client user agent
     * @param jobId
     *            the delayed job ID
     * @return persisted GDPR request record
     */
    public static GdprRequest create(UUID userId, RequestType requestType, String ipAddress, String userAgent,
            Long jobId) {
        GdprRequest request = new GdprRequest();
        request.userId = userId;
        request.requestType = requestType;
        request.status = RequestStatus.PENDING;
        request.jobId = jobId;
        request.requestedAt = Instant.now();
        request.ipAddress = ipAddress;
        request.userAgent = userAgent;
        request.createdAt = Instant.now();
        request.updatedAt = Instant.now();

        request.persist();
        LOG.infof("Created GDPR %s request %s for user %s (job: %d)", requestType, request.id, userId, jobId);
        return request;
    }

    /**
     * Marks request as PROCESSING and records timestamp.
     */
    public void markProcessing() {
        this.status = RequestStatus.PROCESSING;
        this.updatedAt = Instant.now();
        this.persist();
        LOG.infof("GDPR request %s marked as PROCESSING", this.id);
    }

    /**
     * Marks request as COMPLETED with optional signed URL for exports.
     *
     * @param signedUrl
     *            R2 signed URL (exports only, null for deletions)
     * @param signedUrlExpiresAt
     *            expiration timestamp for signed URL
     */
    public void markCompleted(String signedUrl, Instant signedUrlExpiresAt) {
        this.status = RequestStatus.COMPLETED;
        this.completedAt = Instant.now();
        this.signedUrl = signedUrl;
        this.signedUrlExpiresAt = signedUrlExpiresAt;
        this.updatedAt = Instant.now();
        this.persist();
        LOG.infof("GDPR request %s marked as COMPLETED", this.id);
    }

    /**
     * Marks request as FAILED with error message.
     *
     * @param errorMessage
     *            the error description
     */
    public void markFailed(String errorMessage) {
        this.status = RequestStatus.FAILED;
        this.completedAt = Instant.now();
        this.errorMessage = errorMessage;
        this.updatedAt = Instant.now();
        this.persist();
        LOG.errorf("GDPR request %s marked as FAILED: %s", this.id, errorMessage);
    }

    /**
     * Immutable snapshot record for JSON serialization in compliance reports.
     */
    public record RequestSnapshot(@JsonProperty("request_id") UUID requestId, @JsonProperty("user_id") UUID userId,
            @JsonProperty("request_type") String requestType, @JsonProperty("status") String status,
            @JsonProperty("requested_at") Instant requestedAt, @JsonProperty("completed_at") Instant completedAt,
            @JsonProperty("signed_url") String signedUrl,
            @JsonProperty("signed_url_expires_at") Instant signedUrlExpiresAt,
            @JsonProperty("ip_address") String ipAddress, @JsonProperty("error_message") String errorMessage) {
    }

    /**
     * Creates a snapshot of this request for compliance reporting.
     *
     * @return immutable snapshot record
     */
    public RequestSnapshot toSnapshot() {
        return new RequestSnapshot(this.id, this.userId, this.requestType.name(), this.status.name(), this.requestedAt,
                this.completedAt, this.signedUrl, this.signedUrlExpiresAt, this.ipAddress, this.errorMessage);
    }
}
