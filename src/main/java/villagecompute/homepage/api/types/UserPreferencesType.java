package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * API type representing user homepage preferences with versioned schema.
 *
 * <p>
 * This type defines the structure of the JSONB data stored in {@code users.preferences}. It includes layout
 * configuration, widget settings, content subscriptions, and theme preferences.
 *
 * <p>
 * <b>Schema Versioning:</b> The {@code schemaVersion} field enables forward-compatible migrations. Services must handle
 * older versions gracefully by applying defaults for missing fields. See
 * {@code docs/architecture/preferences-schema.md} for migration strategy.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P1 (GDPR/CCPA): Preferences are merged during account merges and deleted on account purge</li>
 * <li>P9 (Anonymous Cookie Security): Anonymous users can store preferences before authentication</li>
 * <li>P14 (Rate Limiting): GET/PUT endpoints enforce tier-based rate limits</li>
 * </ul>
 *
 * @param schemaVersion
 *            schema version for migration tracking (current: 1)
 * @param layout
 *            gridstack.js widget layout configuration
 * @param newsTopics
 *            subscribed news categories/interests for feed personalization
 * @param watchlist
 *            stock symbols for the stocks widget
 * @param weatherLocations
 *            saved weather locations (city, coordinates)
 * @param theme
 *            theme preferences (mode, accent, contrast)
 * @param widgetConfigs
 *            per-widget configuration (density, sorting, filters)
 */
public record UserPreferencesType(@JsonProperty("schema_version") @NotNull @Min(1) Integer schemaVersion,
        @NotNull @Valid List<LayoutWidgetType> layout, @JsonProperty("news_topics") @NotNull List<String> newsTopics,
        @NotNull List<String> watchlist,
        @JsonProperty("weather_locations") @NotNull @Valid List<WeatherLocationType> weatherLocations,
        @NotNull @Valid ThemeType theme,
        @JsonProperty("widget_configs") @NotNull Map<String, WidgetConfigType> widgetConfigs) {

    /**
     * Creates default preferences for new users with standard layout.
     *
     * @return UserPreferencesType with default search bar, news feed, weather, and stocks widgets
     */
    public static UserPreferencesType createDefault() {
        return new UserPreferencesType(1,
                List.of(new LayoutWidgetType("search", "search_bar", 0, 0, 12, 2),
                        new LayoutWidgetType("news", "news_feed", 0, 2, 8, 6),
                        new LayoutWidgetType("weather", "weather", 8, 2, 4, 3),
                        new LayoutWidgetType("stocks", "stocks", 8, 5, 4, 3)),
                List.of(), List.of(), List.of(), new ThemeType("system", null, "standard"), Map.of());
    }
}
