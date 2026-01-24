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
@Schema(
        description = "Daily weather forecast entry with high/low temperatures and conditions")
public record DailyForecastType(@Schema(
        description = "ISO 8601 date for this forecast",
        example = "2026-01-24",
        required = true) @NotBlank String date,

        @Schema(
                description = "Predicted high temperature in Fahrenheit",
                example = "75.0",
                required = true) @JsonProperty("temp_high") @NotNull Double tempHigh,

        @Schema(
                description = "Predicted low temperature in Fahrenheit",
                example = "52.3",
                required = true) @JsonProperty("temp_low") @NotNull Double tempLow,

        @Schema(
                description = "Human-readable weather conditions",
                example = "Sunny",
                required = true) @NotBlank String conditions,

        @Schema(
                description = "Weather icon code for frontend rendering",
                example = "01d",
                required = true) @NotBlank String icon,

        @Schema(
                description = "Maximum probability of precipitation percentage (0-100)",
                example = "10",
                required = true) @JsonProperty("precip_probability") @NotNull @Min(0) @Max(100) Integer precipProbability) {
}
