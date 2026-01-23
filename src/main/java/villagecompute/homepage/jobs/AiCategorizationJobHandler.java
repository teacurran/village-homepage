/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.jobs;

import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.homepage.api.types.ListingCategorizationResultType;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.observability.LoggingConfig;
import villagecompute.homepage.services.AiCategorizationService;
import villagecompute.homepage.services.AiTaggingBudgetService;
import villagecompute.homepage.services.BudgetAction;

/**
 * Job handler for AI-powered marketplace listing categorization with budget enforcement.
 *
 * <p>
 * This handler processes marketplace listings without AI category suggestions, analyzing title and description to
 * suggest appropriate category and subcategory from the marketplace taxonomy. Results are stored in JSONB for human
 * review before applying to the actual category_id field.
 *
 * <p>
 * <b>Batch Processing Strategy:</b> Processes up to 50 listings per API call to minimize costs per P2/P10 budget
 * policy. Budget enforcement occurs at four threshold levels:
 * <ul>
 * <li><b>NORMAL (&lt;75%):</b> Process 50-listing batches at full speed</li>
 * <li><b>REDUCE (75-90%):</b> Process 25-listing batches to conserve budget</li>
 * <li><b>QUEUE (90-100%):</b> Skip processing, defer to next hour</li>
 * <li><b>HARD_STOP (&gt;100%):</b> Stop all operations until next monthly cycle</li>
 * </ul>
 *
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Check budget status via {@link AiTaggingBudgetService}</li>
 * <li>If QUEUE or HARD_STOP, skip processing and log warning</li>
 * <li>Query uncategorized listings via {@link MarketplaceListing#findWithoutAiSuggestions()}</li>
 * <li>Partition into batches (25-50 items based on budget action)</li>
 * <li>For each batch, call {@link AiCategorizationService#categorizeListingsBatch}</li>
 * <li>Store suggestions via {@link AiCategorizationService#storeCategorizationSuggestion}</li>
 * <li>Flag low confidence results (&lt;0.7) for manual review</li>
 * <li>Export telemetry spans and metrics to Prometheus</li>
 * </ol>
 *
 * <p>
 * Individual item failures do NOT fail the entire batch - errors are logged and processing continues with remaining
 * items.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): Shares $500/month ceiling with feed tagging and fraud detection</li>
 * <li>P7 (Observability): OpenTelemetry traces and Micrometer metrics exported</li>
 * <li>P12 (Error Handling): Individual failures isolated, batch processing continues</li>
 * <li>F12.4 (Marketplace): AI suggestions stored in JSONB, not auto-applied to category_id</li>
 * </ul>
 *
 * @see JobType#AI_CATEGORIZATION
 * @see AiCategorizationService
 * @see AiTaggingBudgetService
 * @see MarketplaceListing
 */
@ApplicationScoped
public class AiCategorizationJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(AiCategorizationJobHandler.class);

    private static final int BATCH_SIZE_NORMAL = 50;
    private static final int BATCH_SIZE_REDUCED = 25;

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    AiCategorizationService aiCategorizationService;

    @Inject
    AiTaggingBudgetService budgetService;

    // Metrics
    private Counter successCounter;
    private Counter failureCounter;
    private Counter lowConfidenceCounter;
    private Counter budgetThrottleCounter;

    @Override
    public JobType handlesType() {
        return JobType.AI_CATEGORIZATION;
    }

    @Override
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        // Initialize metrics lazily
        initializeMetrics();

        // Set job context for logging
        LoggingConfig.enrichWithTraceContext();
        LoggingConfig.setJobId(jobId);

        Span span = tracer.spanBuilder("job.ai_categorization").setAttribute("job.id", jobId.toString())
                .setAttribute("job.type", JobType.AI_CATEGORIZATION.name())
                .setAttribute("job.queue", JobQueue.BULK.name()).startSpan();

        try {
            LOG.infof("Starting marketplace AI categorization job: jobId=%s", jobId);

            // Check budget action
            BudgetAction budgetAction = budgetService.getCurrentBudgetAction();
            span.setAttribute("budget.action", budgetAction.name());
            span.setAttribute("budget.percent_used", budgetService.getBudgetPercentUsed());

            LOG.debugf("Budget action: %s, percentUsed=%.2f%%", budgetAction, budgetService.getBudgetPercentUsed());

            // Handle budget throttling
            if (budgetService.shouldStopProcessing(budgetAction)) {
                String message = String.format(
                        "Skipping marketplace categorization due to budget action: action=%s, percentUsed=%.2f%%, "
                                + "remaining=$%.2f",
                        budgetAction, budgetService.getBudgetPercentUsed(),
                        budgetService.getRemainingBudgetCents() / 100.0);
                LOG.warn(message);
                span.addEvent("budget_throttle");
                budgetThrottleCounter.increment();
                span.setStatus(StatusCode.OK, message);
                return;
            }

            // Query listings without AI suggestions
            List<MarketplaceListing> uncategorizedListings = MarketplaceListing.findWithoutAiSuggestions();
            int totalListings = uncategorizedListings.size();
            span.setAttribute("listings.total", totalListings);

            if (totalListings == 0) {
                LOG.debug("No uncategorized listings found, job complete");
                span.setStatus(StatusCode.OK, "No listings to categorize");
                return;
            }

            LOG.infof("Found %d uncategorized listings, budget action: %s", totalListings, budgetAction);

            // Determine batch size based on budget
            int batchSize = getBatchSize(budgetAction);
            span.setAttribute("batch.size", batchSize);

            // Partition into batches
            List<List<MarketplaceListing>> batches = Lists.partition(uncategorizedListings, batchSize);
            span.setAttribute("batches.count", batches.size());

            int successCount = 0;
            int failureCount = 0;
            int lowConfidenceCount = 0;

            // Process each batch
            for (int i = 0; i < batches.size(); i++) {
                List<MarketplaceListing> batch = batches.get(i);

                LOG.debugf("Processing batch %d/%d: %d listings", i + 1, batches.size(), batch.size());

                try {
                    // Categorize entire batch in single API call
                    List<ListingCategorizationResultType> results = aiCategorizationService
                            .categorizeListingsBatch(batch);

                    // Store results
                    for (int j = 0; j < batch.size(); j++) {
                        MarketplaceListing listing = batch.get(j);
                        ListingCategorizationResultType result = j < results.size() ? results.get(j) : null;

                        if (result != null) {
                            // Store suggestion
                            aiCategorizationService.storeCategorizationSuggestion(listing, result);
                            successCount++;
                            successCounter.increment();

                            // Track low confidence
                            if (result.confidenceScore() < 0.7) {
                                lowConfidenceCount++;
                                lowConfidenceCounter.increment();
                                LOG.warnf(
                                        "Low confidence categorization for listing: id=%s, title=\"%s\", "
                                                + "category=%s/%s, confidence=%.2f, reasoning=%s",
                                        listing.id, listing.title, result.category(), result.subcategory(),
                                        result.confidenceScore(), result.reasoning());
                            } else {
                                LOG.debugf("Categorized listing: id=%s, title=\"%s\", category=%s/%s, confidence=%.2f",
                                        listing.id, listing.title, result.category(), result.subcategory(),
                                        result.confidenceScore());
                            }
                        } else {
                            failureCount++;
                            failureCounter.increment();
                            LOG.warnf("Failed to categorize listing (null result): id=%s, title=\"%s\"", listing.id,
                                    listing.title);
                        }
                    }

                } catch (Exception e) {
                    failureCount += batch.size();
                    LOG.errorf(e, "Failed to categorize batch %d/%d (%d listings)", i + 1, batches.size(),
                            batch.size());
                    // Continue with next batch despite error
                }

                // Re-check budget between batches
                BudgetAction updatedAction = budgetService.getCurrentBudgetAction();
                if (budgetService.shouldStopProcessing(updatedAction)) {
                    LOG.warnf("Budget exhausted mid-processing: action=%s, categorized=%d/%d listings", updatedAction,
                            successCount, totalListings);
                    span.addEvent("budget_exhausted_mid_batch");
                    break;
                }
            }

            // Record final stats
            span.setAttribute("listings.categorized", successCount);
            span.setAttribute("listings.failed", failureCount);
            span.setAttribute("listings.low_confidence", lowConfidenceCount);

            LOG.infof(
                    "Marketplace AI categorization job complete: jobId=%s, categorized=%d, failed=%d, "
                            + "lowConfidence=%d, budgetAction=%s, percentUsed=%.2f%%",
                    jobId, successCount, failureCount, lowConfidenceCount, budgetAction,
                    budgetService.getBudgetPercentUsed());

            span.setStatus(StatusCode.OK, String.format("Categorized %d listings", successCount));

        } catch (Exception e) {
            LOG.errorf(e, "Marketplace AI categorization job failed: jobId=%s", jobId);
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;

        } finally {
            span.end();
            LoggingConfig.clearMDC();
        }
    }

    /**
     * Determines batch size based on budget action.
     *
     * @param budgetAction
     *            current budget action from AiTaggingBudgetService
     * @return batch size (50 for NORMAL, 25 for REDUCE)
     */
    private int getBatchSize(BudgetAction budgetAction) {
        return switch (budgetAction) {
            case NORMAL -> BATCH_SIZE_NORMAL;
            case REDUCE -> BATCH_SIZE_REDUCED;
            default -> BATCH_SIZE_REDUCED; // Conservative default
        };
    }

    /**
     * Initializes Micrometer metrics on first invocation.
     */
    private void initializeMetrics() {
        if (successCounter == null) {
            successCounter = Counter.builder("ai.categorization.listings.total").tag("status", "success")
                    .description("Total marketplace listings successfully categorized").register(meterRegistry);
        }

        if (failureCounter == null) {
            failureCounter = Counter.builder("ai.categorization.listings.total").tag("status", "failure")
                    .description("Total marketplace listings failed to categorize").register(meterRegistry);
        }

        if (lowConfidenceCounter == null) {
            lowConfidenceCounter = Counter.builder("ai.categorization.listings.low_confidence")
                    .description("Total marketplace listings with low confidence (<0.7) categorization")
                    .register(meterRegistry);
        }

        if (budgetThrottleCounter == null) {
            budgetThrottleCounter = Counter.builder("ai.categorization.budget.throttles")
                    .description("Number of times categorization job was throttled due to budget")
                    .register(meterRegistry);
        }
    }
}
