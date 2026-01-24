package villagecompute.homepage.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.services.SitemapGenerationService;

import java.util.List;
import java.util.Map;

/**
 * Job handler for SEO sitemap generation (daily at 4am UTC).
 * <p>
 * Generates XML sitemaps for all public content (directory categories, sites, active marketplace listings) and uploads
 * to Cloudflare R2 for CDN delivery. Automatically splits into multiple files if URL count exceeds 50,000 (per
 * sitemaps.org protocol).
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Query all public URLs via {@link SitemapGenerationService#getSitemapUrls()}</li>
 * <li>Generate sitemap XML(s) - single file if <50K URLs, multiple files with index if >50K</li>
 * <li>Upload to R2 storage at /sitemaps/sitemap.xml (or sitemap-1.xml, sitemap-2.xml, etc.)</li>
 * <li>Record metrics for URL count, file count, upload duration</li>
 * </ol>
 * <p>
 * <b>Telemetry:</b>
 * <ul>
 * <li>{@code job.sitemap_generation} (Span) - Full job execution span</li>
 * <li>{@code sitemap.generation.duration} (Timer) - Total job duration</li>
 * <li>{@code sitemap.files.generated} (Counter) - Number of sitemap files created</li>
 * <li>{@code sitemap.urls.total} (Counter) - Total URLs included (recorded in service)</li>
 * </ul>
 * <p>
 * <b>Span Attributes:</b>
 * <ul>
 * <li>{@code job.id} - DelayedJob ID</li>
 * <li>{@code job.type} - SITEMAP_GENERATION</li>
 * <li>{@code job.queue} - LOW</li>
 * <li>{@code sitemaps_generated} - Number of sitemap files uploaded</li>
 * </ul>
 * <p>
 * <b>Payload:</b> Empty map (no parameters)
 * <p>
 * <b>Related Policies:</b>
 * <ul>
 * <li>Policy P11: SEO optimization via sitemap submission to search engines</li>
 * <li>Feature F14.5: Public content discoverability</li>
 * </ul>
 *
 * @see SitemapGenerationService
 * @see SitemapGenerationScheduler
 */
@ApplicationScoped
public class SitemapGenerationJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(SitemapGenerationJobHandler.class);

    @Inject
    SitemapGenerationService sitemapService;

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @Override
    public JobType handlesType() {
        return JobType.SITEMAP_GENERATION;
    }

    @Override
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        Span span = tracer.spanBuilder("job.sitemap_generation").setAttribute("job.id", jobId)
                .setAttribute("job.type", "SITEMAP_GENERATION").setAttribute("job.queue", "LOW").startSpan();

        Timer.Sample timer = Timer.start(meterRegistry);

        try (var scope = span.makeCurrent()) {
            LOG.infof("Starting sitemap generation job (jobId=%d)", jobId);

            // Generate and upload sitemaps
            List<String> uploadedUrls = sitemapService.generateAndUploadSitemaps();

            span.setAttribute("sitemaps_generated", uploadedUrls.size());

            if (uploadedUrls.isEmpty()) {
                LOG.warnf("Sitemap generation complete but no URLs were uploaded (jobId=%d)", jobId);
            } else {
                LOG.infof("Sitemap generation complete: %d files uploaded (jobId=%d)", uploadedUrls.size(), jobId);
            }

        } catch (Exception e) {
            span.recordException(e);
            LOG.errorf(e, "Sitemap generation failed (jobId=%d): %s", jobId, e.getMessage());
            throw e;

        } finally {
            timer.stop(meterRegistry.timer("sitemap.generation.duration"));
            span.end();
        }
    }
}
