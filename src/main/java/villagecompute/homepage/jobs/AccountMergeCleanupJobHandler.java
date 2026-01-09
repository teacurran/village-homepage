package villagecompute.homepage.jobs;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.AccountMergeAudit;
import villagecompute.homepage.data.models.User;
import villagecompute.homepage.observability.LoggingConfig;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Job handler for purging soft-deleted anonymous user records after 90-day retention period (Policy P1).
 *
 * <p>
 * This handler runs daily at 4am UTC to hard-delete anonymous user records that were merged into authenticated accounts
 * and have exceeded their 90-day retention period. It implements GDPR/CCPA compliance requirements for data deletion.
 *
 * <p>
 * <b>Cleanup Process:</b>
 * <ol>
 * <li>Query {@code account_merge_audit} for records where {@code purge_after <= NOW()}</li>
 * <li>For each audit record, hard-delete the associated anonymous user</li>
 * <li>Delete the audit record itself (or mark as processed)</li>
 * <li>Log all operations with trace context for compliance audits</li>
 * </ol>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P1: GDPR/CCPA data governance - 90-day retention for soft-deleted anonymous accounts</li>
 * <li>P7: Unified job orchestration via {@link DelayedJobService}</li>
 * </ul>
 *
 * @see AccountMergeAudit for audit trail entity
 * @see User for user entity
 * @see villagecompute.homepage.services.AccountMergeService for merge logic
 */
@ApplicationScoped
public class AccountMergeCleanupJobHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(AccountMergeCleanupJobHandler.class);

    @Inject
    Tracer tracer;

    @Override
    public JobType handlesType() {
        return JobType.ACCOUNT_MERGE_CLEANUP;
    }

    @Override
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        Span span = tracer.spanBuilder("job.account_merge_cleanup").setAttribute("job.id", jobId)
                .setAttribute("job.type", JobType.ACCOUNT_MERGE_CLEANUP.name())
                .setAttribute("job.queue", JobQueue.DEFAULT.name()).startSpan();

        try (Scope scope = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setJobId(jobId);
            LoggingConfig.setRequestOrigin("AccountMergeCleanupJobHandler");

            LOG.infof("Starting account merge cleanup job %d", jobId);

            // Query for audit records ready for purge
            List<AccountMergeAudit> pendingPurge = AccountMergeAudit.findPendingPurge();

            if (pendingPurge.isEmpty()) {
                LOG.infof("No audit records found ready for purge");
                span.addEvent("cleanup.no_records");
                return;
            }

            LOG.infof("Found %d audit records ready for purge", pendingPurge.size());
            span.setAttribute("records.pending_purge", pendingPurge.size());

            int successCount = 0;
            int failureCount = 0;

            for (AccountMergeAudit audit : pendingPurge) {
                try {
                    purgeAnonymousUser(audit);
                    successCount++;
                } catch (Exception e) {
                    failureCount++;
                    LOG.errorf(e, "Failed to purge anonymous user for audit %s", audit.id);
                    span.recordException(e);
                    // Continue processing other records even if one fails
                }
            }

            span.addEvent("cleanup.completed", Attributes.of(AttributeKey.longKey("records.success"),
                    (long) successCount, AttributeKey.longKey("records.failure"), (long) failureCount));

            LOG.infof("Account merge cleanup job %d completed: %d succeeded, %d failed", jobId, successCount,
                    failureCount);

            if (failureCount > 0) {
                throw new RuntimeException(String.format("Cleanup job partially failed: %d/%d records failed",
                        failureCount, pendingPurge.size()));
            }

        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Purges a single anonymous user and its associated audit record.
     *
     * @param audit
     *            the audit record for the merge
     * @throws Exception
     *             if purge fails
     */
    private void purgeAnonymousUser(AccountMergeAudit audit) throws Exception {
        Span span = tracer.spanBuilder("cleanup.purge_user").setAttribute("audit_id", audit.id.toString())
                .setAttribute("anonymous_user_id", audit.anonymousUserId.toString())
                .setAttribute("authenticated_user_id", audit.authenticatedUserId.toString()).startSpan();

        try (Scope scope = span.makeCurrent()) {
            UUID anonymousUserId = audit.anonymousUserId;

            // Load anonymous user
            Optional<User> userOpt = User.findByIdOptional(anonymousUserId);

            if (userOpt.isEmpty()) {
                LOG.warnf("Anonymous user %s not found - may have been manually deleted", anonymousUserId);
                span.addEvent("user.not_found");

                // Delete audit record anyway to keep database clean
                QuarkusTransaction.requiringNew().run(() -> audit.delete());

                LOG.infof("Deleted orphaned audit record %s (anonymous user %s not found)", audit.id, anonymousUserId);
                return;
            }

            User anonymousUser = userOpt.get();

            // Validate user is actually soft-deleted and anonymous
            if (anonymousUser.deletedAt == null) {
                LOG.warnf("Anonymous user %s is not soft-deleted - skipping purge", anonymousUserId);
                span.addEvent("user.not_soft_deleted");
                throw new IllegalStateException("Cannot purge user " + anonymousUserId + " - not soft-deleted");
            }

            if (!anonymousUser.isAnonymous) {
                LOG.errorf("User %s is not anonymous - cannot purge", anonymousUserId);
                span.addEvent("user.not_anonymous");
                throw new IllegalStateException("Cannot purge user " + anonymousUserId + " - not anonymous");
            }

            // Hard-delete user and audit record in transaction
            QuarkusTransaction.requiringNew().run(() -> {
                anonymousUser.delete();
                audit.delete();
            });

            span.addEvent("user.purged", Attributes.of(AttributeKey.stringKey("anonymous_user_id"),
                    anonymousUserId.toString(), AttributeKey.stringKey("audit_id"), audit.id.toString()));

            LOG.infof("Purged anonymous user %s and audit record %s (consent timestamp: %s, purge after: %s)",
                    anonymousUserId, audit.id, audit.consentTimestamp, audit.purgeAfter);

        } catch (Exception e) {
            span.recordException(e);
            throw e;

        } finally {
            span.end();
        }
    }
}
