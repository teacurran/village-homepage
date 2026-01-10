package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AdminTrustLevelChangeType represents an admin request to manually change a user's trust level.
 *
 * <p>
 * Request body for admin trust level change endpoint.
 * </p>
 *
 * @param trustLevel
 *            New trust level (untrusted, trusted, moderator)
 * @param reason
 *            Human-readable reason for change (required)
 */
public record AdminTrustLevelChangeType(@JsonProperty("trust_level") String trustLevel,
        @JsonProperty("reason") String reason) {
}
