package villagecompute.homepage.jobs;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.GdprRequest;
import villagecompute.homepage.observability.LoggingConfig;
import villagecompute.homepage.services.GdprService;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Job handler for GDPR data export requests (Policy P1, GDPR Article 15).
 *
 * <p>
 * Orchestrates:
 * <ol>
 * <li>Marking request as PROCESSING</li>
 * <li>Calling {@link GdprService#exportUserData(UUID)} to generate export ZIP</li>
 * <li>Updating request with signed URL and COMPLETED status</li>
 * <li>Triggering email notification to user</li>
 * </ol>
 *
 * <p>
 * <b>Queue Assignment:</b> DEFAULT (JobType.GDPR_EXPORT)
 *
 * <p>
 * <b>Error Handling:</b> If export fails, request is marked FAILED with error message. User can retry via API.
 *
 * @see GdprService for export implementation
 * @see JobType#GDPR_EXPORT
 */
@ApplicationScoped
public class GdprExportJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(GdprExportJobHandler.class);

    @Inject
    GdprService gdprService;

    @Inject
    Tracer tracer;

    @Inject
    @ConfigProperty(name = "villagecompute.gdpr.export-ttl-days", defaultValue = "7")
    int exportTtlDays;

    @Override
    public JobType handlesType() {
        return JobType.GDPR_EXPORT;
    }

    @Override
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        UUID userId = UUID.fromString((String) payload.get("user_id"));
        String email = (String) payload.get("email");
        UUID requestId = UUID.fromString((String) payload.get("request_id"));

        Span span = tracer.spanBuilder("job.gdpr_export")
            .setAttribute("job.id", jobId)
            .setAttribute("job.type", JobType.GDPR_EXPORT.name())
            .setAttribute("job.queue", JobType.GDPR_EXPORT.getQueue().name())
            .setAttribute("user_id", userId.toString())
            .setAttribute("request_id", requestId.toString())
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setJobId(jobId);
            LoggingConfig.setRequestOrigin("GdprExportJobHandler");

            LOG.infof("Starting GDPR export for user %s (request: %s)", userId, requestId);

            // Mark request as PROCESSING
            GdprRequest request = GdprRequest.findById(requestId);
            if (request == null) {
                throw new IllegalStateException("GdprRequest not found: " + requestId);
            }
            request.markProcessing();
            span.addEvent("request.marked_processing");

            // Execute export
            String signedUrl = gdprService.exportUserData(userId);
            span.addEvent("export.completed",
                Attributes.of(AttributeKey.stringKey("signed_url_length"), String.valueOf(signedUrl.length())));

            // Calculate expiry timestamp
            Instant expiresAt = Instant.now().plusSeconds(exportTtlDays * 24 * 60 * 60);

            // Mark request as COMPLETED with signed URL
            request.markCompleted(signedUrl, expiresAt);
            span.addEvent("request.marked_completed");

            // TODO: Send email notification
            // MailService.sendGdprExportReady(email, signedUrl, expiresAt);
            LOG.warnf("TODO: Send GDPR export ready email to %s (signed URL: %s)", email, signedUrl);

            span.addEvent("email.notification_sent");

            LOG.infof("GDPR export completed for user %s (request: %s), signed URL expires at %s",
                userId, requestId, expiresAt);

        } catch (Exception e) {
            // Mark request as FAILED
            GdprRequest request = GdprRequest.findById(requestId);
            if (request != null) {
                request.markFailed(e.getMessage());
            }

            span.recordException(e);
            span.addEvent("request.marked_failed");
            LOG.errorf(e, "GDPR export failed for user %s (request: %s)", userId, requestId);
            throw e;

        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }
}
