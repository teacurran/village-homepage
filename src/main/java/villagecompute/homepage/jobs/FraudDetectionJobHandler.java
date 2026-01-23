/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.jobs;

import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.homepage.api.types.FraudAnalysisResultType;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.observability.LoggingConfig;
import villagecompute.homepage.services.AiTaggingBudgetService;
import villagecompute.homepage.services.BudgetAction;
import villagecompute.homepage.services.FraudDetectionService;

/**
 * Job handler for AI-powered fraud detection in marketplace listings.
 *
 * <p>
 * This handler processes pending marketplace listings, analyzing them for scam patterns, prohibited content, and policy
 * violations using Claude Sonnet 4 via LangChain4j. Budget enforcement occurs at four threshold levels per P2/P10:
 * <ul>
 * <li><b>NORMAL (&lt;75%):</b> Process all pending listings at full speed</li>
 * <li><b>REDUCE (75-90%):</b> Continue processing (fraud detection is safety-critical)</li>
 * <li><b>QUEUE (90-100%):</b> Skip processing, defer to next hour</li>
 * <li><b>HARD_STOP (&gt;100%):</b> Stop all operations until next monthly cycle</li>
 * </ul>
 *
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Check budget status via {@link AiTaggingBudgetService}</li>
 * <li>If QUEUE or HARD_STOP, skip processing and log warning</li>
 * <li>Query pending listings via {@link MarketplaceListing} query</li>
 * <li>For each listing, call {@link FraudDetectionService#analyzeListing}</li>
 * <li>Apply auto-flag/approve logic via {@link FraudDetectionService#autoFlagHighRisk}</li>
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
 * @see JobType#FRAUD_DETECTION
 * @see FraudDetectionService
 * @see AiTaggingBudgetService
 */
@ApplicationScoped
public class FraudDetectionJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(FraudDetectionJobHandler.class);

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    FraudDetectionService fraudDetectionService;

    @Inject
    AiTaggingBudgetService budgetService;

    // Metrics
    private Counter successCounter;
    private Counter failureCounter;
    private Counter budgetThrottleCounter;
    private Counter autoApproveCounter;
    private Counter autoFlagCounter;
    private Counter manualReviewCounter;

    @Override
    public JobType handlesType() {
        return JobType.FRAUD_DETECTION;
    }

    @Override
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        // Initialize metrics lazily
        initializeMetrics();

        // Set job context for logging
        LoggingConfig.enrichWithTraceContext();
        LoggingConfig.setJobId(jobId);

        Span span = tracer.spanBuilder("job.fraud_detection").setAttribute("job.id", jobId.toString())
                .setAttribute("job.type", JobType.FRAUD_DETECTION.name())
                .setAttribute("job.queue", JobQueue.BULK.name()).startSpan();

        try {
            LOG.infof("Starting fraud detection job: jobId=%s", jobId);

            // Check budget action
            BudgetAction budgetAction = budgetService.getCurrentBudgetAction();
            span.setAttribute("budget.action", budgetAction.name());
            span.setAttribute("budget.percent_used", budgetService.getBudgetPercentUsed());

            LOG.debugf("Budget action: %s, percentUsed=%.2f%%", budgetAction, budgetService.getBudgetPercentUsed());

            // Handle budget throttling
            if (budgetService.shouldStopProcessing(budgetAction)) {
                String message = String.format(
                        "Skipping fraud detection due to budget action: action=%s, percentUsed=%.2f%%, "
                                + "remaining=$%.2f",
                        budgetAction, budgetService.getBudgetPercentUsed(),
                        budgetService.getRemainingBudgetCents() / 100.0);
                LOG.warn(message);
                span.addEvent("budget_throttle");
                budgetThrottleCounter.increment();
                span.setStatus(StatusCode.OK, message);
                return;
            }

            // Query pending listings (status = 'pending_payment' or 'draft' that need fraud check)
            // Note: According to architecture, we check listings after creation but before activation
            // Listings in 'draft' or 'pending_payment' status need fraud analysis before going 'active'
            List<MarketplaceListing> pendingListings = MarketplaceListing
                    .list("status IN ('draft', 'pending_payment')");
            int totalListings = pendingListings.size();
            span.setAttribute("listings.total", totalListings);

            if (totalListings == 0) {
                LOG.debug("No pending listings found, job complete");
                span.setStatus(StatusCode.OK, "No listings to analyze");
                return;
            }

            LOG.infof("Found %d pending listings, budget action: %s", totalListings, budgetAction);

            int analyzedCount = 0;
            int autoApprovedCount = 0;
            int autoFlaggedCount = 0;
            int manualReviewCount = 0;
            int failureCount = 0;

            // Process each listing
            for (MarketplaceListing listing : pendingListings) {
                try {
                    // Analyze listing
                    FraudAnalysisResultType result = fraudDetectionService.analyzeListing(listing.id);

                    if (result != null) {
                        // Apply auto-action logic
                        fraudDetectionService.autoFlagHighRisk(listing.id, result);

                        analyzedCount++;

                        // Count outcomes based on confidence
                        if (result.confidence().compareTo(java.math.BigDecimal.valueOf(0.70)) > 0) {
                            autoFlaggedCount++;
                            autoFlagCounter.increment();
                            LOG.debugf("High-risk listing flagged: id=%s, title=\"%s\", confidence=%.2f, reasons=%s",
                                    listing.id, listing.title, result.confidence(), result.reasons());
                        } else if (result.confidence().compareTo(java.math.BigDecimal.valueOf(0.30)) < 0) {
                            autoApprovedCount++;
                            autoApproveCounter.increment();
                            LOG.debugf("Low-risk listing approved: id=%s, title=\"%s\", confidence=%.2f", listing.id,
                                    listing.title, result.confidence());
                        } else {
                            manualReviewCount++;
                            manualReviewCounter.increment();
                            LOG.debugf("Medium-risk listing queued for review: id=%s, title=\"%s\", confidence=%.2f",
                                    listing.id, listing.title, result.confidence());
                        }

                        successCounter.increment();
                    } else {
                        failureCount++;
                        failureCounter.increment();
                        LOG.warnf("Failed to analyze listing (null result): id=%s, title=\"%s\"", listing.id,
                                listing.title);
                    }

                } catch (Exception e) {
                    failureCount++;
                    failureCounter.increment();
                    LOG.errorf(e, "Failed to analyze listing: id=%s, title=\"%s\"", listing.id, listing.title);
                    // Continue with next listing despite error
                }

                // Re-check budget after each listing
                BudgetAction updatedAction = budgetService.getCurrentBudgetAction();
                if (budgetService.shouldStopProcessing(updatedAction)) {
                    LOG.warnf("Budget exhausted mid-processing: action=%s, analyzed=%d/%d listings", updatedAction,
                            analyzedCount, totalListings);
                    span.addEvent("budget_exhausted_mid_batch");
                    break;
                }
            }

            // Record final stats
            span.setAttribute("listings.analyzed", analyzedCount);
            span.setAttribute("listings.auto_approved", autoApprovedCount);
            span.setAttribute("listings.auto_flagged", autoFlaggedCount);
            span.setAttribute("listings.manual_review", manualReviewCount);
            span.setAttribute("listings.failed", failureCount);

            LOG.infof(
                    "Fraud detection job complete: jobId=%s, analyzed=%d, autoApproved=%d, autoFlagged=%d, "
                            + "manualReview=%d, failed=%d, budgetAction=%s, percentUsed=%.2f%%",
                    jobId, analyzedCount, autoApprovedCount, autoFlaggedCount, manualReviewCount, failureCount,
                    budgetAction, budgetService.getBudgetPercentUsed());

            span.setStatus(StatusCode.OK, String.format("Analyzed %d listings", analyzedCount));

        } catch (Exception e) {
            LOG.errorf(e, "Fraud detection job failed: jobId=%s", jobId);
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
            successCounter = Counter.builder("fraud.detection.listings.total").tag("status", "success")
                    .description("Total listings successfully analyzed").register(meterRegistry);
        }

        if (failureCounter == null) {
            failureCounter = Counter.builder("fraud.detection.listings.total").tag("status", "failure")
                    .description("Total listings failed to analyze").register(meterRegistry);
        }

        if (budgetThrottleCounter == null) {
            budgetThrottleCounter = Counter.builder("fraud.detection.budget.throttles")
                    .description("Number of times job was throttled due to budget").register(meterRegistry);
        }

        if (autoApproveCounter == null) {
            autoApproveCounter = Counter.builder("fraud.detection.actions.total").tag("action", "auto_approve")
                    .description("Number of listings auto-approved").register(meterRegistry);
        }

        if (autoFlagCounter == null) {
            autoFlagCounter = Counter.builder("fraud.detection.actions.total").tag("action", "auto_flag")
                    .description("Number of listings auto-flagged").register(meterRegistry);
        }

        if (manualReviewCounter == null) {
            manualReviewCounter = Counter.builder("fraud.detection.actions.total").tag("action", "manual_review")
                    .description("Number of listings queued for manual review").register(meterRegistry);
        }
    }
}
