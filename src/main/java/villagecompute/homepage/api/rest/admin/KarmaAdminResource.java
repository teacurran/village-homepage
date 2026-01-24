package villagecompute.homepage.api.rest.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.AdminKarmaAdjustmentType;
import villagecompute.homepage.api.types.AdminTrustLevelChangeType;
import villagecompute.homepage.api.types.KarmaSummaryType;
import villagecompute.homepage.data.models.KarmaAudit;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.services.KarmaService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin REST endpoints for karma management.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>{@code GET /admin/api/karma/{userId}} – get user's karma summary</li>
 * <li>{@code GET /admin/api/karma/{userId}/history} – get karma adjustment history</li>
 * <li>{@code POST /admin/api/karma/{userId}/adjust} – manually adjust karma</li>
 * <li>{@code PATCH /admin/api/karma/{userId}/trust-level} – manually change trust level</li>
 * </ul>
 *
 * <p>
 * <b>Security:</b> All endpoints require super_admin role.
 *
 * <p>
 * <b>Audit Trail:</b> All mutations are logged to karma_audit table with admin user ID.
 */
@Path("/admin/api/karma")
@Tag(
        name = "Admin - Users",
        description = "Admin endpoints for karma management (requires super_admin role)")
@SecurityRequirement(
        name = "bearerAuth")
@RolesAllowed(User.ROLE_SUPER_ADMIN)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KarmaAdminResource {

    private static final Logger LOG = Logger.getLogger(KarmaAdminResource.class);

    @Inject
    KarmaService karmaService;

    @Context
    SecurityContext securityContext;

    /**
     * Get a user's karma summary.
     *
     * @param userId
     *            User ID
     * @return KarmaSummaryType with current karma status
     */
    @GET
    @Path("/{userId}")
    @Operation(
            summary = "Get user karma summary",
            description = "Returns user's karma summary with current status and privileges. Requires super_admin role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = KarmaSummaryType.class))),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions (requires super_admin role)"),
                    @APIResponse(
                            responseCode = "404",
                            description = "User not found"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response getUserKarma(@Parameter(
            description = "User UUID",
            required = true) @PathParam("userId") UUID userId) {
        User user = User.findById(userId);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "User not found: " + userId))
                    .build();
        }

        KarmaSummaryType summary = new KarmaSummaryType(user.directoryKarma, user.directoryTrustLevel,
                user.getKarmaToNextLevel(), user.getKarmaPrivilegeDescription(), user.isTrusted(),
                user.isDirectoryModerator());

        return Response.ok(summary).build();
    }

    /**
     * Get a user's karma adjustment history.
     *
     * @param userId
     *            User ID
     * @return List of karma audit records, most recent first
     */
    @GET
    @Path("/{userId}/history")
    @Operation(
            summary = "Get user karma history",
            description = "Returns user's karma adjustment history ordered by most recent first. Requires super_admin role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions (requires super_admin role)"),
                    @APIResponse(
                            responseCode = "404",
                            description = "User not found"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response getUserKarmaHistory(@Parameter(
            description = "User UUID",
            required = true) @PathParam("userId") UUID userId) {
        User user = User.findById(userId);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "User not found: " + userId))
                    .build();
        }

        List<KarmaAudit> history = KarmaAudit.findByUserId(userId);
        List<KarmaAudit.KarmaAuditSnapshot> snapshots = history.stream().map(KarmaAudit::toSnapshot).toList();

        return Response.ok(snapshots).build();
    }

    /**
     * Manually adjust a user's karma.
     *
     * <p>
     * Allows super admins to add or deduct karma for exceptional cases. All adjustments are logged with admin user ID.
     *
     * @param userId
     *            User ID
     * @param request
     *            Adjustment request with delta and reason
     * @return Updated karma summary
     */
    @POST
    @Path("/{userId}/adjust")
    @Operation(
            summary = "Manually adjust user karma",
            description = "Adjusts user's karma for exceptional cases. All adjustments are logged with admin user ID. Requires super_admin role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - karma adjusted",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = KarmaSummaryType.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Bad request - delta is zero or reason missing"),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions (requires super_admin role)"),
                    @APIResponse(
                            responseCode = "404",
                            description = "User not found"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response adjustKarma(@Parameter(
            description = "User UUID",
            required = true) @PathParam("userId") UUID userId, @Valid AdminKarmaAdjustmentType request) {
        if (request.delta() == 0) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Delta must be non-zero"))
                    .build();
        }

        if (request.reason() == null || request.reason().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Reason is required")).build();
        }

        UUID adminUserId = getAdminUserId();

        try {
            karmaService.adminAdjustKarma(userId, request.delta(), request.reason(), adminUserId, request.metadata());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        }

        // Fetch updated user and return karma summary
        User user = User.findById(userId);
        KarmaSummaryType summary = new KarmaSummaryType(user.directoryKarma, user.directoryTrustLevel,
                user.getKarmaToNextLevel(), user.getKarmaPrivilegeDescription(), user.isTrusted(),
                user.isDirectoryModerator());

        LOG.infof("Admin %s adjusted karma for user %s by %+d: %s", adminUserId, userId, request.delta(),
                request.reason());

        return Response.ok(summary).build();
    }

    /**
     * Manually change a user's trust level.
     *
     * <p>
     * Used to promote users to moderator or demote users for trust violations. All changes are logged.
     *
     * @param userId
     *            User ID
     * @param request
     *            Trust level change request
     * @return Updated karma summary
     */
    @PATCH
    @Path("/{userId}/trust-level")
    @Operation(
            summary = "Set user trust level",
            description = "Manually changes user's trust level (e.g., promote to moderator). All changes are logged. Requires super_admin role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - trust level updated",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = KarmaSummaryType.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Bad request - invalid trust level or reason missing"),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions (requires super_admin role)"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response setTrustLevel(@Parameter(
            description = "User UUID",
            required = true) @PathParam("userId") UUID userId, @Valid AdminTrustLevelChangeType request) {
        if (request.trustLevel() == null || request.trustLevel().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Trust level is required"))
                    .build();
        }

        if (request.reason() == null || request.reason().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Reason is required")).build();
        }

        UUID adminUserId = getAdminUserId();

        try {
            karmaService.setTrustLevel(userId, request.trustLevel(), request.reason(), adminUserId);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }

        // Fetch updated user and return karma summary
        User user = User.findById(userId);
        KarmaSummaryType summary = new KarmaSummaryType(user.directoryKarma, user.directoryTrustLevel,
                user.getKarmaToNextLevel(), user.getKarmaPrivilegeDescription(), user.isTrusted(),
                user.isDirectoryModerator());

        LOG.infof("Admin %s changed trust level for user %s to %s: %s", adminUserId, userId, request.trustLevel(),
                request.reason());

        return Response.ok(summary).build();
    }

    /**
     * Extracts the admin user ID from the security context.
     *
     * @return Admin user UUID
     */
    private UUID getAdminUserId() {
        String userIdStr = securityContext.getUserPrincipal().getName();
        return UUID.fromString(userIdStr);
    }
}
