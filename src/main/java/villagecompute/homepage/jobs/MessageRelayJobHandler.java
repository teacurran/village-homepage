package villagecompute.homepage.jobs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.MarketplaceMessage;
import villagecompute.homepage.observability.LoggingConfig;
import villagecompute.homepage.services.MessageRelayService;

import java.util.Map;
import java.util.UUID;

/**
 * Job handler for marketplace message relay (Features F12.6, F14.3).
 *
 * <p>
 * This handler processes MESSAGE_RELAY jobs enqueued by the listing contact REST endpoint. It relays buyer inquiries to
 * sellers via masked email addresses to protect privacy.
 *
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Extract listing ID, buyer email, name, subject, and message body from job payload</li>
 * <li>Validate listing exists and is active (fails job if listing invalid)</li>
 * <li>Call {@link MessageRelayService#sendInquiry} to send email via Mailer</li>
 * <li>Store message in marketplace_messages table for audit trail</li>
 * <li>Export OpenTelemetry spans and Micrometer metrics for observability</li>
 * </ol>
 *
 * <p>
 * <b>Email Headers:</b>
 * <ul>
 * <li>From: noreply@villagecompute.com (platform sender)</li>
 * <li>To: seller's real email (from listing.contactInfo.email)</li>
 * <li>Reply-To: reply-{messageId}@villagecompute.com (platform relay for seller replies)</li>
 * <li>Message-ID: &lt;msg-{uuid}@villagecompute.com&gt; (unique identifier for threading)</li>
 * </ul>
 *
 * <p>
 * <b>Error Handling:</b> Failures (invalid listing, SMTP errors) are logged and throw exceptions to trigger job retry
 * via DelayedJobService retry logic. After max retries, job enters failed state and sends alert to ops team.
 *
 * <p>
 * <b>Telemetry:</b> Exports the following OpenTelemetry span attributes:
 * <ul>
 * <li>{@code job.id} - Job database primary key</li>
 * <li>{@code job.type} - MESSAGE_RELAY</li>
 * <li>{@code job.queue} - HIGH</li>
 * <li>{@code listing_id} - Listing UUID</li>
 * <li>{@code message_id} - Email Message-ID</li>
 * <li>{@code recipient_email} - Seller email (hashed for privacy)</li>
 * </ul>
 *
 * <p>
 * And Micrometer metrics:
 * <ul>
 * <li>{@code marketplace.messages.relayed.total} (Counter) - Total messages successfully relayed</li>
 * <li>{@code marketplace.messages.relay.duration} (Timer) - Job execution duration</li>
 * <li>{@code marketplace.messages.relay.errors.total} (Counter) - Relay failures</li>
 * </ul>
 *
 * <p>
 * <b>Payload Structure:</b>
 *
 * <pre>
 * {
 *   "listingId": "a3f8b9c0-1234-5678-abcd-1234567890ab",
 *   "fromEmail": "buyer@example.com",
 *   "fromName": "John Doe",
 *   "messageSubject": "Inquiry about: Item Title",
 *   "messageBody": "Is this item still available?"
 * }
 * </pre>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>F12.6: Email masking for contact relay</li>
 * <li>F14.3: IMAP polling for marketplace reply relay (paired with InboundEmailProcessor)</li>
 * <li>P6: Privacy via masked email relay</li>
 * <li>P7: Unified job orchestration via DelayedJobService</li>
 * </ul>
 *
 * @see MessageRelayService for email sending logic
 * @see InboundEmailProcessor for reply processing
 */
@ApplicationScoped
public class MessageRelayJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(MessageRelayJobHandler.class);

    @Inject
    MessageRelayService messageRelayService;

    @Inject
    Tracer tracer;

    @Inject
    MeterRegistry meterRegistry;

    @Override
    public JobType handlesType() {
        return JobType.MESSAGE_RELAY;
    }

    @Override
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        Span span = tracer.spanBuilder("job.message_relay").setAttribute("job.id", jobId)
                .setAttribute("job.type", JobType.MESSAGE_RELAY.name()).setAttribute("job.queue", JobQueue.HIGH.name())
                .startSpan();

        Timer.Sample timerSample = Timer.start(meterRegistry);

        try (Scope scope = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setJobId(jobId);
            LoggingConfig.setRequestOrigin("MessageRelayJobHandler");

            LOG.infof("Starting message relay job %d", jobId);

            // Extract payload parameters
            UUID listingId = UUID.fromString((String) payload.get("listingId"));
            String fromEmail = (String) payload.get("fromEmail");
            String fromName = (String) payload.get("fromName");
            String messageBody = (String) payload.get("messageBody");
            String messageSubject = (String) payload.get("messageSubject");

            span.setAttribute("listing_id", listingId.toString());

            // Validate required parameters
            if (fromEmail == null || fromEmail.isBlank()) {
                throw new IllegalArgumentException("fromEmail is required");
            }
            if (messageBody == null || messageBody.isBlank()) {
                throw new IllegalArgumentException("messageBody is required");
            }

            // Send inquiry via MessageRelayService
            MarketplaceMessage message = messageRelayService.sendInquiry(listingId, fromEmail, fromName, messageBody,
                    messageSubject);

            span.setAttribute("message_id", message.messageId);
            span.setAttribute("recipient_email_hash", hashEmail(message.toEmail)); // Privacy: hash instead of plaintext

            // Export success metrics
            Counter.builder("marketplace.messages.relayed.total").tag("direction", "buyer_to_seller")
                    .register(meterRegistry).increment();

            timerSample.stop(Timer.builder("marketplace.messages.relay.duration").register(meterRegistry));

            LOG.infof("Message relay job %d completed: messageId=%s, listingId=%s", jobId, message.messageId,
                    listingId);

        } catch (Exception e) {
            LOG.errorf(e, "Message relay job %d failed: %s", jobId, e.getMessage());

            span.recordException(e);

            // Export error metrics
            Counter.builder("marketplace.messages.relay.errors.total").tag("error_type", e.getClass().getSimpleName())
                    .register(meterRegistry).increment();

            // Re-throw to trigger retry logic in DelayedJobService
            throw e;

        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Hashes email address for privacy-safe logging.
     *
     * <p>
     * Uses simple hashCode to avoid logging PII in telemetry spans. This allows correlation of events for same email
     * without exposing actual email addresses in logs/metrics.
     *
     * @param email
     *            the email address
     * @return hash code as string
     */
    private String hashEmail(String email) {
        return email != null ? String.valueOf(email.hashCode()) : "null";
    }
}
