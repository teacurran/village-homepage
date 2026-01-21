# Release Checklist - Village Homepage I6 Release

**Release Version:** I6 - Public Profiles, GDPR, Analytics
**Target Date:** TBD (pending blocker resolution)
**Current Status:** 🛑 **BLOCKED** - See TEST_STATUS_I6T9.md
**Runbook Reference:** [Good Sites Release Runbook](runbooks/good-sites-release.md)

---

## Pre-Release Verification

### 1. Code Quality & Testing

#### 1.1 Code Formatting & Linting

- [ ] **Spotless formatting passes:** `./mvnw spotless:check`
  - Status: ✅ Passing (after spotless:apply)
  - Verified by: _________________ Date: _______

- [ ] **ESLint passes:** `npm run lint`
  - Status: ❌ FAIL - 11 errors in React components
  - Blocker: Must fix type safety issues in AnalyticsDashboard, GridstackEditor, ProfileBuilder
  - Verified by: _________________ Date: _______

- [ ] **TypeScript type check passes:** `npm run typecheck`
  - Status: ❌ FAIL - 8 type errors
  - Blocker: Must fix mounts.tsx props mismatch (critical runtime issue)
  - Verified by: _________________ Date: _______

- [ ] **OpenAPI spec validation:** `npm run openapi:validate`
  - Status: ⚠️ Not tested
  - Verified by: _________________ Date: _______

#### 1.2 Test Suite Execution

- [ ] **Unit tests pass:** `./mvnw test`
  - Status: ❌ BLOCKED - Qute template syntax errors
  - Required: Fix yourTimes.html lines 150, 182
  - Target: ≥78 tests passing
  - Verified by: _________________ Date: _______

- [ ] **Integration tests pass:** `./mvnw verify -DskipITs=false`
  - Status: ❌ BLOCKED - Template errors prevent execution
  - Target: All integration tests passing
  - Verified by: _________________ Date: _______

- [ ] **E2E tests pass:** `npm run test:e2e`
  - Status: ❌ NOT RUN - Backend must be functional first
  - Required tests:
    - [ ] `tests/e2e/marketplace.spec.ts`
    - [ ] `tests/e2e/good-sites.spec.ts`
  - Known skipped tests: voting (VoteButtons component), site submission (auth setup)
  - Verified by: _________________ Date: _______

#### 1.3 Code Coverage

- [ ] **Coverage report generated:** `./mvnw verify jacoco:report`
  - Status: ❌ NOT GENERATED - Tests blocked
  - Verified by: _________________ Date: _______

- [ ] **Coverage targets met:**
  - [ ] Overall ≥80% line coverage
  - [ ] Overall ≥80% branch coverage
  - [ ] Profiles module ≥80% (I6)
  - [ ] GDPR module ≥80% (I6)
  - [ ] Analytics module ≥80% (I6)
  - Report location: `target/site/jacoco/index.html`
  - Verified by: _________________ Date: _______

#### 1.4 Performance Testing

- [ ] **Marketplace search load test:** `k6 run tests/load/marketplace-search.js`
  - [ ] p95 latency < 200ms
  - [ ] p99 latency < 500ms
  - [ ] Error rate < 1%
  - Status: ❌ NOT RUN
  - Verified by: _________________ Date: _______

- [ ] **Screenshot queue load test:** `k6 run tests/load/screenshot-queue.js`
  - [ ] p95 capture duration < 5000ms
  - [ ] p99 capture duration < 10000ms
  - [ ] Semaphore wait time < 30000ms
  - [ ] Error rate < 5%
  - Status: ❌ NOT RUN
  - Verified by: _________________ Date: _______

#### 1.5 Security

- [ ] **npm security audit:** `npm audit`
  - Current status: 17 vulnerabilities (1 moderate, 16 high)
  - Action: `npm audit fix` (review breaking changes first)
  - Verified by: _________________ Date: _______

- [ ] **Dependency scan (Snyk/Dependabot):** Review GitHub security alerts
  - Verified by: _________________ Date: _______

- [ ] **OWASP Top 10 review:** SQL injection, XSS, CSRF protections verified
  - Reference: Security review in code comments
  - Verified by: _________________ Date: _______

### 2. Database & Migrations

#### 2.1 Migration Verification

- [ ] **Development migrations applied:** `cd migrations && mvn migration:status -Dmigration.env=development`
  - All pending migrations applied successfully
  - Verified by: _________________ Date: _______

- [ ] **Beta migrations dry-run:** `mvn migration:up -Dmigration.env=beta --dry-run`
  - No conflicts or errors reported
  - Verified by: _________________ Date: _______

- [ ] **Production migrations dry-run:** `mvn migration:up -Dmigration.env=prod --dry-run`
  - No conflicts or errors reported
  - Verified by: _________________ Date: _______

- [ ] **Rollback scripts tested:** Verify each migration has down script
  - Location: `migrations/src/main/resources/migrations/`
  - Verified by: _________________ Date: _______

#### 2.2 Database State

- [ ] **Backup procedures tested:** Restore from backup successful (within past 30 days)
  - Last backup test: _________________
  - Verified by: _________________ Date: _______

- [ ] **PostGIS extensions verified:** Required extensions installed (10.50.0.10)
  - `postgis`, `postgis_topology`
  - Verified by: _________________ Date: _______

### 3. Infrastructure

#### 3.1 K3s Cluster

- [ ] **Cluster health:** All nodes ready
  - Command: `kubectl get nodes`
  - IP: 10.50.0.20
  - Verified by: _________________ Date: _______

- [ ] **Namespace ready:** `village-homepage-prod` namespace exists with correct RBAC
  - Command: `kubectl get namespace village-homepage-prod`
  - Verified by: _________________ Date: _______

- [ ] **ConfigMaps updated:**
  - [ ] `db-config-prod` (PostgreSQL connection)
  - [ ] `clickhouse-config-prod` (Analytics database)
  - [ ] `app-config-prod` (Application properties)
  - Verified by: _________________ Date: _______

- [ ] **Secrets updated:**
  - [ ] `db-secret-prod` (Database credentials)
  - [ ] `clickhouse-secret-prod` (ClickHouse credentials)
  - [ ] `cloudflare-secret` (R2 access keys)
  - [ ] `stripe-secret` (API keys for payments)
  - [ ] `oauth-secrets` (Google, Facebook, Apple OAuth)
  - Verified by: _________________ Date: _______

#### 3.2 External Services

- [ ] **PostgreSQL available:** 10.50.0.10 accepting connections
  - Command: `psql -h 10.50.0.10 -U homepage_user -d homepage_prod -c 'SELECT 1'`
  - Verified by: _________________ Date: _______

- [ ] **ClickHouse available:** 10.50.0.11:8123 responding
  - Command: `curl http://10.50.0.11:8123/ping`
  - Database `homepage_prod_analytics` exists
  - Verified by: _________________ Date: _______

- [ ] **Cloudflare R2 accessible:**
  - [ ] `homepage-screenshots` bucket exists
  - [ ] `homepage-listings` bucket exists
  - Test upload/download successful
  - Verified by: _________________ Date: _______

- [ ] **Elasticsearch cluster ready:** Index mappings updated
  - Listings index: `homepage-listings-prod`
  - Sites index: `homepage-sites-prod`
  - Verified by: _________________ Date: _______

- [ ] **Keycloak OAuth configured:**
  - Realm: `villagecompute`
  - Client: `village-homepage`
  - Redirect URIs updated for production domain
  - Verified by: _________________ Date: _______

#### 3.3 Observability

- [ ] **Prometheus scraping configured:** Targets for homepage pods
  - Dashboard: observability.villagecompute.com/prometheus
  - Verified by: _________________ Date: _______

- [ ] **Loki log aggregation:** Logs flowing from homepage pods
  - Dashboard: observability.villagecompute.com/grafana
  - Query: `{app="village-homepage", namespace="village-homepage-prod"}`
  - Verified by: _________________ Date: _______

- [ ] **Jaeger tracing:** OTLP endpoint configured
  - Endpoint: `http://jaeger-collector.observability.svc:4317`
  - Verified by: _________________ Date: _______

- [ ] **Grafana dashboards deployed:**
  - [ ] Village Homepage - Overview
  - [ ] Village Homepage - Analytics Metrics
  - [ ] Village Homepage - GDPR Operations
  - [ ] Village Homepage - Delayed Jobs
  - Verified by: _________________ Date: _______

### 4. Application Configuration

#### 4.1 Feature Flags

- [ ] **Feature flags initialized:**
  - [ ] `stocks_widget`: enabled=false, rollout=0%
  - [ ] `social_integration`: enabled=false, rollout=0%
  - [ ] `marketplace`: enabled=false, rollout=0%
  - [ ] `good_sites`: enabled=false, rollout=0%
  - [ ] `profiles`: enabled=false, rollout=0%
  - Command: `SELECT * FROM feature_flags;`
  - Verified by: _________________ Date: _______

- [ ] **Analytics consent configured:** Default to opt-out (GDPR compliance)
  - Policy P14 reference
  - Verified by: _________________ Date: _______

#### 4.2 Rate Limits

- [ ] **Rate limit tiers configured:**
  - Anonymous: 10 req/min
  - Logged In: 60 req/min
  - Premium: 300 req/min
  - Admin: Unlimited
  - Verified by: _________________ Date: _______

#### 4.3 AI Budget

- [ ] **AI tagging budget configured:**
  - Monthly budget: $50/month
  - Alert threshold: 75%
  - Cutoff threshold: 90%
  - Email notifications: ops@villagecompute.com
  - Verified by: _________________ Date: _______

#### 4.4 Email

- [ ] **SMTP configured:** Outbound email working
  - Test email sent successfully
  - Verified by: _________________ Date: _______

- [ ] **IMAP configured:** Inbound email relay working
  - Mailbox: homepage-replies@villagecompute.com
  - Polling interval: 1 minute
  - Verified by: _________________ Date: _______

- [ ] **Email templates reviewed:**
  - [ ] Profile published notification
  - [ ] Profile unpublished notification
  - [ ] AI budget alert (75%, 90%, 100%)
  - [ ] GDPR export ready
  - [ ] GDPR deletion complete
  - Location: `src/main/resources/templates/email/`
  - Verified by: _________________ Date: _______

### 5. Build & Deployment

#### 5.1 Container Image

- [ ] **Image built:** `./mvnw package jib:dockerBuild -DskipTests`
  - Status: ⚠️ NOT ATTEMPTED - Tests blocked
  - Image name: `village-homepage:1.0.0-SNAPSHOT`
  - Verified by: _________________ Date: _______

- [ ] **Image pushed to registry:**
  - Registry: Container registry details
  - Tag: `village-homepage:i6-release-YYYYMMDD-HHMM`
  - Verified by: _________________ Date: _______

- [ ] **Image security scan:** Trivy/Clair scan clean
  - No critical/high vulnerabilities
  - Verified by: _________________ Date: _______

#### 5.2 Kubernetes Manifests

- [ ] **Deployment manifest updated:**
  - Image tag references new release
  - Resource limits appropriate (CPU/memory)
  - Liveness/readiness probes configured
  - File: `k8s/prod/deployment.yaml`
  - Verified by: _________________ Date: _______

- [ ] **Service manifest verified:**
  - ClusterIP service for internal access
  - File: `k8s/prod/service.yaml`
  - Verified by: _________________ Date: _______

- [ ] **Ingress manifest verified:**
  - Host: homepage.villagecompute.com
  - TLS enabled (Cloudflare Tunnel)
  - File: `k8s/prod/ingress.yaml`
  - Verified by: _________________ Date: _______

### 6. Documentation

- [ ] **VERIFICATION_STRATEGY.md complete:** Testing approach documented
  - File: `docs/VERIFICATION_STRATEGY.md`
  - Verified by: _________________ Date: _______

- [ ] **GLOSSARY.md complete:** Technical terms defined
  - File: `docs/GLOSSARY.md`
  - Verified by: _________________ Date: _______

- [ ] **API documentation updated:** OpenAPI spec current
  - File: `src/main/resources/META-INF/openapi.yaml`
  - Verified by: _________________ Date: _______

- [ ] **Runbooks available:**
  - [ ] Good Sites release runbook
  - [ ] GDPR operations runbook
  - [ ] Analytics troubleshooting runbook
  - Location: `docs/ops/runbooks/`
  - Verified by: _________________ Date: _______

- [ ] **RETROSPECTIVE_I6.md complete:** Lessons learned documented
  - File: `docs/RETROSPECTIVE_I6.md`
  - Verified by: _________________ Date: _______

---

## Release Execution

### Phase 1: Deploy to Beta

#### 1.1 Database Migration

- [ ] **Apply migrations to beta database:**
  - Command: `cd migrations && mvn migration:up -Dmigration.env=beta`
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Verify schema changes:** Tables/columns exist
  - `profiles`, `curated_content`, `gdpr_requests`, `analytics_events`
  - Verified by: _________________ Date: _______ Time: _______

#### 1.2 Application Deployment

- [ ] **Deploy to beta namespace:**
  - Command: `kubectl apply -f k8s/beta/ --namespace=village-homepage-beta`
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Pods running:** `kubectl get pods -n village-homepage-beta`
  - All pods in Running state
  - No CrashLoopBackOff errors
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Logs clean:** `kubectl logs -n village-homepage-beta -l app=village-homepage --tail=100`
  - No WARN or ERROR messages
  - Application started successfully
  - Verified by: _________________ Date: _______ Time: _______

#### 1.3 Beta Smoke Tests

- [ ] **Health endpoint responding:** `curl https://homepage-beta.villagecompute.com/q/health`
  - Status: UP
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Homepage loads:** Visit https://homepage-beta.villagecompute.com
  - Page renders successfully
  - No JavaScript console errors
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **OAuth login works:**
  - Test login with Google
  - User redirected back successfully
  - Session created
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Profile creation works:**
  - Create test profile with "Your Times" template
  - Articles display correctly
  - Template rendering successful
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Good Sites browsing works:**
  - Navigate to Good Sites directory
  - Category pages load
  - Site detail pages render
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Marketplace browsing works:**
  - Browse listings
  - Search functionality works
  - Location filtering works
  - Verified by: _________________ Date: _______ Time: _______

#### 1.4 Beta Soak Period

- [ ] **Monitor for 48 hours:**
  - Start: _________________ Time: _______
  - End: _________________ Time: _______

- [ ] **Error rate < 1%:** Check Grafana dashboard
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **p95 latency < 500ms:** Check Prometheus metrics
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **No memory leaks:** Pod memory usage stable
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **No database connection exhaustion:** Connection pool healthy
  - Verified by: _________________ Date: _______ Time: _______

### Phase 2: Production Deployment

#### 2.1 Final Pre-Flight Checks

- [ ] **All beta smoke tests passing:** Reference Phase 1.3 results
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Stakeholder approval:**
  - [ ] Tech Lead sign-off
  - [ ] Product Lead sign-off
  - [ ] Ops Lead sign-off
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **On-call rotation confirmed:**
  - Primary: _________________
  - Secondary: _________________
  - Escalation: _________________
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Rollback plan reviewed:** All team members aware of rollback procedure
  - Verified by: _________________ Date: _______ Time: _______

#### 2.2 Backup Production

- [ ] **Database backup taken:**
  - Command: `pg_dump -h 10.50.0.10 -U homepage_user homepage_prod > backup-pre-i6-$(date +%Y%m%d-%H%M%S).sql`
  - Backup location: _________________
  - Backup size: _________________
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Backup verified:** Test restore to dev database successful
  - Verified by: _________________ Date: _______ Time: _______

#### 2.3 Database Migration

- [ ] **Apply migrations to production database:**
  - Command: `cd migrations && mvn migration:up -Dmigration.env=prod`
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Verify schema changes:** Tables/columns exist
  - `profiles`, `curated_content`, `gdpr_requests`, `analytics_events`
  - Verified by: _________________ Date: _______ Time: _______

#### 2.4 Application Deployment

- [ ] **Deploy to production namespace:**
  - Command: `kubectl apply -f k8s/prod/ --namespace=village-homepage-prod`
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Pods running:** `kubectl get pods -n village-homepage-prod`
  - All pods in Running state
  - No CrashLoopBackOff errors
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Logs clean:** `kubectl logs -n village-homepage-prod -l app=village-homepage --tail=100`
  - No WARN or ERROR messages
  - Application started successfully
  - Verified by: _________________ Date: _______ Time: _______

#### 2.5 Production Smoke Tests

- [ ] **Health endpoint responding:** `curl https://homepage.villagecompute.com/q/health`
  - Status: UP
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Homepage loads:** Visit https://homepage.villagecompute.com
  - Page renders successfully
  - No JavaScript console errors
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **OAuth login works:**
  - Test login with Google
  - User redirected back successfully
  - Session created
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Profile creation works:**
  - Create test profile with "Your Times" template
  - Articles display correctly
  - Template rendering successful
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Good Sites browsing works:**
  - Navigate to Good Sites directory
  - Category pages load
  - Site detail pages render
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Marketplace browsing works:**
  - Browse listings
  - Search functionality works
  - Location filtering works
  - Verified by: _________________ Date: _______ Time: _______

### Phase 3: Feature Flag Rollout

**Reference:** [Rollout Playbook](../VERIFICATION_STRATEGY.md#rollout-playbook)

#### 3.1 Good Sites Directory (Module I5)

- [ ] **Enable feature flag:**
  - Flag: `good_sites`
  - Rollout: 0% → 5%
  - Command: `UPDATE feature_flags SET enabled=true, rollout_percentage=5 WHERE flag_key='good_sites';`
  - Executed by: _________________ Date: _______ Time: _______

- [ ] **Monitor for 24 hours:**
  - Error rate < 1%
  - p95 latency < 500ms
  - No critical errors in logs
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Increase to 10%:**
  - Executed by: _________________ Date: _______ Time: _______
  - Monitored for 24 hours: _______

- [ ] **Increase to 25%:**
  - Executed by: _________________ Date: _______ Time: _______
  - Monitored for 24 hours: _______

- [ ] **Increase to 50%:**
  - Executed by: _________________ Date: _______ Time: _______
  - Monitored for 48 hours: _______

- [ ] **Increase to 100%:**
  - Executed by: _________________ Date: _______ Time: _______
  - Monitored for 48 hours: _______

#### 3.2 Public Profiles (Module I6)

- [ ] **Enable feature flag:**
  - Flag: `profiles`
  - Rollout: 0% → 5%
  - Command: `UPDATE feature_flags SET enabled=true, rollout_percentage=5 WHERE flag_key='profiles';`
  - Executed by: _________________ Date: _______ Time: _______

- [ ] **Monitor for 24 hours:**
  - Template rendering successful
  - No Qute errors
  - Article curation working
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Increase to 10%:**
  - Executed by: _________________ Date: _______ Time: _______
  - Monitored for 24 hours: _______

- [ ] **Increase to 25%:**
  - Executed by: _________________ Date: _______ Time: _______
  - Monitored for 24 hours: _______

- [ ] **Increase to 50%:**
  - Executed by: _________________ Date: _______ Time: _______
  - Monitored for 48 hours: _______

- [ ] **Increase to 100%:**
  - Executed by: _________________ Date: _______ Time: _______
  - Monitored for 48 hours: _______

#### 3.3 Other Modules

Repeat rollout process for:
- [ ] `stocks_widget` (Module I3)
- [ ] `social_integration` (Module I3)
- [ ] `marketplace` (Module I4)

---

## Post-Release Monitoring

### 24-Hour Watch (Critical Period)

- [ ] **Error rate < 1%:** Continuous monitoring
  - Hour 1: _______ Status: _______
  - Hour 6: _______ Status: _______
  - Hour 12: _______ Status: _______
  - Hour 24: _______ Status: _______

- [ ] **p95 latency < 500ms:** Continuous monitoring
  - Hour 1: _______ Latency: _______
  - Hour 6: _______ Latency: _______
  - Hour 12: _______ Latency: _______
  - Hour 24: _______ Latency: _______

- [ ] **No critical errors:** Log monitoring
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Database connections stable:** Connection pool health
  - Verified by: _________________ Date: _______ Time: _______

- [ ] **Memory usage stable:** No leaks detected
  - Verified by: _________________ Date: _______ Time: _______

### 7-Day Tracking

- [ ] **KPI dashboards updated daily:**
  - User registrations
  - Profile creations
  - GDPR requests
  - Analytics opt-in rate
  - AI tagging budget usage
  - Dashboard: observability.villagecompute.com/grafana

- [ ] **User feedback collected:**
  - Support tickets reviewed
  - User reports triaged
  - Bugs logged in GitHub

---

## Rollback Procedure

**Trigger Conditions:**
- Error rate > 5% sustained for 10+ minutes
- Critical security vulnerability discovered
- Data corruption detected
- Stakeholder directive

### Rollback Steps

1. **Disable feature flags immediately:**
   ```sql
   UPDATE feature_flags SET enabled=false WHERE flag_key IN (
     'good_sites', 'profiles', 'marketplace', 'stocks_widget', 'social_integration'
   );
   ```

2. **Revert Kubernetes deployment:**
   ```bash
   kubectl rollout undo deployment/village-homepage -n village-homepage-prod
   ```

3. **Verify rollback successful:**
   - Check pods running: `kubectl get pods -n village-homepage-prod`
   - Check logs: `kubectl logs -n village-homepage-prod -l app=village-homepage`
   - Test homepage loads
   - Test OAuth login

4. **Rollback database (if needed):**
   ```bash
   cd migrations
   mvn migration:down -Dmigration.env=prod
   ```
   *Caution: Only if migrations cause data issues. May result in data loss.*

5. **Communicate status:**
   - Post incident in Slack #incidents
   - Update status page
   - Notify stakeholders

6. **Post-mortem:**
   - Document root cause
   - Identify fixes needed
   - Schedule remediation

---

## Sign-Off

### Pre-Release Approval

| Role | Name | Signature | Date |
|------|------|-----------|------|
| Tech Lead | _________________ | _________________ | _______ |
| Ops Lead | _________________ | _________________ | _______ |
| Product Lead | _________________ | _________________ | _______ |
| QA Lead | _________________ | _________________ | _______ |

### Post-Release Confirmation

| Role | Name | Signature | Date |
|------|------|-----------|------|
| Release Manager | _________________ | _________________ | _______ |
| On-Call Engineer | _________________ | _________________ | _______ |

---

## Notes & Observations

**Deployment Notes:**

_______________________________________________________________

_______________________________________________________________

_______________________________________________________________

**Issues Encountered:**

_______________________________________________________________

_______________________________________________________________

_______________________________________________________________

**Lessons Learned:**

_______________________________________________________________

_______________________________________________________________

_______________________________________________________________

---

**Checklist Version:** 1.0
**Created:** 2026-01-21
**Last Updated:** 2026-01-21
**Related Documents:**
- [Regression Report](../TEST_STATUS_I6T9.md)
- [Verification Strategy](../VERIFICATION_STRATEGY.md)
- [Retrospective](../RETROSPECTIVE_I6.md)
- [Good Sites Runbook](runbooks/good-sites-release.md)
