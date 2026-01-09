package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * API request type for feature flag evaluation.
 *
 * <p>
 * Used by frontend components to evaluate multiple flags in a single batch request. Supports both authenticated
 * (user_id) and anonymous (session_hash) contexts.
 *
 * @param flagKey
 *            the feature flag to evaluate
 * @param userId
 *            authenticated user ID (null for anonymous)
 * @param sessionHash
 *            anonymous session hash (null for authenticated)
 * @param consentGranted
 *            whether user has consented to analytics logging (Policy P14)
 */
public record FeatureFlagEvaluationRequestType(@JsonProperty("flag_key") @NotBlank String flagKey,
        @JsonProperty("user_id") Long userId, @JsonProperty("session_hash") String sessionHash,
        @JsonProperty("consent_granted") @NotNull Boolean consentGranted) {
}
