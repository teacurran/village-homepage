/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.api.types;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotNull;

import villagecompute.homepage.data.models.AiUsageTracking;
import villagecompute.homepage.services.BudgetAction;

/**
 * API type representing AI usage tracking data for admin monitoring.
 *
 * <p>
 * Exposes monthly token consumption, costs, budget limits, and current enforcement action to operations dashboards per
 * P2/P10 policy requirements.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): Budget visibility for ops team intervention</li>
 * <li>P8 (Admin Access): Secured via @RolesAllowed on admin endpoints</li>
 * </ul>
 *
 * @param id
 *            unique identifier for tracking record
 * @param month
 *            first day of the month for this usage period
 * @param provider
 *            AI provider name (e.g., "anthropic")
 * @param totalRequests
 *            total number of API requests made
 * @param totalTokensInput
 *            total input tokens consumed
 * @param totalTokensOutput
 *            total output tokens consumed
 * @param estimatedCostCents
 *            estimated cost in cents based on token usage
 * @param budgetLimitCents
 *            monthly budget limit in cents
 * @param budgetAction
 *            current budget enforcement action (NORMAL/REDUCE/QUEUE/HARD_STOP)
 * @param percentUsed
 *            percentage of budget consumed (0.0-100.0+)
 * @param remainingCents
 *            remaining budget in cents (may be negative)
 * @param updatedAt
 *            timestamp of last update
 */
public record AiUsageType(@NotNull UUID id, @NotNull LocalDate month, @NotNull String provider,
        @NotNull Integer totalRequests, @NotNull Long totalTokensInput, @NotNull Long totalTokensOutput,
        @NotNull Integer estimatedCostCents, @NotNull Integer budgetLimitCents, @NotNull BudgetAction budgetAction,
        @NotNull Double percentUsed, @NotNull Integer remainingCents, @NotNull ZonedDateTime updatedAt) {

    /**
     * Creates API type from entity with budget action calculation.
     *
     * @param tracking
     *            the usage tracking entity
     * @param budgetAction
     *            the calculated budget action
     * @return API type instance
     */
    public static AiUsageType fromEntity(AiUsageTracking tracking, BudgetAction budgetAction) {
        return new AiUsageType(tracking.id, tracking.month, tracking.provider, tracking.totalRequests,
                tracking.totalTokensInput, tracking.totalTokensOutput, tracking.estimatedCostCents,
                tracking.budgetLimitCents, budgetAction, tracking.getPercentUsed(), tracking.getRemainingBudgetCents(),
                tracking.updatedAt);
    }

    /**
     * Formats cost in dollars for display.
     *
     * @return formatted cost string (e.g., "$12.34")
     */
    @JsonProperty("estimatedCostFormatted")
    public String getEstimatedCostFormatted() {
        return String.format("$%.2f", estimatedCostCents / 100.0);
    }

    /**
     * Formats budget limit in dollars for display.
     *
     * @return formatted budget string (e.g., "$500.00")
     */
    @JsonProperty("budgetLimitFormatted")
    public String getBudgetLimitFormatted() {
        return String.format("$%.2f", budgetLimitCents / 100.0);
    }

    /**
     * Formats remaining budget in dollars for display.
     *
     * @return formatted remaining budget string (e.g., "$487.66" or "-$12.34")
     */
    @JsonProperty("remainingFormatted")
    public String getRemainingFormatted() {
        return String.format("$%.2f", remainingCents / 100.0);
    }

    /**
     * Formats percentage used for display.
     *
     * @return formatted percentage string (e.g., "2.47%")
     */
    @JsonProperty("percentUsedFormatted")
    public String getPercentUsedFormatted() {
        return String.format("%.2f%%", percentUsed);
    }
}
