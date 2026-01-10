/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.jobs;

import java.io.File;
import java.io.FileReader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.common.collect.Lists;
import com.opencsv.CSVReader;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.homepage.api.types.AiCategorySuggestionType;
import villagecompute.homepage.data.models.DirectoryAiSuggestion;
import villagecompute.homepage.data.models.DirectorySite;
import villagecompute.homepage.observability.LoggingConfig;
import villagecompute.homepage.services.AiCategorizationService;
import villagecompute.homepage.services.AiTaggingBudgetService;
import villagecompute.homepage.services.BudgetAction;

/**
 * Job handler for bulk CSV import with AI categorization.
 *
 * <p>
 * This handler processes CSV files uploaded by admins, creating DirectoryAiSuggestion records for each row. AI
 * categorization is applied in batches (10-20 items depending on budget state), storing suggestions for admin review
 * per Feature F13.14.
 *
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Check budget status via {@link AiTaggingBudgetService}</li>
 * <li>If QUEUE or HARD_STOP, skip processing and log warning</li>
 * <li>Read CSV file from temp storage</li>
 * <li>For each CSV row: validate, check duplicates, create DirectoryAiSuggestion record</li>
 * <li>Query unprocessed suggestions via {@link DirectoryAiSuggestion#findUnprocessed()}</li>
 * <li>Partition into batches based on budget action</li>
 * <li>For each batch, call {@link AiCategorizationService#suggestCategories} per item</li>
 * <li>Persist AI suggestions via {@link DirectoryAiSuggestion#updateSuggestion}</li>
 * <li>Export telemetry spans and metrics to Prometheus</li>
 * </ol>
 *
 * <p>
 * Individual item failures do NOT fail the entire batch - errors are logged and processing continues with remaining
 * items.
 *
 * <p>
 * <b>CSV Format:</b>
 * <ul>
 * <li>url (required) - Website URL to import</li>
 * <li>title (optional) - Override fetched title</li>
 * <li>description (optional) - Override fetched description</li>
 * <li>suggested_categories (optional) - Comma-separated category slugs (manual override)</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): Enforces $500/month ceiling with automatic throttling</li>
 * <li>P7 (Observability): OpenTelemetry traces and Micrometer metrics exported</li>
 * <li>F13.14 (Bulk Import): Admin review workflow with AI assistance and override capability</li>
 * </ul>
 *
 * @see JobType#DIRECTORY_BULK_IMPORT
 * @see AiCategorizationService
 * @see AiTaggingBudgetService
 */
@ApplicationScoped
public class BulkImportJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(BulkImportJobHandler.class);

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    AiCategorizationService aiCategorizationService;

    @Inject
    AiTaggingBudgetService budgetService;

    // Metrics
    private Counter parsedCounter;
    private Counter duplicateCounter;
    private Counter categorizedCounter;
    private Counter failureCounter;
    private Counter budgetThrottleCounter;
    private Gauge budgetPercentGauge;

    @Override
    public JobType handlesType() {
        return JobType.DIRECTORY_BULK_IMPORT;
    }

    @Override
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        // Initialize metrics lazily
        initializeMetrics();

        // Set job context for logging
        LoggingConfig.enrichWithTraceContext();
        LoggingConfig.setJobId(jobId);

        Span span = tracer.spanBuilder("job.directory_bulk_import").setAttribute("job.id", jobId.toString())
                .setAttribute("job.type", JobType.DIRECTORY_BULK_IMPORT.name())
                .setAttribute("job.queue", JobQueue.BULK.name()).startSpan();

        try {
            LOG.infof("Starting bulk import job: jobId=%s", jobId);

            // Extract CSV path from payload
            String csvPath = (String) payload.get("csv_path");
            String uploadedByUserIdStr = (String) payload.get("uploaded_by_user_id");
            UUID uploadedByUserId = UUID.fromString(uploadedByUserIdStr);

            span.setAttribute("csv_path", csvPath);
            span.setAttribute("uploaded_by_user_id", uploadedByUserId.toString());

            LOG.infof("Processing CSV file: path=%s, uploadedBy=%s", csvPath, uploadedByUserId);

            // Phase 1: Parse CSV and create DirectoryAiSuggestion records
            int parsedCount = parseCsvAndCreateSuggestions(csvPath, uploadedByUserId, span);
            span.setAttribute("rows.parsed", parsedCount);

            LOG.infof("CSV parsing complete: jobId=%s, parsedRows=%d", jobId, parsedCount);

            // Phase 2: AI categorization with budget enforcement
            int categorizedCount = categorizeSuggestions(span);
            span.setAttribute("items.categorized", categorizedCount);

            LOG.infof("Bulk import job complete: jobId=%s, parsedRows=%d, categorized=%d", jobId, parsedCount,
                    categorizedCount);

            span.setStatus(StatusCode.OK,
                    String.format("Processed %d rows, categorized %d", parsedCount, categorizedCount));

            // Clean up CSV file
            cleanupCsvFile(csvPath);

        } catch (Exception e) {
            LOG.errorf(e, "Bulk import job failed: jobId=%s", jobId);
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;

        } finally {
            span.end();
            LoggingConfig.clearMDC();
        }
    }

    /**
     * Parses CSV file and creates DirectoryAiSuggestion records.
     *
     * @param csvPath
     *            path to CSV file
     * @param uploadedByUserId
     *            user who uploaded CSV
     * @param span
     *            OpenTelemetry span for tracing
     * @return number of rows parsed
     */
    private int parseCsvAndCreateSuggestions(String csvPath, UUID uploadedByUserId, Span span) {
        int parsedCount = 0;

        try (CSVReader reader = new CSVReader(new FileReader(csvPath))) {
            String[] header = reader.readNext(); // Skip header row

            String[] row;
            while ((row = reader.readNext()) != null) {
                try {
                    // Parse row
                    String url = row.length > 0 ? row[0] : null;
                    String title = row.length > 1 && !row[1].isBlank() ? row[1] : null;
                    String description = row.length > 2 && !row[2].isBlank() ? row[2] : null;

                    // Validate URL
                    if (url == null || url.isBlank()) {
                        LOG.warnf("Skipping row with missing URL: row=%d", parsedCount + 1);
                        continue;
                    }

                    // Normalize URL
                    String normalizedUrl = DirectorySite.normalizeUrl(url);
                    String domain = DirectorySite.extractDomain(normalizedUrl);

                    // Check duplicate (skip if already exists in DirectorySite)
                    Optional<DirectorySite> existingSite = DirectorySite.findByUrl(normalizedUrl);
                    if (existingSite.isPresent()) {
                        LOG.debugf("Skipping duplicate URL: url=%s, existingSiteId=%s", normalizedUrl,
                                existingSite.get().id);
                        duplicateCounter.increment();
                        continue;
                    }

                    // Check duplicate in suggestions
                    Optional<DirectoryAiSuggestion> existingSuggestion = DirectoryAiSuggestion.findByUrl(normalizedUrl);
                    if (existingSuggestion.isPresent()) {
                        LOG.debugf("Skipping duplicate suggestion: url=%s, existingSuggestionId=%s", normalizedUrl,
                                existingSuggestion.get().id);
                        duplicateCounter.increment();
                        continue;
                    }

                    // Use URL as fallback title
                    if (title == null || title.isBlank()) {
                        title = domain;
                    }

                    // Create DirectoryAiSuggestion record
                    DirectoryAiSuggestion suggestion = new DirectoryAiSuggestion();
                    suggestion.url = normalizedUrl;
                    suggestion.domain = domain;
                    suggestion.title = title;
                    suggestion.description = description;
                    suggestion.status = DirectoryAiSuggestion.STATUS_PENDING;
                    suggestion.uploadedByUserId = uploadedByUserId;
                    suggestion.createdAt = Instant.now();
                    suggestion.updatedAt = Instant.now();
                    suggestion.persist();

                    parsedCount++;
                    parsedCounter.increment();

                    LOG.debugf("Created AI suggestion: id=%s, url=%s, title=%s", suggestion.id, normalizedUrl, title);

                } catch (Exception e) {
                    LOG.errorf(e, "Failed to parse CSV row: row=%d", parsedCount + 1);
                    failureCounter.increment();
                    // Continue with next row despite error
                }
            }

        } catch (Exception e) {
            LOG.errorf(e, "Failed to read CSV file: path=%s", csvPath);
            throw new RuntimeException("CSV parsing failed", e);
        }

        return parsedCount;
    }

    /**
     * Processes unprocessed suggestions with AI categorization.
     *
     * @param span
     *            OpenTelemetry span for tracing
     * @return number of items categorized
     */
    private int categorizeSuggestions(Span span) {
        // Check budget action
        BudgetAction budgetAction = budgetService.getCurrentBudgetAction();
        span.setAttribute("budget.action", budgetAction.name());
        span.setAttribute("budget.percent_used", budgetService.getBudgetPercentUsed());

        LOG.debugf("Budget action: %s, percentUsed=%.2f%%", budgetAction, budgetService.getBudgetPercentUsed());

        // Handle budget throttling
        if (budgetService.shouldStopProcessing(budgetAction)) {
            String message = String.format(
                    "Skipping AI categorization due to budget action: action=%s, percentUsed=%.2f%%, remaining=$%.2f",
                    budgetAction, budgetService.getBudgetPercentUsed(),
                    budgetService.getRemainingBudgetCents() / 100.0);
            LOG.warn(message);
            span.addEvent("budget_throttle");
            budgetThrottleCounter.increment();
            return 0;
        }

        // Query unprocessed suggestions
        List<DirectoryAiSuggestion> unprocessed = DirectoryAiSuggestion.findUnprocessed();
        int totalItems = unprocessed.size();
        span.setAttribute("items.total", totalItems);

        if (totalItems == 0) {
            LOG.debug("No unprocessed suggestions found");
            return 0;
        }

        LOG.infof("Found %d unprocessed suggestions, budget action: %s", totalItems, budgetAction);

        // Determine batch size based on budget
        int batchSize = budgetService.getBatchSize(budgetAction);
        span.setAttribute("batch.size", batchSize);

        // Partition into batches
        List<List<DirectoryAiSuggestion>> batches = Lists.partition(unprocessed, batchSize);
        span.setAttribute("batches.count", batches.size());

        int categorizedCount = 0;
        int failureCount = 0;

        // Process each batch
        for (int i = 0; i < batches.size(); i++) {
            List<DirectoryAiSuggestion> batch = batches.get(i);

            LOG.debugf("Processing batch %d/%d: %d items", i + 1, batches.size(), batch.size());

            // Process each item in batch
            for (DirectoryAiSuggestion item : batch) {
                try {
                    // Call AI categorization service
                    AiCategorySuggestionType aiSuggestion = aiCategorizationService.suggestCategories(item.url,
                            item.title, item.description);

                    if (aiSuggestion != null) {
                        // Calculate token usage (rough approximation)
                        long inputTokens = (item.url.length() + item.title.length()
                                + (item.description != null ? item.description.length() : 0)) / 4;
                        long outputTokens = aiSuggestion.toString().length() / 4;
                        int costCents = (int) Math.ceil((inputTokens * 0.0003) + (outputTokens * 0.0015));

                        // Persist AI suggestion
                        DirectoryAiSuggestion.updateSuggestion(item.id, aiSuggestion, inputTokens, outputTokens,
                                costCents);

                        categorizedCount++;
                        categorizedCounter.increment();

                        LOG.debugf("Categorized suggestion: id=%s, url=%s, categories=%d, confidence=%.2f", item.id,
                                item.url, aiSuggestion.suggestedCategories().size(), aiSuggestion.confidence());
                    } else {
                        failureCount++;
                        failureCounter.increment();
                        LOG.warnf("Failed to categorize suggestion (null result): id=%s, url=%s", item.id, item.url);
                    }

                } catch (Exception e) {
                    failureCount++;
                    failureCounter.increment();
                    LOG.errorf(e, "Failed to categorize suggestion: id=%s, url=%s", item.id, item.url);
                    // Continue with next item despite error
                }
            }

            // Re-check budget between batches
            BudgetAction updatedAction = budgetService.getCurrentBudgetAction();
            if (budgetService.shouldStopProcessing(updatedAction)) {
                LOG.warnf("Budget exhausted mid-processing: action=%s, categorized=%d/%d items", updatedAction,
                        categorizedCount, totalItems);
                span.addEvent("budget_exhausted_mid_batch");
                break;
            }
        }

        // Record final stats
        span.setAttribute("items.categorized", categorizedCount);
        span.setAttribute("items.failed", failureCount);

        LOG.infof("AI categorization complete: categorized=%d, failed=%d, budgetAction=%s, percentUsed=%.2f%%",
                categorizedCount, failureCount, budgetAction, budgetService.getBudgetPercentUsed());

        return categorizedCount;
    }

    /**
     * Cleans up temporary CSV file after processing.
     *
     * @param csvPath
     *            path to CSV file
     */
    private void cleanupCsvFile(String csvPath) {
        try {
            File csvFile = new File(csvPath);
            if (csvFile.exists() && csvFile.delete()) {
                LOG.debugf("Deleted CSV file: path=%s", csvPath);
            } else {
                LOG.warnf("Failed to delete CSV file: path=%s", csvPath);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Error deleting CSV file: path=%s", csvPath);
        }
    }

    /**
     * Initializes Micrometer metrics on first invocation.
     */
    private void initializeMetrics() {
        if (parsedCounter == null) {
            parsedCounter = Counter.builder("ai.categorization.csv.rows.total").tag("status", "parsed")
                    .description("Total CSV rows parsed").register(meterRegistry);
        }

        if (duplicateCounter == null) {
            duplicateCounter = Counter.builder("ai.categorization.csv.rows.total").tag("status", "duplicate")
                    .description("Total duplicate URLs skipped").register(meterRegistry);
        }

        if (categorizedCounter == null) {
            categorizedCounter = Counter.builder("ai.categorization.suggestions.total").tag("status", "categorized")
                    .description("Total suggestions successfully categorized").register(meterRegistry);
        }

        if (failureCounter == null) {
            failureCounter = Counter.builder("ai.categorization.suggestions.total").tag("status", "failure")
                    .description("Total suggestions failed to categorize").register(meterRegistry);
        }

        if (budgetThrottleCounter == null) {
            budgetThrottleCounter = Counter.builder("ai.categorization.budget.throttles")
                    .description("Number of times job was throttled due to budget").register(meterRegistry);
        }

        if (budgetPercentGauge == null) {
            budgetPercentGauge = Gauge
                    .builder("ai.budget.percent_used", budgetService, AiTaggingBudgetService::getBudgetPercentUsed)
                    .description("AI budget usage percentage").register(meterRegistry);
        }
    }
}
