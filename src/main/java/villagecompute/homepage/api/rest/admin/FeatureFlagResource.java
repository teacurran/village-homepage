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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import villagecompute.homepage.api.types.FeatureFlagType;
import villagecompute.homepage.api.types.UpdateFeatureFlagRequestType;
import villagecompute.homepage.data.models.FeatureFlag;
import villagecompute.homepage.services.FeatureFlagService;

import java.util.List;
import java.util.Optional;

/**
 * Admin REST endpoints for feature flag management.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>{@code GET /admin/api/feature-flags} – list all flags</li>
 * <li>{@code GET /admin/api/feature-flags/{key}} – get single flag</li>
 * <li>{@code PATCH /admin/api/feature-flags/{key}} – update flag configuration</li>
 * </ul>
 *
 * <p>
 * <b>Security:</b> All endpoints should be secured with {@code @RolesAllowed("super_admin")} once RBAC is implemented.
 * For now, assumes deployment behind authenticated admin gateway.
 *
 * <p>
 * <b>Audit Trail:</b> All mutations synchronously record audit entries via {@link FeatureFlagService} to satisfy Policy
 * P14 traceability requirements.
 */
@Path("/admin/api/feature-flags")
@RolesAllowed("super_admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FeatureFlagResource {

    private static final Logger LOG = Logger.getLogger(FeatureFlagResource.class);

    @Inject
    FeatureFlagService featureFlagService;

    /**
     * Lists all feature flags.
     *
     * @return list of all flags with full configuration
     */
    @GET
    public Response listFlags() {
        List<FeatureFlag> flags = featureFlagService.getAllFlags();
        List<FeatureFlagType> response = flags.stream().map(this::toType).toList();
        return Response.ok(response).build();
    }

    /**
     * Retrieves a single feature flag by key.
     *
     * @param key
     *            the flag identifier
     * @return flag configuration or 404 if not found
     */
    @GET
    @Path("/{key}")
    public Response getFlag(@PathParam("key") String key) {
        Optional<FeatureFlag> flag = featureFlagService.getFlag(key);
        if (flag.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("Feature flag not found: " + key)).build();
        }
        return Response.ok(toType(flag.get())).build();
    }

    /**
     * Updates a feature flag configuration.
     *
     * <p>
     * Supports partial updates via PATCH semantics. Null fields are ignored. Audit trail is recorded synchronously.
     *
     * @param key
     *            the flag to update
     * @param request
     *            update payload with optional fields
     * @return updated flag configuration
     */
    @PATCH
    @Path("/{key}")
    public Response updateFlag(@PathParam("key") String key, @Valid UpdateFeatureFlagRequestType request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse("Request body required"))
                    .build();
        }

        try {
            // TODO: Extract actorId from JWT/session once authentication lands
            Long actorId = 1L; // Placeholder for super_admin bootstrap user

            FeatureFlag updated = featureFlagService.updateFlag(key, request.description(), request.enabled(),
                    request.rolloutPercentage(), request.whitelist(), request.analyticsEnabled(), actorId,
                    request.reason());

            LOG.infof("Feature flag updated: %s by actor=%d", key, actorId);
            return Response.ok(toType(updated)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse(e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to update feature flag: %s", key);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("Failed to update feature flag")).build();
        }
    }

    /**
     * Converts a FeatureFlag entity to a FeatureFlagType DTO.
     */
    private FeatureFlagType toType(FeatureFlag flag) {
        return new FeatureFlagType(flag.flagKey, flag.description, flag.enabled, flag.rolloutPercentage, flag.whitelist,
                flag.analyticsEnabled, flag.createdAt, flag.updatedAt);
    }

    /**
     * Simple error response record for API errors.
     */
    public record ErrorResponse(String error) {
    }
}
