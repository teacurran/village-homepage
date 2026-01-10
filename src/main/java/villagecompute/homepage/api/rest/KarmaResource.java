package villagecompute.homepage.api.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
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
