/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.jobs;

import java.util.Map;

import io.quarkus.scheduler.Scheduled;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.homepage.data.models.FeedItem;
import villagecompute.homepage.services.DelayedJobService;

/**
 * Scheduler for AI tagging job enqueue operations.
 *
 * <p>
 * This scheduler runs hourly to check for untagged feed items and enqueues AI tagging jobs when new content is
 * available. It respects budget limits enforced by {@link AiTaggingJobHandler} - the scheduler only enqueues jobs, the
 * handler decides whether to process based on budget state.
 *
 * <p>
 * <b>Execution Schedule:</b>
 * <ul>
 * <li>Every 1 hour (cron: "0 * * * *")</li>
 * <li>Query count of untagged items: {@code FeedItem.count("ai_tagged = false")}</li>
 * <li>If count &gt; 0, enqueue AI_TAGGING job via {@link DelayedJobService}</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): Budget enforcement occurs in handler, not scheduler</li>
 * <li>P7 (Observability): All enqueue operations logged for audit trail</li>
 * </ul>
 *
 * @see JobType#AI_TAGGING
 * @see AiTaggingJobHandler
 * @see AiTaggingBudgetService
 */
@ApplicationScoped
public class AiTaggingScheduler {

    private static final Logger LOG = Logger.getLogger(AiTaggingScheduler.class);

    @Inject
    DelayedJobService jobService;

    /**
     * Checks for untagged items and enqueues AI tagging job if needed.
     *
     * <p>
     * Runs every hour (top of the hour). Queries database for count of items with {@code ai_tagged = false} and
     * enqueues job if any exist. Budget enforcement happens in the handler - this scheduler only triggers the
     * opportunity to process.
     */
    @Scheduled(
            cron = "0 0 * * * ?") // Every hour at minute 0 (Quarkus 6-part cron: second, minute, hour, day, month, day-of-week)
    void scheduleAiTagging() {
        try {
            // Count untagged items
            long untaggedCount = FeedItem.count("ai_tagged = false");

            if (untaggedCount > 0) {
                LOG.infof("Found %d untagged feed items, enqueuing AI tagging job", untaggedCount);

                // Enqueue job (budget checks happen in handler)
                jobService.enqueue(JobType.AI_TAGGING, Map.of("trigger", "scheduled", "untagged_count", untaggedCount));

                LOG.debugf("AI tagging job enqueued: untaggedCount=%d", untaggedCount);
            } else {
                LOG.debug("No untagged items found, skipping AI tagging job");
            }

        } catch (Exception e) {
            LOG.errorf(e, "Failed to schedule AI tagging job");
            // Don't rethrow - scheduler should continue on next iteration
        }
    }

    /**
     * Checks for untagged items immediately (useful for testing or manual trigger).
     *
     * <p>
     * This method is exposed as public for programmatic invocation (e.g., from admin endpoints or integration tests).
     * It uses the same logic as the scheduled method.
     */
    public void triggerImmediately() {
        LOG.info("Manual AI tagging trigger requested");
        scheduleAiTagging();
    }
}
