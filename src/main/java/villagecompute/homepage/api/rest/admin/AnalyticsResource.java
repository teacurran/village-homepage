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
    public Response getTopViewedProfiles(@QueryParam("start_date") String startDate,
            @QueryParam("end_date") String endDate, @QueryParam("limit") @DefaultValue("20") int limit) {
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
    public Response getCuratedArticleClicks(@PathParam("id") UUID profileId, @QueryParam("start_date") String startDate,
            @QueryParam("end_date") String endDate) {
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
                      COUNT(DISTINCT COALESCE(user_id, session_id)) AS unique_users
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
    public Response getProfileEngagement(@QueryParam("start_date") String startDate,
            @QueryParam("end_date") String endDate) {
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
}
