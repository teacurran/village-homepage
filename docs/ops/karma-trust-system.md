# Karma and Trust System - Operations Guide

## Overview

The Karma and Trust System governs user privileges in the Good Sites directory. It rewards quality submissions and voting behavior while preventing spam and abuse through automatic trust level promotion.

**Feature:** F13.6 - Karma-based moderation and trust levels
**Task:** I5.T4

---

## Trust Levels

### Untrusted (Default)
- **Description:** New users and those with low karma
- **Karma Range:** 0-9 points
- **Privileges:**
  - Submit sites (requires moderator approval)
  - Vote on approved sites
  - View directory
- **Restrictions:**
  - Submissions enter moderation queue
  - Cannot edit existing sites
  - Cannot moderate submissions

### Trusted (Auto-promoted at 10 karma)
- **Description:** Established users with proven track record
- **Karma Range:** 10+ points
- **Privileges:**
  - **Auto-publish:** Submissions immediately approved
  - Vote on approved sites
  - View directory
  - Enhanced rate limits (see RateLimitService.Tier)
- **Restrictions:**
  - Cannot moderate others' submissions
  - Cannot edit sites submitted by others

### Moderator (Manual promotion only)
- **Description:** Community moderators with elevated privileges
- **Karma Range:** Any (trust level manually assigned by super admin)
- **Privileges:**
  - All Trusted user privileges
  - **Approve/reject** pending submissions
  - Edit site metadata for quality control
  - Assign categories to existing sites
- **Restrictions:**
  - Cannot ban users (super admin only)
  - Cannot adjust karma manually

---

## Karma Point Rules

### Earning Karma

| Action | Points | Trigger |
|--------|--------|---------|
| Site-category submission approved | **+5** | When moderator approves OR auto-approved for trusted users |
| Upvote received on submitted site | **+1** | When another user upvotes your site-category submission |

### Losing Karma

| Action | Points | Trigger |
|--------|--------|---------|
| Site-category submission rejected | **-2** | When moderator rejects submission for quality issues |
| Downvote received on submitted site | **-1** | When another user downvotes your site-category submission |

**Note:** Karma cannot go below 0 (floor enforced in KarmaService).

---

## Auto-Promotion Logic

Users are automatically promoted from **Untrusted** to **Trusted** when they reach **10 karma points**.

- **Alignment:** The 10-point threshold matches `RateLimitService.Tier.TRUSTED` for consistent tier benefits.
- **Mechanism:** Checked during every karma adjustment in `KarmaService.adjustKarma()`.
- **Effect:** Future submissions immediately approved without moderation.
- **Logging:** Promotion events logged to `karma_audit` with trigger type `submission_approved`.

**Manual Demotion:** Super admins can manually demote users to Untrusted if quality degrades (e.g., spam pattern detected).

---

## Karma Adjustment Triggers

### 1. Submission Approval (Auto or Manual)

**Auto-Approval (Trusted/Moderator users):**
- Triggered in `DirectoryService.submitSite()` after site-category persisted
- Calls `KarmaService.awardForApprovedSubmission(siteCategoryId)`
- Audit entry: `trigger_type=submission_approved`, `entity_type=site_category`

**Manual Approval (Moderator action):**
- Triggered in `DirectoryService.approveSiteCategory()`
- Calls `KarmaService.awardForApprovedSubmission(siteCategoryId)`
- Audit entry includes moderator user ID

### 2. Submission Rejection (Moderator action)

- Triggered in `DirectoryService.rejectSiteCategory()`
- Calls `KarmaService.deductForRejectedSubmission(siteCategoryId)`
- Audit entry: `trigger_type=submission_rejected`, `delta=-2`

### 3. Voting (Upvote/Downvote)

**New Vote:**
- Triggered in `DirectoryVotingService.castVote()`
- Calls `KarmaService.awardForUpvoteReceived()` or `deductForDownvoteReceived()`
- Audit entry: `trigger_type=vote_received`, `entity_type=vote`

**Vote Change (e.g., upvote → downvote):**
- Triggered when user changes existing vote
- Calls `KarmaService.processVoteChange()`
- Net karma adjustment: `delta = newVote - oldVote` (e.g., +1 to -1 = -2 karma)

**Vote Deletion:**
- Triggered in `DirectoryVotingService.removeVote()`
- Calls `KarmaService.processVoteDeleted()`
- Reverses karma effect: `delta = -deletedVoteValue`

### 4. Admin Manual Adjustment

- Triggered via `POST /admin/api/karma/{userId}/adjust` endpoint
- Calls `KarmaService.adminAdjustKarma()`
- Audit entry: `trigger_type=admin_adjustment`, includes admin user ID and reason

---

## API Endpoints

### Public Endpoints

#### GET /api/karma/me
Get current user's karma summary.

**Response:**
```json
{
  "karma": 15,
  "trust_level": "trusted",
  "karma_to_next_level": null,
  "privilege_description": "Trusted: Submissions auto-publish without moderation",
  "can_auto_publish": true,
  "is_moderator": false
}
```

### Admin Endpoints (super_admin role required)

#### GET /admin/api/karma/{userId}
Get any user's karma summary.

#### GET /admin/api/karma/{userId}/history
Get user's karma adjustment history (audit trail).

**Response:** Array of `KarmaAuditSnapshot` records.

#### POST /admin/api/karma/{userId}/adjust
Manually adjust user's karma.

**Request:**
```json
{
  "delta": 10,
  "reason": "Exceptional contribution to directory quality",
  "metadata": {
    "campaign": "Q1_2026_quality_initiative"
  }
}
```

**Response:** Updated karma summary.

#### PATCH /admin/api/karma/{userId}/trust-level
Manually change user's trust level.

**Request:**
```json
{
  "trust_level": "moderator",
  "reason": "Promoted to moderator for consistent quality reviews"
}
```

**Response:** Updated karma summary.

---

## Database Schema

### karma_audit Table

Stores complete audit trail of all karma changes.

**Columns:**
- `id` (UUID, PK): Audit record ID
- `user_id` (UUID, FK): User whose karma was adjusted
- `old_karma` (INT): Karma before adjustment
- `new_karma` (INT): Karma after adjustment
- `delta` (INT): Change in karma
- `old_trust_level` (TEXT): Trust level before
- `new_trust_level` (TEXT): Trust level after
- `reason` (TEXT): Human-readable reason
- `trigger_type` (TEXT): Event type (CHECK constraint)
  - `submission_approved`
  - `submission_rejected`
  - `vote_received`
  - `admin_adjustment`
  - `system_adjustment`
- `trigger_entity_type` (TEXT): Entity type (site_category, vote, manual)
- `trigger_entity_id` (UUID): Entity ID that triggered adjustment
- `adjusted_by_user_id` (UUID, FK): Admin who made manual adjustment (null for automatic)
- `metadata` (JSONB): Additional context
- `created_at` (TIMESTAMPTZ): Timestamp

**Indexes:**
- `idx_karma_audit_user_id` on `user_id`
- `idx_karma_audit_created_at` on `created_at DESC`
- `idx_karma_audit_trigger_type` on `trigger_type`
- `idx_karma_audit_adjusted_by` on `adjusted_by_user_id` (partial, where not null)
- `idx_karma_audit_user_created` on `(user_id, created_at DESC)` (composite)

**Retention:** Indefinite (no partitioning/pruning currently). Consider partitioning if audit volume exceeds 10M records.

---

## Monitoring & Alerts

### Key Metrics

1. **Karma Distribution:**
   - Query: `SELECT trust_level, COUNT(*) FROM users GROUP BY trust_level`
   - Expected: ~70% untrusted, ~25% trusted, ~5% moderator

2. **Auto-Promotion Rate:**
   - Query: Count `karma_audit` records where `old_trust_level='untrusted' AND new_trust_level='trusted'`
   - Expected: 5-10 promotions per week (varies by submission volume)

3. **Rejection Rate:**
   - Query: Count `karma_audit` records with `trigger_type='submission_rejected'`
   - Expected: <20% of total submissions (adjust moderation thresholds if higher)

4. **Manual Adjustments:**
   - Query: Count `karma_audit` records with `trigger_type='admin_adjustment'`
   - Expected: Rare (<1% of total karma changes)

### Alert Conditions

- **High rejection rate:** >30% of submissions rejected in 24h → Investigate spam wave or overly strict moderation
- **Mass karma manipulation:** >50 manual adjustments in 1 hour → Potential admin abuse
- **Negative karma spike:** >10 users with karma < -10 → System bug (karma floor not enforced)

---

## Troubleshooting

### User stuck at 9 karma (not auto-promoted)

**Diagnosis:**
```sql
SELECT id, directory_karma, directory_trust_level
FROM users
WHERE directory_karma >= 10 AND directory_trust_level = 'untrusted';
```

**Root Cause:** Karma adjustment transaction failed or `shouldPromoteToTrusted()` logic not triggered.

**Fix:**
```bash
# Manual promotion via admin API
curl -X PATCH https://homepage.villagecompute.com/admin/api/karma/{userId}/trust-level \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"trust_level": "trusted", "reason": "Manual fix for auto-promotion bug"}'
```

### Karma not awarded for approved submission

**Diagnosis:**
```sql
SELECT * FROM karma_audit
WHERE trigger_entity_type = 'site_category'
  AND trigger_entity_id = '{siteCategoryId}';
```

**Root Cause:** `KarmaService` not called after approval (check DirectoryService transaction logs).

**Fix:**
```bash
# Manual karma award
curl -X POST https://homepage.villagecompute.com/admin/api/karma/{userId}/adjust \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"delta": 5, "reason": "Retroactive karma for site-category {siteCategoryId}"}'
```

### Karma went negative

**Root Cause:** Bug in `KarmaService.adjustKarma()` (karma floor not enforced).

**Fix:**
```sql
UPDATE users SET directory_karma = 0 WHERE directory_karma < 0;
```

**Prevention:** Add database CHECK constraint:
```sql
ALTER TABLE users ADD CONSTRAINT check_karma_non_negative CHECK (directory_karma >= 0);
```

---

## Best Practices

### For Moderators

1. **Reject sparingly:** Only reject submissions that violate guidelines (spam, broken links, offensive content). Low-quality sites should be downvoted by community, not rejected.

2. **Provide context:** When rejecting, use review notes to explain decision (helps users improve future submissions).

3. **Monitor karma trends:** Check `/admin/api/karma/{userId}/history` for users with repeated rejections → may need education or ban.

### For Super Admins

1. **Manual adjustments are exceptional:** Use only for:
   - Correcting system bugs
   - Rewarding exceptional community contributions (e.g., reporting critical security issue)
   - Penalizing abuse not covered by automatic rules (e.g., sockpuppet voting rings)

2. **Always document in reason field:** Include ticket number or explanation for audit compliance.

3. **Moderator promotion criteria:**
   - 50+ karma points
   - 6+ months as Trusted user
   - No rejected submissions in last 90 days
   - Active community participation (voting, quality submissions)

---

## Security Considerations

### Karma Gaming Attacks

**Attack:** User creates multiple accounts to upvote their own submissions (sockpuppets).

**Mitigation:**
- Rate limit vote actions per IP address (see RateLimitService)
- Monitor for suspicious voting patterns (all votes from same IP, rapid succession)
- Manual admin investigation if user gains >10 karma in <1 hour

**Detection Query:**
```sql
SELECT user_id, COUNT(*) as karma_events, MAX(created_at) - MIN(created_at) as time_span
FROM karma_audit
WHERE created_at > NOW() - INTERVAL '1 hour'
GROUP BY user_id
HAVING COUNT(*) > 10;
```

### Privilege Escalation

**Attack:** User exploits auto-promotion to bypass moderation, then submits spam.

**Mitigation:**
- Trusted users can still be demoted by admins
- Downvotes reduce karma → eventual demotion if quality degrades
- Rate limits still apply to Trusted tier (see RateLimitService)

---

## Future Enhancements (Roadmap)

1. **Decay system:** Reduce karma over time for inactive users (prevents "karma banking")
2. **Weighted voting:** Moderator votes count 2x for karma calculations
3. **Category-specific karma:** Track karma per category (e.g., "Technology" vs "Arts")
4. **Karma leaderboard:** Public API endpoint for top contributors
5. **Badges/achievements:** Unlock at karma milestones (25, 50, 100, 250 points)

---

## Related Documentation

- [Good Sites Submission Guide](./good-sites-submission.md) - Submission workflow and approval rules
- [Rate Limiting](./rate-limiting.md) - Tier-based rate limits aligned with karma thresholds
- [Moderation Queue](./moderation-queue.md) - Moderator tools and workflows
