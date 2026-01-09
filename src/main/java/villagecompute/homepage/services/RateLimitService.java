package villagecompute.homepage.services;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.RateLimitConfig;
import villagecompute.homepage.data.models.RateLimitViolation;
import villagecompute.homepage.observability.LoggingConfig;
import villagecompute.homepage.observability.ObservabilityMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Tier-aware rate limiting service with Caffeine-backed caching and database persistence (Policy P14/F14.2).
 *
 * <p>
 * Provides sliding-window rate limiting with three user tiers:
 * <ul>
 * <li><b>anonymous:</b> Strict limits for unauthenticated users</li>
 * <li><b>logged_in:</b> Moderate limits for authenticated users</li>
 * <li><b>trusted:</b> Generous limits for users with karma >= 10</li>
 * </ul>
 *
 * <p>
 * <b>Architecture:</b>
 * <ul>
 * <li>Configuration loaded from database and cached with Caffeine (10min TTL)</li>
 * <li>Sliding windows stored in-memory with Caffeine eviction aligned to rate windows</li>
 * <li>Violations persisted asynchronously to avoid blocking request threads</li>
 * <li>Backward compatible with legacy {@code check(key, rule)} method for auth flows</li>
 * </ul>
 *
 * <p>
 * <b>Thread Safety:</b> All caches and buckets are thread-safe. Violation persistence runs asynchronously.
 *
 * @see RateLimitConfig for configuration schema
 * @see RateLimitViolation for violation tracking
 */
@ApplicationScoped
public class RateLimitService {

    private static final Logger LOG = Logger.getLogger(RateLimitService.class);

    @Inject
    ObservabilityMetrics observabilityMetrics;

    /**
     * Cache for rate limit configurations (action_type:tier -> config). Expires after 10 minutes to balance performance
     * with config freshness.
     */
    private final Cache<String, RateLimitConfig> configCache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES).maximumSize(500).build();

    /**
     * Sliding window buckets for rate limiting (key -> timestamps). Expires after 24 hours (max window duration).
     */
    private final Cache<String, Deque<Instant>> bucketCache = Caffeine.newBuilder().expireAfterWrite(24, TimeUnit.HOURS)
            .maximumSize(100_000).build();

    /**
     * Legacy in-memory buckets for backward compatibility with check(key, rule) method. This map supports auth flows
     * that use explicit RateLimitRule objects.
     */
    private final ConcurrentMap<String, Deque<Instant>> legacyBuckets = new ConcurrentHashMap<>();

    /**
     * User tier enumeration.
     */
    public enum Tier {
        ANONYMOUS("anonymous"), LOGGED_IN("logged_in"), TRUSTED("trusted");

        private final String value;

        Tier(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static Tier fromKarma(int karma) {
            if (karma >= 10) {
                return TRUSTED;
            }
            return LOGGED_IN;
        }
    }

    /**
     * Rate limit check result with remaining attempts.
     */
    public record RateLimitResult(boolean allowed, int limitCount, int remaining, int windowSeconds) {

        public static RateLimitResult allowed(int limitCount, int remaining, int windowSeconds) {
            return new RateLimitResult(true, limitCount, remaining, windowSeconds);
        }

        public static RateLimitResult denied(int limitCount, int windowSeconds) {
            return new RateLimitResult(false, limitCount, 0, windowSeconds);
        }
    }

    /**
     * Checks whether a request is allowed under tier-aware rate limits.
     *
     * <p>
     * This is the primary method for new code. It loads configuration from the database, checks the sliding window, and
     * records violations asynchronously if the limit is exceeded.
     *
     * @param userId
     *            authenticated user ID (null for anonymous)
     * @param ipAddress
     *            source IP address
     * @param actionType
     *            action identifier (e.g., "login", "search", "vote")
     * @param tier
     *            user tier (anonymous, logged_in, trusted)
     * @param endpoint
     *            HTTP endpoint path (for violation logging)
     * @return RateLimitResult with allowed flag and remaining attempts
     */
    public RateLimitResult checkLimit(Long userId, String ipAddress, String actionType, Tier tier, String endpoint) {
        Objects.requireNonNull(actionType, "actionType is required");
        Objects.requireNonNull(tier, "tier is required");

        // Load configuration (cached)
        Optional<RateLimitConfig> configOpt = getConfig(actionType, tier.getValue());
        if (configOpt.isEmpty()) {
            LOG.warnf("No rate limit config found for action=%s tier=%s, allowing request (fail-open)", actionType,
                    tier.getValue());
            return RateLimitResult.allowed(Integer.MAX_VALUE, Integer.MAX_VALUE, 3600);
        }

        RateLimitConfig config = configOpt.get();
        String bucketKey = buildBucketKey(actionType, tier.getValue(), userId, ipAddress);

        // Check sliding window
        Instant now = Instant.now();
        Deque<Instant> timestamps = bucketCache.get(bucketKey, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            pruneWindow(timestamps, now, Duration.ofSeconds(config.windowSeconds));

            int remaining = config.limitCount - timestamps.size();

            if (timestamps.size() >= config.limitCount) {
                // Rate limit exceeded
                LOG.warnf("Rate limit exceeded: action=%s tier=%s userId=%s ip=%s limit=%d", actionType,
                        tier.getValue(), userId, ipAddress, config.limitCount);

                // Record violation asynchronously
                recordViolationAsync(userId, ipAddress, actionType, endpoint, tier.getValue());

                // Track metrics
                observabilityMetrics.incrementRateLimitCheck(actionType, tier.getValue(), false);
                observabilityMetrics.incrementRateLimitViolation(actionType, tier.getValue());

                LoggingConfig.setRateLimitBucket(bucketKey);
                return RateLimitResult.denied(config.limitCount, config.windowSeconds);
            }

            // Allow request
            timestamps.addLast(now);
            LoggingConfig.setRateLimitBucket(bucketKey);

            // Track metrics
            observabilityMetrics.incrementRateLimitCheck(actionType, tier.getValue(), true);

            return RateLimitResult.allowed(config.limitCount, remaining - 1, config.windowSeconds);
        }
    }

    /**
     * Legacy rate limit check method for backward compatibility.
     *
     * <p>
     * Used by AuthIdentityService for bootstrap and login flows. Maintains in-memory sliding windows without database
     * persistence. New code should use {@link #checkLimit(Long, String, String, Tier, String)} instead.
     *
     * @param key
     *            composite identifier (e.g., "login:google:198.51.100.5")
     * @param rule
     *            rule definition (max requests + window)
     * @return true if allowed, false when throttled
     */
    public boolean check(String key, RateLimitRule rule) {
        Objects.requireNonNull(rule, "RateLimitRule is required");
        Objects.requireNonNull(key, "Rate limit key is required");

        Instant now = Instant.now();
        Deque<Instant> timestamps = legacyBuckets.computeIfAbsent(key, ignored -> new ArrayDeque<>());

        synchronized (timestamps) {
            pruneWindow(timestamps, now, rule.window());
            if (timestamps.size() >= rule.maxRequests()) {
                LOG.warnf("Rate limit exceeded (legacy): rule=%s bucket=%s max=%d", rule.name(), key,
                        rule.maxRequests());
                return false;
            }
            timestamps.addLast(now);
            LoggingConfig.setRateLimitBucket(key);
        }
        return true;
    }

    /**
     * Records a rate limit violation (legacy method for backward compatibility).
     *
     * <p>
     * Persists violation asynchronously. New code should use {@link #checkLimit} which records violations
     * automatically.
     *
     * @param key
     *            rate limit bucket key
     * @param endpoint
     *            HTTP endpoint path
     * @param userId
     *            user ID (nullable)
     * @param ipAddress
     *            source IP
     */
    public void recordViolation(String key, String endpoint, String userId, String ipAddress) {
        Long userIdLong = userId != null ? parseLongSafe(userId) : null;
        String actionType = extractActionFromKey(key);
        // Use "anonymous" tier for legacy calls (bootstrap/login)
        recordViolationAsync(userIdLong, ipAddress, actionType, endpoint, "anonymous");
        observabilityMetrics.incrementRateLimitViolation(actionType, "anonymous");
    }

    /**
     * Gets the remaining attempts for a user/IP on a specific action.
     *
     * <p>
     * Useful for setting X-RateLimit-Remaining headers in responses.
     *
     * @param userId
     *            user ID (nullable)
     * @param ipAddress
     *            source IP
     * @param actionType
     *            action identifier
     * @param tier
     *            user tier
     * @return remaining attempts, or -1 if no config exists
     */
    public int getRemainingAttempts(Long userId, String ipAddress, String actionType, Tier tier) {
        Optional<RateLimitConfig> configOpt = getConfig(actionType, tier.getValue());
        if (configOpt.isEmpty()) {
            return -1;
        }

        RateLimitConfig config = configOpt.get();
        String bucketKey = buildBucketKey(actionType, tier.getValue(), userId, ipAddress);

        Instant now = Instant.now();
        Deque<Instant> timestamps = bucketCache.get(bucketKey, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            pruneWindow(timestamps, now, Duration.ofSeconds(config.windowSeconds));
            return Math.max(0, config.limitCount - timestamps.size());
        }
    }

    /**
     * Loads all rate limit configurations (admin use).
     *
     * @return list of all configs
     */
    public List<RateLimitConfig> getAllConfigs() {
        return RateLimitConfig.findAllConfigs();
    }

    /**
     * Loads a specific rate limit configuration (admin use).
     *
     * @param actionType
     *            action identifier
     * @param tier
     *            user tier
     * @return config if found
     */
    public Optional<RateLimitConfig> getConfig(String actionType, String tier) {
        String cacheKey = actionType + ":" + tier;
        RateLimitConfig cached = configCache.getIfPresent(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<RateLimitConfig> config = RateLimitConfig.findByActionAndTier(actionType, tier);
        config.ifPresent(c -> configCache.put(cacheKey, c));
        return config;
    }

    /**
     * Updates a rate limit configuration (admin use).
     *
     * @param actionType
     *            action identifier
     * @param tier
     *            user tier
     * @param limitCount
     *            new limit count (null to keep current)
     * @param windowSeconds
     *            new window in seconds (null to keep current)
     * @param actorId
     *            admin user ID making the change
     * @return updated config
     * @throws IllegalArgumentException
     *             if config doesn't exist
     */
    @Transactional
    public RateLimitConfig updateConfig(String actionType, String tier, Integer limitCount, Integer windowSeconds,
            Long actorId) {
        Optional<RateLimitConfig> configOpt = RateLimitConfig.findByActionAndTier(actionType, tier);
        if (configOpt.isEmpty()) {
            throw new IllegalArgumentException("Rate limit config not found: " + actionType + ":" + tier);
        }

        RateLimitConfig config = configOpt.get();
        config.update(limitCount, windowSeconds, actorId);

        // Invalidate cache
        configCache.invalidate(actionType + ":" + tier);

        LOG.infof("Updated rate limit config: action=%s tier=%s limit=%d window=%d actor=%d", actionType, tier,
                config.limitCount, config.windowSeconds, actorId);

        return config;
    }

    /**
     * Records a violation asynchronously to avoid blocking the request thread.
     */
    private void recordViolationAsync(Long userId, String ipAddress, String actionType, String endpoint, String tier) {
        CompletableFuture.runAsync(() -> {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    RateLimitViolation.upsertViolation(userId, ipAddress, actionType, endpoint);
                });
            } catch (Exception e) {
                LOG.errorf(e, "Failed to record rate limit violation: userId=%s ip=%s action=%s", userId, ipAddress,
                        actionType);
            }
        });
    }

    /**
     * Builds a bucket key for caching sliding windows.
     *
     * <p>
     * Format: {action}:{tier}:{userId|ip}
     */
    private String buildBucketKey(String actionType, String tier, Long userId, String ipAddress) {
        String subject = userId != null ? "u:" + userId : "ip:" + ipAddress;
        return actionType + ":" + tier + ":" + subject;
    }

    /**
     * Extracts action type from legacy bucket key format (e.g., "login:google:1.2.3.4" -> "login").
     */
    private String extractActionFromKey(String key) {
        int colonIndex = key.indexOf(':');
        return colonIndex > 0 ? key.substring(0, colonIndex) : key;
    }

    /**
     * Safely parses a string to Long, returning null on failure.
     */
    private Long parseLongSafe(String str) {
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Removes timestamps outside the rate limit window.
     */
    private void pruneWindow(Deque<Instant> timestamps, Instant now, Duration window) {
        while (!timestamps.isEmpty() && Duration.between(timestamps.peekFirst(), now).compareTo(window) > 0) {
            timestamps.removeFirst();
        }
    }

    /**
     * Immutable rule configuration (legacy support).
     */
    public record RateLimitRule(String name, int maxRequests, Duration window) {

        public RateLimitRule {
            if (maxRequests <= 0) {
                throw new IllegalArgumentException("maxRequests must be positive");
            }
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("window must be positive");
            }
        }

        public static RateLimitRule of(String name, int maxRequests, Duration window) {
            return new RateLimitRule(name, maxRequests, window);
        }
    }
}
