/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.integration.weather;

/**
 * Utility for mapping WMO weather codes to human-readable conditions and icon codes.
 *
 * <p>
 * Open-Meteo uses WMO (World Meteorological Organization) weather codes (0-99). This mapper translates numeric codes
 * into displayable text and icon codes compatible with weather icon libraries.
 *
 * <h2>WMO Code Ranges</h2>
 * <ul>
 * <li>0: Clear sky</li>
 * <li>1-3: Partly cloudy</li>
 * <li>45-48: Fog</li>
 * <li>51-55: Drizzle</li>
 * <li>56-57: Freezing drizzle</li>
 * <li>61-65: Rain</li>
 * <li>66-67: Freezing rain</li>
 * <li>71-75: Snow</li>
 * <li>77: Snow grains</li>
 * <li>80-82: Rain showers</li>
 * <li>85-86: Snow showers</li>
 * <li>95-99: Thunderstorm</li>
 * </ul>
 *
 * @see <a href="https://open-meteo.com/en/docs">Open-Meteo API Docs</a>
 */
public final class WeatherCodeMapper {

    private WeatherCodeMapper() {
        // Utility class
    }

    /**
     * Maps WMO weather code to human-readable condition text.
     *
     * @param code
     *            WMO weather code (0-99)
     * @return human-readable weather condition (e.g., "Sunny", "Rain", "Thunderstorm")
     */
    public static String mapConditions(int code) {
        return switch (code) {
            case 0 -> "Clear sky";
            case 1 -> "Mainly clear";
            case 2 -> "Partly cloudy";
            case 3 -> "Overcast";
            case 45, 48 -> "Foggy";
            case 51, 53, 55 -> "Drizzle";
            case 56, 57 -> "Freezing drizzle";
            case 61, 63, 65 -> "Rain";
            case 66, 67 -> "Freezing rain";
            case 71, 73, 75 -> "Snow";
            case 77 -> "Snow grains";
            case 80, 81, 82 -> "Rain showers";
            case 85, 86 -> "Snow showers";
            case 95 -> "Thunderstorm";
            case 96, 99 -> "Thunderstorm with hail";
            default -> "Unknown";
        };
    }

    /**
     * Maps WMO weather code to icon code for frontend rendering.
     * <p>
     * Icon codes follow standard weather icon library conventions:
     * <ul>
     * <li>01: Clear sky</li>
     * <li>02: Few clouds</li>
     * <li>03: Scattered clouds</li>
     * <li>04: Broken clouds</li>
     * <li>09: Shower rain</li>
     * <li>10: Rain</li>
     * <li>11: Thunderstorm</li>
     * <li>13: Snow</li>
     * <li>50: Mist/fog</li>
     * </ul>
     * Suffix "d" for day, "n" for night (we use "d" as default for now).
     *
     * @param code
     *            WMO weather code (0-99)
     * @return icon code (e.g., "01d", "09d", "11d")
     */
    public static String mapIcon(int code) {
        return switch (code) {
            case 0 -> "01d"; // Clear sky
            case 1 -> "02d"; // Mainly clear
            case 2 -> "03d"; // Partly cloudy
            case 3 -> "04d"; // Overcast
            case 45, 48 -> "50d"; // Fog
            case 51, 53, 55, 56, 57, 80, 81, 82 -> "09d"; // Drizzle/showers
            case 61, 63, 65, 66, 67 -> "10d"; // Rain
            case 71, 73, 75, 77, 85, 86 -> "13d"; // Snow
            case 95, 96, 99 -> "11d"; // Thunderstorm
            default -> "01d"; // Default to clear
        };
    }

    /**
     * Maps wind direction in degrees to cardinal direction abbreviation.
     *
     * @param degrees
     *            wind direction in degrees (0-359, where 0=North)
     * @return cardinal direction (N, NE, E, SE, S, SW, W, NW)
     */
    public static String mapWindDirection(int degrees) {
        if (degrees < 0 || degrees > 359) {
            return "N";
        }

        String[] directions = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int index = (int) Math.round(((degrees % 360) / 45.0)) % 8;
        return directions[index];
    }
}
