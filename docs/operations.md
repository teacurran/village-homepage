# Village Homepage - Operations Guide

**Version:** 1.0
**Last Updated:** 2026-01-24
**Owner:** Platform Squad
**Status:** Active

## Table of Contents

1. [Monitoring Dashboards](#monitoring-dashboards)
2. [Key Metrics](#key-metrics)
3. [Alert Definitions](#alert-definitions)
4. [Scaling Guidelines](#scaling-guidelines)
5. [Backup and Restore](#backup-and-restore)
6. [Common Operational Tasks](#common-operational-tasks)
7. [Security Operations](#security-operations)

---

## Monitoring Dashboards

Village Homepage uses the VillageCompute observability stack (Prometheus, Grafana, Loki, Jaeger) for comprehensive monitoring.

### Dashboard Access

| Dashboard | URL | Purpose |
|-----------|-----|---------|
| **Grafana Home** | https://grafana.villagecompute.com | Main dashboard portal |
| **Application Overview** | https://grafana.villagecompute.com/d/village-homepage-overview | High-level health metrics |
| **Content Services** | https://grafana.villagecompute.com/d/village-homepage-content-services | RSS feeds, AI tagging, weather, stocks, social |
| **Database Performance** | https://grafana.villagecompute.com/d/village-homepage-database | Connection pool, query performance |
| **Job Queue Monitoring** | https://grafana.villagecompute.com/d/village-homepage-jobs | Background job queue depth and processing |
| **AI Budget Tracking** | https://grafana.villagecompute.com/d/village-homepage-ai-budget | AI API consumption and cost tracking |
| **Logs (Loki)** | https://grafana.villagecompute.com/d/logs | Structured log aggregation |
| **Jaeger Tracing** | https://jaeger.villagecompute.com | Distributed request tracing |

### Dashboard Features

#### Application Overview Dashboard

Displays:
- **Request Rate:** HTTP requests per second (by endpoint, status code)
- **Latency:** P50, P95, P99 response times
- **Error Rate:** 4xx and 5xx errors per second
- **Active Connections:** Current HTTP connections
- **JVM Metrics:** Heap memory, GC activity, thread count
- **Database Pool:** Active connections, pool utilization

#### Content Services Dashboard

Displays (see [docs/ops/observability.md](./ops/observability.md#11-content-services-monitoring-i3t9)):
- RSS feed ingestion throughput
- AI tagging budget consumption (gauge)
- Weather cache staleness
- Stock API rate limit hits
- Social feed refresh status
- Storage gateway upload/download metrics

#### Database Performance Dashboard

Displays:
- **Connection Pool Utilization:** Active vs available connections
- **Query Performance:** Slow query count, average query time
- **Transaction Metrics:** Commits, rollbacks, deadlocks
- **Hibernate Second-Level Cache:** Hit rate, eviction rate

#### Job Queue Monitoring Dashboard

Displays:
- **Queue Depth by Queue Family:** DEFAULT, HIGH, LOW, BULK, SCREENSHOT
- **Job Processing Rate:** Jobs processed per second
- **Job Failure Rate:** Failed jobs per second
- **Screenshot Worker Slots:** Available worker permits (max 3)

---

## Key Metrics

For a complete metrics catalog, see [docs/ops/observability.md](./ops/observability.md#42-metrics-catalog).

### Critical Production Metrics

Monitor these metrics closely in production:

| Metric | Type | Alert Threshold | Normal Range | Dashboard |
|--------|------|----------------|--------------|-----------|
| `http_server_requests_seconds{quantile="0.95"}` | Gauge | > 1s for 5min | 100-300ms | Application Overview |
| `db_connections_active` | Gauge | > 45 (90% of pool) | 5-20 | Database Performance |
| `homepage_jobs_depth` | Gauge | > 100 for 10min | 0-50 | Job Queue Monitoring |
| `homepage_ai_budget_consumed_percent` | Gauge | > 90% | 0-75% | AI Budget Tracking |
| `jvm_memory_used_bytes{area="heap"}` | Gauge | > 1.8GB (90% of limit) | 500MB-1.5GB | Application Overview |
| `http_server_requests_seconds_count{status=~"5.."}` | Counter | Rate > 1/sec for 5min | < 0.1/sec | Application Overview |
| `homepage_screenshot_slots_available` | Gauge | < 1 for 10min | 2-3 | Job Queue Monitoring |

### Business Metrics

Track these for capacity planning and product analytics:

| Metric | Type | Purpose |
|--------|------|---------|
| `http_server_requests_seconds_count{uri="/api/widgets"}` | Counter | Widget creation rate |
| `homepage_jobs_depth{queue="FEED_REFRESH"}` | Gauge | RSS feed backlog |
| `ai_tagging_items_total{status="success"}` | Counter | AI tagging throughput |
| `storage_bytes_uploaded{bucket="listings"}` | Counter | Marketplace listing uploads |
| `social_feed_requests{status="fresh"}` | Counter | Active social integrations |

---

## Alert Definitions

For complete alert configurations, see [docs/ops/observability.md](./ops/observability.md#5-alerting).

### Critical Alerts (PagerDuty)

These alerts trigger immediate pager notification:

#### High Request Latency

```yaml
- alert: HighRequestLatency
  expr: histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) > 1
  for: 5m
  labels:
    severity: critical
    team: platform
  annotations:
    summary: "P95 request latency exceeds 1 second"
    description: "Current P95: {{ $value }}s. Investigate slow queries or external API issues."
    runbook_url: "https://wiki.villagecompute.com/runbooks/high-latency"
```

**Response Procedure:**
1. Check Jaeger traces for slow requests
2. Query database for slow queries: `kubectl exec -it <postgres-pod> -- psql -c "SELECT * FROM pg_stat_activity WHERE state = 'active' AND query_start < now() - interval '5 seconds';"`
3. Check external API status (Anthropic, OpenAI, Alpha Vantage)
4. Review recent deployments for performance regressions
5. Scale application pods if necessary

#### Database Pool Exhaustion

```yaml
- alert: DatabasePoolExhaustion
  expr: db_connections_active > 45
  for: 3m
  labels:
    severity: critical
    team: platform
  annotations:
    summary: "Database connection pool near exhaustion"
    description: "Active connections: {{ $value }}/50. Application may start queueing requests."
    runbook_url: "https://wiki.villagecompute.com/runbooks/db-pool-exhaustion"
```

**Response Procedure:**
1. Check for connection leaks in application logs
2. Review long-running transactions: `SELECT * FROM pg_stat_activity WHERE state = 'active';`
3. Scale application pods to distribute connection load
4. Increase pool size if sustained high load (requires restart)
5. Investigate query performance issues

#### AI Budget Exhausted

```yaml
- alert: AIBudgetExceeded
  expr: homepage_ai_budget_consumed_percent >= 100
  for: 1m
  labels:
    severity: critical
    team: platform
    pagerduty: "true"
  annotations:
    summary: "AI tagging budget exceeded - HARD STOP"
    description: "AI tagging has been disabled. No further API calls will be made until next month."
    runbook_url: "https://wiki.villagecompute.com/runbooks/ai-budget-exceeded"
```

**Response Procedure:**
1. Verify hard stop is in effect (check `ai_tagging_budget_throttles` metric)
2. Notify product team of suspended AI tagging
3. Review budget allocation for next month
4. Consider manually increasing budget if critical (requires approval)
5. Queue untagged content for next month

### Warning Alerts (Slack Only)

These alerts notify the team via Slack but do not page:

- **Job Queue Backlog:** Queue depth > 100 for 10 minutes
- **AI Budget Warning:** Budget consumption > 75%
- **Screenshot Queue Backlog:** SCREENSHOT queue depth > 50 for 10 minutes
- **Stock Rate Limit Exceeded:** Alpha Vantage rate limiting active
- **Weather Cache Staleness:** Cache age > 90 minutes
- **Social Refresh Failures:** Facebook/Instagram API errors > 0.1/sec

### Alert Routing

Production alerts are routed via Prometheus Alertmanager:

| Severity | Slack Channel | PagerDuty | Email |
|----------|---------------|-----------|-------|
| **Critical** | #homepage-alerts | Yes (on-call rotation) | ops@villagecompute.com |
| **Warning** | #homepage-alerts | No | - |
| **Info** | - | No | - |

---

## Scaling Guidelines

### Horizontal Pod Autoscaling (HPA)

Village Homepage uses Horizontal Pod Autoscaler for automatic scaling based on CPU and memory utilization.

#### HPA Configuration (Production)

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: village-homepage-hpa
  namespace: homepage-prod
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: village-homepage
  minReplicas: 3
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

#### Manual Scaling

```bash
# Scale to specific replica count
kubectl scale deployment village-homepage --replicas=5 -n homepage-prod

# Verify scaling
kubectl get pods -n homepage-prod
```

### When to Scale Up

Scale up (increase replicas) when:
- CPU utilization > 70% for 5+ minutes
- Memory utilization > 80% for 5+ minutes
- Request latency P95 > 500ms for 10+ minutes
- Active connections approaching max (100)
- Job queue depth > 200 for 15+ minutes

### When to Scale Down

Scale down (decrease replicas) when:
- CPU utilization < 30% for 15+ minutes
- Memory utilization < 40% for 15+ minutes
- Request rate < 10 req/sec for 30+ minutes
- Off-peak hours (2am-6am ET)

### Database Connection Pool Tuning

**Current Production Configuration:**
- Max connections: 50
- Min connections: 10
- Initial connections: 10

**When to Increase Pool Size:**
- Pool exhaustion alerts firing frequently
- Application scaling beyond 5 replicas (10 connections per pod)
- Database has spare capacity (check `pg_stat_activity`)

**How to Increase Pool Size:**

1. Update `application.yaml` in `%prod:` section:
   ```yaml
   quarkus:
     datasource:
       jdbc:
         max-size: 75  # Increased from 50
         min-size: 15  # Increased from 10
   ```

2. Rebuild and deploy application

3. Monitor connection usage: `SELECT count(*) FROM pg_stat_activity;`

**WARNING:** Do not exceed PostgreSQL `max_connections` setting (default 100). Reserve 20-30 connections for admin tasks.

---

## Backup and Restore

### PostgreSQL Backup

#### Backup Schedule

| Backup Type | Frequency | Retention | Location |
|-------------|-----------|-----------|----------|
| **Full Backup** | Daily (2am ET) | 30 days | S3: `s3://villagecompute-backups/homepage-prod/` |
| **WAL Archive** | Continuous (15min) | 30 days | S3: `s3://villagecompute-backups/homepage-prod/wal/` |
| **On-Demand Snapshot** | Manual | 7 days | S3: `s3://villagecompute-backups/homepage-prod/snapshots/` |

#### Manual Backup

```bash
# Create on-demand backup
kubectl exec -it postgres-pod -n databases -- \
  pg_dump -U homepage_prod -d village_homepage_prod -F c -f /tmp/backup.dump

# Copy backup to local machine
kubectl cp databases/postgres-pod:/tmp/backup.dump ./homepage-backup-$(date +%Y%m%d).dump

# Upload to S3
aws s3 cp homepage-backup-$(date +%Y%m%d).dump \
  s3://villagecompute-backups/homepage-prod/snapshots/
```

#### Restore from Backup

**WARNING:** Restoring will overwrite all data. Coordinate with team and notify users.

```bash
# Download backup from S3
aws s3 cp s3://villagecompute-backups/homepage-prod/snapshots/homepage-backup-20260124.dump \
  ./restore.dump

# Copy to PostgreSQL pod
kubectl cp ./restore.dump databases/postgres-pod:/tmp/restore.dump

# Restore database (drops and recreates)
kubectl exec -it postgres-pod -n databases -- \
  pg_restore -U homepage_prod -d village_homepage_prod -c -F c /tmp/restore.dump
```

### Elasticsearch Backup

#### Backup Schedule

| Backup Type | Frequency | Retention | Location |
|-------------|-----------|-----------|----------|
| **Index Snapshot** | Daily (3am ET) | 14 days | Elasticsearch snapshot repository |

#### Manual Snapshot

```bash
# Create snapshot
curl -X PUT "elasticsearch.villagecompute.com:9200/_snapshot/homepage_backup/snapshot_$(date +%Y%m%d)" \
  -H 'Content-Type: application/json' \
  -d '{"indices": "homepage_*", "ignore_unavailable": true, "include_global_state": false}'

# Check snapshot status
curl -X GET "elasticsearch.villagecompute.com:9200/_snapshot/homepage_backup/snapshot_$(date +%Y%m%d)"
```

#### Restore from Snapshot

```bash
# Close indices before restore
curl -X POST "elasticsearch.villagecompute.com:9200/homepage_*/_close"

# Restore snapshot
curl -X POST "elasticsearch.villagecompute.com:9200/_snapshot/homepage_backup/snapshot_20260124/_restore" \
  -H 'Content-Type: application/json' \
  -d '{"indices": "homepage_*", "ignore_unavailable": true, "include_global_state": false}'

# Open indices after restore
curl -X POST "elasticsearch.villagecompute.com:9200/homepage_*/_open"
```

### Object Storage Backup (S3/R2)

Object storage (Cloudflare R2) has built-in versioning and replication. No manual backups required.

**Versioning Status:**
- `homepage-screenshots-prod`: Enabled (90-day retention)
- `homepage-listings-prod`: Enabled (90-day retention)
- `homepage-profiles-prod`: Enabled (90-day retention)

**To Restore Deleted Object:**

```bash
# List object versions
aws s3api list-object-versions \
  --bucket homepage-listings-prod \
  --prefix listings/12345.webp

# Restore specific version
aws s3api copy-object \
  --bucket homepage-listings-prod \
  --copy-source homepage-listings-prod/listings/12345.webp?versionId=<VERSION_ID> \
  --key listings/12345.webp
```

---

## Common Operational Tasks

### Viewing Application Logs

#### Real-Time Logs (kubectl)

```bash
# Tail logs from all pods
kubectl logs -n homepage-prod deployment/village-homepage --tail=100 -f

# Tail logs from specific pod
kubectl logs -n homepage-prod <pod-name> --tail=100 -f

# View logs from previous container (after crash)
kubectl logs -n homepage-prod <pod-name> --previous
```

#### Historical Logs (Grafana Loki)

1. Navigate to https://grafana.villagecompute.com/d/logs
2. Filter by `service.name=village-homepage`
3. Add filters:
   - `trace_id` - View all logs for specific request
   - `user_id` - View all logs for specific user
   - `job_id` - View all logs for specific background job
   - `level` - Filter by log level (INFO, WARN, ERROR)

#### Search Logs by Trace ID

```bash
# Find all logs for specific trace
kubectl logs -n homepage-prod deployment/village-homepage | grep "4bf92f3577b34da6a3ce929d0e0e4736"
```

### Checking Job Queue Status

#### View Queue Depth (Metrics)

```bash
# Check queue depth for all queues
curl -s https://homepage.villagecompute.com/q/metrics | grep "homepage_jobs_depth"

# Example output:
# homepage_jobs_depth{queue="DEFAULT"} 12
# homepage_jobs_depth{queue="HIGH"} 0
# homepage_jobs_depth{queue="LOW"} 45
# homepage_jobs_depth{queue="BULK"} 150
# homepage_jobs_depth{queue="SCREENSHOT"} 8
```

#### View Failed Jobs (Database)

```bash
# Connect to PostgreSQL
kubectl exec -it postgres-pod -n databases -- psql -U homepage_prod -d village_homepage_prod

# Query failed jobs
SELECT id, job_type, run_at, attempts, last_error
FROM delayed_jobs
WHERE failed = true
ORDER BY run_at DESC
LIMIT 20;
```

### Manually Triggering Background Jobs

#### Trigger Feed Refresh Job

```bash
# Use admin API (requires authentication)
curl -X POST https://homepage.villagecompute.com/admin/api/jobs/trigger-feed-refresh \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"source_id": 123}'
```

#### Trigger AI Tagging Job

```bash
# Trigger bulk AI tagging
curl -X POST https://homepage.villagecompute.com/admin/api/jobs/trigger-ai-tagging \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"batch_size": 50}'
```

### Clearing Caches

#### Clear Hibernate Second-Level Cache

```bash
# Restart application pods (cache is in-memory)
kubectl rollout restart deployment/village-homepage -n homepage-prod
```

#### Clear Application Caches (AI Tagging, Weather, Stock)

```bash
# Use admin API to clear specific caches
curl -X POST https://homepage.villagecompute.com/admin/api/cache/clear \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"cache_name": "ai-tagging-cache"}'

# Available cache names:
# - ai-tagging-cache
# - ai-embedding-cache
# - weather-cache (managed by WeatherService)
# - stock-cache (managed by StockService)
```

### Reindexing Elasticsearch

If Elasticsearch indices become corrupted or out of sync:

```bash
# Use admin API to trigger full reindex
curl -X POST https://homepage.villagecompute.com/admin/api/search/reindex \
  -H "Authorization: Bearer <ADMIN_TOKEN>"

# Monitor reindex progress in logs
kubectl logs -n homepage-prod deployment/village-homepage | grep "reindex"
```

### Managing Feature Flags

#### View Feature Flag Status

```bash
# List all feature flags
curl -s https://homepage.villagecompute.com/admin/api/feature-flags \
  -H "Authorization: Bearer <ADMIN_TOKEN>" | jq .
```

#### Update Feature Flag

```bash
# Enable feature flag
curl -X PATCH https://homepage.villagecompute.com/admin/api/feature-flags/stocks_widget \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"enabled": true, "rollout_percentage": 100}'

# Gradual rollout (10% of users)
curl -X PATCH https://homepage.villagecompute.com/admin/api/feature-flags/social_integration \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"enabled": true, "rollout_percentage": 10}'
```

---

## Security Operations

### Monitoring for Security Incidents

#### Failed Login Attempts

```bash
# Query logs for failed logins
kubectl logs -n homepage-prod deployment/village-homepage | grep "login failed"

# View failed login rate (last 5 minutes)
curl -s https://homepage.villagecompute.com/q/metrics | \
  grep 'http_server_requests_seconds_count{uri="/api/auth/login",status="401"}'
```

#### Suspicious API Access

```bash
# High rate of 401 Unauthorized responses
curl -s https://homepage.villagecompute.com/q/metrics | \
  grep 'http_server_requests_seconds_count{status="401"}'

# High rate of 403 Forbidden responses
curl -s https://homepage.villagecompute.com/q/metrics | \
  grep 'http_server_requests_seconds_count{status="403"}'
```

### Secret Rotation

#### Rotate JWT Session Secret

```bash
# Generate new secret
NEW_SECRET=$(openssl rand -hex 32)

# Update Kubernetes secret
kubectl create secret generic jwt-secret-homepage-prod \
  --from-literal=JWT_SESSION_SECRET=$NEW_SECRET \
  --dry-run=client -o yaml | kubectl apply -f -

# Rollout restart to pick up new secret
kubectl rollout restart deployment/village-homepage -n homepage-prod
```

**WARNING:** Rotating the JWT session secret will invalidate all existing user sessions.

#### Rotate Database Password

```bash
# Update password in PostgreSQL
kubectl exec -it postgres-pod -n databases -- psql -U postgres

# In psql:
ALTER USER homepage_prod WITH PASSWORD 'YOUR_NEW_PASSWORD_HERE';

# Update Kubernetes secret
kubectl create secret generic db-secret-homepage-prod \
  --from-literal=DB_USERNAME=homepage_prod \
  --from-literal=DB_PASSWORD=YOUR_NEW_PASSWORD_HERE \
  --dry-run=client -o yaml | kubectl apply -f -

# Rollout restart
kubectl rollout restart deployment/village-homepage -n homepage-prod
```

### Incident Response

For security incidents:

1. **Immediate Actions:**
   - Scale down application to stop traffic: `kubectl scale deployment village-homepage --replicas=0 -n homepage-prod`
   - Block malicious IPs at Cloudflare level
   - Notify security team via Slack and PagerDuty

2. **Investigation:**
   - Collect logs: `kubectl logs -n homepage-prod deployment/village-homepage --all-containers > incident-logs.txt`
   - Query audit trail in database: `SELECT * FROM audit_log WHERE timestamp > '2026-01-24 12:00:00';`
   - Review access logs in Cloudflare

3. **Remediation:**
   - Apply security patches
   - Rotate compromised secrets
   - Update security policies

4. **Recovery:**
   - Scale application back up: `kubectl scale deployment village-homepage --replicas=3 -n homepage-prod`
   - Monitor for suspicious activity

---

## References

- **Deployment Guide:** [docs/deployment.md](./deployment.md) - Deployment procedures and rollback
- **Troubleshooting Guide:** [docs/troubleshooting.md](./troubleshooting.md) - Common issues and solutions
- **Observability Guide:** [docs/ops/observability.md](./ops/observability.md) - Detailed metrics catalog and alerting
- **CI/CD Pipeline:** [docs/ops/ci-cd-pipeline.md](./ops/ci-cd-pipeline.md) - Build and test workflow

---

## Changelog

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-24 | Platform Squad | Initial operations guide (Task I6.T8) |
