package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

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
public record UpdateFeatureFlagRequestType(String description, Boolean enabled,
        @JsonProperty("rollout_percentage") @Min(0) @Max(100) Short rolloutPercentage, List<String> whitelist,
        @JsonProperty("analytics_enabled") Boolean analyticsEnabled, String reason) {
}
