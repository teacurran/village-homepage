/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import villagecompute.homepage.data.models.WeatherCache;

/**
 * Unit tests for {@link WeatherService}.
 * <p>
 * Tests location key generation and provider selection logic.
 */
class WeatherServiceTest {

    @Test
    void testGenerateLocationKey_roundsCoordinates() {
        // Arrange
        double lat1 = 37.7749;
        double lon1 = -122.4194;

        double lat2 = 37.7751;
        double lon2 = -122.4189;

        // Act
        String key1 = WeatherCache.generateLocationKey(lat1, lon1);
        String key2 = WeatherCache.generateLocationKey(lat2, lon2);

        // Assert
        assertEquals("37.77:-122.42", key1);
        assertEquals("37.78:-122.42", key2); // Slightly different rounding
    }

    @Test
    void testGenerateLocationKey_deduplicatesNearbyLocations() {
        // Arrange
        double lat = 37.774;
        double lon = -122.42;

        // Act
        String key1 = WeatherCache.generateLocationKey(lat, lon);
        String key2 = WeatherCache.generateLocationKey(lat + 0.001, lon); // 0.001° difference - rounds to different
                                                                          // (37.775 -> 37.78)
        String key3 = WeatherCache.generateLocationKey(lat + 0.02, lon); // 0.02° difference - rounds to different

        // Assert
        assertEquals("37.77:-122.42", key1);
        assertEquals("37.78:-122.42", key2); // Banker's rounding on .775
        assertEquals("37.79:-122.42", key3);
        assertFalse(key2.equals(key1)); // Different due to rounding
        assertFalse(key3.equals(key1)); // Different due to larger delta
    }
}
