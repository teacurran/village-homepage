package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Parameters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Email bounce tracking entity for DSN (Delivery Status Notification) parsing and threshold-based email disabling
 * (Feature I5.T5).
 *
 * <p>
 * Records all email bounces (hard and soft) with diagnostic information extracted from RFC 3464 DSN messages. Enables
 * threshold-based automatic disabling of email delivery to problematic addresses.
 *
 * <h3>Schema Mapping:</h3>
 * <ul>
 * <li>id (UUID, PK) - Primary identifier</li>
 * <li>user_id (UUID, FK, nullable) - Reference to users (nullable for non-user emails)</li>
 * <li>email_address (VARCHAR, NOT NULL) - Bounced email address</li>
 * <li>bounce_type (VARCHAR, NOT NULL) - HARD (5.x.x permanent) or SOFT (4.x.x temporary)</li>
 * <li>bounce_reason (TEXT) - Human-readable reason extracted from DSN</li>
 * <li>diagnostic_code (VARCHAR) - DSN status code (e.g., 5.1.1, 4.2.2)</li>
 * <li>created_at (TIMESTAMPTZ, NOT NULL) - Bounce timestamp</li>
 * </ul>
 *
 * <h3>Named Queries:</h3>
 * <ul>
 * <li>{@link #QUERY_FIND_BY_USER_ID} - Find all bounces for a user</li>
 * <li>{@link #QUERY_FIND_RECENT_BY_EMAIL} - Find recent bounces for an email address (last N days)</li>
 * <li>{@link #QUERY_COUNT_RECENT_BY_EMAIL} - Count recent bounces for an email address (last N days)</li>
 * </ul>
 *
 * <h3>Bounce Classification:</h3>
 * <ul>
 * <li><b>HARD (5.x.x):</b> Permanent failures - user unknown, domain invalid, blocked. Immediately disable email
 * delivery.</li>
 * <li><b>SOFT (4.x.x):</b> Temporary failures - mailbox full, connection timeout, greylisting. Disable after 5
 * consecutive bounces.</li>
 * </ul>
 *
 * <h3>Common Bounce Codes:</h3>
 * <ul>
 * <li>5.1.1 - Bad destination mailbox address (user unknown)</li>
 * <li>5.1.2 - Bad destination system address (domain does not exist)</li>
 * <li>5.2.1 - Mailbox disabled, not accepting messages</li>
 * <li>4.2.2 - Mailbox full (over quota)</li>
 * <li>4.4.1 - Connection timed out (temporary network issue)</li>
 * <li>4.7.1 - Delivery not authorized (temporary greylisting)</li>
 * </ul>
 *
 * <h3>Threshold Logic:</h3>
 * <ol>
 * <li>Hard bounce (5.x.x): Immediately set user.emailDisabled = true</li>
 * <li>Soft bounce (4.x.x): Set user.emailDisabled = true after 5 consecutive bounces within 30 days</li>
 * <li>Consecutive: Reset count on successful delivery (not tracked by this entity)</li>
 * </ol>
 *
 * <h3>Privacy Compliance:</h3>
 * <ul>
 * <li>P1: Data minimization (only bounce metadata for deliverability)</li>
 * <li>P2: Cascade delete on user deletion (user_id FK with ON DELETE CASCADE)</li>
 * <li>P7: Data retention (bounces deleted with user account; old bounces ignored for threshold)</li>
 * </ul>
 *
 * @see BounceHandlingService for DSN parsing and threshold logic
 * @see EmailDeliveryLog for email delivery queue and status tracking
 * @see User for emailDisabled flag
 */
@Entity
@Table(
        name = "email_bounces")
@NamedQuery(
        name = EmailBounce.QUERY_FIND_BY_USER_ID,
        query = "FROM EmailBounce WHERE userId = :userId ORDER BY createdAt DESC")
@NamedQuery(
        name = EmailBounce.QUERY_FIND_RECENT_BY_EMAIL,
        query = "FROM EmailBounce WHERE emailAddress = :emailAddress AND createdAt >= :sinceDate ORDER BY createdAt DESC")
@NamedQuery(
        name = EmailBounce.QUERY_COUNT_RECENT_BY_EMAIL,
        query = "SELECT COUNT(*) FROM EmailBounce WHERE emailAddress = :emailAddress AND createdAt >= :sinceDate")
public class EmailBounce extends PanacheEntityBase {

    /**
     * Named query constant: Find all bounces for a user. Query:
     * {@code FROM EmailBounce WHERE userId = :userId ORDER BY createdAt DESC}
     */
    public static final String QUERY_FIND_BY_USER_ID = "EmailBounce.findByUserId";

    /**
     * Named query constant: Find recent bounces for an email address. Query:
     * {@code FROM EmailBounce WHERE emailAddress = :emailAddress AND createdAt >= :sinceDate ORDER BY createdAt DESC}
     */
    public static final String QUERY_FIND_RECENT_BY_EMAIL = "EmailBounce.findRecentByEmail";

    /**
     * Named query constant: Count recent bounces for an email address. Query:
     * {@code SELECT COUNT(*) FROM EmailBounce WHERE emailAddress = :emailAddress AND createdAt >= :sinceDate}
     */
    public static final String QUERY_COUNT_RECENT_BY_EMAIL = "EmailBounce.countRecentByEmail";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "user_id")
    public UUID userId; // Nullable for non-user emails (ops alerts, system emails)

    @Column(
            name = "email_address",
            nullable = false)
    public String emailAddress;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "bounce_type",
            nullable = false,
            length = 20)
    public BounceType bounceType;

    @Column(
            name = "bounce_reason",
            columnDefinition = "TEXT")
    public String bounceReason;

    @Column(
            name = "diagnostic_code",
            length = 50)
    public String diagnosticCode;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    /**
     * Email bounce type enumeration.
     *
     * <p>
     * <b>Bounce Type Classification:</b>
     * <ul>
     * <li><b>HARD:</b> Permanent delivery failure (DSN 5.x.x) - Invalid address, domain does not exist, user unknown,
     * blocked. Immediately disables email delivery.</li>
     * <li><b>SOFT:</b> Temporary delivery failure (DSN 4.x.x) - Mailbox full, connection timeout, greylisting. Disables
     * email delivery after 5 consecutive bounces.</li>
     * </ul>
     */
    public enum BounceType {
        /**
         * Permanent delivery failure (DSN 5.x.x) - immediately disables email delivery.
         */
        HARD,

        /**
         * Temporary delivery failure (DSN 4.x.x) - disables email after 5 consecutive bounces.
         */
        SOFT
    }

    /**
     * Finds all bounces for a user, ordered by creation date (newest first).
     *
     * @param userId
     *            the user's UUID
     * @return List of all bounces (empty list if userId is null)
     */
    public static List<EmailBounce> findByUserId(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return find("#" + QUERY_FIND_BY_USER_ID, Parameters.with("userId", userId)).list();
    }

    /**
     * Finds recent bounces for an email address within the specified number of days.
     *
     * <p>
     * Used by BounceHandlingService to check bounce threshold (5 consecutive soft bounces or 1 hard bounce within 30
     * days).
     *
     * @param emailAddress
     *            the email address to query
     * @param daysBack
     *            number of days to look back (typically 30)
     * @return List of recent bounces ordered by creation date (newest first)
     */
    public static List<EmailBounce> findRecentByEmail(String emailAddress, int daysBack) {
        if (emailAddress == null || emailAddress.isBlank()) {
            return List.of();
        }
        Instant sinceDate = Instant.now().minusSeconds(daysBack * 86400L);
        return find("#" + QUERY_FIND_RECENT_BY_EMAIL,
                Parameters.with("emailAddress", emailAddress).and("sinceDate", sinceDate)).list();
    }

    /**
     * Counts recent bounces for an email address within the specified number of days.
     *
     * <p>
     * Used for quick threshold checks without loading full entity list.
     *
     * @param emailAddress
     *            the email address to query
     * @param daysBack
     *            number of days to look back (typically 30)
     * @return Count of recent bounces
     */
    public static long countRecentByEmail(String emailAddress, int daysBack) {
        if (emailAddress == null || emailAddress.isBlank()) {
            return 0;
        }
        Instant sinceDate = Instant.now().minusSeconds(daysBack * 86400L);
        return find("#" + QUERY_COUNT_RECENT_BY_EMAIL,
                Parameters.with("emailAddress", emailAddress).and("sinceDate", sinceDate)).count();
    }
}
