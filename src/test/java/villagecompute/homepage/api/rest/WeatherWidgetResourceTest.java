/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import villagecompute.homepage.api.types.CurrentWeatherType;
import villagecompute.homepage.api.types.DailyForecastType;
import villagecompute.homepage.api.types.HourlyForecastType;
import villagecompute.homepage.api.types.ThemeType;
import villagecompute.homepage.api.types.UserPreferencesType;
import villagecompute.homepage.api.types.WeatherLocationType;
import villagecompute.homepage.api.types.WeatherWidgetType;
import villagecompute.homepage.services.RateLimitService;
import villagecompute.homepage.services.UserPreferenceService;
import villagecompute.homepage.services.WeatherService;

/**
 * Unit tests for {@link WeatherWidgetResource}.
 */
class WeatherWidgetResourceTest {

    @Mock
    WeatherService weatherService;

    @Mock
    UserPreferenceService userPreferenceService;

    @Mock
    RateLimitService rateLimitService;

    @Mock
    SecurityContext securityContext;

    @Mock
    Principal principal;

    @InjectMocks
    WeatherWidgetResource resource;

    private UUID testUserId;
    private WeatherLocationType testLocation;
    private WeatherWidgetType mockWeatherData;
    private UserPreferencesType mockPreferences;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        testUserId = UUID.randomUUID();
        testLocation = new WeatherLocationType("San Francisco, CA", null, 37.77, -122.42);

        CurrentWeatherType current = new CurrentWeatherType(65.0, 63.0, 70, 10.0, 180, "Partly cloudy", "02d");
        List<HourlyForecastType> hourly = List.of(new HourlyForecastType("2025-01-09T14:00:00Z", 66.0, 20, "02d"));
        List<DailyForecastType> daily = List.of(new DailyForecastType("2025-01-09", 70.0, 55.0, "Sunny", "01d", 10));

        mockWeatherData = new WeatherWidgetType("San Francisco, CA", "nws", current, hourly, daily, List.of(),
                "2025-01-09T10:00:00Z", false);

        mockPreferences = new UserPreferencesType(1, List.of(), List.of(), List.of(), List.of(testLocation),
                new ThemeType("system", null, "standard"), java.util.Map.of());
    }

    @Test
    void testGetWeather_success() {
        // Arrange
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());
        when(rateLimitService.checkLimit(any(), any(), anyString(), any(RateLimitService.Tier.class), anyString()))
                .thenReturn(new RateLimitService.RateLimitResult(true, 100, 99, 3600));
        when(userPreferenceService.getPreferences(testUserId)).thenReturn(mockPreferences);
        when(weatherService.getWeather(any(WeatherLocationType.class))).thenReturn(mockWeatherData);

        // Act
        Response response = resource.getWeather(securityContext);

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        List<WeatherWidgetType> data = (List<WeatherWidgetType>) response.getEntity();
        assertEquals(1, data.size());
        assertEquals("San Francisco, CA", data.get(0).locationName());
    }

    @Test
    void testGetWeather_noLocations_returnsEmptyArray() {
        // Arrange
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());
        when(rateLimitService.checkLimit(any(), any(), anyString(), any(RateLimitService.Tier.class), anyString()))
                .thenReturn(new RateLimitService.RateLimitResult(true, 100, 99, 3600));

        UserPreferencesType emptyPrefs = new UserPreferencesType(1, List.of(), List.of(), List.of(), List.of(), // No
                                                                                                                  // weather
                                                                                                                  // locations
                new ThemeType("system", null, "standard"), java.util.Map.of());
        when(userPreferenceService.getPreferences(testUserId)).thenReturn(emptyPrefs);

        // Act
        Response response = resource.getWeather(securityContext);

        // Assert
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        List<WeatherWidgetType> data = (List<WeatherWidgetType>) response.getEntity();
        assertEquals(0, data.size());
    }

    @Test
    void testGetWeather_notAuthenticated_returns401() {
        // Arrange
        when(securityContext.getUserPrincipal()).thenReturn(null);

        // Act
        Response response = resource.getWeather(securityContext);

        // Assert
        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
    }

    @Test
    void testGetWeather_rateLimitExceeded_returns429() {
        // Arrange
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());
        when(rateLimitService.checkLimit(any(), any(), anyString(), any(RateLimitService.Tier.class), anyString()))
                .thenReturn(new RateLimitService.RateLimitResult(false, 100, 0, 3600));

        // Act
        Response response = resource.getWeather(securityContext);

        // Assert
        assertEquals(Response.Status.TOO_MANY_REQUESTS.getStatusCode(), response.getStatus());
        assertEquals("100", response.getHeaderString("X-RateLimit-Limit"));
        assertEquals("0", response.getHeaderString("X-RateLimit-Remaining"));
    }

    @Test
    void testGetWeather_serviceError_returns500() {
        // Arrange
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(testUserId.toString());
        when(rateLimitService.checkLimit(any(), any(), anyString(), any(RateLimitService.Tier.class), anyString()))
                .thenReturn(new RateLimitService.RateLimitResult(true, 100, 99, 3600));
        when(userPreferenceService.getPreferences(testUserId)).thenThrow(new RuntimeException("Database error"));

        // Act
        Response response = resource.getWeather(securityContext);

        // Assert
        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
    }
}
