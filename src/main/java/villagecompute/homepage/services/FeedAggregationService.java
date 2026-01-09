package villagecompute.homepage.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.FeedItem;
import villagecompute.homepage.data.models.RssSource;
import villagecompute.homepage.data.models.UserFeedSubscription;
import villagecompute.homepage.exceptions.DuplicateResourceException;
import villagecompute.homepage.observability.LoggingConfig;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Central service for RSS feed aggregation, management, and subscription handling.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>RSS source CRUD operations (system and user-custom feeds)</li>
 * <li>Feed item storage and deduplication via item_guid</li>
 * <li>User subscription management (subscribe/unsubscribe)</li>
 * <li>Health monitoring and error tracking for feed sources</li>
 * <li>Integration with feed refresh jobs (I3.T2) and AI tagging jobs (I3.T3)</li>
 * </ul>
 *
 * <p>
 * <b>Policy Compliance:</b>
 * <ul>
 * <li>P1 (GDPR/CCPA): User-custom feeds and subscriptions included in data export</li>
 * <li>P2/P10 (AI Budget): AI tagging budget checked via AiTaggingBudgetService (I3.T3)</li>
 * <li>P14 (Rate Limiting): Feed operations respect tier-based rate limits</li>
 * </ul>
 *
 * <p>
 * <b>Health Monitoring:</b> Sources with 5+ consecutive errors are auto-disabled. Error count resets on successful
 * fetch. See {@code docs/ops/feed-governance.md} for operational runbook.
 */
@ApplicationScoped
public class FeedAggregationService {

    private static final Logger LOG = Logger.getLogger(FeedAggregationService.class);

    @Inject
    Tracer tracer;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Retrieves all RSS sources with optional filtering.
     *
     * @return List of all RSS sources
     */
    @Transactional
    public List<RssSource> getAllSources() {
        Span span = tracer.spanBuilder("feed.get_all_sources").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            return RssSource.listAll();
        } finally {
            span.end();
        }
    }

    /**
     * Retrieves an RSS source by ID.
     *
     * @param id
     *            the source UUID
     * @return Optional containing the source if found
     */
    @Transactional
    public Optional<RssSource> getSourceById(UUID id) {
        Span span = tracer.spanBuilder("feed.get_source_by_id").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            span.setAttribute("source_id", id.toString());
            return RssSource.findByIdOptional(id);
        } finally {
            span.end();
        }
    }

    /**
     * Creates a new system RSS source (admin-only).
     *
     * @param name
     *            feed display name
     * @param url
     *            RSS/Atom feed URL
     * @param category
     *            optional category
     * @param refreshIntervalMinutes
     *            refresh interval (15-1440 minutes)
     * @return the created source
     * @throws DuplicateResourceException
     *             if URL already exists
     */
    @Transactional
    public RssSource createSystemSource(String name, String url, String category, int refreshIntervalMinutes) {
        Span span = tracer.spanBuilder("feed.create_system_source").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            span.setAttribute("source_name", name);
            span.setAttribute("source_url", url);

            // Check for duplicate URL
            Optional<RssSource> existing = RssSource.findByUrl(url);
            if (existing.isPresent()) {
                throw new DuplicateResourceException("RSS source with URL already exists: " + url);
            }

            RssSource source = new RssSource();
            source.name = name;
            source.url = url;
            source.category = category;
            source.isSystem = true;
            source.userId = null;
            source.refreshIntervalMinutes = refreshIntervalMinutes;
            source.isActive = true;
            source.errorCount = 0;

            RssSource created = RssSource.create(source);
            LOG.infof("Created system RSS source: id=%s, name=%s, url=%s", created.id, created.name, created.url);
            return created;
        } finally {
            span.end();
        }
    }

    /**
     * Updates an existing RSS source (admin-only).
     *
     * @param id
     *            the source UUID
     * @param name
     *            new name (optional)
     * @param category
     *            new category (optional)
     * @param refreshIntervalMinutes
     *            new refresh interval (optional)
     * @param isActive
     *            new active status (optional)
     * @return the updated source
     */
    @Transactional
    public Optional<RssSource> updateSource(UUID id, String name, String category, Integer refreshIntervalMinutes,
            Boolean isActive) {
        Span span = tracer.spanBuilder("feed.update_source").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            span.setAttribute("source_id", id.toString());

            Optional<RssSource> sourceOpt = RssSource.findByIdOptional(id);
            if (sourceOpt.isEmpty()) {
                return Optional.empty();
            }

            RssSource source = sourceOpt.get();

            if (name != null) {
                source.name = name;
            }
            if (category != null) {
                source.category = category;
            }
            if (refreshIntervalMinutes != null) {
                source.refreshIntervalMinutes = refreshIntervalMinutes;
            }
            if (isActive != null) {
                source.isActive = isActive;
            }

            RssSource.update(source);
            LOG.infof("Updated RSS source: id=%s, name=%s", source.id, source.name);
            return Optional.of(source);
        } finally {
            span.end();
        }
    }

    /**
     * Deletes an RSS source by ID (cascade deletes feed items and subscriptions).
     *
     * @param id
     *            the source UUID
     * @return true if deleted, false if not found
     */
    @Transactional
    public boolean deleteSource(UUID id) {
        Span span = tracer.spanBuilder("feed.delete_source").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            span.setAttribute("source_id", id.toString());

            Optional<RssSource> sourceOpt = RssSource.findByIdOptional(id);
            if (sourceOpt.isEmpty()) {
                return false;
            }

            RssSource source = sourceOpt.get();
            source.delete();
            LOG.infof("Deleted RSS source: id=%s, name=%s, url=%s", id, source.name, source.url);
            return true;
        } finally {
            span.end();
        }
    }

    /**
     * Retrieves feed items by source ID.
     *
     * @param sourceId
     *            the RSS source UUID
     * @return List of feed items ordered by published date descending
     */
    @Transactional
    public List<FeedItem> getFeedItemsBySource(UUID sourceId) {
        Span span = tracer.spanBuilder("feed.get_items_by_source").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            span.setAttribute("source_id", sourceId.toString());
            return FeedItem.findBySource(sourceId);
        } finally {
            span.end();
        }
    }

    /**
     * Retrieves recent feed items across all sources.
     *
     * @param limit
     *            maximum number of items to return
     * @return List of recent feed items
     */
    @Transactional
    public List<FeedItem> getRecentFeedItems(int limit) {
        Span span = tracer.spanBuilder("feed.get_recent_items").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            span.setAttribute("limit", limit);
            return FeedItem.findRecent(limit);
        } finally {
            span.end();
        }
    }

    /**
     * Retrieves a feed item by ID.
     *
     * @param id
     *            the feed item UUID
     * @return Optional containing the item if found
     */
    @Transactional
    public Optional<FeedItem> getFeedItemById(UUID id) {
        Span span = tracer.spanBuilder("feed.get_item_by_id").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            span.setAttribute("item_id", id.toString());
            return FeedItem.findByIdOptional(id);
        } finally {
            span.end();
        }
    }

    /**
     * Subscribes a user to an RSS source.
     *
     * @param userId
     *            the user's UUID
     * @param sourceId
     *            the RSS source UUID
     * @return the created subscription
     */
    @Transactional
    public UserFeedSubscription subscribe(UUID userId, UUID sourceId) {
        Span span = tracer.spanBuilder("feed.subscribe").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            span.setAttribute("user_id", userId.toString());
            span.setAttribute("source_id", sourceId.toString());

            // Check for existing subscription (active or historical)
            Optional<UserFeedSubscription> existingOpt = UserFeedSubscription.findByUserAndSource(userId, sourceId);
            if (existingOpt.isPresent()) {
                UserFeedSubscription existing = existingOpt.get();
                if (existing.unsubscribedAt == null) {
                    LOG.debugf("User already subscribed: user_id=%s, source_id=%s", userId, sourceId);
                    return existing;
                } else {
                    // Resubscribe
                    UserFeedSubscription.resubscribe(existing);
                    return existing;
                }
            }

            // Create new subscription
            UserFeedSubscription subscription = new UserFeedSubscription();
            subscription.userId = userId;
            subscription.sourceId = sourceId;
            return UserFeedSubscription.create(subscription);
        } finally {
            span.end();
        }
    }

    /**
     * Unsubscribes a user from an RSS source (soft delete).
     *
     * @param userId
     *            the user's UUID
     * @param sourceId
     *            the RSS source UUID
     * @return true if unsubscribed, false if not found
     */
    @Transactional
    public boolean unsubscribe(UUID userId, UUID sourceId) {
        Span span = tracer.spanBuilder("feed.unsubscribe").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            span.setAttribute("user_id", userId.toString());
            span.setAttribute("source_id", sourceId.toString());

            Optional<UserFeedSubscription> subscriptionOpt = UserFeedSubscription.findByUserAndSource(userId, sourceId);
            if (subscriptionOpt.isEmpty() || subscriptionOpt.get().unsubscribedAt != null) {
                return false;
            }

            UserFeedSubscription.unsubscribe(subscriptionOpt.get());
            return true;
        } finally {
            span.end();
        }
    }

    /**
     * Retrieves active subscriptions for a user.
     *
     * @param userId
     *            the user's UUID
     * @return List of active subscriptions
     */
    @Transactional
    public List<UserFeedSubscription> getActiveSubscriptions(UUID userId) {
        Span span = tracer.spanBuilder("feed.get_active_subscriptions").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            span.setAttribute("user_id", userId.toString());
            return UserFeedSubscription.findActiveByUser(userId);
        } finally {
            span.end();
        }
    }
}
