package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(
        description = "Request to evaluate a feature flag for a specific user or session")
public record FeatureFlagEvaluationRequestType(@Schema(
        description = "Feature flag identifier to evaluate",
        example = "stocks_widget",
        required = true) @JsonProperty("flag_key") @NotBlank String flagKey,

        @Schema(
                description = "Authenticated user ID (null for anonymous users)",
                example = "12345",
                nullable = true) @JsonProperty("user_id") Long userId,

        @Schema(
                description = "Anonymous session hash (null for authenticated users)",
                example = "abc123def456",
                nullable = true) @JsonProperty("session_hash") String sessionHash,

        @Schema(
                description = "Whether user has consented to analytics logging (Policy P14)",
                example = "false",
                required = true) @JsonProperty("consent_granted") @NotNull Boolean consentGranted) {
}
