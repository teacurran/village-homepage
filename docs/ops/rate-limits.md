# Rate Limiting Operations Guide

This document provides operational guidance for managing the Village Homepage rate limiting system.

## Overview

The rate limiting system protects the platform from abuse by enforcing request limits per action type and user tier. It uses a sliding-window algorithm with Caffeine-backed caching and database persistence.

**Policy Reference:** P14/F14.2 (Rate Limiting & Abuse Prevention)

## User Tiers

The system supports three user tiers with progressively relaxed limits:

| Tier | Criteria | Use Case |
|------|----------|----------|
| **anonymous** | No authentication | Strict limits to prevent abuse from unauthenticated traffic |
| **logged_in** | Authenticated user | Moderate limits for normal user activity |
| **trusted** | Karma score ≥ 10 | Generous limits for established community members |

Tier assignment is automatic based on authentication state and user karma score.

## Default Rate Limits

### Bootstrap & Authentication

| Action | Tier | Limit | Window |
|--------|------|-------|--------|
| `bootstrap` | anonymous | 5 requests | 1 hour |
| `login` | anonymous | 10 requests | 15 minutes |
| `login` | logged_in | 20 requests | 15 minutes |

### Search & Discovery

| Action | Tier | Limit | Window |
|--------|------|-------|--------|
| `search` | anonymous | 20 requests | 1 minute |
| `search` | logged_in | 50 requests | 1 minute |
| `search` | trusted | 100 requests | 1 minute |

### User-Generated Content

| Action | Tier | Limit | Window |
|--------|------|-------|--------|
| `vote` | logged_in | 100 requests | 1 hour |
| `vote` | trusted | 200 requests | 1 hour |
| `submission` | logged_in | 10 requests | 1 hour |
| `submission` | trusted | 20 requests | 1 hour |
| `contact` | logged_in | 20 requests | 1 hour |
| `contact` | trusted | 50 requests | 1 hour |

## Admin Operations

### Viewing Configurations

List all rate limit configurations:

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://homepage.villagecompute.com/admin/api/rate-limits
```

Get a specific configuration:

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://homepage.villagecompute.com/admin/api/rate-limits/search/anonymous
```

### Updating Limits

Update a rate limit configuration:

```bash
curl -X PATCH \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"limit_count": 30, "window_seconds": 60}' \
  https://homepage.villagecompute.com/admin/api/rate-limits/search/anonymous
```

**Important:** Configuration changes take effect within 10 minutes (cache TTL). For immediate effect, restart the application or wait for cache expiration.

### Querying Violations

View recent violations (all users):

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "https://homepage.villagecompute.com/admin/api/rate-limits/violations?limit=100"
```

Filter by user ID:

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "https://homepage.villagecompute.com/admin/api/rate-limits/violations?user_id=12345"
```

Filter by IP address:

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "https://homepage.villagecompute.com/admin/api/rate-limits/violations?ip_address=203.0.113.42"
```

## HTTP Headers

All rate-limited endpoints return these headers:

- **X-RateLimit-Limit:** Maximum requests allowed in the window
- **X-RateLimit-Remaining:** Remaining requests in current window
- **Retry-After:** (429 responses only) Seconds until window resets

**Example successful response:**

```
HTTP/1.1 200 OK
X-RateLimit-Limit: 50
X-RateLimit-Remaining: 47
```

**Example rate-limited response:**

```
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 50
X-RateLimit-Remaining: 0
Retry-After: 45
```

## Metrics & Monitoring

### Prometheus Metrics

The rate limiting system exports these metrics at `/q/metrics`:

**homepage_rate_limit_checks_total{action, tier, result}**
- Counter tracking all rate limit check outcomes
- `result` values: `allowed`, `denied`
- Use for monitoring throughput and denial rates

**homepage_rate_limit_violations_total{action, tier}**
- Counter tracking violation events
- Use for abuse detection and capacity planning

### Example Queries

Check denial rate for search (anonymous tier):

```promql
rate(homepage_rate_limit_checks_total{action="search",tier="anonymous",result="denied"}[5m])
/
rate(homepage_rate_limit_checks_total{action="search",tier="anonymous"}[5m])
```

Alert on high violation rate:

```promql
rate(homepage_rate_limit_violations_total[5m]) > 10
```

## Troubleshooting

### Users Report Being Rate Limited

1. **Check violation logs:**
   - Query violations by user ID or IP address
   - Look for patterns (repeated action, specific time window)

2. **Review current limits:**
   - Compare user's request rate with configured limits
   - Check if user tier is correct (karma score, authentication)

3. **Temporary relief:**
   - Increase limits for the affected action/tier
   - Add user to whitelist (future feature)

### False Positives (Legitimate Traffic Blocked)

**Causes:**
- Shared IP addresses (NAT, corporate proxy)
- Automated tools (RSS readers, monitoring)
- UI bugs causing request loops

**Solutions:**
- Increase limits for affected action/tier
- Implement IP exemption list (future feature)
- Fix frontend to reduce unnecessary requests

### Performance Issues

**Symptoms:**
- Slow responses on rate-limited endpoints
- High memory usage
- Cache eviction warnings

**Diagnostics:**
- Check Caffeine cache hit rates in JVM metrics
- Review bucket cache size (`maximumSize=100,000`)
- Monitor database query latency for config loads

**Mitigations:**
- Increase cache TTL if config changes are rare
- Tune cache sizes based on traffic patterns
- Add database indexes on violation queries

## Database Schema

### rate_limit_config

Stores rate limit rules per action/tier:

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| action_type | TEXT | Action identifier |
| tier | TEXT | User tier |
| limit_count | INT | Max requests |
| window_seconds | INT | Time window |
| updated_by_user_id | BIGINT | Admin who updated |
| updated_at | TIMESTAMPTZ | Last update |

### rate_limit_violations

Audit log for violations:

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| user_id | BIGINT | User ID (nullable) |
| ip_address | INET | Source IP |
| action_type | TEXT | Violated action |
| endpoint | TEXT | HTTP path |
| violation_count | INT | Count since first |
| first_violation_at | TIMESTAMPTZ | First violation |
| last_violation_at | TIMESTAMPTZ | Latest violation |

## Data Retention

**Violation logs:** No automatic cleanup currently. Consider implementing a retention policy (e.g., 90 days) via cron job:

```sql
DELETE FROM rate_limit_violations
WHERE last_violation_at < NOW() - INTERVAL '90 days';
```

**Configuration history:** Not tracked. Consider implementing audit logging for config changes.

## Security Considerations

1. **Admin endpoints require super_admin role**
   - All `/admin/api/rate-limits` endpoints are protected
   - Audit all configuration changes

2. **Violation persistence is asynchronous**
   - Violations are logged after the response is sent
   - Database failures don't block user requests (fail-open)

3. **IP address extraction**
   - Filter trusts `X-Forwarded-For` header
   - Ensure reverse proxy strips untrusted headers

4. **Bypass for critical paths**
   - Health checks and monitoring endpoints should NOT be rate limited
   - Admin endpoints use separate buckets

## Future Enhancements

- **IP blocklists:** Temporary blocks for abusive IPs
- **User whitelisting:** Exempt specific users from limits
- **Dynamic tier adjustment:** Auto-promote users based on behavior
- **Real-time alerts:** Slack/PagerDuty notifications for attack patterns
- **Rate limit dashboard:** Grafana panels for ops visibility

## Support Contacts

- **Engineering:** @backend-team
- **Ops Runbook:** See `docs/ops/runbooks/rate-limit-incident.md` (TBD)
- **Policy Owner:** @security-team

---

**Last Updated:** 2025-01-09
**Maintainer:** Village Homepage Backend Team
