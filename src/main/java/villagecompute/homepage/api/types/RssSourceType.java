package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
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
public record RssSourceType(@NotNull UUID id, @NotNull String name, @NotNull String url, String category,
        @JsonProperty("is_system") @NotNull Boolean isSystem, @JsonProperty("user_id") UUID userId,
        @JsonProperty("refresh_interval_minutes") @NotNull Integer refreshIntervalMinutes,
        @JsonProperty("is_active") @NotNull Boolean isActive, @JsonProperty("last_fetched_at") Instant lastFetchedAt,
        @JsonProperty("error_count") @NotNull Integer errorCount,
        @JsonProperty("last_error_message") String lastErrorMessage,
        @JsonProperty("created_at") @NotNull Instant createdAt,
        @JsonProperty("updated_at") @NotNull Instant updatedAt) {

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
