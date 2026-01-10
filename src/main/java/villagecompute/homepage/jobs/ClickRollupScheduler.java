package villagecompute.homepage.jobs;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.services.DelayedJobService;

import java.time.LocalDate;
import java.util.Map;

/**
 * Scheduler for hourly click tracking rollup aggregation (Policy F14.9).
 *
 * <p>
 * Enqueues CLICK_ROLLUP jobs on an hourly cadence to aggregate raw click events from partitioned {@code link_clicks}
 * table into rollup tables for efficient dashboard queries. Runs at the top of every hour to ensure fresh analytics
 * data.
 *
 * <p>
 * <b>Schedule:</b> Hourly at top of the hour (cron: 0 0 * * * ?)
 *
 * <p>
 * <b>Queue:</b> LOW (analytics jobs don't require high priority)
 *
 * <p>
 * <b>Expected Duration:</b> ~10-30 seconds depending on click volume
 *
 * <p>
 * <b>Job Payload:</b> Includes yesterday's date as rollup target
 *
 * <p>
 * <b>Rollup Strategy:</b>
 * <ul>
 * <li>Runs hourly to keep analytics near real-time (within 1 hour delay)</li>
 * <li>Aggregates previous day's clicks (allows late-arriving events)</li>
 * <li>Uses ON CONFLICT DO UPDATE for idempotency (safe to re-run)</li>
 * <li>Raw clicks retained for 90 days, rollups retained indefinitely</li>
 * </ul>
 *
 * @see ClickRollupJobHandler
 * @see JobType#CLICK_ROLLUP
 */
@ApplicationScoped
public class ClickRollupScheduler {

    private static final Logger LOG = Logger.getLogger(ClickRollupScheduler.class);

    @Inject
    DelayedJobService jobService;

    /**
     * Schedules hourly click rollup job.
     *
     * <p>
     * Runs at the top of every hour (0 minutes past the hour). Enqueues a job that aggregates yesterday's click events
     * into rollup tables for analytics dashboards.
     */
    @Scheduled(
            cron = "0 0 * * * ?")
    void scheduleClickRollup() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Map<String, Object> payload = Map.of("rollup_date", yesterday.toString());
        jobService.enqueue(JobType.CLICK_ROLLUP, payload);
        LOG.infof("Scheduled hourly click rollup job for date: %s", yesterday);
    }
}
