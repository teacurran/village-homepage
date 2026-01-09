package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * API type for updating an RSS source (admin-only).
 *
 * <p>
 * Used in {@code PATCH /admin/api/feeds/sources/{id}} endpoint. All fields are optional; only provided fields are
 * updated.
 *
 * @param name
 *            feed display name (1-255 characters)
 * @param category
 *            category (Technology, Business, Science, etc.)
 * @param refreshIntervalMinutes
 *            refresh interval (15-1440 minutes)
 * @param isActive
 *            active/disabled status
 */
public record UpdateRssSourceRequestType(@Size(
        min = 1,
        max = 255) String name, String category,
        @JsonProperty("refresh_interval_minutes") @Min(15) @Max(1440) Integer refreshIntervalMinutes,
        @JsonProperty("is_active") Boolean isActive) {
}
