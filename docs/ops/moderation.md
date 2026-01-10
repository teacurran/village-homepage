# Marketplace Moderation System

## Overview

The marketplace moderation system provides user flagging, admin review workflows, AI-powered fraud detection, and automated refund/ban enforcement per Policy P3 and Feature F12.9.

**Key Features:**
- User flag submission with rate limiting (5 flags/day per user)
- Auto-hide listings at 3 pending flags threshold
- Admin moderation queue with approve/dismiss actions
- AI fraud detection with budget-aware fallback to rule-based analysis
- Automatic refunds for listings removed within 24h of payment
- User bans at 2+ chargebacks
- Moderation analytics dashboard

---

## Flag Lifecycle

```
┌─────────────────┐
│ User Submits    │
│ Flag            │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Status: PENDING │──────┐
│ Flag Count += 1 │      │
└────────┬────────┘      │ (3+ pending flags)
         │               │
         │               ▼
         │        ┌──────────────────┐
         │        │ Listing Status:  │
         │        │ ACTIVE → FLAGGED │
         │        └──────────────────┘
         │
         ▼
  ┌─────────────┐
  │ Admin       │
  │ Reviews     │
  └─────┬───────┘
        │
   ┌────┴────┐
   │         │
   ▼         ▼
┌────────┐ ┌─────────┐
│APPROVE │ │ DISMISS │
└───┬────┘ └────┬────┘
    │           │
    ▼           ▼
Remove        Flag Count -= 1
Listing       Restore if 0 pending
Refund (24h)
Ban (2+ CB)
```

---

## User Flag Submission

### Endpoint
```
POST /api/marketplace/listings/{listingId}/flag
Authorization: Bearer <token>

{
  "reason": "fraud",
  "details": "Price too good to be true, requests wire transfer"
}
```

### Flag Reasons

| Reason | Description | Examples |
|--------|-------------|----------|
| `spam` | Duplicate or irrelevant content | Same listing posted 10 times |
| `prohibited_item` | Violates prohibited items policy | Weapons, drugs, adult content |
| `fraud` | Scam indicators | Too-good-to-be-true prices, urgency tactics |
| `duplicate` | Same listing posted multiple times | Same iPhone listing in 5 categories |
| `misleading` | False claims or misrepresentation | "Brand new" but photos show wear |
| `inappropriate` | Offensive or abusive content | Hate speech, harassment |
| `other` | Other violations (requires details) | Any other policy violation |

### Rate Limiting

- **Limit:** 5 flags per 24 hours per user
- **Response:** HTTP 429 Too Many Requests
- **Implementation:** `ListingFlag.countRecentByUser(userId)`

### Auto-Hide Threshold

When a listing receives **3 pending flags**, it is automatically hidden:
- Listing `status` transitions from `active` → `flagged`
- Listing no longer appears in search results or category listings
- Database trigger `check_flag_threshold` handles auto-transition
- Admin review required to restore or permanently remove

---

## Admin Moderation Queue

### List Pending Flags
```
GET /admin/api/moderation/queue
Authorization: Bearer <admin_token>

Response:
[
  {
    "id": "550e8400-...",
    "listing_id": "660f9500-...",
    "listing_title": "iPhone 13 Pro Max - Great Deal!",
    "listing_status": "flagged",
    "user_email": "flagger@example.com",
    "reason": "fraud",
    "details": "Requests Western Union payment",
    "fraud_score": 0.85,
    "fraud_reasons": "{\"reasons\": [\"Suspicious payment method\"], ...}",
    "created_at": "2025-01-10T15:30:00Z"
  }
]
```

### Approve Flag (Remove Listing)
```
POST /admin/api/moderation/flags/{flagId}/approve
Authorization: Bearer <admin_token>

{
  "review_notes": "Confirmed scam - requests Western Union"
}

Actions performed:
1. Flag status → "approved"
2. Listing status → "removed"
3. IF listing.paymentIntentId != null AND created < 24h ago:
   - Create PaymentRefund (reason: moderation_rejection, status: processed)
4. IF seller has 2+ chargebacks:
   - Set user.is_banned = true
   - Mark all seller's active listings as "removed"
```

### Dismiss Flag (False Positive)
```
POST /admin/api/moderation/flags/{flagId}/dismiss
Authorization: Bearer <admin_token>

{
  "review_notes": "False positive - legitimate listing"
}

Actions performed:
1. Flag status → "dismissed"
2. Listing.flag_count -= 1
3. IF flag_count == 0 AND listing.status == "flagged":
   - Listing status → "active"
```

### Moderation Stats Dashboard
```
GET /admin/api/moderation/stats
Authorization: Bearer <admin_token>

Response:
{
  "pending_flags": 42,
  "flagged_listings": 15,
  "approved_flags_24h": 8,
  "dismissed_flags_24h": 12,
  "auto_refunds_issued_24h": 3,
  "banned_users_24h": 1,
  "flag_reason_counts": {
    "fraud": 20,
    "spam": 15,
    "prohibited_item": 7
  },
  "average_review_time_hours": 4.5
}
```

---

## AI Fraud Detection

### Overview

The `FraudDetectionService` analyzes listing content for scam indicators using Claude Sonnet 4 when AI budget allows. When budget is exhausted, it falls back to rule-based keyword matching.

### Budget Integration

Per Policy P2/P10, fraud detection shares the $500/month AI budget:
- **Feed Tagging:** 60% ($300/month) - daily runs
- **Fraud Detection:** 30% ($150/month) - on-demand
- **Good Sites:** 10% ($50/month) - weekly runs

**Budget Actions:**
- **NORMAL** (<75%): Full AI analysis with Claude
- **REDUCE** (75-90%): Continue AI (safety priority)
- **QUEUE** (90-100%): Switch to rule-based fallback
- **HARD_STOP** (>100%): Rule-based only

### AI Prompt (v1.0)

```
Analyze this marketplace listing for potential fraud or policy violations.

LISTING DETAILS:
Title: {title}
Description: {description}
Price: {price}
Category: {category}

CHECK FOR:
1. Scam indicators: too-good-to-be-true prices, urgency tactics, suspicious payment methods
2. Prohibited items: weapons, drugs, counterfeit goods, hazardous materials
3. Duplicate/spam: excessive caps, repeated patterns, generic templates
4. Misleading claims: work-from-home schemes, pyramid schemes, fake credentials

Respond with JSON:
{
  "is_suspicious": true/false,
  "confidence": 0.0-1.0,
  "reasons": ["reason1", "reason2"]
}
```

### Rule-Based Fallback

When AI budget exhausted, uses keyword matching:

| Pattern | Reason |
|---------|--------|
| "western union", "wire transfer", "gift card" | Suspicious payment method |
| "act now", "limited time", "must sell today" | Urgency tactics |
| "prescription", "oxycontin", "adderall" | Prohibited pharmaceuticals |
| "replica", "counterfeit", "knock-off" | Counterfeit goods |
| "work from home" + "$" + "daily" | Work-from-home scheme |
| "passive income", "financial freedom" | MLM indicators |
| >60% UPPERCASE (in title) | Spam indicator |

**Fraud Score:** Suspicious results get 0.70 confidence, clean results get 0.20.

### Fraud Score Storage

- Stored in `listing_flags.fraud_score` (DECIMAL 0.00-1.00)
- Reasons stored in `listing_flags.fraud_reasons` JSONB:
  ```json
  {
    "reasons": ["Suspicious payment method", "Urgency tactics"],
    "prompt_version": "v1.0"
  }
  ```
- Prompt version tracked for audit trail (if prompt changes, can filter old analyses)

---

## Refund Automation

### 24-Hour Refund Window

Per Policy P3, listings removed by moderation within 24 hours of payment creation receive automatic refunds:

```java
Duration timeSinceCreation = Duration.between(listing.createdAt, Instant.now());
if (timeSinceCreation.compareTo(Duration.ofHours(24)) < 0) {
    // Auto-refund
    PaymentRefund refund = new PaymentRefund();
    refund.reason = "moderation_rejection";
    refund.status = "processed";
    refund.persist();
}
```

### Refund Workflow

1. Admin approves flag to remove listing
2. `ModerationService.approveFlag()` checks:
   - Is `listing.paymentIntentId` non-null?
   - Is `listing.createdAt` within 24 hours?
3. If yes, create `PaymentRefund` with:
   - `reason`: `"moderation_rejection"`
   - `status`: `"processed"` (immediate processing)
   - `requested_by_user_id`: admin user ID
   - `notes`: review notes from flag approval
4. Stripe refund API called by async job or webhook handler

### Outside 24-Hour Window

If listing removed after 24 hours:
- NO automatic refund
- Seller keeps payment
- Logged: `"Listing {id} removed but no refund (outside 24h window)"`

---

## User Ban Logic

### Chargeback Threshold

Per Policy P3, users are banned when they accumulate **2 or more chargebacks**:

```java
long chargebacks = PaymentRefund.countChargebacks(userId);
if (chargebacks >= 2) {
    User.banUser(userId, String.format("Repeated chargebacks (%d)", chargebacks));
}
```

### Chargeback Counting

```sql
SELECT COUNT(*)
FROM payment_refunds
WHERE user_id = ?
  AND reason = 'chargeback'
```

### Ban Effects

When `User.banUser(userId, reason)` is called:
1. Set `user.is_banned = true`
2. Set `user.banned_at = NOW()`
3. Set `user.ban_reason = "Repeated chargebacks (3)"`
4. Log: `"BANNED USER: userId={uuid}, reason={reason}"`
5. **TODO:** Mark all user's active listings as "removed"
6. **TODO:** Send email notification with appeal process

### Blocked Actions for Banned Users

Banned users are blocked from:
- Creating marketplace listings
- Submitting flags (`IllegalStateException` thrown)
- Voting on directory sites
- Sending marketplace messages
- Any marketplace actions

**Auth Check:**
```java
if (user.isBanned) {
    throw new IllegalStateException("Banned users cannot submit flags");
}
```

---

## Database Schema

### listing_flags

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID PK | Flag identifier |
| `listing_id` | UUID FK | Listing being flagged |
| `user_id` | UUID FK | User who submitted flag |
| `reason` | TEXT | Flag reason (enum constraint) |
| `details` | TEXT | Additional details |
| `status` | TEXT | pending / approved / dismissed |
| `reviewed_by_user_id` | UUID FK | Admin who reviewed |
| `reviewed_at` | TIMESTAMPTZ | Review timestamp |
| `review_notes` | TEXT | Admin review notes |
| `fraud_score` | DECIMAL(3,2) | AI fraud probability |
| `fraud_reasons` | JSONB | AI analysis results |
| `created_at` | TIMESTAMPTZ | Flag submission time |
| `updated_at` | TIMESTAMPTZ | Last modified time |

**Indexes:**
- `idx_listing_flags_listing_id` (listing_id)
- `idx_listing_flags_status` (status)
- `idx_listing_flags_created_at` (created_at DESC)
- `idx_listing_flags_user_id` (user_id)

**Trigger:** `check_flag_threshold` auto-flags listings at 3 pending flags

### users (ban fields)

| Column | Type | Description |
|--------|------|-------------|
| `is_banned` | BOOLEAN | User banned from platform |
| `banned_at` | TIMESTAMPTZ | Ban timestamp |
| `ban_reason` | TEXT | Ban reason (e.g., "Repeated chargebacks (3)") |

---

## Troubleshooting

### False Positives

**Problem:** Legitimate listings flagged as fraud

**Solutions:**
1. Review AI fraud analysis in `listing_flags.fraud_reasons`
2. Check for keyword false positives (e.g., "urgent sale" != scam urgency)
3. Tune rule-based fallback keywords
4. Adjust AI prompt to reduce false positives
5. Implement trust scoring: trusted users' flags carry more weight

### Flag Spam / Coordinated Attacks

**Problem:** Competitor flags many listings from same seller

**Detection:**
```sql
-- Check if same user flagging many listings from same seller
SELECT f.user_id, l.user_id AS seller_id, COUNT(*)
FROM listing_flags f
JOIN marketplace_listings l ON f.listing_id = l.id
WHERE f.created_at > NOW() - INTERVAL '7 days'
  AND f.status = 'pending'
GROUP BY f.user_id, l.user_id
HAVING COUNT(*) > 5;
```

**Mitigations:**
- Don't auto-hide if all 3 flags from same user
- Track flag accuracy: if user's flags frequently dismissed, reduce weight
- Temporary flag holds for suspicious patterns
- Require details for "other" reason (can't submit blank)

### AI Budget Exhaustion

**Problem:** Fraud detection stopped working mid-month

**Diagnosis:**
```
GET /admin/api/ai-usage/summary

{
  "current_month": "2025-01",
  "total_cost_cents": 52000,
  "budget_cents": 50000,
  "usage_percentage": 104.0,
  "budget_action": "HARD_STOP"
}
```

**Solutions:**
1. Wait until next month for budget reset
2. Rule-based fallback automatically enabled
3. Increase budget limit in `AiTaggingBudgetService` (requires approval)
4. Reduce feed tagging batch size to conserve budget

### Refunds Not Processing

**Problem:** Listing removed but refund not created

**Check:**
1. Was listing paid? (`listing.paymentIntentId != null`)
2. Was removal within 24h? (`listing.createdAt < NOW() - INTERVAL '24 hours'`)
3. Check logs for: `"Created automatic moderation refund for listing {id}"`
4. Check `payment_refunds` table for `reason = 'moderation_rejection'`

**If outside 24h window:**
- Refunds NOT automatic per Policy P3
- User can request manual refund (admin review required)

### User Ban Not Triggered

**Problem:** User with 3 chargebacks still active

**Check:**
```sql
SELECT COUNT(*)
FROM payment_refunds
WHERE user_id = '...'
  AND reason = 'chargeback';
```

**If count >= 2 but not banned:**
- Check logs for: `"BANNED USER: userId={uuid}"`
- Verify `ModerationService.approveFlag()` calls `PaymentRefund.countChargebacks()`
- Manually ban: `User.banUser(userId, "Manual ban - 3 chargebacks")`

---

## GDPR Compliance

### Right to Be Forgotten

When user requests account deletion:
1. **CASCADE delete flags:** `ON DELETE CASCADE` on `user_id` FK
2. Include flags in GDPR export (both submitted and received)
3. After 90-day retention, anonymize: set `user_id = NULL` for aggregate stats

### Data Retention

- **Active flags:** Retained indefinitely (for audit)
- **Deleted listings:** Keep flags for 90 days post-deletion
- **Anonymization:** After 90 days, replace `user_id` with NULL for historical stats

### Transparency

Users can see flags against their listings (with flagger anonymized):
```
GET /api/marketplace/listings/{id}/flags
Authorization: Bearer <owner_token>

Response:
[
  {
    "reason": "spam",
    "details": "Duplicate listing",
    "status": "dismissed",
    "reviewed_at": "2025-01-10T16:00:00Z"
  }
]
```

---

## Metrics & Monitoring

### Key Metrics

| Metric | Query | Alert Threshold |
|--------|-------|-----------------|
| Pending flags | `SELECT COUNT(*) FROM listing_flags WHERE status='pending'` | >100 |
| Average review time | `ModerationStatsType.averageReviewTimeHours` | >8 hours |
| Auto-refunds/day | `COUNT(*) WHERE reason='moderation_rejection' AND created_at > NOW()-INTERVAL'1 day'` | >10 |
| Banned users/week | `COUNT(*) WHERE is_banned=true AND banned_at > NOW()-INTERVAL'7 days'` | >5 |
| Flag spam rate | `COUNT(*) WHERE status='dismissed' / COUNT(*)` | >70% |

### Ops Dashboard

```
GET /admin/api/moderation/stats

Dashboard displays:
- Pending flags count (with trend)
- Flagged listings count
- Approval/dismissal rate (last 24h)
- Average review time
- Refunds issued (last 24h)
- Users banned (last 24h)
- Flag reason breakdown (pie chart)
```

---

## Future Enhancements

1. **ML Model Training:** Train on approved/dismissed flags for better fraud detection
2. **Image Recognition:** Detect weapons, adult content in listing photos
3. **Cross-Listing Fraud:** Detect same scam across multiple listings
4. **Appeal Workflow:** Allow wrongly removed listings to request review
5. **Trust Scoring:** Weight flags based on flagger's historical accuracy
6. **Automated Testing:** Regression tests for false positive/negative rates
