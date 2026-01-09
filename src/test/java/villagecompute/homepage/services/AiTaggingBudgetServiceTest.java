/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.YearMonth;

import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.homepage.data.models.AiUsageTracking;

/**
 * Tests for AI budget enforcement service.
 *
 * <p>
 * Verifies budget action calculation, threshold transitions, batch size determination, and alert triggering logic per
 * P2/P10 policy requirements.
 */
@QuarkusTest
class AiTaggingBudgetServiceTest {

    @Inject
    AiTaggingBudgetService budgetService;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up any existing tracking records for test isolation
        AiUsageTracking.deleteAll();
        budgetService.resetAlertTracking();
    }

    @Test
    @Transactional
    void testBudgetAction_Normal() {
        // Setup: 50% budget used
        setupUsageTracking(25000, 50000); // $250 / $500

        BudgetAction action = budgetService.getCurrentBudgetAction();

        assertEquals(BudgetAction.NORMAL, action);
        assertEquals(20, budgetService.getBatchSize(action));
        assertFalse(budgetService.shouldStopProcessing(action));
    }

    @Test
    @Transactional
    void testBudgetAction_Reduce_At75Percent() {
        // Setup: exactly 75% budget used
        setupUsageTracking(37500, 50000); // $375 / $500

        BudgetAction action = budgetService.getCurrentBudgetAction();

        assertEquals(BudgetAction.REDUCE, action);
        assertEquals(10, budgetService.getBatchSize(action));
        assertFalse(budgetService.shouldStopProcessing(action));
    }

    @Test
    @Transactional
    void testBudgetAction_Reduce_At89Percent() {
        // Setup: 89% budget used (still REDUCE)
        setupUsageTracking(44500, 50000); // $445 / $500

        BudgetAction action = budgetService.getCurrentBudgetAction();

        assertEquals(BudgetAction.REDUCE, action);
        assertEquals(10, budgetService.getBatchSize(action));
        assertFalse(budgetService.shouldStopProcessing(action));
    }

    @Test
    @Transactional
    void testBudgetAction_Queue_At90Percent() {
        // Setup: exactly 90% budget used
        setupUsageTracking(45000, 50000); // $450 / $500

        BudgetAction action = budgetService.getCurrentBudgetAction();

        assertEquals(BudgetAction.QUEUE, action);
        assertEquals(0, budgetService.getBatchSize(action));
        assertTrue(budgetService.shouldStopProcessing(action));
    }

    @Test
    @Transactional
    void testBudgetAction_Queue_At99Percent() {
        // Setup: 99% budget used (still QUEUE)
        setupUsageTracking(49500, 50000); // $495 / $500

        BudgetAction action = budgetService.getCurrentBudgetAction();

        assertEquals(BudgetAction.QUEUE, action);
        assertEquals(0, budgetService.getBatchSize(action));
        assertTrue(budgetService.shouldStopProcessing(action));
    }

    @Test
    @Transactional
    void testBudgetAction_HardStop_At100Percent() {
        // Setup: exactly 100% budget used
        setupUsageTracking(50000, 50000); // $500 / $500

        BudgetAction action = budgetService.getCurrentBudgetAction();

        assertEquals(BudgetAction.HARD_STOP, action);
        assertEquals(0, budgetService.getBatchSize(action));
        assertTrue(budgetService.shouldStopProcessing(action));
    }

    @Test
    @Transactional
    void testBudgetAction_HardStop_Over100Percent() {
        // Setup: 110% budget used
        setupUsageTracking(55000, 50000); // $550 / $500

        BudgetAction action = budgetService.getCurrentBudgetAction();

        assertEquals(BudgetAction.HARD_STOP, action);
        assertEquals(0, budgetService.getBatchSize(action));
        assertTrue(budgetService.shouldStopProcessing(action));
    }

    @Test
    @Transactional
    void testGetRemainingBudget() {
        setupUsageTracking(12345, 50000); // $123.45 / $500

        int remaining = budgetService.getRemainingBudgetCents();

        assertEquals(37655, remaining); // $376.55 remaining
    }

    @Test
    @Transactional
    void testGetRemainingBudget_Negative() {
        setupUsageTracking(55000, 50000); // $550 / $500

        int remaining = budgetService.getRemainingBudgetCents();

        assertEquals(-5000, remaining); // -$50 (over budget)
    }

    @Test
    @Transactional
    void testGetBudgetPercentUsed() {
        setupUsageTracking(12345, 50000); // $123.45 / $500

        double percentUsed = budgetService.getBudgetPercentUsed();

        assertEquals(24.69, percentUsed, 0.01); // 24.69%
    }

    @Test
    @Transactional
    void testGetCurrentMonthUsage() {
        setupUsageTracking(12345, 50000);

        AiUsageTracking usage = budgetService.getCurrentMonthUsage();

        assertNotNull(usage);
        assertEquals(12345, usage.estimatedCostCents);
        assertEquals(50000, usage.budgetLimitCents);
        assertEquals(AiUsageTracking.DEFAULT_PROVIDER, usage.provider);
    }

    @Test
    @Transactional
    void testThresholdTransition_NormalToReduce() {
        // Start at 50% (NORMAL)
        setupUsageTracking(25000, 50000);
        assertEquals(BudgetAction.NORMAL, budgetService.getCurrentBudgetAction());

        // Increment to 75% (REDUCE)
        incrementUsage(12500);
        assertEquals(BudgetAction.REDUCE, budgetService.getCurrentBudgetAction());
    }

    @Test
    @Transactional
    void testThresholdTransition_ReduceToQueue() {
        // Start at 80% (REDUCE)
        setupUsageTracking(40000, 50000);
        assertEquals(BudgetAction.REDUCE, budgetService.getCurrentBudgetAction());

        // Increment to 90% (QUEUE)
        incrementUsage(5000);
        assertEquals(BudgetAction.QUEUE, budgetService.getCurrentBudgetAction());
    }

    @Test
    @Transactional
    void testThresholdTransition_QueueToHardStop() {
        // Start at 95% (QUEUE)
        setupUsageTracking(47500, 50000);
        assertEquals(BudgetAction.QUEUE, budgetService.getCurrentBudgetAction());

        // Increment to 100% (HARD_STOP)
        incrementUsage(2500);
        assertEquals(BudgetAction.HARD_STOP, budgetService.getCurrentBudgetAction());
    }

    @Test
    @Transactional
    void testBatchSizeCalculation() {
        assertEquals(20, budgetService.getBatchSize(BudgetAction.NORMAL));
        assertEquals(10, budgetService.getBatchSize(BudgetAction.REDUCE));
        assertEquals(0, budgetService.getBatchSize(BudgetAction.QUEUE));
        assertEquals(0, budgetService.getBatchSize(BudgetAction.HARD_STOP));
    }

    @Test
    @Transactional
    void testShouldStopProcessing() {
        assertFalse(budgetService.shouldStopProcessing(BudgetAction.NORMAL));
        assertFalse(budgetService.shouldStopProcessing(BudgetAction.REDUCE));
        assertTrue(budgetService.shouldStopProcessing(BudgetAction.QUEUE));
        assertTrue(budgetService.shouldStopProcessing(BudgetAction.HARD_STOP));
    }

    @Test
    @Transactional
    void testZeroBudgetLimit() {
        // Edge case: budget limit is 0
        setupUsageTracking(0, 0);

        // Should handle gracefully (treat as NORMAL since no budget tracking)
        BudgetAction action = budgetService.getCurrentBudgetAction();
        assertNotNull(action);
    }

    /**
     * Helper: creates or updates usage tracking record with specified costs.
     */
    private void setupUsageTracking(int costCents, int limitCents) {
        LocalDate currentMonth = YearMonth.now().atDay(1);

        AiUsageTracking tracking = AiUsageTracking
                .findByMonthAndProvider(currentMonth, AiUsageTracking.DEFAULT_PROVIDER).orElseGet(() -> {
                    AiUsageTracking newTracking = new AiUsageTracking();
                    newTracking.month = currentMonth;
                    newTracking.provider = AiUsageTracking.DEFAULT_PROVIDER;
                    return newTracking;
                });

        tracking.estimatedCostCents = costCents;
        tracking.budgetLimitCents = limitCents;
        tracking.persist();
    }

    /**
     * Helper: increments usage by specified cost amount.
     */
    private void incrementUsage(int additionalCostCents) {
        LocalDate currentMonth = YearMonth.now().atDay(1);

        AiUsageTracking tracking = AiUsageTracking
                .findByMonthAndProvider(currentMonth, AiUsageTracking.DEFAULT_PROVIDER).orElseThrow();

        tracking.estimatedCostCents += additionalCostCents;
        tracking.persist();
    }
}
