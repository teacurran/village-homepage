/*
 * Copyright 2025 VillageCompute Inc.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package villagecompute.homepage.services;

import java.time.YearMonth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.homepage.data.models.AiUsageTracking;

/**
 * Enforces AI budget limits and determines operational mode per P2/P10 policies.
 *
 * <p>
 * This service calculates budget usage percentage and returns appropriate {@link BudgetAction} based on thresholds:
 * <ul>
 * <li><b>NORMAL</b> (&lt;75%): Full-speed operations with normal batch sizes</li>
 * <li><b>REDUCE</b> (75-90%): Reduced batch sizes to conserve budget</li>
 * <li><b>QUEUE</b> (90-100%): Defer operations to next monthly cycle</li>
 * <li><b>HARD_STOP</b> (&gt;100%): Stop all AI operations immediately</li>
 * </ul>
 *
 * <p>
 * Email alerts are sent to operations team when budget crosses 75%, 90%, and 100% thresholds to enable proactive
 * intervention.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2/P10 (AI Budget Control): $500/month ceiling with automatic throttling</li>
 * </ul>
 *
 * @see BudgetAction
 * @see AiUsageTracking
 */
@ApplicationScoped
public class AiTaggingBudgetService {

    private static final Logger LOG = Logger.getLogger(AiTaggingBudgetService.class);

    private static final double REDUCE_THRESHOLD = 75.0;
    private static final double QUEUE_THRESHOLD = 90.0;
    private static final double HARD_STOP_THRESHOLD = 100.0;

    private static final String ALERT_EMAIL = "ops@villagecompute.com";

    // Track last alert sent to avoid spam
    private volatile Double lastAlertPercentage = null;

    @Inject
    EmailNotificationService emailNotificationService;

    /**
     * Determines current budget action based on usage percentage.
     *
     * @return the appropriate budget action for current usage level
     */
    public BudgetAction getCurrentBudgetAction() {
        return getCurrentBudgetAction(AiUsageTracking.DEFAULT_PROVIDER);
    }

    /**
     * Determines current budget action for a specific provider.
     *
     * @param provider
     *            the AI provider name
     * @return the appropriate budget action for current usage level
     */
    public BudgetAction getCurrentBudgetAction(String provider) {
        AiUsageTracking tracking = AiUsageTracking.findOrCreateCurrentMonth(provider);
        double percentUsed = tracking.getPercentUsed();

        BudgetAction action;
        if (percentUsed >= HARD_STOP_THRESHOLD) {
            action = BudgetAction.HARD_STOP;
        } else if (percentUsed >= QUEUE_THRESHOLD) {
            action = BudgetAction.QUEUE;
        } else if (percentUsed >= REDUCE_THRESHOLD) {
            action = BudgetAction.REDUCE;
        } else {
            action = BudgetAction.NORMAL;
        }

        LOG.debugf("Budget action for provider=%s: action=%s, percentUsed=%.2f%%, cost=%d/%d cents", provider, action,
                percentUsed, tracking.estimatedCostCents, tracking.budgetLimitCents);

        // Send alert if crossing threshold
        checkAndSendAlert(percentUsed, tracking);

        return action;
    }

    /**
     * Gets remaining budget in cents for current month.
     *
     * @return remaining budget (may be negative if over budget)
     */
    public int getRemainingBudgetCents() {
        return getRemainingBudgetCents(AiUsageTracking.DEFAULT_PROVIDER);
    }

    /**
     * Gets remaining budget in cents for a specific provider.
     *
     * @param provider
     *            the AI provider name
     * @return remaining budget (may be negative if over budget)
     */
    public int getRemainingBudgetCents(String provider) {
        AiUsageTracking tracking = AiUsageTracking.findOrCreateCurrentMonth(provider);
        return tracking.getRemainingBudgetCents();
    }

    /**
     * Gets current budget usage percentage.
     *
     * @return percentage of budget used (0.0-100.0+)
     */
    public double getBudgetPercentUsed() {
        return getBudgetPercentUsed(AiUsageTracking.DEFAULT_PROVIDER);
    }

    /**
     * Gets budget usage percentage for a specific provider.
     *
     * @param provider
     *            the AI provider name
     * @return percentage of budget used (0.0-100.0+)
     */
    public double getBudgetPercentUsed(String provider) {
        AiUsageTracking tracking = AiUsageTracking.findOrCreateCurrentMonth(provider);
        return tracking.getPercentUsed();
    }

    /**
     * Gets current month usage tracking record.
     *
     * @return the current month's usage record
     */
    public AiUsageTracking getCurrentMonthUsage() {
        return getCurrentMonthUsage(AiUsageTracking.DEFAULT_PROVIDER);
    }

    /**
     * Gets current month usage tracking record for a specific provider.
     *
     * @param provider
     *            the AI provider name
     * @return the current month's usage record
     */
    public AiUsageTracking getCurrentMonthUsage(String provider) {
        return AiUsageTracking.findOrCreateCurrentMonth(provider);
    }

    /**
     * Determines appropriate batch size based on budget action.
     *
     * @param action
     *            the current budget action
     * @return recommended batch size for AI tagging
     */
    public int getBatchSize(BudgetAction action) {
        return switch (action) {
            case NORMAL -> 20; // Full speed
            case REDUCE -> 10; // Reduced speed
            case QUEUE, HARD_STOP -> 0; // Stop processing
        };
    }

    /**
     * Checks if processing should stop based on budget action.
     *
     * @param action
     *            the current budget action
     * @return true if processing should stop, false otherwise
     */
    public boolean shouldStopProcessing(BudgetAction action) {
        return action == BudgetAction.QUEUE || action == BudgetAction.HARD_STOP;
    }

    /**
     * Sends email alert when crossing budget thresholds.
     *
     * <p>
     * Alerts are sent at 75%, 90%, and 100% thresholds. Deduplication logic prevents multiple alerts for the same
     * threshold level.
     *
     * @param percentUsed
     *            current budget usage percentage
     * @param tracking
     *            the usage tracking record
     */
    private void checkAndSendAlert(double percentUsed, AiUsageTracking tracking) {
        // Determine if we crossed a new threshold
        Double alertThreshold = null;

        if (percentUsed >= HARD_STOP_THRESHOLD
                && (lastAlertPercentage == null || lastAlertPercentage < HARD_STOP_THRESHOLD)) {
            alertThreshold = HARD_STOP_THRESHOLD;
        } else if (percentUsed >= QUEUE_THRESHOLD
                && (lastAlertPercentage == null || lastAlertPercentage < QUEUE_THRESHOLD)) {
            alertThreshold = QUEUE_THRESHOLD;
        } else if (percentUsed >= REDUCE_THRESHOLD
                && (lastAlertPercentage == null || lastAlertPercentage < REDUCE_THRESHOLD)) {
            alertThreshold = REDUCE_THRESHOLD;
        }

        if (alertThreshold != null) {
            sendBudgetAlert(percentUsed, tracking, alertThreshold);
            lastAlertPercentage = alertThreshold;
        }
    }

    /**
     * Sends budget alert email to operations team using HTML template.
     *
     * <p>
     * Delegates to EmailNotificationService which handles HTML template rendering, rate limiting, and error handling.
     * Alert level and recommended action are determined based on threshold crossed.
     *
     * @param percentUsed
     *            current budget usage percentage
     * @param tracking
     *            the usage tracking record
     * @param threshold
     *            the threshold that was crossed
     */
    private void sendBudgetAlert(double percentUsed, AiUsageTracking tracking, double threshold) {
        // Determine alert level based on threshold
        String level;
        String action;
        if (threshold >= HARD_STOP_THRESHOLD) {
            level = "EMERGENCY";
            action = "HARD_STOP";
        } else if (threshold >= QUEUE_THRESHOLD) {
            level = "CRITICAL";
            action = "QUEUE";
        } else {
            level = "WARNING";
            action = "REDUCE";
        }

        // Delegate to EmailNotificationService for HTML template rendering and sending
        emailNotificationService.sendAiBudgetAlert(level, percentUsed, tracking.estimatedCostCents,
                tracking.budgetLimitCents, action);

        LOG.infof("Sent AI budget alert: level=%s, threshold=%.0f%%, percentUsed=%.1f%%", level, threshold,
                percentUsed);
    }

    /**
     * Resets alert tracking (typically called at start of new month).
     */
    public void resetAlertTracking() {
        lastAlertPercentage = null;
        LOG.debug("Reset AI budget alert tracking for new month");
    }
}
