/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.api.rest.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.*;

/**
 * Admin REST endpoints for analytics dashboards (Policy F14.9).
 *
 * <p>
 * Provides analytics queries for profile engagement, curated article click-through rates, and user interaction metrics
 * derived from click tracking rollups. All endpoints query aggregated data from {@code click_stats_daily} and
 * {@code click_stats_daily_items} tables.
 *
 * <p>
 * <b>Endpoints:</b>
 * <ul>
 * <li>GET /admin/api/analytics/profiles/top-viewed - Top profiles by view count</li>
 * <li>GET /admin/api/analytics/profiles/{id}/curated-clicks - Curated article click stats for profile</li>
 * <li>GET /admin/api/analytics/profiles/engagement - Overall profile engagement metrics</li>
 * <li>GET /admin/api/analytics/overview - Dashboard overview cards (clicks today/range, users, AI budget)</li>
 * <li>GET /admin/api/analytics/clicks/category-performance - Click breakdown by category with filters</li>
 * <li>GET /admin/api/analytics/ai/budget - AI usage vs budget with daily trend</li>
 * <li>GET /admin/api/analytics/jobs/health - Job queue health metrics</li>
 * </ul>
 *
 * <p>
 * All endpoints secured with {@code @RolesAllowed} requiring super_admin, ops, or read_only role per P8 access control
 * policies.
 *
 * <p>
 * <b>Query Performance:</b> All queries use pre-aggregated rollup tables with indexes on stat_date, click_type, and
 * target_id. Raw click logs are NOT queried directly to avoid partition scan overhead.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F14.9: 90-day retention for raw clicks, indefinite retention for rollups</li>
 * <li>P8 (Admin Access): Role-based endpoint security</li>
 * <li>P14 (Privacy): Aggregated data only, no PII exposure</li>
 * </ul>
 *
 * @see villagecompute.homepage.data.models.ClickStatsDaily
 * @see villagecompute.homepage.data.models.ClickStatsDailyItems
 * @see villagecompute.homepage.jobs.ClickRollupJobHandler
 */
@Path("/admin/api/analytics")
@Tag(
        name = "Admin - Analytics",
        description = "Admin analytics dashboard endpoints (requires super_admin, ops, or read_only role)")
@SecurityRequirement(
        name = "bearerAuth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"super_admin", "ops", "read_only"})
public class AnalyticsResource {

    private static final Logger LOG = Logger.getLogger(AnalyticsResource.class);

    @Inject
    EntityManager entityManager;

    /**
     * Returns top-viewed profiles for a date range.
     *
     * <p>
     * Aggregates profile_view events from click_stats_daily_items and ranks by total clicks. Returns profile ID, total
     * views, unique users, and unique sessions.
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * {
     *   "profiles": [
     *     {
     *       "profile_id": "123e4567-e89b-12d3-a456-426614174000",
     *       "total_views": 1250,
     *       "unique_users": 890,
     *       "unique_sessions": 1050
     *     }
     *   ]
     * }
     * </pre>
     *
     * @param startDate
     *            start date (inclusive)
     * @param endDate
     *            end date (inclusive)
     * @param limit
     *            max results (default 20)
     * @return JSON with top profiles array
     */
    @GET
    @Path("/profiles/top-viewed")
    @Operation(
            summary = "Get top-viewed profiles",
            description = "Returns top-viewed profiles for a date range with total views and unique users. Requires super_admin, ops, or read_only role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - returns profile view statistics",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response getTopViewedProfiles(@Parameter(
            description = "Start date (YYYY-MM-DD, optional)") @QueryParam("start_date") String startDate,
            @Parameter(
                    description = "End date (YYYY-MM-DD, optional)") @QueryParam("end_date") String endDate,
            @Parameter(
                    description = "Max results (default 20, max 100)") @QueryParam("limit") @DefaultValue("20") int limit) {
        try {
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().minusDays(30);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

            if (limit < 1 || limit > 100) {
                limit = 20;
            }

            String sql = """
                    SELECT
                      target_id AS profile_id,
                      SUM(total_clicks) AS total_views,
                      SUM(unique_users) AS unique_users,
                      SUM(unique_sessions) AS unique_sessions
                    FROM click_stats_daily_items
                    WHERE click_type = 'profile_view'
                      AND stat_date BETWEEN :startDate AND :endDate
                    GROUP BY target_id
                    ORDER BY total_views DESC
                    LIMIT :limit
                    """;

            @SuppressWarnings("unchecked")
            List<Object[]> results = entityManager.createNativeQuery(sql).setParameter("startDate", start)
                    .setParameter("endDate", end).setParameter("limit", limit).getResultList();

            List<Map<String, Object>> profiles = new ArrayList<>();
            for (Object[] row : results) {
                Map<String, Object> profile = new HashMap<>();
                profile.put("profile_id", row[0].toString());
                profile.put("total_views", ((Number) row[1]).longValue());
                profile.put("unique_users", ((Number) row[2]).longValue());
                profile.put("unique_sessions", ((Number) row[3]).longValue());
                profiles.add(profile);
            }

            LOG.debugf("Retrieved %d top-viewed profiles for date range %s to %s", profiles.size(), start, end);

            return Response.ok(Map.of("profiles", profiles, "start_date", start.toString(), "end_date", end.toString(),
                    "count", profiles.size())).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to retrieve top-viewed profiles: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to retrieve analytics")).build();
        }
    }

    /**
     * Returns curated article click statistics for a specific profile.
     *
     * <p>
     * Aggregates profile_curated events from link_clicks table (raw data) with metadata JSONB extraction. Returns
     * article ID, article URL, slot name, total clicks, and unique users per article.
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * {
     *   "profile_id": "123e4567-e89b-12d3-a456-426614174000",
     *   "articles": [
     *     {
     *       "article_id": "abc123...",
     *       "article_url": "https://example.com/article",
     *       "article_slot": "top_pick",
     *       "total_clicks": 45,
     *       "unique_users": 32
     *     }
     *   ]
     * }
     * </pre>
     *
     * @param profileId
     *            profile UUID
     * @param startDate
     *            start date (inclusive)
     * @param endDate
     *            end date (inclusive)
     * @return JSON with article click stats
     */
    @GET
    @Path("/profiles/{id}/curated-clicks")
    @Operation(
            summary = "Get curated article click statistics",
            description = "Returns curated article click statistics for a specific profile. Requires super_admin, ops, or read_only role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - returns article click statistics",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response getCuratedArticleClicks(@Parameter(
            description = "Profile UUID",
            required = true) @PathParam("id") UUID profileId,
            @Parameter(
                    description = "Start date (YYYY-MM-DD, optional)") @QueryParam("start_date") String startDate,
            @Parameter(
                    description = "End date (YYYY-MM-DD, optional)") @QueryParam("end_date") String endDate) {
        try {
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().minusDays(30);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

            // Query raw clicks for profile_curated events with metadata extraction
            String sql = """
                    SELECT
                      target_id AS article_id,
                      target_url AS article_url,
                      metadata->>'article_slot' AS article_slot,
                      COUNT(*) AS total_clicks,
                      COUNT(DISTINCT COALESCE(user_id::text, session_id)) AS unique_users
                    FROM link_clicks
                    WHERE click_type = 'profile_curated'
                      AND (metadata->>'profile_id')::UUID = :profileId
                      AND click_date BETWEEN :startDate AND :endDate
                    GROUP BY target_id, target_url, metadata->>'article_slot'
                    ORDER BY total_clicks DESC
                    """;

            @SuppressWarnings("unchecked")
            List<Object[]> results = entityManager.createNativeQuery(sql).setParameter("profileId", profileId)
                    .setParameter("startDate", start).setParameter("endDate", end).getResultList();

            List<Map<String, Object>> articles = new ArrayList<>();
            for (Object[] row : results) {
                Map<String, Object> article = new HashMap<>();
                article.put("article_id", row[0].toString());
                article.put("article_url", row[1].toString());
                article.put("article_slot", row[2] != null ? row[2].toString() : "unknown");
                article.put("total_clicks", ((Number) row[3]).longValue());
                article.put("unique_users", ((Number) row[4]).longValue());
                articles.add(article);
            }

            LOG.debugf("Retrieved %d curated article click stats for profile %s", articles.size(), profileId);

            return Response.ok(Map.of("profile_id", profileId.toString(), "articles", articles, "start_date",
                    start.toString(), "end_date", end.toString(), "count", articles.size())).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to retrieve curated article clicks for profile %s: %s", profileId, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to retrieve analytics")).build();
        }
    }

    /**
     * Returns overall profile engagement metrics.
     *
     * <p>
     * Aggregates profile_view and profile_curated events from rollup tables. Returns total profile views, total curated
     * clicks, unique users, and engagement rate (curated clicks / profile views).
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * {
     *   "total_profile_views": 5250,
     *   "total_curated_clicks": 1320,
     *   "unique_users": 3890,
     *   "engagement_rate": 0.2514,
     *   "avg_views_per_profile": 42.5,
     *   "avg_clicks_per_profile": 10.7
     * }
     * </pre>
     *
     * @param startDate
     *            start date (inclusive)
     * @param endDate
     *            end date (inclusive)
     * @return JSON with engagement metrics
     */
    @GET
    @Path("/profiles/engagement")
    @Operation(
            summary = "Get profile engagement metrics",
            description = "Returns overall profile engagement metrics including views, clicks, and engagement rates. Requires super_admin, ops, or read_only role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - returns engagement metrics",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response getProfileEngagement(@Parameter(
            description = "Start date (YYYY-MM-DD, optional)") @QueryParam("start_date") String startDate,
            @Parameter(
                    description = "End date (YYYY-MM-DD, optional)") @QueryParam("end_date") String endDate) {
        try {
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().minusDays(30);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

            // Query profile_view stats
            String viewSql = """
                    SELECT
                      COUNT(DISTINCT target_id) AS profile_count,
                      SUM(total_clicks) AS total_views,
                      SUM(unique_users) AS unique_users
                    FROM click_stats_daily_items
                    WHERE click_type = 'profile_view'
                      AND stat_date BETWEEN :startDate AND :endDate
                    """;

            Object[] viewResult = (Object[]) entityManager.createNativeQuery(viewSql).setParameter("startDate", start)
                    .setParameter("endDate", end).getSingleResult();

            long profileCount = ((Number) viewResult[0]).longValue();
            long totalViews = ((Number) viewResult[1]).longValue();
            long uniqueUsers = ((Number) viewResult[2]).longValue();

            // Query profile_curated stats
            String curatedSql = """
                    SELECT
                      SUM(total_clicks) AS total_clicks,
                      COUNT(DISTINCT target_id) AS article_count
                    FROM click_stats_daily_items
                    WHERE click_type = 'profile_curated'
                      AND stat_date BETWEEN :startDate AND :endDate
                    """;

            Object[] curatedResult = (Object[]) entityManager.createNativeQuery(curatedSql)
                    .setParameter("startDate", start).setParameter("endDate", end).getSingleResult();

            long totalCuratedClicks = curatedResult[0] != null ? ((Number) curatedResult[0]).longValue() : 0;
            long articleCount = ((Number) curatedResult[1]).longValue();

            // Calculate engagement rate
            double engagementRate = totalViews > 0 ? (double) totalCuratedClicks / totalViews : 0.0;
            double avgViewsPerProfile = profileCount > 0 ? (double) totalViews / profileCount : 0.0;
            double avgClicksPerProfile = profileCount > 0 ? (double) totalCuratedClicks / profileCount : 0.0;

            Map<String, Object> metrics = new HashMap<>();
            metrics.put("total_profile_views", totalViews);
            metrics.put("total_curated_clicks", totalCuratedClicks);
            metrics.put("unique_users", uniqueUsers);
            metrics.put("profile_count", profileCount);
            metrics.put("article_count", articleCount);
            metrics.put("engagement_rate", engagementRate);
            metrics.put("avg_views_per_profile", avgViewsPerProfile);
            metrics.put("avg_clicks_per_profile", avgClicksPerProfile);
            metrics.put("start_date", start.toString());
            metrics.put("end_date", end.toString());

            LOG.debugf("Retrieved profile engagement metrics for date range %s to %s", start, end);

            return Response.ok(metrics).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to retrieve profile engagement metrics: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to retrieve analytics")).build();
        }
    }

    /**
     * Returns overview dashboard metrics for admin analytics UI.
     *
     * <p>
     * Aggregates total clicks today, clicks for date range (1d/7d/30d), unique users today, and AI budget usage
     * percentage. Used by AnalyticsDashboard.tsx overview cards.
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * {
     *   "clicks_today": 1523,
     *   "clicks_range": 8942,
     *   "date_range": "7d",
     *   "unique_users_today": 456,
     *   "ai_budget_pct": 67.3,
     *   "ai_cost_cents": 3365,
     *   "ai_budget_cents": 5000,
     *   "daily_trend": [...]
     * }
     * </pre>
     *
     * @param dateRange
     *            date range: 1d, 7d, or 30d (default 7d)
     * @return JSON with overview metrics
     */
    @GET
    @Path("/overview")
    @Operation(
            summary = "Get dashboard overview metrics",
            description = "Returns overview dashboard metrics including clicks, users, and AI budget usage. Requires super_admin, ops, or read_only role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - returns overview metrics",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response getOverview(@Parameter(
            description = "Date range (1d, 7d, or 30d, default 7d)") @QueryParam("date_range") @DefaultValue("7d") String dateRange) {
        try {
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = switch (dateRange) {
                case "1d" -> endDate.minusDays(1);
                case "30d" -> endDate.minusDays(30);
                default -> endDate.minusDays(7);
            };

            // Total clicks today
            String todaySql = """
                    SELECT COALESCE(SUM(total_clicks), 0), COALESCE(SUM(unique_users), 0)
                    FROM click_stats_daily
                    WHERE stat_date = :today
                    """;
            Object[] todayResult = (Object[]) entityManager.createNativeQuery(todaySql)
                    .setParameter("today", LocalDate.now()).getSingleResult();

            long clicksToday = ((Number) todayResult[0]).longValue();
            long uniqueUsersToday = ((Number) todayResult[1]).longValue();

            // Total clicks in date range
            String rangeSql = """
                    SELECT COALESCE(SUM(total_clicks), 0)
                    FROM click_stats_daily
                    WHERE stat_date BETWEEN :start AND :end
                    """;
            long clicksRange = ((Number) entityManager.createNativeQuery(rangeSql).setParameter("start", startDate)
                    .setParameter("end", endDate).getSingleResult()).longValue();

            // Daily trend for sparkline (last 7 days)
            String trendSql = """
                    SELECT stat_date, COALESCE(SUM(total_clicks), 0) AS clicks
                    FROM click_stats_daily
                    WHERE stat_date BETWEEN :trendStart AND :today
                    GROUP BY stat_date
                    ORDER BY stat_date ASC
                    """;
            @SuppressWarnings("unchecked")
            List<Object[]> trendResults = entityManager.createNativeQuery(trendSql)
                    .setParameter("trendStart", LocalDate.now().minusDays(7)).setParameter("today", LocalDate.now())
                    .getResultList();

            List<Map<String, Object>> dailyTrend = new ArrayList<>();
            for (Object[] row : trendResults) {
                dailyTrend.add(Map.of("date", row[0].toString(), "clicks", ((Number) row[1]).longValue()));
            }

            // AI budget (from ai_usage_tracking table)
            // Monthly budget set to $50 (5000 cents)
            int monthlyBudgetCents = 5000;

            String aiSql = """
                    SELECT COALESCE(SUM(cost_cents), 0)
                    FROM ai_usage_tracking
                    WHERE DATE_TRUNC('month', created_at) = DATE_TRUNC('month', CURRENT_DATE)
                    """;
            long costCents = ((Number) entityManager.createNativeQuery(aiSql).getSingleResult()).longValue();

            double budgetPct = monthlyBudgetCents > 0 ? (double) costCents / monthlyBudgetCents * 100 : 0;

            Map<String, Object> response = new HashMap<>();
            response.put("clicks_today", clicksToday);
            response.put("clicks_range", clicksRange);
            response.put("date_range", dateRange);
            response.put("unique_users_today", uniqueUsersToday);
            response.put("ai_budget_pct", Math.round(budgetPct * 10) / 10.0); // Round to 1 decimal
            response.put("ai_cost_cents", costCents);
            response.put("ai_budget_cents", monthlyBudgetCents);
            response.put("daily_trend", dailyTrend);

            LOG.debugf("Retrieved overview metrics for date range %s: %d clicks today, %d clicks in range", dateRange,
                    clicksToday, clicksRange);

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to retrieve overview analytics: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to retrieve overview analytics")).build();
        }
    }

    /**
     * Returns category performance breakdown for admin analytics dashboard.
     *
     * <p>
     * Aggregates clicks by click_type with optional filtering. Returns total clicks and percentage per category. Used
     * by AnalyticsDashboard.tsx pie/donut chart.
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * {
     *   "categories": [
     *     {"type": "feed_item", "clicks": 5240, "percentage": 45.2},
     *     {"type": "directory_site", "clicks": 3890, "percentage": 33.6}
     *   ],
     *   "total_clicks": 11590
     * }
     * </pre>
     *
     * @param clickTypes
     *            optional comma-separated list of click types to filter (e.g., "feed_item,directory_site")
     * @param startDate
     *            start date (inclusive)
     * @param endDate
     *            end date (inclusive)
     * @return JSON with category breakdown
     */
    @GET
    @Path("/clicks/category-performance")
    @Operation(
            summary = "Get category performance breakdown",
            description = "Returns click breakdown by category with optional filtering. Requires super_admin, ops, or read_only role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - returns category performance data",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response getCategoryPerformance(@Parameter(
            description = "Comma-separated click types to filter (optional)") @QueryParam("click_types") String clickTypes,
            @Parameter(
                    description = "Start date (YYYY-MM-DD, optional)") @QueryParam("start_date") String startDate,
            @Parameter(
                    description = "End date (YYYY-MM-DD, optional)") @QueryParam("end_date") String endDate) {
        try {
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now().minusDays(7);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now();

            // Parse click types filter
            List<String> typeFilter = null;
            if (clickTypes != null && !clickTypes.isBlank()) {
                typeFilter = Arrays.asList(clickTypes.split(","));
            }

            // Build SQL with optional type filter
            String sql = """
                    SELECT click_type, SUM(total_clicks) AS clicks
                    FROM click_stats_daily
                    WHERE stat_date BETWEEN :startDate AND :endDate
                    """ + (typeFilter != null ? " AND click_type IN (:clickTypes)" : "") + """
                    GROUP BY click_type
                    ORDER BY clicks DESC
                    """;

            var query = entityManager.createNativeQuery(sql).setParameter("startDate", start).setParameter("endDate",
                    end);

            if (typeFilter != null) {
                query.setParameter("clickTypes", typeFilter);
            }

            @SuppressWarnings("unchecked")
            List<Object[]> results = query.getResultList();

            // Calculate total for percentages
            long totalClicks = results.stream().mapToLong(row -> ((Number) row[1]).longValue()).sum();

            List<Map<String, Object>> categories = new ArrayList<>();
            for (Object[] row : results) {
                String type = (String) row[0];
                long clicks = ((Number) row[1]).longValue();
                double percentage = totalClicks > 0 ? (double) clicks / totalClicks * 100 : 0;

                categories
                        .add(Map.of("type", type, "clicks", clicks, "percentage", Math.round(percentage * 10) / 10.0));
            }

            LOG.debugf("Retrieved category performance for %d categories, %d total clicks", categories.size(),
                    totalClicks);

            return Response.ok(Map.of("categories", categories, "total_clicks", totalClicks, "start_date",
                    start.toString(), "end_date", end.toString())).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to retrieve category performance: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to retrieve category performance")).build();
        }
    }

    /**
     * Returns AI budget usage details with daily trend and threshold warnings.
     *
     * <p>
     * Queries ai_usage_tracking table for current month usage and calculates percentage of $50 monthly budget. Returns
     * threshold states (75%, 90%, 100%) and 7-day cost trend.
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * {
     *   "current_cost_cents": 3750,
     *   "monthly_budget_cents": 5000,
     *   "usage_pct": 75.0,
     *   "threshold_75": true,
     *   "threshold_90": false,
     *   "threshold_100": false,
     *   "daily_costs": [...]
     * }
     * </pre>
     *
     * @return JSON with AI budget details
     */
    @GET
    @Path("/ai/budget")
    @Operation(
            summary = "Get AI budget usage",
            description = "Returns AI budget usage details with daily trend and threshold warnings. Requires super_admin, ops, or read_only role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - returns AI budget details",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response getAiBudget() {
        try {
            int monthlyBudgetCents = 5000; // $50 monthly budget

            // Current month total cost
            String currentSql = """
                    SELECT COALESCE(SUM(cost_cents), 0)
                    FROM ai_usage_tracking
                    WHERE DATE_TRUNC('month', created_at) = DATE_TRUNC('month', CURRENT_DATE)
                    """;
            long currentCostCents = ((Number) entityManager.createNativeQuery(currentSql).getSingleResult())
                    .longValue();

            // Daily costs for last 7 days
            String dailySql = """
                    SELECT DATE(created_at) AS day, COALESCE(SUM(cost_cents), 0) AS cost
                    FROM ai_usage_tracking
                    WHERE created_at >= CURRENT_DATE - INTERVAL '7 days'
                    GROUP BY DATE(created_at)
                    ORDER BY day ASC
                    """;
            @SuppressWarnings("unchecked")
            List<Object[]> dailyResults = entityManager.createNativeQuery(dailySql).getResultList();

            List<Map<String, Object>> dailyCosts = new ArrayList<>();
            for (Object[] row : dailyResults) {
                dailyCosts.add(Map.of("date", row[0].toString(), "cost_cents", ((Number) row[1]).longValue()));
            }

            double usagePct = (double) currentCostCents / monthlyBudgetCents * 100;

            Map<String, Object> response = new HashMap<>();
            response.put("current_cost_cents", currentCostCents);
            response.put("monthly_budget_cents", monthlyBudgetCents);
            response.put("usage_pct", Math.round(usagePct * 10) / 10.0);
            response.put("threshold_75", usagePct >= 75);
            response.put("threshold_90", usagePct >= 90);
            response.put("threshold_100", usagePct >= 100);
            response.put("daily_costs", dailyCosts);

            LOG.debugf("Retrieved AI budget: %s cents used of %s (%.1f%%)", Long.toString(currentCostCents),
                    Integer.toString(monthlyBudgetCents), usagePct);

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to retrieve AI budget: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to retrieve AI budget")).build();
        }
    }

    /**
     * Returns job queue health metrics for delayed job monitoring.
     *
     * <p>
     * Queries delayed_jobs table for backlog size, average wait time, and stuck job count per queue. Color-coded
     * thresholds: green (&lt;10 backlog), yellow (10-50), red (&gt;50 or stuck jobs present).
     *
     * <p>
     * <b>Example Response:</b>
     *
     * <pre>
     * {
     *   "queues": [
     *     {
     *       "queue": "DEFAULT",
     *       "backlog": 5,
     *       "avg_wait_seconds": 12.3,
     *       "stuck_jobs": 0,
     *       "status": "healthy"
     *     }
     *   ]
     * }
     * </pre>
     *
     * @return JSON with queue health stats
     */
    @GET
    @Path("/jobs/health")
    @Operation(
            summary = "Get job queue health metrics",
            description = "Returns job queue health metrics including backlog size, wait time, and stuck jobs. Requires super_admin, ops, or read_only role.")
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Success - returns job health metrics",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "401",
                            description = "Unauthorized - missing or invalid authentication"),
                    @APIResponse(
                            responseCode = "403",
                            description = "Forbidden - insufficient permissions"),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error")})
    public Response getJobHealth() {
        try {
            String sql = """
                    SELECT
                      queue,
                      COUNT(*) FILTER (WHERE status = 'pending') AS backlog,
                      AVG(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - scheduled_at))) FILTER (WHERE status = 'pending') AS avg_wait_seconds,
                      COUNT(*) FILTER (WHERE status = 'in_progress' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '30 minutes') AS stuck_jobs
                    FROM delayed_jobs
                    WHERE status IN ('pending', 'in_progress')
                    GROUP BY queue
                    ORDER BY queue
                    """;

            @SuppressWarnings("unchecked")
            List<Object[]> results = entityManager.createNativeQuery(sql).getResultList();

            List<Map<String, Object>> queues = new ArrayList<>();
            for (Object[] row : results) {
                String queue = (String) row[0];
                long backlog = ((Number) row[1]).longValue();
                double avgWaitSeconds = row[2] != null ? ((Number) row[2]).doubleValue() : 0.0;
                long stuckJobs = ((Number) row[3]).longValue();

                // Determine health status
                String status;
                if (stuckJobs > 0 || backlog > 50) {
                    status = "critical";
                } else if (backlog > 10) {
                    status = "warning";
                } else {
                    status = "healthy";
                }

                queues.add(Map.of("queue", queue, "backlog", backlog, "avg_wait_seconds",
                        Math.round(avgWaitSeconds * 10) / 10.0, "stuck_jobs", stuckJobs, "status", status));
            }

            LOG.debugf("Retrieved job health for %d queues", queues.size());

            return Response.ok(Map.of("queues", queues, "timestamp", LocalDate.now().toString())).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to retrieve job health: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to retrieve job health")).build();
        }
    }
}
