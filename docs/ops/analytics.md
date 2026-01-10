<!-- TASK: I4.T9 -->
# Marketplace Analytics Documentation

This document describes analytics dashboards, KPIs, data collection, and monitoring for the Village Homepage marketplace module.

## Key Performance Indicators (KPIs)

### Listing KPIs

| Metric | Target | Measurement | Source |
|--------|--------|-------------|--------|
| Listing Publish Time | <5s p95 | Time from form submit to database persist | Application logs |
| Active Listings | N/A | Count of status='active' listings | `SELECT COUNT(*) FROM marketplace_listings WHERE status='active'` |
| Expired Listings/Day | <10 | Listings hitting 30-day expiration | Daily job metrics |
| Flagged Listings | <5% total | Percentage with flag_count >= 3 | `SELECT COUNT(*) WHERE flag_count >= 3` |

### Payment KPIs

| Metric | Target | Measurement | Source |
|--------|--------|-------------|--------|
| Payment Success Rate | >99% | Successful Stripe charges / total attempts | `marketplace.payments.success` / (`marketplace.payments.success` + `marketplace.payments.failed`) |
| Posting Fee Revenue | N/A | Sum of posting_fee from successful payments | Stripe dashboard + `SUM(posting_fee)` |
| Promotion Revenue | N/A | Sum of promotion charges (featured, bump) | Stripe dashboard |
| Refund Rate | <2% | Refunds issued / total payments | `marketplace.refunds.issued` / total payments |

### Search KPIs

| Metric | Target | Measurement | Source |
|--------|--------|-------------|--------|
| Search Latency p95 | <200ms | 95th percentile search query response time | `marketplace.search.latency` histogram |
| Search Latency p99 | <500ms | 99th percentile search query response time | `marketplace.search.latency` histogram |
| Search Error Rate | <0.5% | Failed searches / total searches | `http_requests_total{uri="/api/marketplace/search",status=~"5.."}` |
| Radius Filter Usage | N/A | Percentage of searches with location param | Click tracking metadata |

### Messaging KPIs

| Metric | Target | Measurement | Source |
|--------|--------|-------------|--------|
| Message Relay Success | >98% | Delivered messages / total sent | `marketplace.messages.relayed` / `marketplace.messages.sent` |
| Spam Messages Blocked | N/A | Count of messages flagged as spam | `marketplace.messages.spam_blocked` |
| Reply Latency | <60s p95 | Time from inbound email to database persist | IMAP job metrics |

## Dashboards

### Marketplace Overview Dashboard

**File:** `docs/ops/dashboards/marketplace.json`

**Panels:**
- **Active Listings Over Time** (line chart): Tracks growth of active listings by day
- **Listing Status Breakdown** (pie chart): Distribution of pending_payment / active / expired / flagged
- **Payment Success Rate** (gauge): Current 7-day rolling average payment success rate
- **Revenue by Type** (stacked bar): Posting fees vs. promotions (featured, bump) by week
- **Search Latency Histogram** (heatmap): p50/p95/p99 search latencies over time
- **Top Search Keywords** (table): Most popular search terms with result counts
- **Moderation Queue Depth** (line chart): Number of pending flags over time
- **Message Relay Success Rate** (gauge): Current 24h relay success percentage

**Queries:**
```promql
# Active listings count
count(marketplace_listings{status="active"})

# Payment success rate (7d rolling avg)
rate(marketplace_payments_total{status="success"}[7d]) /
rate(marketplace_payments_total[7d])

# Search latency p95
histogram_quantile(0.95,
  rate(marketplace_search_latency_bucket[5m]))

# Moderation queue depth
count(listing_flags{status="pending"})
```

### Search Performance Dashboard

**File:** `docs/ops/dashboards/marketplace-search.json`

**Panels:**
- **Search Latency by Query Type** (line chart): basic vs. radius vs. complex query latencies
- **Elasticsearch vs PostGIS Query Split** (pie chart): Percentage using each backend
- **Top Slow Queries** (table): Slowest queries with params + duration + timestamp
- **Cache Hit Rate** (gauge): Hibernate query cache effectiveness
- **Error Rate by Endpoint** (bar chart): 4xx/5xx errors per search endpoint

**Use Cases:**
- Identify performance bottlenecks in search queries
- Monitor PostGIS radius query performance
- Track Elasticsearch health and response times
- Optimize cache configuration based on hit rates

## Data Collection

### Metrics Instrumentation

Marketplace metrics are collected via Quarkus Micrometer and exported to Prometheus. See `ObservabilityMetrics.java` for implementation.

**Counter Metrics:**
```java
@Inject MeterRegistry registry;

// Listings created
registry.counter("marketplace.listings.created",
    "category", categoryName,
    "paid", String.valueOf(hasFee)).increment();

// Payment outcomes
registry.counter("marketplace.payments.total",
    "status", "success",
    "type", "posting_fee").increment();

registry.counter("marketplace.payments.total",
    "status", "failed",
    "reason", failureReason).increment();

// Refunds
registry.counter("marketplace.refunds.issued",
    "reason", refundReason).increment();

// Message relay
registry.counter("marketplace.messages.sent",
    "direction", "buyer_to_seller").increment();

registry.counter("marketplace.messages.relayed",
    "success", String.valueOf(relaySuccess)).increment();
```

**Histogram Metrics:**
```java
// Search latency
Timer.Sample sample = Timer.start(registry);
// ... perform search ...
sample.stop(registry.timer("marketplace.search.latency",
    "type", queryType,
    "has_radius", String.valueOf(hasRadius),
    "has_filters", String.valueOf(hasFilters)));
```

**Gauge Metrics:**
```java
// Active listing count (sampled periodically)
registry.gauge("marketplace.listings.active",
    () -> MarketplaceListing.count("status = 'active'"));

// Moderation queue depth
registry.gauge("marketplace.moderation.queue_depth",
    () => ListingFlag.count("status = 'pending'"));
```

### Click Tracking

Click events are logged to `link_clicks` table and aggregated daily to `click_stats_daily`.

**Tracking Implementation:**
```javascript
// Frontend click tracking (listing detail pages)
fetch('/track/click', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    url: window.location.href,
    context: 'marketplace_listing',
    metadata: {
      listing_id: listingId,
      category: listingCategory,
      source: 'search_results', // or 'direct', 'homepage_widget'
      search_query: searchQuery,
      position: resultPosition  // Position in search results
    }
  })
});
```

**Daily Rollup Query:**
```sql
-- Aggregates raw clicks into daily stats (run via scheduled job)
INSERT INTO click_stats_daily (stat_date, context, item_id, click_count, unique_users)
SELECT
  DATE(created_at) AS stat_date,
  context,
  (metadata->>'listing_id')::UUID AS item_id,
  COUNT(*) AS click_count,
  COUNT(DISTINCT COALESCE(user_id, session_hash)) AS unique_users
FROM link_clicks
WHERE context = 'marketplace_listing'
  AND created_at >= CURRENT_DATE - INTERVAL '1 day'
  AND created_at < CURRENT_DATE
GROUP BY DATE(created_at), context, (metadata->>'listing_id')::UUID
ON CONFLICT (stat_date, context, item_id)
DO UPDATE SET
  click_count = EXCLUDED.click_count,
  unique_users = EXCLUDED.unique_users;
```

**Dashboard Queries:**
```sql
-- Top listings by clicks (last 30 days)
SELECT
  ml.id,
  ml.title,
  ml.category,
  SUM(csd.click_count) AS total_clicks,
  SUM(csd.unique_users) AS unique_visitors
FROM click_stats_daily csd
JOIN marketplace_listings ml ON csd.item_id = ml.id
WHERE csd.context = 'marketplace_listing'
  AND csd.stat_date >= CURRENT_DATE - INTERVAL '30 days'
GROUP BY ml.id, ml.title, ml.category
ORDER BY total_clicks DESC
LIMIT 20;

-- Click-through rate from search results
SELECT
  metadata->>'search_query' AS query,
  COUNT(*) AS impressions,
  COUNT(DISTINCT (metadata->>'listing_id')) AS unique_listings_clicked,
  COUNT(*) FILTER (WHERE context = 'marketplace_listing') AS clicks,
  (COUNT(*) FILTER (WHERE context = 'marketplace_listing')::FLOAT / COUNT(*)) AS ctr
FROM link_clicks
WHERE context IN ('marketplace_search', 'marketplace_listing')
  AND created_at >= CURRENT_DATE - INTERVAL '7 days'
GROUP BY metadata->>'search_query'
ORDER BY impressions DESC
LIMIT 50;
```

## Monitoring & Alerting

### Critical Alerts

| Alert | Threshold | Trigger | Action |
|-------|-----------|---------|--------|
| Search Latency High | p95 >200ms for 5min | Prometheus AlertManager | Check Elasticsearch cluster health, review slow query log, verify PostGIS index usage |
| Payment Failure Spike | >5% failures in 15min | Prometheus AlertManager | Verify Stripe API status, check webhook logs, review payment intent errors |
| Refund Rate High | >10% in 1 hour | Prometheus AlertManager | Investigate fraud pattern, review moderation queue, check for policy violations |
| Message Relay Down | >10 failed relays in 5min | Prometheus AlertManager | Check IMAP connection status, verify email service availability, review job handler logs |

**Alert Configuration (Prometheus):**
```yaml
# prometheus-alerts.yml
groups:
  - name: marketplace
    interval: 30s
    rules:
      - alert: MarketplaceSearchLatencyHigh
        expr: histogram_quantile(0.95, rate(marketplace_search_latency_bucket[5m])) > 0.2
        for: 5m
        labels:
          severity: warning
          component: marketplace
        annotations:
          summary: "Marketplace search latency p95 >200ms"
          description: "Search queries are slow ({{ $value }}s). Check Elasticsearch and PostGIS performance."

      - alert: MarketplacePaymentFailureSpike
        expr: |
          (
            rate(marketplace_payments_total{status="failed"}[15m]) /
            rate(marketplace_payments_total[15m])
          ) > 0.05
        for: 5m
        labels:
          severity: critical
          component: marketplace
        annotations:
          summary: "Marketplace payment failure rate >5%"
          description: "High payment failure rate ({{ $value | humanizePercentage }}). Verify Stripe integration."

      - alert: MarketplaceRefundRateHigh
        expr: |
          (
            rate(marketplace_refunds_issued[1h]) /
            rate(marketplace_payments_total{status="success"}[1h])
          ) > 0.10
        for: 10m
        labels:
          severity: warning
          component: marketplace
        annotations:
          summary: "Marketplace refund rate >10%"
          description: "Unusually high refund rate. Investigate for fraud or policy issues."
```

### Warning Alerts

| Alert | Threshold | Action |
|-------|-----------|--------|
| Listing Expiration Backlog | >50 expired not processed | Check job queue health, review scheduler logs, verify database connection pool |
| Moderation Queue Depth | >100 pending flags | Notify admin team, consider auto-reject for spam patterns, increase moderator capacity |
| Image Processing Lag | >20 pending images | Check StorageGateway connectivity, verify job handler is running, review S3/R2 upload errors |

## Reporting

### Weekly Reports

**Schedule:** Every Monday at 9am UTC

**Distribution:** Emailed to stakeholders (product, ops, finance)

**Metrics Included:**
- Total listings created (week-over-week %)
- Revenue breakdown (posting fees, promotions)
- Top categories by listing count
- Search performance trends (avg latency, error rate)
- Moderation summary (flags approved/dismissed, bans issued)
- User engagement (unique searchers, click-through rates)

**Report Query:**
```sql
-- Weekly summary (last 7 days vs. prior 7 days)
WITH current_week AS (
  SELECT
    COUNT(*) AS listings_created,
    SUM(posting_fee) AS posting_revenue,
    SUM(CASE WHEN is_featured THEN featured_fee ELSE 0 END) AS promo_revenue
  FROM marketplace_listings
  WHERE created_at >= CURRENT_DATE - INTERVAL '7 days'
),
prior_week AS (
  SELECT
    COUNT(*) AS listings_created,
    SUM(posting_fee) AS posting_revenue,
    SUM(CASE WHEN is_featured THEN featured_fee ELSE 0 END) AS promo_revenue
  FROM marketplace_listings
  WHERE created_at >= CURRENT_DATE - INTERVAL '14 days'
    AND created_at < CURRENT_DATE - INTERVAL '7 days'
)
SELECT
  cw.listings_created AS current_listings,
  pw.listings_created AS prior_listings,
  ((cw.listings_created - pw.listings_created)::FLOAT / pw.listings_created * 100) AS pct_change,
  cw.posting_revenue + cw.promo_revenue AS current_revenue,
  pw.posting_revenue + pw.promo_revenue AS prior_revenue
FROM current_week cw, prior_week pw;
```

### Monthly Reports

**Schedule:** First day of month at 9am UTC

**Metrics Included:**
- Cumulative revenue (month-over-month growth)
- User acquisition funnel (signups → first listing → paid listing)
- Churn analysis (expired listings not renewed)
- Performance against KPI targets (table with RED/YELLOW/GREEN status)
- Top feature requests from support tickets
- Geographic distribution of listings (heat map)

**Revenue Trend Chart:**
```sql
-- Monthly revenue for last 12 months
SELECT
  DATE_TRUNC('month', created_at) AS month,
  COUNT(*) AS listings_count,
  SUM(posting_fee) AS posting_revenue,
  SUM(CASE WHEN is_featured THEN featured_fee ELSE 0 END) AS featured_revenue,
  SUM(CASE WHEN bump_count > 0 THEN bump_count * 2.00 ELSE 0 END) AS bump_revenue
FROM marketplace_listings
WHERE created_at >= CURRENT_DATE - INTERVAL '12 months'
  AND status != 'pending_payment'  -- Only count completed transactions
GROUP BY DATE_TRUNC('month', created_at)
ORDER BY month DESC;
```

## Operational Runbooks

### Runbook: Investigating High Search Latency

**Symptom:** `MarketplaceSearchLatencyHigh` alert triggered

**Steps:**
1. Check Elasticsearch cluster health:
   ```bash
   curl -X GET "http://elasticsearch:9200/_cluster/health?pretty"
   ```
   - Status should be `green`. If `yellow` or `red`, investigate node failures.

2. Review slow query log (Elasticsearch):
   ```bash
   curl -X GET "http://elasticsearch:9200/_nodes/stats/indices/search?pretty" | jq '.nodes[] | .indices.search'
   ```
   - Look for `query_total` and `query_time_in_millis`. High query times indicate indexing issues.

3. Check PostGIS query performance (for radius searches):
   ```sql
   EXPLAIN ANALYZE
   SELECT * FROM marketplace_listings
   WHERE ST_DWithin(
     geography(ST_MakePoint(longitude, latitude)),
     geography(ST_MakePoint(-122.4194, 37.7749)),
     40000  -- 25 miles in meters
   )
   LIMIT 25;
   ```
   - Verify `Index Scan using idx_listings_geography_point` is used (not Seq Scan).

4. Check Hibernate query cache hit rate:
   ```
   View Grafana dashboard: Marketplace Search Performance → Cache Hit Rate panel
   ```
   - If <70%, consider increasing cache size or TTL.

5. Review application logs for slow queries:
   ```bash
   kubectl logs -l app=village-homepage --tail=100 | grep "marketplace_search_latency" | sort -k5 -rn | head -20
   ```

**Remediation:**
- If Elasticsearch unhealthy: Scale cluster, reindex marketplace listings
- If PostGIS slow: Rebuild geography index, vacuum analyze table
- If cache miss rate high: Adjust `quarkus.hibernate-orm.cache.expiration.max-idle` config

### Runbook: Payment Failure Spike Investigation

**Symptom:** `MarketplacePaymentFailureSpike` alert triggered

**Steps:**
1. Check Stripe API status:
   ```bash
   curl https://status.stripe.com/api/v2/status.json
   ```
   - If Stripe is degraded, failures are expected. Monitor and wait for resolution.

2. Review recent payment intent errors:
   ```sql
   SELECT
     stripe_payment_intent_id,
     stripe_error_code,
     stripe_error_message,
     COUNT(*)
   FROM marketplace_listings
   WHERE status = 'payment_failed'
     AND updated_at >= NOW() - INTERVAL '1 hour'
   GROUP BY stripe_payment_intent_id, stripe_error_code, stripe_error_message
   ORDER BY COUNT(*) DESC;
   ```

3. Check webhook delivery status (Stripe Dashboard):
   - Navigate to Developers → Webhooks → Recent Events
   - Look for `payment_intent.payment_failed` events with 5xx responses

4. Verify webhook signature validation:
   ```bash
   kubectl logs -l app=village-homepage --tail=50 | grep "Stripe webhook signature"
   ```
   - Ensure signatures are valid. Invalid signatures indicate configuration mismatch.

**Remediation:**
- If Stripe API keys expired: Rotate keys in Kubernetes secrets + restart pods
- If webhook endpoint down: Check ingress/load balancer, verify `/api/webhooks/stripe` is reachable
- If systematic card declines: Review listing pricing, check for fraud patterns

---

## Additional Resources

- **Grafana Dashboards:** Import JSON files from `docs/ops/dashboards/`
- **Prometheus Queries:** See `docs/ops/prometheus-queries.md` for reusable PromQL examples
- **Click Tracking Schema:** See migration `migrations/scripts/20241215000000_click_tracking.sql`
- **Load Testing Guide:** See `tests/load/README.md` (TODO: create)

---

**Document Version:** 1.0
**Last Updated:** 2026-01-10
**Task:** I4.T9
