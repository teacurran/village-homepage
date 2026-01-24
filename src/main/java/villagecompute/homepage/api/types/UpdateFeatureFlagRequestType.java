package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * API request type for updating feature flag configuration.
 *
 * <p>
 * All fields are optional to support partial updates via PATCH semantics. Null values indicate "no change" rather than
 * "set to null".
 *
 * @param description
 *            new description (null to keep current)
 * @param enabled
 *            new enabled state (null to keep current)
 * @param rolloutPercentage
 *            new rollout percentage [0-100] (null to keep current)
 * @param whitelist
 *            new whitelist entries (null to keep current)
 * @param analyticsEnabled
 *            new analytics toggle (null to keep current)
 * @param reason
 *            optional explanation for audit log
 */
@Schema(
        description = "Request to update feature flag configuration (partial update)")
public record UpdateFeatureFlagRequestType(@Schema(
        description = "New feature description",
        example = "Enable enhanced stock market widget",
        nullable = true) String description,

        @Schema(
                description = "New master kill switch state",
                example = "true",
                nullable = true) Boolean enabled,

        @Schema(
                description = "New rollout percentage (0-100)",
                example = "75",
                nullable = true) @JsonProperty("rollout_percentage") @Min(0) @Max(100) Short rolloutPercentage,

        @Schema(
                description = "New whitelist entries",
                example = "[\"user_123\", \"session_abc\"]",
                nullable = true) List<String> whitelist,

        @Schema(
                description = "New analytics logging toggle",
                example = "false",
                nullable = true) @JsonProperty("analytics_enabled") Boolean analyticsEnabled,

        @Schema(
                description = "Optional explanation for audit log",
                example = "Increasing rollout to 75% for beta testing",
                nullable = true) String reason) {
}
