package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(
        description = "Feature flag configuration with rollout percentage and whitelist")
public record FeatureFlagType(@Schema(
        description = "Unique feature flag identifier",
        example = "stocks_widget",
        required = true) @JsonProperty("flag_key") @NotBlank String flagKey,

        @Schema(
                description = "Human-readable feature description",
                example = "Enable stock market widget on homepage",
                required = true) @NotBlank String description,

        @Schema(
                description = "Master kill switch state",
                example = "true",
                required = true) @NotNull Boolean enabled,

        @Schema(
                description = "Cohort rollout percentage (0-100)",
                example = "50",
                required = true) @JsonProperty("rollout_percentage") @NotNull @Min(0) @Max(100) Short rolloutPercentage,

        @Schema(
                description = "List of whitelisted user IDs or session hashes",
                example = "[\"user_123\", \"session_abc\"]",
                required = true) @NotNull List<String> whitelist,

        @Schema(
                description = "Whether evaluation logging is enabled (Policy P14)",
                example = "false",
                required = true) @JsonProperty("analytics_enabled") @NotNull Boolean analyticsEnabled,

        @Schema(
                description = "Creation timestamp",
                example = "2026-01-24T10:00:00Z",
                nullable = true) @JsonProperty("created_at") Instant createdAt,

        @Schema(
                description = "Last modification timestamp",
                example = "2026-01-24T12:30:00Z",
                nullable = true) @JsonProperty("updated_at") Instant updatedAt) {
}
