/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.services;

/**
 * AI budget enforcement action states per P2/P10 policy thresholds.
 *
 * <p>
 * Budget thresholds trigger different operational modes:
 * <ul>
 * <li>NORMAL (&lt;75%): Full-speed AI tagging with normal batch sizes (10-20 items)</li>
 * <li>REDUCE (75-90%): Reduced batch sizes (5-10 items) to slow spending</li>
 * <li>QUEUE (90-100%): Defer tagging jobs to next monthly cycle</li>
 * <li>HARD_STOP (&gt;100%): Stop all AI operations until budget resets</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): $500/month ceiling with automatic throttling</li>
 * </ul>
 *
 * @see AiTaggingBudgetService
 */
public enum BudgetAction {

    /**
     * Budget usage below 75% - operate at full capacity.
     */
    NORMAL,

    /**
     * Budget usage 75-90% - reduce batch sizes to conserve budget.
     */
    REDUCE,

    /**
     * Budget usage 90-100% - queue jobs for next monthly cycle.
     */
    QUEUE,

    /**
     * Budget exceeded 100% - stop all AI operations immediately.
     */
    HARD_STOP
}
