package villagecompute.homepage.jobs;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.services.DelayedJobService;

import java.util.Map;

/**
 * Scheduler for hourly rank recalculation in Good Sites directory.
 *
 * <p>
 * Enqueues RANK_RECALCULATION jobs on an hourly cadence to update site rankings within categories. Runs at the top of
 * every hour to ensure fresh ranking data for bubbling logic and category browsing.
 *
 * <p>
 * <b>Schedule:</b> Hourly at top of the hour (cron: 0 0 * * * ?)
 *
 * <p>
 * <b>Queue:</b> DEFAULT (standard priority background job)
 *
 * <p>
 * <b>Expected Duration:</b> ~5 seconds for 1000 categories with 50 sites each
 *
 * <p>
 * <b>Job Payload:</b> Empty map (no parameters required)
 *
 * @see RankRecalculationJobHandler
 * @see JobType#RANK_RECALCULATION
 */
@ApplicationScoped
public class RankRecalculationScheduler {

    private static final Logger LOG = Logger.getLogger(RankRecalculationScheduler.class);

    @Inject
    DelayedJobService jobService;

    /**
     * Schedules hourly rank recalculation job.
     *
     * <p>
     * Runs at the top of every hour (0 minutes past the hour). Enqueues a single job that processes all active
     * categories and updates site rankings.
     */
    @Scheduled(
            cron = "0 0 * * * ?")
    void scheduleRankRecalculation() {
        Map<String, Object> payload = Map.of();
        jobService.enqueue(JobType.RANK_RECALCULATION, payload);
        LOG.info("Scheduled hourly rank recalculation job");
    }
}
