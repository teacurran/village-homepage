package villagecompute.homepage.api.rest.profile;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.UserProfileType;
import villagecompute.homepage.data.models.ReservedUsername;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.data.models.UserProfile;
import villagecompute.homepage.exceptions.ResourceNotFoundException;
import villagecompute.homepage.services.ProfileService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin endpoints for profile management (Feature F11).
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>GET /admin/api/profiles – List all profiles with filters</li>
 * <li>GET /admin/api/profiles/stats – Profile statistics</li>
 * <li>POST /admin/api/reserved-usernames – Add reserved username</li>
 * <li>DELETE /admin/api/reserved-usernames/{id} – Remove reserved username</li>
 * <li>GET /admin/api/reserved-usernames – List all reserved usernames</li>
 * </ul>
 *
 * <p>
 * Security: All endpoints require super_admin or support roles.
 * </p>
 */
@Path("/admin/api")
public class ProfileAdminResource {

    private static final Logger LOG = Logger.getLogger(ProfileAdminResource.class);

    @Inject
    ProfileService profileService;

    /**
     * List all profiles with optional filters.
     *
     * <p>
     * Query params:
     * <ul>
     * <li>published (boolean): Filter by published status</li>
     * <li>offset (int): Pagination offset (default 0)</li>
     * <li>limit (int): Page size (default 50, max 200)</li>
     * </ul>
     * </p>
     *
     * @param published
     *            optional published filter
     * @param offset
     *            pagination offset
     * @param limit
     *            page size
     * @return list of profiles
     */
    @GET
    @Path("/profiles")
    @RolesAllowed({User.ROLE_SUPER_ADMIN, User.ROLE_SUPPORT})
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response listProfiles(@QueryParam("published") Boolean published,
            @QueryParam("offset") @DefaultValue("0") int offset, @QueryParam("limit") @DefaultValue("50") int limit) {

        LOG.infof("Admin listing profiles: published=%s, offset=%d, limit=%d", published, offset, limit);

        // Enforce max limit
        if (limit > 200) {
            limit = 200;
        }

        List<UserProfile> profiles;
        if (published != null && published) {
            profiles = profileService.listPublishedProfiles(offset, limit);
        } else if (published != null && !published) {
            // Find unpublished profiles
            profiles = UserProfile.find("isPublished = false AND deletedAt IS NULL ORDER BY createdAt DESC")
                    .range(offset, offset + limit - 1).list();
        } else {
            // All profiles
            profiles = UserProfile.find("deletedAt IS NULL ORDER BY createdAt DESC").range(offset, offset + limit - 1)
                    .list();
        }

        List<UserProfileType> profileTypes = profiles.stream().map(this::toType).collect(Collectors.toList());

        return Response
                .ok(Map.of("profiles", profileTypes, "count", profileTypes.size(), "offset", offset, "limit", limit))
                .build();
    }

    /**
     * Get profile statistics.
     *
     * <p>
     * Returns:
     * <ul>
     * <li>total_profiles: Total profile count</li>
     * <li>published_profiles: Published profile count</li>
     * <li>total_views: Sum of all view counts</li>
     * </ul>
     * </p>
     *
     * @return profile statistics
     */
    @GET
    @Path("/profiles/stats")
    @RolesAllowed({User.ROLE_SUPER_ADMIN, User.ROLE_SUPPORT, User.ROLE_OPS, User.ROLE_READ_ONLY})
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStats() {
        LOG.info("Admin getting profile stats");

        Map<String, Object> stats = profileService.getStats();
        return Response.ok(stats).build();
    }

    /**
     * Add a reserved username.
     *
     * <p>
     * Request body:
     *
     * <pre>{@code
     * {
     *   "username": "example",
     *   "reason": "System: Reserved for example"
     * }
     * }</pre>
     * </p>
     *
     * @param request
     *            add reserved username request
     * @return created reserved username
     */
    @POST
    @Path("/reserved-usernames")
    @RolesAllowed({User.ROLE_SUPER_ADMIN})
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response addReservedUsername(@Valid AddReservedUsernameRequest request) {
        LOG.infof("Admin adding reserved username: %s (reason: %s)", request.username(), request.reason());

        try {
            ReservedUsername reserved = ReservedUsername.reserve(request.username(), request.reason());
            return Response.status(Response.Status.CREATED).entity(toReservedType(reserved)).build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /**
     * Remove a reserved username.
     *
     * <p>
     * Unreserves the username, making it available for profile creation.
     * </p>
     *
     * @param id
     *            reserved username UUID
     * @return no content
     */
    @DELETE
    @Path("/reserved-usernames/{id}")
    @RolesAllowed({User.ROLE_SUPER_ADMIN})
    @Transactional
    public Response removeReservedUsername(@PathParam("id") UUID id) {
        LOG.infof("Admin removing reserved username: %s", id);

        try {
            ReservedUsername reserved = ReservedUsername.findById(id);
            if (reserved == null) {
                throw new ResourceNotFoundException("Reserved username not found: " + id);
            }

            boolean removed = ReservedUsername.unreserve(reserved.username);
            if (removed) {
                return Response.noContent().build();
            } else {
                return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Reserved username not found"))
                        .build();
            }

        } catch (ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /**
     * List all reserved usernames.
     *
     * @return list of reserved usernames
     */
    @GET
    @Path("/reserved-usernames")
    @RolesAllowed({User.ROLE_SUPER_ADMIN, User.ROLE_SUPPORT, User.ROLE_OPS, User.ROLE_READ_ONLY})
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response listReservedUsernames() {
        LOG.info("Admin listing reserved usernames");

        List<ReservedUsername> reserved = ReservedUsername.listAll();
        List<ReservedUsernameType> types = reserved.stream().map(this::toReservedType).collect(Collectors.toList());

        return Response.ok(Map.of("reserved_usernames", types, "count", types.size())).build();
    }

    // Helper methods

    private UserProfileType toType(UserProfile profile) {
        return new UserProfileType(profile.id, profile.userId, profile.username, profile.displayName, profile.bio,
                profile.avatarUrl, profile.locationText, profile.websiteUrl, profile.socialLinks, profile.template,
                profile.templateConfig, profile.isPublished, profile.viewCount, profile.createdAt, profile.updatedAt);
    }

    private ReservedUsernameType toReservedType(ReservedUsername reserved) {
        return new ReservedUsernameType(reserved.id, reserved.username, reserved.reason, reserved.reservedAt);
    }

    /**
     * Request type for adding reserved username.
     */
    public record AddReservedUsernameRequest(@NotBlank(
            message = "Username is required") String username,

            @NotBlank(
                    message = "Reason is required") String reason) {
    }

    /**
     * Response type for reserved username.
     */
    public record ReservedUsernameType(UUID id, String username, String reason, java.time.Instant reservedAt) {
    }
}
