# Social Integration Operations Guide

## Overview

The Village Homepage social integration provides Instagram and Facebook content display via Meta Graph API. This guide covers token management, refresh logic, staleness tiers, and troubleshooting for operations teams.

## Architecture

### Components

- **SocialToken** - OAuth token storage with encryption (Policy P5)
- **SocialPost** - Cached posts for offline display (90-day retention per Policy P13)
- **MetaGraphClient** - HTTP client for Meta Graph API v19.0
- **SocialIntegrationService** - Cache-first service with graceful degradation
- **SocialFeedRefreshJobHandler** - Background job for post refresh (30-min cadence)
- **SocialFeedRefreshScheduler** - Quartz scheduler for automated jobs
- **SocialWidgetResource** - REST endpoint `/api/widgets/social`

### Database Schema

```sql
-- Social tokens (encrypted at rest via database TDE)
CREATE TABLE social_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    platform VARCHAR(20) NOT NULL CHECK (platform IN ('instagram', 'facebook')),
    access_token VARCHAR(1000) NOT NULL,  -- Encrypted
    refresh_token VARCHAR(1000),          -- Encrypted
    expires_at TIMESTAMPTZ NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    scopes VARCHAR(500),
    last_refresh_attempt TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Cached social posts
CREATE TABLE social_posts (
    id BIGSERIAL PRIMARY KEY,
    social_token_id BIGINT NOT NULL REFERENCES social_tokens(id),
    platform VARCHAR(20) NOT NULL,
    platform_post_id VARCHAR(100) NOT NULL,
    post_type VARCHAR(50) NOT NULL,
    caption TEXT,
    media_urls JSONB NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    engagement_data JSONB,
    is_archived BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
```

## Token Management

### Token Lifecycle

1. **Initial Grant** - User authorizes via OAuth consent screen
2. **Active Period** - Token valid, posts refresh every 30 minutes
3. **Expiration Warning** - Token expires in 7 days, refresh attempt scheduled
4. **Stale Period** - Token expired < 7 days, cached posts served with banner
5. **Very Stale Period** - Token expired > 7 days, posts archived, reconnect required
6. **Revocation** - User disconnects integration, token soft-deleted

### Token Refresh Strategy

**Current Status:** Token refresh is **NOT YET IMPLEMENTED**. This requires Meta app credentials configuration.

**Planned Behavior:**
- Daily job checks for tokens expiring within 7 days
- Automatic refresh using Meta Graph API `/oauth/access_token` endpoint
- Email notification if refresh fails
- User prompted to reconnect if refresh fails 3 times

**TODO:** Configure Meta app credentials in production environment:
- `META_APP_ID` - Meta application ID
- `META_APP_SECRET` - Meta application secret
- `META_OAUTH_REDIRECT_URI` - OAuth callback URL

## Staleness Tiers (Policy P13)

### Tier Definitions

| Tier | Age | Banner Color | UI Behavior | Reconnect CTA |
|------|-----|--------------|-------------|---------------|
| **FRESH** | < 24 hours | Green (optional) | Normal display | No |
| **SLIGHTLY_STALE** | 1-3 days | Yellow | Minor warning | No |
| **STALE** | 4-7 days | Orange | Prominent warning | Yes |
| **VERY_STALE** | > 7 days | Red | Limited functionality | Yes (required) |

### Staleness Calculation

Staleness is calculated based on `last_refresh_attempt` timestamp:

```java
long daysSinceRefresh = ChronoUnit.DAYS.between(token.lastRefreshAttempt, Instant.now());

if (daysSinceRefresh < 1) return FRESH;
else if (daysSinceRefresh <= 3) return SLIGHTLY_STALE;
else if (daysSinceRefresh <= 7) return STALE;
else return VERY_STALE;
```

### User-Facing Messages

- **FRESH:** No message (posts are current)
- **SLIGHTLY_STALE:** "Showing posts from 2 days ago."
- **STALE:** "Showing posts from 5 days ago. Reconnect your Instagram to refresh."
- **VERY_STALE:** "Your Instagram connection expired 10+ days ago. Posts may be outdated. Reconnect to refresh."

## Background Jobs

### Feed Refresh Job

**Schedule:** Every 30 minutes (cron: `0 */30 * * * ?`)

**Behavior:**
- Fetches all active (non-revoked) social tokens
- Skips tokens with `last_refresh_attempt > 7 days` ago
- Calls Meta Graph API for each token independently
- Updates `social_posts` table with latest posts
- Continues on individual failures (does NOT abort batch)

**Metrics:**
- `social.refresh.total{platform,status}` - Counter
- `social.refresh.duration` - Timer
- `social.posts.archived{platform}` - Counter

### Archive Expired Job

**Schedule:** Daily at 3 AM UTC (cron: `0 0 3 * * ?`)

**Behavior:**
- Finds tokens with `last_refresh_attempt > 7 days` ago
- Archives all posts for those tokens (`is_archived = true`)
- Does NOT delete tokens (user can still reconnect)

## API Endpoint

### GET /api/widgets/social

**Query Parameters:**
- `user_id` (required) - User UUID
- `platform` (required) - "instagram" or "facebook"

**Response:**
```json
{
  "platform": "instagram",
  "posts": [
    {
      "platform_post_id": "123456",
      "post_type": "image",
      "caption": "Beautiful sunset!",
      "media_urls": [
        {"url": "https://...", "type": "image"}
      ],
      "posted_at": "2025-01-09T10:00:00Z",
      "engagement": {"likes": 42, "comments": 5},
      "permalink": "https://instagram.com/p/123456"
    }
  ],
  "connection_status": "stale",
  "staleness": "STALE",
  "staleness_days": 5,
  "reconnect_url": "/oauth/connect?platform=instagram",
  "cached_at": "2025-01-04T10:00:00Z",
  "message": "Showing posts from 5 days ago. Reconnect your Instagram to refresh."
}
```

**Connection States:**
- `connected` - Token valid, posts fresh (< 24 hours)
- `stale` - Token valid, posts outdated (1-7 days)
- `expired` - Token expired, cached posts only (> 7 days)
- `disconnected` - No token exists

## Monitoring

### Key Metrics

**Counters:**
- `social.feed.requests{platform,status}` - Feed requests by outcome
- `social.api.failures{platform,reason}` - API failures by type
- `social.refresh.total{platform,status}` - Background job results
- `social.posts.archived{platform}` - Posts archived per platform

**Timers:**
- `social.api.duration{platform,status}` - Meta API latency
- `social.refresh.duration` - Job execution time

**Gauges:**
- `social.tokens.active{platform}` - Active tokens by platform
- `social.posts.cached{platform}` - Cached posts by platform

### Alerts

**Critical:**
- API failure rate > 10% for 15 minutes
- Job execution time > 5 minutes
- Token expiration rate > 50 per day

**Warning:**
- Cache hit rate < 80% (indicates excessive API calls)
- Staleness tier VERY_STALE > 20% of active tokens
- Archive rate > 100 posts per day

## Troubleshooting

### Issue: Posts Not Refreshing

**Symptoms:** Users see stale posts (> 24 hours old)

**Diagnosis:**
```sql
-- Check recent job executions
SELECT * FROM delayed_jobs
WHERE job_type = 'SOCIAL_REFRESH'
ORDER BY created_at DESC LIMIT 10;

-- Check token refresh attempts
SELECT user_id, platform, last_refresh_attempt, expires_at
FROM social_tokens
WHERE revoked_at IS NULL
ORDER BY last_refresh_attempt DESC;
```

**Resolution:**
1. Check Quartz scheduler is running: `SELECT * FROM qrtz_triggers WHERE trigger_name LIKE '%Social%';`
2. Verify Meta API credentials are configured
3. Check application logs for API errors
4. Manually trigger job: `INSERT INTO delayed_jobs (...) VALUES (...);`

### Issue: Meta API Rate Limiting

**Symptoms:** API returns 429 status, posts serve from cache

**Diagnosis:**
```sql
-- Check API failure counts
SELECT platform, COUNT(*)
FROM social_tokens
WHERE last_refresh_attempt > NOW() - INTERVAL '1 hour'
GROUP BY platform;
```

**Resolution:**
1. Reduce refresh frequency from 30 min to 1 hour (edit scheduler cron)
2. Implement exponential backoff for failed tokens
3. Contact Meta support to increase rate limits
4. Serve cached posts gracefully (already implemented)

### Issue: Tokens Expiring Without Refresh

**Symptoms:** Many tokens in VERY_STALE state

**Diagnosis:**
```sql
-- Find expiring tokens
SELECT user_id, platform, expires_at,
       AGE(expires_at, NOW()) as time_until_expiry
FROM social_tokens
WHERE revoked_at IS NULL
  AND expires_at < NOW() + INTERVAL '7 days'
ORDER BY expires_at;
```

**Resolution:**
1. **TODO:** Implement token refresh logic (requires Meta credentials)
2. Email users with expiring tokens
3. Show in-app notification prompting reconnection
4. Archive posts after 7-day grace period

## Security

### Token Storage

**Current:** Tokens stored in plaintext (marked with TODO comments)

**TODO:** Implement encryption at rest using Quarkus Vault:
```java
// In SocialToken entity
@Column(name = "access_token", nullable = false, length = 1000)
@Encrypted(vault = "social-tokens")  // TODO: Add encryption
public String accessToken;
```

**Requirements:**
- Quarkus Vault extension configured
- Vault KMS key rotation policy
- Token decryption only in service layer
- NEVER log full tokens (only prefixes or user IDs)

### OAuth Security

- Tokens granted via Meta OAuth 2.0 consent screen
- Long-lived tokens (60 days) stored per user/platform
- Scopes: `instagram_basic`, `user_posts`, `user_media`
- Tokens revoked when user disconnects integration
- Cascade delete: token deletion archives all posts

## Data Retention (Policy P5/P13)

- **Social tokens:** Indefinite retention until user revocation
- **Social posts:** 90-day retention after `fetched_at` timestamp
- **Archived posts:** Retained until user reconnects or deletes integration
- **GDPR deletion:** Synchronous purge of tokens and posts on account deletion

## Performance

### Cache Strategy

1. **Check cache first** - Query `social_posts` for recent posts
2. **Serve cache on API failure** - Never block user on API errors
3. **Background refresh** - 30-min job updates cache proactively
4. **Staleness indicators** - UI shows age of cached content

### Database Indexes

```sql
-- Token lookups
CREATE UNIQUE INDEX social_tokens_user_platform_idx
    ON social_tokens(user_id, platform) WHERE revoked_at IS NULL;
CREATE INDEX social_tokens_expires_idx
    ON social_tokens(expires_at) WHERE revoked_at IS NULL;

-- Post queries
CREATE INDEX social_posts_token_posted_idx
    ON social_posts(social_token_id, posted_at DESC);
CREATE UNIQUE INDEX social_posts_platform_post_idx
    ON social_posts(platform, platform_post_id);
```

### Query Optimization

- Use `LIMIT 10` for recent posts to avoid full table scan
- Denormalize `platform` in `social_posts` for filtering
- Partition `social_posts` by `fetched_at` for 90-day cleanup
- Use JSONB indexes for engagement queries: `CREATE INDEX ON social_posts USING GIN (engagement_data);`

## Future Enhancements

1. **Token Refresh** - Implement automatic OAuth token refresh (requires Meta credentials)
2. **Webhook Support** - Real-time post updates via Meta webhooks
3. **Multi-Account** - Support multiple Instagram/Facebook accounts per user
4. **Story Support** - Fetch and display Instagram Stories (24-hour TTL)
5. **Direct Posting** - Allow users to create posts from Village Homepage
6. **Analytics** - Track engagement trends over time

## References

- [Meta Graph API Documentation](https://developers.facebook.com/docs/graph-api)
- [Instagram Basic Display API](https://developers.facebook.com/docs/instagram-basic-display-api)
- [Facebook Pages API](https://developers.facebook.com/docs/pages)
- [OAuth 2.0 Best Practices](https://tools.ietf.org/html/rfc6749)
