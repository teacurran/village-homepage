/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.homepage.api.types.AiUsageReportType;
import villagecompute.homepage.api.types.CacheStatsType;
import villagecompute.homepage.data.models.AiUsageTracking;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Test suite for {@link AiUsageTrackingService}.
 *
 * <p>
 * Tests usage logging, cost estimation, cache statistics, and report generation.
 */
@QuarkusTest
class AiUsageTrackingServiceTest {

    @Inject
    AiUsageTrackingService usageTrackingService;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing usage records
        AiUsageTracking.deleteAll();
    }

    @Test
    @Transactional
    void testLogUsage_taggingService() {
        usageTrackingService.logUsage("tagging", "claude-3-haiku-20240307", 1000, 500);

        // Verify usage was recorded
        AiUsageTracking tracking = usageTrackingService.getCurrentMonthUsage();
        assertNotNull(tracking);
        assertTrue(tracking.totalRequests > 0);
        assertTrue(tracking.totalTokensInput >= 1000);
        assertTrue(tracking.totalTokensOutput >= 500);
        assertTrue(tracking.estimatedCostCents > 0);
    }

    @Test
    @Transactional
    void testLogUsage_embeddingService() {
        usageTrackingService.logUsage("embedding", "text-embedding-3-small", 500, 0);

        // Verify usage was recorded
        AiUsageTracking tracking = usageTrackingService.getCurrentMonthUsage();
        assertNotNull(tracking);
        assertTrue(tracking.totalRequests > 0);
        assertTrue(tracking.totalTokensInput >= 500);
        assertEquals(0, tracking.totalTokensOutput, "Embedding calls have no output tokens");
    }

    @Test
    void testEstimateCost_sonnetModel() {
        int cost = usageTrackingService.estimateCost("claude-3-5-sonnet-20241022", 1000, 500);

        // Sonnet pricing: $3/1M input, $15/1M output
        // Expected: (1000 * 0.0003) + (500 * 0.0015) = 0.3 + 0.75 = 1.05 cents
        assertTrue(cost > 0, "Cost should be greater than 0");
        assertTrue(cost >= 1, "Cost should be at least 1 cent for 1000 input + 500 output tokens");
    }

    @Test
    void testEstimateCost_haikuModel() {
        int cost = usageTrackingService.estimateCost("claude-3-haiku-20240307", 1000, 500);

        // Haiku pricing is ~10x cheaper than Sonnet
        // Expected: significantly less than Sonnet cost
        assertTrue(cost > 0, "Cost should be greater than 0");

        int sonnetCost = usageTrackingService.estimateCost("claude-3-5-sonnet-20241022", 1000, 500);
        assertTrue(cost < sonnetCost, "Haiku should be cheaper than Sonnet");
    }

    @Test
    void testEstimateCost_embeddingModel() {
        int cost = usageTrackingService.estimateCost("text-embedding-3-small", 1000, 0);

        // Embedding pricing: $3/1M tokens (input only)
        // Expected: 1000 * 0.0003 = 0.3 cents
        assertTrue(cost > 0, "Cost should be greater than 0");
    }

    @Test
    @Transactional
    void testGetUsageReport_singleMonth() {
        // Log some usage
        usageTrackingService.logUsage("tagging", "claude-3-haiku-20240307", 10000, 5000);
        usageTrackingService.logUsage("embedding", "text-embedding-3-small", 5000, 0);

        // Generate report for current month
        LocalDate today = LocalDate.now();
        AiUsageReportType report = usageTrackingService.getUsageReport(today.withDayOfMonth(1), today);

        assertNotNull(report);
        assertTrue(report.totalRequests() >= 2, "Should have at least 2 requests");
        assertTrue(report.totalInputTokens() >= 15000, "Should have at least 15000 input tokens");
        assertTrue(report.totalOutputTokens() >= 5000, "Should have at least 5000 output tokens");
        assertTrue(report.estimatedCostCents() > 0, "Should have non-zero cost");
        assertEquals(AiUsageTracking.DEFAULT_BUDGET_LIMIT_CENTS, report.budgetLimitCents());
    }

    @Test
    @Transactional
    void testGetCurrentMonthUsage() {
        // Log some usage
        usageTrackingService.logUsage("tagging", "claude-3-haiku-20240307", 1000, 500);

        AiUsageTracking tracking = usageTrackingService.getCurrentMonthUsage();

        assertNotNull(tracking);
        assertEquals(YearMonth.now().atDay(1), tracking.month);
        assertEquals(AiUsageTracking.DEFAULT_PROVIDER, tracking.provider);
        assertTrue(tracking.totalRequests > 0);
    }

    @Test
    void testGetCacheStatistics() {
        // Record some cache hits and misses
        usageTrackingService.recordCacheHit();
        usageTrackingService.recordCacheHit();
        usageTrackingService.recordCacheHit();
        usageTrackingService.recordCacheMiss();
        usageTrackingService.recordCacheMiss();

        CacheStatsType stats = usageTrackingService.getCacheStatistics();

        assertNotNull(stats);
        assertTrue(stats.hits() >= 3, "Should have at least 3 cache hits");
        assertTrue(stats.misses() >= 2, "Should have at least 2 cache misses");
        assertTrue(stats.total() >= 5, "Should have at least 5 total cache requests");
        assertTrue(stats.hitRate() > 0, "Should have non-zero hit rate");
    }

    @Test
    void testCacheHitRate_meetsTarget() {
        // Record 60% hit rate (exceeds 50% target)
        for (int i = 0; i < 6; i++) {
            usageTrackingService.recordCacheHit();
        }
        for (int i = 0; i < 4; i++) {
            usageTrackingService.recordCacheMiss();
        }

        CacheStatsType stats = usageTrackingService.getCacheStatistics();

        assertTrue(stats.meetsTarget(), "Cache hit rate should meet 50% target");
        assertTrue(stats.hitRate() >= 50.0, "Hit rate should be at least 50%");
    }
}
