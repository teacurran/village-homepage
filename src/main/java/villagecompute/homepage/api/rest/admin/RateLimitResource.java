package villagecompute.homepage.api.rest.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.RateLimitConfigType;
import villagecompute.homepage.api.types.RateLimitViolationType;
import villagecompute.homepage.api.types.UpdateRateLimitConfigRequestType;
import villagecompute.homepage.data.models.RateLimitConfig;
import villagecompute.homepage.data.models.RateLimitViolation;
import villagecompute.homepage.services.RateLimitService;

import java.util.List;
import java.util.Optional;

/**
 * Admin REST endpoints for rate limit configuration and violation monitoring.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>{@code GET /admin/api/rate-limits} – list all rate limit configurations</li>
 * <li>{@code GET /admin/api/rate-limits/{action}/{tier}} – get specific configuration</li>
 * <li>{@code PATCH /admin/api/rate-limits/{action}/{tier}} – update configuration</li>
 * <li>{@code GET /admin/api/rate-limits/violations} – query violation logs</li>
 * </ul>
 *
 * <p>
 * <b>Security:</b> All endpoints require {@code super_admin} role per Policy P14/F14.2.
 *
 * <p>
 * <b>Audit Trail:</b> All configuration updates are logged with actor ID and automatically persisted via
 * {@link RateLimitService}.
 */
@Path("/admin/api/rate-limits")
@RolesAllowed("super_admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RateLimitResource {

    private static final Logger LOG = Logger.getLogger(RateLimitResource.class);

    @Inject
    RateLimitService rateLimitService;

    /**
     * Lists all rate limit configurations.
     *
     * @return list of all configs with full configuration
     */
    @GET
    public Response listConfigs() {
        List<RateLimitConfig> configs = rateLimitService.getAllConfigs();
        List<RateLimitConfigType> response = configs.stream().map(this::toType).toList();
        return Response.ok(response).build();
    }

    /**
     * Retrieves a specific rate limit configuration by action type and tier.
     *
     * @param actionType
     *            the action identifier (e.g., "login", "search")
     * @param tier
     *            the user tier ("anonymous", "logged_in", "trusted")
     * @return configuration or 404 if not found
     */
    @GET
    @Path("/{action}/{tier}")
    public Response getConfig(@PathParam("action") String actionType, @PathParam("tier") String tier) {
        Optional<RateLimitConfig> config = rateLimitService.getConfig(actionType, tier);
        if (config.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("Rate limit config not found: " + actionType + ":" + tier)).build();
        }
        return Response.ok(toType(config.get())).build();
    }

    /**
     * Updates a rate limit configuration.
     *
     * <p>
     * Supports partial updates via PATCH semantics. Null fields are ignored. Configuration cache is invalidated
     * automatically.
     *
     * @param actionType
     *            the action type to update
     * @param tier
     *            the tier to update
     * @param request
     *            update payload with optional fields
     * @return updated configuration
     */
    @PATCH
    @Path("/{action}/{tier}")
    public Response updateConfig(@PathParam("action") String actionType, @PathParam("tier") String tier,
            @Valid UpdateRateLimitConfigRequestType request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Request body required"))
                    .build();
        }

        try {
            // TODO: Extract actorId from JWT/session once authentication lands
            Long actorId = 1L; // Placeholder for super_admin bootstrap user

            RateLimitConfig updated = rateLimitService.updateConfig(actionType, tier, request.limitCount(),
                    request.windowSeconds(), actorId);

            LOG.infof("Rate limit config updated: action=%s tier=%s limit=%d window=%d actor=%d", actionType, tier,
                    updated.limitCount, updated.windowSeconds, actorId);

            return Response.ok(toType(updated)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to update rate limit config: %s:%s", actionType, tier);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to update rate limit config")).build();
        }
    }

    /**
     * Queries recent rate limit violations.
     *
     * <p>
     * Supports filtering by user ID or IP address. Returns up to 100 recent violations ordered by last_violation_at
     * DESC.
     *
     * @param userId
     *            filter by user ID (optional)
     * @param ipAddress
     *            filter by IP address (optional)
     * @param limit
     *            max results (default 100, max 1000)
     * @return list of violations
     */
    @GET
    @Path("/violations")
    public Response getViolations(@QueryParam("user_id") Long userId, @QueryParam("ip_address") String ipAddress,
            @QueryParam("limit") Integer limit) {
        int maxResults = limit != null ? Math.min(limit, 1000) : 100;

        List<RateLimitViolation> violations;
        if (userId != null) {
            violations = RateLimitViolation.findByUser(userId, maxResults);
        } else if (ipAddress != null && !ipAddress.isBlank()) {
            violations = RateLimitViolation.findByIp(ipAddress, maxResults);
        } else {
            violations = RateLimitViolation.findRecent(maxResults);
        }

        List<RateLimitViolationType> response = violations.stream().map(this::toViolationType).toList();
        return Response.ok(response).build();
    }

    /**
     * Converts a RateLimitConfig entity to a RateLimitConfigType DTO.
     */
    private RateLimitConfigType toType(RateLimitConfig config) {
        return new RateLimitConfigType(config.actionType, config.tier, config.limitCount, config.windowSeconds,
                config.updatedByUserId, config.updatedAt);
    }

    /**
     * Converts a RateLimitViolation entity to a RateLimitViolationType DTO.
     */
    private RateLimitViolationType toViolationType(RateLimitViolation violation) {
        return new RateLimitViolationType(violation.userId, violation.ipAddress, violation.actionType,
                violation.endpoint, violation.violationCount, violation.firstViolationAt, violation.lastViolationAt);
    }

    /**
     * Simple error response record for API errors.
     */
    public record ErrorResponse(String error) {
    }
}
