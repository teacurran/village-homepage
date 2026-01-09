/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.services;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import villagecompute.homepage.api.types.WeatherAlertType;
import villagecompute.homepage.api.types.WeatherForecastType;
import villagecompute.homepage.api.types.WeatherLocationType;
import villagecompute.homepage.api.types.WeatherWidgetType;
import villagecompute.homepage.data.models.WeatherCache;
import villagecompute.homepage.integration.weather.NwsClient;
import villagecompute.homepage.integration.weather.OpenMeteoClient;

/**
 * Service for fetching and caching weather data from Open-Meteo (international) and NWS (US) providers.
 *
 * <p>
 * Implements cache-first strategy with configurable TTL (1 hour for forecasts, 15 minutes for alerts). Falls back to
 * stale cache if API fails. Supports provider selection based on geographic location.
 *
 * <h2>Provider Selection</h2>
 * <ul>
 * <li>US locations (lat 24-49, lon -125 to -66): NWS</li>
 * <li>International locations: Open-Meteo</li>
 * <li>Fallback: If NWS fails, try Open-Meteo</li>
 * </ul>
 *
 * <h2>Caching Strategy</h2>
 * <ul>
 * <li>Cache key: lat:lon rounded to 2 decimals</li>
 * <li>Forecast TTL: 1 hour</li>
 * <li>Alert TTL: 15 minutes</li>
 * <li>Stale threshold: 90 minutes</li>
 * <li>On API failure: serve stale cache OR return error</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * {@code
 * @Inject
 * WeatherService weatherService;
 *
 * WeatherLocationType location = new WeatherLocationType("San Francisco, CA", null, 37.77, -122.42);
 * WeatherWidgetType weather = weatherService.getWeather(location);
 * }
 * </pre>
 */
@ApplicationScoped
public class WeatherService {

    private static final Logger LOG = Logger.getLogger(WeatherService.class);

    // US approximate bounds for NWS provider selection
    private static final double US_LAT_MIN = 24.0;
    private static final double US_LAT_MAX = 49.0;
    private static final double US_LON_MIN = -125.0;
    private static final double US_LON_MAX = -66.0;

    // Cache expiration times
    private static final int FORECAST_CACHE_HOURS = 1;
    private static final int ALERT_CACHE_MINUTES = 15;
    private static final int STALE_THRESHOLD_MINUTES = 90;

    @Inject
    OpenMeteoClient openMeteoClient;

    @Inject
    NwsClient nwsClient;

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Gets weather data for location (cache-first).
     *
     * @param location
     *            weather location with coordinates
     * @return weather widget data with current/hourly/daily/alerts
     * @throws RuntimeException
     *             if both cache miss and API fetch fail
     */
    @Transactional
    public WeatherWidgetType getWeather(WeatherLocationType location) {
        String locationKey = WeatherCache.generateLocationKey(location.latitude(), location.longitude());
        Span span = tracer.spanBuilder("weather.get").setAttribute("location_key", locationKey)
                .setAttribute("city", location.city()).startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Try cache first
            Optional<WeatherCache> cache = WeatherCache.findValidCache(locationKey);

            if (cache.isPresent()) {
                incrementCounter("weather.cache.hits");
                span.setAttribute("cache_hit", true);
                LOG.debugf("Weather cache hit for %s", locationKey);
                return buildWidgetType(location.city(), cache.get());
            }

            // Cache miss - fetch from API
            incrementCounter("weather.cache.misses");
            span.setAttribute("cache_hit", false);
            LOG.debugf("Weather cache miss for %s, fetching from API", locationKey);

            return fetchAndCache(location, locationKey, span);

        } catch (Exception e) {
            span.recordException(e);
            LOG.errorf(e, "Failed to get weather for %s", locationKey);

            // Try to serve stale cache as fallback
            Optional<WeatherCache> staleCache = WeatherCache.findByLocationKey(locationKey);
            if (staleCache.isPresent()) {
                LOG.warnf("Serving stale cache for %s due to API failure", locationKey);
                incrementCounter("weather.cache.stale_served");
                return buildWidgetType(location.city(), staleCache.get());
            }

            throw new RuntimeException("Weather data unavailable for " + location.city(), e);
        } finally {
            span.end();
        }
    }

    /**
     * Refreshes weather cache for location (bypasses cache).
     * <p>
     * Used by job handler to proactively refresh cache.
     *
     * @param location
     *            weather location
     */
    @Transactional
    public void refreshCache(WeatherLocationType location) {
        String locationKey = WeatherCache.generateLocationKey(location.latitude(), location.longitude());
        LOG.debugf("Refreshing weather cache for %s", locationKey);

        Span span = tracer.spanBuilder("weather.refresh").setAttribute("location_key", locationKey)
                .setAttribute("city", location.city()).startSpan();

        try (Scope scope = span.makeCurrent()) {
            fetchAndCache(location, locationKey, span);
        } catch (Exception e) {
            span.recordException(e);
            LOG.errorf(e, "Failed to refresh weather cache for %s", locationKey);
            incrementCounter("weather.refresh.failures", "location_key", locationKey);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Fetches weather from appropriate provider and updates cache.
     */
    private WeatherWidgetType fetchAndCache(WeatherLocationType location, String locationKey, Span span) {
        boolean isUS = isUSLocation(location.latitude(), location.longitude());
        String provider = isUS ? "nws" : "open_meteo";
        span.setAttribute("provider", provider);

        WeatherForecastType forecast;
        List<WeatherAlertType> alerts = List.of();

        try {
            Timer.Sample sample = Timer.start(meterRegistry);

            if (isUS) {
                // Try NWS first
                try {
                    forecast = nwsClient.fetchForecast(location.latitude(), location.longitude());
                    alerts = nwsClient.fetchAlerts(location.latitude(), location.longitude());
                    sample.stop(Timer.builder("weather.api.duration").tag("provider", "nws").register(meterRegistry));
                    incrementCounter("weather.fetch.total", "provider", "nws", "status", "success");
                } catch (Exception nwsError) {
                    LOG.warnf(nwsError, "NWS failed for %s, falling back to Open-Meteo", locationKey);
                    incrementCounter("weather.fetch.total", "provider", "nws", "status", "failure");
                    span.setAttribute("nws_fallback", true);

                    // Fallback to Open-Meteo
                    forecast = openMeteoClient.fetchForecast(location.latitude(), location.longitude());
                    provider = "open_meteo";
                    sample.stop(Timer.builder("weather.api.duration").tag("provider", "open_meteo_fallback")
                            .register(meterRegistry));
                    incrementCounter("weather.fetch.total", "provider", "open_meteo", "status", "success");
                }
            } else {
                // International - use Open-Meteo
                forecast = openMeteoClient.fetchForecast(location.latitude(), location.longitude());
                sample.stop(
                        Timer.builder("weather.api.duration").tag("provider", "open_meteo").register(meterRegistry));
                incrementCounter("weather.fetch.total", "provider", "open_meteo", "status", "success");
            }

        } catch (Exception e) {
            incrementCounter("weather.fetch.total", "provider", provider, "status", "failure");
            throw e;
        }

        // Update cache
        updateCache(locationKey, provider, forecast, alerts);

        // Build response
        WeatherCache cached = WeatherCache.findByLocationKey(locationKey).orElseThrow();
        return buildWidgetType(location.city(), cached);
    }

    /**
     * Updates or creates cache entry.
     */
    private void updateCache(String locationKey, String provider, WeatherForecastType forecast,
            List<WeatherAlertType> alerts) {
        Optional<WeatherCache> existing = WeatherCache.findByLocationKey(locationKey);

        WeatherCache cache;
        if (existing.isPresent()) {
            cache = existing.get();
        } else {
            cache = new WeatherCache();
            cache.locationKey = locationKey;
        }

        cache.provider = provider;
        cache.forecastData = forecast;
        cache.alerts = alerts;
        cache.fetchedAt = Instant.now();
        cache.expiresAt = Instant.now().plus(FORECAST_CACHE_HOURS, ChronoUnit.HOURS);

        cache.persist();
        LOG.debugf("Updated weather cache for %s (provider: %s)", locationKey, provider);
    }

    /**
     * Builds WeatherWidgetType from cache entry.
     */
    private WeatherWidgetType buildWidgetType(String locationName, WeatherCache cache) {
        boolean stale = cache.isStale();
        String cachedAt = cache.fetchedAt.toString();

        return new WeatherWidgetType(locationName, cache.provider, cache.forecastData.current(),
                cache.forecastData.hourly(), cache.forecastData.daily(),
                cache.alerts != null ? cache.alerts : List.of(), cachedAt, stale);
    }

    /**
     * Checks if coordinates are within US bounds (approximate).
     */
    private boolean isUSLocation(double latitude, double longitude) {
        return latitude >= US_LAT_MIN && latitude <= US_LAT_MAX && longitude >= US_LON_MIN && longitude <= US_LON_MAX;
    }

    /**
     * Helper to increment Micrometer counter.
     */
    private void incrementCounter(String name, String... tags) {
        Counter.builder(name).tags(tags).register(meterRegistry).increment();
    }
}
