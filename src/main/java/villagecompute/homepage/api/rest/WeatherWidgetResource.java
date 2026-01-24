/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.rest;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import villagecompute.homepage.api.types.UserPreferencesType;
import villagecompute.homepage.api.types.WeatherLocationType;
import villagecompute.homepage.api.types.WeatherWidgetType;
import villagecompute.homepage.services.RateLimitService;
import villagecompute.homepage.services.UserPreferenceService;
import villagecompute.homepage.services.WeatherService;

/**
 * REST endpoint for weather widget data.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>{@code GET /api/widgets/weather} – retrieve weather data for all user locations</li>
 * </ul>
 *
 * <p>
 * <b>Security:</b> Requires authentication. User ID is extracted from JWT token in SecurityContext. Users can only
 * access their own weather locations.
 *
 * <p>
 * <b>Rate Limiting:</b> Enforces tier-based rate limits:
 * <ul>
 * <li>Anonymous: 20 reads/hour</li>
 * <li>Logged-in: 100 reads/hour</li>
 * <li>Trusted: 500 reads/hour</li>
 * </ul>
 *
 * <p>
 * <b>Caching:</b> Weather data is cached with 1-hour TTL. Cache-first strategy serves fresh data when available, falls
 * back to stale cache on API failure.
 *
 * <p>
 * <b>Response Format:</b>
 *
 * <pre>
 * [
 *   {
 *     "location_name": "San Francisco, CA",
 *     "provider": "nws",
 *     "current": { "temperature": 62.0, "feels_like": 60.0, ... },
 *     "hourly": [ ... ],
 *     "daily": [ ... ],
 *     "alerts": [ ... ],
 *     "cached_at": "2025-01-09T10:00:00Z",
 *     "stale": false
 *   }
 * ]
 * </pre>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P1 (GDPR/CCPA): Weather locations are personal data, secured per-user</li>
 * <li>P14 (Rate Limiting): All operations are rate-limited to prevent abuse</li>
 * </ul>
 */
@Path("/api/widgets/weather")
@Produces(MediaType.APPLICATION_JSON)
@Tag(
        name = "Widgets",
        description = "Homepage widget data operations")
public class WeatherWidgetResource {

    private static final Logger LOG = Logger.getLogger(WeatherWidgetResource.class);

    @Inject
    WeatherService weatherService;

    @Inject
    UserPreferenceService userPreferenceService;

    @Inject
    RateLimitService rateLimitService;

    /**
     * Retrieves weather data for all user-configured locations.
     *
     * <p>
     * Fetches user preferences, extracts weatherLocations, and calls WeatherService for each location. Returns weather
     * data for all locations in a single response.
     *
     * @param securityContext
     *            injected security context with JWT principal
     * @return 200 OK with weather data array, 401 if not authenticated, 429 if rate limited
     */
    @GET
    @Operation(
            summary = "Get weather widget data",
            description = "Retrieve weather data for all user-configured locations with 1-hour caching")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Weather data returned successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = WeatherWidgetType.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid request",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "401",
                            description = "Authentication required",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "429",
                            description = "Rate limit exceeded",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Failed to retrieve weather data",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    @SecurityRequirement(
            name = "bearerAuth")
    public Response getWeather(@Context SecurityContext securityContext) {
        // Extract user ID from security context
        UUID userId = extractUserId(securityContext);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity(new ErrorResponse("Authentication required"))
                    .build();
        }

        // Check rate limit
        RateLimitService.Tier tier = determineTier(securityContext);
        RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(null, null, "weather_read", tier,
                "/api/widgets/weather");

        if (!rateLimitResult.allowed()) {
            LOG.warnf("Rate limit exceeded for user %s on weather_read", userId);
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity(new ErrorResponse("Rate limit exceeded. Try again later."))
                    .header("X-RateLimit-Limit", rateLimitResult.limitCount())
                    .header("X-RateLimit-Remaining", rateLimitResult.remaining())
                    .header("X-RateLimit-Window", rateLimitResult.windowSeconds()).build();
        }

        try {
            // Fetch user preferences and extract weather locations
            UserPreferencesType preferences = userPreferenceService.getPreferences(userId);
            List<WeatherLocationType> locations = preferences.weatherLocations();

            if (locations.isEmpty()) {
                LOG.debugf("User %s has no weather locations configured", userId);
                return Response.ok(List.of()).header("X-RateLimit-Limit", rateLimitResult.limitCount())
                        .header("X-RateLimit-Remaining", rateLimitResult.remaining())
                        .header("X-RateLimit-Window", rateLimitResult.windowSeconds()).build();
            }

            // Fetch weather data for each location
            List<WeatherWidgetType> weatherData = new ArrayList<>();
            for (WeatherLocationType location : locations) {
                try {
                    WeatherWidgetType weather = weatherService.getWeather(location);
                    weatherData.add(weather);
                } catch (Exception e) {
                    // Log error but continue with other locations
                    LOG.errorf(e, "Failed to fetch weather for location %s (user %s)", location.city(), userId);
                    // Don't fail entire request if one location fails
                }
            }

            return Response.ok(weatherData).header("X-RateLimit-Limit", rateLimitResult.limitCount())
                    .header("X-RateLimit-Remaining", rateLimitResult.remaining())
                    .header("X-RateLimit-Window", rateLimitResult.windowSeconds()).build();

        } catch (IllegalArgumentException e) {
            LOG.errorf(e, "Failed to retrieve weather for user %s", userId);
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error retrieving weather for user %s", userId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to retrieve weather data")).build();
        }
    }

    /**
     * Extracts user ID from JWT principal in security context.
     * <p>
     * TODO: Replace with actual JWT claim extraction once OAuth integration lands in I2.T1.
     *
     * @param securityContext
     *            security context with principal
     * @return user UUID if authenticated, null otherwise
     */
    private UUID extractUserId(SecurityContext securityContext) {
        if (securityContext == null) {
            return null;
        }

        Principal principal = securityContext.getUserPrincipal();
        if (principal == null) {
            return null;
        }

        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            LOG.warnf("Failed to parse user ID from principal: %s", principal.getName());
            return null;
        }
    }

    /**
     * Determines user tier for rate limiting based on security context.
     * <p>
     * TODO: Fetch user entity and determine tier from karma once integration is complete.
     *
     * @param securityContext
     *            security context with principal
     * @return user tier for rate limiting
     */
    private RateLimitService.Tier determineTier(SecurityContext securityContext) {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            return RateLimitService.Tier.ANONYMOUS;
        }

        return RateLimitService.Tier.LOGGED_IN;
    }

    /**
     * Simple error response record for API errors.
     */
    public record ErrorResponse(String error) {
    }
}
