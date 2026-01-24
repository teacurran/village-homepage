package villagecompute.homepage.jobs;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.services.DelayedJobService;

import java.util.Map;

/**
 * Scheduler for daily sitemap generation at 4am UTC.
 * <p>
 * Enqueues SITEMAP_GENERATION job daily to refresh SEO sitemap XML files with latest public content.
 * <p>
 * <b>Schedule:</b> Daily at 4am UTC (cron: 0 0 4 * * ?)
 * <p>
 * <b>Queue:</b> LOW (5 workers, 30s poll interval)
 * <p>
 * <b>Expected Duration:</b> <30 minutes for 100K URLs (per async-workloads.md)
 * <p>
 * <b>Related Components:</b>
 * <ul>
 * <li>{@link SitemapGenerationJobHandler} - Executes the actual sitemap generation</li>
 * <li>{@link villagecompute.homepage.services.SitemapGenerationService} - Business logic</li>
 * </ul>
 *
 * @see SitemapGenerationJobHandler
 */
@ApplicationScoped
public class SitemapGenerationScheduler {

    private static final Logger LOG = Logger.getLogger(SitemapGenerationScheduler.class);

    @Inject
    DelayedJobService jobService;

    /**
     * Schedules daily sitemap generation at 4am UTC.
     * <p>
     * Cron expression: 0 0 4 * * ? (minute=0, hour=0, hour=4, day=*, month=*, day-of-week=?)
     */
    @Scheduled(
            cron = "0 0 4 * * ?")
    void scheduleSitemapGeneration() {
        Map<String, Object> payload = Map.of();
        jobService.enqueue(JobType.SITEMAP_GENERATION, payload);
        LOG.info("Scheduled daily sitemap generation job");
    }
}
