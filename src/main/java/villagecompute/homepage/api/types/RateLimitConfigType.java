package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Rate limit configuration DTO for API responses.
 *
 * <p>
 * Represents a single rate limit rule for an action type and user tier combination.
 *
 * @param actionType
 *            Action identifier (e.g., "login", "search", "vote")
 * @param tier
 *            User tier ("anonymous", "logged_in", "trusted")
 * @param limitCount
 *            Maximum allowed requests within window
 * @param windowSeconds
 *            Time window in seconds for limit enforcement
 * @param updatedByUserId
 *            User ID who last modified this config (nullable)
 * @param updatedAt
 *            Last modification timestamp
 */
public record RateLimitConfigType(@JsonProperty("action_type") String actionType, String tier,
        @JsonProperty("limit_count") int limitCount, @JsonProperty("window_seconds") int windowSeconds,
        @JsonProperty("updated_by_user_id") Long updatedByUserId, @JsonProperty("updated_at") Instant updatedAt) {
}
