package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * API type for creating a new RSS source (admin-only).
 *
 * <p>
 * Used in {@code POST /admin/api/feeds/sources} endpoint. Validates URL format and refresh interval constraints.
 *
 * @param name
 *            feed display name (1-255 characters)
 * @param url
 *            RSS/Atom feed URL (must be valid HTTP/HTTPS)
 * @param category
 *            optional category (Technology, Business, Science, etc.)
 * @param refreshIntervalMinutes
 *            refresh interval (15-1440 minutes, default 60)
 */
public record CreateRssSourceRequestType(@NotBlank @Size(
        min = 1,
        max = 255) String name,
        @NotBlank @Pattern(
                regexp = "^https?://.*",
                message = "URL must start with http:// or https://") String url,
        String category, @JsonProperty("refresh_interval_minutes") @Min(15) @Max(1440) Integer refreshIntervalMinutes) {
}
