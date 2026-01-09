package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * API type representing a widget's position and dimensions in the gridstack.js layout grid.
 *
 * <p>
 * Grid system configuration:
 * <ul>
 * <li>Desktop: 12 columns</li>
 * <li>Tablet: 6 columns</li>
 * <li>Mobile: 2 columns</li>
 * </ul>
 *
 * <p>
 * Widget types:
 * <ul>
 * <li>{@code search_bar} - Global search input</li>
 * <li>{@code news_feed} - RSS feed aggregation with AI tagging</li>
 * <li>{@code weather} - Weather widget (Open-Meteo/NWS)</li>
 * <li>{@code stocks} - Stock market data (Alpha Vantage)</li>
 * <li>{@code social_feed} - Instagram/Facebook integration</li>
 * <li>{@code rss_feed} - Custom RSS feed widget</li>
 * <li>{@code quick_links} - User-defined link shortcuts</li>
 * </ul>
 *
 * @param widgetId
 *            unique identifier for this widget instance (e.g., "news", "weather_1")
 * @param widgetType
 *            widget type identifier from the list above
 * @param x
 *            column offset (0-based)
 * @param y
 *            row offset (0-based)
 * @param width
 *            widget width in columns
 * @param height
 *            widget height in rows
 */
public record LayoutWidgetType(@JsonProperty("widget_id") @NotBlank String widgetId,
        @JsonProperty("widget_type") @NotBlank String widgetType, @Min(0) int x, @Min(0) int y, @Min(1) int width,
        @Min(1) int height) {
}
