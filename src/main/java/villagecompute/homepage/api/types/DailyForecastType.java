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
 * Daily weather forecast entry for the weather widget.
 *
 * <p>
 * Used to display 7-day forecast with high/low temperatures, conditions, precipitation probability, and icon.
 *
 * @param date
 *            ISO 8601 date for this forecast (e.g., "2025-01-09")
 * @param tempHigh
 *            predicted high temperature in Fahrenheit
 * @param tempLow
 *            predicted low temperature in Fahrenheit
 * @param conditions
 *            human-readable weather conditions (e.g., "Sunny", "Thunderstorms")
 * @param icon
 *            weather icon code for frontend rendering
 * @param precipProbability
 *            maximum probability of precipitation percentage (0-100)
 */
public record DailyForecastType(@NotBlank String date, @JsonProperty("temp_high") @NotNull Double tempHigh,
        @JsonProperty("temp_low") @NotNull Double tempLow, @NotBlank String conditions, @NotBlank String icon,
        @JsonProperty("precip_probability") @NotNull @Min(0) @Max(100) Integer precipProbability) {
}
