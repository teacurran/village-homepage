/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Complete weather widget response containing current conditions, hourly forecast, daily forecast, and alerts.
 *
 * <p>
 * Returned by {@code /api/widgets/weather} endpoint for display in the weather widget. Combines data from either
 * Open-Meteo (international) or National Weather Service (US) providers.
 *
 * @param locationName
 *            display name of location (e.g., "San Francisco, CA")
 * @param provider
 *            weather data provider: "open_meteo" or "nws"
 * @param current
 *            current weather conditions
 * @param hourly
 *            hourly forecast for next 24 hours
 * @param daily
 *            daily forecast for next 7 days
 * @param alerts
 *            severe weather alerts (empty list if none or Open-Meteo provider)
 * @param cachedAt
 *            ISO 8601 timestamp when this data was cached
 * @param stale
 *            true if cache is older than 90 minutes (warn user to refresh)
 */
public record WeatherWidgetType(@JsonProperty("location_name") @NotBlank String locationName, @NotBlank String provider,
        @NotNull @Valid CurrentWeatherType current, @NotNull @Valid List<HourlyForecastType> hourly,
        @NotNull @Valid List<DailyForecastType> daily, @NotNull @Valid List<WeatherAlertType> alerts,
        @JsonProperty("cached_at") @NotBlank String cachedAt, boolean stale) {
}
