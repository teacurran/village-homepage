package villagecompute.homepage.api.rest;

import jakarta.ws.rs.GET;
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
import villagecompute.homepage.api.types.KarmaSummaryType;
import villagecompute.homepage.data.models.User;

import java.util.UUID;

/**
 * KarmaResource provides public API endpoints for viewing karma status.
 *
 * <p>
 * Endpoints:
 * <ul>
 * <li>GET /api/karma/me - Get current user's karma summary</li>
 * </ul>
 *
 * @see KarmaSummaryType
 */
@Path("/api/karma")
@Produces(MediaType.APPLICATION_JSON)
@Tag(
        name = "Profile",
        description = "User profile and karma operations")
public class KarmaResource {

    private static final Logger LOG = Logger.getLogger(KarmaResource.class);

    @Context
    SecurityContext securityContext;

    /**
     * Get current user's karma summary.
     *
     * <p>
     * Returns karma points, trust level, next milestone, and privilege descriptions for display in the UI header.
     * </p>
     *
     * @return KarmaSummaryType with current karma status
     */
    @GET
    @Path("/me")
    @Operation(
            summary = "Get current user's karma",
            description = "Retrieve current user's karma summary with trust level and privileges")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Karma summary returned successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = KarmaSummaryType.class))),
                    @APIResponse(
                            responseCode = "401",
                            description = "Authentication required",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "User not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    @SecurityRequirement(
            name = "bearerAuth")
    public Response getMyKarma() {
        // Extract user ID from security context
        String userIdStr = securityContext.getUserPrincipal().getName();
        UUID userId;
        try {
            userId = UUID.fromString(userIdStr);
        } catch (IllegalArgumentException e) {
            LOG.errorf("Invalid user ID in security context: %s", userIdStr);
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        User user = User.findById(userId);
        if (user == null) {
            LOG.warnf("User %s not found for karma summary", userId);
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        KarmaSummaryType summary = new KarmaSummaryType(user.directoryKarma, user.directoryTrustLevel,
                user.getKarmaToNextLevel(), user.getKarmaPrivilegeDescription(), user.isTrusted(),
                user.isDirectoryModerator());

        return Response.ok(summary).build();
    }
}
