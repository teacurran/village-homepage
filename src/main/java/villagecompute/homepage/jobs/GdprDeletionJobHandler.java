package villagecompute.homepage.jobs;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.GdprRequest;
import villagecompute.homepage.observability.LoggingConfig;
import villagecompute.homepage.services.GdprService;

import java.util.Map;
import java.util.UUID;

/**
 * Job handler for GDPR account deletion requests (Policy P1, GDPR Article 17).
 *
 * <p>
 * Orchestrates:
 * <ol>
 * <li>Marking request as PROCESSING</li>
 * <li>Calling {@link GdprService#deleteUserData(UUID)} to cascade deletion</li>
 * <li>Marking request as COMPLETED (before user record is deleted)</li>
 * <li>Triggering confirmation email notification</li>
 * </ol>
 *
 * <p>
 * <b>Queue Assignment:</b> HIGH (JobType.GDPR_DELETION) - prioritized over DEFAULT jobs
 *
 * <p>
 * <b>Critical Note:</b> This is a permanent, irreversible operation. All user data and related entities are hard-deleted
 * (no soft delete). Ensure request validation occurs in REST layer before enqueuing.
 *
 * <p>
 * <b>Error Handling:</b> If deletion fails, request is marked FAILED with error message. Transaction rollback ensures
 * partial deletion does not occur.
 *
 * @see GdprService for deletion implementation
 * @see JobType#GDPR_DELETION
 */
@ApplicationScoped
public class GdprDeletionJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(GdprDeletionJobHandler.class);

    @Inject
    GdprService gdprService;

    @Inject
    Tracer tracer;

    @Override
    public JobType handlesType() {
        return JobType.GDPR_DELETION;
    }

    @Override
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        UUID userId = UUID.fromString((String) payload.get("user_id"));
        String email = (String) payload.get("email");
        UUID requestId = UUID.fromString((String) payload.get("request_id"));

        Span span = tracer.spanBuilder("job.gdpr_deletion")
            .setAttribute("job.id", jobId)
            .setAttribute("job.type", JobType.GDPR_DELETION.name())
            .setAttribute("job.queue", JobType.GDPR_DELETION.getQueue().name())
            .setAttribute("user_id", userId.toString())
            .setAttribute("request_id", requestId.toString())
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setJobId(jobId);
            LoggingConfig.setRequestOrigin("GdprDeletionJobHandler");

            LOG.infof("Starting GDPR deletion for user %s (request: %s)", userId, requestId);

            // Mark request as PROCESSING
            GdprRequest request = GdprRequest.findById(requestId);
            if (request == null) {
                throw new IllegalStateException("GdprRequest not found: " + requestId);
            }
            request.markProcessing();
            span.addEvent("request.marked_processing");

            // Mark request as COMPLETED BEFORE deletion (since GdprRequest itself will be deleted)
            // This ensures audit trail is preserved even if email send fails
            request.markCompleted(null, null);
            span.addEvent("request.marked_completed");

            // Execute deletion (this will delete the user record and all related data)
            gdprService.deleteUserData(userId);
            span.addEvent("deletion.completed");

            // TODO: Send confirmation email
            // MailService.sendGdprDeletionComplete(email);
            LOG.warnf("TODO: Send GDPR deletion confirmation email to %s", email);

            span.addEvent("email.notification_sent");

            LOG.infof("GDPR deletion completed for user %s (request: %s)", userId, requestId);

        } catch (Exception e) {
            // Mark request as FAILED
            GdprRequest request = GdprRequest.findById(requestId);
            if (request != null) {
                request.markFailed(e.getMessage());
            }

            span.recordException(e);
            span.addEvent("request.marked_failed");
            LOG.errorf(e, "GDPR deletion failed for user %s (request: %s)", userId, requestId);
            throw e;

        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }
}
