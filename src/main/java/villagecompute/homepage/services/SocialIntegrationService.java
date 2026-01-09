/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.services;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.SocialPostType;
import villagecompute.homepage.api.types.SocialWidgetStateType;
import villagecompute.homepage.data.models.SocialPost;
import villagecompute.homepage.data.models.SocialToken;
import villagecompute.homepage.integration.social.MetaGraphClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for Meta Graph API integration with Instagram and Facebook per Policy P5/P13.
 *
 * <p>
 * Implements cache-first strategy with graceful degradation when tokens become stale. Provides staleness banners with
 * color-coded warnings (green/yellow/orange/red) based on days since last refresh. Supports reconnect CTAs and
 * automatic token refresh 7 days before expiry.
 *
 * <h2>Connection States</h2>
 * <ul>
 * <li><b>connected</b> - Token valid and fresh (< 24 hours)</li>
 * <li><b>stale</b> - Token valid but posts outdated (1-7 days)</li>
 * <li><b>expired</b> - Token expired, showing cached posts only (> 7 days)</li>
 * <li><b>disconnected</b> - No token exists for platform</li>
 * </ul>
 *
 * <h2>Staleness Tiers (Policy P13)</h2>
 * <ul>
 * <li><b>FRESH</b> (< 24 hours) - Green banner, no warning</li>
 * <li><b>SLIGHTLY_STALE</b> (1-3 days) - Yellow banner, minor warning</li>
 * <li><b>STALE</b> (4-7 days) - Orange banner, reconnect CTA shown</li>
 * <li><b>VERY_STALE</b> (> 7 days) - Red banner, limited functionality</li>
 * </ul>
 *
 * <h2>Caching Strategy</h2>
 * <ol>
 * <li>Check if user has active token for platform</li>
 * <li>Load cached posts from database (up to 10 most recent)</li>
 * <li>Calculate staleness tier based on last refresh timestamp</li>
 * <li>If token fresh, attempt API fetch to update cache</li>
 * <li>On API failure, serve cached posts with staleness banner</li>
 * <li>If token expired > 7 days, archive posts and show reconnect CTA</li>
 * </ol>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * {@code
 * @Inject
 * SocialIntegrationService service;
 *
 * SocialWidgetStateType state = service.getSocialFeed(userId, "instagram");
 * }
 * </pre>
 */
@ApplicationScoped
public class SocialIntegrationService {

    private static final Logger LOG = Logger.getLogger(SocialIntegrationService.class);

    private static final int MAX_POSTS_TO_RETURN = 10;
    private static final String RECONNECT_URL_BASE = "/oauth/connect"; // TODO: Replace with actual OAuth flow URL

    @Inject
    MetaGraphClient metaGraphClient;

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Gets social feed state for user and platform (cache-first strategy).
     *
     * @param userId
     *            user UUID
     * @param platform
     *            "instagram" or "facebook"
     * @return widget state with posts, connection status, and staleness indicators
     */
    @Transactional
    public SocialWidgetStateType getSocialFeed(UUID userId, String platform) {
        Span span = tracer.spanBuilder("social.get_feed").setAttribute("user_id", userId.toString())
                .setAttribute("platform", platform).startSpan();

        try (Scope scope = span.makeCurrent()) {
            // 1. Find user's token for platform
            Optional<SocialToken> tokenOpt = SocialToken.findActiveByUserAndPlatform(userId, platform);

            if (tokenOpt.isEmpty()) {
                incrementCounter("social.feed.requests", platform, "disconnected");
                span.setAttribute("connection_status", "disconnected");
                return buildDisconnectedState(platform);
            }

            SocialToken token = tokenOpt.get();
            span.setAttribute("token_id", token.id);

            // 2. Load cached posts
            List<SocialPost> cachedPosts = SocialPost.findRecentByToken(token.id, MAX_POSTS_TO_RETURN);
            LOG.debugf("Loaded %d cached posts for user %s platform %s", cachedPosts.size(), userId, platform);

            // 3. Calculate staleness
            StalenessState staleness = calculateStaleness(token);
            span.setAttribute("staleness", staleness.name());
            span.setAttribute("staleness_days", token.getDaysSinceRefresh());

            // 4. Decide whether to refresh
            if (shouldRefresh(token, staleness)) {
                try {
                    Timer.Sample sample = Timer.start(meterRegistry);
                    List<SocialPost> freshPosts = fetchAndCachePosts(token, userId);
                    sample.stop(Timer.builder("social.api.duration").tag("platform", platform).tag("status", "success")
                            .register(meterRegistry));

                    incrementCounter("social.feed.requests", platform, "fresh");
                    return buildFreshState(platform, freshPosts, token);

                } catch (Exception e) {
                    LOG.warnf(e, "Failed to refresh posts for user %s platform %s, serving cache", userId, platform);
                    incrementCounter("social.api.failures", platform, "fetch_failed");
                    // Fall through to serve cached posts with staleness indicator
                }
            }

            // 5. Serve cached posts with staleness indicator
            incrementCounter("social.feed.requests", platform, staleness.name().toLowerCase());
            return buildStateWithStaleness(platform, cachedPosts, token, staleness);

        } catch (Exception e) {
            span.recordException(e);
            LOG.errorf(e, "Failed to get social feed for user %s platform %s", userId, platform);
            incrementCounter("social.feed.errors", platform, "unexpected");
            throw new RuntimeException("Failed to get social feed", e);

        } finally {
            span.end();
        }
    }

    /**
     * Refreshes posts for a social token (called by background job).
     *
     * @param tokenId
     *            social token ID
     */
    @Transactional
    public void refreshPosts(Long tokenId) {
        SocialToken token = SocialToken.findById(tokenId);
        if (token == null) {
            LOG.warnf("Token %d not found for refresh", tokenId);
            return;
        }
        if (token.revokedAt != null) {
            LOG.debugf("Skipping revoked token %d", tokenId);
            return;
        }

        try {
            fetchAndCachePosts(token, token.userId);
            LOG.infof("Refreshed posts for token %d (user %s platform %s)", tokenId, token.userId, token.platform);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to refresh posts for token %d", tokenId);
            token.markRefreshAttempt();
            throw e;
        }
    }

    /**
     * Fetches posts from Meta Graph API and updates cache.
     */
    private List<SocialPost> fetchAndCachePosts(SocialToken token, UUID userId) {
        List<SocialPost> posts = new ArrayList<>();

        if (SocialToken.PLATFORM_INSTAGRAM.equals(token.platform)) {
            List<MetaGraphClient.InstagramPostType> apiPosts = metaGraphClient.fetchInstagramPosts(token.accessToken,
                    userId);

            for (MetaGraphClient.InstagramPostType apiPost : apiPosts) {
                SocialPost post = SocialPost.create(token.id, token.platform, apiPost.id(), apiPost.postType(),
                        apiPost.caption(), apiPost.mediaUrls(), apiPost.postedAt(), apiPost.engagement());
                posts.add(post);
            }

        } else if (SocialToken.PLATFORM_FACEBOOK.equals(token.platform)) {
            List<MetaGraphClient.FacebookPostType> apiPosts = metaGraphClient.fetchFacebookPosts(token.accessToken,
                    userId);

            for (MetaGraphClient.FacebookPostType apiPost : apiPosts) {
                SocialPost post = SocialPost.create(token.id, token.platform, apiPost.id(), apiPost.type(),
                        apiPost.message(), apiPost.mediaUrls(), apiPost.postedAt(), apiPost.engagement());
                posts.add(post);
            }
        }

        // Update token's last refresh timestamp
        token.markRefreshAttempt();

        return posts;
    }

    /**
     * Calculates staleness state based on token's last refresh attempt.
     */
    private StalenessState calculateStaleness(SocialToken token) {
        long daysSinceRefresh = token.getDaysSinceRefresh();

        if (daysSinceRefresh < 1) {
            return StalenessState.FRESH;
        } else if (daysSinceRefresh <= 3) {
            return StalenessState.SLIGHTLY_STALE;
        } else if (daysSinceRefresh <= 7) {
            return StalenessState.STALE;
        } else {
            return StalenessState.VERY_STALE;
        }
    }

    /**
     * Determines whether to attempt API refresh.
     */
    private boolean shouldRefresh(SocialToken token, StalenessState staleness) {
        // Don't refresh if very stale (token likely expired)
        if (staleness == StalenessState.VERY_STALE) {
            return false;
        }

        // Always try to refresh if fresh or slightly stale
        return staleness == StalenessState.FRESH || staleness == StalenessState.SLIGHTLY_STALE;
    }

    /**
     * Builds widget state for disconnected platform.
     */
    private SocialWidgetStateType buildDisconnectedState(String platform) {
        String reconnectUrl = RECONNECT_URL_BASE + "?platform=" + platform;
        return new SocialWidgetStateType(platform, List.of(), "disconnected", "VERY_STALE", 0, reconnectUrl, null,
                "Connect your " + capitalizeFirst(platform) + " account to see your posts here.");
    }

    /**
     * Builds widget state for fresh posts (just fetched from API).
     */
    private SocialWidgetStateType buildFreshState(String platform, List<SocialPost> posts, SocialToken token) {
        List<SocialPostType> postTypes = posts.stream().limit(MAX_POSTS_TO_RETURN).map(this::toPostType).toList();

        return new SocialWidgetStateType(platform, postTypes, "connected", "FRESH", 0, null,
                token.lastRefreshAttempt.toString(), null);
    }

    /**
     * Builds widget state with staleness indicators.
     */
    private SocialWidgetStateType buildStateWithStaleness(String platform, List<SocialPost> cachedPosts,
            SocialToken token, StalenessState staleness) {

        List<SocialPostType> postTypes = cachedPosts.stream().limit(MAX_POSTS_TO_RETURN).map(this::toPostType).toList();

        String connectionStatus = staleness == StalenessState.VERY_STALE ? "expired" : "stale";
        String reconnectUrl = (staleness == StalenessState.STALE || staleness == StalenessState.VERY_STALE)
                ? RECONNECT_URL_BASE + "?platform=" + platform
                : null;

        long daysSince = token.getDaysSinceRefresh();
        String message = buildStaleMessage(platform, daysSince, staleness);

        String cachedAt = token.lastRefreshAttempt != null ? token.lastRefreshAttempt.toString()
                : token.grantedAt.toString();

        return new SocialWidgetStateType(platform, postTypes, connectionStatus, staleness.name(), (int) daysSince,
                reconnectUrl, cachedAt, message);
    }

    /**
     * Builds user-facing staleness message per Policy P13.
     */
    private String buildStaleMessage(String platform, long daysSince, StalenessState staleness) {
        String platformName = capitalizeFirst(platform);

        return switch (staleness) {
            case FRESH -> null;
            case SLIGHTLY_STALE ->
                String.format("Showing posts from %d day%s ago.", daysSince, daysSince == 1 ? "" : "s");
            case STALE ->
                String.format("Showing posts from %d days ago. Reconnect your %s to refresh.", daysSince, platformName);
            case VERY_STALE ->
                String.format("Your %s connection expired %d+ days ago. Posts may be outdated. Reconnect to refresh.",
                        platformName, daysSince);
        };
    }

    /**
     * Converts SocialPost entity to SocialPostType DTO.
     */
    private SocialPostType toPostType(SocialPost post) {
        return new SocialPostType(post.platformPostId, post.postType, post.caption, post.mediaUrls,
                post.postedAt.toString(), post.engagementData, null);
    }

    /**
     * Helper to capitalize first letter of string.
     */
    private String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /**
     * Helper to increment Micrometer counter.
     */
    private void incrementCounter(String name, String platform, String status) {
        Counter.builder(name).tag("platform", platform).tag("status", status).register(meterRegistry).increment();
    }

    /**
     * Staleness state enum per Policy P13.
     */
    public enum StalenessState {
        FRESH, // < 24 hours - green
        SLIGHTLY_STALE, // 1-3 days - yellow
        STALE, // 4-7 days - orange
        VERY_STALE // > 7 days - red
    }
}
