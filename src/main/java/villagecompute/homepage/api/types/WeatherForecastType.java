/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.types;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

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
public record WeatherForecastType(@NotNull @Valid CurrentWeatherType current,
        @NotNull @Valid List<HourlyForecastType> hourly, @NotNull @Valid List<DailyForecastType> daily) {
}
