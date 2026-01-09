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
import villagecompute.homepage.api.types.WeatherForecastType;

/**
 * HTTP client for Open-Meteo weather API (international coverage).
 *
 * <p>
 * Open-Meteo provides free weather forecast data with no API key required. It uses WMO weather codes and returns
 * structured JSON with current conditions, hourly forecasts, and daily forecasts.
 *
 * <h2>API Details</h2>
 * <ul>
 * <li>Base URL: https://api.open-meteo.com/v1/forecast</li>
 * <li>Rate limit: 10,000 calls/day (free tier)</li>
 * <li>No authentication required</li>
 * <li>Units: Fahrenheit, MPH, inches (configured via query params)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * {@code
 * @Inject
 * OpenMeteoClient client;
 *
 * WeatherForecastType forecast = client.fetchForecast(37.7749, -122.4194);
 * }
 * </pre>
 *
 * @see <a href="https://open-meteo.com/en/docs">Open-Meteo API Documentation</a>
 */
@ApplicationScoped
public class OpenMeteoClient {

    private static final Logger LOG = Logger.getLogger(OpenMeteoClient.class);

    private static final String API_BASE = "https://api.open-meteo.com/v1/forecast";
    private static final int TIMEOUT_SECONDS = 10;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Inject
    public OpenMeteoClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * Fetches weather forecast from Open-Meteo API.
     *
     * @param latitude
     *            latitude coordinate (-90 to 90)
     * @param longitude
     *            longitude coordinate (-180 to 180)
     * @return weather forecast with current conditions, 24-hour hourly, and 7-day daily forecasts
     * @throws RuntimeException
     *             if API request fails or response parsing fails
     */
    public WeatherForecastType fetchForecast(double latitude, double longitude) {
        String url = buildApiUrl(latitude, longitude);
        LOG.debugf("Fetching Open-Meteo forecast for %.2f,%.2f", latitude, longitude);

        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "Open-Meteo API returned status " + response.statusCode() + ": " + response.body());
            }

            return parseForecast(response.body());

        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch Open-Meteo forecast for %.2f,%.2f", latitude, longitude);
            throw new RuntimeException("Failed to fetch Open-Meteo forecast", e);
        }
    }

    /**
     * Builds Open-Meteo API URL with required parameters.
     */
    private String buildApiUrl(double latitude, double longitude) {
        return String.format(
                "%s?latitude=%f&longitude=%f" + "&current=temperature_2m,relative_humidity_2m,"
                        + "apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_direction_10m"
                        + "&hourly=temperature_2m,precipitation_probability,precipitation,weather_code"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,"
                        + "precipitation_probability_max,wind_speed_10m_max"
                        + "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch&timezone=auto",
                API_BASE, latitude, longitude);
    }

    /**
     * Parses Open-Meteo JSON response into WeatherForecastType.
     */
    private WeatherForecastType parseForecast(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);

        CurrentWeatherType current = parseCurrent(root.path("current"));
        List<HourlyForecastType> hourly = parseHourly(root.path("hourly"));
        List<DailyForecastType> daily = parseDaily(root.path("daily"));

        return new WeatherForecastType(current, hourly, daily);
    }

    /**
     * Parses current weather conditions from JSON.
     */
    private CurrentWeatherType parseCurrent(JsonNode current) {
        double temperature = current.path("temperature_2m").asDouble();
        double feelsLike = current.path("apparent_temperature").asDouble();
        int humidity = current.path("relative_humidity_2m").asInt();
        double windSpeed = current.path("wind_speed_10m").asDouble();
        int windDirection = current.path("wind_direction_10m").asInt();
        int weatherCode = current.path("weather_code").asInt();

        String conditions = WeatherCodeMapper.mapConditions(weatherCode);
        String icon = WeatherCodeMapper.mapIcon(weatherCode);

        return new CurrentWeatherType(temperature, feelsLike, humidity, windSpeed, windDirection, conditions, icon);
    }

    /**
     * Parses hourly forecast (next 24 hours) from JSON.
     */
    private List<HourlyForecastType> parseHourly(JsonNode hourly) {
        List<HourlyForecastType> forecasts = new ArrayList<>();
        JsonNode times = hourly.path("time");
        JsonNode temps = hourly.path("temperature_2m");
        JsonNode precipProbs = hourly.path("precipitation_probability");
        JsonNode codes = hourly.path("weather_code");

        // Return next 24 hours
        int limit = Math.min(24, times.size());
        for (int i = 0; i < limit; i++) {
            String hour = times.get(i).asText();
            double temperature = temps.get(i).asDouble();
            int precipProbability = precipProbs.get(i).asInt(0);
            int weatherCode = codes.get(i).asInt(0);
            String icon = WeatherCodeMapper.mapIcon(weatherCode);

            forecasts.add(new HourlyForecastType(hour, temperature, precipProbability, icon));
        }

        return forecasts;
    }

    /**
     * Parses daily forecast (7 days) from JSON.
     */
    private List<DailyForecastType> parseDaily(JsonNode daily) {
        List<DailyForecastType> forecasts = new ArrayList<>();
        JsonNode dates = daily.path("time");
        JsonNode maxTemps = daily.path("temperature_2m_max");
        JsonNode minTemps = daily.path("temperature_2m_min");
        JsonNode precipProbs = daily.path("precipitation_probability_max");
        JsonNode codes = daily.path("weather_code");

        // Return 7 days
        int limit = Math.min(7, dates.size());
        for (int i = 0; i < limit; i++) {
            String date = dates.get(i).asText();
            double tempHigh = maxTemps.get(i).asDouble();
            double tempLow = minTemps.get(i).asDouble();
            int precipProbability = precipProbs.get(i).asInt(0);
            int weatherCode = codes.get(i).asInt(0);
            String conditions = WeatherCodeMapper.mapConditions(weatherCode);
            String icon = WeatherCodeMapper.mapIcon(weatherCode);

            forecasts.add(new DailyForecastType(date, tempHigh, tempLow, conditions, icon, precipProbability));
        }

        return forecasts;
    }
}
