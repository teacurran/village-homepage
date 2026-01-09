package villagecompute.homepage.jobs;

/**
 * Defines the queue families for distributed async job processing.
 *
 * <p>Queue selection determines priority, concurrency, and resource allocation per the
 * async workload matrix. See docs/ops/async-workloads.md for SLA commitments and
 * escalation paths.
 *
 * <p><b>Policy References:</b>
 * <ul>
 *   <li>P7: Job orchestration framework across all queue families</li>
 *   <li>P12: SCREENSHOT queue enforces dedicated worker pool with limited concurrency</li>
 * </ul>
 *
 * @see JobType for job-to-queue assignments
 * @see DelayedJobService for dispatcher implementation
 */
public enum JobQueue {

    /**
     * DEFAULT queue (Blue) - Standard priority, general housekeeping.
     * <p>Handles: Feed refresh, weather refresh, listing expiration, link health checks,
     * sitemap generation, rank recalculation, inbound email parsing.
     * <p><b>SLA:</b> 95th percentile latency < 5 minutes
     * <p><b>Concurrency:</b> 10 workers per pod
     */
    DEFAULT(5, "Standard priority for periodic maintenance tasks"),

    /**
     * HIGH queue (Salmon) - Time-sensitive operations requiring rapid processing.
     * <p>Handles: Stock refresh (5min market hours), message relay, urgent notifications.
     * <p><b>SLA:</b> 95th percentile latency < 30 seconds
     * <p><b>Concurrency:</b> 20 workers per pod
     */
    HIGH(0, "High priority for time-sensitive operations"),

    /**
     * LOW queue (Green) - Background cleanup and non-urgent aggregations.
     * <p>Handles: Anonymous cleanup, profile view aggregation, metadata refresh,
     * rate limit log processing.
     * <p><b>SLA:</b> 95th percentile latency < 30 minutes
     * <p><b>Concurrency:</b> 5 workers per pod
     */
    LOW(7, "Low priority for background cleanup tasks"),

    /**
     * BULK queue (Yellow) - Resource-intensive batch operations with budget controls.
     * <p>Handles: AI tagging (P10 budget enforcement), image processing, bulk imports.
     * <p><b>SLA:</b> Best effort, may be throttled under load
     * <p><b>Concurrency:</b> 8 workers per pod
     * <p><b>Policy P10:</b> AI tagging jobs must check budget ceiling ($500/month) before execution
     */
    BULK(8, "Bulk processing with cost/resource controls"),

    /**
     * SCREENSHOT queue (Lavender) - Dedicated Puppeteer pool for web captures.
     * <p>Handles: Good Sites screenshot generation, preview captures.
     * <p><b>SLA:</b> 95th percentile latency < 2 minutes
     * <p><b>Concurrency:</b> 3 workers per pod (P12 enforcement via semaphore)
     * <p><b>Policy P12:</b> Limited concurrency to prevent Chromium memory exhaustion
     */
    SCREENSHOT(6, "Dedicated queue for browser-based captures (P12)");

    private final int priority;
    private final String description;

    JobQueue(int priority, String description) {
        this.priority = priority;
        this.description = description;
    }

    /**
     * Returns the execution priority (lower values = higher priority).
     * Used for database sorting when multiple queues compete for worker slots.
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Returns a human-readable description of the queue's purpose.
     */
    public String getDescription() {
        return description;
    }
}
