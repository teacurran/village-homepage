package villagecompute.homepage.api.types;

import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * API type representing per-widget configuration settings.
 *
 * <p>
 * Widget configs are stored in {@code UserPreferencesType.widgetConfigs} as a map keyed by widget ID. Each widget can
 * define its own configuration schema, with common fields for density and sorting.
 *
 * <p>
 * <b>Common Fields:</b>
 * <ul>
 * <li>{@code density} - Display density: "comfortable" (default padding), "compact" (reduced padding)</li>
 * <li>{@code sort} - Widget-specific sorting preference (e.g., "date_desc", "popularity")</li>
 * <li>{@code filters} - Arbitrary key-value pairs for widget-specific filters</li>
 * </ul>
 *
 * <p>
 * <b>Example Configurations:</b>
 * <ul>
 * <li>News Feed: {@code {density: "compact", sort: "date_desc", filters: {category: "tech"}}}</li>
 * <li>Stocks Widget: {@code {density: "comfortable", sort: "alphabetical", filters: {}}}</li>
 * <li>Weather Widget: {@code {density: "comfortable", sort: null, filters: {units: "imperial"}}}</li>
 * </ul>
 *
 * @param density
 *            display density: "comfortable" or "compact" (nullable for default)
 * @param sort
 *            widget-specific sorting preference (nullable)
 * @param filters
 *            flexible key-value map for widget-specific filters (empty map if no filters)
 */
public record WidgetConfigType(@Pattern(
        regexp = "^(comfortable|compact)$") String density, String sort, Map<String, Object> filters) {
}
