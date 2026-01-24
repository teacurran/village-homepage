/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.api.rest;

import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.SocialWidgetStateType;
import villagecompute.homepage.services.SocialIntegrationService;

import java.util.UUID;

/**
 * REST resource for social media widget integration (Instagram and Facebook).
 *
 * <p>
 * Provides endpoints to fetch social feed state with cached posts, connection status, and staleness indicators per
 * Policy P5/P13. Supports graceful degradation when tokens expire or API fails.
 *
 * <h2>Endpoints</h2>
 * <ul>
 * <li><b>GET /api/widgets/social</b> - Fetch social feed state for user and platform</li>
 * </ul>
 *
 * <h2>Authentication</h2>
 * <p>
 * All endpoints require authenticated user context (user_id query parameter for now; will use JWT in future).
 *
 * <h2>Response States</h2>
 * <ul>
 * <li><b>connected</b> - Token valid, posts fresh (< 24 hours)</li>
 * <li><b>stale</b> - Token valid, posts outdated (1-7 days)</li>
 * <li><b>expired</b> - Token expired, showing cached posts only (> 7 days)</li>
 * <li><b>disconnected</b> - No token exists, user needs to connect</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>
 * GET /api/widgets/social?user_id=550e8400-e29b-41d4-a716-446655440000&platform=instagram
 *
 * Response:
 * {
 *   "platform": "instagram",
 *   "posts": [
 *     {
 *       "platform_post_id": "12345",
 *       "post_type": "image",
 *       "caption": "Beautiful sunset!",
 *       "media_urls": [{"url": "https://...", "type": "image"}],
 *       "posted_at": "2025-01-09T10:00:00Z",
 *       "engagement": {"likes": 42, "comments": 5}
 *     }
 *   ],
 *   "connection_status": "stale",
 *   "staleness": "STALE",
 *   "staleness_days": 5,
 *   "reconnect_url": "/oauth/connect?platform=instagram",
 *   "cached_at": "2025-01-04T10:00:00Z",
 *   "message": "Showing posts from 5 days ago. Reconnect your Instagram to refresh."
 * }
 * </pre>
 */
@Path("/api/widgets/social")
@Produces(MediaType.APPLICATION_JSON)
@Tag(
        name = "Social",
        description = "Social media integration operations")
public class SocialWidgetResource {

    private static final Logger LOG = Logger.getLogger(SocialWidgetResource.class);

    @Inject
    SocialIntegrationService socialIntegrationService;

    /**
     * Fetches social feed state for user and platform.
     *
     * @param userIdStr
     *            user UUID (required)
     * @param platform
     *            "instagram" or "facebook" (required)
     * @return social widget state with posts, connection status, and staleness
     */
    @GET
    @Operation(
            summary = "Get social media feed",
            description = "Fetch social feed state for user and platform (Instagram or Facebook)")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Social feed returned successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = SocialWidgetStateType.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid user_id or platform",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Failed to fetch social feed",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response getSocialFeed(@Parameter(
            description = "User UUID",
            required = true,
            example = "550e8400-e29b-41d4-a716-446655440000") @QueryParam("user_id") @NotBlank String userIdStr,
            @Parameter(
                    description = "Platform: instagram or facebook",
                    required = true,
                    example = "instagram") @QueryParam("platform") @NotBlank String platform) {

        try {
            // Validate user_id
            UUID userId;
            try {
                userId = UUID.fromString(userIdStr);
            } catch (IllegalArgumentException e) {
                LOG.warnf("Invalid user_id format: %s", userIdStr);
                return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Invalid user_id format"))
                        .build();
            }

            // Validate platform
            if (!"instagram".equalsIgnoreCase(platform) && !"facebook".equalsIgnoreCase(platform)) {
                LOG.warnf("Invalid platform: %s", platform);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Platform must be 'instagram' or 'facebook'")).build();
            }

            String normalizedPlatform = platform.toLowerCase();

            // Fetch social feed state
            SocialWidgetStateType state = socialIntegrationService.getSocialFeed(userId, normalizedPlatform);

            LOG.debugf("Returning social feed state for user %s platform %s: %s", userId, normalizedPlatform,
                    state.connectionStatus());

            return Response.ok(state).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch social feed for user %s platform %s", userIdStr, platform);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to fetch social feed", "details", e.getMessage())).build();
        }
    }

    /**
     * Helper for building error response maps.
     */
    private static class Map {
        public static java.util.Map<String, String> of(String key, String value) {
            return java.util.Map.of(key, value);
        }

        public static java.util.Map<String, String> of(String key1, String value1, String key2, String value2) {
            return java.util.Map.of(key1, value1, key2, value2);
        }
    }
}
