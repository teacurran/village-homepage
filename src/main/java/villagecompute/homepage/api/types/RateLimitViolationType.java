package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Rate limit violation DTO for API responses.
 *
 * <p>
 * Represents a single rate limit violation record for audit and analysis.
 *
 * @param userId
 *            Authenticated user ID (null for anonymous violations)
 * @param ipAddress
 *            Source IP address
 * @param actionType
 *            Action that triggered the violation
 * @param endpoint
 *            HTTP endpoint path
 * @param violationCount
 *            Number of violations since first_violation_at
 * @param firstViolationAt
 *            Timestamp of first violation in window
 * @param lastViolationAt
 *            Timestamp of most recent violation
 */
public record RateLimitViolationType(@JsonProperty("user_id") Long userId, @JsonProperty("ip_address") String ipAddress,
        @JsonProperty("action_type") String actionType, String endpoint,
        @JsonProperty("violation_count") int violationCount,
        @JsonProperty("first_violation_at") Instant firstViolationAt,
        @JsonProperty("last_violation_at") Instant lastViolationAt) {
}
