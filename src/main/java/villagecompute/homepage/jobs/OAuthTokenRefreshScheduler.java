/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.jobs;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.jboss.logging.Logger;
import villagecompute.homepage.services.DelayedJobService;

/**
 * Scheduler for OAuth token refresh job.
 *
 * <p>
 * Enqueues a daily job to refresh expiring OAuth access tokens before they expire. This prevents social integration
 * failures by proactively refreshing tokens for users with tokens expiring within 7 days.
 *
 * <p>
 * The job refreshes tokens for all three OAuth providers:
 * <ul>
 * <li><b>Google:</b> Uses refresh_token grant (token persists forever until revoked)</li>
 * <li><b>Facebook:</b> Extends long-lived token (resets 60-day expiration)</li>
 * <li><b>Apple:</b> Uses refresh_token grant with JWT regeneration (6-month expiration)</li>
 * </ul>
 *
 * <p>
 * Jobs are processed asynchronously by {@link OAuthTokenRefreshJobHandler} with automatic retry on failure (3 retries
 * with exponential backoff per DEFAULT queue policy).
 *
 * @see OAuthTokenRefreshJobHandler
 */
@ApplicationScoped
public class OAuthTokenRefreshScheduler {

    private static final Logger LOG = Logger.getLogger(OAuthTokenRefreshScheduler.class);

    @Inject
    DelayedJobService jobService;

    /**
     * Schedules OAuth token refresh every day at 2am UTC.
     *
     * <p>
     * Queries users with tokens expiring within 7 days and refreshes their access tokens. Runs at 2am to minimize
     * impact on daytime API usage and avoid rate limits.
     */
    @Scheduled(
            cron = "0 0 2 * * ?")
    void scheduleOAuthTokenRefresh() {
        LOG.info("Enqueuing daily OAuth token refresh job");
        jobService.enqueue(JobType.OAUTH_TOKEN_REFRESH, Map.of());
    }
}
