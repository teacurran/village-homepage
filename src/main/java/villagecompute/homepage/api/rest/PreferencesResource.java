package villagecompute.homepage.api.rest;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.UserPreferencesType;
import villagecompute.homepage.services.RateLimitService;
import villagecompute.homepage.services.UserPreferenceService;

import java.security.Principal;
import java.util.UUID;

/**
 * REST endpoints for user homepage preferences management.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>{@code GET /api/preferences} – retrieve current user's preferences</li>
 * <li>{@code PUT /api/preferences} – update current user's preferences</li>
 * </ul>
 *
 * <p>
 * <b>Security:</b> All endpoints require authentication. The user ID is extracted from the JWT token in the
 * SecurityContext. Users can only access/modify their own preferences.
 *
 * <p>
 * <b>Rate Limiting:</b> Endpoints enforce tier-based rate limits:
 * <ul>
 * <li>Anonymous: 10 reads/hour, 5 updates/hour</li>
 * <li>Logged-in: 100 reads/hour, 50 updates/hour</li>
 * <li>Trusted: 500 reads/hour, 200 updates/hour</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P1 (GDPR/CCPA): Preferences are personal data, secured per-user</li>
 * <li>P9 (Anonymous Cookie Security): Anonymous users can access via cookie-based auth</li>
 * <li>P14 (Rate Limiting): All operations are rate-limited to prevent abuse</li>
 * </ul>
 */
@Path("/api/preferences")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(
        name = "Widgets",
        description = "Homepage widget data operations")
public class PreferencesResource {

    private static final Logger LOG = Logger.getLogger(PreferencesResource.class);

    @Inject
    UserPreferenceService userPreferenceService;

    @Inject
    RateLimitService rateLimitService;

    /**
     * Retrieves the current user's preferences.
     *
     * <p>
     * If the user has no preferences stored, returns default preferences. The response includes the current schema
     * version and full preference structure.
     *
     * @param securityContext
     *            injected security context with JWT principal
     * @return 200 OK with preferences, 401 if not authenticated, 429 if rate limited
     */
    @GET
    @Operation(
            summary = "Get user preferences",
            description = "Retrieve current user's homepage preferences")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Preferences returned successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = UserPreferencesType.class))),
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
                            description = "Failed to retrieve preferences",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    @SecurityRequirement(
            name = "bearerAuth")
    public Response getPreferences(@Context SecurityContext securityContext) {
        // Extract user ID from security context
        UUID userId = extractUserId(securityContext);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity(new ErrorResponse("Authentication required"))
                    .build();
        }

        // Check rate limit
        RateLimitService.Tier tier = determineTier(securityContext);
        RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(null, // userId is Long, we have
                                                                                             // UUID
                null, // IP address would come from request context
                "preferences_read", tier, "/api/preferences");

        if (!rateLimitResult.allowed()) {
            LOG.warnf("Rate limit exceeded for user %s on preferences_read", userId);
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity(new ErrorResponse("Rate limit exceeded. Try again later."))
                    .header("X-RateLimit-Limit", rateLimitResult.limitCount())
                    .header("X-RateLimit-Remaining", rateLimitResult.remaining())
                    .header("X-RateLimit-Window", rateLimitResult.windowSeconds()).build();
        }

        try {
            UserPreferencesType preferences = userPreferenceService.getPreferences(userId);
            return Response.ok(preferences).header("X-RateLimit-Limit", rateLimitResult.limitCount())
                    .header("X-RateLimit-Remaining", rateLimitResult.remaining())
                    .header("X-RateLimit-Window", rateLimitResult.windowSeconds()).build();

        } catch (IllegalArgumentException e) {
            LOG.errorf(e, "Failed to retrieve preferences for user %s", userId);
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error retrieving preferences for user %s", userId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to retrieve preferences")).build();
        }
    }

    /**
     * Updates the current user's preferences.
     *
     * <p>
     * The request body must be a valid {@link UserPreferencesType} with all required fields. Validation is performed by
     * Jakarta Bean Validation annotations on the Type record.
     *
     * @param preferences
     *            new preferences (validated)
     * @param securityContext
     *            injected security context with JWT principal
     * @return 200 OK with updated preferences, 400 if validation fails, 401 if not authenticated, 429 if rate limited
     */
    @PUT
    @Operation(
            summary = "Update user preferences",
            description = "Update current user's homepage preferences")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Preferences updated successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = UserPreferencesType.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid request or validation failed",
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
                            description = "Failed to update preferences",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    @SecurityRequirement(
            name = "bearerAuth")
    public Response updatePreferences(@Valid UserPreferencesType preferences,
            @Context SecurityContext securityContext) {
        // Extract user ID from security context
        UUID userId = extractUserId(securityContext);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity(new ErrorResponse("Authentication required"))
                    .build();
        }

        if (preferences == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Request body required"))
                    .build();
        }

        // Check rate limit
        RateLimitService.Tier tier = determineTier(securityContext);
        RateLimitService.RateLimitResult rateLimitResult = rateLimitService.checkLimit(null, // userId is Long, we have
                                                                                             // UUID
                null, // IP address would come from request context
                "preferences_update", tier, "/api/preferences");

        if (!rateLimitResult.allowed()) {
            LOG.warnf("Rate limit exceeded for user %s on preferences_update", userId);
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity(new ErrorResponse("Rate limit exceeded. Try again later."))
                    .header("X-RateLimit-Limit", rateLimitResult.limitCount())
                    .header("X-RateLimit-Remaining", rateLimitResult.remaining())
                    .header("X-RateLimit-Window", rateLimitResult.windowSeconds()).build();
        }

        try {
            UserPreferencesType updated = userPreferenceService.updatePreferences(userId, preferences);
            return Response.ok(updated).header("X-RateLimit-Limit", rateLimitResult.limitCount())
                    .header("X-RateLimit-Remaining", rateLimitResult.remaining())
                    .header("X-RateLimit-Window", rateLimitResult.windowSeconds()).build();

        } catch (IllegalArgumentException e) {
            LOG.errorf(e, "Failed to update preferences for user %s", userId);
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error updating preferences for user %s", userId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to update preferences")).build();
        }
    }

    /**
     * Extracts user ID from JWT principal in security context.
     *
     * <p>
     * TODO: This is a placeholder implementation. Once OAuth/JWT integration lands in I2.T1, replace with actual JWT
     * claim extraction (e.g., {@code jwt.getClaim("sub")}).
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

        // TODO: Replace with actual JWT claim extraction
        // For now, parse principal name as UUID (placeholder)
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            LOG.warnf("Failed to parse user ID from principal: %s", principal.getName());
            return null;
        }
    }

    /**
     * Determines user tier for rate limiting based on security context.
     *
     * <p>
     * TODO: This is a placeholder implementation. Once User entity integration is complete, fetch the user's karma and
     * use {@link RateLimitService.Tier#fromKarma(int)}.
     *
     * @param securityContext
     *            security context with principal
     * @return user tier for rate limiting
     */
    private RateLimitService.Tier determineTier(SecurityContext securityContext) {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            return RateLimitService.Tier.ANONYMOUS;
        }

        // TODO: Fetch user entity and determine tier from karma
        // For now, assume authenticated users are logged_in tier
        return RateLimitService.Tier.LOGGED_IN;
    }

    /**
     * Simple error response record for API errors.
     */
    public record ErrorResponse(String error) {
    }
}
