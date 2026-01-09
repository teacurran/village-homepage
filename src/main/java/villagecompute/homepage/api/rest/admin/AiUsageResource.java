/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.api.rest.admin;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

import villagecompute.homepage.api.types.AiUsageType;
import villagecompute.homepage.data.models.AiUsageTracking;
import villagecompute.homepage.services.AiTaggingBudgetService;
import villagecompute.homepage.services.BudgetAction;

/**
 * Admin REST endpoints for AI usage monitoring and budget management.
 *
 * <p>
 * Provides operations dashboard visibility into token consumption, costs, budget enforcement states, and manual budget
 * limit adjustments per P2/P10 policy requirements.
 *
 * <p>
 * <b>Endpoints:</b>
 * <ul>
 * <li>GET /admin/api/ai-usage - Current month usage summary with budget action</li>
 * <li>GET /admin/api/ai-usage/history - Historical usage for last N months</li>
 * <li>PATCH /admin/api/ai-usage/{month}/budget - Update budget limit for specific month</li>
 * </ul>
 *
 * <p>
 * All endpoints secured with {@code @RolesAllowed} requiring super_admin or ops role per P8 access control policies.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): Budget visibility and adjustment capabilities</li>
 * <li>P8 (Admin Access): Role-based endpoint security</li>
 * <li>P7 (Observability): All mutations logged for audit trail</li>
 * </ul>
 *
 * @see AiUsageType
 * @see AiUsageTracking
 * @see AiTaggingBudgetService
 */
@Path("/admin/api/ai-usage")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"super_admin", "ops"})
public class AiUsageResource {

    private static final Logger LOG = Logger.getLogger(AiUsageResource.class);

    @Inject
    AiTaggingBudgetService budgetService;

    /**
     * Returns current month AI usage summary with budget action.
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * {
     *   "id": "123e4567-e89b-12d3-a456-426614174000",
     *   "month": "2025-01-01",
     *   "provider": "anthropic",
     *   "totalRequests": 42,
     *   "totalTokensInput": 52000,
     *   "totalTokensOutput": 8400,
     *   "estimatedCostCents": 1230,
     *   "budgetLimitCents": 50000,
     *   "budgetAction": "NORMAL",
     *   "percentUsed": 2.46,
     *   "remainingCents": 48770,
     *   "updatedAt": "2025-01-09T15:30:00Z",
     *   "estimatedCostFormatted": "$12.30",
     *   "budgetLimitFormatted": "$500.00",
     *   "remainingFormatted": "$487.70",
     *   "percentUsedFormatted": "2.46%"
     * }
     * </pre>
     *
     * @param provider
     *            optional provider filter (defaults to "anthropic")
     * @return current month usage with budget state
     */
    @GET
    public Response getCurrentMonthUsage(@QueryParam("provider") String provider) {
        String targetProvider = provider != null ? provider : AiUsageTracking.DEFAULT_PROVIDER;

        LOG.debugf("Fetching current month AI usage: provider=%s", targetProvider);

        AiUsageTracking tracking = budgetService.getCurrentMonthUsage(targetProvider);
        BudgetAction budgetAction = budgetService.getCurrentBudgetAction(targetProvider);

        AiUsageType response = AiUsageType.fromEntity(tracking, budgetAction);

        return Response.ok(response).build();
    }

    /**
     * Returns historical AI usage for last N months.
     *
     * <p>
     * Useful for tracking spend trends and forecasting budget needs. Results ordered by month descending (most recent
     * first).
     *
     * <p>
     * <b>Query Parameters:</b>
     * <ul>
     * <li>provider (optional): AI provider name, defaults to "anthropic"</li>
     * <li>months (optional): Number of months to retrieve, defaults to 12, max 24</li>
     * </ul>
     *
     * @param provider
     *            optional provider filter
     * @param months
     *            number of historical months to retrieve (default 12, max 24)
     * @return list of historical usage records
     */
    @GET
    @Path("/history")
    public Response getUsageHistory(@QueryParam("provider") String provider,
            @QueryParam("months") @Min(1) @Max(24) Integer months) {

        String targetProvider = provider != null ? provider : AiUsageTracking.DEFAULT_PROVIDER;
        int monthsToFetch = months != null ? months : 12;

        LOG.debugf("Fetching AI usage history: provider=%s, months=%d", targetProvider, monthsToFetch);

        // Calculate date range
        YearMonth currentMonth = YearMonth.now();
        LocalDate sinceMonth = currentMonth.minusMonths(monthsToFetch - 1).atDay(1);

        // Query historical records
        List<AiUsageTracking> trackingRecords = AiUsageTracking.find("#" + AiUsageTracking.QUERY_FIND_LAST_N_MONTHS,
                Map.of("provider", targetProvider, "sinceMonth", sinceMonth)).list();

        // Convert to API types with budget actions
        List<AiUsageType> response = new ArrayList<>();
        for (AiUsageTracking tracking : trackingRecords) {
            // Calculate budget action for historical month
            double percentUsed = tracking.getPercentUsed();
            BudgetAction action;
            if (percentUsed >= 100) {
                action = BudgetAction.HARD_STOP;
            } else if (percentUsed >= 90) {
                action = BudgetAction.QUEUE;
            } else if (percentUsed >= 75) {
                action = BudgetAction.REDUCE;
            } else {
                action = BudgetAction.NORMAL;
            }

            response.add(AiUsageType.fromEntity(tracking, action));
        }

        LOG.debugf("Retrieved %d historical usage records", response.size());

        return Response.ok(response).build();
    }

    /**
     * Updates budget limit for a specific month.
     *
     * <p>
     * Allows operations team to adjust budget limits in response to business needs (e.g., increase for high-traffic
     * months, decrease for budget cuts). All mutations logged for audit trail.
     *
     * <p>
     * <b>Request Body:</b>
     *
     * <pre>
     * {
     *   "budgetLimitCents": 75000
     * }
     * </pre>
     *
     * @param monthStr
     *            month in YYYY-MM format (e.g., "2025-01")
     * @param request
     *            request body with new budget limit
     * @return updated usage record
     */
    @PATCH
    @Path("/{month}/budget")
    @Transactional
    public Response updateBudgetLimit(@PathParam("month") String monthStr, Map<String, Object> request) {

        // Parse month
        YearMonth month;
        try {
            month = YearMonth.parse(monthStr);
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid month format, expected YYYY-MM: " + monthStr)).build();
        }

        // Extract new budget limit
        if (!request.containsKey("budgetLimitCents")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Missing required field: budgetLimitCents")).build();
        }

        int newBudgetLimitCents;
        try {
            Object value = request.get("budgetLimitCents");
            if (value instanceof Integer) {
                newBudgetLimitCents = (Integer) value;
            } else if (value instanceof Number) {
                newBudgetLimitCents = ((Number) value).intValue();
            } else {
                throw new IllegalArgumentException("budgetLimitCents must be a number");
            }
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid budgetLimitCents value: " + e.getMessage())).build();
        }

        // Validate budget limit
        if (newBudgetLimitCents < 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Budget limit cannot be negative")).build();
        }

        LOG.infof("Updating AI budget limit: month=%s, newLimit=%d cents ($%.2f)", month, newBudgetLimitCents,
                newBudgetLimitCents / 100.0);

        // Get or create tracking record
        String provider = (String) request.getOrDefault("provider", AiUsageTracking.DEFAULT_PROVIDER);
        LocalDate monthDate = month.atDay(1);

        AiUsageTracking tracking = AiUsageTracking.findByMonthAndProvider(monthDate, provider).orElseGet(() -> {
            AiUsageTracking newTracking = new AiUsageTracking();
            newTracking.month = monthDate;
            newTracking.provider = provider;
            return newTracking;
        });

        int oldBudgetLimit = tracking.budgetLimitCents;
        tracking.budgetLimitCents = newBudgetLimitCents;
        tracking.persist();

        LOG.infof("Updated AI budget limit: month=%s, provider=%s, oldLimit=%d, newLimit=%d", month, provider,
                oldBudgetLimit, newBudgetLimitCents);

        // Calculate new budget action
        BudgetAction budgetAction = budgetService.getCurrentBudgetAction(provider);

        AiUsageType response = AiUsageType.fromEntity(tracking, budgetAction);

        return Response.ok(response).build();
    }
}
