/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.services;

import java.time.LocalDate;
import java.time.YearMonth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.homepage.api.types.AiUsageReportType;
import villagecompute.homepage.api.types.CacheStatsType;
import villagecompute.homepage.config.AiCacheConfig;
import villagecompute.homepage.data.models.AiUsageTracking;

/**
 * Service for tracking AI API usage, cost estimation, and cache statistics.
 *
 * <p>
 * This service wraps {@link AiUsageTracking} entity and provides business logic for usage reporting, cost calculation,
 * and cache hit rate monitoring per P2/P10 budget policy (Feature I4.T6).
 *
 * <p>
 * <b>Key Capabilities:</b>
 * <ul>
 * <li>Log AI API usage (service, model, tokens, cost)</li>
 * <li>Generate aggregated usage reports by date range</li>
 * <li>Estimate costs based on Anthropic pricing (Sonnet vs Haiku)</li>
 * <li>Expose cache hit rate and budget metrics via Micrometer</li>
 * </ul>
 *
 * <p>
 * <b>Cost Estimation:</b>
 * <ul>
 * <li><b>Sonnet (claude-3-5-sonnet-20241022):</b> $3/1M input tokens, $15/1M output tokens</li>
 * <li><b>Haiku (claude-3-haiku-20240307):</b> ~10x cheaper than Sonnet (exact pricing varies)</li>
 * <li><b>Embeddings:</b> $3/1M tokens (input-only)</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): $500/month budget enforcement via {@link AiTaggingBudgetService}</li>
 * </ul>
 *
 * @see AiUsageTracking
 * @see AiTaggingBudgetService
 * @see AiCacheConfig
 */
@ApplicationScoped
public class AiUsageTrackingService {

    private static final Logger LOG = Logger.getLogger(AiUsageTrackingService.class);

    private static final String PROVIDER = AiUsageTracking.DEFAULT_PROVIDER;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    AiCacheConfig cacheConfig;

    // Micrometer metrics
    private Counter taggingRequests;
    private Counter embeddingRequests;
    private Counter cacheHits;
    private Counter cacheMisses;

    /**
     * Initializes Micrometer metrics for AI usage tracking.
     *
     * <p>
     * This method is called automatically by CDI after bean construction. It registers custom metrics for:
     * <ul>
     * <li>AI API request counts by service type (tagging, embedding, categorization, fraud)</li>
     * <li>Cache hit/miss counters for cost optimization tracking</li>
     * <li>Budget usage percentage gauge for real-time monitoring</li>
     * </ul>
     */
    @PostConstruct
    public void init() {
        // AI API request counters
        taggingRequests = Counter.builder("ai.api.requests").tag("service", "tagging")
                .description("Total AI tagging API requests").register(meterRegistry);

        embeddingRequests = Counter.builder("ai.api.requests").tag("service", "embedding")
                .description("Total AI embedding API requests").register(meterRegistry);

        // Cache metrics
        cacheHits = Counter.builder("ai.cache.hits").description("Total cache hits for AI results")
                .register(meterRegistry);

        cacheMisses = Counter.builder("ai.cache.misses").description("Total cache misses for AI results")
                .register(meterRegistry);

        // Budget usage gauge
        Gauge.builder("ai.budget.percent_used", this, service -> {
            AiUsageTracking tracking = AiUsageTracking.findOrCreateCurrentMonth(PROVIDER);
            return tracking.getPercentUsed();
        }).description("AI budget usage percentage for current month").register(meterRegistry);

        LOG.info("AI usage tracking metrics initialized");
    }

    /**
     * Records AI API usage for a single request.
     *
     * <p>
     * Delegates to {@link AiUsageTracking#recordUsage} which persists the usage data in a new transaction. Updates
     * Micrometer counters for observability.
     *
     * @param service
     *            service type (tagging, categorization, fraud, embedding)
     * @param model
     *            model name (claude-3-5-sonnet-20241022, claude-3-haiku-20240307, etc.)
     * @param inputTokens
     *            number of input tokens consumed
     * @param outputTokens
     *            number of output tokens consumed
     */
    public void logUsage(String service, String model, int inputTokens, int outputTokens) {
        int costCents = estimateCost(model, inputTokens, outputTokens);

        AiUsageTracking.recordUsage(YearMonth.now(), PROVIDER, 1, inputTokens, outputTokens, costCents);

        // Update Micrometer counters
        if ("tagging".equals(service) || "categorization".equals(service)) {
            taggingRequests.increment();
        } else if ("embedding".equals(service)) {
            embeddingRequests.increment();
        }

        LOG.debugf("Logged AI usage: service=%s, model=%s, inputTokens=%d, outputTokens=%d, costCents=%d", service,
                model, inputTokens, outputTokens, costCents);
    }

    /**
     * Generates usage report for a date range.
     *
     * <p>
     * Aggregates token usage and costs across all AI services. Returns null if no usage found in the specified range.
     *
     * @param startDate
     *            start of date range (inclusive)
     * @param endDate
     *            end of date range (inclusive)
     * @return usage report with totals, or null if no data
     */
    public AiUsageReportType getUsageReport(LocalDate startDate, LocalDate endDate) {
        // Query all months in date range
        YearMonth startMonth = YearMonth.from(startDate);
        YearMonth endMonth = YearMonth.from(endDate);

        long totalRequests = 0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        int totalCostCents = 0;

        // Iterate through months in range
        YearMonth currentMonth = startMonth;
        while (!currentMonth.isAfter(endMonth)) {
            LocalDate monthDate = currentMonth.atDay(1);
            AiUsageTracking tracking = AiUsageTracking.findByMonthAndProvider(monthDate, PROVIDER).orElse(null);

            if (tracking != null) {
                totalRequests += tracking.totalRequests;
                totalInputTokens += tracking.totalTokensInput;
                totalOutputTokens += tracking.totalTokensOutput;
                totalCostCents += tracking.estimatedCostCents;
            }

            currentMonth = currentMonth.plusMonths(1);
        }

        if (totalRequests == 0) {
            LOG.debugf("No AI usage found for date range: %s to %s", startDate, endDate);
            return null;
        }

        return new AiUsageReportType(startDate, endDate, (int) totalRequests, totalInputTokens, totalOutputTokens,
                totalCostCents, AiUsageTracking.DEFAULT_BUDGET_LIMIT_CENTS);
    }

    /**
     * Gets current month's usage tracking record.
     *
     * @return current month usage record (created if not exists)
     */
    public AiUsageTracking getCurrentMonthUsage() {
        return AiUsageTracking.findOrCreateCurrentMonth(PROVIDER);
    }

    /**
     * Estimates cost in cents based on model and token usage.
     *
     * <p>
     * Uses different pricing for Sonnet vs Haiku models. Embedding calls only use input tokens.
     *
     * @param model
     *            model name (claude-3-5-sonnet-20241022, claude-3-haiku-20240307, etc.)
     * @param inputTokens
     *            input tokens consumed
     * @param outputTokens
     *            output tokens consumed
     * @return estimated cost in cents
     */
    public int estimateCost(String model, int inputTokens, int outputTokens) {
        // For embedding models, only count input tokens
        if (model.contains("embedding") || model.contains("embed")) {
            return AiUsageTracking.calculateEmbeddingCostCents(inputTokens);
        }

        // For Haiku model, use ~10x cheaper pricing (rough approximation)
        if (model.contains("haiku")) {
            // Haiku pricing (approximate): $0.25/1M input, $1.25/1M output
            double inputCostCents = inputTokens * 0.000025; // $0.25 per 1M tokens
            double outputCostCents = outputTokens * 0.000125; // $1.25 per 1M tokens
            return (int) Math.ceil(inputCostCents + outputCostCents);
        }

        // Default to Sonnet pricing
        return AiUsageTracking.calculateCostCents(inputTokens, outputTokens);
    }

    /**
     * Gets cache statistics for AI result caching.
     *
     * <p>
     * Reads Micrometer cache metrics to calculate hit rate and other cache performance indicators. Cache hit rate
     * target is &gt;50% per acceptance criteria.
     *
     * @return cache statistics including hit rate, total hits/misses
     */
    public CacheStatsType getCacheStatistics() {
        long hits = (long) cacheHits.count();
        long misses = (long) cacheMisses.count();
        long total = hits + misses;

        double hitRate = total > 0 ? (double) hits / total * 100.0 : 0.0;

        return new CacheStatsType(hits, misses, total, hitRate);
    }

    /**
     * Records cache hit for metrics tracking.
     */
    public void recordCacheHit() {
        cacheHits.increment();
    }

    /**
     * Records cache miss for metrics tracking.
     */
    public void recordCacheMiss() {
        cacheMisses.increment();
    }
}
