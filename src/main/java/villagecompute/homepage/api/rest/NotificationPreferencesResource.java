package villagecompute.homepage.api.rest;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.NotificationPreferencesType;
import villagecompute.homepage.api.types.NotificationPreferencesUpdateType;
import villagecompute.homepage.data.models.NotificationPreferences;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.services.NotificationPreferencesService;
import villagecompute.homepage.services.NotificationPreferencesService.UnsubscribeTokenData;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST resource for managing notification preferences and handling unsubscribe links. Provides both authenticated
 * preference management and one-click unauthenticated unsubscribe.
 */
@Path("/api/notifications")
public class NotificationPreferencesResource {

    private static final Logger LOG = Logger.getLogger(NotificationPreferencesResource.class);

    @Inject
    NotificationPreferencesService notificationPreferencesService;

    /**
     * One-click unsubscribe endpoint (no authentication required). Validates HMAC-signed token, updates preferences,
     * and redirects to success page.
     *
     * @param token
     *            Base64-encoded unsubscribe token
     * @return 303 redirect to success page, or error response
     */
    @GET
    @Path("/unsubscribe")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response unsubscribe(@QueryParam("token") String token) {
        if (token == null || token.isBlank()) {
            LOG.warnf("Unsubscribe called with missing token");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("<html><body><h1>Error: Missing unsubscribe token</h1>"
                            + "<p>This link appears to be incomplete. Please use the link from your email.</p></body></html>")
                    .build();
        }

        // Parse and validate token
        Optional<UnsubscribeTokenData> tokenData = notificationPreferencesService.parseUnsubscribeToken(token);

        if (tokenData.isEmpty()) {
            LOG.warnf("Invalid or expired unsubscribe token received");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("<html><body><h1>Error: Invalid or Expired Link</h1>"
                            + "<p>This unsubscribe link is invalid or has expired (links expire after 30 days).</p>"
                            + "<p>If you'd like to manage your notification preferences, please "
                            + "<a href=\"/settings/notifications\">visit your settings page</a>.</p></body></html>")
                    .build();
        }

        // Find user
        User user = User.findById(tokenData.get().userId());
        if (user == null) {
            LOG.warnf("Unsubscribe token references non-existent user: %s", tokenData.get().userId());
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("<html><body><h1>Error: User Not Found</h1>"
                            + "<p>The user account associated with this link could not be found.</p></body></html>")
                    .build();
        }

        // Update preferences (no auth required for one-click)
        notificationPreferencesService.unsubscribe(user, tokenData.get().notificationType());

        LOG.infof("Processed one-click unsubscribe for user %s, type %s", user.id, tokenData.get().notificationType());

        // Redirect to success page
        return Response.seeOther(URI.create("/notifications/unsubscribe-success")).build();
    }

    /**
     * Get notification preferences for the authenticated user.
     *
     * @param securityContext
     *            Security context containing user principal
     * @return 200 with preferences JSON, or 401/404 error
     */
    @GET
    @Path("/preferences")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPreferences(@Context SecurityContext securityContext) {
        // Extract and validate user ID (requires authentication)
        UUID userId = extractUserId(securityContext);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("{\"error\": \"Authentication required\"}")
                    .build();
        }

        User user = User.findById(userId);
        if (user == null) {
            LOG.warnf("Authenticated user not found: %s", userId);
            return Response.status(Response.Status.NOT_FOUND).entity("{\"error\": \"User not found\"}").build();
        }

        NotificationPreferences prefs = notificationPreferencesService.getPreferences(user);

        // Convert to response type
        NotificationPreferencesType response = new NotificationPreferencesType(prefs.emailEnabled,
                prefs.emailListingMessages, prefs.emailSiteApproved, prefs.emailSiteRejected, prefs.emailDigest);

        return Response.ok(response).build();
    }

    /**
     * Update notification preferences for the authenticated user.
     *
     * @param securityContext
     *            Security context containing user principal
     * @param updateRequest
     *            Preferences to update
     * @return 200 on success, or 401/404 error
     */
    @PUT
    @Path("/preferences")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response updatePreferences(@Context SecurityContext securityContext,
            NotificationPreferencesUpdateType updateRequest) {
        // Extract and validate user ID (requires authentication)
        UUID userId = extractUserId(securityContext);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("{\"error\": \"Authentication required\"}")
                    .build();
        }

        User user = User.findById(userId);
        if (user == null) {
            LOG.warnf("Authenticated user not found: %s", userId);
            return Response.status(Response.Status.NOT_FOUND).entity("{\"error\": \"User not found\"}").build();
        }

        // Convert DTO to Map (camelCase keys match entity fields)
        Map<String, Boolean> preferences = Map.of("emailEnabled", updateRequest.emailEnabled(), "emailListingMessages",
                updateRequest.emailListingMessages(), "emailSiteApproved", updateRequest.emailSiteApproved(),
                "emailSiteRejected", updateRequest.emailSiteRejected(), "emailDigest", updateRequest.emailDigest());

        notificationPreferencesService.updatePreferences(user, preferences);

        LOG.infof("Updated notification preferences for user %s", user.id);

        return Response.ok("{\"success\": true}").build();
    }

    /**
     * Extracts user ID from security context. Returns null if user is not authenticated or ID is invalid.
     *
     * @param securityContext
     *            Security context to extract from
     * @return User UUID, or null if not authenticated/invalid
     */
    private UUID extractUserId(SecurityContext securityContext) {
        if (securityContext.getUserPrincipal() == null) {
            LOG.warnf("No user principal in security context");
            return null;
        }

        String userIdStr = securityContext.getUserPrincipal().getName();
        try {
            return UUID.fromString(userIdStr);
        } catch (IllegalArgumentException e) {
            LOG.errorf("Invalid user ID in security context: %s", userIdStr);
            return null;
        }
    }
}
