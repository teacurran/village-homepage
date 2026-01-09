package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * API response type for feature flag evaluation results.
 *
 * @param flagKey
 *            the evaluated feature flag
 * @param enabled
 *            whether the flag is enabled for the subject
 * @param reason
 *            evaluation reason code (master_disabled, whitelisted, cohort_enabled, etc.)
 * @param rolloutPercentage
 *            snapshot of rollout percentage at evaluation time
 */
public record FeatureFlagEvaluationResponseType(@JsonProperty("flag_key") String flagKey, boolean enabled,
        String reason, @JsonProperty("rollout_percentage") short rolloutPercentage) {
}
