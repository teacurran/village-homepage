package villagecompute.homepage.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.DirectorySite;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Monitors link health for Good Sites directory by performing HTTP HEAD requests.
 *
 * <p>
 * <b>Execution Strategy:</b>
 * <ul>
 * <li>Checks all approved sites for HTTP accessibility</li>
 * <li>Tracks consecutive failures in healthCheckFailures counter</li>
 * <li>Marks site as dead after 3 consecutive failures</li>
 * <li>Resets counter on successful check</li>
 * </ul>
 *
 * <p>
 * <b>HTTP Check Logic:</b>
 * <ul>
 * <li>Uses HEAD request to minimize bandwidth</li>
 * <li>Falls back to GET if HEAD fails with 405 Method Not Allowed</li>
 * <li>10 second timeout per request</li>
 * <li>2xx and 3xx status codes considered healthy</li>
 * <li>4xx and 5xx status codes count as failures</li>
 * </ul>
 *
 * <p>
 * <b>Dead Link Handling:</b>
 * <ul>
 * <li>After 3 consecutive failures, site.markDead() is called</li>
 * <li>Dead sites display warning in UI (see good-sites-ux-guide.md)</li>
 * <li>Vote buttons disabled for dead sites</li>
 * <li>Moderators notified (TODO: implement email notification)</li>
 * </ul>
 *
 * <p>
 * <b>Recovery Handling:</b>
 * <ul>
 * <li>Sites marked dead are NOT excluded from health checks</li>
 * <li>If dead site becomes accessible, failure counter resets</li>
 * <li>Manual moderator action required to restore status to approved</li>
 * </ul>
 *
 * <p>
 * <b>Metrics Emitted:</b>
 * <ul>
 * <li>link_health.checks.total{result=success}</li>
 * <li>link_health.checks.total{result=failed}</li>
 * <li>link_health.checks.total{result=recovered}</li>
 * <li>link_health.check.duration (timer)</li>
 * </ul>
 *
 * <p>
 * <b>Scheduled Execution:</b> Weekly via LinkHealthCheckScheduler (cron: 0 0 3 ? * SUN - Sunday 3am UTC)
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P7: Participates in unified async orchestration framework</li>
 * <li>Feature F13.5: Link health monitoring for Good Sites directory</li>
 * </ul>
 *
 * @see DirectorySite#markDead()
 * @see LinkHealthCheckScheduler
 */
@ApplicationScoped
public class LinkHealthCheckJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(LinkHealthCheckJobHandler.class);

    private static final int TIMEOUT_SECONDS = 10;
    private static final int FAILURE_THRESHOLD = 3;
    private static final int BATCH_SIZE = 100;

    @Inject
    MeterRegistry meterRegistry;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();

    @Override
    public JobType handlesType() {
        return JobType.LINK_HEALTH_CHECK;
    }

    @Override
    @Transactional
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        LOG.infof("Starting link health check job (jobId=%d)", jobId);

        Timer.Sample sample = Timer.start(meterRegistry);

        // Check all approved sites (including dead ones for recovery detection)
        List<DirectorySite> approvedSites = DirectorySite.findByStatus("approved");
        LOG.infof("Found %d approved sites to check", approvedSites.size());

        int totalChecked = 0;
        int successCount = 0;
        int failedCount = 0;
        int recoveredCount = 0;
        int markedDeadCount = 0;

        // Process in batches to avoid memory issues
        for (int i = 0; i < approvedSites.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, approvedSites.size());
            List<DirectorySite> batch = approvedSites.subList(i, end);

            LOG.infof("Processing batch %d-%d of %d sites", i + 1, end, approvedSites.size());

            for (DirectorySite site : batch) {
                try {
                    boolean accessible = isLinkAccessible(site.url);
                    checkSiteHealth(site, accessible);

                    totalChecked++;

                    if (accessible) {
                        if (site.isDead) {
                            recoveredCount++;
                            meterRegistry.counter("link_health.checks.total", "result", "recovered").increment();
                        } else {
                            successCount++;
                            meterRegistry.counter("link_health.checks.total", "result", "success").increment();
                        }
                    } else {
                        failedCount++;
                        meterRegistry.counter("link_health.checks.total", "result", "failed").increment();

                        if (site.healthCheckFailures >= FAILURE_THRESHOLD && site.isDead) {
                            markedDeadCount++;
                        }
                    }
                } catch (Exception e) {
                    LOG.errorf(e, "Error checking site health: %s (site_id=%s)", site.url, site.id);
                    // Count exception as failure
                    failedCount++;
                    meterRegistry.counter("link_health.checks.total", "result", "error").increment();
                }
            }

            // Flush batch to database
            DirectorySite.flush();
        }

        sample.stop(Timer.builder("link_health.check.duration").register(meterRegistry));

        LOG.infof("Link health check completed: total=%d, success=%d, failed=%d, recovered=%d, marked_dead=%d",
                totalChecked, successCount, failedCount, recoveredCount, markedDeadCount);
    }

    /**
     * Checks if a link is accessible via HTTP HEAD request.
     *
     * @param url
     *            URL to check
     * @return true if accessible (2xx or 3xx status), false otherwise
     */
    private boolean isLinkAccessible(String url) {
        try {
            // First try HEAD request
            HttpRequest headRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();

            HttpResponse<Void> response = httpClient.send(headRequest, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();

            // If HEAD not allowed, fall back to GET
            if (status == 405) {
                LOG.debugf("HEAD not allowed for %s, falling back to GET", url);
                return tryGetRequest(url);
            }

            // 2xx and 3xx are considered accessible
            boolean accessible = status >= 200 && status < 400;

            if (!accessible) {
                LOG.debugf("Site returned non-successful status: %d for %s", status, url);
            }

            return accessible;

        } catch (Exception e) {
            LOG.debugf(e, "Failed to check link accessibility: %s", url);
            return false;
        }
    }

    /**
     * Performs GET request as fallback when HEAD is not allowed.
     *
     * @param url
     *            URL to check
     * @return true if accessible (2xx or 3xx status), false otherwise
     */
    private boolean tryGetRequest(String url) {
        try {
            HttpRequest getRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();

            HttpResponse<Void> response = httpClient.send(getRequest, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();

            return status >= 200 && status < 400;

        } catch (Exception e) {
            LOG.debugf(e, "GET fallback failed for: %s", url);
            return false;
        }
    }

    /**
     * Updates site health status based on check result.
     *
     * <p>
     * Tracks consecutive failures and marks site dead after threshold. Resets counter on success.
     *
     * @param site
     *            Site to update
     * @param accessible
     *            Whether site is currently accessible
     */
    private void checkSiteHealth(DirectorySite site, boolean accessible) {
        if (accessible) {
            // Reset failure counter on success
            if (site.healthCheckFailures > 0) {
                LOG.infof("Site recovered: %s (site_id=%s, previous_failures=%d)",
                        site.url, site.id, site.healthCheckFailures);

                site.healthCheckFailures = 0;
                site.lastCheckedAt = Instant.now();
                site.updatedAt = Instant.now();
                site.persist();
            } else {
                // Just update timestamp for healthy sites
                site.lastCheckedAt = Instant.now();
                site.updatedAt = Instant.now();
                site.persist();
            }
        } else {
            // Increment failure counter
            site.healthCheckFailures++;
            site.lastCheckedAt = Instant.now();
            site.updatedAt = Instant.now();

            // Mark dead after threshold
            if (site.healthCheckFailures >= FAILURE_THRESHOLD && !site.isDead) {
                site.markDead();
                LOG.warnf("Site marked dead after %d consecutive failures: %s (site_id=%s)",
                        site.healthCheckFailures, site.url, site.id);

                // TODO: Notify moderators (F13.5 requirement)
                notifyModerators(site);
            } else {
                site.persist();
                LOG.warnf("Site health check failed (%d/%d): %s (site_id=%s)",
                        site.healthCheckFailures, FAILURE_THRESHOLD, site.url, site.id);
            }
        }
    }

    /**
     * Sends notification to moderators about dead link detection.
     *
     * <p>
     * TODO: Implement email notification using EmailService.
     *
     * @param site
     *            Site that was marked dead
     */
    private void notifyModerators(DirectorySite site) {
        LOG.infof("TODO: Notify moderators about dead link: %s (site_id=%s, title=%s, failures=%d)",
                site.url, site.id, site.title, site.healthCheckFailures);

        // Stub for future email notification
        // String subject = "Dead link detected: " + site.title;
        // String body = String.format(
        // "Site URL: %s\nTitle: %s\nLast checked: %s\nConsecutive failures: %d",
        // site.url, site.title, site.lastCheckedAt, site.healthCheckFailures
        // );
        // emailService.sendToModerators(subject, body);
    }
}
