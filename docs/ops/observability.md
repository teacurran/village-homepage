# Observability Operations Guide

**Version:** 1.0
**Last Updated:** 2025-01-08
**Owner:** Platform Squad
**Status:** Active

## 1. Overview

This guide documents the observability baseline for Village Homepage, covering structured logging, distributed tracing, metrics collection, and alerting strategies. It provides operational procedures for monitoring application health, diagnosing issues, and maintaining SLA commitments.

### 1.1 Purpose

The observability stack enables:
- Real-time visibility into request flows across async boundaries
- Correlation of logs, traces, and metrics for rapid troubleshooting
- Proactive alerting on resource exhaustion and SLA violations
- Performance profiling and capacity planning
- Audit trails for security and compliance

### 1.2 Observability Pillars

| Pillar | Technology | Access Point | Purpose |
|--------|------------|--------------|---------|
| **Structured Logging** | Quarkus JSON Console + MDC | Application logs (stdout/stderr) | Contextual event recording with trace correlation |
| **Distributed Tracing** | OpenTelemetry + Jaeger | http://localhost:16686 (dev)<br>https://jaeger.villagecompute.com (prod) | Request flow visualization across services and async jobs |
| **Metrics** | SmallRye Metrics (Prometheus format) | http://localhost:8080/q/metrics | Resource utilization, throughput, latency, custom business metrics |
| **Alerting** | Prometheus Alertmanager (prod) | Configured by ops team | Proactive notification of threshold breaches |

### 1.3 Policy References

This observability baseline implements requirements from:
- **Section 3.1** - Observability Implementation Blueprint (logging fields, metrics catalog, tracing coverage)
- **P7** - Unified job orchestration with telemetry integration
- **P10** - AI budget tracking and >90% consumption alerts
- **P12** - Screenshot queue concurrency monitoring

---

## 2. Structured Logging

### 2.1 Log Format

Village Homepage emits structured JSON logs in production (`%prod` profile) and human-readable logs in development (`%dev` profile). All logs include standard fields plus custom MDC enrichments.

#### Standard Fields (Auto-Included)

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `timestamp` | ISO8601 | Log entry creation time | `2025-01-08T22:45:12.345Z` |
| `level` | String | Log severity (TRACE, DEBUG, INFO, WARN, ERROR, FATAL) | `INFO` |
| `message` | String | Human-readable log message | `Widget created successfully` |
| `loggerName` | String | Fully-qualified class name of logger | `villagecompute.homepage.api.rest.WidgetResource` |
| `threadName` | String | Thread executing the log statement | `executor-thread-42` |
| `threadId` | Long | Thread identifier | `123` |

#### Custom MDC Fields (Context-Dependent)

These fields are populated by `LoggingEnricher` (HTTP requests) and `DelayedJobService` (async jobs) via the `LoggingConfig` utility class.

| Field | Type | Presence | Description | Example |
|-------|------|----------|-------------|---------|
| `trace_id` | String | Always | OpenTelemetry trace identifier (32 hex chars) | `4bf92f3577b34da6a3ce929d0e0e4736` |
| `span_id` | String | Always | OpenTelemetry span identifier (16 hex chars) | `00f067aa0ba902b7` |
| `user_id` | String | Authenticated requests | Authenticated user primary key | `42` |
| `anon_id` | String | Anonymous requests | Anonymous session UUID | `550e8400-e29b-41d4-a716-446655440000` |
| `feature_flags` | String | When flags active | Comma-separated active feature flags | `stocks_widget,social_integration` |
| `request_origin` | String | Always | HTTP path or job type identifier | `/api/widgets` or `JobType.FEED_REFRESH` |
| `rate_limit_bucket` | String | When applicable | Rate limiting bucket key | `user:42:widget_create` |
| `job_id` | String | Async jobs only | Delayed job primary key | `1234` |
| `service.name` | String | Always | Application identifier | `village-homepage` |
| `service.version` | String | Always | Application version | `1.0.0` |
| `environment` | String | Always | Deployment environment | `dev`, `beta`, `prod` |

### 2.2 Example Log Entries

#### HTTP Request (Authenticated User)
```json
{
  "timestamp": "2025-01-08T22:45:12.345Z",
  "level": "INFO",
  "message": "Widget created successfully",
  "loggerName": "villagecompute.homepage.api.rest.WidgetResource",
  "threadName": "executor-thread-5",
  "threadId": 42,
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "span_id": "00f067aa0ba902b7",
  "user_id": "42",
  "request_origin": "POST /api/widgets",
  "feature_flags": "stocks_widget,social_integration",
  "service.name": "village-homepage",
  "service.version": "1.0.0",
  "environment": "prod"
}
```

#### Async Job Execution
```json
{
  "timestamp": "2025-01-08T22:46:30.123Z",
  "level": "INFO",
  "message": "Job 1234 (type: FEED_REFRESH) completed successfully on attempt 1",
  "loggerName": "villagecompute.homepage.services.DelayedJobService",
  "threadName": "delayed-job-worker-3",
  "threadId": 78,
  "trace_id": "a1b2c3d4e5f6789012345678abcdef01",
  "span_id": "1234567890abcdef",
  "job_id": "1234",
  "request_origin": "JobType.FEED_REFRESH",
  "service.name": "village-homepage",
  "service.version": "1.0.0",
  "environment": "prod"
}
```

### 2.3 Log Querying (Production)

Logs are ingested into Elasticsearch and queryable via Kibana or the Logs API.

#### Find all logs for a specific trace
```json
GET /logs/_search
{
  "query": {
    "term": { "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736" }
  },
  "sort": [{ "timestamp": "asc" }]
}
```

#### Find errors for a specific user
```json
GET /logs/_search
{
  "query": {
    "bool": {
      "must": [
        { "term": { "user_id": "42" } },
        { "term": { "level": "ERROR" } }
      ]
    }
  }
}
```

#### Find failed jobs in SCREENSHOT queue
```json
GET /logs/_search
{
  "query": {
    "bool": {
      "must": [
        { "match": { "request_origin": "JobType.SCREENSHOT_CAPTURE" } },
        { "match": { "message": "failed" } }
      ]
    }
  }
}
```

---

## 3. Distributed Tracing

### 3.1 Architecture

Village Homepage uses **OpenTelemetry** for distributed tracing with **Jaeger** as the visualization backend. Traces follow the W3C Trace Context standard for cross-service correlation.

#### Trace Propagation
- **HTTP Requests:** Trace context auto-propagated via `traceparent` header
- **Async Jobs:** Trace context stored in job payload metadata and restored in worker thread
- **External APIs:** Trace context injected into outbound HTTP client requests

### 3.2 Instrumented Operations

Per Section 3.1 of the Observability Blueprint, the following workflows are fully instrumented:

| Workflow | Root Span | Child Spans | Custom Attributes |
|----------|-----------|-------------|-------------------|
| **Feed Refresh** | `job.execute` | `http.client.fetch`, `db.insert` | `feed.id`, `feed.url`, `item.count` |
| **AI Tagging** | `job.execute` | `ai.tag_batch`, `db.update` | `batch.size`, `ai.model`, `ai.cost_cents` |
| **Screenshot Capture** | `job.execute` | `puppeteer.navigate`, `puppeteer.screenshot`, `s3.upload` | `url`, `viewport.width`, `file.size_bytes` |
| **Payment Lifecycle** | `payment.process` | `stripe.create_charge`, `db.update`, `email.send` | `amount_cents`, `stripe.charge_id`, `user.id` |
| **Inbound Email** | `email.parse` | `imap.fetch`, `message.relay`, `db.update` | `listing.id`, `sender.masked` |

### 3.3 Accessing Jaeger UI

#### Development
1. Ensure Jaeger is running: `docker-compose ps jaeger`
2. Open browser to http://localhost:16686
3. Select service: `village-homepage`
4. Filter by operation, tags, or duration
5. Click trace ID to view waterfall diagram

#### Production
1. Access Jaeger via VPN: https://jaeger.villagecompute.com
2. Authenticate with SSO
3. Select service: `village-homepage`
4. Use time range picker to narrow search window

### 3.4 Example Trace Analysis

**Scenario:** User reports slow widget creation (POST /api/widgets)

1. **Find Trace by Duration:**
   - Filter: `service=village-homepage`, `operation=POST /api/widgets`, `min-duration=5s`
   - Result: Trace `4bf92f3577b34da6a3ce929d0e0e4736`

2. **Analyze Waterfall:**
   - Root span: `http.server.request` (5.2s total)
   - Child span: `db.query.widget.create` (0.1s) ✅ Fast
   - Child span: `http.client.ai_tagging` (5.0s) ❌ Slow
   - Child span: `db.query.widget.update` (0.1s) ✅ Fast

3. **Identify Root Cause:**
   - AI tagging API (OpenAI) experiencing high latency
   - Check `ai.tag_batch` span attributes for model/batch size
   - Correlate with P10 budget metrics to rule out rate limiting

4. **Mitigation:**
   - Move AI tagging to async BULK queue (future enhancement)
   - Add timeout circuit breaker on AI client

### 3.5 Custom Span Attributes

Developers can add custom attributes to spans for enhanced debugging:

```java
import io.opentelemetry.api.trace.Span;

Span currentSpan = Span.current();
currentSpan.setAttribute("widget.id", widgetId);
currentSpan.setAttribute("widget.type", widgetType.name());
currentSpan.setAttribute("user.tier", user.getTier().name());
```

---

## 4. Metrics

### 4.1 Metrics Endpoint

All metrics are exposed in Prometheus format at `/q/metrics`. This endpoint is publicly accessible in dev mode and secured by IP whitelist in production.

**Access (Development):**
```bash
curl http://localhost:8080/q/metrics
```

**Access (Production):**
```bash
# Requires VPN + whitelisted IP
curl https://homepage.villagecompute.com/q/metrics
```

### 4.2 Metrics Catalog

#### HTTP Metrics (Auto-Provided by Quarkus)

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `http_server_requests_seconds` | Timer | `method`, `uri`, `status`, `outcome` | HTTP request duration histogram |
| `http_server_requests_seconds_count` | Counter | `method`, `uri`, `status`, `outcome` | Total HTTP requests |
| `http_server_requests_seconds_sum` | Counter | `method`, `uri`, `status`, `outcome` | Cumulative HTTP request duration |

**Example Query (Prometheus PromQL):**
```promql
# Average response time for /api/widgets endpoint (last 5 minutes)
rate(http_server_requests_seconds_sum{uri="/api/widgets"}[5m])
/
rate(http_server_requests_seconds_count{uri="/api/widgets"}[5m])
```

#### Job Queue Metrics (Custom)

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `homepage_jobs_depth` | Gauge | `queue`, `priority` | Pending job count per queue family |
| `homepage_screenshot_slots_available` | Gauge | `queue=SCREENSHOT` | Available screenshot worker permits (max 3) |

**Example Query (Prometheus PromQL):**
```promql
# Total pending jobs across all queues
sum(homepage_jobs_depth)

# Screenshot queue backlog alert (triggers when >50 pending)
homepage_jobs_depth{queue="SCREENSHOT"} > 50
```

**Grafana Dashboard Panel (Queue Depth):**
```json
{
  "title": "Delayed Job Queue Depth",
  "targets": [
    {
      "expr": "homepage_jobs_depth",
      "legendFormat": "{{queue}} (priority={{priority}})"
    }
  ],
  "type": "graph",
  "yaxes": [
    { "label": "Pending Jobs", "format": "short" }
  ]
}
```

#### AI Budget Metrics (Custom - P10)

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `homepage_ai_budget_consumed_percent` | Gauge | `budget_ceiling_dollars` | AI tagging budget consumption (0-100%) |

**Example Query (Prometheus PromQL):**
```promql
# Alert when AI budget exceeds 90% (per Section 3.1)
homepage_ai_budget_consumed_percent > 90
```

**Grafana Dashboard Panel (AI Budget):**
```json
{
  "title": "AI Tagging Budget Consumption",
  "targets": [
    {
      "expr": "homepage_ai_budget_consumed_percent",
      "legendFormat": "Consumed %"
    }
  ],
  "type": "gauge",
  "thresholds": [
    { "value": 80, "color": "yellow" },
    { "value": 90, "color": "red" }
  ],
  "max": 100
}
```

### 4.3 Content Services Metrics (I3)

The following metrics track content aggregation services implemented in Iteration I3:

#### RSS Feed Metrics

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `rss_fetch_items_total` | Counter | `source_id`, `status` (new/duplicate) | Total feed items fetched |
| `rss_fetch_duration` | Timer | `source_id`, `result` (success/failure) | Feed fetch duration |
| `rss_fetch_errors_total` | Counter | `source_id`, `error_type` | Feed fetch errors |

**Example Queries:**
```promql
# Feed ingestion throughput (new items/sec)
rate(rss_fetch_items_total{status="new"}[5m])

# Feed error rate
sum(rate(rss_fetch_errors_total[5m])) / sum(rate(rss_fetch_duration_count[5m]))

# Feed latency (p95)
histogram_quantile(0.95, rate(rss_fetch_duration_bucket[5m]))
```

#### AI Tagging Metrics

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `ai_tagging_items_total` | Counter | `status` (success/failure) | Items processed by AI tagging |
| `ai_tagging_budget_throttles` | Counter | (none) | Requests throttled due to budget |
| `homepage_ai_budget_consumed_percent` | Gauge | `budget_ceiling_dollars` | Budget consumption 0-100% |

**Example Queries:**
```promql
# AI tagging success rate
rate(ai_tagging_items_total{status="success"}[5m]) / rate(ai_tagging_items_total[5m])

# Budget throttles in last hour
sum(increase(ai_tagging_budget_throttles[1h]))
```

#### Weather Service Metrics

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `weather_cache_hits` | Counter | (none) | Weather cache hits |
| `weather_cache_misses` | Counter | (none) | Weather cache misses |
| `homepage_weather_cache_staleness_minutes` | Gauge | (none) | Age of oldest cache entry |

**Example Queries:**
```promql
# Cache hit rate
rate(weather_cache_hits[5m]) / (rate(weather_cache_hits[5m]) + rate(weather_cache_misses[5m]))

# Alert on stale cache
homepage_weather_cache_staleness_minutes > 90
```

#### Stock Market Metrics

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `stock_cache_hits` | Counter | (none) | Stock cache hits |
| `stock_cache_misses` | Counter | (none) | Stock cache misses |
| `stock_rate_limit_exceeded` | Counter | (none) | Alpha Vantage rate limit hits |
| `stock_fetch_total` | Counter | `symbol`, `status` (success/rate_limited/error) | Stock fetch outcomes |
| `stock_api_duration` | Timer | (none) | Alpha Vantage API latency |

**Example Queries:**
```promql
# Rate limit hits per second
sum(rate(stock_fetch_total{status="rate_limited"}[5m]))

# API latency (p95)
histogram_quantile(0.95, rate(stock_api_duration_bucket[5m]))
```

#### Social Integration Metrics

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `social_feed_requests` | Counter | `platform`, `status` (disconnected/fresh/stale) | Social feed request outcomes |
| `social_api_duration` | Timer | (none) | Meta API response time |
| `social_api_failures` | Counter | `platform` | API call failures |
| `social_feed_errors` | Counter | (none) | Feed processing errors |

**Example Queries:**
```promql
# Failure rate by platform
sum(rate(social_api_failures[5m])) by (platform)

# Fresh vs stale vs disconnected
sum(rate(social_feed_requests[5m])) by (status)
```

#### Storage Gateway Metrics

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `storage_uploads_total` | Counter | `bucket`, `status` (success/failure) | Upload operations |
| `storage_bytes_uploaded` | Counter | `bucket` | Bytes uploaded |
| `storage_upload_duration` | Timer | `bucket` | Upload latency |
| `storage_downloads_total` | Counter | `bucket`, `status` | Download operations |
| `storage_bytes_downloaded` | Counter | `bucket` | Bytes downloaded |
| `storage_download_duration` | Timer | `bucket` | Download latency |
| `storage_signed_urls_total` | Counter | `bucket` | Signed URL generations |
| `storage_deletes_total` | Counter | `bucket`, `status` | Delete operations |

**Example Queries:**
```promql
# Upload error rate
sum(rate(storage_uploads_total{status="failure"}[5m])) / sum(rate(storage_uploads_total[5m]))

# Upload latency (p95)
histogram_quantile(0.95, rate(storage_upload_duration_bucket[5m]))

# Bytes uploaded (last hour) by bucket
sum(increase(storage_bytes_uploaded[1h])) by (bucket)
```

### 4.4 Future Metrics (Roadmap)

The following metrics will be added in subsequent iterations:

| Metric Name | Type | Description | Iteration |
|-------------|------|-------------|-----------|
| `homepage_screenshot_capture_seconds` | Histogram | Screenshot capture duration | I4 |
| `homepage_marketplace_listing_views` | Counter | Marketplace listing view count | I4 |
| `homepage_directory_submission_total` | Counter | Good Sites directory submissions | I4 |

---

## 5. Alerting

### 5.1 Critical Alerts (Section 3.1)

The following alerts **must** be configured in production Prometheus Alertmanager:

#### AI Budget Threshold (P10)
```yaml
- alert: AIBudgetExceeded
  expr: homepage_ai_budget_consumed_percent > 90
  for: 5m
  labels:
    severity: critical
    team: platform
  annotations:
    summary: "AI tagging budget exceeded 90%"
    description: "Current consumption: {{ $value }}%. Monthly ceiling: $500. Disable AI tagging to prevent overrun."
    runbook_url: "https://wiki.villagecompute.com/runbooks/ai-budget-exceeded"
```

#### Screenshot Queue Backlog (P12)
```yaml
- alert: ScreenshotQueueBacklog
  expr: homepage_jobs_depth{queue="SCREENSHOT"} > 100
  for: 10m
  labels:
    severity: warning
    team: platform
  annotations:
    summary: "Screenshot queue backlog exceeds threshold"
    description: "Pending screenshots: {{ $value }}. Check Chromium worker health and concurrency limits."
    runbook_url: "https://wiki.villagecompute.com/runbooks/screenshot-backlog"
```

#### Elasticsearch Unreachable
```yaml
- alert: ElasticsearchDown
  expr: up{job="elasticsearch"} == 0
  for: 2m
  labels:
    severity: critical
    team: platform
  annotations:
    summary: "Elasticsearch cluster unreachable"
    description: "Search and directory features degraded. Check cluster health and network connectivity."
    runbook_url: "https://wiki.villagecompute.com/runbooks/elasticsearch-down"
```

#### Stripe Webhook Failures
```yaml
- alert: StripeWebhookFailures
  expr: rate(http_server_requests_seconds_count{uri="/webhooks/stripe",status=~"5.."}[5m]) > 0.1
  for: 5m
  labels:
    severity: critical
    team: platform
  annotations:
    summary: "Stripe webhook endpoint returning 5xx errors"
    description: "Failed requests: {{ $value }}/sec. Payment processing may be delayed."
    runbook_url: "https://wiki.villagecompute.com/runbooks/stripe-webhook-failures"
```

#### OIDC Login Errors
```yaml
- alert: OIDCLoginFailures
  expr: rate(http_server_requests_seconds_count{uri=~"/oauth2/.*",status=~"5.."}[5m]) > 0.2
  for: 5m
  labels:
    severity: warning
    team: platform
  annotations:
    summary: "OIDC login failure rate elevated"
    description: "Failed logins: {{ $value }}/sec. Check provider status (Google/Facebook/Apple)."
    runbook_url: "https://wiki.villagecompute.com/runbooks/oidc-failures"
```

#### Content Services Alerts (I3)

The following alerts monitor content aggregation services. Full alert definitions available in `docs/ops/alerts/content-services.yaml`.

**AI Budget Monitoring:**
```yaml
- alert: AIBudgetWarning
  expr: homepage_ai_budget_consumed_percent > 75
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "AI tagging budget at 75%"
    runbook_url: "https://wiki.villagecompute.com/runbooks/content-monitoring#ai-budget"

- alert: AIBudgetCritical
  expr: homepage_ai_budget_consumed_percent > 90
  for: 5m
  labels:
    severity: critical
  annotations:
    summary: "AI tagging budget at 90% (critical threshold)"
    runbook_url: "https://wiki.villagecompute.com/runbooks/content-monitoring#ai-budget"

- alert: AIBudgetExceeded
  expr: homepage_ai_budget_consumed_percent >= 100
  for: 1m
  labels:
    severity: critical
    pagerduty: "true"
  annotations:
    summary: "AI tagging budget exceeded - HARD STOP"
    runbook_url: "https://wiki.villagecompute.com/runbooks/content-monitoring#ai-budget"
```

**Feed Backlog:**
```yaml
- alert: FeedBacklog
  expr: sum(homepage_jobs_depth{queue="DEFAULT"}) > 500
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Feed refresh queue backlog exceeds 500"
    runbook_url: "https://wiki.villagecompute.com/runbooks/content-monitoring#feed-backlog"
```

**Weather Cache Staleness:**
```yaml
- alert: WeatherCacheTooStale
  expr: homepage_weather_cache_staleness_minutes > 90
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Weather cache staleness exceeds 90 minutes"
    runbook_url: "https://wiki.villagecompute.com/runbooks/content-monitoring#weather-stale"
```

**Stock Rate Limiting:**
```yaml
- alert: StockRateLimitExceeded
  expr: sum(rate(stock_fetch_total{status="rate_limited"}[5m])) > 0.1
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Stock API rate limit being hit"
    runbook_url: "https://wiki.villagecompute.com/runbooks/content-monitoring#stock-rate-limit"
```

**Social Refresh Failures:**
```yaml
- alert: SocialRefreshFailures
  expr: sum(rate(social_api_failures[5m])) by (platform) > 0.1
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Social feed refresh failures on {{ $labels.platform }}"
    runbook_url: "https://wiki.villagecompute.com/runbooks/content-monitoring#social-failures"
```

**Storage Gateway Errors:**
```yaml
- alert: StorageGatewayErrors
  expr: sum(rate(storage_uploads_total{status="failure"}[5m])) / sum(rate(storage_uploads_total[5m])) > 0.05
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Storage gateway upload error rate exceeds 5%"
    runbook_url: "https://wiki.villagecompute.com/runbooks/content-monitoring#storage-errors"
```

**See Also:**
- Full alert definitions: `docs/ops/alerts/content-services.yaml`
- Troubleshooting procedures: `docs/ops/runbooks/content-monitoring.md`
- Content services dashboard: `docs/ops/dashboards/content-services.json`

### 5.2 Alert Routing

Production alerts are routed via Alertmanager to:
- **Slack:** `#homepage-alerts` channel (all severities)
- **PagerDuty:** On-call rotation (critical severity only)
- **Email:** `ops@villagecompute.com` (critical severity, 5-minute grouping)

---

## 6. Configuration Reference

### 6.1 Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `ENVIRONMENT` | No | `dev` | Deployment environment (`dev`, `beta`, `prod`) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Prod only | `http://localhost:4318` | OpenTelemetry collector endpoint (HTTP) |
| `OTEL_EXPORTER_OTLP_HEADERS` | Prod only | (empty) | Authentication headers for collector (e.g., `Authorization=Bearer token`) |

### 6.2 Toggling Observability Components

#### Disable Tracing (for testing or cost reduction)
```yaml
# application.yaml or env override
quarkus:
  otel:
    sdk:
      disabled: true
```

#### Disable Metrics Endpoint
```yaml
quarkus:
  smallrye-metrics:
    enabled: false
```

#### Switch to Plain-Text Logs (dev override)
```yaml
"%dev":
  quarkus:
    log:
      console:
        json: false
        format: "%d{HH:mm:ss} %-5p [%c{2.}] (%t) %X{trace_id} %s%e%n"
```

### 6.3 Jaeger Exporter Endpoints

| Environment | Protocol | Endpoint | Authentication |
|-------------|----------|----------|----------------|
| Development | HTTP/Protobuf (OTLP) | http://localhost:4318 | None |
| Beta | HTTP/Protobuf (OTLP) | https://jaeger-collector-beta.villagecompute.com | Bearer token (env var) |
| Production | HTTP/Protobuf (OTLP) | https://jaeger-collector.villagecompute.com | Bearer token (env var) |

---

## 7. Troubleshooting

### 7.1 No Traces in Jaeger

**Symptoms:** Application logs show trace IDs, but Jaeger UI has no traces.

**Diagnosis:**
1. Check OTLP exporter configuration in `application.yaml`
2. Verify Jaeger collector is reachable: `curl http://localhost:4318/v1/traces -I`
3. Check application logs for OTLP export errors: `grep "otlp" logs/quarkus.log`
4. Confirm sampling is enabled: `quarkus.otel.traces.sampler=always_on`

**Resolution:**
```bash
# Restart Jaeger if unhealthy
docker-compose restart jaeger

# Verify traces are being exported (look for HTTP POST to /v1/traces)
curl -v http://localhost:4318/v1/traces
```

### 7.2 Missing MDC Fields in Logs

**Symptoms:** JSON logs missing `user_id`, `trace_id`, or other custom fields.

**Diagnosis:**
1. Verify `LoggingEnricher` is registered: `grep "@Provider" src/main/java/villagecompute/homepage/observability/LoggingEnricher.java`
2. Check filter priority (should be 1000): `@Priority(1000)`
3. Confirm JSON logging is enabled: `quarkus.log.console.json=true` (prod profile)

**Resolution:**
```bash
# Rebuild and restart application
./mvnw clean compile quarkus:dev

# Trigger test request and inspect logs
curl -v http://localhost:8080/api/widgets
```

### 7.3 Metrics Endpoint Returns 404

**Symptoms:** `curl http://localhost:8080/q/metrics` returns 404.

**Diagnosis:**
1. Verify metrics are enabled: `quarkus.smallrye-metrics.enabled=true`
2. Check if extension is installed: `./mvnw quarkus:list-extensions | grep metrics`
3. Confirm path configuration: `quarkus.smallrye-metrics.path=/q/metrics`

**Resolution:**
```bash
# Add metrics extension if missing
./mvnw quarkus:add-extension -Dextensions="micrometer-registry-prometheus"

# Restart application
./mvnw quarkus:dev
```

### 7.4 High Cardinality Metrics

**Symptoms:** Prometheus scrape times increase, memory usage spikes.

**Diagnosis:**
1. Check for unbounded label values (e.g., trace IDs, job IDs as labels)
2. Review custom metrics in `ObservabilityMetrics.java`
3. Query Prometheus for cardinality: `count({__name__=~".+"})`

**Resolution:**
- Remove high-cardinality labels (use span attributes instead)
- Limit label value space (e.g., group by status code range, not individual codes)

---

## 8. Maintenance

### 8.1 Log Retention

| Environment | Storage | Retention | Archival |
|-------------|---------|-----------|----------|
| Development | Docker volume | 7 days | None |
| Beta | Elasticsearch | 30 days | S3 (90 days) |
| Production | Elasticsearch | 90 days | S3 (1 year) |

### 8.2 Trace Retention

| Environment | Sampling | Retention | Storage Estimate |
|-------------|----------|-----------|------------------|
| Development | 100% | 3 days | 10 GB |
| Beta | 10% | 7 days | 50 GB |
| Production | 1% | 30 days | 200 GB |

### 8.3 Metrics Retention

| Resolution | Retention | Downsampling |
|------------|-----------|--------------|
| 15s (raw) | 7 days | None |
| 1m (aggregated) | 30 days | Mean, max, min |
| 1h (aggregated) | 1 year | Mean, max, min |

---

## 9. References

- **Observability Blueprint:** `.codemachine/artifacts/architecture/01_Blueprint_Foundation.md` (Section 3.1)
- **OpenTelemetry Docs:** https://opentelemetry.io/docs/
- **Jaeger Docs:** https://www.jaegertracing.io/docs/
- **Quarkus Observability Guide:** https://quarkus.io/guides/observability
- **Prometheus Alerting:** https://prometheus.io/docs/alerting/latest/

---

## 11. Content Services Monitoring (I3.T9)

This section documents monitoring implementation for content aggregation services delivered in Iteration I3.

### 11.1 RSS Feed Ingestion Metrics

**Metrics Implemented:**

- `rss_fetch_items_total{source_id, status}` (Counter) - Items fetched per source, tagged by status (new/duplicate)
- `rss_fetch_duration{source_id, result}` (Timer) - Feed fetch latency, tagged by result (success/failure)
- `rss_fetch_errors_total{source_id, error_type}` (Counter) - Fetch errors grouped by type (network/parse/timeout)

**Dashboard Panels:**
- Feed Ingestion Throughput: `rate(rss_fetch_items_total{status="new"}[5m])`
- Feed Error Rate by Type: `sum(rate(rss_fetch_errors_total[5m])) by (error_type)`
- Feed Fetch Duration (p95): `histogram_quantile(0.95, rate(rss_fetch_duration_bucket[5m]))`

**Alerts:**
- `FeedBacklog`: Triggers when DEFAULT queue depth exceeds 500 for 10 minutes
- `FeedErrorRateHigh`: Triggers when error rate exceeds 10% for 15 minutes
- `FeedFetchLatencyHigh`: Triggers when p95 latency exceeds 30 seconds for 10 minutes

**Implemented in:** `RssFeedRefreshJobHandler.java`

### 11.2 AI Budget Tracking

**Metrics Implemented:**

- `homepage_ai_budget_consumed_percent` (Gauge) - Monthly AI budget consumption percentage (0-100)
- `ai_tagging_items_total{status}` (Counter) - Items tagged, tagged by status (success/failure)
- `ai_tagging_budget_throttles` (Counter) - Number of requests throttled due to budget constraints

**Dashboard Panels:**
- AI Budget Consumption (gauge with thresholds at 75%/90%/100%)
- AI Tagging Success Rate: `rate(ai_tagging_items_total{status="success"}[5m]) / rate(ai_tagging_items_total[5m])`
- Budget Throttles: `sum(increase(ai_tagging_budget_throttles[1h]))`

**Alerts:**
- `AIBudgetWarning`: 75% consumption (warning, 5min delay)
- `AIBudgetCritical`: 90% consumption (critical, 5min delay)
- `AIBudgetExceeded`: 100% consumption (critical, 1min delay, pages on-call)

**Budget State Transitions:**
- 0-75%: NORMAL (full batch processing)
- 75-80%: REDUCE (smaller batches, slower throughput)
- 80-90%: REDUCE (continued)
- 90-100%: QUEUE (new jobs queued for next month)
- ≥100%: HARD_STOP (all tagging halted)

**Implemented in:** `ObservabilityMetrics.java`, `AiTaggingJobHandler.java`

### 11.3 Weather Service Monitoring

**Metrics Implemented:**

- `homepage_weather_cache_staleness_minutes` (Gauge) - Age of oldest weather cache entry in minutes
- `weather_cache_hits` (Counter) - Weather cache hits
- `weather_cache_misses` (Counter) - Weather cache misses (triggers API call)

**Dashboard Panels:**
- Weather Cache Staleness: `homepage_weather_cache_staleness_minutes` with alert line at 90 minutes
- Weather Cache Hit Rate: `rate(weather_cache_hits[5m]) / (rate(weather_cache_hits[5m]) + rate(weather_cache_misses[5m]))`

**Alerts:**
- `WeatherCacheTooStale`: Triggers when staleness exceeds 90 minutes for 5 minutes
- `WeatherCacheHitRateLow`: Info alert when hit rate drops below 50% for 15 minutes

**Provider Details:**
- Open-Meteo: International forecasts (no rate limits on free tier)
- National Weather Service (NWS): US forecasts (5 requests/sec limit, no issues expected)

**Implemented in:** `ObservabilityMetrics.java` (gauge), `WeatherService.java` (counters)

### 11.4 Stock Market Data Monitoring

**Metrics Implemented:**

- `stock_fetch_total{symbol, status}` (Counter) - Stock fetches tagged by status (success/rate_limited/error)
- `stock_api_duration` (Timer) - Alpha Vantage API response times
- `stock_rate_limit_exceeded` (Counter) - Rate limit hits
- `stock_cache_hits` / `stock_cache_misses` (Counters) - Cache performance

**Dashboard Panels:**
- Stock Rate Limit Hits: `sum(rate(stock_fetch_total{status="rate_limited"}[5m]))`
- Stock Fetch Status Breakdown: Pie chart of success/rate_limited/error over 1 hour
- Stock API Latency (p95): `histogram_quantile(0.95, rate(stock_api_duration_bucket[5m]))`

**Alerts:**
- `StockRateLimitExceeded`: Triggers when rate limited requests exceed 0.1/sec for 10 minutes
- `StockFetchErrorRateHigh`: Triggers when error rate exceeds 5% for 10 minutes

**Provider Limits:**
- Free tier: 5 API calls/min, 100 calls/day
- Premium tier: Higher limits (check subscription)

**Implemented in:** `StockService.java`

### 11.5 Social Integration Monitoring

**Metrics Implemented:**

- `social_feed_requests{platform, status}` (Counter) - Feed requests tagged by status (fresh/stale/disconnected)
- `social_api_failures{platform}` (Counter) - API failures per platform (Instagram/Facebook)
- `social_api_duration{platform}` (Timer) - Meta Graph API response times
- `social_feed_errors` (Counter) - General social feed errors

**Dashboard Panels:**
- Social Refresh Failures by Platform: `sum(rate(social_api_failures[5m])) by (platform)`
- Social Feed Request Status: `sum(rate(social_feed_requests[5m])) by (status)`

**Alerts:**
- `SocialRefreshFailures`: Triggers when failure rate exceeds 0.1/sec per platform for 10 minutes
- `SocialDisconnectedUsersHigh`: Info alert when disconnected requests exceed 1.0/sec for 15 minutes

**OAuth Token Management:**
- Meta refresh tokens valid for 60 days
- Automatic refresh recommended before expiration
- Users prompted to re-authenticate when tokens expire

**Implemented in:** `SocialIntegrationService.java`

### 11.6 Storage Gateway Monitoring

**Metrics Implemented:**

- `storage_uploads_total{bucket, status}` (Counter) - Upload outcomes (success/failure)
- `storage_bytes_uploaded{bucket}` (Counter) - Bytes uploaded per bucket
- `storage_upload_duration{bucket}` (Timer) - Upload latency
- `storage_downloads_total{bucket, status}` (Counter) - Download outcomes
- `storage_bytes_downloaded{bucket}` (Counter) - Download bandwidth
- `storage_download_duration{bucket}` (Timer) - Download latency
- `storage_signed_urls_total{bucket}` (Counter) - Signed URL generation count
- `storage_deletes_total{bucket, status}` (Counter) - Delete operations

**Dashboard Panels:**
- Storage Upload Error Rate: `sum(rate(storage_uploads_total{status="failure"}[5m])) by (bucket)`
- Storage Upload Latency (p95): `histogram_quantile(0.95, rate(storage_upload_duration_bucket[5m])) by (bucket)`
- Bytes Uploaded (Last Hour): `sum(increase(storage_bytes_uploaded[1h])) by (bucket)`

**Alerts:**
- `StorageGatewayErrors`: Triggers when upload error rate exceeds 5% for 10 minutes
- `StorageUploadLatencyHigh`: Triggers when p95 latency exceeds 10 seconds for 15 minutes

**Storage Buckets:**
- `screenshots`: Good Sites directory thumbnails
- `listings`: Marketplace listing images

**Provider:** Cloudflare R2 (S3-compatible)

**Implemented in:** `StorageGateway.java`

### 11.7 Dashboard Access

**Primary Dashboard:** [Content Services Dashboard](https://grafana.villagecompute.com/d/village-homepage-content-services)

**Dashboard Features:**
- 18 panels covering all content services
- Real-time metrics with 30-second refresh
- 6-hour time window (configurable)
- Alert annotations overlay
- Summary health table

**Dashboard File:** `docs/ops/dashboards/content-services.json`

### 11.8 Alert Configuration

**Alert Definitions File:** `docs/ops/alerts/content-services.yaml`

**Alert Groups:**
1. `content_services_ai_budget` - AI tagging budget alerts (3 rules)
2. `content_services_feeds` - RSS feed alerts (3 rules)
3. `content_services_weather` - Weather cache alerts (2 rules)
4. `content_services_stocks` - Stock API alerts (2 rules)
5. `content_services_social` - Social integration alerts (2 rules)
6. `content_services_storage` - Storage gateway alerts (2 rules)
7. `content_services_composite` - Multi-service health check (1 rule)

**Total Alert Rules:** 15 rules with proper annotations, runbook links, and dashboard links

### 11.9 Runbook Reference

**Runbook File:** `docs/ops/runbooks/content-monitoring.md`

**Runbook Contents:**
- Alert response procedures for all 15 alert rules
- Diagnosis steps with PromQL queries and kubectl commands
- Resolution procedures for common failure scenarios
- Escalation paths and severity definitions
- External dependency status pages
- Maintenance tasks (daily/weekly/monthly)
- Common troubleshooting scenarios

**Escalation Levels:**
- **Critical:** Immediate page (AI budget ≥100%, storage down, feed backlog >2000)
- **Warning:** Slack alert (AI budget >75%, feed backlog >500, weather >90min)
- **Info:** Dashboard only (cache hit rates, latency trends)

### 11.10 Deployment Checklist

When deploying content services monitoring to production:

1. **Metrics:** Verify all gauges/counters/timers are exported at `/q/metrics`
2. **Dashboard:** Import `content-services.json` to Grafana
3. **Alerts:** Deploy `content-services.yaml` to Prometheus Alertmanager
4. **Routing:** Configure Slack webhook for `#homepage-alerts` channel
5. **PagerDuty:** Add integration for critical-severity alerts
6. **Runbook:** Publish to internal wiki and link from alert annotations
7. **Testing:** Trigger test alerts to verify routing
8. **Documentation:** Update ops team training materials

---

## 10. Changelog

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-01-08 | Platform Squad | Initial observability baseline (I1.T8) |
| 1.1 | 2026-01-09 | Platform Squad | Added content services monitoring (I3.T9): RSS feeds, AI tagging, weather, stocks, social, storage gateway metrics, alerts, and runbook |
