# Feed Aggregation Governance

**Status:** Baseline Established (I3.T1)
**Owner:** Backend Team
**Related Policies:** P1 (GDPR/CCPA), P2/P10 (AI Budget Control), P14 (Rate Limiting)

---

## Overview

This document defines the operational procedures for RSS feed aggregation, including system feed management, user-custom feed submission, health monitoring, error handling, moderation workflows, and policy compliance. All feed operations align with VillageCompute Project Standards and the architectural blueprint.

### Quick Reference

| Feed Domain | Key Components | Policy Reference |
|-------------|----------------|------------------|
| System Feeds | TechCrunch, BBC, Reuters, etc. (admin-managed) | P1 |
| User-Custom Feeds | User-submitted RSS URLs (included in GDPR export) | P1 |
| Health Monitoring | Error thresholds, auto-disable at 5 failures | Operational |
| AI Tagging | LangChain4j topic/sentiment extraction | P2/P10 |
| Refresh Jobs | DEFAULT queue (15min-daily intervals) | I3.T2 |
| Content Retention | 90-day TTL for non-bookmarked items | P14 |

---

## 1. System Feeds Management

### 1.1 Curated Categories

Village Homepage maintains system-managed RSS feeds across the following categories:

- **Technology** - TechCrunch, Hacker News, Ars Technica, The Verge
- **Business & Finance** - Reuters, Financial Times, MarketWatch
- **World News** - BBC World News, The Guardian, Al Jazeera
- **Science & Health** - Scientific American, Nature News, Science Daily
- **Politics** - Politico, The Hill
- **Entertainment** - Entertainment Weekly, Variety
- **Sports** - ESPN, Sports Illustrated
- **Lifestyle** - (To be added in future iterations)

### 1.2 Default Refresh Intervals

System feeds are configured with the following refresh intervals based on content velocity:

| Category | Interval | Rationale |
|----------|----------|-----------|
| Breaking News (Reuters, BBC) | 15 minutes | High-velocity news requires frequent updates |
| Tech News (TechCrunch, HN) | 60 minutes | Moderate update frequency balances freshness and load |
| Analysis/Opinion (FT, Scientific American) | 360 minutes (6 hours) | Long-form content updates less frequently |
| Sports/Entertainment | 30-120 minutes | Event-driven content with variable velocity |

Refresh intervals are enforced via database CHECK constraint (15-1440 minutes).

### 1.3 Adding System Feeds

**Admin-Only Operation:** System feeds are managed via `/admin/api/feeds/sources` endpoints (requires `super_admin` role).

**Procedure:**

1. **Verify Feed URL:** Test RSS/Atom feed URL in browser or curl
2. **Check for Duplicates:** Query `/admin/api/feeds/sources` to ensure URL doesn't exist
3. **Create Feed via Admin API:**

```bash
curl -X POST https://homepage.villagecompute.com/admin/api/feeds/sources \
  -H "Authorization: Bearer <admin_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "New Tech Feed",
    "url": "https://example.com/feed.xml",
    "category": "Technology",
    "refresh_interval_minutes": 60
  }'
```

4. **Verify Creation:** Check response for 201 Created status with source ID
5. **Monitor First Fetch:** Check logs for successful initial fetch (I3.T2 job)

### 1.4 Bulk Seed Data

Default system feeds can be loaded via SQL seed script:

```bash
cd migrations
psql -h $POSTGRES_HOST -p $POSTGRES_PORT -U $POSTGRES_USER -d $POSTGRES_DB \
  -f seeds/rss_sources.sql
```

Seed script uses `ON CONFLICT (url) DO UPDATE` for safe re-execution.

---

## 2. User-Custom Feeds

### 2.1 Submission Process

**Note:** User-submitted custom feeds are **planned for future iterations** (not I3). The schema and infrastructure are ready, but user-facing submission UI and moderation queue are deferred.

**Planned Flow (Future):**

1. User submits RSS URL via `/api/feeds/custom` endpoint (authenticated users only)
2. Backend validates:
   - URL format (HTTP/HTTPS)
   - Reachability (HEAD request, 5-second timeout)
   - Valid RSS/Atom format (basic XML parsing)
3. Feed added with `is_system = false`, `user_id = <submitter_id>`, `is_active = false`
4. Moderation queue review (if user is "untrusted")
   - Trusted users: Auto-approve (`is_active = true`)
   - Untrusted users: Manual review by support team
5. On approval, feed enters refresh rotation (DEFAULT queue)

### 2.2 Validation Rules

- **URL Regex:** `^https?://.*` (HTTP/HTTPS only, no FTP/file:// schemes)
- **Refresh Interval:** 15-1440 minutes (enforced by DB constraint)
- **Duplicate URLs:** Rejected with 409 Conflict
- **Name Length:** 1-255 characters (enforced by validation)

### 2.3 GDPR Compliance (Policy P1)

User-custom feeds are **included in GDPR data export**:

- Export includes: `name`, `url`, `category`, `created_at`, `is_active`
- User subscriptions also exported (join with `user_feed_subscriptions`)
- On account deletion: Cascade delete via FK constraint removes user's custom feeds

---

## 3. Feed Health & Error Handling

### 3.1 Health Monitoring Metrics

Each RSS source tracks:

- **`last_fetched_at`** - Timestamp of last successful fetch
- **`error_count`** - Consecutive error count (reset to 0 on success)
- **`last_error_message`** - Last error message for debugging (TEXT field)
- **`is_active`** - Active/disabled status (manual or auto-disable)

### 3.2 Error Thresholds & Auto-Disable

**Auto-Disable Rule:** After **5 consecutive fetch errors**, source is automatically disabled (`is_active = false`).

**Rationale:** Prevents wasted resources on dead feeds while allowing transient errors (network blips, rate limits).

**Error Scenarios:**

| Error Type | Example | Action |
|------------|---------|--------|
| Network timeout | Feed server unreachable | Increment `error_count`, log error |
| HTTP 404/410 | Feed URL no longer exists | Increment `error_count`, log error |
| HTTP 429 | Rate limit exceeded | Increment `error_count`, retry with backoff |
| Invalid XML | Malformed RSS/Atom | Increment `error_count`, log error |
| Parse error | Missing required fields (title, link) | Increment `error_count`, log error |

**Success Behavior:** On successful fetch, `error_count` resets to 0, `last_error_message` cleared.

### 3.3 Alert Conditions

**Alerting is NOT implemented in I3.T1** but should be added in future iterations:

- Alert if **>20% of system feeds disabled** (indicates widespread issue)
- Alert if **any single system feed disabled >24 hours** (requires manual investigation)
- Alert if **error_count >3 for critical feeds** (BBC, Reuters, TechCrunch)

**Proposed Alerting Tools:** Prometheus metrics + AlertManager (future integration).

---

## 4. Moderation & Content Policy

### 4.1 System Feed Moderation

System feeds are **pre-vetted** by VillageCompute team and require no ongoing moderation. Criteria:

- Reputable news sources with editorial standards
- Publicly accessible (no paywalls blocking RSS)
- Reliable uptime (>99% historical availability)
- No malware/spam history

### 4.2 User-Custom Feed Moderation (Future)

**Moderation Queue Workflow (Planned):**

1. Untrusted users submit custom feed → enters moderation queue (`is_active = false`)
2. Support team reviews via `/admin/api/feeds/moderation` endpoint (future)
3. Check for:
   - Spam domains (blacklist check)
   - Inappropriate content (manual review)
   - Duplicate of existing feed (suggest subscription instead)
4. Approve (`is_active = true`) or Reject (soft delete)

**Trust Level Progression:**

- **Untrusted (default):** All submissions require moderation
- **Trusted (10+ directory karma):** Auto-approve custom feeds
- **Moderator:** Can approve others' submissions (future role)

### 4.3 Prohibited Feed Content

User-custom feeds will be rejected if they contain (future enforcement):

- Adult content (unless site-wide adult content policy changes)
- Hate speech or harassment
- Malware distribution domains
- Copyright infringement (piracy feeds)
- Spam/SEO farms

**Enforcement:** Manual review + domain blacklist integration (future).

---

## 5. AI Tagging & Budget Control

### 5.1 AI Tagging Overview

Feed items are enriched with AI-generated tags via LangChain4j integration (I3.T3):

- **Topics:** Extracted keywords/concepts (e.g., "artificial intelligence", "climate change")
- **Sentiment:** positive, negative, neutral, mixed
- **Categories:** Mapped to homepage categories (Technology, Business, etc.)
- **Confidence:** 0.0-1.0 score for tag quality

**Tagging Job:** BULK queue processes `feed_items` where `ai_tagged = false`.

### 5.2 Budget Control (Policy P2/P10)

**Monthly AI Budget:** $500/month ceiling for LangChain4j API calls.

**Budget Enforcement (I3.T3):**

1. `AiTaggingBudgetService` tracks monthly spend in `ai_usage_tracking` table
2. Before tagging batch, check current month spend
3. If spend >= $500, **skip tagging** and log warning
4. Reset counter on 1st of each month (UTC)

**Prioritization:** High-traffic sources (TechCrunch, BBC) tagged first; low-traffic sources deferred if budget limited.

### 5.3 Tagging Retry Policy

- **Failed tags:** `ai_tagged = false` allows retry on next job run
- **Max retries:** 3 attempts, then mark `ai_tagged = true` with empty tags (prevent infinite retries)

---

## 6. Data Retention & Partitioning

### 6.1 Feed Item TTL (Policy P14)

**Retention Rule:** Non-bookmarked feed items are purged after **90 days** from `published_at`.

**Bookmarked Items:** Users can bookmark articles (future feature) → exempt from TTL.

**Enforcement (Future):** Daily cron job deletes `feed_items WHERE published_at < NOW() - INTERVAL '90 days' AND NOT EXISTS (SELECT 1 FROM bookmarks WHERE feed_item_id = feed_items.id)`.

### 6.2 Monthly Partitioning (Planned)

**Current Status (I3.T1):** Partitioning **NOT YET IMPLEMENTED** (deferred to ops team).

**Planned Strategy:**

- Partition `feed_items` by `published_at` (monthly: `feed_items_y2026m01`, `feed_items_y2026m02`, etc.)
- Automated partition creation via cron job (1st of each month)
- Drop partitions older than 90 days (aligned with TTL policy)
- Partitioning script location: `migrations/scripts/create_partitions.sql` (future)

**Benefits:** Efficient purging, improved query performance on recent items.

---

## 7. Operational Runbook

### 7.1 Troubleshooting Stuck Feeds

**Symptom:** Feed hasn't updated in >24 hours despite `is_active = true`.

**Diagnosis:**

1. Check `last_error_message` and `error_count`:

```sql
SELECT id, name, url, last_fetched_at, error_count, last_error_message
FROM rss_sources
WHERE is_active = true AND last_fetched_at < NOW() - INTERVAL '24 hours';
```

2. Manual fetch test:

```bash
curl -I https://example.com/feed.xml
```

3. Check Quarkus logs for fetch errors (I3.T2 job logs)

**Resolution:**

- If URL changed: Update via admin API (`PATCH /admin/api/feeds/sources/{id}`)
- If temporarily down: Wait for auto-recovery (reset `error_count` if needed)
- If permanently dead: Disable feed (`is_active = false`) or delete

### 7.2 Manual Feed Refresh (Future I3.T2)

**Endpoint (Future):** `POST /admin/api/feeds/sources/{id}/refresh` triggers immediate fetch.

**Use Case:** Force refresh after fixing broken feed URL.

### 7.3 Bulk Feed Health Check

**Query:** Find all disabled feeds with recent errors:

```sql
SELECT name, url, category, error_count, last_error_message, last_fetched_at
FROM rss_sources
WHERE is_active = false AND error_count > 0
ORDER BY last_fetched_at DESC NULLS LAST;
```

**Action:** Review errors, re-enable fixable feeds, delete permanently broken feeds.

### 7.4 Resetting Error Counts

**Admin API (Future):** `POST /admin/api/feeds/sources/{id}/reset-errors` clears `error_count` and `last_error_message`.

**Manual SQL (Development Only):**

```sql
UPDATE rss_sources
SET error_count = 0, last_error_message = NULL, updated_at = NOW()
WHERE id = '<source_uuid>';
```

---

## 8. Future Enhancements

### 8.1 Planned Features (Post-I3)

- **User Feed Discovery:** Browse/search community-submitted feeds
- **Feed Subscriptions UI:** User-facing subscribe/unsubscribe widget
- **Partitioning Automation:** Monthly partition creation + 90-day purge
- **Advanced Moderation:** Spam detection via ML, domain reputation API
- **Feed Analytics:** Popularity metrics, click-through rates per source
- **Feed Health Dashboard:** Admin UI showing error trends, uptime stats

### 8.2 Schema Changes (Future)

- Add `moderation_status` ENUM (pending, approved, rejected) to `rss_sources`
- Add `bookmarks` table for user-saved articles (prevents TTL deletion)
- Add `feed_item_clicks` table for analytics (subject to consent per P14)

---

## 9. Policy Compliance Summary

| Policy | Requirement | Implementation |
|--------|-------------|----------------|
| **P1 (GDPR/CCPA)** | User-custom feeds in data export | Included in `UserDataExportService` (future) |
| **P1 (GDPR/CCPA)** | Cascade delete on account purge | FK constraint `ON DELETE CASCADE` |
| **P2/P10 (AI Budget)** | $500/month tagging ceiling | `AiTaggingBudgetService` checks (I3.T3) |
| **P14 (Rate Limiting)** | Feed operation rate limits | Applied at API gateway (existing) |
| **P14 (Data Retention)** | 90-day TTL for feed items | Purge job (future) + partition drops |

---

## 10. Contact & Support

**Team:** Backend Team (feed aggregation ownership)
**On-Call Rotation:** See `docs/ops/oncall.md` (future)
**Escalation Path:** Backend → Ops → Engineering Manager

**Related Documentation:**
- `docs/architecture/data-flows.md` - Feed refresh → AI tagging flow
- `docs/ops/delayed-jobs.md` (future) - Job queue configuration
- `.codemachine/artifacts/architecture/erd-guide.md` - Database schema reference
