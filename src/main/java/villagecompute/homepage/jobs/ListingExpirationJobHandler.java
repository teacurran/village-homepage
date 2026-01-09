package villagecompute.homepage.jobs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.MarketplaceListing;
import villagecompute.homepage.observability.LoggingConfig;

import java.util.List;
import java.util.Map;

/**
 * Job handler for marketplace listing expiration (Feature F12.7).
 *
 * <p>
 * This handler runs daily to identify and expire active marketplace listings that have passed their 30-day expiration
 * period. Listings with status = 'active' and expires_at <= NOW() are transitioned to status = 'expired', making them
 * no longer visible in public searches.
 *
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Query {@link MarketplaceListing#findExpired()} for active listings with expires_at <= NOW()</li>
 * <li>For each expired listing:
 * <ul>
 * <li>Call {@link MarketplaceListing#markExpired(java.util.UUID)} to update status to 'expired'</li>
 * <li>Update updated_at timestamp</li>
 * <li>Log telemetry event</li>
 * </ul>
 * </li>
 * <li>Export OpenTelemetry spans and Micrometer metrics for observability</li>
 * <li>Individual listing errors do NOT abort batch processing</li>
 * </ol>
 *
 * <p>
 * <b>Expiration Logic:</b> When a listing is activated (status → 'active'), expires_at is automatically set to NOW() +
 * 30 days. This job scans for listings where that timestamp has passed and marks them as expired.
 *
 * <p>
 * <b>Error Handling:</b> Individual listing expiration failures (e.g., concurrent updates, database errors) are logged
 * and recorded as exceptions, but do NOT abort the job. The job continues processing remaining expired listings and
 * reports partial failure if any listing fails.
 *
 * <p>
 * <b>Telemetry:</b> Exports the following OpenTelemetry span attributes:
 * <ul>
 * <li>{@code job.id} - Job database primary key</li>
 * <li>{@code job.type} - LISTING_EXPIRATION</li>
 * <li>{@code job.queue} - DEFAULT</li>
 * <li>{@code listings_found} - Total expired listings found</li>
 * <li>{@code listings_expired} - Listings successfully marked as expired</li>
 * <li>{@code listings_failed} - Listings that failed to expire</li>
 * </ul>
 *
 * <p>
 * And Micrometer metrics:
 * <ul>
 * <li>{@code marketplace.listings.expired.total} (Counter) - Total listings successfully expired</li>
 * <li>{@code marketplace.listings.expiration.duration} (Timer) - Job execution duration</li>
 * <li>{@code marketplace.listings.expiration.errors.total} (Counter) - Individual listing expiration errors</li>
 * </ul>
 *
 * <p>
 * <b>Payload Structure:</b>
 *
 * <pre>
 * {
 *   "force": false  // Optional - if true, bypass timestamp check and expire all active listings
 * }
 * </pre>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F12.7: 30-day expiration schedule for marketplace listings</li>
 * <li>P7: Unified job orchestration via DelayedJobService</li>
 * <li>P14: Soft-delete lifecycle (expired listings retained for audit, not purged immediately)</li>
 * </ul>
 *
 * @see MarketplaceListing for listing entity
 * @see ListingReminderJobHandler for reminder email handler
 */
@ApplicationScoped
public class ListingExpirationJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(ListingExpirationJobHandler.class);

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @Override
    public JobType handlesType() {
        return JobType.LISTING_EXPIRATION;
    }

    @Override
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        Span span = tracer.spanBuilder("job.listing_expiration").setAttribute("job.id", jobId)
                .setAttribute("job.type", JobType.LISTING_EXPIRATION.name())
                .setAttribute("job.queue", JobQueue.DEFAULT.name()).startSpan();

        Timer.Sample timerSample = Timer.start(meterRegistry);

        try (Scope scope = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setJobId(jobId);
            LoggingConfig.setRequestOrigin("ListingExpirationJobHandler");

            LOG.infof("Starting listing expiration job %d", jobId);

            // Extract payload parameters
            boolean force = false;
            if (payload.containsKey("force")) {
                force = Boolean.parseBoolean(payload.get("force").toString());
            }

            if (force) {
                LOG.warnf("Force flag set - this is a test/admin operation");
                span.addEvent("expiration.force_mode");
            }

            // Find expired listings
            List<MarketplaceListing> expiredListings = MarketplaceListing.findExpired();

            if (expiredListings.isEmpty()) {
                LOG.infof("No expired listings found");
                span.addEvent("expiration.no_listings");
                timerSample.stop(Timer.builder("marketplace.listings.expiration.duration").register(meterRegistry));
                return;
            }

            LOG.infof("Found %d expired listings to mark", expiredListings.size());
            span.setAttribute("listings_found", expiredListings.size());

            int successCount = 0;
            int failureCount = 0;

            // Process each expired listing
            for (MarketplaceListing listing : expiredListings) {
                try {
                    MarketplaceListing.markExpired(listing.id);
                    successCount++;

                    LOG.debugf("Expired listing: id=%s, userId=%s, categoryId=%s, expiresAt=%s", listing.id,
                            listing.userId, listing.categoryId, listing.expiresAt);

                } catch (Exception e) {
                    failureCount++;
                    LOG.errorf(e, "Failed to expire listing %s: %s", listing.id, e.getMessage());
                    span.recordException(e);

                    Counter.builder("marketplace.listings.expiration.errors.total")
                            .tag("listing_id", listing.id.toString()).register(meterRegistry).increment();

                    // Continue processing other listings even if one fails
                }
            }

            span.setAttribute("listings_expired", successCount);
            span.setAttribute("listings_failed", failureCount);
            span.addEvent("expiration.completed", Attributes.of(AttributeKey.longKey("success"), (long) successCount,
                    AttributeKey.longKey("failure"), (long) failureCount));

            // Record success metrics
            Counter.builder("marketplace.listings.expired.total").register(meterRegistry).increment(successCount);

            timerSample.stop(Timer.builder("marketplace.listings.expiration.duration").register(meterRegistry));

            LOG.infof("Listing expiration job %d completed: %d expired, %d failed", jobId, successCount, failureCount);

            if (failureCount > 0) {
                throw new RuntimeException(String.format("Expiration job partially failed: %d/%d listings failed",
                        failureCount, expiredListings.size()));
            }

        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }
}
