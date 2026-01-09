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
 * Rate limit violation tracking entity implementing the Panache ActiveRecord pattern.
 *
 * <p>
 * Records rate limit violations for abuse detection and analytics. Violations are upserted when they occur within the
 * same window (incrementing violation_count), or created as new records when outside the window.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary key identifier</li>
 * <li>{@code user_id} (BIGINT) - Authenticated user ID (NULL for anonymous)</li>
 * <li>{@code ip_address} (INET) - Source IP address for geoblocking/analysis</li>
 * <li>{@code action_type} (TEXT) - Action that triggered violation</li>
 * <li>{@code endpoint} (TEXT) - HTTP endpoint path</li>
 * <li>{@code violation_count} (INT) - Number of violations since first_violation_at</li>
 * <li>{@code first_violation_at} (TIMESTAMPTZ) - Timestamp of first violation in window</li>
 * <li>{@code last_violation_at} (TIMESTAMPTZ) - Timestamp of most recent violation</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P14/F14.2: Violation logging for audit trails</li>
 * </ul>
 *
 * @see RateLimitConfig for rate limit rules
 */
@Entity
@Table(
        name = "rate_limit_violations")
public class RateLimitViolation extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(RateLimitViolation.class);

    @Id
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "user_id")
    public Long userId;

    @Column(
            name = "ip_address")
    public String ipAddress;

    @Column(
            name = "action_type",
            nullable = false)
    public String actionType;

    @Column(
            nullable = false)
    public String endpoint;

    @Column(
            name = "violation_count",
            nullable = false)
    public int violationCount;

    @Column(
            name = "first_violation_at",
            nullable = false)
    public Instant firstViolationAt;

    @Column(
            name = "last_violation_at",
            nullable = false)
    public Instant lastViolationAt;

    /**
     * Finds a recent violation record for the given user/IP and action.
     *
     * <p>
     * "Recent" is defined as within the last 24 hours to support multi-window violation tracking.
     *
     * @param userId
     *            user ID (nullable)
     * @param ipAddress
     *            source IP address (nullable)
     * @param actionType
     *            action identifier
     * @return Optional containing the violation if found, empty otherwise
     */
    public static Optional<RateLimitViolation> findRecentViolation(Long userId, String ipAddress, String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return Optional.empty();
        }

        Instant cutoff = Instant.now().minusSeconds(86400); // 24 hours

        if (userId != null) {
            return find("userId = ?1 AND actionType = ?2 AND lastViolationAt > ?3 ORDER BY lastViolationAt DESC",
                    userId, actionType, cutoff).firstResultOptional();
        } else if (ipAddress != null && !ipAddress.isBlank()) {
            return find("ipAddress = ?1 AND actionType = ?2 AND lastViolationAt > ?3 ORDER BY lastViolationAt DESC",
                    ipAddress, actionType, cutoff).firstResultOptional();
        }

        return Optional.empty();
    }

    /**
     * Retrieves all violations for a specific user (admin audit view).
     *
     * @param userId
     *            the user ID
     * @param limit
     *            maximum results to return
     * @return List of violations, ordered by last_violation_at DESC
     */
    public static List<RateLimitViolation> findByUser(Long userId, int limit) {
        if (userId == null) {
            return List.of();
        }
        return find("userId = ?1 ORDER BY lastViolationAt DESC", userId).page(0, limit).list();
    }

    /**
     * Retrieves all violations for a specific IP address (admin audit view).
     *
     * @param ipAddress
     *            the IP address
     * @param limit
     *            maximum results to return
     * @return List of violations, ordered by last_violation_at DESC
     */
    public static List<RateLimitViolation> findByIp(String ipAddress, int limit) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return List.of();
        }
        return find("ipAddress = ?1 ORDER BY lastViolationAt DESC", ipAddress).page(0, limit).list();
    }

    /**
     * Retrieves recent violations across all users (admin dashboard).
     *
     * @param limit
     *            maximum results to return
     * @return List of violations, ordered by last_violation_at DESC
     */
    public static List<RateLimitViolation> findRecent(int limit) {
        return find("ORDER BY lastViolationAt DESC").page(0, limit).list();
    }

    /**
     * Creates a new violation record.
     *
     * @param userId
     *            user ID (nullable for anonymous)
     * @param ipAddress
     *            source IP
     * @param actionType
     *            action identifier
     * @param endpoint
     *            HTTP endpoint path
     * @return new persisted violation
     */
    public static RateLimitViolation create(Long userId, String ipAddress, String actionType, String endpoint) {
        RateLimitViolation violation = new RateLimitViolation();
        violation.id = UUID.randomUUID();
        violation.userId = userId;
        violation.ipAddress = ipAddress;
        violation.actionType = actionType;
        violation.endpoint = endpoint;
        violation.violationCount = 1;
        violation.firstViolationAt = Instant.now();
        violation.lastViolationAt = Instant.now();
        violation.persist();
        return violation;
    }

    /**
     * Increments the violation count and updates last_violation_at timestamp.
     */
    public void incrementViolation() {
        this.violationCount++;
        this.lastViolationAt = Instant.now();
    }

    /**
     * Upserts a violation record (increments if exists within 24h window, creates new otherwise).
     *
     * @param userId
     *            user ID (nullable)
     * @param ipAddress
     *            source IP
     * @param actionType
     *            action identifier
     * @param endpoint
     *            HTTP endpoint path
     */
    public static void upsertViolation(Long userId, String ipAddress, String actionType, String endpoint) {
        Optional<RateLimitViolation> existing = findRecentViolation(userId, ipAddress, actionType);

        if (existing.isPresent()) {
            RateLimitViolation violation = existing.get();
            violation.incrementViolation();
            LOG.debugf("Incremented violation count: userId=%s ip=%s action=%s count=%d", userId, ipAddress, actionType,
                    violation.violationCount);
        } else {
            create(userId, ipAddress, actionType, endpoint);
            LOG.debugf("Created new violation record: userId=%s ip=%s action=%s", userId, ipAddress, actionType);
        }
    }
}
