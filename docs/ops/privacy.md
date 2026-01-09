# Privacy Operations Guide

This document provides operational guidance for managing privacy compliance features in Village Homepage, with specific focus on anonymous account merges, data retention, and GDPR/CCPA compliance (Policy P1).

## Table of Contents

- [Overview](#overview)
- [Anonymous-to-Authenticated Account Merge Flow](#anonymous-to-authenticated-account-merge-flow)
- [Data Retention Policy](#data-retention-policy)
- [Consent Management](#consent-management)
- [Cleanup Job Operations](#cleanup-job-operations)
- [Troubleshooting](#troubleshooting)
- [Compliance Auditing](#compliance-auditing)
- [Emergency Procedures](#emergency-procedures)

---

## Overview

Village Homepage implements privacy-first features per Policy P1 (GDPR/CCPA Data Governance) and Policy P9 (Anonymous Cookie Security). The core privacy features are:

- **Anonymous Sessions**: Users can browse without creating an account, tracked via secure `vu_anon_id` cookie
- **Account Merge Flow**: Explicit consent-based merge of anonymous data into authenticated accounts
- **90-Day Retention**: Soft-deleted anonymous accounts are hard-deleted after 90 days
- **Audit Trail**: All merge operations logged with timestamp, IP address, user agent, and policy version
- **Opt-Out Path**: Users can decline merge and start fresh with authenticated account

### Key Components

| Component | Purpose | Location |
|-----------|---------|----------|
| `User` entity | Stores user records (anonymous + authenticated) | `src/main/java/villagecompute/homepage/data/models/User.java` |
| `AccountMergeAudit` entity | Audit trail for merge operations | `src/main/java/villagecompute/homepage/data/models/AccountMergeAudit.java` |
| `AccountMergeService` | Merge orchestration and consent recording | `src/main/java/villagecompute/homepage/services/AccountMergeService.java` |
| `AccountMergeCleanupJobHandler` | Daily job for purging expired records | `src/main/java/villagecompute/homepage/jobs/AccountMergeCleanupJobHandler.java` |
| `users` table | PostgreSQL table for user storage | `migrations/scripts/20250109000500_create_users_table.sql` |
| `account_merge_audit` table | PostgreSQL table for audit logs | `migrations/scripts/20250109000600_create_account_merge_audit.sql` |

### Policy References

- **Policy P1**: GDPR/CCPA data governance - explicit consent, 90-day retention, audit logging
- **Policy P9**: Anonymous cookie security - secure cookie attributes (HttpOnly, Secure, SameSite=Lax)

---

## Anonymous-to-Authenticated Account Merge Flow

### User Journey

1. **Anonymous Session**: User visits homepage without logging in, receives `vu_anon_id` cookie
2. **Customize**: User customizes layout, subscribes to topics (stored in anonymous user's `preferences` JSONB)
3. **OAuth Login**: User clicks "Sign in with Google/Facebook/Apple"
4. **Merge Detection**: System detects anonymous user has data worth merging
5. **Consent Modal**: Frontend shows consent modal with options:
   - **Merge**: "Keep your layout and preferences"
   - **Decline**: "Start fresh with your new account"
6. **Consent Recording**: User's choice recorded with timestamp, IP address, user agent (Policy P1)
7. **Merge Execution** (if consented):
   - Anonymous preferences merged into authenticated account
   - Anonymous user soft-deleted (`deleted_at = NOW()`)
   - Audit record created with `purge_after = NOW() + 90 days`
   - Cleanup job scheduled for 90-day purge
8. **Cleanup** (90 days later): Anonymous user hard-deleted from database

### Sequence Diagram

See [docs/diagrams/anon-merge-seq.puml](../diagrams/anon-merge-seq.puml) for detailed sequence diagram showing:
- OAuth login flow with merge detection
- Consent recording with P1 compliance fields
- Merge execution with transaction boundaries
- Cleanup job scheduling and execution
- Opt-out path for users who decline

### API Endpoints

| Endpoint | Method | Purpose | Auth Required |
|----------|--------|---------|---------------|
| `/api/auth/login/{provider}` | GET | Initiate OAuth login (detects merge opportunity in callback) | No |
| `/api/auth/merge-consent` | POST | Record user consent for merge | Yes (OAuth token) |
| `/api/auth/execute-merge` | POST | Execute merge after consent recorded | Yes (OAuth token) |

**Example: Record Consent**
```bash
curl -X POST https://homepage.villagecompute.com/api/auth/merge-consent \
  -H "Authorization: Bearer <oauth_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "anonymousUserId": "a1b2c3d4-...",
    "authenticatedUserId": "e5f6g7h8-...",
    "consentGiven": true
  }'
```

**Example: Execute Merge**
```bash
curl -X POST https://homepage.villagecompute.com/api/auth/execute-merge \
  -H "Authorization: Bearer <oauth_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "auditId": "m9n0o1p2-..."
  }'
```

---

## Data Retention Policy

### Anonymous Users

| State | Retention | Location | Purge Trigger |
|-------|-----------|----------|---------------|
| Active (not merged) | Indefinite | `users` table (`deleted_at IS NULL`) | User-initiated deletion |
| Soft-deleted (merged) | 90 days | `users` table (`deleted_at IS NOT NULL`) | `AccountMergeCleanupJobHandler` |
| Hard-deleted | Permanent | N/A (removed from database) | N/A |

### Audit Records

| Record Type | Retention | Location | Purge Trigger |
|-------------|-----------|----------|---------------|
| Merge audit (consent given) | 90 days | `account_merge_audit` table | `AccountMergeCleanupJobHandler` |
| Merge audit (consent declined) | 90 days | `account_merge_audit` table | Manual cleanup or policy change |

**Note**: Declined merge audits are kept for compliance (proof that consent was requested) but may be purged via future policy updates. Current implementation does not auto-purge declined audits.

### Authenticated Users

Authenticated user records are retained indefinitely until user requests deletion via GDPR data deletion request (see [Emergency Procedures](#emergency-procedures)).

---

## Consent Management

### Consent Recording Requirements (Policy P1)

All merge consent records MUST include:

1. **Consent Decision**: Boolean flag (`consent_given`)
2. **Timestamp**: ISO 8601 timestamp of consent action (`consent_timestamp`)
3. **IP Address**: IPv4 or IPv6 address of user at time of consent (`ip_address`)
4. **User Agent**: Full browser/client user agent string (`user_agent`)
5. **Policy Version**: Privacy policy version user consented to (`consent_policy_version`, currently "1.0")

### Consent Policy Version Management

The current policy version is defined in `AccountMergeService.CURRENT_POLICY_VERSION = "1.0"`.

**When to increment policy version:**
- Material changes to privacy policy (data handling, retention periods, third-party sharing)
- Changes to merge data scope (e.g., adding new data types to merge)
- Regulatory requirement updates (GDPR, CCPA amendments)

**How to increment:**
1. Update `AccountMergeService.CURRENT_POLICY_VERSION` to new version (e.g., "2.0")
2. Update privacy policy documentation with change date and summary
3. Re-prompt existing users for consent if required by regulation
4. Audit team reviews change log for compliance

### Consent Verification

**SQL Query: Check consent for a specific user**
```sql
SELECT
  ama.id AS audit_id,
  ama.consent_given,
  ama.consent_timestamp,
  ama.consent_policy_version,
  ama.ip_address,
  ama.purge_after,
  u_anon.email AS anonymous_email,
  u_auth.email AS authenticated_email
FROM account_merge_audit ama
LEFT JOIN users u_anon ON ama.anonymous_user_id = u_anon.id
LEFT JOIN users u_auth ON ama.authenticated_user_id = u_auth.id
WHERE u_auth.email = 'user@example.com'
ORDER BY ama.consent_timestamp DESC;
```

---

## Cleanup Job Operations

### Job Overview

- **Job Type**: `ACCOUNT_MERGE_CLEANUP`
- **Queue**: `DEFAULT`
- **Cadence**: Daily at 4am UTC (configured via Quarkus `@Scheduled`)
- **Handler**: `AccountMergeCleanupJobHandler`
- **Purpose**: Hard-delete soft-deleted anonymous users after 90-day retention period

### Job Execution Flow

1. **Query**: Find audit records where `purge_after <= NOW()`
2. **Validate**: Ensure anonymous user is soft-deleted and `is_anonymous = TRUE`
3. **Hard-Delete**: Delete anonymous user record from `users` table
4. **Cleanup Audit**: Delete audit record from `account_merge_audit` table
5. **Logging**: Record all operations with trace context for compliance audits

### Monitoring

**Metrics** (OpenTelemetry):
- `account_merge.cleanup.records_purged` (counter) - Total records purged
- `account_merge.cleanup.duration` (histogram) - Job execution time
- `account_merge.cleanup.failures` (counter) - Failed purge operations

**Dashboard Queries**:
```promql
# Daily purge count
sum(increase(account_merge_cleanup_records_purged_total[24h]))

# Average job duration
histogram_quantile(0.95, rate(account_merge_cleanup_duration_seconds_bucket[1h]))

# Failure rate
rate(account_merge_cleanup_failures_total[1h]) / rate(account_merge_cleanup_records_purged_total[1h])
```

### Manual Trigger

**Production** (requires kubectl access to k3s cluster):
```bash
# Trigger job via REST API (requires super_admin JWT)
curl -X POST https://homepage.villagecompute.com/admin/api/jobs/trigger \
  -H "Authorization: Bearer <super_admin_jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "jobType": "ACCOUNT_MERGE_CLEANUP",
    "payload": {}
  }'
```

**Development** (via psql):
```bash
# Connect to local dev database
docker exec -it village-homepage-db psql -U postgres -d homepage_dev

# Manually insert cleanup job
INSERT INTO delayed_jobs (job_type, queue, priority, scheduled_at, payload, created_at)
VALUES (
  'ACCOUNT_MERGE_CLEANUP',
  'DEFAULT',
  10,
  NOW(),
  '{}',
  NOW()
);

# Job will be picked up by scheduler on next poll cycle (10 seconds for DEFAULT queue)
```

---

## Troubleshooting

### Issue: Cleanup Job Not Running

**Symptoms**: Audit records with `purge_after` in the past are not being deleted.

**Diagnostics**:
```sql
-- Check for pending purge records
SELECT COUNT(*) AS pending_purge_count
FROM account_merge_audit
WHERE purge_after <= NOW();

-- Check if cleanup jobs are being created
SELECT * FROM delayed_jobs
WHERE job_type = 'ACCOUNT_MERGE_CLEANUP'
ORDER BY created_at DESC
LIMIT 10;
```

**Resolution**:
1. Verify scheduler is running: `kubectl logs -l app=village-homepage -n village-homepage | grep "poll(JobQueue.DEFAULT)"`
2. Check for handler registration errors: `kubectl logs -l app=village-homepage -n village-homepage | grep "AccountMergeCleanupJobHandler"`
3. Manually trigger cleanup job (see [Manual Trigger](#manual-trigger))

### Issue: Merge Execution Fails with "User Not Found"

**Symptoms**: Merge API returns 500 error with "Anonymous user not found" or "Authenticated user not found".

**Diagnostics**:
```sql
-- Verify users exist
SELECT id, email, is_anonymous, deleted_at
FROM users
WHERE id IN ('<anonymous_user_id>', '<authenticated_user_id>');

-- Check audit record
SELECT * FROM account_merge_audit
WHERE id = '<audit_id>';
```

**Resolution**:
1. If anonymous user already purged: Audit record should reflect this (check `purge_after` timestamp)
2. If authenticated user deleted: User may have requested account deletion; merge cannot proceed
3. If audit record not found: Consent may not have been recorded; retry consent flow

### Issue: Consent Recording Fails with "IP Address Required"

**Symptoms**: Consent API returns 400 error with "IP address is required for consent tracking (Policy P1)".

**Root Cause**: Client IP address not being forwarded to backend (reverse proxy misconfiguration).

**Resolution**:
1. Verify reverse proxy (Traefik/Nginx) is forwarding `X-Forwarded-For` header
2. Check Quarkus config: `quarkus.http.proxy.enable-forwarded-header=true`
3. If using Cloudflare, verify `CF-Connecting-IP` header is being forwarded

### Issue: Cleanup Job Partially Failed

**Symptoms**: Job completes with warning "Cleanup job partially failed: X/Y records failed".

**Diagnostics**:
```bash
# Check job logs for specific failures
kubectl logs -l app=village-homepage -n village-homepage \
  | grep "Failed to purge anonymous user for audit"
```

**Common Causes**:
1. Anonymous user not soft-deleted (`deleted_at IS NULL`)
2. Anonymous user is actually authenticated (`is_anonymous = FALSE`)
3. Database deadlock or constraint violation

**Resolution**:
1. Investigate failed audit IDs in logs
2. Manually inspect affected records in database
3. Correct data inconsistencies via SQL update
4. Re-trigger cleanup job for failed records

---

## Compliance Auditing

### GDPR Right to Access

Users can request all personal data held by Village Homepage per GDPR Article 15.

**SQL Query: Export all data for user**
```sql
-- User profile
SELECT
  id, email, oauth_provider, display_name,
  preferences, directory_karma, directory_trust_level,
  analytics_consent, created_at, updated_at, deleted_at
FROM users
WHERE email = 'user@example.com';

-- Merge audit trail
SELECT
  ama.id, ama.consent_given, ama.consent_timestamp,
  ama.consent_policy_version, ama.merged_data_summary,
  ama.purge_after
FROM account_merge_audit ama
JOIN users u ON ama.authenticated_user_id = u.id
WHERE u.email = 'user@example.com'
ORDER BY ama.consent_timestamp DESC;
```

**Export Format**: JSON (see `User.toSnapshot()` and `AccountMergeAudit.toSnapshot()` methods)

### GDPR Right to Erasure

Users can request deletion of personal data per GDPR Article 17.

**Procedure**:
1. Verify user identity via support ticket (email confirmation + OAuth re-auth)
2. Execute deletion script (see [Emergency Procedures](#emergency-procedures))
3. Confirm deletion via audit log
4. Respond to user within 30 days per GDPR timeline

### CCPA Data Sale Disclosure

Village Homepage does **NOT** sell user data to third parties. No disclosure required per CCPA § 1798.100.

### Audit Report Generation

**SQL Query: Monthly merge statistics**
```sql
SELECT
  DATE_TRUNC('month', consent_timestamp) AS month,
  COUNT(*) FILTER (WHERE consent_given = TRUE) AS consents_given,
  COUNT(*) FILTER (WHERE consent_given = FALSE) AS consents_declined,
  COUNT(*) AS total_consent_requests,
  ROUND(100.0 * COUNT(*) FILTER (WHERE consent_given = TRUE) / COUNT(*), 2) AS consent_rate_pct
FROM account_merge_audit
WHERE consent_timestamp >= NOW() - INTERVAL '12 months'
GROUP BY month
ORDER BY month DESC;
```

**SQL Query: Retention compliance check**
```sql
-- Find anonymous users soft-deleted >90 days ago (should be empty if cleanup job working)
SELECT
  u.id, u.deleted_at,
  NOW() - u.deleted_at AS days_since_deletion,
  ama.purge_after
FROM users u
LEFT JOIN account_merge_audit ama ON u.id = ama.anonymous_user_id
WHERE u.is_anonymous = TRUE
  AND u.deleted_at IS NOT NULL
  AND u.deleted_at < NOW() - INTERVAL '90 days'
ORDER BY u.deleted_at ASC;
```

---

## Emergency Procedures

### Immediate User Deletion (GDPR Request)

**Use Case**: User requests immediate deletion of all personal data.

**Script** (requires database access):
```sql
BEGIN;

-- 1. Find user by email
SELECT id, email, is_anonymous FROM users WHERE email = 'user@example.com';
-- Note the user ID for audit trail

-- 2. Delete merge audit records
DELETE FROM account_merge_audit
WHERE authenticated_user_id = '<user_id>'
   OR anonymous_user_id = '<user_id>';

-- 3. Delete user record
DELETE FROM users WHERE id = '<user_id>';

-- 4. Verify deletion
SELECT COUNT(*) FROM users WHERE id = '<user_id>';
-- Should return 0

COMMIT;
```

**Post-Deletion**:
1. Log deletion in compliance audit spreadsheet (who, when, reason)
2. Notify user via email: "Your account and all associated data have been permanently deleted."
3. Archive deletion ticket with user ID and timestamp

### Bulk Cleanup (Backlog Resolution)

**Use Case**: Cleanup job failed for extended period, large backlog of expired records.

**Script** (requires psql access):
```sql
BEGIN;

-- Find all records ready for purge
CREATE TEMP TABLE purge_candidates AS
SELECT
  ama.id AS audit_id,
  ama.anonymous_user_id,
  u.deleted_at
FROM account_merge_audit ama
JOIN users u ON ama.anonymous_user_id = u.id
WHERE ama.purge_after <= NOW()
  AND u.deleted_at IS NOT NULL
  AND u.is_anonymous = TRUE;

-- Display summary
SELECT COUNT(*) AS total_records FROM purge_candidates;

-- Hard-delete anonymous users
DELETE FROM users
WHERE id IN (SELECT anonymous_user_id FROM purge_candidates);

-- Delete audit records
DELETE FROM account_merge_audit
WHERE id IN (SELECT audit_id FROM purge_candidates);

-- Verify cleanup
SELECT COUNT(*) FROM account_merge_audit WHERE purge_after <= NOW();
-- Should return 0 or very small number

COMMIT;
```

**Post-Cleanup**:
1. Investigate root cause of cleanup job failure (check logs, metrics)
2. Fix underlying issue (scheduler config, handler registration, database performance)
3. Monitor next scheduled job run for success

### Data Breach Response

**Use Case**: Unauthorized access to user data detected.

**Immediate Actions**:
1. **Isolate**: Take affected database replica offline (read-only mode)
2. **Assess**: Identify scope of breach (which users, which data fields)
3. **Notify**: Report to compliance team within 1 hour
4. **Contain**: Rotate all database credentials, API keys, JWT secrets

**72-Hour Actions** (GDPR Article 33):
1. Report breach to supervisory authority (EU: national DPA, US: FTC)
2. Prepare breach notification email for affected users
3. Document timeline, scope, remediation steps

**Follow-Up**:
1. Conduct post-mortem with security team
2. Implement additional security controls (network segmentation, access logging)
3. Update security.md with lessons learned

---

## Additional Resources

- **Sequence Diagram**: [docs/diagrams/anon-merge-seq.puml](../diagrams/anon-merge-seq.puml)
- **Security Operations**: [docs/ops/security.md](./security.md)
- **Async Workloads**: [docs/ops/async-workloads.md](./async-workloads.md)
- **Database Schema**: [docs/diagrams/erd.puml](../diagrams/erd.puml)
- **GDPR Compliance Checklist**: https://gdpr.eu/checklist/
- **CCPA Resource Center**: https://oag.ca.gov/privacy/ccpa

---

## Revision History

| Date | Version | Author | Changes |
|------|---------|--------|---------|
| 2025-01-09 | 1.0 | System | Initial documentation for I2.T4 completion |

