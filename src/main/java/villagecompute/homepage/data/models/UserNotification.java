package villagecompute.homepage.data.models;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Parameters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * User notification entity for in-app notification system.
 *
 * <p>
 * Stores notifications for user actions (listing messages, site approvals, etc.). Supports unread filtering via readAt
 * timestamp (null = unread).
 *
 * <h3>Schema Mapping:</h3>
 * <ul>
 * <li>id (UUID, PK) - Primary identifier</li>
 * <li>user_id (UUID, FK) - Reference to users</li>
 * <li>type (TEXT) - Notification type</li>
 * <li>title (TEXT) - Notification title</li>
 * <li>message (TEXT) - Notification message</li>
 * <li>action_url (TEXT) - URL for notification action (optional)</li>
 * <li>read_at (TIMESTAMPTZ) - Read timestamp (null for unread)</li>
 * <li>created_at (TIMESTAMPTZ) - Creation timestamp</li>
 * </ul>
 *
 * <h3>Named Queries:</h3>
 * <ul>
 * <li>{@link #QUERY_FIND_BY_USER_ID} - Find all notifications for a user</li>
 * <li>{@link #QUERY_FIND_UNREAD} - Find unread notifications (readAt IS NULL)</li>
 * <li>{@link #QUERY_COUNT_UNREAD} - Count unread notifications for badge display</li>
 * </ul>
 *
 * <h3>Privacy Compliance:</h3>
 * <ul>
 * <li>P1: Data minimization (only essential notification data)</li>
 * <li>P2: Cascade delete on user deletion</li>
 * <li>P7: Data retention (notifications deleted with user account)</li>
 * <li>P14: Consent-aware (respects notification preferences)</li>
 * </ul>
 */
@Entity
@Table(
        name = "user_notifications")
@NamedQuery(
        name = UserNotification.QUERY_FIND_BY_USER_ID,
        query = "FROM UserNotification WHERE userId = :userId ORDER BY createdAt DESC")
@NamedQuery(
        name = UserNotification.QUERY_FIND_UNREAD,
        query = "FROM UserNotification WHERE userId = :userId AND readAt IS NULL ORDER BY createdAt DESC")
@NamedQuery(
        name = UserNotification.QUERY_COUNT_UNREAD,
        query = "SELECT COUNT(n) FROM UserNotification n WHERE n.userId = :userId AND n.readAt IS NULL")
public class UserNotification extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(UserNotification.class);

    /**
     * Named query constant: Find all notifications for a user. Query:
     * {@code FROM UserNotification WHERE userId = :userId ORDER BY createdAt DESC}
     */
    public static final String QUERY_FIND_BY_USER_ID = "UserNotification.findByUserId";

    /**
     * Named query constant: Find unread notifications for a user. Query:
     * {@code FROM UserNotification WHERE userId = :userId AND readAt IS NULL ORDER BY createdAt DESC}
     */
    public static final String QUERY_FIND_UNREAD = "UserNotification.findUnread";

    /**
     * Named query constant: Count unread notifications for a user. Query:
     * {@code SELECT COUNT(n) FROM UserNotification n WHERE n.userId = :userId AND n.readAt IS NULL}
     */
    public static final String QUERY_COUNT_UNREAD = "UserNotification.countUnread";

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
            nullable = false)
    public String type;

    @Column(
            nullable = false)
    public String title;

    @Column(
            nullable = false)
    public String message;

    @Column(
            name = "action_url")
    public String actionUrl;

    @Column(
            name = "read_at")
    public Instant readAt;

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    /**
     * Finds all notifications for a user, ordered by creation date (newest first).
     *
     * @param userId
     *            the user's UUID
     * @return List of all notifications (empty list if userId is null)
     */
    public static List<UserNotification> findByUserId(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return find("#" + QUERY_FIND_BY_USER_ID, Parameters.with("userId", userId)).list();
    }

    /**
     * Finds unread notifications for a user (readAt IS NULL). Ordered by creation date (newest first).
     *
     * @param userId
     *            the user's UUID
     * @return List of unread notifications (empty list if userId is null)
     */
    public static List<UserNotification> findUnread(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return find("#" + QUERY_FIND_UNREAD, Parameters.with("userId", userId)).list();
    }

    /**
     * Counts unread notifications for a user (for badge display).
     *
     * @param userId
     *            the user's UUID
     * @return Count of unread notifications (0 if userId is null)
     */
    public static Long countUnread(UUID userId) {
        if (userId == null) {
            return 0L;
        }
        return find("#" + QUERY_COUNT_UNREAD, Parameters.with("userId", userId)).count();
    }

    /**
     * Creates a new notification.
     *
     * @param notification
     *            the notification to create
     * @return the persisted notification
     */
    public static UserNotification create(UserNotification notification) {
        QuarkusTransaction.requiringNew().run(() -> {
            notification.createdAt = Instant.now();
            notification.persist();
            LOG.infof("Created notification: user_id=%s, type=%s", notification.userId, notification.type);
        });
        return notification;
    }

    /**
     * Marks a notification as read.
     *
     * @param notification
     *            the notification to mark as read
     */
    public static void markAsRead(UserNotification notification) {
        QuarkusTransaction.requiringNew().run(() -> {
            notification.readAt = Instant.now();
            notification.persist();
            LOG.debugf("Marked notification as read: id=%s", notification.id);
        });
    }
}
