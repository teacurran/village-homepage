package villagecompute.homepage.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.jboss.logging.MDC;

/**
 * Central configuration and utilities for structured logging with observability context.
 *
 * <p>
 * This class defines standard MDC field names and provides helper methods for enriching logs with contextual metadata
 * required by the observability blueprint (Section 3.1). All log entries should include trace IDs, user identifiers,
 * feature flags, and request origin when available.
 *
 * <p>
 * <b>Standard Log Fields (per Section 3.1):</b>
 * <ul>
 * <li>{@code trace_id} - OpenTelemetry trace identifier for distributed tracing</li>
 * <li>{@code span_id} - Current span identifier within the trace</li>
 * <li>{@code user_id} - Authenticated user primary key (null for anonymous)</li>
 * <li>{@code anon_id} - Anonymous session identifier (UUID, null for authenticated)</li>
 * <li>{@code feature_flags} - Comma-separated list of active feature flags for this request</li>
 * <li>{@code request_origin} - HTTP request path or job type identifier</li>
 * <li>{@code rate_limit_bucket} - Rate limiting bucket key (user tier + action type)</li>
 * <li>{@code job_id} - Delayed job primary key (only for async job execution)</li>
 * </ul>
 *
 * <p>
 * <b>Usage in HTTP Filters:</b>
 *
 * <pre>
 * LoggingConfig.enrichWithTraceContext();
 * LoggingConfig.setUserId(authenticatedUserId);
 * LoggingConfig.setRequestOrigin(requestPath);
 * </pre>
 *
 * <p>
 * <b>Usage in Job Handlers:</b>
 *
 * <pre>
 * LoggingConfig.enrichWithTraceContext();
 * LoggingConfig.setJobId(jobId);
 * LoggingConfig.setRequestOrigin("JobType." + jobType.name());
 * </pre>
 *
 * <p>
 * <b>Thread Safety:</b> All methods operate on {@link MDC}, which uses ThreadLocal storage. Each request/job execution
 * should clear MDC at the end of processing to prevent context leakage.
 *
 * @see io.opentelemetry.api.trace.Span for trace context extraction
 * @see LoggingEnricher for automatic HTTP request enrichment
 */
public final class LoggingConfig {

    /**
     * OpenTelemetry trace identifier (hexadecimal string, 32 characters). Links log entries to distributed traces in
     * Jaeger.
     */
    public static final String MDC_TRACE_ID = "trace_id";

    /**
     * OpenTelemetry span identifier (hexadecimal string, 16 characters). Identifies the specific operation within a
     * trace.
     */
    public static final String MDC_SPAN_ID = "span_id";

    /**
     * Authenticated user primary key (Long as String). Null for anonymous sessions.
     */
    public static final String MDC_USER_ID = "user_id";

    /**
     * Anonymous session identifier (UUID string). Null for authenticated users. Generated at session creation and
     * stored in cookie.
     */
    public static final String MDC_ANON_ID = "anon_id";

    /**
     * Comma-separated list of active feature flags for this request (e.g., "stocks_widget,social_integration"). Used
     * for A/B testing correlation and debugging flag-specific issues.
     */
    public static final String MDC_FEATURE_FLAGS = "feature_flags";

    /**
     * HTTP request path (e.g., "/api/widgets") or job type identifier (e.g., "JobType.FEED_REFRESH"). Provides context
     * for log entry origin.
     */
    public static final String MDC_REQUEST_ORIGIN = "request_origin";

    /**
     * Rate limiting bucket key (e.g., "user:42:widget_create" or "anon:uuid:listing_view"). Used to correlate
     * rate-limit violations with specific users/actions.
     */
    public static final String MDC_RATE_LIMIT_BUCKET = "rate_limit_bucket";

    /**
     * Delayed job primary key (Long as String). Only present for async job execution logs. Links log entries to job
     * lifecycle events.
     */
    public static final String MDC_JOB_ID = "job_id";

    private LoggingConfig() {
        // Utility class, no instantiation
    }

    /**
     * Enriches MDC with trace_id and span_id from the current OpenTelemetry span.
     *
     * <p>
     * This method should be called at the start of every HTTP request (via filter) and job execution (via
     * DelayedJobService). If no active span exists, the fields are set to empty strings to maintain consistent log
     * structure.
     *
     * <p>
     * <b>Implementation Note:</b> Uses {@link Span#current()} to access thread-local span context. Quarkus
     * automatically propagates trace context across async boundaries when using managed executors.
     */
    public static void enrichWithTraceContext() {
        Span currentSpan = Span.current();
        SpanContext spanContext = currentSpan.getSpanContext();

        if (spanContext.isValid()) {
            MDC.put(MDC_TRACE_ID, spanContext.getTraceId());
            MDC.put(MDC_SPAN_ID, spanContext.getSpanId());
        } else {
            // Set empty strings to maintain consistent JSON schema
            MDC.put(MDC_TRACE_ID, "");
            MDC.put(MDC_SPAN_ID, "");
        }
    }

    /**
     * Sets the authenticated user ID in MDC and clears any anonymous ID.
     *
     * @param userId
     *            authenticated user primary key
     */
    public static void setUserId(Long userId) {
        if (userId != null) {
            MDC.put(MDC_USER_ID, userId.toString());
            MDC.remove(MDC_ANON_ID);
        }
    }

    /**
     * Sets the anonymous session ID in MDC and clears any user ID.
     *
     * @param anonId
     *            anonymous session UUID
     */
    public static void setAnonId(String anonId) {
        if (anonId != null) {
            MDC.put(MDC_ANON_ID, anonId);
            MDC.remove(MDC_USER_ID);
        }
    }

    /**
     * Sets the active feature flags for this request as a comma-separated list.
     *
     * @param featureFlags
     *            comma-separated flag names (e.g., "stocks_widget,social_integration")
     */
    public static void setFeatureFlags(String featureFlags) {
        if (featureFlags != null && !featureFlags.isEmpty()) {
            MDC.put(MDC_FEATURE_FLAGS, featureFlags);
        }
    }

    /**
     * Sets the request origin (HTTP path or job type identifier).
     *
     * @param requestOrigin
     *            path like "/api/widgets" or "JobType.FEED_REFRESH"
     */
    public static void setRequestOrigin(String requestOrigin) {
        if (requestOrigin != null) {
            MDC.put(MDC_REQUEST_ORIGIN, requestOrigin);
        }
    }

    /**
     * Sets the rate limiting bucket key for correlation with rate-limit violations.
     *
     * @param rateLimitBucket
     *            bucket identifier like "user:42:widget_create"
     */
    public static void setRateLimitBucket(String rateLimitBucket) {
        if (rateLimitBucket != null) {
            MDC.put(MDC_RATE_LIMIT_BUCKET, rateLimitBucket);
        }
    }

    /**
     * Sets the delayed job ID for async job execution logs.
     *
     * @param jobId
     *            delayed job primary key
     */
    public static void setJobId(Long jobId) {
        if (jobId != null) {
            MDC.put(MDC_JOB_ID, jobId.toString());
        }
    }

    /**
     * Clears all observability-related MDC fields. Should be called at the end of every request/job to prevent context
     * leakage across thread reuse.
     *
     * <p>
     * <b>Critical:</b> Failure to clear MDC can cause logs from one request to contain stale data from previous
     * requests served by the same thread.
     */
    public static void clearMDC() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_SPAN_ID);
        MDC.remove(MDC_USER_ID);
        MDC.remove(MDC_ANON_ID);
        MDC.remove(MDC_FEATURE_FLAGS);
        MDC.remove(MDC_REQUEST_ORIGIN);
        MDC.remove(MDC_RATE_LIMIT_BUCKET);
        MDC.remove(MDC_JOB_ID);
    }
}
