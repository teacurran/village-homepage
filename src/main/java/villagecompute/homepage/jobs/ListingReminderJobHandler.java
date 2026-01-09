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
 * Job handler for marketplace listing expiration reminders (Feature F12.7).
 *
 * <p>
 * This handler runs daily to identify active marketplace listings expiring within 2-3 days and send reminder emails to
 * sellers. Reminders are sent once per listing (tracked by reminder_sent boolean) to avoid duplicate notifications.
 *
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Extract {@code days} parameter from payload (default: 3 days)</li>
 * <li>Query {@link MarketplaceListing#findExpiringWithinDays(int)} for active listings expiring within N days</li>
 * <li>For each listing:
 * <ul>
 * <li>Send reminder email to seller (contact_info.email) with expiration date</li>
 * <li>Call {@link MarketplaceListing#markReminderSent(java.util.UUID)} to set reminder_sent = true</li>
 * <li>Log telemetry event</li>
 * </ul>
 * </li>
 * <li>Export OpenTelemetry spans and Micrometer metrics for observability</li>
 * <li>Individual email failures do NOT abort batch processing</li>
 * </ol>
 *
 * <p>
 * <b>Reminder Logic:</b> The job queries listings where:
 * <ul>
 * <li>status = 'active'</li>
 * <li>expires_at > NOW() (not yet expired)</li>
 * <li>expires_at <= NOW() + INTERVAL 'N days'</li>
 * <li>reminder_sent = false (not already reminded)</li>
 * </ul>
 *
 * <p>
 * <b>Email Content:</b> Reminder email includes:
 * <ul>
 * <li>Listing title and URL</li>
 * <li>Expiration date (formatted, e.g., "January 15, 2026")</li>
 * <li>Call-to-action to renew/bump listing (future feature)</li>
 * <li>Link to manage listing in dashboard</li>
 * </ul>
 *
 * <p>
 * <b>Error Handling:</b> Individual email send failures (e.g., SMTP errors, invalid email addresses) are logged and
 * recorded as exceptions, but do NOT abort the job. The job continues processing remaining listings and reports partial
 * failure if any email fails. Listings with failed emails are NOT marked as reminder_sent to allow retry on next run.
 *
 * <p>
 * <b>Telemetry:</b> Exports the following OpenTelemetry span attributes:
 * <ul>
 * <li>{@code job.id} - Job database primary key</li>
 * <li>{@code job.type} - LISTING_REMINDER</li>
 * <li>{@code job.queue} - DEFAULT</li>
 * <li>{@code days_threshold} - Number of days before expiration for reminder</li>
 * <li>{@code listings_found} - Total listings expiring soon</li>
 * <li>{@code reminders_sent} - Reminder emails successfully sent</li>
 * <li>{@code reminders_failed} - Reminder emails that failed to send</li>
 * </ul>
 *
 * <p>
 * And Micrometer metrics:
 * <ul>
 * <li>{@code marketplace.listings.reminded.total} (Counter) - Total reminder emails successfully sent</li>
 * <li>{@code marketplace.listings.reminder.duration} (Timer) - Job execution duration</li>
 * <li>{@code marketplace.listings.reminder.errors.total} (Counter) - Individual email send errors</li>
 * </ul>
 *
 * <p>
 * <b>Payload Structure:</b>
 *
 * <pre>
 * {
 *   "days": 3  // Optional - number of days before expiration (default: 3)
 * }
 * </pre>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F12.7: Expiration reminders 2-3 days before listing expires</li>
 * <li>P1: GDPR compliance - sellers control their listings and can opt-out of emails</li>
 * <li>P7: Unified job orchestration via DelayedJobService</li>
 * </ul>
 *
 * <p>
 * <b>NOTE:</b> Email sending functionality is currently a placeholder. Full email integration with Qute templates will
 * be implemented in future iteration (similar to village-calendar email system).
 *
 * @see MarketplaceListing for listing entity
 * @see ListingExpirationJobHandler for expiration handler
 */
@ApplicationScoped
public class ListingReminderJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(ListingReminderJobHandler.class);

    /** Default threshold: remind sellers 3 days before expiration. */
    private static final int DEFAULT_DAYS_THRESHOLD = 3;

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @Override
    public JobType handlesType() {
        return JobType.LISTING_REMINDER;
    }

    @Override
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        Span span = tracer.spanBuilder("job.listing_reminder").setAttribute("job.id", jobId)
                .setAttribute("job.type", JobType.LISTING_REMINDER.name())
                .setAttribute("job.queue", JobQueue.DEFAULT.name()).startSpan();

        Timer.Sample timerSample = Timer.start(meterRegistry);

        try (Scope scope = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setJobId(jobId);
            LoggingConfig.setRequestOrigin("ListingReminderJobHandler");

            LOG.infof("Starting listing reminder job %d", jobId);

            // Extract payload parameters
            int daysThreshold = DEFAULT_DAYS_THRESHOLD;
            if (payload.containsKey("days")) {
                daysThreshold = Integer.parseInt(payload.get("days").toString());
            }

            span.setAttribute("days_threshold", daysThreshold);
            LOG.infof("Reminder threshold: %d days before expiration", daysThreshold);

            // Find listings expiring soon
            List<MarketplaceListing> expiringListings = MarketplaceListing.findExpiringWithinDays(daysThreshold);

            if (expiringListings.isEmpty()) {
                LOG.infof("No listings expiring within %d days", daysThreshold);
                span.addEvent("reminder.no_listings");
                timerSample.stop(Timer.builder("marketplace.listings.reminder.duration").register(meterRegistry));
                return;
            }

            LOG.infof("Found %d listings expiring within %d days", expiringListings.size(), daysThreshold);
            span.setAttribute("listings_found", expiringListings.size());

            int successCount = 0;
            int failureCount = 0;

            // Process each expiring listing
            for (MarketplaceListing listing : expiringListings) {
                try {
                    // Send reminder email
                    sendReminderEmail(listing);

                    // Mark reminder as sent to prevent duplicates
                    MarketplaceListing.markReminderSent(listing.id);

                    successCount++;

                    LOG.infof("Sent reminder for listing: id=%s, userId=%s, expiresAt=%s, email=%s", listing.id,
                            listing.userId, listing.expiresAt, listing.contactInfo.email());

                } catch (Exception e) {
                    failureCount++;
                    LOG.errorf(e, "Failed to send reminder for listing %s: %s", listing.id, e.getMessage());
                    span.recordException(e);

                    Counter.builder("marketplace.listings.reminder.errors.total")
                            .tag("listing_id", listing.id.toString()).register(meterRegistry).increment();

                    // Continue processing other listings even if one fails
                    // Do NOT mark reminder_sent for failed emails to allow retry
                }
            }

            span.setAttribute("reminders_sent", successCount);
            span.setAttribute("reminders_failed", failureCount);
            span.addEvent("reminder.completed", Attributes.of(AttributeKey.longKey("success"), (long) successCount,
                    AttributeKey.longKey("failure"), (long) failureCount));

            // Record success metrics
            Counter.builder("marketplace.listings.reminded.total").register(meterRegistry).increment(successCount);

            timerSample.stop(Timer.builder("marketplace.listings.reminder.duration").register(meterRegistry));

            LOG.infof("Listing reminder job %d completed: %d reminders sent, %d failed", jobId, successCount,
                    failureCount);

            if (failureCount > 0) {
                throw new RuntimeException(String.format("Reminder job partially failed: %d/%d emails failed",
                        failureCount, expiringListings.size()));
            }

        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Sends a reminder email to the listing seller.
     *
     * <p>
     * <b>NOTE:</b> This is currently a placeholder implementation. Full email integration with Qute templates will be
     * added in future iteration.
     *
     * <p>
     * Email should include:
     * <ul>
     * <li>Subject: "Your listing '{title}' expires soon"</li>
     * <li>Body: Listing title, expiration date, link to manage listing</li>
     * <li>Call-to-action: Renew/bump listing (future paid feature)</li>
     * </ul>
     *
     * @param listing
     *            the listing to send reminder for
     * @throws Exception
     *             if email send fails
     */
    private void sendReminderEmail(MarketplaceListing listing) throws Exception {
        // TODO: Implement email sending with Qute templates
        // For now, just log that we would send an email

        String emailTo = listing.contactInfo.email();
        String subject = String.format("Your listing '%s' expires soon", listing.title);
        String expiresAtFormatted = listing.expiresAt.toString(); // TODO: Format with DateTimeFormatter

        LOG.infof("PLACEHOLDER: Would send reminder email to %s: subject='%s', expiresAt=%s", emailTo, subject,
                expiresAtFormatted);

        // Future implementation:
        // 1. Load Qute template: templates/emails/listing-expiration-reminder.html
        // 2. Render template with listing data
        // 3. Send via SMTP (use existing email service from village-calendar pattern)
        // 4. Handle SMTP errors and invalid email addresses
    }
}
