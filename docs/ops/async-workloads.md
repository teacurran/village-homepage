# Async Workload Strategy

**Version:** 1.0
**Last Updated:** 2026-01-08
**Owner:** Platform Engineering

This document defines the async job architecture for Village Homepage, including queue strategy, retry policies, concurrency controls, and escalation paths. All code artifacts reference this document as the source of truth for operational behavior.

---

## 1. Queue Families

Village Homepage uses five queue families for prioritized job routing. Each queue has dedicated worker threads, SLA commitments, and failure escalation paths.

### Queue Matrix

| Queue | Priority | Color | Concurrency | SLA (P95 Latency) | Use Cases |
|-------|----------|-------|-------------|-------------------|-----------|
| **HIGH** | 0 | Salmon | 20 workers/pod | < 30 seconds | Stock refresh (market hours), message relay, urgent notifications |
| **DEFAULT** | 5 | Blue | 10 workers/pod | < 5 minutes | Feed refresh, weather refresh, listing expiration, rank recalculation, inbound email |
| **SCREENSHOT** | 6 | Lavender | 3 workers/pod (P12) | < 2 minutes | Good Sites screenshot capture via jvppeteer/Chromium |
| **LOW** | 7 | Green | 5 workers/pod | < 30 minutes | Social refresh, link health checks, sitemap generation, click rollup |
| **BULK** | 8 | Yellow | 8 workers/pod | Best effort | AI tagging (P10 budget enforced), image processing, bulk imports |

**Policy References:**
- **P7:** Unified job orchestration framework across all queue families
- **P12:** SCREENSHOT queue enforces semaphore-limited concurrency (3 workers) to prevent Chromium memory exhaustion

---

## 2. Job Type Registry

All async workloads are enumerated in the `JobType` enum. Each type is permanently assigned to one queue family.

| Job Type | Queue | Cadence | Handler Class | Policy Notes |
|----------|-------|---------|---------------|--------------|
| `RSS_FEED_REFRESH` | DEFAULT | 15min-daily (configurable) | `RssFeedRefreshHandler` | — |
| `WEATHER_REFRESH` | DEFAULT | 1 hour | `WeatherRefreshHandler` | — |
| `LISTING_EXPIRATION` | DEFAULT | Daily @ 2am UTC | `ListingExpirationHandler` | — |
| `RANK_RECALCULATION` | DEFAULT | Hourly | `RankRecalculationHandler` | — |
| `INBOUND_EMAIL` | DEFAULT | 1 minute | `InboundEmailHandler` | IMAP polling for marketplace relay |
| `STOCK_REFRESH` | HIGH | 5 minutes (9:30am-4pm ET) | `StockRefreshHandler` | Alpha Vantage API integration |
| `MESSAGE_RELAY` | HIGH | On-demand | `MessageRelayHandler` | Marketplace inquiry relay with email masking |
| `SOCIAL_REFRESH` | LOW | 30 minutes | `SocialRefreshHandler` | P5/P13: Secure token storage required |
| `LINK_HEALTH_CHECK` | LOW | Weekly | `LinkHealthCheckHandler` | Detects dead links in Good Sites directory |
| `SITEMAP_GENERATION` | LOW | Daily @ 3am UTC | `SitemapGenerationHandler` | SEO sitemap XML generation |
| `CLICK_ROLLUP` | LOW | Hourly | `ClickRollupHandler` | P14: Consent-gated, 90-day retention |
| `AI_TAGGING` | BULK | On-demand (batch) | `AiTaggingHandler` | P10: Check $500/month budget ceiling before execution |
| `IMAGE_PROCESSING` | BULK | On-demand (triggered by upload) | `ImageProcessingHandler` | Resize/optimize marketplace listing images |
| `SCREENSHOT_CAPTURE` | SCREENSHOT | On-demand (triggered by site submission) | `ScreenshotCaptureHandler` | P12: Dedicated worker pool, semaphore-limited to 3 concurrent jobs |

---

## 3. Retry & Backoff Strategy

### Retry Logic

Jobs automatically retry on failure using an exponential backoff strategy with jitter to prevent thundering herd scenarios.

**Formula:**
```
delay_seconds = (2^attempt) × BASE_DELAY × jitter
```

Where:
- `BASE_DELAY` = 30 seconds (configurable via `villagecompute.jobs.base-delay`)
- `jitter` = random multiplier in range [0.75, 1.25]
- `attempt` = 1-indexed attempt counter (1, 2, 3, ...)

**Example Delays (with jitter range):**
- Attempt 1: 23-38 seconds
- Attempt 2: 45-75 seconds
- Attempt 3: 90-150 seconds (2.5 minutes)
- Attempt 4: 180-300 seconds (5 minutes)
- Attempt 5: 360-600 seconds (10 minutes)

### Max Attempts

Default: **5 attempts** per job (configurable via `villagecompute.jobs.max-attempts`)

After exhausting retries, the job moves to `FAILED` state and triggers escalation per the queue's escalation policy (see Section 5).

### Jitter Rationale

Random jitter prevents synchronized retry storms when many jobs fail simultaneously (e.g., database outage, external API downtime). Without jitter, all failed jobs would retry at the exact same intervals, causing load spikes.

---

## 4. Concurrency Controls

### General Concurrency

Each queue family has a fixed number of worker threads per pod (see Queue Matrix). Workers poll the database using `SELECT ... FOR UPDATE SKIP LOCKED` to ensure lock-free distributed coordination.

**Worker Poll Cadence:**
- HIGH: every 5 seconds
- DEFAULT: every 10 seconds
- SCREENSHOT: every 10 seconds
- LOW: every 30 seconds
- BULK: every 60 seconds

### Policy P12: SCREENSHOT Queue Semaphore

The SCREENSHOT queue enforces an additional semaphore limit of **3 concurrent jobs** per pod to prevent Chromium/jvppeteer memory exhaustion.

**Implementation Details:**
- `DelayedJobService.screenshotConcurrency` = `Semaphore(3)`
- Workers acquire permit before executing `ScreenshotCaptureHandler`
- If semaphore pool exhausted, worker releases database lock and retries in next poll cycle
- Available slots exposed via `DelayedJobService.getAvailableScreenshotSlots()` for monitoring

**Why 3 Workers?**
- Each Chromium instance consumes ~200-400MB RAM under typical webpage loads
- 3 concurrent instances ≈ 1.2GB max, leaving headroom for JVM + Quarkus runtime
- Tunable via `villagecompute.jobs.screenshot-concurrency` if container resources change

---

## 5. Escalation Paths

When jobs exhaust all retry attempts, the system triggers queue-specific escalation actions.

| Queue | Escalation Action | Notification Channel | Response SLA |
|-------|-------------------|----------------------|--------------|
| **HIGH** | Immediate alert | PagerDuty | 15 minutes (on-call) |
| **DEFAULT** | Slack notification | #ops-alerts channel | 4 hours (business hours) |
| **SCREENSHOT** | Slack notification | #ops-alerts channel | 4 hours (business hours) |
| **LOW** | Email digest (daily) | ops@villagecompute.com | Next business day |
| **BULK** | Email digest (daily) | ops@villagecompute.com | Next business day |

**Example Scenarios:**

1. **Stock Refresh Failure (HIGH queue):**
   - Alpha Vantage API down for >10 minutes
   - After 5 failed attempts, PagerDuty alert fires
   - On-call engineer investigates API status, switches to fallback data source if needed

2. **AI Tagging Budget Exceeded (BULK queue):**
   - Monthly spend hits $500 ceiling (Policy P10)
   - Jobs fail immediately without retries (budget check before execution)
   - Daily digest notifies ops team to review usage/increase budget

3. **Screenshot Capture Timeout (SCREENSHOT queue):**
   - Target website unresponsive or Chromium crashes
   - After 5 retries (10 minutes total), job marked FAILED
   - Slack notification posted with URL for manual review

---

## 6. Telemetry & Observability

All job executions emit OpenTelemetry spans and metrics for monitoring and debugging.

### OpenTelemetry Span Attributes

Every job execution includes the following span attributes:

| Attribute | Type | Description |
|-----------|------|-------------|
| `job.id` | long | Database primary key from `delayed_jobs.id` |
| `job.type` | string | JobType enum name (e.g., `RSS_FEED_REFRESH`) |
| `job.queue` | string | JobQueue enum name (e.g., `DEFAULT`) |
| `job.attempt` | int | Current attempt number (1-indexed) |
| `job.handler` | string | Fully-qualified handler class name |

### Span Events

- `job.completed` - Successful execution
- `job.failed` - Exception thrown during execution
- `job.retry_scheduled` - Retry scheduled with backoff delay
- `job.exhausted` - Max attempts exceeded, escalation triggered
- `job.interrupted` - Worker thread interrupted (graceful shutdown)

### Metrics

The following metrics are exported for Prometheus scraping:

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `job_execution_duration_seconds` | Histogram | `job_type`, `queue`, `status` | Job execution time (success/failure) |
| `job_queue_backlog_total` | Gauge | `queue` | Number of jobs waiting execution |
| `job_retry_total` | Counter | `job_type`, `queue` | Total retry count by type and queue |
| `screenshot_concurrency_utilization` | Gauge | — | Screenshot semaphore utilization (0-3, Policy P12) |
| `job_budget_consumed_dollars` | Counter | `job_type` | AI tagging cost tracking (Policy P10) |

### Example Grafana Queries

**Queue Backlog Over Time:**
```promql
sum by (queue) (job_queue_backlog_total)
```

**P95 Execution Latency by Queue:**
```promql
histogram_quantile(0.95, sum by (queue, le) (rate(job_execution_duration_seconds_bucket[5m])))
```

**Screenshot Concurrency Utilization (P12):**
```promql
screenshot_concurrency_utilization / 3 * 100  # Percentage of 3-worker pool
```

---

## 7. Database Schema

Jobs are persisted in the `delayed_jobs` table. Key columns:

| Column | Type | Description |
|--------|------|-------------|
| `id` | BIGSERIAL | Primary key |
| `queue` | VARCHAR(32) | JobQueue enum name |
| `job_type` | VARCHAR(64) | JobType enum name |
| `priority` | INTEGER | Lower = higher priority (0-10) |
| `payload` | JSONB | Job parameters (deserialized to `Map<String, Object>`) |
| `scheduled_at` | TIMESTAMP | Earliest execution time (for delayed/retry jobs) |
| `locked_at` | TIMESTAMP | Worker lock timestamp (null = available) |
| `locked_by` | VARCHAR(128) | Worker pod identifier (hostname) |
| `attempts` | INTEGER | Retry counter (1-indexed) |
| `max_attempts` | INTEGER | Retry limit (default 5) |
| `last_error` | TEXT | Exception message from last failure |
| `created_at` | TIMESTAMP | Enqueue timestamp |

**Indexes:**
- `(queue, priority, scheduled_at)` - Worker polling query
- `(locked_at, scheduled_at)` - Unlocked job detection
- `(job_type)` - Metrics queries

See `docs/diagrams/erd-guide.md` Section 6.1 for full schema details.

---

## 8. Configuration Properties

Job system behavior is controlled via `application.yaml`:

```yaml
villagecompute:
  jobs:
    # Retry configuration
    base-delay: 30                # Base delay for backoff calculation (seconds)
    max-attempts: 5               # Default retry limit

    # Worker concurrency (per pod)
    workers:
      high: 20
      default: 10
      screenshot: 3               # P12: Semaphore-limited for Chromium memory
      low: 5
      bulk: 8

    # Worker poll cadence (seconds)
    poll-interval:
      high: 5
      default: 10
      screenshot: 10
      low: 30
      bulk: 60

    # Budget enforcement (Policy P10)
    ai-tagging:
      monthly-budget: 500.00      # USD ceiling for LangChain4j costs

    # Escalation configuration
    escalation:
      pagerduty-key: ${PAGERDUTY_INTEGRATION_KEY}
      slack-webhook: ${SLACK_OPS_WEBHOOK}
      ops-email: ops@villagecompute.com
```

---

## 9. Handler Implementation Guide

To implement a new job handler:

1. **Create Handler Class** under `villagecompute.homepage.jobs` package:

```java
package villagecompute.homepage.jobs;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@ApplicationScoped
public class MyCustomHandler implements JobHandler {

    @Override
    public JobType handlesType() {
        return JobType.MY_CUSTOM_JOB;  // Add to JobType enum first
    }

    @Override
    public void execute(Long jobId, Map<String, Object> payload) throws Exception {
        // Extract parameters from payload
        String param = (String) payload.get("someParam");

        // Implement business logic
        // ...

        // Throw exception to trigger retry
        // Return normally to mark job completed
    }
}
```

2. **Add Job Type** to `JobType` enum with queue assignment:

```java
MY_CUSTOM_JOB(JobQueue.DEFAULT, "Description with cadence")
```

3. **Configure Scheduler** (if periodic execution needed):

```java
@ApplicationScoped
public class MyJobScheduler {

    @Inject
    DelayedJobService jobService;

    @Scheduled(every = "1h")
    void scheduleMyJob() {
        Map<String, Object> payload = Map.of("someParam", "value");
        jobService.enqueue(JobType.MY_CUSTOM_JOB, payload);
    }
}
```

4. **Write Tests** verifying handler logic and retry behavior.

---

## 10. Future Enhancements

### Planned Improvements

1. **Dead Letter Queue:**
   - Move permanently failed jobs to separate `delayed_jobs_dlq` table
   - Admin UI for manual retry or job inspection

2. **Dynamic Concurrency Tuning:**
   - Auto-adjust worker pool sizes based on queue backlog and P95 latency
   - Kubernetes HPA integration for pod autoscaling

3. **Job Chaining:**
   - Support for workflow DAGs (e.g., screenshot capture → image compression → CDN upload)
   - Parent-child job relationships with atomic rollback

4. **Budget Alerts:**
   - Proactive notifications when AI tagging spend reaches 80% of monthly budget (P10)
   - Cost breakdown by job type and originating user

5. **Priority Boosting:**
   - Automatic priority escalation for jobs sitting in queue >10 minutes
   - Admin API for manual job prioritization

---

## 11. Policy Cross-References

This document implements the following architectural policies:

- **P7:** Job orchestration framework (all sections)
- **P10:** AI tagging budget enforcement (Sections 2, 6, 8)
- **P12:** SCREENSHOT queue concurrency limits (Sections 1, 4)
- **P14:** Click tracking consent gating (Section 2)

For full policy definitions, see `docs/diagrams/README.md` and `docs/diagrams/container.puml`.

---

## Appendix: Sequence Diagram

See `docs/diagrams/job-dispatch-sequence.puml` for a detailed PlantUML sequence diagram illustrating:
- Job enqueue flow
- Worker polling and distributed locking
- Retry scheduling with backoff
- P12 semaphore acquisition for SCREENSHOT queue
- OpenTelemetry instrumentation

Render with: `plantuml docs/diagrams/job-dispatch-sequence.puml`

---

**Document Status:** ✅ Accepted
**Review Sign-Off:** Ops Architect (required per I1.T4 acceptance criteria)
**Next Review:** Q2 2026 (or when adding new queue families)
