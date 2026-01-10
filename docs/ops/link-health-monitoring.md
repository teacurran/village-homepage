# Link Health Monitoring Operations Guide

**Feature:** F13.5 (Link Health Check)
**Responsible Team:** Platform Services Guild
**On-Call Slack:** `#platform-feeds`

---

## 1. Overview

The link health monitoring system detects dead links in the Good Sites directory by performing periodic HTTP checks. Sites that fail health checks are marked as "dead" with warnings displayed to users.

### Key Components

- **LinkHealthCheckJobHandler** - Performs HTTP HEAD/GET requests to check link accessibility
- **LinkHealthCheckScheduler** - Enqueues weekly health check jobs (Sundays at 3am UTC)
- **DirectorySite.healthCheckFailures** - Consecutive failure counter (threshold: 3)
- **DirectorySite.isDead** - Dead link flag (UI displays warning when true)
- **DirectorySite.lastCheckedAt** - Last health check timestamp

---

## 2. Health Check Algorithm

### Check Flow

```
FOR each approved site:
  1. Attempt HTTP HEAD request (10 second timeout)
  2. If HEAD returns 405 Method Not Allowed, fall back to GET
  3. Status codes 200-399 = PASS, 400-599 = FAIL
  4. Update failure counter and lastCheckedAt timestamp
  5. If failures >= 3, mark site as dead
```

### Failure Counter Logic

| Current State | Check Result | New State | Action |
|---------------|-------------|-----------|--------|
| failures=0, isDead=false | PASS | failures=0 | Update timestamp |
| failures=1, isDead=false | PASS | failures=0 | Reset counter, log recovery |
| failures=0, isDead=false | FAIL | failures=1 | Increment, log warning |
| failures=2, isDead=false | FAIL | failures=3, isDead=true | Mark dead, notify moderators |
| failures=3, isDead=true | PASS | failures=0, isDead=true | Reset counter (manual restoration required) |

### HTTP Request Strategy

**Primary:** HEAD request to minimize bandwidth
**Fallback:** GET request if HEAD not allowed (status 405)
**Timeout:** 10 seconds per request
**Redirects:** Follow automatically (up to 5 hops)
**User-Agent:** `VillageCompute-LinkHealthChecker/1.0`

---

## 3. Job Execution

### Schedule

**Cadence:** Weekly
**Cron:** `0 0 3 ? * SUN` (Sundays at 3am UTC)
**Queue:** LOW
**Priority:** 7

### Performance Characteristics

| Metric | Value |
|--------|-------|
| Sites processed per run | ~10,000 (estimated production scale) |
| Batch size | 100 sites (to manage memory) |
| Timeout per site | 10 seconds |
| Expected duration | 10-30 minutes (depending on network latency) |
| Database writes | ~10,000 (one UPDATE per site) |
| Prometheus metrics | 4 counters + 1 timer |

### Resource Consumption

**CPU:** Low (mostly I/O wait)
**Memory:** ~50 MB (batch processing limits heap usage)
**Network:** ~1-5 MB total (HTTP HEAD responses are small)
**Database Connections:** 1 (uses @Transactional)

---

## 4. Monitoring & Alerting

### Prometheus Metrics

```
# Health check results
link_health.checks.total{result="success"}    # 200-399 status codes
link_health.checks.total{result="failed"}     # 400-599 status codes or exceptions
link_health.checks.total{result="recovered"}  # Dead sites that became accessible
link_health.checks.total{result="error"}      # Exception during check

# Job duration
link_health.check.duration                    # Timer metric
```

### Grafana Dashboard Queries

**Dead Link Rate:**
```promql
rate(link_health.checks.total{result="failed"}[1h]) /
rate(link_health.checks.total[1h]) * 100
```

**Recovery Rate:**
```promql
sum(increase(link_health.checks.total{result="recovered"}[7d]))
```

**Job Duration (P95):**
```promql
histogram_quantile(0.95, rate(link_health_check_duration_bucket[7d]))
```

### Alerts

**Critical: Job Failure**
```yaml
alert: LinkHealthCheckJobFailed
expr: delayed_jobs_failed_total{job_type="LINK_HEALTH_CHECK"} > 0
for: 5m
annotations:
  summary: "Link health check job failed"
  description: "Check delayed_jobs table for error details"
```

**Warning: High Dead Link Rate**
```yaml
alert: HighDeadLinkRate
expr: |
  (link_health.checks.total{result="failed"} /
   link_health.checks.total) > 0.10
for: 1h
annotations:
  summary: "More than 10% of sites failing health checks"
  description: "Investigate network issues or external site outages"
```

---

## 5. Operational Procedures

### 5.1 Manually Trigger Health Check

```bash
# SSH into k3s node
kubectl exec -it deployment/village-homepage -n production -- bash

# Enqueue job via psql
psql $DATABASE_URL -c "
  INSERT INTO delayed_jobs (job_type, queue, payload, status, created_at, updated_at)
  VALUES ('LINK_HEALTH_CHECK', 'LOW', '{}', 'pending', NOW(), NOW());
"
```

### 5.2 Review Dead Sites

```sql
-- List all dead sites
SELECT id, url, title, health_check_failures, last_checked_at
FROM directory_sites
WHERE is_dead = true
ORDER BY last_checked_at DESC;

-- Count dead sites by domain
SELECT domain, COUNT(*) as dead_count
FROM directory_sites
WHERE is_dead = true
GROUP BY domain
ORDER BY dead_count DESC
LIMIT 20;
```

### 5.3 Restore Dead Site (Manual Override)

If a site recovers but remains marked dead, moderators can manually restore it:

```sql
-- Verify site is accessible
-- curl -I https://recovered-site.com

-- Restore site status
UPDATE directory_sites
SET is_dead = false,
    status = 'approved',
    health_check_failures = 0,
    updated_at = NOW()
WHERE id = '<site-uuid>';
```

### 5.4 Bulk Delete Permanently Dead Sites

For sites that have been dead for 90+ days:

```sql
-- Identify candidates
SELECT id, url, title, last_checked_at,
       EXTRACT(EPOCH FROM (NOW() - last_checked_at)) / 86400 AS days_dead
FROM directory_sites
WHERE is_dead = true
  AND last_checked_at < NOW() - INTERVAL '90 days'
ORDER BY last_checked_at;

-- Soft delete (set status to 'deleted')
UPDATE directory_sites
SET status = 'deleted', updated_at = NOW()
WHERE is_dead = true
  AND last_checked_at < NOW() - INTERVAL '90 days';
```

---

## 6. Troubleshooting

### Issue: Job Hangs for Hours

**Cause:** One or more sites have very slow response times, blocking batch processing.

**Resolution:**
1. Check job logs for last processed site:
   ```bash
   kubectl logs -f deployment/village-homepage -n production | grep "checking site health"
   ```
2. Identify slow sites from logs
3. Add slow domains to exclusion list (future enhancement)
4. Manually mark slow sites as dead:
   ```sql
   UPDATE directory_sites
   SET is_dead = true, status = 'dead', updated_at = NOW()
   WHERE url LIKE '%slowdomain.com%';
   ```

### Issue: High False Positive Rate

**Cause:** Network issues, CDN misconfigurations, or overly aggressive firewalls.

**Resolution:**
1. Review recent failures:
   ```sql
   SELECT url, health_check_failures, last_checked_at
   FROM directory_sites
   WHERE health_check_failures > 0
     AND is_dead = false
   ORDER BY health_check_failures DESC, last_checked_at DESC
   LIMIT 50;
   ```
2. Manually test sites: `curl -I <url>`
3. If sites are accessible, adjust failure threshold (requires code change):
   ```java
   // In LinkHealthCheckJobHandler.java
   private static final int FAILURE_THRESHOLD = 5; // Increase from 3
   ```

### Issue: Dead Sites Not Recovering

**Cause:** Sites become accessible again but job doesn't run, or recovery logic has bug.

**Resolution:**
1. Check last job execution:
   ```sql
   SELECT id, job_type, status, created_at, started_at, completed_at
   FROM delayed_jobs
   WHERE job_type = 'LINK_HEALTH_CHECK'
   ORDER BY created_at DESC
   LIMIT 10;
   ```
2. Manually trigger health check (see section 5.1)
3. If job runs but sites don't recover, check counter reset logic:
   ```java
   // In LinkHealthCheckJobHandler.checkSiteHealth()
   if (accessible) {
       site.healthCheckFailures = 0; // Should reset to zero
       site.persist();
   }
   ```

### Issue: Out of Memory During Job

**Cause:** Batch size too large, or memory leak in HTTP client.

**Resolution:**
1. Check heap usage:
   ```bash
   kubectl exec deployment/village-homepage -n production -- \
     jcmd 1 GC.heap_info
   ```
2. Reduce batch size in code:
   ```java
   // In LinkHealthCheckJobHandler.java
   private static final int BATCH_SIZE = 50; // Reduce from 100
   ```
3. Restart pod to clear memory:
   ```bash
   kubectl rollout restart deployment/village-homepage -n production
   ```

---

## 7. Database Schema Reference

```sql
CREATE TABLE directory_sites (
    id UUID PRIMARY KEY,
    url TEXT NOT NULL,
    domain TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    status TEXT NOT NULL,  -- 'pending', 'approved', 'rejected', 'dead'
    is_dead BOOLEAN NOT NULL DEFAULT false,
    health_check_failures INT NOT NULL DEFAULT 0,  -- Consecutive failures
    last_checked_at TIMESTAMPTZ,  -- Last health check timestamp
    screenshot_url TEXT,
    screenshot_captured_at TIMESTAMPTZ,
    og_image_url TEXT,
    favicon_url TEXT,
    custom_image_url TEXT,
    submitted_by_user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_directory_sites_status ON directory_sites(status);
CREATE INDEX idx_directory_sites_is_dead ON directory_sites(is_dead);
CREATE INDEX idx_directory_sites_last_checked_at ON directory_sites(last_checked_at);
```

---

## 8. Future Enhancements

- **Parallel Processing:** Use ExecutorService to check multiple sites concurrently (limit concurrency to avoid rate limits)
- **Smart Retry:** Implement exponential backoff for transient errors (distinguish 5xx from 4xx)
- **Exclusion List:** Skip health checks for known-slow domains (e.g., government sites with aggressive rate limiting)
- **Email Notifications:** Send digest to moderators with dead link report (currently stubbed)
- **Auto-Recovery:** Automatically restore sites that pass health checks after being dead for < 7 days
- **Webhook Alerts:** POST to Slack/Discord when high-value sites (score > 50) go dead

---

## 9. Related Documentation

- **Good Sites UX Guide:** `docs/good-sites-ux-guide.md` (UI warning display)
- **Async Workloads:** `docs/ops/async-workloads.md` (job orchestration)
- **Ranking & Bubbling:** `docs/ops/link-health-monitoring.md` (this document)
- **Database Schema:** `migrations/scripts/20250111000100_add_health_check_failures.sql`

---

## 10. Contact & Support

**Primary On-Call:** Platform Services Guild (`#platform-feeds`)
**Escalation:** Engineering Manager (`#homepage-oncall`)
**Runbook:** https://wiki.villagecompute.com/runbooks/link-health-check
**PagerDuty Service:** `village-homepage-background-jobs`
