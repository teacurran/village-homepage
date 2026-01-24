package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(
        description = "Feature flag evaluation result with reason and rollout snapshot")
public record FeatureFlagEvaluationResponseType(@Schema(
        description = "Evaluated feature flag identifier",
        example = "stocks_widget",
        required = true) @JsonProperty("flag_key") String flagKey,

        @Schema(
                description = "Whether the flag is enabled for this user/session",
                example = "true",
                required = true) boolean enabled,

        @Schema(
                description = "Evaluation reason code",
                example = "cohort_enabled",
                enumeration = {
                        "master_disabled", "whitelisted", "cohort_enabled", "cohort_disabled"},
                required = true) String reason,

        @Schema(
                description = "Snapshot of rollout percentage at evaluation time",
                example = "50",
                required = true) @JsonProperty("rollout_percentage") short rolloutPercentage){
}
