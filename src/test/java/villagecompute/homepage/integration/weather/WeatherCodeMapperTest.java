/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.integration.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WeatherCodeMapper}.
 */
class WeatherCodeMapperTest {

    @Test
    void testMapConditions_clearSky() {
        assertEquals("Clear sky", WeatherCodeMapper.mapConditions(0));
    }

    @Test
    void testMapConditions_cloudy() {
        assertEquals("Mainly clear", WeatherCodeMapper.mapConditions(1));
        assertEquals("Partly cloudy", WeatherCodeMapper.mapConditions(2));
        assertEquals("Overcast", WeatherCodeMapper.mapConditions(3));
    }

    @Test
    void testMapConditions_rain() {
        assertEquals("Rain", WeatherCodeMapper.mapConditions(61));
        assertEquals("Rain", WeatherCodeMapper.mapConditions(63));
        assertEquals("Rain", WeatherCodeMapper.mapConditions(65));
    }

    @Test
    void testMapConditions_thunderstorm() {
        assertEquals("Thunderstorm", WeatherCodeMapper.mapConditions(95));
        assertEquals("Thunderstorm with hail", WeatherCodeMapper.mapConditions(96));
        assertEquals("Thunderstorm with hail", WeatherCodeMapper.mapConditions(99));
    }

    @Test
    void testMapConditions_unknown() {
        assertEquals("Unknown", WeatherCodeMapper.mapConditions(999));
        assertEquals("Unknown", WeatherCodeMapper.mapConditions(-1));
    }

    @Test
    void testMapIcon_clearSky() {
        assertEquals("01d", WeatherCodeMapper.mapIcon(0));
    }

    @Test
    void testMapIcon_cloudy() {
        assertEquals("02d", WeatherCodeMapper.mapIcon(1));
        assertEquals("03d", WeatherCodeMapper.mapIcon(2));
        assertEquals("04d", WeatherCodeMapper.mapIcon(3));
    }

    @Test
    void testMapIcon_rain() {
        assertEquals("10d", WeatherCodeMapper.mapIcon(61));
        assertEquals("10d", WeatherCodeMapper.mapIcon(63));
        assertEquals("10d", WeatherCodeMapper.mapIcon(65));
    }

    @Test
    void testMapIcon_snow() {
        assertEquals("13d", WeatherCodeMapper.mapIcon(71));
        assertEquals("13d", WeatherCodeMapper.mapIcon(73));
        assertEquals("13d", WeatherCodeMapper.mapIcon(75));
    }

    @Test
    void testMapIcon_thunderstorm() {
        assertEquals("11d", WeatherCodeMapper.mapIcon(95));
        assertEquals("11d", WeatherCodeMapper.mapIcon(96));
        assertEquals("11d", WeatherCodeMapper.mapIcon(99));
    }

    @Test
    void testMapWindDirection_cardinalDirections() {
        assertEquals("N", WeatherCodeMapper.mapWindDirection(0));
        assertEquals("N", WeatherCodeMapper.mapWindDirection(360));
        assertEquals("E", WeatherCodeMapper.mapWindDirection(90));
        assertEquals("S", WeatherCodeMapper.mapWindDirection(180));
        assertEquals("W", WeatherCodeMapper.mapWindDirection(270));
    }

    @Test
    void testMapWindDirection_intermediateDirections() {
        assertEquals("NE", WeatherCodeMapper.mapWindDirection(45));
        assertEquals("SE", WeatherCodeMapper.mapWindDirection(135));
        assertEquals("SW", WeatherCodeMapper.mapWindDirection(225));
        assertEquals("NW", WeatherCodeMapper.mapWindDirection(315));
    }

    @Test
    void testMapWindDirection_invalidInput() {
        assertEquals("N", WeatherCodeMapper.mapWindDirection(-1));
        assertEquals("N", WeatherCodeMapper.mapWindDirection(400));
    }

    @Test
    void testMapWindDirection_edgeCases() {
        // Test values near boundaries
        assertEquals("N", WeatherCodeMapper.mapWindDirection(22)); // 22° rounds to N
        assertEquals("NE", WeatherCodeMapper.mapWindDirection(23)); // 23° rounds to NE
        assertEquals("NE", WeatherCodeMapper.mapWindDirection(67)); // 67° rounds to NE
        assertEquals("E", WeatherCodeMapper.mapWindDirection(68)); // 68° rounds to E
    }
}
