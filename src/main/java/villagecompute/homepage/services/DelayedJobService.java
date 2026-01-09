package villagecompute.homepage.services;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import villagecompute.homepage.jobs.JobHandler;
import villagecompute.homepage.jobs.JobQueue;
import villagecompute.homepage.jobs.JobType;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Central orchestrator for database-backed async job processing.
 *
 * <p>
 * This service provides the foundation for distributed job execution across the five queue families (DEFAULT, HIGH,
 * LOW, BULK, SCREENSHOT). It handles:
 * <ul>
 * <li>Job enqueuing with priority and scheduled execution</li>
 * <li>Worker polling and distributed locking via {@code locked_at}/{@code locked_by}</li>
 * <li>Retry logic with exponential backoff</li>
 * <li>OpenTelemetry instrumentation for observability</li>
 * <li>Policy enforcement (P10 budget checks, P12 concurrency limits)</li>
 * </ul>
 *
 * <p>
 * <b>Retry Strategy:</b> Failed jobs retry up to {@code max_attempts} (default 5) with exponential backoff:
 * {@code delay = (2^attempt) * base_delay_seconds} with random jitter (±25%) to prevent thundering herd. After
 * exhausting retries, jobs enter a failed state and trigger alerting per escalation policy (see
 * docs/ops/async-workloads.md).
 *
 * <p>
 * <b>Concurrency Limits (Policy P12):</b> The SCREENSHOT queue enforces a semaphore-based concurrency limit (3 workers
 * per pod) to prevent Chromium memory exhaustion. Future work may extend this pattern to other resource-constrained job
 * types.
 *
 * <p>
 * <b>Policy References:</b>
 * <ul>
 * <li>P7: Unified job orchestration framework across all queue families</li>
 * <li>P10: AI tagging jobs must check AiTaggingService budget before execution</li>
 * <li>P12: SCREENSHOT queue enforces dedicated worker pool with limited concurrency</li>
 * </ul>
 *
 * <p>
 * <b>Future Work:</b>
 * <ul>
 * <li>Integrate with {@code data.models.DelayedJob} Panache entity for database persistence</li>
 * <li>Implement Quarkus {@code @Scheduled} polling methods for each queue family</li>
 * <li>Add dead letter queue for jobs exceeding max retries</li>
 * <li>Expose metrics endpoint for job backlog/latency monitoring</li>
 * </ul>
 *
 * @see JobHandler for handler contract
 * @see JobQueue for queue family descriptions
 * @see JobType for job-to-queue mappings
 */
@ApplicationScoped
public class DelayedJobService {

    private static final Logger LOG = Logger.getLogger(DelayedJobService.class);

    /**
     * Base delay in seconds for retry backoff calculation. Actual delay = (2^attempt) * BASE_DELAY_SECONDS with ±25%
     * jitter.
     */
    private static final int BASE_DELAY_SECONDS = 30;

    /**
     * Maximum retry attempts before moving job to failed state. Configurable via application.yaml
     * (villagecompute.jobs.max-attempts).
     */
    private static final int DEFAULT_MAX_ATTEMPTS = 5;

    /**
     * Semaphore enforcing P12 concurrency limit for SCREENSHOT queue. Permits = 3 (prevents Chromium/jvppeteer memory
     * exhaustion).
     *
     * <p>
     * Future handlers can check {@code screenshotConcurrency.availablePermits()} before attempting to acquire. If
     * unavailable, job remains in ready state for next poll cycle.
     */
    private final Semaphore screenshotConcurrency = new Semaphore(3);

    /**
     * Registry mapping JobType → JobHandler for CDI-based handler discovery. Populated at application startup via
     * {@link #buildHandlerRegistry}.
     */
    private final Map<JobType, JobHandler> handlerRegistry;

    @Inject
    Tracer tracer;

    @Inject
    public DelayedJobService(Instance<JobHandler> handlers) {
        this.handlerRegistry = buildHandlerRegistry(handlers);
        LOG.infof("Initialized DelayedJobService with %d registered handlers", handlerRegistry.size());
    }

    /**
     * Discovers all CDI-managed {@link JobHandler} beans and builds a type → handler map.
     *
     * @param handlers
     *            CDI Instance providing all JobHandler implementations
     * @return EnumMap for O(1) handler lookups
     * @throws IllegalStateException
     *             if duplicate handlers register for the same JobType
     */
    private Map<JobType, JobHandler> buildHandlerRegistry(Instance<JobHandler> handlers) {
        Map<JobType, JobHandler> registry = new EnumMap<>(JobType.class);
        for (JobHandler handler : handlers) {
            JobType type = handler.handlesType();
            if (registry.containsKey(type)) {
                throw new IllegalStateException("Duplicate handlers registered for JobType." + type + ": "
                        + registry.get(type).getClass().getName() + " and " + handler.getClass().getName());
            }
            registry.put(type, handler);
            LOG.debugf("Registered handler %s for JobType.%s (queue: %s)", handler.getClass().getSimpleName(), type,
                    type.getQueue());
        }
        return registry;
    }

    /**
     * Enqueues a new job for async execution.
     *
     * <p>
     * <b>Implementation Note:</b> This is a skeleton method. Future work will:
     * <ol>
     * <li>Create a {@code DelayedJob} Panache entity with payload serialized as JSONB</li>
     * <li>Set priority based on {@code jobType.getQueue().getPriority()}</li>
     * <li>Persist to database with {@code scheduled_at} for delayed execution</li>
     * <li>Return the generated job ID for client tracking</li>
     * </ol>
     *
     * @param jobType
     *            the type of job to enqueue
     * @param payload
     *            job parameters (will be serialized as JSONB)
     * @return generated job ID (currently returns -1 as placeholder)
     */
    public long enqueue(JobType jobType, Map<String, Object> payload) {
        // TODO: Implement persistence via DelayedJob.persist()
        LOG.infof("Would enqueue JobType.%s to queue %s with payload: %s", jobType, jobType.getQueue(), payload);
        return -1L; // Placeholder
    }

    /**
     * Executes a single job by dispatching to its registered handler.
     *
     * <p>
     * This method wraps handler execution with:
     * <ul>
     * <li>OpenTelemetry span for distributed tracing</li>
     * <li>P12 semaphore acquisition for SCREENSHOT jobs</li>
     * <li>Exception handling for retry scheduling</li>
     * <li>Telemetry attributes (job.id, job.type, job.queue, job.attempt)</li>
     * </ul>
     *
     * <p>
     * <b>Implementation Note:</b> Future work will integrate with {@code DelayedJob} entity to update
     * {@code locked_at}, {@code attempts}, and {@code last_error} fields after execution.
     *
     * @param jobType
     *            the type of job to execute
     * @param jobId
     *            database primary key
     * @param payload
     *            deserialized job parameters from JSONB column
     * @param attempt
     *            current attempt number (1-indexed)
     * @throws IllegalStateException
     *             if no handler registered for jobType
     */
    public void executeJob(JobType jobType, Long jobId, Map<String, Object> payload, int attempt) {
        JobHandler handler = handlerRegistry.get(jobType);
        if (handler == null) {
            throw new IllegalStateException("No handler registered for JobType." + jobType);
        }

        Span span = tracer.spanBuilder("job.execute").setAttribute("job.id", jobId)
                .setAttribute("job.type", jobType.name()).setAttribute("job.queue", jobType.getQueue().name())
                .setAttribute("job.attempt", attempt).startSpan();

        try (Scope scope = span.makeCurrent()) {
            // P12: Enforce concurrency limit for SCREENSHOT queue
            if (jobType.getQueue() == JobQueue.SCREENSHOT) {
                LOG.debugf("Acquiring SCREENSHOT semaphore for job %d (available: %d)", (Object) jobId,
                        (Object) screenshotConcurrency.availablePermits());
                screenshotConcurrency.acquire();
            }

            handler.execute(jobId, payload);
            span.addEvent("job.completed");
            LOG.infof("Job %d (type: %s) completed successfully on attempt %d", jobId, jobType, attempt);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            span.recordException(e);
            span.addEvent("job.interrupted");
            LOG.warnf(e, "Job %d (type: %s) interrupted during execution", jobId, jobType);
            // TODO: Schedule retry with backoff

        } catch (Exception e) {
            span.recordException(e);
            span.addEvent("job.failed");
            LOG.errorf(e, "Job %d (type: %s) failed on attempt %d", jobId, jobType, attempt);
            // TODO: Calculate backoff delay and update scheduled_at for retry
            // TODO: If attempt >= max_attempts, move to failed state and trigger alert

        } finally {
            // P12: Release semaphore for SCREENSHOT queue
            if (jobType.getQueue() == JobQueue.SCREENSHOT) {
                screenshotConcurrency.release();
                LOG.debugf("Released SCREENSHOT semaphore for job %d (available: %d)", (Object) jobId,
                        (Object) screenshotConcurrency.availablePermits());
            }
            span.end();
        }
    }

    /**
     * Calculates the next retry delay using exponential backoff with jitter.
     *
     * <p>
     * <b>Formula:</b> {@code delay = (2^attempt) * BASE_DELAY_SECONDS * (1.0 ± 0.25)}
     *
     * <p>
     * Jitter prevents thundering herd when many jobs fail simultaneously (e.g., database outage).
     *
     * @param attempt
     *            current attempt number (1-indexed)
     * @return delay in seconds before next retry
     */
    public long calculateBackoffDelay(int attempt) {
        double baseDelay = Math.pow(2, attempt) * BASE_DELAY_SECONDS;
        double jitter = 0.75 + (Math.random() * 0.5); // Random multiplier in [0.75, 1.25]
        return (long) (baseDelay * jitter);
    }

    /**
     * Returns the current available permits for the SCREENSHOT queue semaphore. Exposed for monitoring and testing
     * purposes (Policy P12 compliance).
     *
     * @return number of available screenshot worker slots
     */
    public int getAvailableScreenshotSlots() {
        return screenshotConcurrency.availablePermits();
    }
}
