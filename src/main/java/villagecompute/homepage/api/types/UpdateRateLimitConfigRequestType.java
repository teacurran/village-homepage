package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;

/**
 * Request DTO for updating rate limit configurations.
 *
 * <p>
 * Supports partial updates via PATCH semantics. Null fields are ignored and retain their current values.
 *
 * @param limitCount
 *            New limit count (optional, must be positive if provided)
 * @param windowSeconds
 *            New window in seconds (optional, must be positive if provided)
 */
public record UpdateRateLimitConfigRequestType(@JsonProperty("limit_count") @Min(
        value = 1,
        message = "limit_count must be at least 1") Integer limitCount,
        @JsonProperty("window_seconds") @Min(
                value = 1,
                message = "window_seconds must be at least 1") Integer windowSeconds) {
}
