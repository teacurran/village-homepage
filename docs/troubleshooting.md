# Village Homepage - Troubleshooting Guide

**Version:** 1.0
**Last Updated:** 2026-01-24
**Owner:** Platform Squad
**Status:** Active

## Table of Contents

1. [Database Issues](#database-issues)
2. [High Request Latency](#high-request-latency)
3. [Email Delivery Issues](#email-delivery-issues)
4. [AI API Cost Spikes](#ai-api-cost-spikes)
5. [Background Job Queue Issues](#background-job-queue-issues)
6. [Authentication Issues](#authentication-issues)
7. [Search and Indexing Issues](#search-and-indexing-issues)
8. [Storage Gateway Issues](#storage-gateway-issues)
9. [Container and Deployment Issues](#container-and-deployment-issues)

---

## Database Issues

### Issue: Connection Refused

**Symptoms:**
- Application logs show `connection refused` errors
- Health check endpoint `/q/health/ready` returns 503
- `db_connections_active` metric shows 0

**Diagnostics:**

```bash
# Check database pod status
kubectl get pods -n databases | grep postgres

# Check database logs
kubectl logs -n databases postgres-pod

# Test connectivity from application pod
kubectl exec -it <app-pod> -n homepage-prod -- nc -zv 10.50.0.10 5432

# Check PostgreSQL status
kubectl exec -it postgres-pod -n databases -- pg_isready -U homepage_prod
```

**Solutions:**

1. **Database pod not running:**
   ```bash
   kubectl get pods -n databases
   kubectl describe pod postgres-pod -n databases
   # Check for OOMKilled, ImagePullBackOff, or CrashLoopBackOff
   ```

2. **Network policy blocking traffic:**
   ```bash
   # Verify NetworkPolicy allows traffic from homepage namespace
   kubectl get networkpolicy -n databases
   ```

3. **Incorrect database host/port in ConfigMap:**
   ```bash
   # Verify DB_HOST and DB_PORT
   kubectl get configmap app-config-homepage-prod -n homepage-prod -o yaml
   ```

4. **Firewall blocking traffic:**
   ```bash
   # Test from K3s node directly
   ssh tea@10.50.0.20
   nc -zv 10.50.0.10 5432
   ```

---

### Issue: Connection Pool Exhaustion

**Symptoms:**
- `db_connections_active` metric shows 50 (max pool size)
- Application logs show `Unable to acquire JDBC Connection`
- Request latency increases
- Alert: `DatabasePoolExhaustion` firing

**Diagnostics:**

```bash
# Check connection pool metrics
curl -s https://homepage.villagecompute.com/q/metrics | grep "db_connections"

# Check active database connections
kubectl exec -it postgres-pod -n databases -- psql -U postgres -c \
  "SELECT count(*) FROM pg_stat_activity WHERE datname='village_homepage_prod';"

# Check for long-running queries
kubectl exec -it postgres-pod -n databases -- psql -U postgres -c \
  "SELECT pid, usename, state, query_start, query FROM pg_stat_activity WHERE state = 'active' AND query_start < now() - interval '10 seconds';"

# Check for connection leaks in application
kubectl logs -n homepage-prod deployment/village-homepage | grep "leak"
```

**Solutions:**

1. **Scale application pods to distribute load:**
   ```bash
   kubectl scale deployment village-homepage --replicas=5 -n homepage-prod
   ```

2. **Increase connection pool size (requires restart):**
   - Edit `application.yaml` `%prod:` section
   - Increase `quarkus.datasource.jdbc.max-size` from 50 to 75
   - Redeploy application

3. **Kill long-running queries:**
   ```bash
   # Identify blocking queries
   kubectl exec -it postgres-pod -n databases -- psql -U postgres -c \
     "SELECT pid, query FROM pg_stat_activity WHERE state = 'active' AND query_start < now() - interval '1 minute';"

   # Kill specific query
   kubectl exec -it postgres-pod -n databases -- psql -U postgres -c \
     "SELECT pg_terminate_backend(<PID>);"
   ```

4. **Fix connection leaks in code:**
   - Review recent code changes for unclosed database connections
   - Check for missing `@Transactional` annotations
   - Review exception handling in database access code

---

### Issue: Slow Queries

**Symptoms:**
- P95 request latency > 1 second
- Database CPU usage > 80%
- Slow query logs show queries taking > 5 seconds

**Diagnostics:**

```bash
# Check slow query log
kubectl exec -it postgres-pod -n databases -- psql -U postgres -c \
  "SELECT query, calls, total_time, mean_time FROM pg_stat_statements ORDER BY mean_time DESC LIMIT 10;"

# Check for missing indexes
kubectl exec -it postgres-pod -n databases -- psql -U postgres -d village_homepage_prod -c \
  "SELECT schemaname, tablename, attname, n_distinct, correlation FROM pg_stats WHERE schemaname = 'public' ORDER BY correlation;"

# Check table bloat
kubectl exec -it postgres-pod -n databases -- psql -U postgres -d village_homepage_prod -c \
  "SELECT tablename, pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size FROM pg_tables WHERE schemaname = 'public' ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;"
```

**Solutions:**

1. **Add missing indexes:**
   - Identify slow queries from `pg_stat_statements`
   - Create migration to add indexes
   - Example: `CREATE INDEX idx_listings_location ON marketplace_listings (city_id, state_id);`

2. **Optimize query:**
   - Review Hibernate queries in code
   - Use Hibernate batch fetching: `@BatchSize(size = 25)`
   - Enable second-level cache for read-heavy entities

3. **Run VACUUM and ANALYZE:**
   ```bash
   kubectl exec -it postgres-pod -n databases -- psql -U postgres -d village_homepage_prod -c "VACUUM ANALYZE;"
   ```

4. **Partition large tables:**
   - Consider partitioning `feed_items` table by date
   - Partition `delayed_jobs` table by status

---

## High Request Latency

### Issue: P95 Latency > 1 Second

**Symptoms:**
- Alert: `HighRequestLatency` firing
- Users report slow page loads
- Jaeger traces show long request durations

**Diagnostics:**

```bash
# Check request latency metrics
curl -s https://homepage.villagecompute.com/q/metrics | grep "http_server_requests_seconds"

# Identify slowest endpoints
curl -s https://homepage.villagecompute.com/q/metrics | \
  grep 'http_server_requests_seconds{.*quantile="0.95"' | sort -t'=' -k3 -n

# Find slow traces in Jaeger
# Navigate to https://jaeger.villagecompute.com
# Filter: service=village-homepage, min-duration=1s
```

**Correlation ID for Tracing:**

Extract trace ID from response headers or logs:

```bash
# From application logs
kubectl logs -n homepage-prod deployment/village-homepage | grep "trace_id" | tail -1

# From response headers (X-B3-TraceId)
curl -v https://homepage.villagecompute.com/api/widgets 2>&1 | grep "X-B3-TraceId"

# View all logs for specific trace
kubectl logs -n homepage-prod deployment/village-homepage | grep "4bf92f3577b34da6a3ce929d0e0e4736"
```

**Solutions:**

1. **Slow database queries (see Database Issues above)**

2. **Slow external API calls:**
   - Check Jaeger spans for `http.client.*` operations
   - Review timeout configuration for REST clients
   - Add circuit breakers for unreliable external APIs

3. **AI API latency:**
   - Check `ai.tag_batch` span duration in Jaeger
   - Switch from Sonnet to Haiku model for non-critical tagging
   - Move AI tagging to async BULK queue

4. **Inefficient code:**
   - Review recent deployments for performance regressions
   - Profile application with JFR: `kubectl exec -it <app-pod> -n homepage-prod -- jcmd 1 JFR.start`
   - Analyze with JDK Mission Control

5. **Resource constraints:**
   - Check JVM memory metrics: `jvm_memory_used_bytes`
   - Check CPU throttling: `kubectl top pods -n homepage-prod`
   - Scale application pods or increase resource limits

---

## Email Delivery Issues

### Issue: Emails Not Sending

**Symptoms:**
- Users report not receiving emails
- Application logs show `SMTPException` errors
- Email notification jobs failing

**Diagnostics:**

```bash
# Check SMTP configuration
kubectl get configmap app-config-homepage-prod -n homepage-prod -o yaml | grep SMTP

# Check SMTP credentials
kubectl get secret smtp-secret-homepage-prod -n homepage-prod -o yaml

# Test SMTP connection from application pod
kubectl exec -it <app-pod> -n homepage-prod -- \
  nc -zv smtp.sendgrid.net 587

# Check email job failures
kubectl exec -it postgres-pod -n databases -- psql -U homepage_prod -d village_homepage_prod -c \
  "SELECT id, job_type, last_error FROM delayed_jobs WHERE job_type = 'EMAIL_SEND' AND failed = true ORDER BY run_at DESC LIMIT 10;"

# Check application logs for email errors
kubectl logs -n homepage-prod deployment/village-homepage | grep -i "email\|smtp"
```

**Solutions:**

1. **SMTP authentication failure:**
   - Verify SMTP credentials in secret
   - Check for password special characters (may need URL encoding)
   - Rotate SMTP password and update secret

2. **SMTP server unreachable:**
   - Test connectivity: `nc -zv smtp.sendgrid.net 587`
   - Check firewall rules
   - Verify DNS resolution: `nslookup smtp.sendgrid.net`

3. **TLS/SSL issues:**
   - Verify TLS configuration in `application.yaml` `%prod:` section
   - `quarkus.mailer.tls: true`
   - `quarkus.mailer.start-tls: REQUIRED`

4. **Rate limiting:**
   - Check SendGrid dashboard for rate limit errors
   - Implement email queue throttling
   - Upgrade SendGrid plan

---

### Issue: Email Bounce Rate High

**Symptoms:**
- SendGrid dashboard shows high bounce rate
- Users report not receiving emails at valid addresses

**Diagnostics:**

```bash
# Check bounce logs in SendGrid dashboard
# Navigate to SendGrid > Activity > Bounces

# Query bounced emails from database
kubectl exec -it postgres-pod -n databases -- psql -U homepage_prod -d village_homepage_prod -c \
  "SELECT email, bounce_reason, bounced_at FROM email_bounces ORDER BY bounced_at DESC LIMIT 20;"
```

**Solutions:**

1. **Invalid email addresses:**
   - Implement email validation on registration
   - Remove bounced emails from mailing lists

2. **Spam classification:**
   - Review email content for spam triggers
   - Add SPF, DKIM, DMARC records to DNS
   - Verify sender domain reputation

3. **Mailbox full:**
   - Retry bounced emails after 24 hours
   - Notify users to clear mailbox

---

## AI API Cost Spikes

### Issue: AI Budget Alert Triggered

**Symptoms:**
- Alert: `AIBudgetWarning` (75%) or `AIBudgetCritical` (90%)
- `homepage_ai_budget_consumed_percent` metric elevated
- Unexpected increase in `ai_tagging_items_total` counter

**Diagnostics:**

```bash
# Check current AI budget consumption
curl -s https://homepage.villagecompute.com/q/metrics | grep "homepage_ai_budget_consumed_percent"

# Check AI tagging rate
curl -s https://homepage.villagecompute.com/q/metrics | grep "ai_tagging_items_total"

# Check AI tagging job queue depth
curl -s https://homepage.villagecompute.com/q/metrics | grep 'homepage_jobs_depth{queue="BULK"}'

# Review AI API logs
kubectl logs -n homepage-prod deployment/village-homepage | grep -i "anthropic\|openai\|ai.tag"

# Check for cache misses
curl -s https://homepage.villagecompute.com/q/metrics | grep "ai_tagging_cache"
```

**Solutions:**

1. **Enable AI result caching:**
   - Verify `ai-tagging-cache` is enabled in `application.yaml`
   - Check cache hit rate in metrics
   - Increase cache size if necessary

2. **Switch to cheaper model:**
   - Change from Sonnet to Haiku for bulk tagging
   - Update `AI_MODEL_HAIKU` in ConfigMap
   - Restart application

3. **Reduce tagging frequency:**
   - Decrease RSS feed refresh frequency
   - Disable AI tagging for low-priority feeds
   - Batch AI tagging jobs (process 50 items per batch instead of 10)

4. **Pause AI tagging:**
   - Use admin API to disable AI tagging temporarily
   - Queue untagged content for next month

   ```bash
   curl -X POST https://homepage.villagecompute.com/admin/api/ai/pause \
     -H "Authorization: Bearer <ADMIN_TOKEN>"
   ```

5. **Request budget increase:**
   - Review AI spend in Anthropic/OpenAI dashboards
   - Justify business value of AI tagging
   - Update `AI_RATE_LIMIT_TOKENS_PER_DAY` in ConfigMap after approval

---

## Background Job Queue Issues

### Issue: Job Queue Backlog

**Symptoms:**
- Alert: `JobQueueBacklog` firing
- `homepage_jobs_depth` metric > 100
- Background jobs not processing

**Diagnostics:**

```bash
# Check queue depth by queue family
curl -s https://homepage.villagecompute.com/q/metrics | grep "homepage_jobs_depth"

# Check failed jobs
kubectl exec -it postgres-pod -n databases -- psql -U homepage_prod -d village_homepage_prod -c \
  "SELECT job_type, count(*) FROM delayed_jobs WHERE failed = true GROUP BY job_type;"

# Check oldest pending job
kubectl exec -it postgres-pod -n databases -- psql -U homepage_prod -d village_homepage_prod -c \
  "SELECT id, job_type, run_at, attempts FROM delayed_jobs WHERE failed = false AND run_at < now() ORDER BY run_at LIMIT 1;"

# Check job processing rate
kubectl logs -n homepage-prod deployment/village-homepage | grep "Job .* completed successfully"
```

**Solutions:**

1. **Scale application pods:**
   ```bash
   kubectl scale deployment village-homepage --replicas=5 -n homepage-prod
   ```

2. **Increase job worker concurrency:**
   - Update `DelayedJobService` configuration
   - Increase `@Scheduled(every = "10s")` to `every = "5s"`
   - Redeploy application

3. **Purge old failed jobs:**
   ```bash
   kubectl exec -it postgres-pod -n databases -- psql -U homepage_prod -d village_homepage_prod -c \
     "DELETE FROM delayed_jobs WHERE failed = true AND run_at < now() - interval '7 days';"
   ```

4. **Fix failing jobs:**
   - Review `last_error` column for failed jobs
   - Fix underlying issue (e.g., invalid feed URL, API credentials)
   - Retry failed jobs:
   ```bash
   kubectl exec -it postgres-pod -n databases -- psql -U homepage_prod -d village_homepage_prod -c \
     "UPDATE delayed_jobs SET failed = false, attempts = 0, last_error = null WHERE job_type = 'FEED_REFRESH' AND failed = true;"
   ```

---

### Issue: Screenshot Queue Backlog

**Symptoms:**
- Alert: `ScreenshotQueueBacklog` firing
- `homepage_jobs_depth{queue="SCREENSHOT"}` > 50
- `homepage_screenshot_slots_available` metric shows 0

**Diagnostics:**

```bash
# Check screenshot queue depth
curl -s https://homepage.villagecompute.com/q/metrics | grep 'homepage_jobs_depth{queue="SCREENSHOT"}'

# Check screenshot worker availability
curl -s https://homepage.villagecompute.com/q/metrics | grep "homepage_screenshot_slots_available"

# Check for screenshot job failures
kubectl exec -it postgres-pod -n databases -- psql -U homepage_prod -d village_homepage_prod -c \
  "SELECT id, payload, last_error FROM delayed_jobs WHERE job_type = 'SCREENSHOT_CAPTURE' AND failed = true ORDER BY run_at DESC LIMIT 5;"

# Check Chromium process health
kubectl exec -it <app-pod> -n homepage-prod -- ps aux | grep chromium
```

**Solutions:**

1. **Chromium process stuck:**
   - Restart application pod: `kubectl delete pod <app-pod> -n homepage-prod`
   - Check for zombie Chromium processes

2. **Increase screenshot worker concurrency:**
   - Edit `ScreenshotService` to increase semaphore permits from 3 to 5
   - Redeploy application
   - **WARNING:** Monitor memory usage (Chromium is memory-intensive)

3. **Timeout stuck screenshots:**
   - Check `ScreenshotJobHandler` timeout configuration
   - Increase timeout from 30s to 60s for slow-loading sites

4. **Purge failed screenshot jobs:**
   ```bash
   kubectl exec -it postgres-pod -n databases -- psql -U homepage_prod -d village_homepage_prod -c \
     "DELETE FROM delayed_jobs WHERE job_type = 'SCREENSHOT_CAPTURE' AND attempts > 5;"
   ```

---

## Authentication Issues

### Issue: OAuth Login Failures

**Symptoms:**
- Alert: `OIDCLoginFailures` firing
- Users report "Login failed" errors
- Logs show `OAuth state mismatch` or `Invalid token` errors

**Diagnostics:**

```bash
# Check OAuth login failure rate
curl -s https://homepage.villagecompute.com/q/metrics | grep 'http_server_requests_seconds_count{uri=~"/oauth2/.*",status=~"5.."}'

# Check OAuth provider status
# - Google: https://status.cloud.google.com/
# - Facebook: https://developers.facebook.com/status/
# - Apple: https://developer.apple.com/system-status/

# Check OAuth credentials
kubectl get secret oauth-secret-homepage-prod -n homepage-prod -o yaml

# Check application logs for OAuth errors
kubectl logs -n homepage-prod deployment/village-homepage | grep -i "oauth\|oidc"
```

**Solutions:**

1. **Invalid OAuth credentials:**
   - Verify client ID and secret in Kubernetes secret
   - Regenerate OAuth credentials in provider console
   - Update secret and restart application

2. **OAuth redirect URI mismatch:**
   - Verify redirect URI in provider console matches application configuration
   - Production: `https://homepage.villagecompute.com/api/auth/callback`
   - Check for trailing slashes or http vs https

3. **OAuth state encryption secret rotation:**
   - OIDC state secret must be stable across pod restarts
   - Verify `OIDC_STATE_SECRET` is set in Kubernetes secret
   - Do NOT rotate frequently (will break in-flight logins)

4. **OAuth provider rate limiting:**
   - Check provider dashboard for rate limit errors
   - Implement login throttling in application
   - Upgrade provider plan if necessary

---

### Issue: JWT Session Errors

**Symptoms:**
- Users logged out unexpectedly
- Logs show `Invalid JWT signature` or `JWT expired`
- Alert: `http_server_requests_seconds_count{uri="/api/*",status="401"}` elevated

**Diagnostics:**

```bash
# Check JWT-related errors in logs
kubectl logs -n homepage-prod deployment/village-homepage | grep -i "jwt"

# Check JWT secret configuration
kubectl get secret jwt-secret-homepage-prod -n homepage-prod -o yaml
```

**Solutions:**

1. **JWT session secret rotation:**
   - If the JWT session secret was rotated, all existing sessions are invalid
   - Users must re-login
   - Notify users of forced logout

2. **Clock skew:**
   - Verify system time is synchronized across K8s nodes
   - Check NTP configuration: `timedatectl status`

3. **JWT expiration too short:**
   - Default: 60 minutes (JWT_EXPIRATION_MINUTES)
   - Increase to 24 hours for production
   - Update ConfigMap and restart

---

## Search and Indexing Issues

### Issue: Search Results Missing or Stale

**Symptoms:**
- Users report search not returning expected results
- Recently created listings not appearing in search
- Elasticsearch index out of sync with database

**Diagnostics:**

```bash
# Check Elasticsearch health
curl -s "http://elasticsearch.villagecompute.com:9200/_cluster/health?pretty"

# Check index status
curl -s "http://elasticsearch.villagecompute.com:9200/homepage_*/_stats?pretty"

# Check Hibernate Search logs
kubectl logs -n homepage-prod deployment/village-homepage | grep -i "hibernate.search"

# Check automatic indexing configuration
kubectl logs -n homepage-prod deployment/village-homepage | grep "automatic-indexing"
```

**Solutions:**

1. **Elasticsearch cluster down:**
   - Check Elasticsearch pod status
   - Review Elasticsearch logs for errors
   - Restart Elasticsearch if necessary

2. **Automatic indexing disabled:**
   - Verify `quarkus.hibernate-search-orm.elasticsearch.automatic-indexing.enabled=true`
   - Check for `IndexingPlan` errors in logs

3. **Trigger full reindex:**
   ```bash
   curl -X POST https://homepage.villagecompute.com/admin/api/search/reindex \
     -H "Authorization: Bearer <ADMIN_TOKEN>"
   ```

4. **Check index mapping:**
   ```bash
   # View index mapping
   curl -s "http://elasticsearch.villagecompute.com:9200/homepage_listings/_mapping?pretty"

   # If mapping is incorrect, drop and recreate index
   curl -X DELETE "http://elasticsearch.villagecompute.com:9200/homepage_listings"
   # Restart application to auto-create index with correct mapping
   kubectl rollout restart deployment/village-homepage -n homepage-prod
   ```

---

## Storage Gateway Issues

### Issue: Image Upload Failures

**Symptoms:**
- Alert: `StorageGatewayErrors` firing
- Users report image upload errors
- `storage_uploads_total{status="failure"}` metric elevated

**Diagnostics:**

```bash
# Check storage upload error rate
curl -s https://homepage.villagecompute.com/q/metrics | grep 'storage_uploads_total{status="failure"}'

# Check S3 configuration
kubectl get configmap app-config-homepage-prod -n homepage-prod -o yaml | grep S3

# Test S3 connectivity from application pod
kubectl exec -it <app-pod> -n homepage-prod -- \
  curl -s https://r2.cloudflarestorage.com/villagecompute

# Check application logs for S3 errors
kubectl logs -n homepage-prod deployment/village-homepage | grep -i "s3\|storage"
```

**Solutions:**

1. **S3 credentials invalid:**
   - Verify S3 access key and secret key in Kubernetes secret
   - Regenerate credentials in Cloudflare R2 dashboard
   - Update secret and restart application

2. **S3 endpoint unreachable:**
   - Test connectivity: `curl https://r2.cloudflarestorage.com/villagecompute`
   - Check DNS resolution: `nslookup r2.cloudflarestorage.com`
   - Verify firewall rules allow outbound HTTPS

3. **Bucket does not exist:**
   - Verify bucket names in ConfigMap match actual buckets in R2
   - Create missing buckets in Cloudflare R2 dashboard

4. **Bucket permissions incorrect:**
   - Verify S3 access key has write permissions to bucket
   - Check bucket policy in Cloudflare R2 dashboard

---

## Container and Deployment Issues

### Issue: Pods Not Starting

**Symptoms:**
- Deployment rollout stuck
- Pods in `CrashLoopBackOff`, `ImagePullBackOff`, or `Pending` state
- Health checks failing

**Diagnostics:**

```bash
# Check pod status
kubectl get pods -n homepage-prod

# Describe pod for detailed error
kubectl describe pod <pod-name> -n homepage-prod

# Check events
kubectl get events -n homepage-prod --sort-by='.lastTimestamp'

# Check pod logs (even if not running)
kubectl logs <pod-name> -n homepage-prod --previous
```

**Solutions:**

1. **ImagePullBackOff (cannot pull container image):**
   - Verify image exists: `docker pull ghcr.io/villagecompute/village-homepage:v1.0.0`
   - Check container registry authentication
   - Create image pull secret if using private registry

2. **CrashLoopBackOff (application crashes on startup):**
   - Check logs for startup errors: `kubectl logs <pod-name> -n homepage-prod --previous`
   - Common causes: missing environment variables, database connection failure
   - Verify all required secrets and ConfigMaps exist

3. **Pending (cannot schedule pod):**
   - Check node resources: `kubectl describe nodes`
   - Check for insufficient CPU/memory
   - Check for node taints/tolerations

4. **Readiness probe failure:**
   - Check `/q/health/ready` endpoint
   - Verify database connectivity
   - Increase `initialDelaySeconds` in readiness probe if slow startup

---

### Issue: Deployment Rollout Stuck

**Symptoms:**
- `kubectl rollout status` shows "Waiting for rollout to finish"
- New pods not becoming ready
- Old pods not terminating

**Diagnostics:**

```bash
# Check rollout status
kubectl rollout status deployment/village-homepage -n homepage-prod

# Check pod status
kubectl get pods -n homepage-prod

# Check deployment history
kubectl rollout history deployment/village-homepage -n homepage-prod
```

**Solutions:**

1. **Rollback to previous version:**
   ```bash
   kubectl rollout undo deployment/village-homepage -n homepage-prod
   ```

2. **Force restart deployment:**
   ```bash
   kubectl rollout restart deployment/village-homepage -n homepage-prod
   ```

3. **Delete stuck pods:**
   ```bash
   kubectl delete pod <pod-name> -n homepage-prod --grace-period=0 --force
   ```

---

## Escalation

If issues cannot be resolved using this guide:

1. **Check related documentation:**
   - [Deployment Guide](./deployment.md)
   - [Operations Guide](./operations.md)
   - [Observability Guide](./ops/observability.md)

2. **Gather diagnostic information:**
   ```bash
   # Collect pod logs
   kubectl logs -n homepage-prod deployment/village-homepage --all-containers > logs.txt

   # Collect pod describe output
   kubectl describe pods -n homepage-prod > pods-describe.txt

   # Collect events
   kubectl get events -n homepage-prod --sort-by='.lastTimestamp' > events.txt
   ```

3. **Contact support:**
   - **Slack:** #homepage-alerts (team members)
   - **PagerDuty:** Page on-call engineer (critical issues only)
   - **Email:** ops@villagecompute.com

4. **Incident declaration:**
   - For production outages affecting users, declare incident in Slack: `/incident declare`
   - Follow incident response playbook

---

## Changelog

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-24 | Platform Squad | Initial troubleshooting guide (Task I6.T8) |
