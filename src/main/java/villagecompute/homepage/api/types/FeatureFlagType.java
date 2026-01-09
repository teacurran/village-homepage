package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * API response type for feature flag representation.
 *
 * <p>
 * Used in admin endpoints to expose flag configuration without leaking internal entity details. All JSON marshaling
 * flows through this type per VillageCompute Java Project Standards.
 *
 * @param flagKey
 *            unique feature flag identifier
 * @param description
 *            human-readable feature description
 * @param enabled
 *            master kill switch state
 * @param rolloutPercentage
 *            cohort rollout percentage [0-100]
 * @param whitelist
 *            list of whitelisted user IDs or session hashes
 * @param analyticsEnabled
 *            whether evaluation logging is enabled (Policy P14)
 * @param createdAt
 *            record creation timestamp
 * @param updatedAt
 *            last modification timestamp
 */
public record FeatureFlagType(@JsonProperty("flag_key") @NotBlank String flagKey, @NotBlank String description,
        @NotNull Boolean enabled,
        @JsonProperty("rollout_percentage") @NotNull @Min(0) @Max(100) Short rolloutPercentage,
        @NotNull List<String> whitelist, @JsonProperty("analytics_enabled") @NotNull Boolean analyticsEnabled,
        @JsonProperty("created_at") Instant createdAt, @JsonProperty("updated_at") Instant updatedAt) {
}
