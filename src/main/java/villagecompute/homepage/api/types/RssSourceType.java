package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import villagecompute.homepage.data.models.RssSource;

import java.time.Instant;
import java.util.UUID;

/**
 * API type representing an RSS source for JSON responses.
 *
 * <p>
 * Used in admin CRUD endpoints and user subscription APIs. Converts from {@link RssSource} entity.
 *
 * @param id
 *            unique identifier
 * @param name
 *            feed display name
 * @param url
 *            RSS/Atom feed URL
 * @param category
 *            optional category (Technology, Business, etc.)
 * @param isSystem
 *            system-managed vs user-custom feed
 * @param userId
 *            owner UUID for user-custom feeds (null for system)
 * @param refreshIntervalMinutes
 *            refresh interval (15-1440 minutes)
 * @param isActive
 *            active/disabled status
 * @param lastFetchedAt
 *            last successful fetch timestamp
 * @param errorCount
 *            consecutive error count
 * @param lastErrorMessage
 *            last error message (for debugging)
 * @param createdAt
 *            creation timestamp
 * @param updatedAt
 *            last modification timestamp
 */
@Schema(
        description = "RSS/Atom feed source configuration with refresh settings and error tracking")
public record RssSourceType(@Schema(
        description = "Unique RSS source identifier",
        example = "550e8400-e29b-41d4-a716-446655440000",
        required = true) @NotNull UUID id,

        @Schema(
                description = "Feed display name",
                example = "TechCrunch",
                required = true,
                maxLength = 200) @NotNull String name,

        @Schema(
                description = "RSS/Atom feed URL",
                example = "https://techcrunch.com/feed",
                required = true) @NotNull String url,

        @Schema(
                description = "Optional category",
                example = "Technology",
                nullable = true) String category,

        @Schema(
                description = "Whether this is a system-managed feed",
                example = "true",
                required = true) @JsonProperty("is_system") @NotNull Boolean isSystem,

        @Schema(
                description = "Owner UUID for user-custom feeds (null for system feeds)",
                example = "660e8400-e29b-41d4-a716-446655440001",
                nullable = true) @JsonProperty("user_id") UUID userId,

        @Schema(
                description = "Refresh interval in minutes (15-1440)",
                example = "60",
                required = true) @JsonProperty("refresh_interval_minutes") @NotNull Integer refreshIntervalMinutes,

        @Schema(
                description = "Whether feed is active and being refreshed",
                example = "true",
                required = true) @JsonProperty("is_active") @NotNull Boolean isActive,

        @Schema(
                description = "Last successful fetch timestamp",
                example = "2026-01-24T10:00:00Z",
                nullable = true) @JsonProperty("last_fetched_at") Instant lastFetchedAt,

        @Schema(
                description = "Consecutive error count (resets on success)",
                example = "0",
                required = true) @JsonProperty("error_count") @NotNull Integer errorCount,

        @Schema(
                description = "Last error message for debugging",
                example = "Feed temporarily unavailable (HTTP 503)",
                nullable = true) @JsonProperty("last_error_message") String lastErrorMessage,

        @Schema(
                description = "Creation timestamp",
                example = "2026-01-24T10:00:00Z",
                required = true) @JsonProperty("created_at") @NotNull Instant createdAt,

        @Schema(
                description = "Last modification timestamp",
                example = "2026-01-24T12:30:00Z",
                required = true) @JsonProperty("updated_at") @NotNull Instant updatedAt) {

    /**
     * Converts an RssSource entity to API type.
     *
     * @param source
     *            the entity to convert
     * @return RssSourceType for JSON response
     */
    public static RssSourceType fromEntity(RssSource source) {
        return new RssSourceType(source.id, source.name, source.url, source.category, source.isSystem, source.userId,
                source.refreshIntervalMinutes, source.isActive, source.lastFetchedAt, source.errorCount,
                source.lastErrorMessage, source.createdAt, source.updatedAt);
    }
}
