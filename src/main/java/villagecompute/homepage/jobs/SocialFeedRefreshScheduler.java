/*
 * Copyright (c) 2025 VillageCompute Inc. All rights reserved.
 */
package villagecompute.homepage.jobs;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.SocialToken;
import villagecompute.homepage.services.DelayedJobService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheduler for social feed refresh and token refresh jobs.
 *
 * <p>
 * Schedules two types of jobs:
 * <ol>
 * <li><b>Feed Refresh:</b> Every 30 minutes - refreshes posts from Instagram/Facebook API</li>
 * <li><b>Token Refresh:</b> Daily - checks for tokens expiring within 7 days and triggers refresh</li>
 * <li><b>Archive Expired:</b> Daily - archives posts for tokens expired > 7 days</li>
 * </ol>
 *
 * <p>
 * <b>Cron Schedules:</b>
 * <ul>
 * <li>Feed refresh: 0 star/30 star star star star ? (every 30 minutes)</li>
 * <li>Token refresh: 0 0 2 star star ? (daily at 2 AM UTC)</li>
 * <li>Archive expired: 0 0 3 star star ? (daily at 3 AM UTC)</li>
 * </ul>
 *
 * @see SocialFeedRefreshJobHandler for job execution logic
 */
@ApplicationScoped
public class SocialFeedRefreshScheduler {

    private static final Logger LOG = Logger.getLogger(SocialFeedRefreshScheduler.class);

    @Inject
    DelayedJobService jobService;

    /**
     * Schedules social feed refresh job every 30 minutes.
     * <p>
     * Refreshes posts from Instagram and Facebook for all active tokens.
     */
    @Scheduled(
            cron = "0 */30 * * * ?") // Every 30 minutes
    public void scheduleFeedRefresh() {
        try {
            jobService.enqueue(JobType.SOCIAL_REFRESH, Map.of());
            LOG.info("Scheduled social feed refresh job");

        } catch (Exception e) {
            LOG.errorf(e, "Failed to schedule social feed refresh job");
        }
    }

    /**
     * Schedules token refresh check daily at 2 AM UTC.
     * <p>
     * Checks for tokens expiring within 7 days and triggers refresh. This is a placeholder for future implementation of
     * actual OAuth token refresh via Meta Graph API.
     */
    @Scheduled(
            cron = "0 0 2 * * ?") // Daily at 2 AM UTC
    public void scheduleTokenRefreshCheck() {
        try {
            List<SocialToken> expiringSoon = SocialToken.findExpiringSoon(7);

            if (expiringSoon.isEmpty()) {
                LOG.debug("No social tokens expiring within 7 days");
                return;
            }

            LOG.infof("Found %d social tokens expiring within 7 days", expiringSoon.size());

            // TODO: Implement actual token refresh logic when Meta credentials configured
            // For now, just log the tokens that need refresh
            for (SocialToken token : expiringSoon) {
                LOG.warnf("Token %d for user %s platform %s expires soon (%s) - refresh not yet implemented", token.id,
                        token.userId, token.platform, token.expiresAt);
            }

        } catch (Exception e) {
            LOG.errorf(e, "Failed to check for expiring social tokens");
        }
    }

    /**
     * Schedules post archival for expired tokens daily at 3 AM UTC.
     * <p>
     * Archives posts for tokens that have been expired for more than 7 days per Policy P13.
     */
    @Scheduled(
            cron = "0 0 3 * * ?") // Daily at 3 AM UTC
    public void scheduleArchiveExpired() {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("archive_expired", true);

            jobService.enqueue(JobType.SOCIAL_REFRESH, payload);
            LOG.info("Scheduled social post archival job");

        } catch (Exception e) {
            LOG.errorf(e, "Failed to schedule social post archival job");
        }
    }
}
