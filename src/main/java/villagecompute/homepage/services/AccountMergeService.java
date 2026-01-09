package villagecompute.homepage.services;

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
import villagecompute.homepage.jobs.JobType;
import villagecompute.homepage.observability.LoggingConfig;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing anonymous-to-authenticated account merges with GDPR/CCPA compliance (Policy P1).
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Detecting merge opportunities when OAuth login completes with existing anonymous cookie</li>
 * <li>Recording explicit user consent with timestamp, IP address, user agent, and policy version</li>
 * <li>Merging anonymous user data (preferences, layout, topic subscriptions) into authenticated account</li>
 * <li>Soft-deleting anonymous user records with 90-day retention</li>
 * <li>Scheduling cleanup jobs for hard-deletion after retention period</li>
 * <li>Providing opt-out path for users who decline merge</li>
 * </ul>
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P1: GDPR/CCPA data governance - explicit consent, audit logging, 90-day retention</li>
 * <li>P9: Anonymous cookie security - secure vu_anon_id cookie tracking</li>
 * </ul>
 *
 * @see AccountMergeAudit for audit trail entity
 * @see User for user entity
 * @see villagecompute.homepage.jobs.AccountMergeCleanupJobHandler for cleanup job implementation
 */
@ApplicationScoped
public class AccountMergeService {

    private static final Logger LOG = Logger.getLogger(AccountMergeService.class);

    /**
     * Current privacy policy version for consent tracking (Policy P1). This should be incremented whenever the privacy
     * policy changes materially.
     */
    private static final String CURRENT_POLICY_VERSION = "1.0";

    @Inject
    Tracer tracer;

    @Inject
    DelayedJobService delayedJobService;

    /**
     * Records user consent for account merge without performing the actual merge.
     *
     * <p>
     * This method should be called from a consent API endpoint (e.g., {@code POST /api/auth/merge-consent}) when the
     * user explicitly agrees to merge their anonymous data into their authenticated account.
     *
     * @param anonymousUserId
     *            ID of the anonymous user (from vu_anon_id cookie)
     * @param authenticatedUserId
     *            ID of the authenticated user (from OAuth login)
     * @param consentGiven
     *            whether the user consented to the merge (true) or declined (false)
     * @param ipAddress
     *            user's IP address at time of consent
     * @param userAgent
     *            user's browser/client user agent
     * @return merge result with audit record ID
     */
    public MergeConsentResult recordConsent(UUID anonymousUserId, UUID authenticatedUserId, boolean consentGiven,
            String ipAddress, String userAgent) {
        Span span = tracer.spanBuilder("account_merge.record_consent")
                .setAttribute("anonymous_user_id", anonymousUserId.toString())
                .setAttribute("authenticated_user_id", authenticatedUserId.toString())
                .setAttribute("consent_given", consentGiven).startSpan();

        try (Scope scope = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setRequestOrigin("AccountMergeService.recordConsent");

            if (anonymousUserId == null || authenticatedUserId == null) {
                throw new IllegalArgumentException("Both anonymous and authenticated user IDs are required");
            }

            if (ipAddress == null || ipAddress.isBlank()) {
                throw new IllegalArgumentException("IP address is required for consent tracking (Policy P1)");
            }

            if (userAgent == null || userAgent.isBlank()) {
                throw new IllegalArgumentException("User agent is required for consent tracking (Policy P1)");
            }

            // Record consent decision regardless of whether user accepted or declined
            Map<String, Object> mergedDataSummary = consentGiven ? Map.of("status", "pending_merge")
                    : Map.of("status", "declined");

            AccountMergeAudit audit = AccountMergeAudit.create(anonymousUserId, authenticatedUserId, mergedDataSummary,
                    consentGiven, ipAddress, userAgent, CURRENT_POLICY_VERSION);

            span.addEvent("consent.recorded",
                    Attributes.of(AttributeKey.stringKey("audit_id"), audit.id.toString(),
                            AttributeKey.booleanKey("consent_given"), consentGiven,
                            AttributeKey.stringKey("policy_version"), CURRENT_POLICY_VERSION));

            LOG.infof("Recorded merge consent for anonymous user %s → authenticated user %s (consent: %s)",
                    anonymousUserId, authenticatedUserId, consentGiven);

            return new MergeConsentResult(audit.id, consentGiven, audit.consentTimestamp);

        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Performs the actual account merge after consent has been recorded.
     *
     * <p>
     * This method:
     * <ol>
     * <li>Validates that both users exist and consent was given</li>
     * <li>Merges anonymous user's preferences into authenticated user</li>
     * <li>Soft-deletes the anonymous user record</li>
     * <li>Updates the audit record with merged data summary</li>
     * <li>Schedules cleanup job for hard-deletion after 90 days</li>
     * </ol>
     *
     * @param auditId
     *            ID of the consent audit record
     * @return merge result with data summary
     */
    public MergeExecutionResult executeMerge(UUID auditId) {
        Span span = tracer.spanBuilder("account_merge.execute_merge").setAttribute("audit_id", auditId.toString())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            LoggingConfig.setRequestOrigin("AccountMergeService.executeMerge");

            // Load audit record
            Optional<AccountMergeAudit> auditOpt = AccountMergeAudit.findByIdOptional(auditId);
            if (auditOpt.isEmpty()) {
                throw new IllegalArgumentException("Audit record not found: " + auditId);
            }

            AccountMergeAudit audit = auditOpt.get();

            if (!audit.consentGiven) {
                throw new IllegalStateException("Cannot execute merge without user consent");
            }

            // Load users
            Optional<User> anonymousUserOpt = User.findByIdOptional(audit.anonymousUserId);
            Optional<User> authenticatedUserOpt = User.findByIdOptional(audit.authenticatedUserId);

            if (anonymousUserOpt.isEmpty()) {
                throw new IllegalStateException("Anonymous user not found: " + audit.anonymousUserId);
            }

            if (authenticatedUserOpt.isEmpty()) {
                throw new IllegalStateException("Authenticated user not found: " + audit.authenticatedUserId);
            }

            User anonymousUser = anonymousUserOpt.get();
            User authenticatedUser = authenticatedUserOpt.get();

            if (!anonymousUser.isAnonymous) {
                throw new IllegalStateException("Source user is not anonymous: " + anonymousUser.id);
            }

            if (authenticatedUser.isAnonymous) {
                throw new IllegalStateException("Target user is still anonymous: " + authenticatedUser.id);
            }

            // Perform merge in transaction
            QuarkusTransaction.requiringNew().run(() -> {
                // Merge preferences
                Map<String, Object> originalPreferences = new HashMap<>(
                        authenticatedUser.preferences != null ? authenticatedUser.preferences : Map.of());
                authenticatedUser.mergePreferences(anonymousUser.preferences);

                // Build data summary for audit
                Map<String, Object> dataSummary = new HashMap<>();
                dataSummary.put("preferences_merged",
                        anonymousUser.preferences != null ? anonymousUser.preferences.keySet() : Map.of());
                dataSummary.put("original_preferences_keys", originalPreferences.keySet());
                dataSummary.put("final_preferences_keys",
                        authenticatedUser.preferences != null ? authenticatedUser.preferences.keySet() : Map.of());
                dataSummary.put("merge_timestamp", Instant.now().toString());

                // Update audit record with merge summary
                audit.mergedDataSummary = dataSummary;
                audit.persist();

                // Soft-delete anonymous user
                anonymousUser.softDelete();

                // Persist all changes
                authenticatedUser.persist();
            });

            // Schedule cleanup job for 90-day purge
            Map<String, Object> jobPayload = Map.of("audit_id", auditId.toString(), "anonymous_user_id",
                    audit.anonymousUserId.toString(), "purge_after", audit.purgeAfter.toString());

            long jobId = delayedJobService.enqueue(JobType.ACCOUNT_MERGE_CLEANUP, jobPayload);

            span.addEvent("merge.completed",
                    Attributes.of(AttributeKey.stringKey("audit_id"), auditId.toString(),
                            AttributeKey.stringKey("anonymous_user_id"), audit.anonymousUserId.toString(),
                            AttributeKey.stringKey("authenticated_user_id"), audit.authenticatedUserId.toString(),
                            AttributeKey.longKey("cleanup_job_id"), jobId));

            LOG.infof(
                    "Executed account merge for audit %s: anonymous user %s → authenticated user %s (cleanup job: %d)",
                    auditId, audit.anonymousUserId, audit.authenticatedUserId, jobId);

            return new MergeExecutionResult(true, audit.mergedDataSummary, audit.purgeAfter);

        } catch (Exception e) {
            span.recordException(e);
            LOG.errorf(e, "Failed to execute merge for audit %s", auditId);
            throw e;

        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Convenience method for detecting and initiating merge flow during OAuth callback.
     *
     * <p>
     * This should be called from the OAuth callback handler when:
     * <ol>
     * <li>User completes OAuth login</li>
     * <li>Request contains a valid vu_anon_id cookie</li>
     * <li>No existing merge audit exists for this user pair</li>
     * </ol>
     *
     * @param anonymousUserId
     *            ID from vu_anon_id cookie
     * @param authenticatedUserId
     *            ID of newly authenticated user
     * @return true if merge opportunity detected (consent modal should be shown)
     */
    public boolean detectMergeOpportunity(UUID anonymousUserId, UUID authenticatedUserId) {
        if (anonymousUserId == null || authenticatedUserId == null) {
            return false;
        }

        // Check if anonymous user exists and is actually anonymous
        Optional<User> anonymousUserOpt = User.findByIdOptional(anonymousUserId);
        if (anonymousUserOpt.isEmpty() || !anonymousUserOpt.get().isAnonymous) {
            return false;
        }

        // Check if authenticated user exists and is authenticated
        Optional<User> authenticatedUserOpt = User.findByIdOptional(authenticatedUserId);
        if (authenticatedUserOpt.isEmpty() || authenticatedUserOpt.get().isAnonymous) {
            return false;
        }

        // Check if anonymous user has any data worth merging
        User anonymousUser = anonymousUserOpt.get();
        if (anonymousUser.preferences == null || anonymousUser.preferences.isEmpty()) {
            LOG.debugf("No merge needed for anonymous user %s - no data to merge", anonymousUserId);
            return false;
        }

        // Check if merge already exists
        java.util.List<AccountMergeAudit> existingMerges = AccountMergeAudit
                .findByAuthenticatedUser(authenticatedUserId);
        boolean alreadyMerged = existingMerges.stream()
                .anyMatch(audit -> audit.anonymousUserId.equals(anonymousUserId));

        if (alreadyMerged) {
            LOG.debugf("Merge already exists for anonymous user %s → authenticated user %s", anonymousUserId,
                    authenticatedUserId);
            return false;
        }

        LOG.infof("Detected merge opportunity: anonymous user %s → authenticated user %s", anonymousUserId,
                authenticatedUserId);
        return true;
    }

    /**
     * Result of recording merge consent.
     */
    public record MergeConsentResult(UUID auditId, boolean consentGiven, Instant consentTimestamp) {
    }

    /**
     * Result of executing account merge.
     */
    public record MergeExecutionResult(boolean success, Map<String, Object> mergedDataSummary, Instant purgeAfter) {
    }
}
