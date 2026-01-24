/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.api.rest.admin;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.User;

/**
 * Admin UI resource controller for server-side rendered admin pages.
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>GET /admin/analytics - Analytics dashboard page with React island</li>
 * </ul>
 *
 * <p>
 * All UI endpoints secured with {@code @RolesAllowed} requiring super_admin, ops, or read_only role per P8 access
 * control policies.
 *
 * <p>
 * <b>React Islands:</b> Admin pages use the islands architecture where server-rendered Qute templates contain isolated
 * React components that hydrate on the client side. Components are mounted via {@code data-mount} attributes.
 *
 * @see AnalyticsResource for backend API endpoints
 * @see villagecompute.homepage.api.rest.admin.AnalyticsResource
 */
@Path("/admin")
@Tag(
        name = "Admin - Users",
        description = "Admin UI endpoints for server-side rendered pages (requires super_admin, ops, or read_only role)")
@SecurityRequirement(
        name = "bearerAuth")
public class AdminResource {

    private static final Logger LOG = Logger.getLogger(AdminResource.class);

    /**
     * Qute templates for admin pages.
     */
    @CheckedTemplate
    public static class Templates {
        /**
         * Analytics dashboard template.
         *
         * @param user
         *            authenticated user
         * @param userRole
         *            user's role (super_admin, ops, read_only)
         * @return template instance
         */
        public static native TemplateInstance analytics(User user, String userRole);
    }

    /**
     * Render analytics dashboard page with AnalyticsDashboard React island.
     *
     * <p>
     * This page displays comprehensive analytics including:
     * <ul>
     * <li>Overview cards: Total clicks, unique users, AI budget usage</li>
     * <li>Category performance: Pie/donut chart with filters</li>
     * <li>Job health: Queue backlog and stuck job monitoring</li>
     * </ul>
     *
     * <p>
     * The AnalyticsDashboard React component is mounted via data-mount attribute and fetches data from
     * {@code /admin/api/analytics/*} endpoints.
     *
     * @param ctx
     *            security context for authenticated user
     * @return Qute template instance
     */
    @GET
    @Path("/analytics")
    @Produces(MediaType.TEXT_HTML)
    @Operation(
            summary = "Render analytics dashboard page",
            description = "Returns server-rendered analytics dashboard with React island components. Requires super_admin, ops, or read_only role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - HTML page rendered",
                    content = @Content(
                            mediaType = MediaType.TEXT_HTML)),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions (requires super_admin, ops, or read_only role)"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    @RolesAllowed({User.ROLE_SUPER_ADMIN, User.ROLE_OPS, User.ROLE_READ_ONLY})
    public TemplateInstance analytics(@Context SecurityContext ctx) {
        User user = (User) ctx.getUserPrincipal();

        // Extract user role for frontend permission checks
        String userRole = user.hasRole(User.ROLE_SUPER_ADMIN) ? User.ROLE_SUPER_ADMIN
                : user.hasRole(User.ROLE_OPS) ? User.ROLE_OPS : User.ROLE_READ_ONLY;

        LOG.infof("Rendering analytics dashboard for user %s (role: %s)", user.id, userRole);

        return Templates.analytics(user, userRole);
    }
}
