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
import java.util.Optional;
import java.util.UUID;

/**
 * Notification preferences entity for managing user opt-in/opt-out settings.
 *
 * <p>
 * Stores per-channel notification preferences (email, push). One preferences record per user (enforced via UNIQUE
 * constraint on user_id).
 *
 * <h3>Schema Mapping:</h3>
 * <ul>
 * <li>id (UUID, PK) - Primary identifier</li>
 * <li>user_id (UUID, FK, UNIQUE) - Reference to users</li>
 * <li>email_enabled (BOOLEAN) - Global email notification toggle</li>
 * <li>email_listing_messages (BOOLEAN) - Marketplace message notifications</li>
 * <li>email_site_approved (BOOLEAN) - Directory site approval notifications</li>
 * <li>email_site_rejected (BOOLEAN) - Directory site rejection notifications</li>
 * <li>email_digest (BOOLEAN) - Daily digest emails</li>
 * <li>created_at (TIMESTAMPTZ) - Creation timestamp</li>
 * <li>updated_at (TIMESTAMPTZ) - Update timestamp</li>
 * </ul>
 *
 * <h3>Named Queries:</h3>
 * <ul>
 * <li>{@link #QUERY_FIND_BY_USER_ID} - Find preferences for a user (returns Optional)</li>
 * </ul>
 *
 * <h3>Privacy Compliance:</h3>
 * <ul>
 * <li>P1: Data minimization (only preference flags)</li>
 * <li>P2: Cascade delete on user deletion</li>
 * <li>P7: Data retention (preferences deleted with user account)</li>
 * <li>P14: Explicit consent management (opt-in/opt-out controls)</li>
 * </ul>
 */
@Entity
@Table(
        name = "notification_preferences")
@NamedQuery(
        name = NotificationPreferences.QUERY_FIND_BY_USER_ID,
        query = "FROM NotificationPreferences WHERE userId = :userId")
public class NotificationPreferences extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(NotificationPreferences.class);

    /**
     * Named query constant: Find notification preferences for a user. Query:
     * {@code FROM NotificationPreferences WHERE userId = :userId}
     */
    public static final String QUERY_FIND_BY_USER_ID = "NotificationPreferences.findByUserId";

    @Id
    @GeneratedValue
    @Column(
            nullable = false)
    public UUID id;

    @Column(
            name = "user_id",
            nullable = false,
            unique = true)
    public UUID userId;

    @Column(
            name = "email_enabled",
            nullable = false)
    public boolean emailEnabled = true;

    @Column(
            name = "email_listing_messages",
            nullable = false)
    public boolean emailListingMessages = true;

    @Column(
            name = "email_site_approved",
            nullable = false)
    public boolean emailSiteApproved = true;

    @Column(
            name = "email_site_rejected",
            nullable = false)
    public boolean emailSiteRejected = true;

    @Column(
            name = "email_digest",
            nullable = false)
    public boolean emailDigest = false; // Default opt-out for digest

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    /**
     * Finds notification preferences for a user. Returns Optional as preferences may not exist for new users.
     *
     * @param userId
     *            the user's UUID
     * @return Optional containing preferences if found, empty if userId is null or preferences don't exist
     */
    public static Optional<NotificationPreferences> findByUserId(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return find("#" + QUERY_FIND_BY_USER_ID, Parameters.with("userId", userId)).firstResultOptional();
    }

    /**
     * Creates default notification preferences for a user.
     *
     * @param userId
     *            the user's UUID
     * @return the created preferences
     */
    public static NotificationPreferences create(UUID userId) {
        NotificationPreferences prefs = new NotificationPreferences();
        prefs.userId = userId;
        prefs.createdAt = Instant.now();
        prefs.updatedAt = Instant.now();

        QuarkusTransaction.requiringNew().run(() -> {
            prefs.persist();
            LOG.infof("Created notification preferences: user_id=%s", userId);
        });
        return prefs;
    }

    /**
     * Updates notification preferences.
     *
     * @param prefs
     *            the preferences to update
     */
    public static void update(NotificationPreferences prefs) {
        QuarkusTransaction.requiringNew().run(() -> {
            prefs.updatedAt = Instant.now();
            prefs.persist();
            LOG.infof("Updated notification preferences: user_id=%s", prefs.userId);
        });
    }
}
