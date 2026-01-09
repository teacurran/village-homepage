# Async Workload Strategy

**Version:** 1.0
**Last Updated:** 2026-01-08
**Owner:** Platform Engineering

This document defines the async job architecture for Village Homepage, including queue strategy, retry policies, concurrency controls, and escalation paths. All code artifacts reference this document as the source of truth for operational behavior.

---

## 1. Queue Families

Village Homepage uses five queue families for prioritized job routing. Each queue has dedicated worker threads, SLA commitments, and failure escalation paths.

### Queue Matrix

| Queue | Priority | Owner (Ops Contact) | Color | Concurrency | SLA (P95 Latency) | Use Cases |
|-------|----------|---------------------|-------|-------------|-------------------|-----------|
| **HIGH** | 0 | Ops On-Call (PagerDuty: `vc-ops-high`) | Salmon | 20 workers/pod | < 30 seconds | Stock refresh (market hours), message relay, urgent notifications |
| **DEFAULT** | 5 | Platform Services Guild (Slack: `#platform-feeds`) | Blue | 10 workers/pod | < 5 minutes | Feed refresh, weather refresh, listing expiration, rank recalculation, inbound email |
| **SCREENSHOT** | 6 | Good Sites Platform (Slack: `#good-sites`) | Lavender | 3 workers/pod (P12) | < 2 minutes | Good Sites screenshot capture via jvppeteer/Chromium |
| **LOW** | 7 | Data Insights Pod (Slack: `#analytics`) | Green | 5 workers/pod | < 30 minutes | Social refresh, link health checks, sitemap generation, click rollup |
| **BULK** | 8 | Content Intelligence Squad (Slack: `#ai-pipelines`) | Yellow | 8 workers/pod | Best effort | AI tagging (P10 budget enforced), image processing, bulk imports |

Queue owners are responsible for on-call escalations when SLA breaches occur; Ops Architects map these owners to Kubernetes worker deployments per Policy P12 and the scaling thresholds defined in Section 4.

**Policy References:**
- **P7:** Unified job orchestration framework across all queue families
- **P12:** SCREENSHOT queue enforces semaphore-limited concurrency (3 workers) to prevent Chromium memory exhaustion

---

## 2. Job Type Registry

All async workloads are enumerated in the `JobType` enum. Each type is permanently assigned to one queue family.

| Job Type | Queue | Cadence | Handler Class | Policy Notes |
|----------|-------|---------|---------------|--------------|
| `RSS_FEED_REFRESH` | DEFAULT | 15min-daily (configurable) | `RssFeedRefreshHandler` | — |
| `WEATHER_REFRESH` | DEFAULT | 1 hour (forecast), 15 min (alerts) | `WeatherRefreshJobHandler` | Dual cadence: hourly forecast refresh + 15-min severe weather alerts (NWS only) |
| `LISTING_EXPIRATION` | DEFAULT | Daily @ 2am UTC | `ListingExpirationHandler` | — |
| `RANK_RECALCULATION` | DEFAULT | Hourly | `RankRecalculationHandler` | — |
| `INBOUND_EMAIL` | DEFAULT | 1 minute | `InboundEmailHandler` | IMAP polling for marketplace relay |
| `STOCK_REFRESH` | HIGH | 5 min (market hours), 1hr (after hours), 6hr (weekends) | `StockRefreshJobHandler` | Alpha Vantage API: 25 req/day free tier, rate limit fallback serves stale cache, market hours: 9:30am-4pm ET Mon-Fri |
| `MESSAGE_RELAY` | HIGH | On-demand | `MessageRelayHandler` | Marketplace inquiry relay with email masking |
| `SOCIAL_REFRESH` | LOW | 30 minutes | `SocialRefreshHandler` | P5/P13: Secure token storage required |
| `LINK_HEALTH_CHECK` | LOW | Weekly | `LinkHealthCheckHandler` | Detects dead links in Good Sites directory |
| `SITEMAP_GENERATION` | LOW | Daily @ 3am UTC | `SitemapGenerationHandler` | SEO sitemap XML generation |
| `CLICK_ROLLUP` | LOW | Hourly | `ClickRollupHandler` | P14: Consent-gated, 90-day retention |
| `AI_TAGGING` | BULK | Hourly (scheduled) | `AiTaggingJobHandler` | P2/P10: $500/month budget ceiling with automatic throttling (see Section 13) |
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

## 10. RSS Feed Refresh Job Details

The `RSS_FEED_REFRESH` job is a critical component of the content aggregation pipeline, responsible for fetching, parsing, and storing news articles from configured RSS/Atom sources.

### Job Payload Structure

```json
{
  "source_id": "uuid-string",  // Optional - if null, refresh all due feeds
  "force_refresh": false        // Optional - bypass interval check (default: false)
}
```

**Payload Parameters:**
- **`source_id`** (UUID, optional): Specific RSS source to refresh. If omitted, handler queries `RssSource.findDueForRefresh()` to process all sources due based on `last_fetched_at` and `refresh_interval_minutes`.
- **`force_refresh`** (boolean, optional): Bypasses interval check to force immediate refresh. Used by admin dashboard for manual refresh operations.

### Scheduler Behavior

The `RssFeedRefreshScheduler` runs every **5 minutes** to ensure high-frequency feeds (15-minute intervals for breaking news) are refreshed promptly. The scheduler:
1. Invokes handler with empty payload
2. Handler queries `RssSource.findDueForRefresh()` to determine which sources are ready
3. Processes each source independently (continues on individual failures)

### Feed Refresh Intervals

Per Policy P2 (Feed Governance), sources are configured with intervals based on content velocity:

| Category | Interval | Examples |
|----------|----------|----------|
| Breaking News | 15 minutes | Reuters, BBC News, AP |
| Tech News | 60 minutes | TechCrunch, Hacker News |
| Analysis/Opinion | 360 minutes (6 hours) | Financial Times, Scientific American |
| Sports/Entertainment | 30-120 minutes | ESPN, Variety |

Intervals are enforced via database CHECK constraint (15-1440 minutes) and respected by `findDueForRefresh()` query.

### Error Handling & Auto-Disable

**5-Error Auto-Disable Policy:**
- Each fetch failure increments `rss_sources.error_count`
- After **5 consecutive errors**, source is automatically disabled (`is_active = false`)
- Successful fetch resets `error_count` to 0 and clears `last_error_message`

**Common Error Scenarios:**

| Error Type | HTTP Status/Exception | Handler Action | Retry Behavior |
|------------|----------------------|----------------|----------------|
| Network timeout | `HttpTimeoutException` | Increment error_count, log warning | Retry via job retry mechanism |
| HTTP 404/410 | Gone/Not Found | Increment error_count, log warning | Retry (may auto-disable after 5) |
| HTTP 429 | Rate Limit Exceeded | Increment error_count, respect Retry-After header | Backoff and retry |
| Invalid XML | `WireFeedInput` parse error | Increment error_count, log parse details | Retry (feed may recover) |
| Missing fields | Title/URL null or blank | Skip item, log warning, continue with remaining entries | No retry (entry-level failure) |

### Deduplication Strategy

**Primary Deduplication:** RSS GUID (`item_guid` unique constraint)
1. Handler calls `FeedItem.findByGuid(entry.getUri())` before creating item
2. If GUID exists, skip item and increment `items_duplicate` counter
3. If GUID missing, fallback to URL + published_at hash

**Content Hash:** MD5 hash of title + description stored in `content_hash` for future similarity detection (not yet implemented in frontend).

### OpenTelemetry Span Events

RSS refresh jobs emit the following span events for debugging:

| Event Name | Attributes | Description |
|------------|------------|-------------|
| `fetch.started` | `source_id`, `source_url` | HTTP request initiated |
| `parse.completed` | `items_fetched` | Rome Tools parsing succeeded |
| `dedupe.completed` | `new`, `duplicate` | Deduplication pass completed |
| `entry.missing_fields` | `guid` | Entry skipped due to missing title/url |
| `source.not_found` | `source_id` | Payload specified non-existent source |
| `source.inactive` | `source_id` | Source disabled, refresh skipped |
| `refresh.no_sources` | — | No sources due for refresh |
| `refresh.completed` | `sources.success`, `sources.failure` | Batch refresh completed |

### Micrometer Metrics

**Counters:**
- **`rss.fetch.items.total`** - Total feed items processed
  - Tags: `source_id`, `status={new|duplicate}`
- **`rss.fetch.errors.total`** - Fetch failures by error type
  - Tags: `source_id`, `error_type={HttpTimeoutException|http_404|...}`

**Timers:**
- **`rss.fetch.duration`** - Fetch and parse duration
  - Tags: `source_id`, `result={success|failure}`

### Troubleshooting Guide

**Symptom:** Source disabled after multiple errors
- **Diagnosis:** Check `rss_sources.last_error_message` for details
- **Resolution:**
  1. Verify feed URL is still valid (may have moved or been removed)
  2. Check if site is blocking user agent (some sites block Java HttpClient)
  3. Re-enable via Admin API: `PATCH /admin/api/rss-sources/{id}` with `{"is_active": true, "error_count": 0}`

**Symptom:** Duplicate items appearing in database
- **Diagnosis:** Feed provider changed GUID format or doesn't provide stable GUIDs
- **Resolution:**
  1. Query `feed_items` for items with similar `content_hash` but different `item_guid`
  2. If widespread, consider switching to content hash-based deduplication for that source
  3. File bug report with feed provider if GUIDs are unstable

**Symptom:** Job execution time >5 minutes (SLA breach)
- **Diagnosis:** Large feed (>1000 entries) or slow parsing
- **Resolution:**
  1. Check Grafana: `histogram_quantile(0.95, rate(rss_fetch_duration_bucket{source_id="xyz"}[5m]))`
  2. If parse time high, consider adding pagination or entry limit (not yet implemented)
  3. If network time high, verify feed server performance

**Symptom:** AI tagging not triggering for new items
- **Diagnosis:** Items not marked with `ai_tagged = false`
- **Resolution:**
  1. Verify handler sets `item.aiTagged = false` on new items
  2. Check `FeedItem.findUntagged()` returns items (should be picked up by I3.T3 AI tagging job)

### Manual Operations

**Force Refresh Specific Source:**
```bash
curl -X POST http://localhost:8080/admin/api/jobs/enqueue \
  -H "Content-Type: application/json" \
  -d '{
    "job_type": "RSS_FEED_REFRESH",
    "payload": {
      "source_id": "uuid-of-source",
      "force_refresh": true
    }
  }'
```

**Query Sources Due for Refresh:**
```sql
SELECT id, name, url, last_fetched_at, refresh_interval_minutes
FROM rss_sources
WHERE is_active = true
  AND (last_fetched_at IS NULL
       OR last_fetched_at + (refresh_interval_minutes || ' minutes')::interval < NOW())
ORDER BY last_fetched_at NULLS FIRST;
```

**Reset Error Count for Disabled Source:**
```sql
UPDATE rss_sources
SET error_count = 0,
    last_error_message = NULL,
    is_active = true,
    updated_at = NOW()
WHERE id = 'uuid-of-source';
```

---

## 13. AI Tagging Job Details

The `AI_TAGGING` job uses Anthropic Claude Sonnet 4 via LangChain4j to extract topics, sentiment, and categories from RSS feed items. This job is subject to strict budget enforcement per P2/P10 policies.

### Job Payload Structure

```json
{
  "trigger": "scheduled",      // Optional - "scheduled" or "manual"
  "untagged_count": 150        // Optional - number of untagged items (informational)
}
```

**Payload Parameters:**
- **`trigger`** (string, optional): Source of the job (scheduled vs. manual admin trigger). Used for telemetry.
- **`untagged_count`** (integer, optional): Snapshot of untagged item count at enqueue time. For logging/metrics only.

### Scheduler Behavior

The `AiTaggingScheduler` runs **every hour** (top of the hour) to check for untagged feed items:
1. Queries `FeedItem.count("ai_tagged = false")`
2. If count > 0, enqueues AI_TAGGING job
3. Handler processes items in batches based on budget state

### Budget Enforcement States

The handler checks budget status before and during processing, with four enforcement levels:

| State | Budget Used | Batch Size | Behavior |
|-------|-------------|------------|----------|
| **NORMAL** | < 75% | 20 items | Full-speed processing |
| **REDUCE** | 75-90% | 10 items | Reduced batch sizes to conserve budget |
| **QUEUE** | 90-100% | 0 items | Skip processing, defer to next hour |
| **HARD_STOP** | ≥ 100% | 0 items | Stop all processing until next monthly cycle |

**Budget Tracking:**
- Monthly budget: $500 (50,000 cents)
- Cost per article: ~$0.006 (estimated)
- Capacity: ~83,000 articles/month
- Budget resets automatically on 1st of each month

**Email Alerts:**
- 75% threshold: INFO alert to `ops@villagecompute.com`
- 90% threshold: WARNING alert
- 100% threshold: CRITICAL alert

### Processing Flow

1. **Budget Check:** Handler calls `AiTaggingBudgetService.getCurrentBudgetAction()`
2. **Early Exit:** If QUEUE or HARD_STOP, skip processing and log warning
3. **Query Untagged:** Fetch items via `FeedItem.findUntagged()` (returns `ai_tagged = false`)
4. **Batch Processing:** Partition items based on budget action batch size
5. **Tag Generation:** For each item, call `AiTaggingService.tagArticle(title, description, content)`
6. **Persistence:** Store tags via `FeedItem.updateAiTags(item, tags)`
7. **Cost Tracking:** Record token usage and cost in `ai_usage_tracking` table
8. **Mid-Batch Check:** Re-check budget between batches to handle budget exhaustion

### Tag Structure

Each feed item receives an `AiTagsType` record stored as JSONB in `feed_items.ai_tags`:

```json
{
  "topics": ["AI", "Machine Learning", "Technology"],
  "sentiment": "positive",
  "categories": ["Technology", "Science"],
  "confidence": 0.85
}
```

**Fields:**
- **topics** (string[]): 3-5 extracted keywords/topics
- **sentiment** (string): "positive", "negative", "neutral", or "mixed"
- **categories** (string[]): 1-3 categories from predefined list (Technology, Business, Science, Health, Politics, Entertainment, Sports, World, etc.)
- **confidence** (float): 0.0-1.0 quality score for tagging accuracy

### Error Handling

**Individual Item Failures:**
- Handler catches exceptions per item and continues with batch
- Failed items remain untagged (`ai_tagged = false`) for retry in next hour
- Metrics track success/failure counts separately

**Common Error Scenarios:**

| Error Type | Handler Action | Retry Behavior |
|------------|----------------|----------------|
| Invalid JSON response | Log warning, skip item | Retry next hour |
| API timeout (>30s) | Log error, skip item | Retry next hour |
| API rate limiting | Increment batch delay | Retry via job retry mechanism |
| Budget exhausted mid-batch | Stop processing, log warning | Resume next hour (budget permitting) |

### OpenTelemetry Span Events

AI tagging jobs emit the following span events:

| Event Name | Attributes | Description |
|------------|------------|-------------|
| `budget_check` | `budget_action`, `percent_used` | Budget status evaluated |
| `budget_throttle` | `budget_action` | Processing skipped due to budget |
| `budget_exhausted_mid_batch` | `tagged_count`, `total_count` | Budget exhausted during batch |
| `item.tagged` | `item_id`, `topics`, `sentiment`, `categories` | Individual item tagged successfully |
| `item.failed` | `item_id`, `error` | Individual item tagging failed |

### Micrometer Metrics

**Counters:**
- **`ai.tagging.items.total`** - Items processed by status
  - Tags: `status={success|failure}`
- **`ai.tagging.tokens.total`** - Token consumption
  - Tags: `type={input|output}`
- **`ai.tagging.cost.cents`** - Estimated cost in cents
- **`ai.tagging.budget.throttles`** - Number of throttle events

**Gauges:**
- **`ai.budget.percent_used`** - Current month budget percentage (0-100+)

### Admin Monitoring

**View Current Budget Status:**
```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://homepage.villagecompute.com/admin/api/ai-usage
```

**View Historical Usage (last 12 months):**
```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://homepage.villagecompute.com/admin/api/ai-usage/history?months=12
```

**Adjust Budget Limit:**
```bash
curl -X PATCH \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"budgetLimitCents": 75000}' \
  https://homepage.villagecompute.com/admin/api/ai-usage/2025-01/budget
```

### Troubleshooting Guide

**Symptom:** Tags not being generated despite job running
- **Diagnosis:** Check budget action via admin API
- **Resolution:** If QUEUE or HARD_STOP, either wait for next month or increase budget limit

**Symptom:** High tag failure rate
- **Diagnosis:** Check logs for "Failed to parse Claude response"
- **Resolution:** Review `AiTaggingService.buildPrompt()` for clarity, consider adjusting temperature or max_tokens

**Symptom:** Budget exhausted mid-month
- **Diagnosis:** Check `ai_usage_tracking` table for unexpected token usage spikes
- **Resolution:** Investigate feed sources for spam or long-form content, adjust `MAX_CONTENT_LENGTH` if needed

**Symptom:** Low confidence scores (<0.5)
- **Diagnosis:** Review sample tagged items in database
- **Resolution:** May indicate unclear content, poor prompts, or need for model fine-tuning

### Manual Operations

**Trigger AI Tagging Immediately:**
```bash
curl -X POST http://localhost:8080/admin/api/jobs/enqueue \
  -H "Content-Type: application/json" \
  -d '{
    "job_type": "AI_TAGGING",
    "payload": {
      "trigger": "manual"
    }
  }'
```

**Query Untagged Items:**
```sql
SELECT COUNT(*) FROM feed_items WHERE ai_tagged = false;
```

**Reset Budget for Current Month (emergency):**
```sql
UPDATE ai_usage_tracking
SET estimated_cost_cents = 0,
    total_requests = 0,
    total_tokens_input = 0,
    total_tokens_output = 0,
    updated_at = NOW()
WHERE month = DATE_TRUNC('month', CURRENT_DATE)
  AND provider = 'anthropic';
```

**Check Tag Distribution:**
```sql
SELECT
  jsonb_array_elements_text(ai_tags->'categories') AS category,
  COUNT(*) AS count
FROM feed_items
WHERE ai_tagged = true AND ai_tags IS NOT NULL
GROUP BY category
ORDER BY count DESC;
```

### Performance Characteristics

**Typical Execution Times:**
- Single article tagging: 2-5 seconds (Claude API latency)
- 20-item batch (NORMAL mode): 40-100 seconds
- 10-item batch (REDUCE mode): 20-50 seconds
- Budget check overhead: <100ms

**Token Usage Estimates:**
- Average article: 1000 input tokens, 200 output tokens
- Cost per article: ~$0.006
- Hourly capacity (NORMAL mode): ~240 articles (12 batches × 20 items)

**Database Impact:**
- 1 SELECT per item (deduplication check)
- 1 UPDATE per item (tag persistence)
- 1 INSERT/UPDATE on `ai_usage_tracking` per batch
- Typical query time: <50ms per item

For comprehensive budget management procedures, see **`docs/ops/ai-budget-management.md`** runbook.

---

## 11. Future Enhancements

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

## 12. Policy Cross-References

This document implements the following architectural policies:

- **P7:** Job orchestration framework (all sections)
- **P10:** AI tagging budget enforcement (Sections 2, 6, 8)
- **P12:** SCREENSHOT queue concurrency limits (Sections 1, 4)
- **P14:** Click tracking consent gating (Section 2)
- **P2:** Feed Governance - RSS refresh intervals and error handling (Section 10)

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
