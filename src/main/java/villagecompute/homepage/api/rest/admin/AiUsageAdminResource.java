/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.api.rest.admin;

import java.time.LocalDate;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import villagecompute.homepage.api.types.AiUsageReportType;
import villagecompute.homepage.api.types.CacheStatsType;
import villagecompute.homepage.data.models.AiUsageTracking;
import villagecompute.homepage.services.AiUsageTrackingService;

/**
 * Admin REST resource for AI usage monitoring and cost tracking (Feature I4.T6).
 *
 * <p>
 * Provides endpoints for administrators to monitor AI API usage, cache performance, and budget consumption. All
 * endpoints require {@code super_admin} role per Policy P1 (RBAC).
 *
 * <p>
 * <b>Endpoints:</b>
 * <ul>
 * <li>{@code GET /admin/api/ai-usage/current-month} - Current month usage and budget status</li>
 * <li>{@code GET /admin/api/ai-usage/cache-stats} - Cache hit rate and performance metrics</li>
 * <li>{@code GET /admin/api/ai-usage/cost-report} - Historical cost report by date range</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P1 (RBAC): Only super_admin can access AI usage data</li>
 * <li>P2/P10 (AI Budget Control): Monitoring dashboard for $500/month budget enforcement</li>
 * </ul>
 *
 * @see AiUsageTrackingService
 * @see AiUsageTracking
 */
@Path("/admin/api/ai-usage")
@Produces(MediaType.APPLICATION_JSON)
public class AiUsageAdminResource {

    private static final Logger LOG = Logger.getLogger(AiUsageAdminResource.class);

    @Inject
    AiUsageTrackingService usageTrackingService;

    /**
     * Gets current month's AI usage and budget status.
     *
     * <p>
     * Returns total requests, token counts, estimated costs, and budget usage percentage for the current month. Used by
     * admin dashboard to monitor real-time AI spending.
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * {
     *   "id": "123e4567-e89b-12d3-a456-426614174000",
     *   "month": "2025-01-01",
     *   "provider": "anthropic",
     *   "totalRequests": 1234,
     *   "totalTokensInput": 500000,
     *   "totalTokensOutput": 250000,
     *   "estimatedCostCents": 15000,
     *   "budgetLimitCents": 50000,
     *   "percentUsed": 30.0
     * }
     * </pre>
     *
     * @return current month usage record
     */
    @GET
    @Path("/current-month")
    @RolesAllowed("super_admin")
    public Response getCurrentMonthUsage() {
        LOG.debug("Fetching current month AI usage for admin dashboard");

        AiUsageTracking usage = usageTrackingService.getCurrentMonthUsage();

        if (usage == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("No usage data found for current month").build();
        }

        return Response.ok(usage).build();
    }

    /**
     * Gets cache statistics for AI result caching.
     *
     * <p>
     * Returns cache hit rate, total hits/misses, and cache size metrics. Target hit rate: &gt;50% per acceptance
     * criteria.
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * {
     *   "hits": 5000,
     *   "misses": 3000,
     *   "total": 8000,
     *   "hitRate": 62.5,
     *   "meetsTarget": true
     * }
     * </pre>
     *
     * @return cache statistics
     */
    @GET
    @Path("/cache-stats")
    @RolesAllowed("super_admin")
    public Response getCacheStats() {
        LOG.debug("Fetching cache statistics for admin dashboard");

        CacheStatsType stats = usageTrackingService.getCacheStatistics();

        return Response.ok(stats).build();
    }

    /**
     * Gets cost report for a date range.
     *
     * <p>
     * Aggregates token usage and costs across all AI services for the specified date range. Used for historical
     * analysis and budget forecasting.
     *
     * <p>
     * <b>Query Parameters:</b>
     * <ul>
     * <li>{@code startDate} (required): Start of report period (ISO 8601 format: YYYY-MM-DD)</li>
     * <li>{@code endDate} (required): End of report period (ISO 8601 format: YYYY-MM-DD)</li>
     * </ul>
     *
     * <p>
     * <b>Example Request:</b> {@code GET /admin/api/ai-usage/cost-report?startDate=2025-01-01&endDate=2025-01-31}
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * {
     *   "startDate": "2025-01-01",
     *   "endDate": "2025-01-31",
     *   "totalRequests": 10000,
     *   "totalInputTokens": 5000000,
     *   "totalOutputTokens": 2500000,
     *   "estimatedCostCents": 45000,
     *   "budgetLimitCents": 50000,
     *   "percentUsed": 90.0,
     *   "costDollars": 450.00
     * }
     * </pre>
     *
     * @param startDate
     *            start of date range (ISO 8601 format)
     * @param endDate
     *            end of date range (ISO 8601 format)
     * @return cost report for date range
     */
    @GET
    @Path("/cost-report")
    @RolesAllowed("super_admin")
    public Response getCostReport(@QueryParam("startDate") String startDate, @QueryParam("endDate") String endDate) {
        if (startDate == null || startDate.isBlank() || endDate == null || endDate.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Both startDate and endDate query parameters are required (ISO 8601 format: YYYY-MM-DD)")
                    .build();
        }

        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);

            if (start.isAfter(end)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("startDate must be before or equal to endDate").build();
            }

            LOG.debugf("Fetching cost report for admin dashboard: %s to %s", startDate, endDate);

            AiUsageReportType report = usageTrackingService.getUsageReport(start, end);

            if (report == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("No usage data found for date range: " + startDate + " to " + endDate).build();
            }

            return Response.ok(report).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse date parameters: startDate=%s, endDate=%s", startDate, endDate);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid date format. Use ISO 8601 format: YYYY-MM-DD").build();
        }
    }
}
