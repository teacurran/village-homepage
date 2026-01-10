package villagecompute.homepage.jobs;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.services.DelayedJobService;

import java.util.Map;

/**
 * Scheduler for weekly link health checks in Good Sites directory.
 *
 * <p>
 * Enqueues LINK_HEALTH_CHECK jobs on a weekly cadence to detect dead links. Runs every Sunday at 3am UTC to minimize
 * user impact during low-traffic periods.
 *
 * <p>
 * <b>Schedule:</b> Weekly on Sunday at 3am UTC (cron: 0 0 3 ? * SUN)
 *
 * <p>
 * <b>Queue:</b> LOW (non-critical background maintenance)
 *
 * <p>
 * <b>Expected Duration:</b> 10-30 minutes for 10,000 sites (HTTP timeouts dominate runtime)
 *
 * <p>
 * <b>Job Payload:</b> Empty map (no parameters required)
 *
 * @see LinkHealthCheckJobHandler
 * @see JobType#LINK_HEALTH_CHECK
 */
@ApplicationScoped
public class LinkHealthCheckScheduler {

    private static final Logger LOG = Logger.getLogger(LinkHealthCheckScheduler.class);

    @Inject
    DelayedJobService jobService;

    /**
     * Schedules weekly link health check job.
     *
     * <p>
     * Runs every Sunday at 3am UTC. Enqueues a single job that processes all approved sites in the directory.
     */
    @Scheduled(
            cron = "0 0 3 ? * SUN")
    void scheduleLinkHealthCheck() {
        Map<String, Object> payload = Map.of();
        jobService.enqueue(JobType.LINK_HEALTH_CHECK, payload);
        LOG.info("Scheduled weekly link health check job");
    }
}
