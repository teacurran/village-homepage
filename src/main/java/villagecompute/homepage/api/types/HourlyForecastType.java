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
 * Hourly weather forecast entry for the weather widget.
 *
 * <p>
 * Used to display the next 24 hours of weather predictions with temperature, precipitation probability, and icon.
 *
 * @param hour
 *            ISO 8601 timestamp for this forecast hour (e.g., "2025-01-09T14:00:00Z")
 * @param temperature
 *            predicted temperature in Fahrenheit
 * @param precipProbability
 *            probability of precipitation percentage (0-100)
 * @param icon
 *            weather icon code for frontend rendering (e.g., "09d" for rain)
 */
public record HourlyForecastType(@NotBlank String hour, @NotNull Double temperature,
        @JsonProperty("precip_probability") @NotNull @Min(0) @Max(100) Integer precipProbability,
        @NotBlank String icon) {
}
