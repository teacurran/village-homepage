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
 * Email delivery log entity for async email queue and delivery tracking (Feature I5.T3).
 *
 * <p>
 * Records all queued and sent emails with retry tracking and error logging. Enables asynchronous email delivery via
 * background job processing (EmailDeliveryJob).
 *
 * <h3>Schema Mapping:</h3>
 * <ul>
 * <li>id (UUID, PK) - Primary identifier</li>
 * <li>user_id (UUID, FK, nullable) - Reference to users (nullable for ops alerts)</li>
 * <li>email_address (VARCHAR, NOT NULL) - Recipient email address</li>
 * <li>template_name (VARCHAR, NOT NULL) - Template identifier for logging</li>
 * <li>subject (VARCHAR, NOT NULL) - Email subject line</li>
 * <li>html_body (TEXT) - Rendered HTML email body</li>
 * <li>text_body (TEXT) - Rendered plain text email body</li>
 * <li>status (VARCHAR, NOT NULL) - Delivery status (QUEUED, SENDING, SENT, FAILED, BOUNCED)</li>
 * <li>sent_at (TIMESTAMPTZ) - Successful delivery timestamp</li>
 * <li>error_message (TEXT) - Error details for failed deliveries</li>
 * <li>retry_count (INT) - Number of retry attempts (max 3)</li>
 * <li>created_at (TIMESTAMPTZ, NOT NULL) - Queue timestamp</li>
 * </ul>
 *
 * <h3>Named Queries:</h3>
 * <ul>
 * <li>{@link #QUERY_FIND_BY_USER_ID} - Find all delivery logs for a user</li>
 * <li>{@link #QUERY_FIND_FAILED} - Find all failed deliveries (status = FAILED)</li>
 * <li>{@link #QUERY_FIND_QUEUED} - Find queued deliveries for job processing (status = QUEUED, ordered by
 * created_at)</li>
 * </ul>
 *
 * <h3>Delivery Flow:</h3>
 * <ol>
 * <li><b>Queue:</b> NotificationService creates log with status = QUEUED</li>
 * <li><b>Process:</b> EmailDeliveryJob queries QUEUED logs every 1 minute</li>
 * <li><b>Send:</b> Job updates status to SENDING, calls EmailService</li>
 * <li><b>Success:</b> Job updates status to SENT, sets sentAt timestamp</li>
 * <li><b>Failure:</b> Job increments retryCount, sets status to QUEUED (if retries < 3) or FAILED (if retries >=
 * 3)</li>
 * </ol>
 *
 * <h3>Retry Strategy:</h3>
 * <ul>
 * <li>Maximum retry attempts: 3</li>
 * <li>Retry delay: Exponential backoff (1s, 2s, 4s) handled by EmailService</li>
 * <li>Terminal states: SENT (success), FAILED (max retries exceeded), BOUNCED (permanent failure)</li>
 * </ul>
 *
 * <h3>Privacy Compliance:</h3>
 * <ul>
 * <li>P1: Data minimization (only email delivery metadata)</li>
 * <li>P2: Cascade delete on user deletion (user_id FK with ON DELETE CASCADE)</li>
 * <li>P7: Data retention (logs deleted with user account)</li>
 * </ul>
 *
 * @see EmailService for email sending with retry logic
 * @see NotificationService for email queueing via createUserNotification
 * @see EmailDeliveryJob for background processing of queued emails
 */
@Entity
@Table(
        name = "email_delivery_logs")
@NamedQuery(
        name = EmailDeliveryLog.QUERY_FIND_BY_USER_ID,
        query = "FROM EmailDeliveryLog WHERE userId = :userId ORDER BY createdAt DESC")
@NamedQuery(
        name = EmailDeliveryLog.QUERY_FIND_FAILED,
        query = "FROM EmailDeliveryLog WHERE status = 'FAILED' ORDER BY createdAt DESC")
@NamedQuery(
        name = EmailDeliveryLog.QUERY_FIND_QUEUED,
        query = "FROM EmailDeliveryLog WHERE status = 'QUEUED' ORDER BY createdAt ASC")
public class EmailDeliveryLog extends PanacheEntityBase {

    /**
     * Named query constant: Find all delivery logs for a user. Query:
     * {@code FROM EmailDeliveryLog WHERE userId = :userId ORDER BY createdAt DESC}
     */
    public static final String QUERY_FIND_BY_USER_ID = "EmailDeliveryLog.findByUserId";

    /**
     * Named query constant: Find all failed deliveries. Query:
     * {@code FROM EmailDeliveryLog WHERE status = 'FAILED' ORDER BY createdAt DESC}
     */
    public static final String QUERY_FIND_FAILED = "EmailDeliveryLog.findFailed";

    /**
     * Named query constant: Find queued deliveries for job processing. Query:
     * {@code FROM EmailDeliveryLog WHERE status = 'QUEUED' ORDER BY createdAt ASC}
     */
    public static final String QUERY_FIND_QUEUED = "EmailDeliveryLog.findQueued";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "user_id")
    public UUID userId; // Nullable for ops alerts

    @Column(
            name = "email_address",
            nullable = false)
    public String emailAddress;

    @Column(
            name = "template_name",
            nullable = false)
    public String templateName;

    @Column(
            nullable = false,
            length = 500)
    public String subject;

    @Column(
            name = "html_body",
            columnDefinition = "TEXT")
    public String htmlBody;

    @Column(
            name = "text_body",
            columnDefinition = "TEXT")
    public String textBody;

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 20)
    public DeliveryStatus status = DeliveryStatus.QUEUED;

    @Column(
            name = "sent_at")
    public Instant sentAt;

    @Column(
            name = "error_message",
            columnDefinition = "TEXT")
    public String errorMessage;

    @Column(
            name = "retry_count",
            nullable = false)
    public int retryCount = 0;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    /**
     * Email delivery status enumeration.
     *
     * <p>
     * <b>Status Lifecycle:</b>
     * <ul>
     * <li><b>QUEUED:</b> Email created by NotificationService, awaiting job processing</li>
     * <li><b>SENDING:</b> Job actively sending email via EmailService (transient state)</li>
     * <li><b>SENT:</b> Email successfully delivered to SMTP server (terminal success state)</li>
     * <li><b>FAILED:</b> Email delivery failed after max retries (terminal failure state)</li>
     * <li><b>BOUNCED:</b> Email rejected by recipient server (permanent failure, no retry)</li>
     * </ul>
     */
    public enum DeliveryStatus {
        /**
         * Email queued for delivery, awaiting job processing.
         */
        QUEUED,

        /**
         * Email actively being sent to SMTP server (transient state).
         */
        SENDING,

        /**
         * Email successfully delivered to SMTP server (terminal success).
         */
        SENT,

        /**
         * Email delivery failed after max retries (terminal failure).
         */
        FAILED,

        /**
         * Email rejected by recipient server (permanent failure, no retry).
         */
        BOUNCED
    }

    /**
     * Finds all delivery logs for a user, ordered by creation date (newest first).
     *
     * @param userId
     *            the user's UUID
     * @return List of all delivery logs (empty list if userId is null)
     */
    public static List<EmailDeliveryLog> findByUserId(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return find("#" + QUERY_FIND_BY_USER_ID, Parameters.with("userId", userId)).list();
    }

    /**
     * Finds all failed email deliveries (status = FAILED), ordered by creation date (newest first).
     *
     * <p>
     * Used by admin dashboard to monitor email delivery issues and investigate failures.
     *
     * @return List of all failed deliveries (empty list if none)
     */
    public static List<EmailDeliveryLog> findFailed() {
        return find("#" + QUERY_FIND_FAILED).list();
    }

    /**
     * Finds queued email deliveries for job processing (status = QUEUED), ordered by creation date (oldest first).
     *
     * <p>
     * Called by EmailDeliveryJob every 1 minute to process pending emails. FIFO ordering ensures emails are sent in the
     * order they were queued.
     *
     * @param limit
     *            maximum number of queued emails to return (batch size)
     * @return List of queued deliveries (empty list if none)
     */
    public static List<EmailDeliveryLog> findQueued(int limit) {
        return find("#" + QUERY_FIND_QUEUED).page(0, limit).list();
    }
}
