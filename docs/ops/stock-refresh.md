# Stock Refresh Job

**Version:** 1.0
**Last Updated:** 2026-01-09
**Owner:** Platform Engineering
**Queue:** HIGH
**Job Type:** `STOCK_REFRESH`

This document provides operational guidance for the stock market refresh job, including market hours detection, rate limit handling, cache strategy, and troubleshooting procedures.

---

## Overview

The stock refresh job fetches real-time stock quotes from Alpha Vantage API and caches them for the stock market widget. It runs at different intervals based on US market hours to optimize API usage while providing fresh data.

**Key Components:**
- `StockRefreshJobHandler` - Async job handler
- `StockRefreshScheduler` - Market-aware scheduler
- `StockService` - Business logic with cache-first strategy
- `AlphaVantageClient` - HTTP client for Alpha Vantage API

---

## Market Hours Detection

**US Stock Market Hours:**
- **Open:** Monday-Friday, 9:30 AM - 4:00 PM ET
- **Closed:** Weekdays outside market hours
- **Weekend:** Saturday-Sunday (all day)

**Important Notes:**
- All times are in America/New_York timezone
- Federal holidays are NOT currently detected (simplified to weekends only)
- Pre-market (4:00-9:30 AM) and after-hours (4:00-8:00 PM) are treated as "closed"

**Market Status Values:**
- `OPEN` - Market is currently open (9:30 AM - 4:00 PM ET, Mon-Fri)
- `CLOSED` - Market is closed (weekdays outside market hours)
- `WEEKEND` - Weekend (Saturday or Sunday)

---

## Refresh Intervals

The scheduler dynamically adjusts refresh frequency based on market status:

| Market Status | Interval | Scheduler Method | Rationale |
|---------------|----------|------------------|-----------|
| **OPEN** | 5 minutes | `scheduleMarketHoursRefresh()` | Prices change frequently during market hours |
| **CLOSED** | 1 hour | `scheduleAfterHoursRefresh()` | Prices static, but keep cache warm |
| **WEEKEND** | 6 hours | `scheduleWeekendRefresh()` | Minimal changes, just maintain cache |

**Scheduler Details:**
```java
@Scheduled(every = "5m")
void scheduleMarketHoursRefresh() {
    if (stockService.isMarketOpen()) {
        jobService.enqueue(JobType.STOCK_REFRESH, Map.of());
    }
}

@Scheduled(cron = "0 0 * * * 1-5")  // Hourly on weekdays
void scheduleAfterHoursRefresh() {
    if (!stockService.isMarketOpen()) {
        jobService.enqueue(JobType.STOCK_REFRESH, Map.of());
    }
}

@Scheduled(cron = "0 0/6 * * * 0,6")  // Every 6 hours on weekends
void scheduleWeekendRefresh() {
    jobService.enqueue(JobType.STOCK_REFRESH, Map.of());
}
```

---

## Alpha Vantage API Integration

**API Endpoints:**
- `GLOBAL_QUOTE` - Current price, change, percent change
- `TIME_SERIES_DAILY` - Historical closing prices for sparkline

**Rate Limits (Free Tier):**
- 25 requests per day
- 5 requests per minute
- Exceeding limits returns HTTP 200 with JSON containing "Note" field

**Rate Limit Response Example:**
```json
{
  "Note": "Thank you for using Alpha Vantage! Our standard API call frequency is 5 calls per minute and 500 calls per day."
}
```

**Detection Logic:**
```java
if (root.has("Note")) {
    String message = root.get("Note").asText();
    throw new RateLimitException("Alpha Vantage rate limit exceeded: " + message);
}
```

---

## Cache Strategy

**Cache-First Approach:**
1. Check cache for valid (non-expired) quote
2. If cache hit, return cached data
3. If cache miss or expired, fetch from API
4. Update cache with new data and expiration

**Cache Expiration (Dynamic):**
- Market open: 5 minutes
- After hours: 1 hour
- Weekends: 6 hours

**Database Schema:**
```sql
CREATE TABLE stock_quotes (
    id UUID PRIMARY KEY,
    symbol TEXT NOT NULL UNIQUE,
    company_name TEXT NOT NULL,
    quote_data JSONB NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
```

**Cache Cleanup:**
- Expired quotes are deleted daily at 2 AM ET
- Executed by `StockRefreshScheduler.cleanupExpiredQuotes()`
- Prevents database bloat

---

## Rate Limit Fallback Strategy

When Alpha Vantage rate limit is exceeded:

1. **Serve Stale Cache:** Return cached data even if expired (up to 24 hours old)
2. **Set Flag:** Mark response with `rateLimited: true`
3. **Log Warning:** Alert operations team via logs
4. **Export Metric:** Increment `stock.rate_limit.exceeded` counter
5. **Continue Processing:** Don't abort batch - process remaining symbols

**Example Response with Rate Limit:**
```json
{
  "quotes": [
    {
      "symbol": "AAPL",
      "companyName": "Apple Inc.",
      "price": 150.50,
      "change": 1.25,
      "changePercent": 0.84,
      "sparkline": [149.00, 149.50, 150.00, 150.25, 150.50],
      "lastUpdated": "2026-01-09T10:00:00Z"
    }
  ],
  "marketStatus": "OPEN",
  "cachedAt": "2026-01-09T09:00:00Z",
  "stale": true,
  "rateLimited": true
}
```

**User Experience:**
- Widget displays cached data with warning indicator
- No functionality is lost - users still see prices
- Warning message: "Stock data may be delayed due to API limits"

---

## Watchlist Management

**User Limits:**
- Maximum 20 symbols per user
- Enforced at API layer (`PUT /api/widgets/stocks/watchlist`)

**Symbol Validation:**
- Must be uppercase (e.g., "AAPL", not "aapl")
- Alphanumeric characters plus caret (^) for indices
- Regex: `^[A-Z0-9^]+$`
- Examples: `AAPL`, `GOOGL`, `^GSPC` (S&P 500)

**Storage:**
- Stored in `users.preferences` JSONB column
- Field: `watchlist` (array of strings)
- Example: `["AAPL", "GOOGL", "^GSPC"]`

**Default Watchlist:**
- New users start with empty watchlist
- No default symbols (user must add manually)

---

## Sparkline Generation

**Definition:** Mini line chart showing last 5 trading days' closing prices

**Data Source:** Alpha Vantage `TIME_SERIES_DAILY` endpoint

**Processing Steps:**
1. Fetch daily time series (compact output)
2. Extract first 5 entries (most recent 5 days)
3. Parse closing price ("4. close" field)
4. Reverse array to get chronological order (oldest → newest)
5. Return as `List<Double>`

**Example:**
```java
// API returns dates in descending order
"Time Series (Daily)": {
  "2026-01-09": {"4. close": "151.50"},
  "2026-01-08": {"4. close": "150.50"},
  "2026-01-07": {"4. close": "150.00"},
  "2026-01-06": {"4. close": "149.50"},
  "2026-01-03": {"4. close": "149.00"}
}

// After processing: [149.00, 149.50, 150.00, 150.50, 151.50]
```

---

## Monitoring & Observability

**Metrics (Micrometer):**
- `stock.fetch.total{symbol, status}` - Total API fetches (tagged: success, error, rate_limited)
- `stock.cache.hits` - Cache hit counter
- `stock.cache.misses` - Cache miss counter
- `stock.rate_limit.exceeded` - Rate limit exceeded counter
- `stock.api.duration{endpoint}` - API latency histogram (tagged: global_quote, time_series_daily)
- `stock.market.status` - Gauge: 1 = open, 0 = closed
- `stock.refresh.jobs.completed` - Completed job counter
- `stock.refresh.jobs.failed` - Failed job counter
- `stock.refresh.failed{symbol}` - Per-symbol failure counter
- `stock.refresh.rate_limited{symbol}` - Per-symbol rate limit counter

**OpenTelemetry Spans:**
- `job.stock_refresh` - Top-level job span
  - Attributes: `job.id`, `market_status`, `symbols.count`, `symbols.success`, `symbols.failure`, `symbols.rate_limited`
- `stock.refresh_symbol` - Per-symbol span
  - Attributes: `job.id`, `symbol`, `status` (success/error/rate_limited)
- `stock.get_quote` - Widget fetch span
  - Attributes: `symbol`, `cache_hit`, `price`, `change_percent`
- `stock.refresh_cache` - Cache refresh span
  - Attributes: `symbol`, `market_status`, `price`, `change_percent`, `rate_limited`

**Logs:**
```
[INFO] Starting stock refresh job {job_id}
[DEBUG] Collected {count} unique symbols from user watchlists
[DEBUG] Successfully refreshed quote for {symbol}
[WARN] Rate limit exceeded while refreshing {symbol}: {message}
[ERROR] Failed to refresh quote for {symbol}
[INFO] Stock refresh job {job_id} completed: {success} success, {failed} failed, {rate_limited} rate limited
```

---

## Troubleshooting

### Rate Limit Exhaustion

**Symptoms:**
- `stock.rate_limit.exceeded` metric increasing
- Warning logs: "Alpha Vantage rate limit exceeded"
- Widget shows `rateLimited: true` flag
- Stale cache being served

**Diagnosis:**
```bash
# Check rate limit metrics
curl http://localhost:8080/q/metrics | grep stock.rate_limit.exceeded

# Check recent logs
kubectl logs -n homepage deployment/homepage-app --tail=100 | grep "rate limit"

# Check cache age
psql -h localhost -U village -d village_homepage -c \
  "SELECT symbol, fetched_at, expires_at, NOW() - fetched_at as age FROM stock_quotes ORDER BY fetched_at DESC LIMIT 10;"
```

**Resolution:**
1. **Upgrade API tier** - Premium tier provides 75+ requests/day
2. **Reduce refresh frequency** - Temporarily increase intervals in scheduler
3. **Serve stale cache longer** - Already implemented, no action needed
4. **Monitor symbol count** - Fewer symbols = fewer API calls

**Prevention:**
- Limit concurrent symbol fetches
- Use single API call for multiple symbols (if API supports)
- Cache aggressively during market hours

### Cache Not Updating

**Symptoms:**
- Old prices displayed in widget
- `fetched_at` timestamp not recent
- No recent job executions

**Diagnosis:**
```bash
# Check scheduler logs
kubectl logs -n homepage deployment/homepage-app --tail=50 | grep "stock refresh"

# Verify market hours detection
curl http://localhost:8080/q/metrics | grep stock.market.status

# Check last job execution
psql -h localhost -U village -d village_homepage -c \
  "SELECT * FROM delayed_jobs WHERE job_type = 'STOCK_REFRESH' ORDER BY created_at DESC LIMIT 5;"
```

**Resolution:**
1. Verify scheduler is running: `StockRefreshScheduler` bean should be loaded
2. Check market hours detection: `stockService.isMarketOpen()` accuracy
3. Manually enqueue job: `jobService.enqueue(JobType.STOCK_REFRESH, Map.of())`
4. Verify job handler is registered: `JobType.STOCK_REFRESH` in `JobType` enum

### API Errors

**Symptoms:**
- `stock.fetch.total{status=error}` metric increasing
- Error logs with API exceptions
- Empty widget or missing quotes

**Diagnosis:**
```bash
# Check API errors
kubectl logs -n homepage deployment/homepage-app --tail=100 | grep "Failed to fetch quote"

# Test API directly
curl "https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol=AAPL&apikey=demo"

# Check API key configuration
kubectl get secret homepage-secrets -n homepage -o jsonpath='{.data.ALPHAVANTAGE_API_KEY}' | base64 -d
```

**Common Errors:**
- **Invalid API key:** Verify `ALPHAVANTAGE_API_KEY` environment variable
- **Invalid symbol:** Check user watchlist contains valid ticker symbols
- **API timeout:** Increase `HTTP_TIMEOUT` in `AlphaVantageClient` (default 10s)
- **Network issues:** Verify outbound HTTPS connectivity to alphavantage.co

**Resolution:**
1. Validate API key is correct and active
2. Test API with curl/Postman
3. Check network connectivity
4. Review API status page: https://www.alphavantage.co/status

### High Latency

**Symptoms:**
- Widget loads slowly
- `stock.api.duration` metric showing high values
- User complaints about performance

**Diagnosis:**
```bash
# Check API latency
curl http://localhost:8080/q/metrics | grep stock.api.duration

# Check cache hit rate
curl http://localhost:8080/q/metrics | grep -E "stock.cache.(hits|misses)"

# Check database query performance
psql -h localhost -U village -d village_homepage -c \
  "EXPLAIN ANALYZE SELECT * FROM stock_quotes WHERE symbol = 'AAPL' AND expires_at > NOW();"
```

**Resolution:**
1. **Improve cache hit rate:** Increase expiration times during low-volatility periods
2. **Add database indexes:** Already have indexes on `symbol` and `expires_at`
3. **Reduce watchlist size:** Encourage users to limit symbols
4. **Parallelize API calls:** Fetch multiple symbols concurrently (within rate limits)

---

## Emergency Procedures

### Complete API Outage

**Scenario:** Alpha Vantage API is completely down or unreachable

**Actions:**
1. **Serve stale cache:** Already implemented - no action needed
2. **Extend cache TTL:** Temporarily increase expiration times
3. **Disable refresh scheduler:** Comment out `@Scheduled` annotations
4. **Notify users:** Display banner warning of delayed data
5. **Monitor API status:** Check Alpha Vantage status page for updates

**Recovery:**
1. Verify API is back online with test queries
2. Re-enable scheduler
3. Manually trigger refresh: `jobService.enqueue(JobType.STOCK_REFRESH, Map.of())`
4. Monitor metrics for normal operation
5. Remove user notification banner

### Database Corruption

**Scenario:** `stock_quotes` table corruption or data inconsistency

**Actions:**
1. **Check table integrity:**
   ```sql
   SELECT COUNT(*), MIN(fetched_at), MAX(fetched_at) FROM stock_quotes;
   SELECT symbol, COUNT(*) FROM stock_quotes GROUP BY symbol HAVING COUNT(*) > 1;
   ```

2. **Clear corrupted data:**
   ```sql
   DELETE FROM stock_quotes WHERE quote_data IS NULL OR fetched_at IS NULL;
   ```

3. **Rebuild cache:**
   ```sql
   TRUNCATE stock_quotes;
   ```
   Then manually trigger job: `jobService.enqueue(JobType.STOCK_REFRESH, Map.of())`

4. **Verify recovery:**
   ```sql
   SELECT symbol, fetched_at, expires_at FROM stock_quotes ORDER BY fetched_at DESC;
   ```

---

## Configuration

**Environment Variables:**
```properties
# Alpha Vantage API key (required)
ALPHAVANTAGE_API_KEY=your_api_key_here

# Job configuration (optional, defaults shown)
villagecompute.jobs.base-delay=30           # Base retry delay in seconds
villagecompute.jobs.max-attempts=5          # Max retry attempts
villagecompute.jobs.screenshot-concurrency=3 # Concurrent screenshot jobs (not relevant for stocks)
```

**Application Properties:**
```properties
# Database connection
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/village_homepage
quarkus.datasource.username=village
quarkus.datasource.password=${DB_PASSWORD}

# Hibernate
quarkus.hibernate-orm.database.generation=none
quarkus.hibernate-orm.log.sql=false

# HTTP client
quarkus.http.ssl.native=true
```

---

## Contact & Escalation

**Primary Contact:** Platform Engineering Team
**Slack Channel:** `#platform-services`
**On-Call:** PagerDuty rotation `vc-ops-high` (for HIGH queue alerts)

**Escalation Path:**
1. Check metrics and logs (see Troubleshooting section)
2. Review Alpha Vantage API status
3. Post in `#platform-services` Slack channel
4. If critical (market hours + no data), page on-call via PagerDuty

**SLA:**
- HIGH queue failures: 15-minute response SLA (on-call)
- Non-critical issues: 4-hour response SLA (business hours)
