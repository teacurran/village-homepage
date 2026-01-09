# Content Services Monitoring Runbook

**Version:** 1.0
**Last Updated:** 2026-01-09
**Owner:** Platform Squad
**Iteration:** I3

## Overview

This runbook covers monitoring, alerting, and troubleshooting procedures for Village Homepage content aggregation services implemented in Iteration I3:

- **RSS Feed Ingestion** - News feed parsing and item storage
- **AI Tagging** - LangChain4j-powered content categorization with budget enforcement
- **Weather Service** - Open-Meteo and NWS forecast caching
- **Stock Market Data** - Alpha Vantage integration with rate limiting
- **Social Integration** - Instagram/Facebook feed refresh via Meta Graph API
- **Storage Gateway** - S3/R2 object storage for screenshots and listings

## Quick Reference

### Dashboards

- **Content Services Dashboard**: https://grafana.villagecompute.com/d/village-homepage-content-services
- **Jobs Dashboard**: https://grafana.villagecompute.com/d/village-homepage-jobs
- **Infrastructure Dashboard**: https://grafana.villagecompute.com/d/village-homepage-infra

### Key Metrics

| Metric | Alert Threshold | Normal Range |
|--------|----------------|--------------|
| `homepage_ai_budget_consumed_percent` | >75% warning, >90% critical, ≥100% hard stop | 0-75% |
| `homepage_jobs_depth{queue="DEFAULT"}` | >500 for 10min | 0-100 |
| `homepage_weather_cache_staleness_minutes` | >90min | 0-60min |
| `stock_fetch_total{status="rate_limited"}` | >0.1/sec sustained | 0 |
| `social_api_failures` | >0.1/sec per platform | <0.01/sec |
| `storage_uploads_total{status="failure"}` | >5% error rate | <1% |

### On-Call Contacts

- **Primary Escalation**: Platform Squad Slack channel (#homepage-alerts)
- **PagerDuty**: Critical alerts only (AI budget ≥100%, storage gateway down)
- **Business Hours Support**: ops@villagecompute.com
- **After Hours**: PagerDuty rotation

---

## Alert Response Procedures

### AIBudgetWarning / AIBudgetCritical / AIBudgetExceeded

**Alert Names:**
- `AIBudgetWarning` - 75% consumption
- `AIBudgetCritical` - 90% consumption
- `AIBudgetExceeded` - 100% consumption (HARD STOP)

**Symptoms:**
- AI budget consumption approaching or exceeding monthly ceiling ($500/month default)
- At 75%: Early warning, REDUCE state may trigger soon
- At 90%: QUEUE state active, new tagging jobs queued for next month
- At 100%: HARD_STOP state, all AI tagging halted

**Impact:**
- **75-90%**: Tagging continues but at reduced batch sizes (lower throughput)
- **90-100%**: New tagging jobs queued, backlog builds up
- **≥100%**: All tagging stopped, news items lack AI-generated topics

**Diagnosis:**

1. **Check current budget consumption:**
   ```bash
   curl -s http://homepage.villagecompute.com/q/metrics | grep homepage_ai_budget_consumed_percent
   ```

2. **Review recent tagging volume:**
   ```promql
   sum(rate(ai_tagging_items_total[1h]))
   ```

3. **Check for batch size spikes:**
   ```bash
   kubectl logs -l app=village-homepage --tail=100 | grep "AI tagging batch"
   ```

4. **Inspect AI service logs for cost tracking:**
   ```bash
   kubectl logs -l app=village-homepage | grep "AI cost tracking" | tail -20
   ```

5. **Query admin API for detailed breakdown:**
   ```bash
   curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://homepage.villagecompute.com/admin/api/ai/usage
   ```

**Resolution:**

**If budget exhaustion is expected (legitimate traffic):**

1. **Increase monthly budget** (requires config change + restart):
   ```yaml
   # Update application.yaml or environment variable
   villagecompute:
     ai:
       monthly-budget-dollars: 1000  # Increase from default $500
   ```

2. **Deploy updated configuration:**
   ```bash
   kubectl set env deployment/village-homepage VILLAGECOMPUTE_AI_MONTHLY_BUDGET_DOLLARS=1000
   kubectl rollout status deployment/village-homepage
   ```

3. **Verify budget metric updates:**
   ```bash
   # Should show reduced percentage after ceiling increase
   curl -s http://homepage.villagecompute.com/q/metrics | grep homepage_ai_budget_consumed_percent
   ```

**If budget exhaustion is anomalous:**

1. **Disable AI tagging via feature flag** (immediate mitigation):
   ```bash
   curl -X PATCH \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     https://homepage.villagecompute.com/admin/api/feature-flags/ai_tagging \
     -d '{"enabled": false}'
   ```

2. **Investigate recent changes:**
   - Check for new feed sources with high volume
   - Review batch size configuration changes
   - Look for duplicate processing bugs

3. **Review prompt engineering efficiency:**
   - Analyze token usage per tagging request
   - Consider shorter system prompts if appropriate
   - Check for unnecessary context in prompts

**If at HARD_STOP (≥100%):**

1. **Immediate action required** - choose one:
   - Increase budget (production approval needed)
   - Disable feature flag (degrades UX but stops spend)
   - Wait until next month (budget resets automatically)

2. **Clear queued jobs if disabling tagging:**
   ```sql
   -- Connect to production DB (requires ops credentials)
   DELETE FROM delayed_jobs
   WHERE handler LIKE '%AiTaggingJobHandler%'
   AND locked_at IS NULL;
   ```

**Escalation:**
- **75-90%**: Inform Platform Squad lead, no immediate escalation needed
- **90-100%**: Escalate to Product Manager for budget increase approval
- **≥100%**: Page on-call if tagging is business-critical, otherwise handle during business hours

**Post-Incident:**
- Document actual spend vs budget
- Review batch size and frequency configurations
- Consider graduated budget increases for growth

---

### FeedBacklog

**Alert Name:** `FeedBacklog`

**Symptoms:**
- More than 500 pending RSS refresh jobs in DEFAULT queue for >10 minutes
- News widget content stale across user homepages
- Job queue depth gauge elevated on dashboard

**Impact:**
- Users see outdated news content
- Feed refresh intervals not being met
- Potential cascade to AI tagging backlog

**Diagnosis:**

1. **Check job queue depth:**
   ```promql
   sum(homepage_jobs_depth{queue="DEFAULT"})
   ```

2. **Review error logs for failing sources:**
   ```bash
   kubectl logs -l app=village-homepage --tail=200 | grep "RssFeedRefreshJobHandler"
   ```

3. **Query Prometheus for error breakdown:**
   ```promql
   sum(rate(rss_fetch_errors_total[5m])) by (error_type)
   ```

4. **Identify slowest/failing feed sources:**
   ```promql
   topk(10, sum(rate(rss_fetch_errors_total[1h])) by (source_id))
   ```

5. **Check worker pool utilization:**
   ```bash
   kubectl top pods -l app=village-homepage
   ```

**Resolution:**

**If specific sources are failing:**

1. **Identify problematic sources via admin UI:**
   ```bash
   curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://homepage.villagecompute.com/admin/api/feeds?status=failing
   ```

2. **Temporarily disable failing sources:**
   ```bash
   # Via admin API
   curl -X PATCH \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://homepage.villagecompute.com/admin/api/feeds/{source_id} \
     -d '{"enabled": false}'
   ```

3. **Clear backlogged jobs for disabled sources:**
   ```sql
   -- Via database (ops credentials required)
   DELETE FROM delayed_jobs
   WHERE handler LIKE '%RssFeedRefreshJobHandler%'
   AND handler LIKE '%"sourceId":{source_id}%'
   AND locked_at IS NULL;
   ```

**If worker pool is exhausted:**

1. **Scale up worker pods:**
   ```bash
   kubectl scale deployment/village-homepage --replicas=5
   kubectl rollout status deployment/village-homepage
   ```

2. **Monitor queue drainage:**
   ```promql
   sum(homepage_jobs_depth{queue="DEFAULT"})
   ```

3. **Review job execution duration:**
   ```promql
   histogram_quantile(0.95, rate(rss_fetch_duration_bucket[5m]))
   ```

**If network/infrastructure issues:**

1. **Check cluster network health:**
   ```bash
   kubectl get nodes -o wide
   kubectl describe node <node-name> | grep -A5 Conditions
   ```

2. **Verify DNS resolution:**
   ```bash
   kubectl run -it --rm debug --image=busybox --restart=Never -- nslookup google.com
   ```

3. **Test external connectivity from pod:**
   ```bash
   kubectl exec -it deployment/village-homepage -- curl -I https://feeds.example.com
   ```

**Escalation:**
- **500-1000 jobs**: Platform Squad handles during business hours
- **>1000 jobs**: Page on-call, may indicate systemic issue
- **Sustained >2000 jobs**: Escalate to infrastructure team

**Post-Incident:**
- Audit feed source health and reliability
- Consider feed quality thresholds (auto-disable after N failures)
- Review worker pool auto-scaling policies

---

### WeatherCacheTooStale

**Alert Name:** `WeatherCacheTooStale`

**Symptoms:**
- Weather cache entries older than 90 minutes
- Weather widget displaying outdated forecasts
- Staleness gauge elevated on dashboard

**Impact:**
- Users see inaccurate weather data (low-severity UX issue)
- Increased API calls when cache misses occur
- Potential user confusion during rapidly changing conditions

**Diagnosis:**

1. **Check current staleness:**
   ```promql
   homepage_weather_cache_staleness_minutes
   ```

2. **Review weather job scheduler logs:**
   ```bash
   kubectl logs -l app=village-homepage | grep "WeatherRefreshScheduler" | tail -50
   ```

3. **Verify scheduled job execution:**
   ```bash
   kubectl logs -l app=village-homepage | grep "WeatherRefreshJobHandler" | tail -20
   ```

4. **Check for API connectivity issues:**
   ```bash
   # Test Open-Meteo
   curl -I https://api.open-meteo.com/v1/forecast

   # Test NWS (US only)
   curl -I https://api.weather.gov/gridpoints/MTR/85,105/forecast
   ```

5. **Query cache table directly:**
   ```sql
   SELECT
     location_key,
     provider,
     fetched_at,
     EXTRACT(EPOCH FROM (NOW() - fetched_at)) / 60 AS age_minutes
   FROM weather_cache
   ORDER BY fetched_at ASC
   LIMIT 10;
   ```

**Resolution:**

**If scheduler is not triggering:**

1. **Check Quartz scheduler status:**
   ```bash
   kubectl logs -l app=village-homepage | grep "Quartz Scheduler" | tail -20
   ```

2. **Verify cron expression in code:**
   - Scheduled hourly: `@Scheduled(cron = "0 0 * * * ?")`
   - Check `WeatherRefreshScheduler.java` for configuration

3. **Restart pods to reinitialize scheduler:**
   ```bash
   kubectl rollout restart deployment/village-homepage
   kubectl rollout status deployment/village-homepage
   ```

**If API calls are failing:**

1. **Check API credentials/keys:**
   ```bash
   # Verify environment variables are set
   kubectl get secret homepage-secrets -o yaml | grep WEATHER
   ```

2. **Test API endpoints manually:**
   ```bash
   # From inside pod
   kubectl exec -it deployment/village-homepage -- \
     curl "https://api.open-meteo.com/v1/forecast?latitude=37.77&longitude=-122.42&current_weather=true"
   ```

3. **Check rate limiting on provider side:**
   - Open-Meteo: No rate limits (free tier)
   - NWS: 5 requests/sec, should not be an issue

4. **Review circuit breaker status:**
   ```bash
   kubectl logs -l app=village-homepage | grep "CircuitBreaker" | grep weather
   ```

**If cache is being cleared incorrectly:**

1. **Check for cache eviction jobs:**
   ```bash
   kubectl logs -l app=village-homepage | grep "deleteExpired" | tail -20
   ```

2. **Verify cache TTL configuration:**
   - Default: 1 hour for forecasts, 15 min for alerts
   - Check `WeatherCache.java` expiration logic

**Quick Fix (if urgent):**

1. **Manually trigger weather refresh for all locations:**
   ```bash
   curl -X POST \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://homepage.villagecompute.com/admin/api/weather/refresh-all
   ```

**Escalation:**
- **90-120 min staleness**: Platform Squad handles, low priority
- **>120 min staleness**: Investigate during business hours
- **>6 hours staleness**: Indicates scheduler failure, page on-call

**Post-Incident:**
- Review cache TTL vs refresh frequency
- Consider adding synthetic monitoring for weather API health
- Document provider SLA and downtime patterns

---

### StockRateLimitExceeded

**Alert Name:** `StockRateLimitExceeded`

**Symptoms:**
- Stock market data fetches hitting Alpha Vantage rate limits
- Users seeing "Unable to load stock data" errors
- Rate limit counter elevated on dashboard

**Impact:**
- Stock widgets show stale or missing data
- No real-time price updates during market hours
- User experience degraded for stock tracking feature

**Diagnosis:**

1. **Check rate limit hit frequency:**
   ```promql
   sum(rate(stock_fetch_total{status="rate_limited"}[5m]))
   ```

2. **Review Alpha Vantage API tier:**
   - Free tier: 5 API calls/min, 100 calls/day
   - Premium tier: Higher limits (check subscription)

3. **Check number of tracked symbols:**
   ```sql
   SELECT COUNT(DISTINCT symbol)
   FROM user_stock_preferences;
   ```

4. **Review refresh job frequency:**
   ```bash
   kubectl logs -l app=village-homepage | grep "StockRefreshScheduler" | tail -50
   ```

5. **Identify most requested symbols:**
   ```promql
   topk(20, sum(rate(stock_fetch_total[1h])) by (symbol))
   ```

**Resolution:**

**If on free tier and hitting limits:**

1. **Increase cache TTL temporarily:**
   - Edit `StockService.java` cache expiration
   - Increase from 5min to 15min during market hours
   - Deploy updated configuration

2. **Implement request batching:**
   - Use Alpha Vantage BATCH_STOCK_QUOTES endpoint
   - Fetch multiple symbols in single API call
   - Requires code change + deployment

3. **Upgrade to Premium API tier:**
   - Contact Alpha Vantage for pricing
   - Update API key in secrets
   - Restart pods with new key

**If already on premium tier:**

1. **Check for request storms:**
   ```bash
   kubectl logs -l app=village-homepage --tail=500 | grep "Stock fetch" | wc -l
   ```

2. **Review job scheduler configuration:**
   - Default: 5 minutes during market hours (9:30 AM - 4:00 PM ET)
   - Consider dynamic scheduling based on user activity

3. **Implement circuit breaker:**
   - Already exists in `StockService.java`
   - Verify it's triggering correctly:
     ```bash
     kubectl logs -l app=village-homepage | grep "Stock circuit breaker"
     ```

**If rate limits are unexpected:**

1. **Check for duplicate requests:**
   ```sql
   SELECT symbol, COUNT(*) as request_count
   FROM delayed_jobs
   WHERE handler LIKE '%StockRefreshJobHandler%'
   GROUP BY symbol
   HAVING COUNT(*) > 1;
   ```

2. **Clear duplicate jobs:**
   ```sql
   DELETE FROM delayed_jobs
   WHERE id NOT IN (
     SELECT MIN(id)
     FROM delayed_jobs
     WHERE handler LIKE '%StockRefreshJobHandler%'
     GROUP BY handler
   );
   ```

**Quick Mitigation:**

1. **Disable stock widget feature flag:**
   ```bash
   curl -X PATCH \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://homepage.villagecompute.com/admin/api/feature-flags/stocks_widget \
     -d '{"enabled": false}'
   ```

2. **Notify users via banner:**
   - Add system announcement in admin UI
   - Message: "Stock data temporarily unavailable due to API limits"

**Escalation:**
- **Free tier exhaustion**: Platform Squad handles, consider upgrade
- **Premium tier exhaustion**: Escalate to product team for budget approval
- **Sustained issues**: May require architectural change (different provider)

**Post-Incident:**
- Audit stock refresh frequency vs user engagement
- Consider provider alternatives (IEX Cloud, Finnhub)
- Implement request deduplication at job enqueue time

---

### SocialRefreshFailures

**Alert Name:** `SocialRefreshFailures`

**Symptoms:**
- Instagram/Facebook feed refresh API calls failing
- Users seeing "Unable to connect social account" errors
- Social failure counter elevated on dashboard

**Impact:**
- Social widgets show stale content
- Users may need to re-authenticate
- Reduced engagement with social features

**Diagnosis:**

1. **Check failure rate by platform:**
   ```promql
   sum(rate(social_api_failures[5m])) by (platform)
   ```

2. **Review error types in logs:**
   ```bash
   kubectl logs -l app=village-homepage --tail=200 | grep "SocialIntegrationService" | grep ERROR
   ```

3. **Check Meta API status:**
   - Visit: https://developers.facebook.com/status/
   - Look for Graph API outages

4. **Verify OAuth token validity:**
   ```sql
   SELECT platform, COUNT(*) as expired_count
   FROM social_accounts
   WHERE token_expires_at < NOW()
   GROUP BY platform;
   ```

5. **Test API connectivity:**
   ```bash
   # From inside pod with valid access token
   kubectl exec -it deployment/village-homepage -- \
     curl "https://graph.facebook.com/v18.0/me?access_token=$TOKEN"
   ```

**Resolution:**

**If Meta API is down:**

1. **Verify outage on Meta status page**
2. **Enable staleness tolerance in UI:**
   - Show cached data with "Updated X hours ago" banner
   - Already implemented in `SocialFeedWidget.qute.html`

3. **Adjust retry backoff:**
   - Increase exponential backoff to reduce API hammering
   - Edit `SocialIntegrationService.java` retry configuration

**If OAuth tokens are expired:**

1. **Identify affected users:**
   ```sql
   SELECT user_id, platform, token_expires_at
   FROM social_accounts
   WHERE token_expires_at < NOW()
   ORDER BY token_expires_at DESC
   LIMIT 100;
   ```

2. **Send re-authentication emails:**
   ```bash
   curl -X POST \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://homepage.villagecompute.com/admin/api/social/send-reconnect-emails
   ```

3. **Consider automatic token refresh:**
   - Meta provides refresh tokens (60-day expiry)
   - Implement background refresh job if not already present

**If rate limiting by Meta:**

1. **Check rate limit headers in responses:**
   ```bash
   kubectl logs -l app=village-homepage | grep "X-App-Usage" | tail -20
   ```

2. **Reduce refresh frequency:**
   - Default: 30 minutes
   - Increase to 60 minutes during rate limit windows

3. **Implement usage-based throttling:**
   - Track Meta API usage percentage
   - Pause refreshes if approaching limit

**If permissions issues:**

1. **Verify required scopes are granted:**
   - Instagram: `instagram_basic`, `instagram_manage_insights`
   - Facebook: `pages_read_engagement`, `pages_show_list`

2. **Check for permission revocations:**
   ```sql
   SELECT user_id, platform, scopes, updated_at
   FROM social_accounts
   WHERE updated_at > NOW() - INTERVAL '24 hours';
   ```

**Quick Mitigation:**

1. **Disable social refresh temporarily:**
   ```bash
   curl -X PATCH \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://homepage.villagecompute.com/admin/api/feature-flags/social_integration \
     -d '{"rollout_percentage": 0}'
   ```

2. **Serve cached data only:**
   - Modify `SocialIntegrationService.java` to skip API calls
   - Return cached data regardless of staleness

**Escalation:**
- **Single platform failing**: Platform Squad handles, low urgency
- **Both platforms failing**: Investigate immediately, may be OAuth issue
- **Meta API outage**: Monitor status page, escalate if >1 hour

**Post-Incident:**
- Document Meta API reliability patterns
- Consider alternative social providers (Twitter API, LinkedIn)
- Implement proactive token refresh before expiration

---

### StorageGatewayErrors

**Alert Name:** `StorageGatewayErrors`

**Symptoms:**
- S3/R2 upload failures exceeding 5% error rate
- Screenshot captures failing to persist
- Marketplace listing images not uploading

**Impact:**
- Screenshots show broken image placeholders
- Marketplace listings missing photos
- User-uploaded content lost

**Diagnosis:**

1. **Check error rate by bucket:**
   ```promql
   sum(rate(storage_uploads_total{status="failure"}[5m])) by (bucket)
   ```

2. **Review error logs:**
   ```bash
   kubectl logs -l app=village-homepage --tail=200 | grep "StorageGateway" | grep ERROR
   ```

3. **Test S3/R2 connectivity:**
   ```bash
   kubectl exec -it deployment/village-homepage -- \
     aws s3 ls s3://village-homepage-screenshots/ --endpoint-url=$R2_ENDPOINT
   ```

4. **Check storage credentials:**
   ```bash
   kubectl get secret homepage-secrets -o yaml | grep STORAGE
   ```

5. **Verify bucket permissions:**
   ```bash
   # Test write permission
   kubectl exec -it deployment/village-homepage -- \
     aws s3 cp /tmp/test.txt s3://village-homepage-screenshots/test.txt --endpoint-url=$R2_ENDPOINT
   ```

**Resolution:**

**If credentials are invalid:**

1. **Rotate S3/R2 access keys:**
   - Generate new keys in Cloudflare R2 dashboard
   - Update Kubernetes secret:
     ```bash
     kubectl create secret generic homepage-secrets \
       --from-literal=R2_ACCESS_KEY_ID=new_key \
       --from-literal=R2_SECRET_ACCESS_KEY=new_secret \
       --dry-run=client -o yaml | kubectl apply -f -
     ```

2. **Restart pods to pick up new secrets:**
   ```bash
   kubectl rollout restart deployment/village-homepage
   ```

**If bucket permissions are misconfigured:**

1. **Review bucket policy (R2 dashboard)**
2. **Ensure policy allows PutObject, GetObject, DeleteObject**
3. **Check CORS configuration if browser uploads fail**

**If network connectivity issues:**

1. **Check egress firewall rules:**
   ```bash
   kubectl get networkpolicies
   ```

2. **Test DNS resolution:**
   ```bash
   kubectl exec -it deployment/village-homepage -- nslookup $R2_ENDPOINT
   ```

3. **Verify R2 endpoint is correct:**
   - Format: `https://<account-id>.r2.cloudflarestorage.com`
   - Check environment variable: `R2_ENDPOINT`

**If storage quota exceeded:**

1. **Check bucket size:**
   ```bash
   aws s3 ls s3://village-homepage-screenshots/ --recursive --summarize --endpoint-url=$R2_ENDPOINT
   ```

2. **Review Cloudflare R2 quota limits:**
   - Free tier: 10GB storage
   - Paid tier: Higher limits

3. **Implement cleanup job for old screenshots:**
   ```sql
   -- Delete screenshots older than 90 days
   DELETE FROM screenshots
   WHERE created_at < NOW() - INTERVAL '90 days';
   ```

**Quick Mitigation:**

1. **Disable screenshot capture temporarily:**
   ```bash
   curl -X PATCH \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://homepage.villagecompute.com/admin/api/feature-flags/screenshot_service \
     -d '{"enabled": false}'
   ```

2. **Queue uploads for retry:**
   - Failed uploads automatically retry (exponential backoff)
   - Monitor queue depth: `homepage_jobs_depth{queue="SCREENSHOT"}`

**Escalation:**
- **<10% error rate**: Platform Squad handles during business hours
- **>10% error rate**: Escalate to infrastructure team
- **Complete storage outage**: Page on-call immediately

**Post-Incident:**
- Review storage redundancy strategy
- Consider multi-region replication
- Document R2 SLA and incident patterns

---

## Metrics Reference

### RSS Feed Metrics

```promql
# Feed ingestion throughput (items/sec)
rate(rss_fetch_items_total{status="new"}[5m])

# Feed error rate
sum(rate(rss_fetch_errors_total[5m])) / sum(rate(rss_fetch_duration_count[5m]))

# Feed latency (p95)
histogram_quantile(0.95, rate(rss_fetch_duration_bucket[5m]))

# Top error types
topk(5, sum(rate(rss_fetch_errors_total[5m])) by (error_type))
```

### AI Tagging Metrics

```promql
# Current budget consumption
homepage_ai_budget_consumed_percent

# Tagging success rate
rate(ai_tagging_items_total{status="success"}[5m]) / rate(ai_tagging_items_total[5m])

# Budget throttles (last hour)
sum(increase(ai_tagging_budget_throttles[1h]))

# Items tagged per minute
sum(rate(ai_tagging_items_total{status="success"}[5m])) * 60
```

### Weather Metrics

```promql
# Cache staleness (minutes)
homepage_weather_cache_staleness_minutes

# Cache hit rate
rate(weather_cache_hits[5m]) / (rate(weather_cache_hits[5m]) + rate(weather_cache_misses[5m]))

# API call volume
rate(weather_cache_misses[5m])
```

### Stock Metrics

```promql
# Rate limit hits per second
sum(rate(stock_fetch_total{status="rate_limited"}[5m]))

# Success rate
rate(stock_fetch_total{status="success"}[5m]) / rate(stock_fetch_total[5m])

# API latency (p95)
histogram_quantile(0.95, rate(stock_api_duration_bucket[5m]))

# Top requested symbols
topk(10, sum(rate(stock_fetch_total[1h])) by (symbol))
```

### Social Metrics

```promql
# Failure rate by platform
sum(rate(social_api_failures[5m])) by (platform)

# Request status breakdown
sum(rate(social_feed_requests[5m])) by (status)

# API latency (p95)
histogram_quantile(0.95, rate(social_api_duration_bucket[5m]))
```

### Storage Metrics

```promql
# Upload error rate
sum(rate(storage_uploads_total{status="failure"}[5m])) / sum(rate(storage_uploads_total[5m]))

# Upload latency (p95)
histogram_quantile(0.95, rate(storage_upload_duration_bucket[5m]))

# Bytes uploaded (last hour)
sum(increase(storage_bytes_uploaded[1h])) by (bucket)

# Download bandwidth (last 5 min)
sum(rate(storage_bytes_downloaded[5m])) * 60
```

---

## Common Troubleshooting Scenarios

### Scenario 1: News Widget Shows No Content

**Symptoms:**
- User reports empty news widget
- Widget renders but no articles appear

**Diagnosis:**

1. Check if user has any feed sources enabled
2. Verify feed refresh jobs are running
3. Look for AI tagging backlog (items exist but no topics)
4. Check for JavaScript errors in browser console

**Resolution:**

1. Add default feed sources for user
2. Manually trigger feed refresh
3. Check AI tagging budget and enable if needed
4. Review widget query filters (topic mismatch)

### Scenario 2: Weather Widget Shows "Stale Data" Banner

**Symptoms:**
- Weather widget displays but shows staleness warning
- Data is >90 minutes old

**Diagnosis:**

1. Check weather cache staleness metric
2. Review weather job scheduler logs
3. Verify API connectivity

**Resolution:**

1. Manually trigger weather refresh
2. Check scheduler cron expression
3. Restart pods if scheduler hung

### Scenario 3: Stock Prices Not Updating During Market Hours

**Symptoms:**
- Stock widget shows last close price instead of intraday
- Prices static during trading hours (9:30 AM - 4:00 PM ET)

**Diagnosis:**

1. Check rate limit metrics
2. Verify market hours scheduler is active
3. Review Alpha Vantage API quota

**Resolution:**

1. Check API tier and upgrade if needed
2. Increase cache TTL temporarily
3. Clear rate limit backlog

### Scenario 4: Social Feed Shows "Disconnected" Message

**Symptoms:**
- User sees "Please reconnect your social account"
- Previously working social feed now unavailable

**Diagnosis:**

1. Check OAuth token expiration
2. Review permission revocations
3. Verify Meta API status

**Resolution:**

1. Prompt user to re-authenticate
2. Implement token refresh if available
3. Check for app-level permission issues

### Scenario 5: Screenshot Thumbnails Broken

**Symptoms:**
- Good Sites directory shows broken image icons
- Screenshot URLs return 404

**Diagnosis:**

1. Check storage gateway error metrics
2. Verify screenshot job completion
3. Test S3/R2 bucket access

**Resolution:**

1. Re-trigger screenshot capture job
2. Verify storage credentials
3. Check bucket CORS and permissions

---

## Escalation Paths

### Severity Levels

| Severity | Response Time | Escalation | Examples |
|----------|---------------|------------|----------|
| **Critical** | Immediate (page on-call) | Platform Squad → Infrastructure | AI budget ≥100%, storage gateway down, feed backlog >2000 |
| **Warning** | Business hours (Slack alert) | Platform Squad | AI budget >75%, feed backlog >500, weather cache >90min |
| **Info** | No alert (dashboard only) | Self-service | Cache hit rates, API latency trends |

### Escalation Chain

1. **First Response**: Platform Squad Slack (#homepage-alerts)
2. **Technical Lead**: Platform Squad Lead (business hours)
3. **On-Call Engineer**: PagerDuty rotation (critical only)
4. **Infrastructure Team**: For network, storage, or cluster issues
5. **Product Manager**: For budget increases or feature flag decisions

### External Dependencies

| Service | Status Page | Support Contact |
|---------|-------------|-----------------|
| **Alpha Vantage** | https://www.alphavantage.co/support/ | support@alphavantage.co |
| **Meta Graph API** | https://developers.facebook.com/status/ | Developer support portal |
| **Open-Meteo** | https://open-meteo.com/ | N/A (free tier, no SLA) |
| **Cloudflare R2** | https://www.cloudflarestatus.com/ | Enterprise support ticket |

---

## Maintenance Tasks

### Daily

- Review AI budget consumption trends
- Check feed error rates
- Monitor social token expiration queue

### Weekly

- Audit failed feed sources (consider disabling)
- Review stock API quota usage
- Clean up expired weather cache entries

### Monthly

- AI budget reset (automatic on 1st of month)
- Review storage gateway costs
- Update runbook with new incident patterns

---

## References

- **Observability Guide**: `docs/ops/observability.md`
- **Dashboard Definitions**: `docs/ops/dashboards/content-services.json`
- **Alert Rules**: `docs/ops/alerts/content-services.yaml`
- **Architecture**: `.codemachine/artifacts/architecture/04_Operational_Architecture.md` (Section 3.6)
- **Iteration Plan**: `.codemachine/artifacts/iterations/02_Iteration_I3.md`

---

## Changelog

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-09 | Platform Squad | Initial content services monitoring runbook (I3.T9) |
