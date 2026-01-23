/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.api.types;

import java.time.LocalDate;

/**
 * AI usage report for a date range (Feature I4.T6).
 *
 * <p>
 * Aggregates token usage, request counts, and costs across all AI services for budget tracking and cost optimization.
 *
 * @param startDate
 *            start of report period (inclusive)
 * @param endDate
 *            end of report period (inclusive)
 * @param totalRequests
 *            total AI API requests in period
 * @param totalInputTokens
 *            total input tokens consumed
 * @param totalOutputTokens
 *            total output tokens consumed
 * @param estimatedCostCents
 *            estimated cost in cents
 * @param budgetLimitCents
 *            budget limit in cents (for percentage calculation)
 */
public record AiUsageReportType(LocalDate startDate, LocalDate endDate, int totalRequests, long totalInputTokens,
        long totalOutputTokens, int estimatedCostCents, int budgetLimitCents) {

    /**
     * Calculates budget usage percentage.
     *
     * @return percentage of budget used (0.0-100.0+)
     */
    public double getPercentUsed() {
        if (budgetLimitCents == 0) {
            return 0.0;
        }
        return (double) estimatedCostCents / budgetLimitCents * 100.0;
    }

    /**
     * Gets estimated cost in dollars.
     *
     * @return cost in dollars
     */
    public double getCostDollars() {
        return estimatedCostCents / 100.0;
    }
}
