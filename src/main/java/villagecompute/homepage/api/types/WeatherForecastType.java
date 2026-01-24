/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.types;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Internal type for storing weather forecast data in JSONB weather_cache.forecast_data column.
 *
 * <p>
 * This is a unified structure that can represent data from either Open-Meteo or NWS providers. It's stored in the
 * database and transformed into {@link WeatherWidgetType} for API responses.
 *
 * @param current
 *            current weather conditions
 * @param hourly
 *            hourly forecast entries (24 hours)
 * @param daily
 *            daily forecast entries (7 days)
 */
@Schema(
        description = "Complete weather forecast with current conditions, hourly and daily predictions")
public record WeatherForecastType(@Schema(
        description = "Current weather conditions",
        required = true) @NotNull @Valid CurrentWeatherType current,

        @Schema(
                description = "Hourly forecast entries for next 24 hours",
                required = true) @NotNull @Valid List<HourlyForecastType> hourly,

        @Schema(
                description = "Daily forecast entries for next 7 days",
                required = true) @NotNull @Valid List<DailyForecastType> daily) {
}
