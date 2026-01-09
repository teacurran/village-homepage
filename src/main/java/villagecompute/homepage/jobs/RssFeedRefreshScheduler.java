package villagecompute.homepage.jobs;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Scheduler for RSS feed refresh jobs.
 *
 * <p>
 * Runs every 5 minutes to trigger refresh of RSS sources that are due for update based on their configured
 * {@code refresh_interval_minutes}. The scheduler queries
 * {@link villagecompute.homepage.data.models.RssSource#findDueForRefresh()} to determine which feeds are ready,
 * respecting per-feed intervals (15min-6hrs).
 *
 * <p>
 * <b>Execution Cadence:</b> 5-minute intervals ensure high-frequency feeds (e.g., breaking news at 15-minute intervals)
 * are refreshed promptly without over-fetching lower-frequency feeds.
 *
 * <p>
 * <b>Job Payload:</b> The scheduler invokes {@link RssFeedRefreshJobHandler} with an empty payload, causing it to
 * refresh ALL due feeds in a single job execution. For manual admin refresh or targeted refresh, use the Admin API to
 * enqueue a job with specific {@code source_id} parameter.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P2: Feed Governance - respects configured refresh intervals (15min-6hrs)</li>
 * <li>P7: Unified job orchestration via {@link DelayedJobService}</li>
 * </ul>
 *
 * @see RssFeedRefreshJobHandler for handler implementation
 * @see villagecompute.homepage.data.models.RssSource for source entity
 */
@ApplicationScoped
public class RssFeedRefreshScheduler {

    private static final Logger LOG = Logger.getLogger(RssFeedRefreshScheduler.class);

    @Inject
    RssFeedRefreshJobHandler handler;

    /**
     * Scheduled task to refresh RSS feeds every 5 minutes.
     *
     * <p>
     * The handler internally queries {@code RssSource.findDueForRefresh()} to determine which sources are ready based
     * on {@code last_fetched_at} and {@code refresh_interval_minutes}.
     */
    @Scheduled(
            every = "5m",
            identity = "rss-feed-refresh")
    void refreshFeeds() {
        LOG.debugf("RSS feed refresh scheduler triggered");

        try {
            // Invoke handler directly with empty payload (handler will query for due feeds)
            Map<String, Object> payload = new HashMap<>();
            handler.execute(System.currentTimeMillis(), payload);

        } catch (Exception e) {
            LOG.errorf(e, "RSS feed refresh scheduler failed: %s", e.getMessage());
            // Swallow exception to prevent scheduler from being disabled
        }
    }
}
