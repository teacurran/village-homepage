# Good Sites Submission Flow

## Overview

The Good Sites directory submission system allows users to submit websites for inclusion in a hand-curated web directory similar to the classic Yahoo Directory or DMOZ. This document describes the submission flow, moderation process, and operational considerations.

## Feature References

- **F13.2**: Hand-curated web directory (Good Sites)
- **F13.3**: Reddit-style up/down voting (login required)
- **F13.6**: Karma-based trust system for moderation

## Submission Flow

### 1. User Submits Site

**Endpoint**: `POST /api/good-sites/submissions`

**Request Payload**:
```json
{
  "url": "https://news.ycombinator.com",
  "category_ids": ["550e8400-e29b-41d4-a716-446655440000"],
  "title": "Hacker News",
  "description": "Social news website focusing on computer science",
  "custom_image_url": null
}
```

**Validation**:
- URL must be valid HTTP(S) URL
- At least 1 category required, maximum 3
- Title max 200 characters (optional, fetched from OpenGraph if blank)
- Description max 2000 characters (optional)
- Custom image URL must be HTTPS (optional override of screenshot)

### 2. URL Normalization

The system normalizes URLs for duplicate detection:

1. **HTTPS enforcement**: `http://example.com` → `https://example.com`
2. **Trailing slash removal**: `https://example.com/` → `https://example.com`
3. **Lowercase domain**: Applied during storage
4. **Preserve path/query**: `/about?q=test` preserved exactly

**Duplicate Detection**: Unique constraint on `directory_sites.url` column prevents same URL from being submitted twice.

### 3. Metadata Fetching

**Current Implementation (I5.T2)**:
- Title/description extracted from user input or URL domain
- User-provided values sanitized (HTML tags stripped)
- OpenGraph metadata fetch is stubbed (TODO: I5.T3)

**Future Enhancement (I5.T3)**:
- Fetch OpenGraph metadata from URL (og:title, og:description, og:image)
- Screenshot capture via jvppeteer
- Favicon extraction

### 4. Karma-Based Auto-Approval

User's `directory_trust_level` determines submission status:

| Trust Level | Submission Status | Requires Moderation | Link Count Updated |
|-------------|-------------------|---------------------|-------------------|
| `untrusted` | pending | Yes | No |
| `trusted` | approved | No | Yes (immediate) |
| `moderator` | approved | No | Yes (immediate) |

**Trust Level Promotion**:
- Users start as `untrusted` (default)
- Gain karma through quality submissions and upvotes
- Manual promotion to `trusted` or `moderator` by super admins

### 5. Site and Category Creation

**DirectorySite Record**:
- Created with normalized URL and extracted domain
- Status: "pending" (always, even for auto-approved)
- Metadata: title, description, og_image_url, custom_image_url
- Submitted by: user_id from authentication context

**DirectorySiteCategory Records** (one per category):
- Junction table linking site to categories
- Separate status per category: "pending" or "approved"
- Vote aggregates: upvotes, downvotes, score (initially 0)
- If auto-approved: `category.link_count` incremented immediately

### 6. Response

**Auto-Approved (trusted/moderator users)**:
```json
{
  "site_id": "550e8400-e29b-41d4-a716-446655440001",
  "status": "approved",
  "title": "Hacker News",
  "description": "Social news website...",
  "categories_pending": [],
  "categories_approved": ["660e8400-e29b-41d4-a716-446655440000"],
  "message": "Site submitted and approved automatically (trusted user)"
}
```

**Pending Moderation (untrusted users)**:
```json
{
  "site_id": "550e8400-e29b-41d4-a716-446655440001",
  "status": "pending",
  "title": "Hacker News",
  "description": "Social news website...",
  "categories_pending": ["660e8400-e29b-41d4-a716-446655440000"],
  "categories_approved": [],
  "message": "Site submitted and awaiting moderation approval"
}
```

## Moderation Queue

### Finding Pending Submissions

**SQL Query**:
```sql
SELECT
  ds.id AS site_id,
  ds.url,
  ds.title,
  ds.submitted_by_user_id,
  dsc.category_id,
  dsc.status AS category_status,
  dsc.created_at
FROM directory_sites ds
JOIN directory_site_categories dsc ON ds.id = dsc.site_id
WHERE dsc.status = 'pending'
ORDER BY dsc.created_at ASC;
```

**Entity Query**:
```java
List<DirectorySiteCategory> pending = DirectorySiteCategory.findAllPending();
```

### Approval Process

**Manual Approval** (moderators/admins):
1. Review site URL, title, description
2. Verify site is appropriate for assigned categories
3. Approve via: `siteCategory.approve(moderatorUserId)`
4. Side effects:
   - Status changed to "approved"
   - Category link_count incremented
   - Site becomes visible in category browsing

**Rejection**:
1. Review site and determine inappropriateness
2. Reject via: `siteCategory.reject()`
3. Side effects:
   - Status changed to "rejected"
   - Site hidden from category
   - User notified (TODO: notification system)

## Voting System

### Vote Mechanics

- **Login required**: Anonymous users cannot vote
- **Vote values**: +1 (upvote) or -1 (downvote)
- **One vote per user per site+category**: Enforced by unique constraint
- **Vote scoping**: Votes are on site-category membership, not site itself
  - Rationale: Site might be excellent in one category but mediocre in another

### Vote Aggregation

**Cached Denormalized Values** (on `directory_site_categories`):
- `upvotes`: Count of +1 votes
- `downvotes`: Count of -1 votes
- `score`: upvotes - downvotes (used for ranking)

**Update Trigger**:
- Aggregates updated whenever vote created/updated/deleted
- Call `DirectoryVote.updateAggregates()` after vote change

**SQL Example**:
```sql
SELECT
  SUM(CASE WHEN vote = 1 THEN 1 ELSE 0 END) AS upvotes,
  SUM(CASE WHEN vote = -1 THEN 1 ELSE 0 END) AS downvotes,
  SUM(vote) AS score
FROM directory_votes
WHERE site_category_id = ?;
```

### Ranking

**Ranking Algorithm** (TODO: I5.T7 background job):
- Sites within category ordered by score (high to low)
- Rank stored in `directory_site_categories.rank_in_category`
- Recalculated hourly by background job
- Top-ranked sites "bubble up" to parent categories

## Status Transitions

### DirectorySite Lifecycle

```
pending → approved (moderator approval or auto-approve)
pending → rejected (moderator rejection)
approved → dead (link health check failure)
```

### DirectorySiteCategory Lifecycle

```
pending → approved (moderator approval per category)
pending → rejected (moderator rejection per category)
```

**Note**: Site can be approved in one category but pending/rejected in another.

## Duplicate Detection

### Rules

1. **URL uniqueness**: Enforced by unique index on `directory_sites.url`
2. **Normalization applied first**: Before duplicate check
3. **Case sensitivity**: Path is case-sensitive, domain is normalized to lowercase
4. **Subdomain significance**: `www.example.com` ≠ `example.com` (different sites)

### Edge Cases

| URL 1 | URL 2 | Duplicate? | Reason |
|-------|-------|------------|--------|
| http://example.com | https://example.com | Yes | HTTP upgraded to HTTPS |
| example.com/ | example.com | Yes | Trailing slash removed |
| example.com/About | example.com/about | No | Path is case-sensitive |
| www.example.com | example.com | No | Subdomain matters |

## Content Sanitization

### User-Generated Content

**Policy P13**: All user-generated content must be sanitized to prevent XSS.

**Sanitization Points**:
1. **Title**: HTML tags stripped, max 200 chars
2. **Description**: HTML tags stripped, max 2000 chars
3. **URL**: Validated against URL pattern, no `javascript:` or `data:` schemes
4. **Custom image URL**: HTTPS only, validated URL pattern

**Implementation**:
```java
String sanitized = Jsoup.clean(userInput, Safelist.none());
```

This removes all HTML tags while preserving text content.

## Database Schema

### directory_sites

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| id | UUID | NOT NULL | Primary key |
| url | TEXT | NOT NULL | Normalized URL (unique) |
| domain | TEXT | NOT NULL | Extracted domain |
| title | TEXT | NOT NULL | Site title |
| description | TEXT | NULL | Site description |
| screenshot_url | TEXT | NULL | Screenshot in R2 (I5.T3) |
| screenshot_captured_at | TIMESTAMPTZ | NULL | Screenshot timestamp |
| og_image_url | TEXT | NULL | OpenGraph image |
| favicon_url | TEXT | NULL | Favicon URL |
| custom_image_url | TEXT | NULL | User override image |
| submitted_by_user_id | UUID | NOT NULL | FK to users |
| status | TEXT | NOT NULL | pending/approved/rejected/dead |
| last_checked_at | TIMESTAMPTZ | NULL | Link health check |
| is_dead | BOOLEAN | NOT NULL | Dead link flag |
| created_at | TIMESTAMPTZ | NOT NULL | Creation timestamp |
| updated_at | TIMESTAMPTZ | NOT NULL | Update timestamp |

**Indexes**:
- `idx_directory_sites_url` (UNIQUE): Duplicate detection
- `idx_directory_sites_user_id`: User's submissions
- `idx_directory_sites_status`: Moderation queue
- `idx_directory_sites_domain`: Domain lookup
- `idx_directory_sites_dead`: Link health cleanup

### directory_site_categories

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| id | UUID | NOT NULL | Primary key |
| site_id | UUID | NOT NULL | FK to directory_sites |
| category_id | UUID | NOT NULL | FK to directory_categories |
| score | INT | NOT NULL | Cached vote score |
| upvotes | INT | NOT NULL | Cached upvote count |
| downvotes | INT | NOT NULL | Cached downvote count |
| rank_in_category | INT | NULL | Ranking position |
| submitted_by_user_id | UUID | NOT NULL | FK to users |
| approved_by_user_id | UUID | NULL | FK to users |
| status | TEXT | NOT NULL | pending/approved/rejected |
| created_at | TIMESTAMPTZ | NOT NULL | Creation timestamp |
| updated_at | TIMESTAMPTZ | NOT NULL | Update timestamp |

**Constraints**:
- `uq_directory_site_categories_site_category` (UNIQUE): One site per category

**Indexes**:
- `idx_directory_site_categories_category_approved`: Category browsing
- `idx_directory_site_categories_site_id`: Site's categories
- `idx_directory_site_categories_status`: Moderation queue
- `idx_directory_site_categories_category_score`: Ranking queries

### directory_votes

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| id | UUID | NOT NULL | Primary key |
| site_category_id | UUID | NOT NULL | FK to directory_site_categories |
| user_id | UUID | NOT NULL | FK to users |
| vote | SMALLINT | NOT NULL | +1 or -1 |
| created_at | TIMESTAMPTZ | NOT NULL | Creation timestamp |
| updated_at | TIMESTAMPTZ | NOT NULL | Update timestamp |

**Constraints**:
- `uq_directory_votes_user_site_category` (UNIQUE): One vote per user per site+category
- `chk_directory_votes_vote`: Vote must be +1 or -1

**Indexes**:
- `idx_directory_votes_site_category_id`: Vote aggregation
- `idx_directory_votes_user_id`: User's voting history

## Monitoring & Alerts

### Key Metrics

1. **Submission Rate**: Submissions per hour (track spam surges)
2. **Moderation Queue Size**: Pending count (alert if >1000)
3. **Auto-Approval Rate**: % of submissions auto-approved (track trust system health)
4. **Duplicate Submissions**: Count per user (detect spam)
5. **Vote Rate**: Votes per hour (track engagement)

### Operational Queries

**Moderation queue backlog**:
```sql
SELECT COUNT(*) FROM directory_site_categories WHERE status = 'pending';
```

**Top submitters (last 24h)**:
```sql
SELECT submitted_by_user_id, COUNT(*) AS count
FROM directory_sites
WHERE created_at > NOW() - INTERVAL '24 hours'
GROUP BY submitted_by_user_id
ORDER BY count DESC
LIMIT 10;
```

**Spam detection (duplicate domain submissions)**:
```sql
SELECT domain, COUNT(*) AS count
FROM directory_sites
WHERE created_at > NOW() - INTERVAL '1 hour'
GROUP BY domain
HAVING COUNT(*) > 5
ORDER BY count DESC;
```

## Future Enhancements

### I5.T3: Screenshot Capture
- Auto-capture 1280x800 screenshots via jvppeteer
- Store in Cloudflare R2 bucket
- Version tracking (detect visual changes over time)

### I5.T4: Karma System
- Award karma points for approved submissions
- Deduct karma for rejected submissions
- Auto-promote users to "trusted" at karma thresholds

### I5.T5: Browse UI
- Public-facing category browsing
- Sort by score, date, alphabetical
- Pagination and infinite scroll

### I5.T7: Background Jobs
- Link health checking (detect dead links)
- Rank recalculation (hourly)
- OpenGraph metadata refresh (weekly)
- Screenshot refresh (monthly)

## Troubleshooting

### Duplicate URL Error

**Symptom**: User receives 409 Conflict error when submitting site.

**Cause**: URL already exists in `directory_sites` table.

**Resolution**:
1. Check if URL is truly duplicate:
   ```sql
   SELECT * FROM directory_sites WHERE url = 'https://example.com';
   ```
2. If legitimate duplicate, inform user site already submitted
3. If different URL but conflict, check normalization logic

### Submissions Not Auto-Approving

**Symptom**: Trusted user's submissions go to pending queue.

**Cause**: User's `directory_trust_level` not set correctly.

**Resolution**:
1. Check user's trust level:
   ```sql
   SELECT id, email, directory_trust_level FROM users WHERE id = ?;
   ```
2. Update if incorrect:
   ```sql
   UPDATE users SET directory_trust_level = 'trusted' WHERE id = ?;
   ```

### Category Link Count Incorrect

**Symptom**: Category shows wrong number of sites.

**Cause**: Link count cache out of sync (approval/rejection/deletion not updating count).

**Resolution**:
1. Recalculate from site_categories:
   ```sql
   UPDATE directory_categories
   SET link_count = (
     SELECT COUNT(*)
     FROM directory_site_categories
     WHERE category_id = directory_categories.id
       AND status = 'approved'
   );
   ```

## Support Contacts

- **Engineering**: homepage-dev@villagecompute.com
- **Operations**: ops@villagecompute.com
- **On-call**: PagerDuty rotation

## Change Log

| Date | Version | Changes |
|------|---------|---------|
| 2025-01-11 | 1.0 | Initial documentation for I5.T2 |
