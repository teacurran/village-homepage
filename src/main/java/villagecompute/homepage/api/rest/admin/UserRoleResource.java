package villagecompute.homepage.api.rest.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.AssignRoleRequestType;
import villagecompute.homepage.api.types.UserRoleType;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.services.AuthIdentityService;

import java.util.List;
import java.util.UUID;

/**
 * Admin REST endpoints for user role management.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>{@code GET /admin/api/users/roles} – list all users with admin roles</li>
 * <li>{@code GET /admin/api/users/roles/{userId}} – get role for specific user</li>
 * <li>{@code PUT /admin/api/users/roles/{userId}} – assign/update admin role</li>
 * <li>{@code DELETE /admin/api/users/roles/{userId}} – revoke admin role</li>
 * </ul>
 *
 * <p>
 * <b>Security:</b> All endpoints are secured with {@code @RolesAllowed("super_admin")} per Section 3.7.1 RBAC
 * requirements. Only super admins can manage roles.
 *
 * <p>
 * <b>Audit Trail:</b> All role changes are logged via {@link AuthIdentityService} to satisfy admin action audit
 * requirements.
 *
 * <p>
 * Implements Task I2.T8 admin role management API per Iteration 2 goals.
 */
@Path("/admin/api/users/roles")
@RolesAllowed("super_admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserRoleResource {

    private static final Logger LOG = Logger.getLogger(UserRoleResource.class);

    @Inject
    AuthIdentityService authService;

    /**
     * Lists all users with admin roles.
     *
     * <p>
     * Optionally filters by specific role using the {@code role} query parameter.
     *
     * @param roleFilter
     *            optional role filter (super_admin, support, ops, read_only)
     * @return list of users with admin roles
     */
    @GET
    public Response listAdminUsers(@QueryParam("role") String roleFilter) {
        List<User> users;
        if (roleFilter != null && !roleFilter.isBlank()) {
            users = User.findByAdminRole(roleFilter);
        } else {
            users = User.findAdmins();
        }

        List<UserRoleType> response = users.stream().map(this::toType).toList();
        LOG.debugf("Listed %d admin users (filter: %s)", response.size(), roleFilter);
        return Response.ok(response).build();
    }

    /**
     * Retrieves role information for a specific user.
     *
     * @param userId
     *            the user ID
     * @return role information or 404 if user not found or has no admin role
     */
    @GET
    @Path("/{userId}")
    public Response getUserRole(@PathParam("userId") String userId) {
        UUID userUuid;
        try {
            userUuid = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Invalid user ID format"))
                    .build();
        }

        User user = User.findById(userUuid);
        if (user == null || user.deletedAt != null) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse("User not found")).build();
        }

        if (user.adminRole == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse("User has no admin role"))
                    .build();
        }

        return Response.ok(toType(user)).build();
    }

    /**
     * Assigns or updates an admin role for a user.
     *
     * <p>
     * The authenticated admin's user ID is extracted from the security context and recorded as the granter for audit
     * purposes.
     *
     * @param userId
     *            the user ID to assign the role to
     * @param request
     *            the role assignment request
     * @param securityContext
     *            injected security context for extracting current user
     * @return updated user role information
     */
    @PUT
    @Path("/{userId}")
    public Response assignRole(@PathParam("userId") String userId, @Valid AssignRoleRequestType request,
            @Context SecurityContext securityContext) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Request body required"))
                    .build();
        }

        UUID userUuid;
        try {
            userUuid = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Invalid user ID format"))
                    .build();
        }

        try {
            // TODO: Extract current user ID from JWT/session once authentication lands
            // For now, use placeholder that matches the bootstrap user pattern
            UUID grantedBy = extractCurrentUserId(securityContext);

            authService.assignRole(userUuid, request.role(), grantedBy);

            User user = User.findById(userUuid);
            LOG.infof("Assigned role %s to user %s by %s", request.role(), userUuid, grantedBy);
            return Response.ok(toType(user)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to assign role for user: %s", userId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to assign role")).build();
        }
    }

    /**
     * Revokes an admin role from a user.
     *
     * <p>
     * Prevents users from revoking their own super_admin role to avoid system lockout.
     *
     * @param userId
     *            the user ID to revoke the role from
     * @param securityContext
     *            injected security context for extracting current user
     * @return 204 No Content on success
     */
    @DELETE
    @Path("/{userId}")
    public Response revokeRole(@PathParam("userId") String userId, @Context SecurityContext securityContext) {
        UUID userUuid;
        try {
            userUuid = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Invalid user ID format"))
                    .build();
        }

        try {
            // TODO: Extract current user ID from JWT/session once authentication lands
            UUID currentUserId = extractCurrentUserId(securityContext);

            // Prevent self-revocation of super_admin role
            User targetUser = User.findById(userUuid);
            if (targetUser != null && currentUserId.equals(userUuid)
                    && User.ROLE_SUPER_ADMIN.equals(targetUser.adminRole)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorResponse("Cannot revoke own super_admin role")).build();
            }

            authService.revokeRole(userUuid, currentUserId);
            LOG.infof("Revoked admin role from user %s by %s", userUuid, currentUserId);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to revoke role for user: %s", userId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to revoke role")).build();
        }
    }

    /**
     * Converts a User entity to a UserRoleType DTO.
     */
    private UserRoleType toType(User user) {
        return new UserRoleType(user.id, user.email, user.displayName, user.adminRole, user.adminRoleGrantedAt,
                user.adminRoleGrantedBy);
    }

    /**
     * Extracts the current user ID from the security context.
     *
     * <p>
     * TODO: Once JWT authentication is fully implemented, this should extract the subject from the JWT token. For now,
     * returns a placeholder UUID.
     */
    private UUID extractCurrentUserId(SecurityContext securityContext) {
        // Placeholder: In production, extract from JWT token claims
        // String subject = securityContext.getUserPrincipal().getName();
        // return UUID.fromString(subject);

        // For bootstrap testing, use a fixed UUID that matches the first super admin
        // This will be replaced once JWT authentication with proper user context is implemented
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    /**
     * Simple error response record for API errors.
     */
    public record ErrorResponse(String error) {
    }
}
