/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Type representing the complete social widget state with posts, connection status, and staleness indicators.
 *
 * <p>
 * All JSON-marshalled types in this project use the Type suffix and record classes.
 *
 * <p>
 * This type supports Policy P13 staleness banners:
 * <ul>
 * <li>FRESH (< 24 hours) - Green banner</li>
 * <li>SLIGHTLY_STALE (1-3 days) - Yellow banner</li>
 * <li>STALE (4-7 days) - Orange banner with reconnect CTA</li>
 * <li>VERY_STALE (> 7 days) - Red banner with limited functionality</li>
 * </ul>
 *
 * @param platform
 *            "instagram" or "facebook"
 * @param posts
 *            list of social media posts (cached or fresh)
 * @param connectionStatus
 *            connection state: "connected", "disconnected", "stale", "expired"
 * @param staleness
 *            staleness tier: "FRESH", "SLIGHTLY_STALE", "STALE", "VERY_STALE"
 * @param stalenessDays
 *            number of days since last successful fetch
 * @param reconnectUrl
 *            OAuth URL to reconnect integration (null if connected and fresh)
 * @param cachedAt
 *            ISO 8601 timestamp when posts were last fetched
 * @param message
 *            user-facing message explaining current state (e.g., "Showing posts from 3 days ago. Reconnect to
 *            refresh.")
 */
public record SocialWidgetStateType(@NotBlank String platform, @NotNull @Valid List<SocialPostType> posts,
        @JsonProperty("connection_status") @NotBlank String connectionStatus, @NotBlank String staleness,
        @JsonProperty("staleness_days") int stalenessDays, @JsonProperty("reconnect_url") String reconnectUrl,
        @JsonProperty("cached_at") String cachedAt, String message) {
}
