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
@Schema(
        description = "Hourly weather forecast entry with temperature and precipitation probability")
public record HourlyForecastType(@Schema(
        description = "ISO 8601 timestamp for this forecast hour",
        example = "2026-01-24T14:00:00Z",
        required = true) @NotBlank String hour,

        @Schema(
                description = "Predicted temperature in Fahrenheit",
                example = "68.2",
                required = true) @NotNull Double temperature,

        @Schema(
                description = "Probability of precipitation percentage (0-100)",
                example = "30",
                required = true) @JsonProperty("precip_probability") @NotNull @Min(0) @Max(100) Integer precipProbability,

        @Schema(
                description = "Weather icon code for frontend rendering",
                example = "09d",
                required = true) @NotBlank String icon) {
}
