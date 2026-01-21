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

# Good Sites Analytics Documentation

This section describes analytics dashboards, KPIs, data collection, and monitoring for the Good Sites directory module (Task I5.T8).

## Key Performance Indicators (KPIs)

### Submission KPIs

| Metric | Target | Measurement | Source |
|--------|--------|-------------|--------|
| Submission Approval Rate | >80% | Approved / Total Submissions | `SELECT COUNT(*) FROM directory_sites WHERE status='approved' / COUNT(*)` |
| Pending Submissions | <50 | Count of status='pending' sites | `homepage_directory_pending_submissions` gauge |
| Avg Time to Approval | <24h | Time from submission to approval | Application logs |
| AI Categorization Accuracy | >85% | AI suggestions accepted / total | Bulk import job metrics |

### Engagement KPIs

| Metric | Target | Measurement | Source |
|--------|--------|-------------|--------|
| Vote Activity | >100/day | Total votes cast per day | `homepage_directory_votes_24h` gauge |
| Upvote Ratio | >70% | Upvotes / (Upvotes + Downvotes) | `SELECT COUNT(*) WHERE vote=1 / COUNT(*)` |
| Click-Through Rate | N/A | Clicks / Views per site | `click_stats_daily_items` table |
| Category Engagement | N/A | Clicks per category per day | `click_stats_daily` table |

### Quality KPIs

| Metric | Target | Measurement | Source |
|--------|--------|-------------|--------|
| Dead Link Rate | <5% | Dead sites / Total sites | `homepage_directory_dead_sites` / total sites |
| Bubbling Effectiveness | N/A | Clicks on bubbled sites / total bubbled | `click_stats_daily_items.bubbled_clicks` |
| Top Sites Contribution | N/A | Clicks from top 10% sites | `click_stats_daily_items` aggregation |

## Dashboards

### Good Sites Overview Dashboard

**File:** `docs/ops/dashboards/good-sites.json`

**Panels:**
- **Good Sites Clicks Over Time** (time series): Rate of directory click events by type
- **Directory Metrics Overview** (stat): Pending submissions, dead links, bubbled sites, votes (24h)
- **Top Sites by Clicks (30 days)** (table): Top 20 most-clicked sites with total clicks
- **Category Engagement Heatmap** (heatmap): Click activity by category over time
- **Bubbled Site Contribution** (bar chart): Top 10 source categories for bubbled clicks
- **Vote Activity Over Time** (time series): Upvote/downvote rates
- **Submission Queue Depth** (gauge): Pending submissions with threshold alerts
- **Dead Link Rate** (gauge): Percentage of dead sites with threshold alerts
- **Click Rollup Job Duration** (time series): p50/p99 rollup job latency

**Queries:**
```promql
# Good Sites clicks rate
rate(homepage_link_clicks_total{click_type=~"directory_.*"}[5m])

# Pending submissions count
homepage_directory_pending_submissions

# Dead link rate (%)
100 * homepage_directory_dead_sites / (count(homepage_directory_sites_total))

# Top sites by clicks (30d)
topk(20, sum by (target_id) (increase(homepage_link_clicks_total{click_type="directory_site_click"}[30d])))

# Bubbled site contribution
sum by (source_category) (increase(homepage_link_clicks_total{click_type="directory_bubbled_click"}[30d]))

# Vote activity rate
rate(homepage_directory_votes_total[5m])

# Click rollup job duration p99
histogram_quantile(0.99, rate(click_tracking_rollup_duration_bucket[5m]))
```

## Data Collection

### Click Tracking Implementation

Good Sites click tracking uses the unified `/track/click` endpoint with directory-specific metadata.

**Tracked Events:**
- `directory_site_click` - Click on site link from category page
- `directory_category_view` - Category page view
- `directory_site_view` - Site detail page view
- `directory_vote` - Vote cast on site
- `directory_search` - Search query
- `directory_bubbled_click` - Click on bubbled site from parent category
- `marketplace_listing` - Click on marketplace listing
- `marketplace_view` - View marketplace listing detail
- `profile_view` - View user profile (tracked since I6.T4)
- `profile_curated` - Click on curated article from profile page (tracked since I6.T4)

**Metadata Fields (JSONB):**
```json
{
  "category_id": "uuid",
  "category_slug": "string",
  "is_bubbled": "boolean",
  "source_category_id": "uuid",
  "rank_in_category": "integer",
  "score": "integer",
  "search_query": "string"
}
```

**Frontend Integration:**
Good Sites Qute templates include JavaScript to track clicks:

```javascript
// Track site click from category page
fetch('/track/click', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    clickType: 'directory_site_click',
    targetId: siteId,
    targetUrl: siteUrl,
    metadata: {
      category_id: categoryId,
      is_bubbled: isBubbled,
      source_category_id: sourceCategoryId,
      rank_in_category: rank,
      score: score
    }
  }),
  keepalive: true
});
```

### Daily Rollup Aggregation

Raw click events are aggregated hourly into rollup tables for efficient dashboard queries.

**Aggregation Query (Category Stats):**
```sql
INSERT INTO click_stats_daily (stat_date, click_type, category_id, category_name, total_clicks, unique_users, unique_sessions)
SELECT
  DATE(click_timestamp) AS stat_date,
  click_type,
  category_id,
  (SELECT name FROM directory_categories WHERE id = category_id),
  COUNT(*) AS total_clicks,
  COUNT(DISTINCT COALESCE(user_id, session_id)) AS unique_users,
  COUNT(DISTINCT session_id) AS unique_sessions
FROM link_clicks
WHERE click_date = :targetDate
  AND click_type LIKE 'directory_%'
GROUP BY DATE(click_timestamp), click_type, category_id
ON CONFLICT (stat_date, click_type, category_id)
DO UPDATE SET
  total_clicks = EXCLUDED.total_clicks,
  unique_users = EXCLUDED.unique_users,
  unique_sessions = EXCLUDED.unique_sessions;
```

**Aggregation Query (Item Stats with Bubbling):**
```sql
INSERT INTO click_stats_daily_items (stat_date, click_type, target_id, total_clicks, unique_users, avg_rank, avg_score, bubbled_clicks)
SELECT
  DATE(click_timestamp) AS stat_date,
  click_type,
  target_id,
  COUNT(*) AS total_clicks,
  COUNT(DISTINCT COALESCE(user_id, session_id)) AS unique_users,
  AVG((metadata->>'rank_in_category')::NUMERIC) AS avg_rank,
  AVG((metadata->>'score')::NUMERIC) AS avg_score,
  COUNT(*) FILTER (WHERE (metadata->>'is_bubbled')::BOOLEAN = true) AS bubbled_clicks
FROM link_clicks
WHERE click_date = :targetDate
  AND click_type = 'directory_site_click'
  AND target_id IS NOT NULL
GROUP BY DATE(click_timestamp), click_type, target_id
ON CONFLICT (stat_date, click_type, target_id)
DO UPDATE SET
  total_clicks = EXCLUDED.total_clicks,
  unique_users = EXCLUDED.unique_users,
  avg_rank = EXCLUDED.avg_rank,
  avg_score = EXCLUDED.avg_score,
  bubbled_clicks = EXCLUDED.bubbled_clicks;
```

### Profile Analytics (Policy F14.9)

Profile click tracking captures user engagement with public profiles and curated article selections.

**Tracked Events:**
- `profile_view` - User views another user's profile
- `profile_curated` - User clicks on curated article from profile page

**Profile Metadata Fields (JSONB):**
```json
{
  "profile_id": "uuid",
  "profile_username": "string",
  "article_id": "uuid",
  "article_slot": "string",
  "article_url": "string",
  "template": "string"
}
```

**Frontend Integration:**
Profile pages include JavaScript to track curated article clicks:

```javascript
// Track curated article click from profile page
fetch('/track/click', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    clickType: 'profile_curated',
    targetId: articleId,
    targetUrl: articleUrl,
    metadata: {
      profile_id: profileId,
      profile_username: username,
      article_id: articleId,
      article_slot: slotName,
      template: templateType
    }
  }),
  keepalive: true
});
```

**Analytics Endpoints:**

The AnalyticsResource provides admin endpoints for profile metrics:

```bash
# Top viewed profiles (last 30 days)
GET /admin/api/analytics/profiles/top-viewed?start_date=2026-01-01&end_date=2026-01-31&limit=20

# Curated article clicks for specific profile
GET /admin/api/analytics/profiles/{profile_id}/curated-clicks?start_date=2026-01-01&end_date=2026-01-31

# Overall profile engagement metrics
GET /admin/api/analytics/profiles/engagement?start_date=2026-01-01&end_date=2026-01-31
```

**Dashboard Queries:**

Top viewed profiles (last 30 days):
```sql
SELECT
  target_id AS profile_id,
  SUM(total_clicks) AS total_views,
  SUM(unique_users) AS unique_users,
  SUM(unique_sessions) AS unique_sessions
FROM click_stats_daily_items
WHERE click_type = 'profile_view'
  AND stat_date >= CURRENT_DATE - INTERVAL '30 days'
GROUP BY target_id
ORDER BY total_views DESC
LIMIT 20;
```

Curated article click-through rate by profile:
```sql
SELECT
  (metadata->>'profile_id')::UUID AS profile_id,
  target_id AS article_id,
  target_url AS article_url,
  metadata->>'article_slot' AS slot,
  COUNT(*) AS total_clicks,
  COUNT(DISTINCT COALESCE(user_id, session_id)) AS unique_users
FROM link_clicks
WHERE click_type = 'profile_curated'
  AND click_date >= CURRENT_DATE - INTERVAL '30 days'
GROUP BY (metadata->>'profile_id')::UUID, target_id, target_url, metadata->>'article_slot'
ORDER BY total_clicks DESC;
```

Profile engagement rate (curated clicks / profile views):
```sql
SELECT
  pv.total_views,
  pc.total_curated_clicks,
  ROUND(100.0 * pc.total_curated_clicks / NULLIF(pv.total_views, 0), 2) AS engagement_rate
FROM
  (SELECT SUM(total_clicks) AS total_views
   FROM click_stats_daily_items
   WHERE click_type = 'profile_view'
     AND stat_date >= CURRENT_DATE - INTERVAL '30 days') pv,
  (SELECT SUM(total_clicks) AS total_curated_clicks
   FROM click_stats_daily_items
   WHERE click_type = 'profile_curated'
     AND stat_date >= CURRENT_DATE - INTERVAL '30 days') pc;
```

**Privacy Considerations:**
- All profile analytics respect Policy P14 (consent-gated tracking)
- IP addresses sanitized (last octet zeroed) before storage
- User agents truncated to 512 characters
- Aggregated rollup tables contain no PII

### Data Retention

- **Raw Click Logs:** 90-day retention via partitioned `link_clicks` table (Policy F14.9)
- **Rollup Tables:** Indefinite retention for historical analytics
- **Partition Cleanup:** Daily job drops expired partitions at 4am UTC

## Dashboard Queries

### Top Sites by Clicks (Last 30 Days)

```sql
SELECT
  ds.id,
  ds.title,
  ds.url,
  ds.domain,
  SUM(csd.total_clicks) AS total_clicks,
  SUM(csd.unique_users) AS unique_visitors,
  AVG(csd.avg_score) AS avg_score
FROM click_stats_daily_items csd
JOIN directory_sites ds ON csd.target_id = ds.id
WHERE csd.click_type = 'directory_site_click'
  AND csd.stat_date >= CURRENT_DATE - INTERVAL '30 days'
GROUP BY ds.id, ds.title, ds.url, ds.domain
ORDER BY total_clicks DESC
LIMIT 20;
```

### Bubbled Site Effectiveness (Source Category Contribution)

```sql
SELECT
  dc.name AS source_category,
  dc.slug,
  COUNT(DISTINCT csd.target_id) AS unique_sites_clicked,
  SUM(csd.bubbled_clicks) AS total_bubbled_clicks,
  SUM(csd.total_clicks) AS total_clicks,
  ROUND(100.0 * SUM(csd.bubbled_clicks) / NULLIF(SUM(csd.total_clicks), 0), 2) AS bubbled_click_ratio
FROM click_stats_daily_items csd
JOIN directory_sites ds ON csd.target_id = ds.id
JOIN directory_site_categories dsc ON dsc.site_id = ds.id
JOIN directory_categories dc ON dsc.category_id = dc.id
WHERE csd.click_type = 'directory_site_click'
  AND csd.stat_date >= CURRENT_DATE - INTERVAL '30 days'
  AND csd.bubbled_clicks > 0
GROUP BY dc.name, dc.slug
ORDER BY total_bubbled_clicks DESC
LIMIT 10;
```

### Category Engagement Heatmap

```sql
SELECT
  dc.name AS category,
  dc.slug,
  DATE(csd.stat_date) AS day,
  SUM(csd.total_clicks) AS clicks,
  SUM(csd.unique_users) AS unique_visitors
FROM click_stats_daily csd
JOIN directory_categories dc ON csd.category_id = dc.id
WHERE csd.click_type IN ('directory_category_view', 'directory_site_click')
  AND csd.stat_date >= CURRENT_DATE - INTERVAL '30 days'
GROUP BY dc.name, dc.slug, DATE(csd.stat_date)
ORDER BY day DESC, clicks DESC;
```

### Submission Funnel (Last 30 Days)

```sql
SELECT
  status,
  COUNT(*) AS count,
  ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2) AS percentage
FROM directory_sites
WHERE created_at >= CURRENT_DATE - INTERVAL '30 days'
GROUP BY status
ORDER BY
  CASE status
    WHEN 'pending' THEN 1
    WHEN 'approved' THEN 2
    WHEN 'rejected' THEN 3
    ELSE 4
  END;
```

### Vote Activity Analysis

```sql
SELECT
  DATE_TRUNC('hour', created_at) AS hour,
  CASE WHEN vote = 1 THEN 'upvote' ELSE 'downvote' END AS vote_type,
  COUNT(*) AS votes
FROM directory_votes
WHERE created_at >= NOW() - INTERVAL '7 days'
GROUP BY DATE_TRUNC('hour', created_at), vote_type
ORDER BY hour DESC;
```

## Monitoring & Alerting

### Prometheus Alert Rules

**File:** `docs/ops/alerts/good-sites.yaml`

```yaml
groups:
  - name: good-sites
    interval: 30s
    rules:
      - alert: GoodSitesSubmissionBacklog
        expr: homepage_directory_pending_submissions > 50
        for: 1h
        labels:
          severity: warning
          component: good-sites
        annotations:
          summary: "Good Sites submission queue backlog >50"
          description: "Pending submissions: {{ $value }}. Review moderation queue."

      - alert: GoodSitesDeadLinkRateHigh
        expr: 100 * homepage_directory_dead_sites / (homepage_directory_dead_sites + on() (count(homepage_directory_sites_total) - homepage_directory_dead_sites)) > 10
        for: 6h
        labels:
          severity: warning
          component: good-sites
        annotations:
          summary: "Good Sites dead link rate >10%"
          description: "Dead link rate: {{ $value | humanizePercentage }}. Run link health check job."

      - alert: GoodSitesVoteActivityLow
        expr: rate(homepage_directory_votes_24h[1h]) < (10 / 3600)
        for: 4h
        labels:
          severity: info
          component: good-sites
        annotations:
          summary: "Good Sites vote activity <10 votes/hour"
          description: "Recent vote rate: {{ $value }} votes/sec. Check user engagement."

      - alert: ClickRollupJobSlow
        expr: histogram_quantile(0.99, rate(click_tracking_rollup_duration_bucket[5m])) > 60
        for: 15m
        labels:
          severity: warning
          component: analytics
        annotations:
          summary: "Click rollup job p99 latency >60s"
          description: "Rollup job duration p99: {{ $value }}s. Check database performance."
```

### Runbook: High Submission Backlog

**Symptom:** `homepage_directory_pending_submissions` gauge >50 for 1+ hour

**Diagnosis:**
1. Check moderation queue: `SELECT COUNT(*) FROM directory_sites WHERE status='pending'`
2. Review recent submissions: `SELECT * FROM directory_sites WHERE status='pending' ORDER BY created_at DESC LIMIT 10`
3. Check karma audit for trusted user promotion: `SELECT * FROM karma_audit ORDER BY created_at DESC LIMIT 20`

**Remediation:**
- Assign moderators to review pending submissions
- Promote high-karma users to trusted status (auto-approve submissions)
- Run bulk import with AI categorization for quick review

### Runbook: High Dead Link Rate

**Symptom:** Dead link rate >10% for 6+ hours

**Diagnosis:**
1. Count dead sites: `SELECT COUNT(*) FROM directory_sites WHERE isDead=true`
2. Review recent link health check results: Check LinkHealthCheckJobHandler logs
3. Identify failing domains: `SELECT domain, COUNT(*) FROM directory_sites WHERE isDead=true GROUP BY domain ORDER BY COUNT(*) DESC`

**Remediation:**
- Run manual link health check: Trigger LinkHealthCheckJobHandler
- Contact site maintainers for affected sites
- Consider removing consistently dead sites after review

## Implementation References

- **Click Tracking Endpoint:** `src/main/java/villagecompute/homepage/api/rest/ClickTrackingResource.java`
- **Rollup Job Handler:** `src/main/java/villagecompute/homepage/jobs/ClickRollupJobHandler.java`
- **Metrics Registration:** `src/main/java/villagecompute/homepage/observability/ObservabilityMetrics.java`
- **Database Schema:** `migrations/scripts/20250111000700_create_link_clicks.sql`, `migrations/scripts/20250111000800_create_click_stats_rollup.sql`
- **Grafana Dashboard:** `docs/ops/dashboards/good-sites.json`

---

**Document Version:** 1.1
**Last Updated:** 2026-01-11
**Tasks:** I4.T9 (Marketplace), I5.T8 (Good Sites)
