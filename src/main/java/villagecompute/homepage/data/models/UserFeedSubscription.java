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
import java.util.Optional;
import java.util.UUID;

/**
 * User feed subscription entity implementing the Panache ActiveRecord pattern for many-to-many relationships.
 *
 * <p>
 * Manages user subscriptions to both system-managed and user-custom RSS feeds per Policy P1 (GDPR/CCPA). Uses soft
 * delete pattern via {@code unsubscribed_at} to preserve historical data for analytics and data export.
 *
 * <p>
 * <b>Schema Mapping:</b>
 * <ul>
 * <li>{@code id} (UUID, PK) - Primary identifier</li>
 * <li>{@code user_id} (UUID, FK) - Reference to users</li>
 * <li>{@code source_id} (UUID, FK) - Reference to rss_sources</li>
 * <li>{@code subscribed_at} (TIMESTAMPTZ) - Subscription timestamp</li>
 * <li>{@code unsubscribed_at} (TIMESTAMPTZ) - Unsubscription timestamp (null for active)</li>
 * </ul>
 *
 * <p>
 * <b>GDPR Compliance:</b> User subscriptions are included in data export per Policy P1. Cascade delete ensures
 * subscriptions are removed when user or source is deleted.
 *
 * @see User for user accounts
 * @see RssSource for feed sources
 */
@Entity
@Table(
        name = "user_feed_subscriptions")
@NamedQuery(
        name = UserFeedSubscription.QUERY_FIND_ACTIVE_BY_USER,
        query = UserFeedSubscription.JPQL_FIND_ACTIVE_BY_USER)
@NamedQuery(
        name = UserFeedSubscription.QUERY_FIND_BY_USER_AND_SOURCE,
        query = UserFeedSubscription.JPQL_FIND_BY_USER_AND_SOURCE)
@NamedQuery(
        name = UserFeedSubscription.QUERY_FIND_ACTIVE_BY_SOURCE,
        query = UserFeedSubscription.JPQL_FIND_ACTIVE_BY_SOURCE)
public class UserFeedSubscription extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(UserFeedSubscription.class);

    public static final String JPQL_FIND_ACTIVE_BY_USER = "FROM UserFeedSubscription WHERE userId = :userId AND unsubscribedAt IS NULL";
    public static final String QUERY_FIND_ACTIVE_BY_USER = "UserFeedSubscription.findActiveByUser";

    public static final String JPQL_FIND_BY_USER_AND_SOURCE = "FROM UserFeedSubscription WHERE userId = :userId AND sourceId = :sourceId";
    public static final String QUERY_FIND_BY_USER_AND_SOURCE = "UserFeedSubscription.findByUserAndSource";

    public static final String JPQL_FIND_ACTIVE_BY_SOURCE = "FROM UserFeedSubscription WHERE sourceId = :sourceId AND unsubscribedAt IS NULL";
    public static final String QUERY_FIND_ACTIVE_BY_SOURCE = "UserFeedSubscription.findActiveBySource";

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
            name = "source_id",
            nullable = false)
    public UUID sourceId;

    @Column(
            name = "subscribed_at",
            nullable = false)
    public Instant subscribedAt;

    @Column(
            name = "unsubscribed_at")
    public Instant unsubscribedAt;

    /**
     * Finds all active subscriptions for a user.
     *
     * @param userId
     *            the user's UUID
     * @return List of active subscriptions
     */
    public static List<UserFeedSubscription> findActiveByUser(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return find(JPQL_FIND_ACTIVE_BY_USER, Parameters.with("userId", userId)).list();
    }

    /**
     * Finds subscription by user and source (active or historical).
     *
     * @param userId
     *            the user's UUID
     * @param sourceId
     *            the source's UUID
     * @return Optional containing the subscription if found
     */
    public static Optional<UserFeedSubscription> findByUserAndSource(UUID userId, UUID sourceId) {
        if (userId == null || sourceId == null) {
            return Optional.empty();
        }
        return find(JPQL_FIND_BY_USER_AND_SOURCE, Parameters.with("userId", userId).and("sourceId", sourceId))
                .firstResultOptional();
    }

    /**
     * Finds all active subscriptions for a specific source.
     *
     * @param sourceId
     *            the source's UUID
     * @return List of active subscriptions
     */
    public static List<UserFeedSubscription> findActiveBySource(UUID sourceId) {
        if (sourceId == null) {
            return List.of();
        }
        return find(JPQL_FIND_ACTIVE_BY_SOURCE, Parameters.with("sourceId", sourceId)).list();
    }

    /**
     * Creates a new subscription.
     *
     * @param subscription
     *            the subscription to persist
     * @return the persisted subscription with generated ID
     */
    public static UserFeedSubscription create(UserFeedSubscription subscription) {
        QuarkusTransaction.requiringNew().run(() -> {
            subscription.subscribedAt = Instant.now();
            subscription.persist();
            LOG.infof("Created subscription: user_id=%s, source_id=%s", subscription.userId, subscription.sourceId);
        });
        return subscription;
    }

    /**
     * Unsubscribes a user from a source (soft delete).
     *
     * @param subscription
     *            the subscription to unsubscribe
     */
    public static void unsubscribe(UserFeedSubscription subscription) {
        QuarkusTransaction.requiringNew().run(() -> {
            subscription.unsubscribedAt = Instant.now();
            subscription.persist();
            LOG.infof("Unsubscribed: user_id=%s, source_id=%s", subscription.userId, subscription.sourceId);
        });
    }

    /**
     * Resubscribes a user to a previously unsubscribed source.
     *
     * @param subscription
     *            the subscription to reactivate
     */
    public static void resubscribe(UserFeedSubscription subscription) {
        QuarkusTransaction.requiringNew().run(() -> {
            subscription.unsubscribedAt = null;
            subscription.subscribedAt = Instant.now();
            subscription.persist();
            LOG.infof("Resubscribed: user_id=%s, source_id=%s", subscription.userId, subscription.sourceId);
        });
    }
}
