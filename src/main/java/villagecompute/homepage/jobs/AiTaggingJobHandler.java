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
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.homepage.api.types.AiTagsType;
import villagecompute.homepage.observability.LoggingConfig;
import villagecompute.homepage.data.models.FeedItem;
import villagecompute.homepage.services.AiTaggingBudgetService;
import villagecompute.homepage.services.AiTaggingService;
import villagecompute.homepage.services.BudgetAction;

/**
 * Job handler for AI-powered content tagging with budget enforcement.
 *
 * <p>
 * This handler processes untagged feed items in batches (10-20 items depending on budget state), extracting topics,
 * sentiment, categories, and confidence scores using Claude Sonnet 4 via LangChain4j. Budget enforcement occurs at four
 * threshold levels per P2/P10:
 * <ul>
 * <li><b>NORMAL (&lt;75%):</b> Process 20-item batches at full speed</li>
 * <li><b>REDUCE (75-90%):</b> Process 10-item batches to conserve budget</li>
 * <li><b>QUEUE (90-100%):</b> Skip processing, defer to next hour</li>
 * <li><b>HARD_STOP (&gt;100%):</b> Stop all operations until next monthly cycle</li>
 * </ul>
 *
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Check budget status via {@link AiTaggingBudgetService}</li>
 * <li>If QUEUE or HARD_STOP, skip processing and log warning</li>
 * <li>Query untagged items via {@link FeedItem#findUntagged()}</li>
 * <li>Partition into batches based on budget action</li>
 * <li>For each batch, call {@link AiTaggingService#tagArticle} per item</li>
 * <li>Persist tags via {@link FeedItem#updateAiTags}</li>
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
 * <li>P2/P10 (AI Budget Control): Enforces $500/month ceiling with automatic throttling</li>
 * <li>P7 (Observability): OpenTelemetry traces and Micrometer metrics exported</li>
 * <li>P12 (Error Handling): Individual failures isolated, batch processing continues</li>
 * </ul>
 *
 * @see JobType#AI_TAGGING
 * @see AiTaggingService
 * @see AiTaggingBudgetService
 */
@ApplicationScoped
public class AiTaggingJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(AiTaggingJobHandler.class);

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    AiTaggingService aiTaggingService;

    @Inject
    AiTaggingBudgetService budgetService;

    // Metrics
    private Counter successCounter;
    private Counter failureCounter;
    private Counter budgetThrottleCounter;
    private Gauge budgetPercentGauge;

    @Override
    public JobType handlesType() {
        return JobType.AI_TAGGING;
    }

    @Override
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        // Initialize metrics lazily
        initializeMetrics();

        // Set job context for logging
        LoggingConfig.enrichWithTraceContext();
        LoggingConfig.setJobId(jobId);

        Span span = tracer.spanBuilder("job.ai_tagging").setAttribute("job.id", jobId.toString())
                .setAttribute("job.type", JobType.AI_TAGGING.name()).setAttribute("job.queue", JobQueue.BULK.name())
                .startSpan();

        try {
            LOG.infof("Starting AI tagging job: jobId=%s", jobId);

            // Check budget action
            BudgetAction budgetAction = budgetService.getCurrentBudgetAction();
            span.setAttribute("budget.action", budgetAction.name());
            span.setAttribute("budget.percent_used", budgetService.getBudgetPercentUsed());

            LOG.debugf("Budget action: %s, percentUsed=%.2f%%", budgetAction, budgetService.getBudgetPercentUsed());

            // Handle budget throttling
            if (budgetService.shouldStopProcessing(budgetAction)) {
                String message = String.format(
                        "Skipping AI tagging due to budget action: action=%s, percentUsed=%.2f%%, " + "remaining=$%.2f",
                        budgetAction, budgetService.getBudgetPercentUsed(),
                        budgetService.getRemainingBudgetCents() / 100.0);
                LOG.warn(message);
                span.addEvent("budget_throttle");
                budgetThrottleCounter.increment();
                span.setStatus(StatusCode.OK, message);
                return;
            }

            // Query untagged items
            List<FeedItem> untaggedItems = FeedItem.findUntagged();
            int totalItems = untaggedItems.size();
            span.setAttribute("items.total", totalItems);

            if (totalItems == 0) {
                LOG.debug("No untagged items found, job complete");
                span.setStatus(StatusCode.OK, "No items to tag");
                return;
            }

            LOG.infof("Found %d untagged items, budget action: %s", totalItems, budgetAction);

            // Determine batch size based on budget
            int batchSize = budgetService.getBatchSize(budgetAction);
            span.setAttribute("batch.size", batchSize);

            // Partition into batches
            List<List<FeedItem>> batches = Lists.partition(untaggedItems, batchSize);
            span.setAttribute("batches.count", batches.size());

            int successCount = 0;
            int failureCount = 0;

            // Process each batch
            for (int i = 0; i < batches.size(); i++) {
                List<FeedItem> batch = batches.get(i);

                LOG.debugf("Processing batch %d/%d: %d items", i + 1, batches.size(), batch.size());

                // Process each item in batch
                for (FeedItem item : batch) {
                    try {
                        // Tag article
                        AiTagsType tags = aiTaggingService.tagArticle(item.title, item.description, item.content);

                        if (tags != null) {
                            // Persist tags
                            FeedItem.updateAiTags(item, tags);
                            successCount++;
                            successCounter.increment();

                            LOG.debugf("Tagged item: id=%s, title=\"%s\", topics=%s, sentiment=%s, categories=%s",
                                    item.id, item.title, tags.topics(), tags.sentiment(), tags.categories());
                        } else {
                            failureCount++;
                            failureCounter.increment();
                            LOG.warnf("Failed to tag item (null tags): id=%s, title=\"%s\"", item.id, item.title);
                        }

                    } catch (Exception e) {
                        failureCount++;
                        failureCounter.increment();
                        LOG.errorf(e, "Failed to tag item: id=%s, title=\"%s\"", item.id, item.title);
                        // Continue with next item despite error
                    }
                }

                // Re-check budget between batches
                BudgetAction updatedAction = budgetService.getCurrentBudgetAction();
                if (budgetService.shouldStopProcessing(updatedAction)) {
                    LOG.warnf("Budget exhausted mid-processing: action=%s, tagged=%d/%d items", updatedAction,
                            successCount, totalItems);
                    span.addEvent("budget_exhausted_mid_batch");
                    break;
                }
            }

            // Record final stats
            span.setAttribute("items.tagged", successCount);
            span.setAttribute("items.failed", failureCount);

            LOG.infof("AI tagging job complete: jobId=%s, tagged=%d, failed=%d, budgetAction=%s, percentUsed=%.2f%%",
                    jobId, successCount, failureCount, budgetAction, budgetService.getBudgetPercentUsed());

            span.setStatus(StatusCode.OK, String.format("Tagged %d items", successCount));

        } catch (Exception e) {
            LOG.errorf(e, "AI tagging job failed: jobId=%s", jobId);
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;

        } finally {
            span.end();
            LoggingConfig.clearMDC();
        }
    }

    /**
     * Initializes Micrometer metrics on first invocation.
     */
    private void initializeMetrics() {
        if (successCounter == null) {
            successCounter = Counter.builder("ai.tagging.items.total").tag("status", "success")
                    .description("Total feed items successfully tagged").register(meterRegistry);
        }

        if (failureCounter == null) {
            failureCounter = Counter.builder("ai.tagging.items.total").tag("status", "failure")
                    .description("Total feed items failed to tag").register(meterRegistry);
        }

        if (budgetThrottleCounter == null) {
            budgetThrottleCounter = Counter.builder("ai.tagging.budget.throttles")
                    .description("Number of times job was throttled due to budget").register(meterRegistry);
        }

        if (budgetPercentGauge == null) {
            budgetPercentGauge = Gauge
                    .builder("ai.budget.percent_used", budgetService, AiTaggingBudgetService::getBudgetPercentUsed)
                    .description("AI budget usage percentage").register(meterRegistry);
        }
    }
}
