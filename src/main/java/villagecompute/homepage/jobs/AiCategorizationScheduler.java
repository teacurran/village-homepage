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

import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.services.DelayedJobService;

/**
 * Scheduler for marketplace listing AI categorization job enqueue operations.
 *
 * <p>
 * This scheduler runs hourly to check for active marketplace listings without AI category suggestions and enqueues
 * categorization jobs when uncategorized content is available. It respects budget limits enforced by
 * {@link AiCategorizationJobHandler} - the scheduler only enqueues jobs, the handler decides whether to process based
 * on budget state.
 *
 * <p>
 * <b>Execution Schedule:</b>
 * <ul>
 * <li>Every 1 hour (cron: "0 * * * *")</li>
 * <li>Query count of uncategorized listings: {@code MarketplaceListing.count("status = 'active' AND
 * aiCategorySuggestion IS NULL")}</li>
 * <li>If count &gt; 0, enqueue AI_CATEGORIZATION job via {@link DelayedJobService}</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): Budget enforcement occurs in handler, not scheduler</li>
 * <li>P7 (Observability): All enqueue operations logged for audit trail</li>
 * <li>F12.4 (Marketplace): AI suggestions stored in JSONB for human review</li>
 * </ul>
 *
 * @see JobType#AI_CATEGORIZATION
 * @see AiCategorizationJobHandler
 * @see AiTaggingBudgetService
 * @see MarketplaceListing
 */
@ApplicationScoped
public class AiCategorizationScheduler {

    private static final Logger LOG = Logger.getLogger(AiCategorizationScheduler.class);

    @Inject
    DelayedJobService jobService;

    /**
     * Checks for uncategorized listings and enqueues AI categorization job if needed.
     *
     * <p>
     * Runs every hour (top of the hour). Queries database for count of active listings with
     * {@code aiCategorySuggestion IS NULL} and enqueues job if any exist. Budget enforcement happens in the handler -
     * this scheduler only triggers the opportunity to process.
     */
    @Scheduled(
            cron = "0 0 * * * ?") // Every hour at minute 0 (Quarkus 6-part cron: second, minute, hour, day, month,
                                  // day-of-week)
    void scheduleAiCategorization() {
        try {
            // Count uncategorized listings
            long uncategorizedCount = MarketplaceListing.count("status = 'active' AND aiCategorySuggestion IS NULL");

            if (uncategorizedCount > 0) {
                LOG.infof("Found %d uncategorized marketplace listings, enqueuing AI categorization job",
                        uncategorizedCount);

                // Enqueue job (budget checks happen in handler)
                jobService.enqueue(JobType.AI_CATEGORIZATION,
                        Map.of("trigger", "scheduled", "uncategorized_count", uncategorizedCount));

                LOG.debugf("AI categorization job enqueued: uncategorizedCount=%d", uncategorizedCount);
            } else {
                LOG.debug("No uncategorized listings found, skipping AI categorization job");
            }

        } catch (Exception e) {
            LOG.errorf(e, "Failed to schedule AI categorization job");
            // Don't rethrow - scheduler should continue on next iteration
        }
    }

    /**
     * Checks for uncategorized listings immediately (useful for testing or manual trigger).
     *
     * <p>
     * This method is exposed as public for programmatic invocation (e.g., from admin endpoints or integration tests).
     * It uses the same logic as the scheduled method.
     */
    public void triggerImmediately() {
        LOG.info("Manual AI categorization trigger requested");
        scheduleAiCategorization();
    }
}
