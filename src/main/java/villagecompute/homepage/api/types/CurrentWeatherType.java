/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Current weather conditions for the weather widget.
 *
 * <p>
 * Includes temperature, feels-like temperature, humidity, wind conditions, and human-readable weather description with
 * icon code for frontend rendering.
 *
 * @param temperature
 *            current temperature in Fahrenheit
 * @param feelsLike
 *            apparent temperature in Fahrenheit (wind chill or heat index)
 * @param humidity
 *            relative humidity percentage (0-100)
 * @param windSpeed
 *            wind speed in miles per hour
 * @param windDirection
 *            wind direction in degrees (0-359, where 0=North)
 * @param conditions
 *            human-readable weather conditions (e.g., "Sunny", "Partly Cloudy", "Rain")
 * @param icon
 *            weather icon code for frontend rendering (e.g., "01d", "02n")
 */
public record CurrentWeatherType(@NotNull Double temperature, @JsonProperty("feels_like") @NotNull Double feelsLike,
        @NotNull @Min(0) @Max(100) Integer humidity, @JsonProperty("wind_speed") @NotNull Double windSpeed,
        @JsonProperty("wind_direction") @NotNull @Min(0) @Max(359) Integer windDirection, @NotBlank String conditions,
        @NotBlank String icon) {
}
