/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.types;

import jakarta.validation.constraints.NotBlank;

/**
 * Severe weather alert from the National Weather Service.
 *
 * <p>
 * Only available for US locations using NWS provider. Open-Meteo does not provide weather alerts.
 *
 * @param event
 *            alert type (e.g., "Severe Thunderstorm Warning", "Winter Storm Watch")
 * @param severity
 *            alert severity level: "Extreme", "Severe", "Moderate", "Minor", "Unknown"
 * @param headline
 *            short alert headline for display
 * @param description
 *            full alert description with details
 * @param expires
 *            ISO 8601 timestamp when alert expires (e.g., "2025-01-09T18:00:00Z")
 */
public record WeatherAlertType(@NotBlank String event, @NotBlank String severity, @NotBlank String headline,
        @NotBlank String description, @NotBlank String expires) {
}
