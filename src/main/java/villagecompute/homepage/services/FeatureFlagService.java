package villagecompute.homepage.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import villagecompute.homepage.data.models.FeatureFlag;
import villagecompute.homepage.data.models.FeatureFlagAudit;
import villagecompute.homepage.observability.LoggingConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Central feature flag evaluation and management service (Policy P7 compliance).
 *
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Stable cohort evaluation using MD5 hashing of flag key + subject identifier</li>
 * <li>Whitelist override support for forced enablement</li>
 * <li>Consent-aware evaluation logging (Policy P14)</li>
 * <li>Admin CRUD operations with synchronous audit trail</li>
 * </ul>
 *
 * <p>
 * <b>Cohort Stability:</b> Uses MD5(flagKey + ":" + subjectId) mod 100 to assign users to stable cohorts. This ensures
 * a user always sees the same feature state across sessions unless rollout percentage changes.
 *
 * <p>
 * <b>Evaluation Priority:</b>
 * <ol>
 * <li>If flag disabled → return false</li>
 * <li>If subject in whitelist → return true</li>
 * <li>If rollout_percentage == 100 → return true</li>
 * <li>Else → compute cohort and compare to rollout_percentage</li>
 * </ol>
 *
 * <p>
 * <b>Privacy Compliance:</b> Evaluation logs are only persisted if consent is granted (Policy P14). Anonymous sessions
 * use session hashes instead of user IDs. Logs partition by month for efficient 90-day purging.
 */
@ApplicationScoped
public class FeatureFlagService {

    private static final Logger LOG = Logger.getLogger(FeatureFlagService.class);

    @Inject
    Tracer tracer;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Evaluates a feature flag for a subject (user or anonymous session).
     *
     * @param flagKey
     *            the feature flag identifier
     * @param userId
     *            authenticated user ID (null for anonymous)
     * @param sessionHash
     *            anonymous session hash (null for authenticated)
     * @param consentGranted
     *            whether user has consented to analytics logging
     * @return evaluation result with flag state and reason
     */
    @Transactional
    public EvaluationResult evaluateFlag(String flagKey, Long userId, String sessionHash, boolean consentGranted) {
        Objects.requireNonNull(flagKey, "flagKey is required");

        Span span = tracer.spanBuilder("feature_flag.evaluate").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            span.setAttribute("flag_key", flagKey);

            String subjectType;
            String subjectId;
            if (userId != null) {
                subjectType = "user";
                subjectId = userId.toString();
                LoggingConfig.setUserId(userId);
            } else if (sessionHash != null) {
                subjectType = "session";
                subjectId = sessionHash;
                LoggingConfig.setAnonId(sessionHash);
            } else {
                LOG.warnf("Flag evaluation missing subject identity for flag=%s", flagKey);
                return new EvaluationResult(false, "missing_subject", (short) 0);
            }

            span.setAttribute("subject_type", subjectType);

            Optional<FeatureFlag> flagOpt = FeatureFlag.findByKey(flagKey);
            if (flagOpt.isEmpty()) {
                span.addEvent("flag.not_found");
                return new EvaluationResult(false, "flag_not_found", (short) 0);
            }

            FeatureFlag flag = flagOpt.get();
            span.setAttribute("enabled", flag.enabled);
            span.setAttribute("rollout_percentage", flag.rolloutPercentage);

            // Priority 1: Master kill switch
            if (!flag.enabled) {
                logEvaluation(flagKey, subjectType, subjectId, false, consentGranted, flag.rolloutPercentage,
                        "master_disabled", flag.analyticsEnabled);
                return new EvaluationResult(false, "master_disabled", flag.rolloutPercentage);
            }

            // Priority 2: Whitelist override
            if (flag.isWhitelisted(subjectId)) {
                span.addEvent("flag.whitelisted");
                logEvaluation(flagKey, subjectType, subjectId, true, consentGranted, flag.rolloutPercentage,
                        "whitelisted", flag.analyticsEnabled);
                return new EvaluationResult(true, "whitelisted", flag.rolloutPercentage);
            }

            // Priority 3: Full rollout
            if (flag.rolloutPercentage >= 100) {
                logEvaluation(flagKey, subjectType, subjectId, true, consentGranted, flag.rolloutPercentage,
                        "full_rollout", flag.analyticsEnabled);
                return new EvaluationResult(true, "full_rollout", flag.rolloutPercentage);
            }

            // Priority 4: Zero rollout
            if (flag.rolloutPercentage <= 0) {
                logEvaluation(flagKey, subjectType, subjectId, false, consentGranted, flag.rolloutPercentage,
                        "zero_rollout", flag.analyticsEnabled);
                return new EvaluationResult(false, "zero_rollout", flag.rolloutPercentage);
            }

            // Priority 5: Stable cohort evaluation
            int cohort = computeCohort(flagKey, subjectId);
            boolean enabled = cohort < flag.rolloutPercentage;
            String reason = enabled ? "cohort_enabled" : "cohort_disabled";

            span.setAttribute("cohort", cohort);
            span.setAttribute("result", enabled);

            logEvaluation(flagKey, subjectType, subjectId, enabled, consentGranted, flag.rolloutPercentage, reason,
                    flag.analyticsEnabled);
            return new EvaluationResult(enabled, reason, flag.rolloutPercentage);
        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Retrieves all feature flags (admin view).
     *
     * @return list of all flags
     */
    public List<FeatureFlag> getAllFlags() {
        Span span = tracer.spanBuilder("feature_flag.get_all").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return FeatureFlag.findAllFlags();
        } finally {
            span.end();
        }
    }

    /**
     * Retrieves a single feature flag by key.
     *
     * @param flagKey
     *            the flag identifier
     * @return Optional containing the flag if found
     */
    public Optional<FeatureFlag> getFlag(String flagKey) {
        return FeatureFlag.findByKey(flagKey);
    }

    /**
     * Updates a feature flag configuration with audit logging.
     *
     * @param flagKey
     *            the flag to update
     * @param description
     *            new description (null to keep current)
     * @param enabled
     *            new enabled state (null to keep current)
     * @param rolloutPercentage
     *            new rollout percentage (null to keep current)
     * @param whitelist
     *            new whitelist (null to keep current)
     * @param analyticsEnabled
     *            new analytics toggle (null to keep current)
     * @param actorId
     *            admin user performing the update
     * @param reason
     *            optional explanation for the change
     * @return the updated flag
     */
    @Transactional
    public FeatureFlag updateFlag(String flagKey, String description, Boolean enabled, Short rolloutPercentage,
            List<String> whitelist, Boolean analyticsEnabled, Long actorId, String reason) {

        Objects.requireNonNull(flagKey, "flagKey is required");

        Span span = tracer.spanBuilder("feature_flag.update").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggingConfig.enrichWithTraceContext();
            span.setAttribute("flag_key", flagKey);

            Optional<FeatureFlag> flagOpt = FeatureFlag.findByKey(flagKey);
            if (flagOpt.isEmpty()) {
                span.addEvent("flag.not_found");
                throw new IllegalArgumentException("Feature flag not found: " + flagKey);
            }

            FeatureFlag flag = flagOpt.get();
            String beforeState = flag.toJson(objectMapper);

            flag.update(description, enabled, rolloutPercentage, whitelist, analyticsEnabled);
            flag.persist();

            String afterState = flag.toJson(objectMapper);
            String traceId = span.getSpanContext().getTraceId();

            String actorType = actorId == null ? "system" : "admin";
            FeatureFlagAudit.recordMutation(flagKey, actorId, actorType, "update", beforeState, afterState, reason,
                    traceId);

            String actorValue = actorId != null ? actorId.toString() : "system";
            span.addEvent("flag.updated", Attributes.of(AttributeKey.stringKey("actor_id"), actorValue));
            LOG.infof("Feature flag updated: %s by actor=%s", flagKey, actorValue);
            return flag;
        } finally {
            LoggingConfig.clearMDC();
            span.end();
        }
    }

    /**
     * Computes stable cohort assignment using MD5 hash.
     *
     * <p>
     * Algorithm:
     * <ol>
     * <li>Concatenate flagKey + ":" + subjectId</li>
     * <li>Compute MD5 hash of concatenated string</li>
     * <li>Convert first 4 bytes of hash to unsigned integer</li>
     * <li>Return (hash mod 100) to get value in range [0-99]</li>
     * </ol>
     *
     * @param flagKey
     *            the feature flag identifier
     * @param subjectId
     *            user ID or session hash
     * @return cohort value [0-99]
     */
    private int computeCohort(String flagKey, String subjectId) {
        try {
            String input = flagKey + ":" + subjectId;
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] hash = md5.digest(input.getBytes(StandardCharsets.UTF_8));

            // Convert first 4 bytes to unsigned integer (avoids Math.abs(Integer.MIN_VALUE))
            int hashInt = ((hash[0] & 0xFF) << 24) | ((hash[1] & 0xFF) << 16) | ((hash[2] & 0xFF) << 8)
                    | (hash[3] & 0xFF);
            long unsignedValue = hashInt & 0xFFFFFFFFL;

            // Map to [0-99] deterministically
            return (int) (unsignedValue % 100);
        } catch (NoSuchAlgorithmException e) {
            LOG.error("MD5 algorithm not available - falling back to default cohort 0", e);
            return 0;
        }
    }

    /**
     * Logs feature flag evaluation to the partitioned evaluation table.
     *
     * <p>
     * Only logs if:
     * <ul>
     * <li>Consent is granted (Policy P14)</li>
     * <li>Analytics is enabled for this flag</li>
     * </ul>
     *
     * @param flagKey
     *            the evaluated flag
     * @param subjectType
     *            'user' or 'session'
     * @param subjectId
     *            user ID or session hash
     * @param result
     *            evaluation outcome (true/false)
     * @param consentGranted
     *            whether user has consented to analytics
     * @param rolloutPercentage
     *            snapshot of rollout percentage at evaluation time
     * @param reason
     *            evaluation reason code
     * @param analyticsEnabled
     *            flag-level analytics toggle
     */
    private void logEvaluation(String flagKey, String subjectType, String subjectId, boolean result,
            boolean consentGranted, short rolloutPercentage, String reason, boolean analyticsEnabled) {

        if (!consentGranted || !analyticsEnabled) {
            return; // Skip logging per Policy P14
        }

        try {
            String traceId = Span.current().getSpanContext().getTraceId();
            String sql = """
                    INSERT INTO feature_flag_evaluations
                    (flag_key, subject_type, subject_id, result, consent_granted,
                     rollout_percentage_snapshot, evaluation_reason, trace_id, timestamp)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;

            FeatureFlag.getEntityManager().createNativeQuery(sql).setParameter(1, flagKey).setParameter(2, subjectType)
                    .setParameter(3, subjectId).setParameter(4, result).setParameter(5, consentGranted)
                    .setParameter(6, rolloutPercentage).setParameter(7, reason).setParameter(8, traceId)
                    .setParameter(9, Instant.now()).executeUpdate();
        } catch (Exception e) {
            LOG.warnf("Failed to log feature flag evaluation for %s: %s", flagKey, e.getMessage());
        }
    }

    /**
     * Evaluation result record containing flag state and metadata.
     *
     * @param enabled
     *            whether the flag is enabled for the subject
     * @param reason
     *            evaluation reason (master_disabled, whitelisted, cohort_enabled, etc.)
     * @param rolloutPercentage
     *            snapshot of rollout percentage at evaluation time
     */
    public record EvaluationResult(boolean enabled, String reason, short rolloutPercentage) {
    }
}
