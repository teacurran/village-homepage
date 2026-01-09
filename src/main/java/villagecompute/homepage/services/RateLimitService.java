package villagecompute.homepage.services;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import villagecompute.homepage.observability.LoggingConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Lightweight in-memory rate limiter (Policy P14/F14.2).
 *
 * <p>
 * The long-term plan introduces a database-backed token bucket, but Task I2.T1 needs working guardrails for the auth
 * bootstrap/login flows. This implementation uses sliding windows per bucket and logs violations for observability.
 */
@ApplicationScoped
public class RateLimitService {

    private static final Logger LOG = Logger.getLogger(RateLimitService.class);

    private final ConcurrentMap<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    /**
     * Checks whether a request identified by {@code key} is allowed under the provided rule.
     *
     * @param key
     *            composite identifier (e.g., {@code login:google:198.51.100.5})
     * @param rule
     *            rule definition (max requests + window)
     * @return {@code true} if allowed, {@code false} when throttled
     */
    public boolean check(String key, RateLimitRule rule) {
        Objects.requireNonNull(rule, "RateLimitRule is required");
        Objects.requireNonNull(key, "Rate limit key is required");

        Instant now = Instant.now();
        Deque<Instant> timestamps = buckets.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            pruneWindow(timestamps, now, rule.window());
            if (timestamps.size() >= rule.maxRequests()) {
                LOG.warnf("Rate limit exceeded (rule=%s, bucket=%s, max=%d)", rule.name(), key, rule.maxRequests());
                return false;
            }
            timestamps.addLast(now);
            LoggingConfig.setRateLimitBucket(key);
        }
        return true;
    }

    /**
     * Records a throttled event. Persistence will be added in Task I2.T3; for now we log for audit visibility.
     */
    public void recordViolation(String key, String endpoint, String userId, String ipAddress) {
        LOG.warnf("Rate limit violation: bucket=%s endpoint=%s userId=%s ip=%s", key, endpoint, userId, ipAddress);
    }

    private void pruneWindow(Deque<Instant> timestamps, Instant now, Duration window) {
        while (!timestamps.isEmpty() && Duration.between(timestamps.peekFirst(), now).compareTo(window) > 0) {
            timestamps.removeFirst();
        }
    }

    /**
     * Immutable rule configuration.
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
