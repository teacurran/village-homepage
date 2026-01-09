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

### 4.3 Future Metrics (Roadmap)

The following metrics will be added in subsequent iterations:

| Metric Name | Type | Description | Iteration |
|-------------|------|-------------|-----------|
| `homepage_feed_ingestion_seconds` | Timer | Feed refresh latency per source | I2 |
| `homepage_ai_tag_batches_total` | Counter | AI tagging batches processed | I3 |
| `homepage_screenshot_capture_seconds` | Histogram | Screenshot capture duration | I4 |
| `homepage_rate_limit_violations_total` | Meter | Rate-limit violations by bucket | I5 |

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

## 10. Changelog

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-01-08 | Platform Squad | Initial observability baseline (I1.T8) |
