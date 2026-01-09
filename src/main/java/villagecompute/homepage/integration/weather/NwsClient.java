/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.integration.weather;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import villagecompute.homepage.api.types.CurrentWeatherType;
import villagecompute.homepage.api.types.DailyForecastType;
import villagecompute.homepage.api.types.HourlyForecastType;
import villagecompute.homepage.api.types.WeatherAlertType;
import villagecompute.homepage.api.types.WeatherForecastType;

/**
 * HTTP client for National Weather Service API (US locations only).
 *
 * <p>
 * NWS provides official US government weather data with no API key required. The API returns human-readable forecast
 * periods and severe weather alerts.
 *
 * <h2>API Details</h2>
 * <ul>
 * <li>Base URL: https://api.weather.gov</li>
 * <li>No rate limit published (reasonable use expected)</li>
 * <li>No authentication required</li>
 * <li>REQUIRES User-Agent header with contact info</li>
 * <li>Two-step fetch: /points → /forecast URL</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * {@code
 * @Inject
 * NwsClient client;
 *
 * WeatherForecastType forecast = client.fetchForecast(37.7749, -122.4194);
 * List<WeatherAlertType> alerts = client.fetchAlerts(37.7749, -122.4194);
 * }
 * </pre>
 *
 * @see <a href="https://weather-gov.github.io/api/">NWS API Documentation</a>
 */
@ApplicationScoped
public class NwsClient {

    private static final Logger LOG = Logger.getLogger(NwsClient.class);

    private static final String API_BASE = "https://api.weather.gov";
    private static final String USER_AGENT = "(Village Homepage, homepage@villagecompute.com)";
    private static final int TIMEOUT_SECONDS = 10;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Inject
    public NwsClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * Fetches weather forecast from NWS API (two-step process).
     *
     * @param latitude
     *            latitude coordinate (US bounds: 24-49)
     * @param longitude
     *            longitude coordinate (US bounds: -125 to -66)
     * @return weather forecast with current conditions, 24-hour hourly, and 7-day daily forecasts
     * @throws RuntimeException
     *             if API request fails or response parsing fails
     */
    public WeatherForecastType fetchForecast(double latitude, double longitude) {
        LOG.debugf("Fetching NWS forecast for %.4f,%.4f", latitude, longitude);

        try {
            // Step 1: Get gridpoint metadata
            String pointsUrl = String.format("%s/points/%.4f,%.4f", API_BASE, latitude, longitude);
            JsonNode pointsData = fetchJson(pointsUrl);

            String forecastUrl = pointsData.path("properties").path("forecast").asText();
            String forecastHourlyUrl = pointsData.path("properties").path("forecastHourly").asText();

            if (forecastUrl.isEmpty() || forecastHourlyUrl.isEmpty()) {
                throw new RuntimeException("NWS points response missing forecast URLs");
            }

            // Step 2: Fetch daily and hourly forecasts
            JsonNode dailyData = fetchJson(forecastUrl);
            JsonNode hourlyData = fetchJson(forecastHourlyUrl);

            return parseForecast(dailyData, hourlyData);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch NWS forecast for %.4f,%.4f", latitude, longitude);
            throw new RuntimeException("Failed to fetch NWS forecast", e);
        }
    }

    /**
     * Fetches active weather alerts for location.
     *
     * @param latitude
     *            latitude coordinate
     * @param longitude
     *            longitude coordinate
     * @return list of active weather alerts (empty if none)
     */
    public List<WeatherAlertType> fetchAlerts(double latitude, double longitude) {
        String alertsUrl = String.format("%s/alerts/active?point=%.4f,%.4f", API_BASE, latitude, longitude);
        LOG.debugf("Fetching NWS alerts for %.4f,%.4f", latitude, longitude);

        try {
            JsonNode alertsData = fetchJson(alertsUrl);
            return parseAlerts(alertsData);

        } catch (Exception e) {
            LOG.warnf(e, "Failed to fetch NWS alerts for %.4f,%.4f (non-critical)", latitude, longitude);
            return List.of(); // Alerts are optional, don't fail on error
        }
    }

    /**
     * Fetches JSON from NWS API with required User-Agent header.
     */
    private JsonNode fetchJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).GET().build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("NWS API returned status " + response.statusCode() + ": " + response.body());
        }

        return objectMapper.readTree(response.body());
    }

    /**
     * Parses NWS forecast response into WeatherForecastType.
     * <p>
     * NWS returns period-based forecasts (e.g., "Today", "Tonight", "Tomorrow"). We extract current conditions from the
     * first period and build daily/hourly forecasts.
     */
    private WeatherForecastType parseForecast(JsonNode dailyData, JsonNode hourlyData) {
        JsonNode dailyPeriods = dailyData.path("properties").path("periods");
        JsonNode hourlyPeriods = hourlyData.path("properties").path("periods");

        CurrentWeatherType current = parseCurrentFromPeriods(dailyPeriods, hourlyPeriods);
        List<HourlyForecastType> hourly = parseHourlyPeriods(hourlyPeriods);
        List<DailyForecastType> daily = parseDailyPeriods(dailyPeriods);

        return new WeatherForecastType(current, hourly, daily);
    }

    /**
     * Extracts current conditions from first forecast period.
     * <p>
     * NWS doesn't provide a dedicated "current" endpoint, so we use the most recent hourly period as current.
     */
    private CurrentWeatherType parseCurrentFromPeriods(JsonNode dailyPeriods, JsonNode hourlyPeriods) {
        if (hourlyPeriods.isEmpty()) {
            throw new RuntimeException("NWS hourly forecast is empty");
        }

        JsonNode firstHour = hourlyPeriods.get(0);
        double temperature = firstHour.path("temperature").asDouble();
        int humidity = firstHour.path("relativeHumidity").path("value").asInt(50); // May be null
        String windSpeed = firstHour.path("windSpeed").asText("0 mph");
        String windDirection = firstHour.path("windDirection").asText("N");
        String shortForecast = firstHour.path("shortForecast").asText("Unknown");
        String icon = firstHour.path("icon").asText("");

        // Parse wind speed (format: "10 mph" or "5 to 10 mph")
        double windSpeedValue = parseWindSpeed(windSpeed);

        // Map wind direction to degrees (approximate)
        int windDirectionDegrees = mapWindDirectionToDegrees(windDirection);

        // Extract simple icon code from NWS icon URL
        String iconCode = extractIconCode(icon);

        // NWS doesn't provide feels-like, use actual temp
        double feelsLike = temperature;

        return new CurrentWeatherType(temperature, feelsLike, humidity, windSpeedValue, windDirectionDegrees,
                shortForecast, iconCode);
    }

    /**
     * Parses hourly forecast periods (next 24 hours).
     */
    private List<HourlyForecastType> parseHourlyPeriods(JsonNode periods) {
        List<HourlyForecastType> forecasts = new ArrayList<>();
        int limit = Math.min(24, periods.size());

        for (int i = 0; i < limit; i++) {
            JsonNode period = periods.get(i);
            String startTime = period.path("startTime").asText();
            double temperature = period.path("temperature").asDouble();
            int precipProbability = period.path("probabilityOfPrecipitation").path("value").asInt(0);
            String icon = period.path("icon").asText("");
            String iconCode = extractIconCode(icon);

            forecasts.add(new HourlyForecastType(startTime, temperature, precipProbability, iconCode));
        }

        return forecasts;
    }

    /**
     * Parses daily forecast periods (7 days).
     * <p>
     * NWS periods alternate day/night (e.g., "Today", "Tonight", "Thursday", "Thursday Night"). We combine pairs into
     * single daily forecasts.
     */
    private List<DailyForecastType> parseDailyPeriods(JsonNode periods) {
        List<DailyForecastType> forecasts = new ArrayList<>();
        int dayCount = 0;

        for (int i = 0; i < periods.size() && dayCount < 7; i++) {
            JsonNode period = periods.get(i);
            boolean isDaytime = period.path("isDaytime").asBoolean();

            if (!isDaytime) {
                continue; // Skip night periods, we only want daytime
            }

            String name = period.path("name").asText();
            String startTime = period.path("startTime").asText();
            double tempHigh = period.path("temperature").asDouble();
            String shortForecast = period.path("shortForecast").asText();
            String icon = period.path("icon").asText("");
            int precipProbability = period.path("probabilityOfPrecipitation").path("value").asInt(0);

            // Get night period for low temp
            double tempLow = tempHigh - 10; // Default estimate
            if (i + 1 < periods.size()) {
                JsonNode nightPeriod = periods.get(i + 1);
                tempLow = nightPeriod.path("temperature").asDouble();
            }

            // Extract date from ISO timestamp
            String date = startTime.split("T")[0];
            String iconCode = extractIconCode(icon);

            forecasts.add(new DailyForecastType(date, tempHigh, tempLow, shortForecast, iconCode, precipProbability));
            dayCount++;
        }

        return forecasts;
    }

    /**
     * Parses NWS alerts response.
     */
    private List<WeatherAlertType> parseAlerts(JsonNode alertsData) {
        List<WeatherAlertType> alerts = new ArrayList<>();
        JsonNode features = alertsData.path("features");

        for (JsonNode feature : features) {
            JsonNode properties = feature.path("properties");
            String event = properties.path("event").asText();
            String severity = properties.path("severity").asText("Unknown");
            String headline = properties.path("headline").asText();
            String description = properties.path("description").asText();
            String expires = properties.path("expires").asText();

            alerts.add(new WeatherAlertType(event, severity, headline, description, expires));
        }

        return alerts;
    }

    /**
     * Parses wind speed from NWS format (e.g., "10 mph", "5 to 10 mph").
     */
    private double parseWindSpeed(String windSpeed) {
        try {
            // Extract first number from string
            String[] parts = windSpeed.split(" ");
            return Double.parseDouble(parts[0]);
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * Maps cardinal wind direction to degrees.
     */
    private int mapWindDirectionToDegrees(String direction) {
        return switch (direction.toUpperCase()) {
            case "N" -> 0;
            case "NE" -> 45;
            case "E" -> 90;
            case "SE" -> 135;
            case "S" -> 180;
            case "SW" -> 225;
            case "W" -> 270;
            case "NW" -> 315;
            default -> 0;
        };
    }

    /**
     * Extracts icon code from NWS icon URL.
     * <p>
     * NWS icon URLs format: https://api.weather.gov/icons/land/day/rain,40?size=medium We extract the weather type
     * (e.g., "rain") and map to standard icon codes.
     */
    private String extractIconCode(String iconUrl) {
        if (iconUrl.isEmpty()) {
            return "01d";
        }

        try {
            // Extract path segment after /icons/land/day/ or /icons/land/night/
            String[] parts = iconUrl.split("/");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals("day") || parts[i].equals("night")) {
                    if (i + 1 < parts.length) {
                        String weatherType = parts[i + 1].split("[,?]")[0]; // Remove probability/size params
                        return mapNwsIconToCode(weatherType);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to parse NWS icon URL: %s", iconUrl);
        }

        return "01d"; // Default to clear sky
    }

    /**
     * Maps NWS weather type to standard icon code.
     */
    private String mapNwsIconToCode(String weatherType) {
        return switch (weatherType.toLowerCase()) {
            case "skc", "few", "sct" -> "01d"; // Clear/few clouds
            case "bkn" -> "03d"; // Broken clouds
            case "ovc" -> "04d"; // Overcast
            case "rain", "rain_showers" -> "10d";
            case "tsra", "tsra_sct", "tsra_hi" -> "11d"; // Thunderstorm
            case "snow", "snow_fzra" -> "13d";
            case "fog" -> "50d";
            default -> "01d";
        };
    }
}
