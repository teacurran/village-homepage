package villagecompute.homepage.jobs;

import java.util.Map;

/**
 * Contract for async job handler implementations.
 *
 * <p>Handlers must be CDI-managed beans annotated with {@code @ApplicationScoped} and implement
 * this interface. The {@link DelayedJobService} discovers handlers at runtime and routes jobs
 * based on their {@link JobType}.
 *
 * <p><b>Execution Model:</b>
 * <ul>
 *   <li>Handlers execute in worker threads managed by the Quarkus scheduler</li>
 *   <li>Failed jobs retry with exponential backoff per retry policy (see async-workloads.md)</li>
 *   <li>OpenTelemetry spans automatically wrap handler execution for observability</li>
 * </ul>
 *
 * <p><b>Example Implementation:</b>
 * <pre>{@code
 * @ApplicationScoped
 * public class RssFeedRefreshHandler implements JobHandler {
 *     @Override
 *     public JobType handlesType() {
 *         return JobType.RSS_FEED_REFRESH;
 *     }
 *
 *     @Override
 *     public void execute(Long jobId, Map<String, Object> payload) throws Exception {
 *         Long feedId = (Long) payload.get("feedId");
 *         // Fetch and parse RSS feed...
 *     }
 * }
 * }</pre>
 *
 * <p><b>Policy References:</b>
 * <ul>
 *   <li>P7: Handlers participate in unified job orchestration framework</li>
 *   <li>P10: AI tagging handlers must check budget before LangChain4j calls</li>
 *   <li>P12: Screenshot handlers must respect semaphore-limited concurrency</li>
 * </ul>
 *
 * @see DelayedJobService for dispatcher implementation
 * @see JobType for supported job types
 */
public interface JobHandler {

    /**
     * Returns the job type this handler processes.
     *
     * <p>The {@link DelayedJobService} uses this method to build a registry of
     * type → handler mappings at application startup.
     *
     * @return the job type enum value
     */
    JobType handlesType();

    /**
     * Executes the job with the given payload.
     *
     * <p><b>Thread Safety:</b> This method may be called concurrently by multiple worker threads.
     * Implementations must be thread-safe or use external synchronization.
     *
     * <p><b>Error Handling:</b> Thrown exceptions trigger retry logic per the job's
     * {@code max_attempts} and exponential backoff strategy. After exhausting retries,
     * jobs move to a failed state and trigger alerting per escalation policy.
     *
     * <p><b>Telemetry:</b> The following OpenTelemetry attributes are automatically recorded:
     * <ul>
     *   <li>{@code job.id} - Database primary key</li>
     *   <li>{@code job.type} - JobType enum name</li>
     *   <li>{@code job.queue} - JobQueue enum name</li>
     *   <li>{@code job.attempt} - Current attempt number (1-indexed)</li>
     * </ul>
     *
     * @param jobId the database primary key from {@code delayed_jobs.id}
     * @param payload deserialized job parameters (stored as JSONB in database)
     * @throws Exception any error during execution; triggers retry logic
     */
    void execute(Long jobId, Map<String, Object> payload) throws Exception;
}
