# Good Sites Directory - Release Runbook

**Feature:** Good Sites Directory (Web Directory with Voting, Karma, and Link Health Monitoring)
**Iteration:** I5
**Release Date:** TBD
**Owner:** Engineering Team
**Reviewers:** Platform Team, QA Lead

---

## Pre-Release Checklist

### 1. Code Quality Verification

- [ ] All unit tests pass: `./mvnw test -Dtest=*Voting*Test,*Karma*Test,*LinkHealth*Test,*Screenshot*Test`
- [ ] All integration tests pass: `./mvnw test -Dtest=GoodSitesResourceTest`
- [ ] Coverage ≥80% for Good Sites modules: `./mvnw jacoco:report`
- [ ] SonarCloud quality gate passed (0 bugs, 0 vulnerabilities)
- [ ] Code formatting applied: `./mvnw spotless:apply`
- [ ] No secrets leaked: `npm run lint:secrets`

### 2. E2E and Load Testing

- [ ] E2E tests pass on beta environment: `npm run test:e2e tests/e2e/good-sites.spec.ts`
- [ ] Load test for screenshot queue completed: `k6 run tests/load/screenshot-queue.js`
  - [ ] p95 screenshot capture <5s
  - [ ] p99 screenshot capture <10s
  - [ ] Semaphore wait time <30s
  - [ ] Error rate <5%
- [ ] Screenshot capture + feed ingestion interplay tested (concurrent load)
- [ ] No database connection pool exhaustion
- [ ] No browser pool leaks detected

### 3. Database Migrations

- [ ] Migrations reviewed and tested in beta:
  - [ ] `directory_categories` table created
  - [ ] `directory_sites` table created
  - [ ] `directory_site_categories` table created (junction table)
  - [ ] `directory_votes` table created
  - [ ] `directory_screenshot_versions` table created
  - [ ] `karma_audit` table created
  - [ ] Users table updated with `directory_karma`, `directory_trust_level` fields
  - [ ] Indexes verified: `idx_directory_sites_domain`, `idx_directory_votes_user_site_category`, etc.
- [ ] Migration rollback tested on staging
- [ ] Data validation queries prepared (see [Verification Scripts](#verification-scripts))

### 4. Seed Data

- [ ] Root categories seeded (Arts, Business, Computers, News, Recreation, Science, Society)
- [ ] Sample sites added to each category (minimum 10 sites per category)
- [ ] Hierarchical category structure verified (2-3 levels depth)
- [ ] Test users created with varied karma levels:
  - [ ] Untrusted user (karma <10)
  - [ ] Trusted user (karma ≥10)
  - [ ] Moderator user (admin-promoted)

### 5. Background Jobs Configuration

- [ ] `LinkHealthCheckScheduler` configured (cron: 0 0 3 ? * SUN - Sunday 3am UTC)
- [ ] `RankRecalculationScheduler` configured (cron: 0 0 * * * ? - Hourly)
- [ ] DelayedJob queue handlers registered:
  - [ ] `SCREENSHOT_CAPTURE` → `ScreenshotCaptureJobHandler`
  - [ ] `LINK_HEALTH_CHECK` → `LinkHealthCheckJobHandler`
- [ ] Job retry policies configured (max 3 retries with exponential backoff)
- [ ] Job monitoring alerts enabled (see [Monitoring](#monitoring))

### 6. External Dependencies

- [ ] **Chromium/Puppeteer** installed in container image
  - [ ] Verify browser startup: `/usr/bin/chromium-browser --version`
  - [ ] Test screenshot capture on beta: `curl -X POST https://beta.example.com/api/test/screenshot-capture`
- [ ] **Cloudflare R2** buckets configured:
  - [ ] `screenshots` bucket exists
  - [ ] CORS policy allows public read access
  - [ ] CDN caching configured (1 day TTL for screenshots)
- [ ] **Elasticsearch** index created:
  - [ ] `directory_sites` index created with text analysis
  - [ ] Mappings verified: `url`, `title`, `description`, `domain` fields
  - [ ] Index health: GREEN (replicas: 1)

### 7. Feature Flags

- [ ] Feature flags configured in database:
  - [ ] `good_sites_enabled` → `true` (master switch)
  - [ ] `good_sites_voting_enabled` → `true` (enable voting)
  - [ ] `good_sites_submissions_enabled` → `true` (enable user submissions)
  - [ ] `good_sites_ai_categorization_enabled` → `false` (disabled until tested)
- [ ] Rollout percentages set:
  - [ ] Start at 10% for first 24 hours
  - [ ] Increase to 50% if no issues
  - [ ] Increase to 100% after 48 hours
- [ ] Whitelist configured for early access testers (optional)

### 8. Monitoring and Alerting

- [ ] Grafana dashboards created:
  - [ ] **Good Sites Overview:** Site submissions, votes, karma changes, category growth
  - [ ] **Screenshot Capture:** Capture duration, semaphore wait time, error rate, browser pool usage
  - [ ] **Link Health:** Sites checked, dead sites detected, recovery rate
  - [ ] **Voting Activity:** Votes per hour, rate limit rejections, karma adjustments
- [ ] Prometheus metrics verified:
  - [ ] `link_health.checks.total{result=success|failed|recovered}`
  - [ ] `screenshot_capture.duration`
  - [ ] `directory_votes.total{type=upvote|downvote}`
  - [ ] `karma_adjustments.total{reason=submission|vote|manual}`
- [ ] Alerts configured:
  - [ ] **P1:** Screenshot capture error rate >10% (5min window)
  - [ ] **P2:** Link health check failure rate >20% (1hr window)
  - [ ] **P2:** Browser pool exhaustion (semaphore wait >60s)
  - [ ] **P3:** Dead sites detected (daily summary)
  - [ ] **P3:** Karma adjustments spike (>500/hr)

### 9. Documentation

- [ ] User-facing documentation published:
  - [ ] How to browse Good Sites
  - [ ] How to vote on sites
  - [ ] How to submit a new site
  - [ ] Karma and trust level explanation
- [ ] Admin documentation published:
  - [ ] Moderation queue workflow
  - [ ] How to manually adjust karma
  - [ ] How to promote users to moderator
  - [ ] How to handle flagged sites
- [ ] Ops documentation updated:
  - [ ] This runbook finalized
  - [ ] [Testing Guide](../testing.md) updated with Good Sites section
  - [ ] [Monitoring Guide](../monitoring.md) updated with new dashboards

---

## Release Procedure

### Step 1: Pre-Release Smoke Test (Beta Environment)

**Execute on:** `homepage-beta.villagecompute.com`

1. **Browse Good Sites Homepage:**
   ```bash
   curl -I https://homepage-beta.villagecompute.com/good-sites
   # Expected: HTTP 200
   ```

2. **Test Category Navigation:**
   - Visit `/good-sites/computers`
   - Verify sites are displayed
   - Verify subcategories are displayed
   - Verify pagination works (if >25 sites)

3. **Test Search:**
   - Search for "programming"
   - Verify results are relevant
   - Verify special characters don't break search (e.g., "C++")

4. **Test Voting (Authenticated User):**
   - Log in as test user
   - Upvote a site
   - Verify vote count increments
   - Change vote to downvote
   - Verify vote count updates correctly
   - Remove vote
   - Verify vote count decrements

5. **Test Rate Limiting:**
   - Cast 50 votes rapidly
   - Verify 51st vote is rejected with 429 status

6. **Test Screenshot Capture:**
   ```bash
   # Submit screenshot capture job
   curl -X POST https://homepage-beta.villagecompute.com/api/admin/directory/sites/SITE_ID/capture-screenshot \
     -H "Authorization: Bearer $ADMIN_TOKEN"

   # Wait 30 seconds, then verify screenshot URL updated
   curl https://homepage-beta.villagecompute.com/api/directory/sites/SITE_ID | jq '.screenshotUrl'
   ```

7. **Test Link Health Check:**
   ```bash
   # Trigger manual health check job
   curl -X POST https://homepage-beta.villagecompute.com/api/admin/jobs/link-health-check \
     -H "Authorization: Bearer $ADMIN_TOKEN"

   # Wait for completion, check logs for dead sites
   kubectl logs -n homepage -l app=homepage-beta --tail=100 | grep "marked dead"
   ```

**Acceptance Criteria:**
- [ ] All smoke tests pass
- [ ] No errors in application logs
- [ ] Response times <500ms (p95)

---

### Step 2: Database Migration (Production)

**Execute on:** Production database

**WARNING:** Take database backup before proceeding!

```bash
# 1. Create backup
pg_dump -h $PROD_DB_HOST -U $PROD_DB_USER homepage_prod > good-sites-pre-migration-backup.sql

# 2. Run migrations
cd migrations
mvn migration:up -Dmigration.env=production

# 3. Verify migrations
psql -h $PROD_DB_HOST -U $PROD_DB_USER homepage_prod -c "\dt directory*"
psql -h $PROD_DB_HOST -U $PROD_DB_USER homepage_prod -c "\d+ users" | grep directory
```

**Expected Output:**
```
                     List of relations
 Schema |            Name              | Type  |  Owner
--------+------------------------------+-------+---------
 public | directory_categories         | table | homepage
 public | directory_screenshot_versions| table | homepage
 public | directory_site_categories    | table | homepage
 public | directory_sites              | table | homepage
 public | directory_votes              | table | homepage
 public | karma_audit                  | table | homepage
```

**Rollback Procedure (if needed):**
```bash
mvn migration:down -Dmigration.env=production -Dmigration.count=6
```

---

### Step 3: Deploy Application (Production)

**Execute on:** Production k3s cluster

1. **Tag Docker Image:**
   ```bash
   docker tag homepage:latest homepage:good-sites-release-v1.5.0
   docker push registry.villagecompute.com/homepage:good-sites-release-v1.5.0
   ```

2. **Update Deployment:**
   ```bash
   kubectl set image deployment/homepage homepage=homepage:good-sites-release-v1.5.0 -n homepage
   ```

3. **Monitor Rollout:**
   ```bash
   kubectl rollout status deployment/homepage -n homepage --timeout=5m
   ```

4. **Verify Health:**
   ```bash
   curl https://homepage.villagecompute.com/q/health/ready
   # Expected: {"status":"UP"}
   ```

**Rollback Procedure (if needed):**
```bash
kubectl rollout undo deployment/homepage -n homepage
```

---

### Step 4: Seed Initial Data (Production)

**Execute on:** Production pod

1. **Seed Root Categories:**
   ```bash
   kubectl exec -it deployment/homepage -n homepage -- \
     java -jar app.jar seed-directory-categories
   ```

2. **Seed Sample Sites (Optional):**
   ```bash
   # Only if starting with sample content
   kubectl exec -it deployment/homepage -n homepage -- \
     java -jar app.jar seed-directory-sites --count=100
   ```

3. **Verify Seed Data:**
   ```bash
   psql -h $PROD_DB_HOST -U $PROD_DB_USER homepage_prod \
     -c "SELECT COUNT(*) FROM directory_categories WHERE parent_id IS NULL;"
   # Expected: 7 (root categories)
   ```

---

### Step 5: Enable Feature Flags (Gradual Rollout)

**Execute via:** Admin UI or database

1. **10% Rollout (First 24 Hours):**
   ```sql
   UPDATE feature_flags
   SET rollout_percentage = 10,
       enabled = true,
       updated_at = NOW()
   WHERE flag_key = 'good_sites_enabled';
   ```

2. **Monitor Metrics (24 Hours):**
   - Check Grafana dashboards for errors
   - Review application logs for exceptions
   - Monitor screenshot capture queue depth
   - Verify no database connection pool exhaustion

3. **50% Rollout (After 24 Hours):**
   ```sql
   UPDATE feature_flags
   SET rollout_percentage = 50,
       updated_at = NOW()
   WHERE flag_key = 'good_sites_enabled';
   ```

4. **100% Rollout (After 48 Hours):**
   ```sql
   UPDATE feature_flags
   SET rollout_percentage = 100,
       updated_at = NOW()
   WHERE flag_key = 'good_sites_enabled';
   ```

---

### Step 6: Post-Release Verification

**Execute within 1 hour of 100% rollout:**

1. **Functional Tests:**
   - [ ] Browse homepage: `https://homepage.villagecompute.com/good-sites`
   - [ ] Navigate categories: `/good-sites/computers`, `/good-sites/news`, etc.
   - [ ] Search for sites: `/good-sites/search?q=programming`
   - [ ] View site detail: `/good-sites/site/{id}`
   - [ ] Cast vote (authenticated user)
   - [ ] Submit new site (authenticated user)

2. **Performance Tests:**
   - [ ] Homepage load time <500ms (p95)
   - [ ] Category page load time <500ms (p95)
   - [ ] Search response time <200ms (p95)
   - [ ] Screenshot capture <5s (p95)

3. **Background Jobs:**
   - [ ] Screenshot capture jobs executing
   - [ ] Link health check scheduled (next run: Sunday 3am UTC)
   - [ ] Rank recalculation running hourly
   - [ ] No jobs stuck in "running" state

4. **Monitoring:**
   - [ ] Grafana dashboards showing data
   - [ ] Prometheus metrics being scraped
   - [ ] Alerts configured and tested (test alert)
   - [ ] No critical alerts firing

---

## Monitoring

### Key Metrics to Watch (First Week)

**Dashboard:** [Good Sites Overview](https://grafana.villagecompute.com/d/good-sites-overview)

| Metric | Target | Alert Threshold |
|--------|--------|----------------|
| Site submissions/day | >10 | <5 (P3) |
| Votes/day | >100 | <50 (P3) |
| Screenshot capture p95 | <5s | >10s (P2) |
| Screenshot capture error rate | <5% | >10% (P1) |
| Link health check error rate | <20% | >30% (P2) |
| Dead sites detected/week | <10 | >50 (P3) |
| Browser pool exhaustion | 0 | >5 occurrences/hr (P2) |
| Karma adjustments/hr | <100 | >500 (P3 - investigate) |
| Rate limit rejections/hr | <50 | >200 (P3 - review limits) |

### Logs to Monitor

**Application Logs:**
```bash
# Watch for errors related to Good Sites
kubectl logs -f deployment/homepage -n homepage | grep -i "directory\|karma\|screenshot\|vote"

# Watch for screenshot capture errors
kubectl logs -f deployment/homepage -n homepage | grep "ScreenshotCaptureException"

# Watch for dead site detections
kubectl logs -f deployment/homepage -n homepage | grep "marked dead"
```

**Job Execution Logs:**
```bash
# Watch delayed job processing
kubectl logs -f deployment/homepage -n homepage | grep "DelayedJobWorker"

# Watch for job failures
kubectl logs -f deployment/homepage -n homepage | grep "Job execution failed"
```

---

## Rollback Procedure

**If critical issues arise, follow this procedure:**

### Level 1: Feature Flag Rollback (Fastest)

```sql
-- Disable Good Sites feature entirely
UPDATE feature_flags
SET enabled = false,
    updated_at = NOW()
WHERE flag_key = 'good_sites_enabled';
```

**Impact:** Good Sites feature hidden from users within seconds. No data loss.

### Level 2: Application Rollback

```bash
# Rollback to previous deployment
kubectl rollout undo deployment/homepage -n homepage

# Verify rollback complete
kubectl rollout status deployment/homepage -n homepage
```

**Impact:** Previous version restored within 5 minutes. Good Sites data remains in database.

### Level 3: Database Rollback (Last Resort)

**WARNING:** Only if database corruption detected. Data loss will occur.

```bash
# Restore from backup
psql -h $PROD_DB_HOST -U $PROD_DB_USER homepage_prod < good-sites-pre-migration-backup.sql

# Run down migrations
cd migrations
mvn migration:down -Dmigration.env=production -Dmigration.count=6
```

**Impact:** All Good Sites data created since migration will be lost. Users will see pre-release state.

---

## Known Issues and Workarounds

### Issue 1: Screenshot Capture Timeouts

**Symptom:** Screenshot capture jobs timing out after 30 seconds for slow-loading sites.

**Workaround:**
- Monitor timeout errors in Grafana
- If >10% timeout rate, increase timeout to 60s:
  ```java
  // ScreenshotCaptureJobHandler.java
  private static final int TIMEOUT_SECONDS = 60; // Increase from 30
  ```
- Redeploy application

**Permanent Fix:** Implement tiered timeout strategy (fast-fail for known-slow domains).

### Issue 2: Browser Pool Exhaustion

**Symptom:** Semaphore wait time >60s during high traffic.

**Workaround:**
- Increase semaphore limit in Policy P12:
  ```java
  // ScreenshotService.java
  private static final int MAX_CONCURRENT_BROWSERS = 5; // Increase from 3
  ```
- Monitor memory usage (each browser consumes ~200MB)

**Permanent Fix:** Implement external screenshot service with dedicated resources.

### Issue 3: Dead Link False Positives

**Symptom:** Popular sites incorrectly marked as dead due to rate limiting.

**Workaround:**
- Manually unmark dead sites:
  ```sql
  UPDATE directory_sites
  SET is_dead = false,
      status = 'approved',
      health_check_failures = 0
  WHERE id = 'SITE_ID';
  ```
- Add site to whitelist in LinkHealthCheckJobHandler (excludes from checks)

**Permanent Fix:** Implement User-Agent rotation and respect `robots.txt` rate limits.

### Issue 4: Karma Gaming/Abuse

**Symptom:** Users creating multiple accounts to vote on their own submissions.

**Workaround:**
- Manually adjust karma:
  ```sql
  -- Via KarmaService.adminAdjustKarma()
  POST /api/admin/karma/users/{userId}/adjust
  {
    "delta": -50,
    "reason": "Karma abuse detected - multiple account voting"
  }
  ```
- Demote trust level:
  ```sql
  POST /api/admin/karma/users/{userId}/trust-level
  {
    "trustLevel": "untrusted",
    "reason": "Voting pattern indicates abuse"
  }
  ```

**Permanent Fix:** Implement IP-based duplicate detection and voting pattern analysis.

---

## Verification Scripts

### Post-Migration Data Validation

```sql
-- 1. Verify root categories exist
SELECT * FROM directory_categories WHERE parent_id IS NULL;
-- Expected: 7 rows (Arts, Business, Computers, News, Recreation, Science, Society)

-- 2. Verify users have directory fields
SELECT COUNT(*) FROM users WHERE directory_karma IS NOT NULL;
-- Expected: All users have default karma=0, trust_level='untrusted'

-- 3. Verify indexes exist
SELECT indexname FROM pg_indexes WHERE tablename LIKE 'directory%';
-- Expected: At least 10 indexes (see migration files)

-- 4. Verify screenshot versions table
SELECT COUNT(*) FROM directory_screenshot_versions;
-- Expected: 0 (no screenshots captured yet)

-- 5. Verify karma audit table
SELECT COUNT(*) FROM karma_audit;
-- Expected: 0 (no karma adjustments yet)
```

### Post-Release Functional Validation

```bash
# 1. Verify Good Sites homepage loads
curl -sI https://homepage.villagecompute.com/good-sites | head -1
# Expected: HTTP/2 200

# 2. Verify category navigation
curl -s https://homepage.villagecompute.com/good-sites/computers | grep -c "Computers"
# Expected: >0 (category name appears)

# 3. Verify search works
curl -s "https://homepage.villagecompute.com/good-sites/search?q=test" | grep -c "search-result"
# Expected: ≥0 (may be 0 if no results)

# 4. Verify voting API (authenticated)
curl -X POST https://homepage.villagecompute.com/good-sites/api/vote \
  -H "Cookie: quarkus-session-id=$SESSION_ID" \
  -H "Content-Type: application/json" \
  -d '{"siteCategoryId":"SITE_CATEGORY_ID","vote":1}'
# Expected: HTTP 200 or 201

# 5. Verify rate limiting
for i in {1..51}; do
  curl -X POST https://homepage.villagecompute.com/good-sites/api/vote \
    -H "Cookie: quarkus-session-id=$SESSION_ID" \
    -H "Content-Type: application/json" \
    -d "{\"siteCategoryId\":\"$SITE_CATEGORY_ID\",\"vote\":1}" \
    -w "%{http_code}\n" -o /dev/null -s
done | tail -1
# Expected: 429 (Too Many Requests)
```

---

## Success Criteria

Release is considered successful when ALL of the following are met:

- [ ] All smoke tests pass on production
- [ ] No P1 or P2 alerts fired in first 24 hours
- [ ] User submissions >10/day after 48 hours
- [ ] Votes >100/day after 48 hours
- [ ] Screenshot capture error rate <5%
- [ ] Link health check error rate <20%
- [ ] No database connection pool exhaustion
- [ ] No browser pool leaks detected
- [ ] User feedback positive (0 critical bugs reported)
- [ ] Feature flag rollout at 100%
- [ ] All documentation published

---

## Contact Information

**Escalation Path:**
1. **On-Call Engineer:** `ops-oncall@villagecompute.com` (PagerDuty)
2. **Platform Team Lead:** `platform-lead@villagecompute.com`
3. **Engineering Manager:** `eng-manager@villagecompute.com`

**Slack Channels:**
- `#homepage-incidents` - Critical issues
- `#homepage-releases` - Release coordination
- `#homepage-monitoring` - Metrics and alerts

---

**Runbook Version:** 1.0
**Last Updated:** 2026-01-10
**Task:** I5.T9
**Next Review Date:** 2026-02-10 (30 days post-release)
