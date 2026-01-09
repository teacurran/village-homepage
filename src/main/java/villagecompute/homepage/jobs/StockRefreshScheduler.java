package villagecompute.homepage.jobs;

import java.util.Map;

import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import villagecompute.homepage.services.DelayedJobService;
import villagecompute.homepage.services.StockService;

/**
 * Scheduler for stock market refresh jobs with market hours awareness.
 *
 * <p>
 * Schedules stock refresh jobs at different intervals based on market status:
 * <ul>
 * <li>Market open (Mon-Fri 9:30 AM - 4:00 PM ET): Every 5 minutes</li>
 * <li>After hours (Mon-Fri outside market hours): Every 1 hour</li>
 * <li>Weekends (Sat-Sun): Every 6 hours</li>
 * </ul>
 *
 * <p>
 * The scheduler checks market status before enqueuing jobs to avoid unnecessary API calls when the market is closed.
 */
@ApplicationScoped
public class StockRefreshScheduler {

    private static final Logger LOG = Logger.getLogger(StockRefreshScheduler.class);

    @Inject
    DelayedJobService jobService;

    @Inject
    StockService stockService;

    /**
     * Check every 5 minutes and enqueue refresh job only if market is open.
     *
     * <p>
     * During market hours (9:30 AM - 4:00 PM ET, Mon-Fri), stock prices change frequently so we refresh every 5
     * minutes. Outside market hours, this scheduler does nothing.
     */
    @Scheduled(
            every = "5m")
    void scheduleMarketHoursRefresh() {
        if (stockService.isMarketOpen()) {
            LOG.debug("Market is open, enqueuing stock refresh job");
            jobService.enqueue(JobType.STOCK_REFRESH, Map.of());
        } else {
            LOG.trace("Market is closed, skipping market hours refresh");
        }
    }

    /**
     * Refresh every hour on weekdays (Mon-Fri).
     *
     * <p>
     * This covers after-hours trading and pre-market activity. Only enqueues if market is closed (to avoid duplicate
     * jobs with market hours scheduler).
     */
    @Scheduled(
            cron = "0 0 * * * ?",
            identity = "after-hours-refresh")
    void scheduleAfterHoursRefresh() {
        if (!stockService.isMarketOpen()) {
            LOG.debug("After hours on weekday, enqueuing stock refresh job");
            jobService.enqueue(JobType.STOCK_REFRESH, Map.of());
        } else {
            LOG.trace("Market is open, skipping after hours refresh");
        }
    }

    /**
     * Refresh every 6 hours on weekends (Sat-Sun).
     *
     * <p>
     * Stock prices don't change on weekends, so we refresh infrequently to keep cache warm for Monday morning.
     */
    @Scheduled(
            cron = "0 0/6 * * * ?",
            identity = "weekend-refresh")
    void scheduleWeekendRefresh() {
        LOG.debug("Weekend refresh, enqueuing stock refresh job");
        jobService.enqueue(JobType.STOCK_REFRESH, Map.of());
    }

    /**
     * Clean up expired stock quotes daily at 2 AM ET.
     *
     * <p>
     * Removes stale cache entries to prevent database bloat.
     */
    @Scheduled(
            cron = "0 2 * * * ?",
            timeZone = "America/New_York")
    void cleanupExpiredQuotes() {
        LOG.debug("Running daily cleanup of expired stock quotes");
        // This will be handled by a scheduled task in StockQuote entity
        long deleted = villagecompute.homepage.data.models.StockQuote.deleteExpired();
        LOG.infof("Deleted %d expired stock quotes", deleted);
    }
}
