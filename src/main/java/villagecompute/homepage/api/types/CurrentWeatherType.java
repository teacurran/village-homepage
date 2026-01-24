/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
@Schema(
        description = "Current weather conditions with temperature, humidity, and wind data")
public record CurrentWeatherType(@Schema(
        description = "Current temperature in Fahrenheit",
        example = "72.5",
        required = true) @NotNull Double temperature,

        @Schema(
                description = "Apparent temperature in Fahrenheit (wind chill or heat index)",
                example = "68.3",
                required = true) @JsonProperty("feels_like") @NotNull Double feelsLike,

        @Schema(
                description = "Relative humidity percentage (0-100)",
                example = "65",
                required = true) @NotNull @Min(0) @Max(100) Integer humidity,

        @Schema(
                description = "Wind speed in miles per hour",
                example = "12.5",
                required = true) @JsonProperty("wind_speed") @NotNull Double windSpeed,

        @Schema(
                description = "Wind direction in degrees (0-359, where 0=North)",
                example = "180",
                required = true) @JsonProperty("wind_direction") @NotNull @Min(0) @Max(359) Integer windDirection,

        @Schema(
                description = "Human-readable weather conditions",
                example = "Partly Cloudy",
                required = true) @NotBlank String conditions,

        @Schema(
                description = "Weather icon code for frontend rendering",
                example = "02d",
                required = true) @NotBlank String icon) {
}
