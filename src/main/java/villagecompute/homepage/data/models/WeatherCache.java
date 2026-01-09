/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.data.models;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import villagecompute.homepage.api.types.WeatherAlertType;
import villagecompute.homepage.api.types.WeatherForecastType;

/**
 * Cache table for weather forecast data from Open-Meteo and National Weather Service APIs.
 *
 * <p>
 * Weather data is cached with 1-hour expiration for forecasts and 15-minute expiration for severe weather alerts. Cache
 * keys are generated from rounded lat/lon coordinates to improve hit rate while maintaining accuracy.
 *
 * <h2>Cache Strategy</h2>
 * <ul>
 * <li>Location key format: "lat:lon" rounded to 2 decimals (e.g., "37.77:-122.42")</li>
 * <li>Forecast cache TTL: 1 hour</li>
 * <li>Alert cache TTL: 15 minutes</li>
 * <li>Stale data threshold: 90 minutes (serve with warning)</li>
 * <li>Provider fallback: NWS → Open-Meteo for US locations</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * {@code
 * // Lookup valid cache entry
 * Optional<WeatherCache> cache = WeatherCache.findValidCache("37.77:-122.42");
 *
 * // Store new forecast
 * WeatherCache entry = new WeatherCache();
 * entry.locationKey = "37.77:-122.42";
 * entry.provider = "nws";
 * entry.forecastData = forecastType;
 * entry.alerts = alertsList;
 * entry.fetchedAt = Instant.now();
 * entry.expiresAt = Instant.now().plusSeconds(3600);
 * entry.persist();
 *
 * // Delete expired entries
 * long deleted = WeatherCache.deleteExpired();
 * }
 * </pre>
 */
@Entity
@Table(
        name = "weather_cache")
public class WeatherCache extends PanacheEntityBase {

    /**
     * Primary key UUID.
     */
    @Id
    @GeneratedValue(
            strategy = GenerationType.UUID)
    public UUID id;

    /**
     * Location cache key in format "lat:lon" rounded to 2 decimals.
     * <p>
     * Example: "37.77:-122.42"
     */
    @Column(
            nullable = false,
            unique = true)
    public String locationKey;

    /**
     * Weather data provider: "open_meteo" or "nws".
     */
    @Column(
            nullable = false)
    public String provider;

    /**
     * Cached forecast data stored as JSONB.
     * <p>
     * Contains current conditions, hourly forecast (24 hours), and daily forecast (7 days).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            nullable = false,
            columnDefinition = "jsonb")
    public WeatherForecastType forecastData;

    /**
     * Severe weather alerts (NWS only, null for Open-Meteo).
     * <p>
     * Array of alert objects with event, severity, headline, description, and expiration time.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            columnDefinition = "jsonb")
    public List<WeatherAlertType> alerts;

    /**
     * Timestamp when forecast data was fetched from API.
     */
    @Column(
            nullable = false)
    public Instant fetchedAt;

    /**
     * Cache expiration timestamp (1 hour for forecast, 15 min for alerts).
     */
    @Column(
            nullable = false)
    public Instant expiresAt;

    /**
     * Row creation timestamp (automatic).
     */
    @Column(
            nullable = false,
            updatable = false)
    public Instant createdAt = Instant.now();

    /**
     * Row update timestamp (automatic via trigger).
     */
    @Column(
            nullable = false)
    public Instant updatedAt = Instant.now();

    /**
     * Finds cache entry by location key (may be expired).
     *
     * @param locationKey
     *            location key in format "lat:lon"
     * @return Optional containing cache entry if exists, empty otherwise
     */
    public static Optional<WeatherCache> findByLocationKey(String locationKey) {
        return find("locationKey", locationKey).firstResultOptional();
    }

    /**
     * Finds valid (non-expired) cache entry by location key.
     *
     * @param locationKey
     *            location key in format "lat:lon"
     * @return Optional containing valid cache entry if exists and not expired, empty otherwise
     */
    public static Optional<WeatherCache> findValidCache(String locationKey) {
        return find("locationKey = ?1 AND expiresAt > ?2", locationKey, Instant.now()).firstResultOptional();
    }

    /**
     * Deletes all expired cache entries.
     *
     * @return number of deleted entries
     */
    public static long deleteExpired() {
        return delete("expiresAt < ?1", Instant.now());
    }

    /**
     * Checks if cache entry is stale (older than 90 minutes).
     * <p>
     * Stale data can still be served but should be flagged to the user as potentially outdated.
     *
     * @return true if fetchedAt is more than 90 minutes ago
     */
    public boolean isStale() {
        return fetchedAt.isBefore(Instant.now().minusSeconds(90 * 60));
    }

    /**
     * Generates cache location key from coordinates.
     * <p>
     * Rounds latitude and longitude to 2 decimal places to reduce cache fragmentation while maintaining ~1km accuracy.
     *
     * @param latitude
     *            latitude coordinate
     * @param longitude
     *            longitude coordinate
     * @return location key in format "lat:lon" (e.g., "37.77:-122.42")
     */
    public static String generateLocationKey(double latitude, double longitude) {
        double roundedLat = Math.round(latitude * 100.0) / 100.0;
        double roundedLon = Math.round(longitude * 100.0) / 100.0;
        return String.format("%.2f:%.2f", roundedLat, roundedLon);
    }
}
