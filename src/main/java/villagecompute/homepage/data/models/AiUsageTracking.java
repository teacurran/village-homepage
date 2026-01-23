/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.data.models;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Parameters;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

import org.jboss.logging.Logger;

/**
 * Tracks AI API usage and enforces budget limits per P2/P10 policies.
 *
 * <p>
 * This entity records monthly token consumption, API request counts, and estimated costs for AI providers (initially
 * Anthropic Claude Sonnet 4). Budget enforcement occurs at four threshold levels: NORMAL (&lt;75%), REDUCE (75-90%),
 * QUEUE (90-100%), and HARD_STOP (&gt;100%).
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): $500/month ceiling with automatic throttling and alerts</li>
 * </ul>
 *
 * <p>
 * <b>Database Access Pattern:</b> All queries use static methods following Panache ActiveRecord pattern. Named queries
 * defined at class level for performance and maintainability.
 *
 * @see villagecompute.homepage.services.AiTaggingBudgetService
 * @see villagecompute.homepage.services.BudgetAction
 */
@Entity
@Table(
        name = "ai_usage_tracking")
@NamedQuery(
        name = AiUsageTracking.QUERY_FIND_BY_MONTH_AND_PROVIDER,
        query = "FROM AiUsageTracking WHERE month = :month AND provider = :provider")
@NamedQuery(
        name = AiUsageTracking.QUERY_FIND_CURRENT_MONTH,
        query = "FROM AiUsageTracking WHERE month = :month AND provider = :provider")
@NamedQuery(
        name = AiUsageTracking.QUERY_FIND_LAST_N_MONTHS,
        query = "FROM AiUsageTracking WHERE provider = :provider AND month >= :sinceMonth ORDER BY month DESC")
public class AiUsageTracking extends PanacheEntityBase {

    private static final Logger LOG = Logger.getLogger(AiUsageTracking.class);

    public static final String QUERY_FIND_BY_MONTH_AND_PROVIDER = "AiUsageTracking.findByMonthAndProvider";
    public static final String QUERY_FIND_CURRENT_MONTH = "AiUsageTracking.findCurrentMonth";
    public static final String QUERY_FIND_LAST_N_MONTHS = "AiUsageTracking.findLastNMonths";

    public static final String DEFAULT_PROVIDER = "anthropic";
    public static final int DEFAULT_BUDGET_LIMIT_CENTS = 50000; // $500

    // Claude Sonnet 4 pricing (as of 2025-01-09)
    public static final double INPUT_TOKEN_COST_CENTS = 0.0003; // $3 per 1M tokens = $0.000003 per token
    public static final double OUTPUT_TOKEN_COST_CENTS = 0.0015; // $15 per 1M tokens = $0.000015 per token

    // Anthropic Embedding pricing (as of 2025-01-23)
    // Note: Using same rate as input tokens since embeddings are input-only operations
    public static final double EMBEDDING_TOKEN_COST_CENTS = 0.0003; // $3 per 1M tokens = $0.000003 per token

    @Id
    @GeneratedValue
    public UUID id;

    @Column(
            name = "\"month\"",
            nullable = false)
    public LocalDate month;

    @Column(
            nullable = false)
    public String provider = DEFAULT_PROVIDER;

    @Column(
            nullable = false)
    public int totalRequests = 0;

    @Column(
            nullable = false)
    public long totalTokensInput = 0L;

    @Column(
            nullable = false)
    public long totalTokensOutput = 0L;

    @Column(
            nullable = false)
    public int estimatedCostCents = 0;

    @Column(
            nullable = false)
    public int budgetLimitCents = DEFAULT_BUDGET_LIMIT_CENTS;

    @Column(
            nullable = false)
    public ZonedDateTime updatedAt = ZonedDateTime.now();

    /**
     * Finds usage tracking record for a specific month and provider.
     *
     * @param month
     *            the first day of the month
     * @param provider
     *            the AI provider name (e.g., "anthropic")
     * @return Optional containing the usage record if found
     */
    public static Optional<AiUsageTracking> findByMonthAndProvider(LocalDate month, String provider) {
        return find("#" + QUERY_FIND_BY_MONTH_AND_PROVIDER, Parameters.with("month", month).and("provider", provider))
                .firstResultOptional();
    }

    /**
     * Finds or creates usage tracking record for the current month.
     *
     * @param provider
     *            the AI provider name
     * @return the current month's usage record
     */
    public static AiUsageTracking findOrCreateCurrentMonth(String provider) {
        LocalDate currentMonth = YearMonth.now().atDay(1);
        return findByMonthAndProvider(currentMonth, provider).orElseGet(() -> {
            AiUsageTracking tracking = new AiUsageTracking();
            tracking.month = currentMonth;
            tracking.provider = provider;
            tracking.persist();
            LOG.infof("Created new AI usage tracking record: month=%s, provider=%s", currentMonth, provider);
            return tracking;
        });
    }

    /**
     * Records API usage and updates cost estimates.
     *
     * <p>
     * This method increments request count, token usage, and recalculates estimated cost based on token consumption. It
     * uses a new transaction to ensure usage is persisted even if the calling transaction fails.
     *
     * @param month
     *            the usage month (first day of month)
     * @param provider
     *            the AI provider name
     * @param requests
     *            number of API requests to add
     * @param inputTokens
     *            input tokens consumed
     * @param outputTokens
     *            output tokens consumed
     * @param costCents
     *            estimated cost in cents
     */
    public static void recordUsage(YearMonth month, String provider, int requests, long inputTokens, long outputTokens,
            int costCents) {
        QuarkusTransaction.requiringNew().run(() -> {
            LocalDate monthDate = month.atDay(1);
            AiUsageTracking tracking = findByMonthAndProvider(monthDate, provider).orElseGet(() -> {
                AiUsageTracking newTracking = new AiUsageTracking();
                newTracking.month = monthDate;
                newTracking.provider = provider;
                return newTracking;
            });

            tracking.totalRequests += requests;
            tracking.totalTokensInput += inputTokens;
            tracking.totalTokensOutput += outputTokens;
            tracking.estimatedCostCents += costCents;
            tracking.updatedAt = ZonedDateTime.now();
            tracking.persist();

            LOG.debugf(
                    "Recorded AI usage: provider=%s, requests=%d, inputTokens=%d, outputTokens=%d, costCents=%d, "
                            + "totalCost=%d/%d cents",
                    provider, requests, inputTokens, outputTokens, costCents, tracking.estimatedCostCents,
                    tracking.budgetLimitCents);
        });
    }

    /**
     * Calculates estimated cost in cents based on token usage.
     *
     * @param inputTokens
     *            number of input tokens
     * @param outputTokens
     *            number of output tokens
     * @return estimated cost in cents (rounded up)
     */
    public static int calculateCostCents(long inputTokens, long outputTokens) {
        double cost = (inputTokens * INPUT_TOKEN_COST_CENTS) + (outputTokens * OUTPUT_TOKEN_COST_CENTS);
        return (int) Math.ceil(cost);
    }

    /**
     * Calculates estimated cost in cents for embedding API calls.
     *
     * <p>
     * Embedding API calls only consume input tokens (no output tokens generated). Used by
     * {@link villagecompute.homepage.services.SemanticSearchService} to track embedding generation costs.
     *
     * @param inputTokens
     *            number of input tokens
     * @return estimated cost in cents (rounded up)
     */
    public static int calculateEmbeddingCostCents(long inputTokens) {
        double cost = inputTokens * EMBEDDING_TOKEN_COST_CENTS;
        return (int) Math.ceil(cost);
    }

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
     * Calculates remaining budget in cents.
     *
     * @return remaining budget (may be negative if over budget)
     */
    public int getRemainingBudgetCents() {
        return budgetLimitCents - estimatedCostCents;
    }
}
