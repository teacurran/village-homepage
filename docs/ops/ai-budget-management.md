# AI Budget Management Runbook

**Policy References:** P2/P10 (AI Budget Control)
**Component:** AI Tagging Pipeline
**Owner:** Operations Team
**Last Updated:** 2025-01-09

---

## Overview

The AI tagging pipeline uses Anthropic Claude Sonnet 4 via LangChain4j to extract topics, sentiment, and categories from RSS feed items. This runbook describes budget enforcement mechanisms, monitoring procedures, and operational interventions per P2/P10 policy requirements.

**Monthly Budget:** $500 (50,000 cents)

**Pricing (Claude Sonnet 4):**
- Input tokens: $3 per 1M tokens ($0.000003/token)
- Output tokens: $15 per 1M tokens ($0.000015/token)
- Estimated cost per article: ~$0.006 (1000 input + 200 output tokens)

**Capacity:** ~83,000 articles/month at budget ceiling

---

## Budget Enforcement States

The system automatically throttles AI operations based on budget consumption percentage:

| State | Threshold | Batch Size | Behavior | Alert Level |
|-------|-----------|------------|----------|-------------|
| **NORMAL** | < 75% | 20 items | Full-speed processing | None |
| **REDUCE** | 75-90% | 10 items | Reduced batch sizes to conserve budget | INFO |
| **QUEUE** | 90-100% | 0 items | Defer jobs to next monthly cycle | WARNING |
| **HARD_STOP** | ≥ 100% | 0 items | Stop all AI operations immediately | CRITICAL |

Budget state is recalculated before each job execution and between batches.

---

## Monitoring

### Admin Dashboard

Access current and historical AI usage via admin API:

```bash
# Current month usage
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://homepage.villagecompute.com/admin/api/ai-usage

# Historical usage (last 12 months)
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://homepage.villagecompute.com/admin/api/ai-usage/history?months=12
```

**Response Example:**

```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "month": "2025-01-01",
  "provider": "anthropic",
  "totalRequests": 1250,
  "totalTokensInput": 1562500,
  "totalTokensOutput": 250000,
  "estimatedCostCents": 8437,
  "budgetLimitCents": 50000,
  "budgetAction": "NORMAL",
  "percentUsed": 16.87,
  "remainingCents": 41563,
  "updatedAt": "2025-01-09T15:30:00Z",
  "estimatedCostFormatted": "$84.37",
  "budgetLimitFormatted": "$500.00",
  "remainingFormatted": "$415.63",
  "percentUsedFormatted": "16.87%"
}
```

### Prometheus Metrics

AI tagging exports metrics to Prometheus for dashboards and alerting:

```promql
# Budget usage percentage
ai_budget_percent_used

# Items tagged (success/failure)
ai_tagging_items_total{status="success"}
ai_tagging_items_total{status="failure"}

# Budget throttle events
ai_tagging_budget_throttles
```

**Recommended Alerts:**

```yaml
# Alert at 75% budget usage (REDUCE threshold)
- alert: AIBudgetReductionMode
  expr: ai_budget_percent_used > 75
  for: 5m
  labels:
    severity: info
  annotations:
    summary: "AI budget at {{ $value }}% - entering REDUCE mode"

# Alert at 90% budget usage (QUEUE threshold)
- alert: AIBudgetQueueMode
  expr: ai_budget_percent_used > 90
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "AI budget at {{ $value }}% - entering QUEUE mode"

# Alert at 100% budget usage (HARD_STOP threshold)
- alert: AIBudgetExhausted
  expr: ai_budget_percent_used >= 100
  for: 1m
  labels:
    severity: critical
  annotations:
    summary: "AI budget exhausted - all operations stopped"
```

### Email Alerts

Automatic email alerts sent to `ops@villagecompute.com` when crossing thresholds:

- **75%:** Budget entering REDUCE mode (smaller batches)
- **90%:** Budget entering QUEUE mode (defer to next cycle)
- **100%:** Budget exhausted (HARD_STOP)

Alert emails include:
- Current spend and remaining budget
- Token usage statistics
- Current budget action state
- Link to admin dashboard

---

## Operational Procedures

### Scenario 1: Budget Exhausted Before Month End

**Symptoms:**
- Alert: "AI budget exhausted - all operations stopped"
- Feed items show `ai_tagged = false` in dashboard
- Metrics show `ai_budget_percent_used >= 100`

**Options:**

1. **Increase Budget Limit (Recommended for emergency)**

   ```bash
   curl -X PATCH \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"budgetLimitCents": 75000}' \
     https://homepage.villagecompute.com/admin/api/ai-usage/2025-01/budget
   ```

   This increases January 2025 budget from $500 to $750.

2. **Wait for Next Monthly Cycle**

   Budget resets automatically on the 1st of each month. Untagged items accumulate and process when budget renews.

3. **Manual Tagging Review**

   Review untagged items and decide if manual categorization is needed:

   ```sql
   SELECT COUNT(*) FROM feed_items WHERE ai_tagged = false;
   ```

### Scenario 2: Unusually High Spend Rate

**Symptoms:**
- Budget reaching 75% threshold earlier than expected
- Higher than normal `ai_tagging_items_total` rate

**Investigation Steps:**

1. Check for feed ingestion anomalies:

   ```sql
   -- Check recent feed item counts
   SELECT DATE(published_at), COUNT(*)
   FROM feed_items
   WHERE published_at > NOW() - INTERVAL '7 days'
   GROUP BY DATE(published_at)
   ORDER BY 1 DESC;
   ```

2. Review token usage patterns:

   ```bash
   curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://homepage.villagecompute.com/admin/api/ai-usage/history?months=3
   ```

3. Check for long article content causing high token counts:

   ```sql
   -- Find articles with unusually long content
   SELECT id, title, LENGTH(content) as content_length
   FROM feed_items
   WHERE ai_tagged = true
   ORDER BY LENGTH(content) DESC
   LIMIT 20;
   ```

**Remediation:**

- Adjust `MAX_CONTENT_LENGTH` in `AiTaggingService.java` if articles too long
- Review RSS feed sources for spam or content aggregation feeds
- Consider reducing tagging frequency (modify scheduler cron)

### Scenario 3: Budget Increase Request

**Business Need:** Higher traffic month, new feeds added, increased tagging priority

**Procedure:**

1. **Update Current Month Budget:**

   ```bash
   curl -X PATCH \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"budgetLimitCents": 100000}' \
     https://homepage.villagecompute.com/admin/api/ai-usage/$(date +%Y-%m)/budget
   ```

2. **Update Default Budget for Future Months:**

   Edit `AiUsageTracking.java`:

   ```java
   public static final int DEFAULT_BUDGET_LIMIT_CENTS = 100000; // $1000
   ```

   Rebuild and deploy:

   ```bash
   ./mvnw package -Dquarkus.container-image.build=true
   kubectl rollout restart deployment/village-homepage -n production
   ```

3. **Document Decision:**

   Update this runbook with new budget limit and business justification.

### Scenario 4: Budget Decrease Request

**Business Need:** Cost reduction, lower traffic period, temporary freeze

**Procedure:**

1. **Update Budget Limit:**

   ```bash
   curl -X PATCH \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"budgetLimitCents": 25000}' \
     https://homepage.villagecompute.com/admin/api/ai-usage/$(date +%Y-%m)/budget
   ```

2. **Verify Impact:**

   - Current spend: If already above new limit, immediate HARD_STOP
   - If approaching new limit, REDUCE or QUEUE state activated
   - Untagged items will accumulate until next cycle or budget increase

3. **Communicate Impact:**

   Notify stakeholders that new feed items may show as "Uncategorized" until budget renews.

### Scenario 5: Manual Job Trigger

**Use Case:** Backfill after budget reset, test tagging pipeline, recover from error

**Procedure:**

1. **Check for Untagged Items:**

   ```sql
   SELECT COUNT(*) FROM feed_items WHERE ai_tagged = false;
   ```

2. **Trigger Scheduler Manually:**

   Via admin endpoint (requires implementation):

   ```bash
   curl -X POST \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://homepage.villagecompute.com/admin/api/jobs/ai-tagging/trigger
   ```

   Or via SQL (bypass scheduler):

   ```sql
   INSERT INTO delayed_jobs (job_type, payload, queue, scheduled_at, created_at)
   VALUES ('AI_TAGGING', '{"trigger": "manual"}', 'BULK', NOW(), NOW());
   ```

3. **Monitor Execution:**

   ```bash
   # Watch logs
   kubectl logs -f deployment/village-homepage -n production | grep "AI tagging"

   # Check metrics
   curl http://localhost:8080/q/metrics | grep ai_tagging
   ```

---

## Database Queries

### Current Month Usage Summary

```sql
SELECT
  month,
  provider,
  total_requests,
  total_tokens_input,
  total_tokens_output,
  estimated_cost_cents / 100.0 AS cost_dollars,
  budget_limit_cents / 100.0 AS budget_dollars,
  ROUND(100.0 * estimated_cost_cents / budget_limit_cents, 2) AS percent_used,
  (budget_limit_cents - estimated_cost_cents) / 100.0 AS remaining_dollars
FROM ai_usage_tracking
WHERE month = DATE_TRUNC('month', CURRENT_DATE)
ORDER BY provider;
```

### Historical Spend by Month

```sql
SELECT
  TO_CHAR(month, 'YYYY-MM') AS month,
  estimated_cost_cents / 100.0 AS cost_dollars,
  total_requests,
  total_tokens_input + total_tokens_output AS total_tokens,
  ROUND(estimated_cost_cents::numeric / NULLIF(total_requests, 0), 2) AS cost_per_request_cents
FROM ai_usage_tracking
WHERE provider = 'anthropic'
ORDER BY month DESC
LIMIT 12;
```

### Untagged Items by Feed

```sql
SELECT
  rf.title AS feed_title,
  COUNT(*) AS untagged_count,
  MIN(fi.published_at) AS oldest_untagged,
  MAX(fi.published_at) AS newest_untagged
FROM feed_items fi
JOIN rss_feeds rf ON fi.feed_id = rf.id
WHERE fi.ai_tagged = false
GROUP BY rf.id, rf.title
ORDER BY untagged_count DESC;
```

### Tag Distribution Analysis

```sql
SELECT
  jsonb_array_elements_text(ai_tags->'categories') AS category,
  COUNT(*) AS count
FROM feed_items
WHERE ai_tagged = true
  AND ai_tags IS NOT NULL
GROUP BY category
ORDER BY count DESC;
```

---

## Budget Reset Process

Budget counters reset automatically on the 1st of each month via new tracking record creation in `AiUsageTracking.findOrCreateCurrentMonth()`.

**No manual intervention required.**

**Verification:**

On the 1st of each month, verify budget reset:

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://homepage.villagecompute.com/admin/api/ai-usage
```

Expected: `estimatedCostCents: 0`, `budgetAction: NORMAL`

---

## Troubleshooting

### Issue: Tags Not Being Generated

**Symptoms:**
- Items marked `ai_tagged = false` despite jobs running
- No errors in logs

**Diagnosis:**

1. Check budget state:

   ```bash
   curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://homepage.villagecompute.com/admin/api/ai-usage
   ```

   If `budgetAction = QUEUE` or `HARD_STOP`, processing is paused.

2. Check LangChain4j configuration:

   ```bash
   kubectl get secret homepage-anthropic-key -n production -o jsonpath='{.data.ANTHROPIC_API_KEY}' | base64 -d
   ```

   Verify API key is set and valid.

3. Check job execution logs:

   ```bash
   kubectl logs -f deployment/village-homepage -n production | grep "AI tagging job"
   ```

   Look for errors like "Invalid API key" or "Budget exhausted".

### Issue: High Failure Rate

**Symptoms:**
- Metric `ai_tagging_items_total{status="failure"}` increasing
- Logs show "Failed to tag item" errors

**Common Causes:**

1. **Invalid JSON Response from Claude:**

   LangChain4j may return non-JSON or malformed response. Check logs for `Failed to parse Claude response`.

   **Fix:** Review prompt in `AiTaggingService.buildPrompt()` for clarity.

2. **API Rate Limiting:**

   Anthropic enforces rate limits on API keys. Check for HTTP 429 errors.

   **Fix:** Reduce batch size or increase delay between requests.

3. **Timeout Errors:**

   Long articles may exceed 30-second timeout.

   **Fix:** Increase timeout in `application.yaml`:

   ```yaml
   quarkus:
     langchain4j:
       anthropic:
         chat-model:
           timeout: 60s
   ```

### Issue: Budget Percentage Not Updating

**Symptoms:**
- Prometheus metric `ai_budget_percent_used` stale
- Dashboard shows outdated percentage

**Diagnosis:**

1. Verify `ai_usage_tracking` table updated:

   ```sql
   SELECT updated_at FROM ai_usage_tracking
   WHERE month = DATE_TRUNC('month', CURRENT_DATE);
   ```

2. Check for transaction failures in logs:

   ```bash
   kubectl logs deployment/village-homepage -n production | grep "Failed to record AI usage"
   ```

**Fix:**

If tracking record corrupted, manually update:

```sql
UPDATE ai_usage_tracking
SET updated_at = NOW()
WHERE month = DATE_TRUNC('month', CURRENT_DATE);
```

---

## Cost Optimization Strategies

### 1. Content Truncation

Current implementation truncates content to 3000 characters. Adjust in `AiTaggingService.java`:

```java
private static final int MAX_CONTENT_LENGTH = 2000; // Reduce from 3000
```

**Impact:** ~20% token reduction per article, proportional cost savings.

### 2. Reduce Tagging Frequency

Modify scheduler in `AiTaggingScheduler.java`:

```java
@Scheduled(cron = "0 */2 * * *") // Every 2 hours instead of hourly
```

**Impact:** Slower tagging, reduced real-time categorization.

### 3. Selective Feed Tagging

Tag only high-priority feeds (e.g., major news sources):

```java
// In AiTaggingJobHandler, filter by feed priority
List<FeedItem> untaggedItems = FeedItem.find(
  "ai_tagged = false AND feed.priority >= ?1", FeedPriority.HIGH
).list();
```

**Impact:** Lower coverage, focused budget on important content.

### 4. Use Cheaper Model

Switch to Claude Haiku (faster, cheaper, lower quality):

```yaml
quarkus:
  langchain4j:
    anthropic:
      chat-model:
        model-name: claude-haiku-4-20250110
```

**Impact:** ~5x cost reduction, potentially lower tagging accuracy.

---

## Contact & Escalation

**Primary Contact:** Operations Team (`ops@villagecompute.com`)
**Secondary Contact:** Engineering Team (`dev@villagecompute.com`)
**Escalation:** CTO for budget increases over $1000/month

**Runbook Version:** 1.0
**Last Reviewed:** 2025-01-09
**Next Review:** 2025-04-01
