package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * AdminKarmaAdjustmentType represents an admin request to manually adjust a user's karma.
 *
 * <p>
 * Request body for admin karma adjustment endpoint.
 * </p>
 *
 * @param delta
 *            Karma change (positive or negative, non-zero)
 * @param reason
 *            Human-readable reason for adjustment (required)
 * @param metadata
 *            Optional additional context
 */
public record AdminKarmaAdjustmentType(@JsonProperty("delta") int delta, @JsonProperty("reason") String reason,
        @JsonProperty("metadata") Map<String, Object> metadata) {
}
