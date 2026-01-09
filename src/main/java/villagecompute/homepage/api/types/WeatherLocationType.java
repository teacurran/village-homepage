package villagecompute.homepage.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * API type representing a saved weather location for the weather widget.
 *
 * <p>
 * Weather locations are stored in {@code UserPreferencesType.weatherLocations} and used by the WeatherService to fetch
 * forecasts from Open-Meteo (international) or National Weather Service (US).
 *
 * <p>
 * Users can save multiple locations and cycle through them in the weather widget. The widget displays temperature,
 * conditions, and a 5-day forecast for the selected location.
 *
 * @param city
 *            city name (e.g., "San Francisco, CA")
 * @param cityId
 *            optional reference to cities table for geocoding (nullable)
 * @param latitude
 *            latitude coordinate for weather API
 * @param longitude
 *            longitude coordinate for weather API
 */
public record WeatherLocationType(@NotBlank String city, @JsonProperty("city_id") Integer cityId,
        @Min(-90) @Max(90) double latitude, @Min(-180) @Max(180) double longitude) {
}
