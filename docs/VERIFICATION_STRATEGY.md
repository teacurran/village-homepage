<!-- anchor: verification-and-integration-strategy -->
# Verification & Integration Strategy

This document provides a comprehensive overview of the testing, quality assurance, CI/CD, and release management processes for Village Homepage. It serves as a strategic guide that ties together detailed operational documentation across the codebase.

**Audience:** Engineering leads, QA engineers, DevOps, autonomous agents

**Related Documentation:**
- [Testing Guide](ops/testing.md) - Detailed testing patterns and execution instructions
- [CI/CD Pipeline](ops/ci-cd-pipeline.md) - Pipeline configuration and troubleshooting
- [Observability](ops/observability.md) - Logging, metrics, and tracing
- [Runbooks](ops/runbooks/) - Operational procedures
- [Glossary](GLOSSARY.md) - Technical terminology reference

---

<!-- anchor: testing-levels-ownership -->
## 1. Testing Levels & Ownership

Village Homepage employs a comprehensive testing pyramid covering unit, integration, end-to-end, and performance testing. Coverage targets align with quality gates (≥80% line/branch coverage) and are enforced via CI (I1.T7).

### Testing Pyramid

```
         /\
        /  \  E2E Tests (Playwright)
       /    \  - Homepage personalization
      /------\  - Marketplace lifecycle
     /        \ - Good Sites submissions
    /  Integration Tests (@QuarkusTest)
   /            - REST API endpoints
  /              - Database interactions
 /----------------\ - External service mocks
/                  \
/   Unit Tests      \
/   (JUnit 5)        \
/____________________\
```

### Module Ownership by Iteration

| Module | Iteration | Owner Focus |
|--------|-----------|-------------|
| Auth & Preferences | I2 | OAuth flows, anonymous merge, user preferences, feature flags |
| Content & AI | I3 | RSS feeds, AI tagging, widget endpoints, click tracking |
| Marketplace | I4 | Listings, payments, promotions, moderation, email relay |
| Good Sites | I5 | Directory submissions, voting, screenshots, karma system |
| Public Profiles | I6 | Templates, curation, analytics, GDPR export/deletion |

### Testing Standards

- **Unit Tests:** Mandatory for services, integrations, DTO serializers. Use JUnit 5 + Mockito + AssertJ.
- **Integration Tests:** Use `@QuarkusTest` + RestAssured for HTTP testing, Testcontainers for Postgres/PostGIS, Elasticsearch, Stripe mocks, Mailpit, IMAP.
- **E2E Tests:** Playwright suites per iteration, executed in CI nightly + pre-release (I4.T9, I5.T9, I6.T9).
- **Performance Tests:** Focus on PostGIS/Elasticsearch queries, screenshot queue throughput, AI tagging budget transitions, gridstack interactions using k6 or JMeter.

**For detailed testing patterns, setup instructions, and troubleshooting, see [Testing Guide](ops/testing.md).**

---

<!-- anchor: ci-cd-workflow -->
## 2. CI/CD Workflow

The CI/CD pipeline is implemented via GitHub Actions (`.github/workflows/build.yml`) and documented in detail in [CI/CD Pipeline](ops/ci-cd-pipeline.md).

### Pipeline Stages

1. **Checkout & Setup** - Clone repo with full history (for Sonar), set up JDK 21 + Node.js 20.10.0
2. **Plan Compliance Verification** - Run `.codemachine/scripts/extract_tasks.js` and diff artifacts against HEAD (Section 4 compliance)
3. **Install Dependencies** - Run `tools/install.cjs` to resolve Maven + npm dependencies
4. **Code Formatting Check** - Validate Java/XML/YAML/Markdown with Spotless (Eclipse formatter)
5. **Frontend Linting** - ESLint for TypeScript/React code
6. **TypeScript Type Checking** - Strict type validation across React islands
7. **Frontend Build** - Compile TypeScript/React with esbuild
8. **Unit & Integration Tests** - Maven Surefire (unit) + Failsafe (integration) with JaCoCo coverage
9. **E2E Smoke Tests** - Playwright tests (main branch only)
10. **Coverage Reporting** - Upload to Sonar + generate reports
11. **Security Scanning** - SonarCloud + OWASP dependency check
12. **Container Image Build** - Jib image build and publish to registry

### Deployment Strategy

- **Beta Deploy:** Automatic on main branch merges, feature flags gate risky modules
- **Production Deploy:** Manual approval with release checklist (I6.T9)
- **Rolling Updates:** maxUnavailable=1 ensures minimal disruption
- **Feature Flags:** Act as safety net for progressive enablement
- **Smoke Tests:** Verify key flows post-deployment (anonymous homepage, login, marketplace, Good Sites, click tracking)

### Pipeline Caching

Maven + npm artifacts are cached to accelerate builds. Secrets for Stripe/Meta APIs use GitHub Encrypted Secrets referencing Kubernetes sealed secrets (Policy P8, P3 compliance).

**For detailed pipeline configuration, caching strategies, and troubleshooting, see [CI/CD Pipeline](ops/ci-cd-pipeline.md).**

---

<!-- anchor: quality-gates-code-review -->
## 3. Quality Gates & Code Review

Quality gates enforce code standards, security, and maintainability before merging.

### Automated Quality Gates

1. **Spotless Formatting** - Zero tolerance for formatting violations (Eclipse formatter)
2. **ESLint** - Zero warnings/errors for TypeScript/React code
3. **TypeScript Type Check** - Strict type safety enforcement
4. **JaCoCo Coverage** - ≥80% line and branch coverage (post-merge reporting)
5. **SonarCloud Quality Gate:**
   - 0 blocker issues
   - 0 critical security vulnerabilities
   - Maintainability rating A
   - Code smells < 50
6. **OWASP Dependency Check** - Flag known vulnerabilities

### Code Review Requirements

Pull requests MUST:
- **Cite task ID** - Reference iteration.task format (e.g., I3.T9)
- **Reference policies** - Cite relevant policies from architecture (e.g., P1, P8)
- **Include tests** - New features require corresponding test coverage
- **Document migrations** - Database changes need rollback documentation
- **Feature flag audit** - Flag changes require audit entry + screenshot of analytics impact

### Migration Standards

Migrations (MyBatis) require:
- Rollback documentation in migration file header
- Task ID citation in comments
- Dry-run verification in staging before production
- Checksum logging for geo imports and seed data

**For detailed quality gate thresholds, see [CI/CD Pipeline](ops/ci-cd-pipeline.md#quality-gates-and-policies).**

---

<!-- anchor: integration-validation -->
## 4. Integration Validation

External service integrations are validated using sandbox environments, mocks, and automated tests.

### OAuth/OIDC (I2.T1, I2.T4)

- **Test Strategy:** Use sandbox providers (Google, Facebook, Apple testmode)
- **Mock Failure States:** Rate limiting, consent flows, token expiration
- **Validation:** Anonymous-to-auth merge flows with consent logging
- **Audit:** AccountMergeAudit table captures merge events per Policy P1, P9

### LangChain4j AI Integration (I3.T3, I5.T6)

- **Test Strategy:** Use sandbox AI provider keys for tests
- **Budget Enforcement:** Record fake usage data to validate AiTaggingBudgetService
- **Budget Actions:** Verify NORMAL/REDUCE/QUEUE/HARD_STOP responses
- **Policy Compliance:** Enforce P2 (AI Budget Governance) and P10 (AI Cost Controls)
- **Validation:** AI tagging for RSS feeds (I3), Good Sites categorization (I5)

### Stripe Payments (I4.T4)

- **Test Strategy:** Hit testmode endpoints only
- **Scenarios:** Simulate payment_intent succeeded/failed/refunded
- **Webhook Validation:** Ensure signature validation works correctly
- **Refund Flows:** Test PromotionExpirationJobHandler coordination with refunds
- **Policy Compliance:** P11 (Payment Processing), P3 (PCI compliance)

### Meta Graph API (I3.T6)

- **Test Strategy:** Use test pages for token refresh
- **Staleness Detection:** Verify color-coded UI states (green/yellow/red)
- **Token Refresh:** Test refresh flows before expiration
- **Rate Limiting:** Validate rate limit headers and backoff

### Cloudflare R2 Storage (I1.T6, I3.T7, I4.T6, I5.T3)

- **Local Dev:** Use MinIO for development (I1.T6)
- **Integration Tests:** Ensure signed URLs + WebP conversions work
- **Buckets:** Screenshots, listings, profile assets
- **Policy Compliance:** P4 (Screenshot Lifecycle), P12 (R2 Storage)
- **Health Checks:** Verify upload, retrieval, deletion via StorageGateway

---

<!-- anchor: artifact-validation -->
## 5. Artifact Validation

Documentation artifacts, API specs, and visual assets are validated via CI automation.

### PlantUML Diagrams (I1, I2, I3)

- **Location:** `docs/diagrams/`
- **Files:** context.puml, container.puml, erd.puml, anon-merge-seq.puml, async_matrix.puml, job-dispatch-sequence.puml
- **Validation:** CI script runs `plantuml` command to ensure diagrams render cleanly
- **Purpose:** C4 architecture diagrams, entity relationships, sequence flows, async workload matrix

### OpenAPI Specifications (I2.T7, I3.T8, I4.T5)

- **Location:** `api/openapi/v1.yaml`
- **Validation:** `swagger-cli validate api/openapi/v1.yaml` in CI
- **Coverage:** Auth, widgets (news/weather/stocks/social), marketplace, Good Sites, profiles, admin analytics
- **Client Generation:** Frontend typed clients reference this spec

### Screenshot Artifacts (I3.T7, I5.T3)

- **Service:** ScreenshotService (jvppeteer-based capture)
- **Validation:** Sample capture job verifies screenshot generation
- **Documentation:** Health metrics recorded in `docs/ops/runbooks/good-sites-release.md`
- **Storage:** Cloudflare R2 with WebP conversion via StorageGateway

### Documentation Anchors (I6.T9)

- **Validation:** Script cross-checks anchors vs manifest entries
- **Format:** `<!-- anchor: name -->` HTML comments before headings
- **Naming:** lowercase-with-hyphens slug convention
- **Purpose:** Enable autonomous agent navigation via plan manifest

---

<!-- anchor: data-integrity-compliance -->
## 6. Data Integrity & Compliance

Data integrity and compliance are verified through automated tests, audit trails, and retention policies.

### Partition Retention (I3.T9, I6.T4)

- **Tables:** link_clicks, feature_flag_evaluations
- **Strategy:** Monthly partitions with 90-day retention
- **Validation:** CI job simulates dropping old partitions and verifies rollups (ClickStatsRollupJobHandler)
- **Monitoring:** Dashboard tracks partition counts and rollup success rates

### GDPR Export & Deletion (I6.T6)

- **Export Package:** Zipped JSON archive with layout, feeds, marketplace, Good Sites, profile content
- **Job Handler:** DataExportJobHandler generates exports to Cloudflare R2
- **Deletion:** Cascading deletes verified via automated tests with fixture users
- **Consent Logs:** Verified via database query against consent_tracking table
- **Policy Compliance:** P1 (Data Export), P9 (Consent Tracking)

### Audit Trails (I2.T2, I4.T4, I2.T4)

- **Feature Flags:** All mutations logged to feature_flag_audit with before/after state
- **Refunds:** Stripe refund coordination logged with reason codes
- **Account Merges:** AccountMergeAudit captures anonymous→auth merge events
- **Review Cadence:** Weekly dashboard reviews in compliance meetings
- **Retention:** 7 years for financial audit trails, 90 days for operational logs

---

<!-- anchor: operational-readiness -->
## 7. Operational Readiness

Operational procedures, monitoring, and alerting ensure the platform stays within SLOs.

### Runbooks (I1.T4, I3.T9, I4.T9, I5.T7)

Runbooks are stored in `docs/ops/runbooks/` and include detection, troubleshooting, and escalation instructions:

- **[Content Monitoring](ops/runbooks/content-monitoring.md)** - RSS, AI tagging, weather, stocks, social integration
- **[Good Sites Release](ops/runbooks/good-sites-release.md)** - Directory feature rollout procedures
- **[Marketplace Operations](ops/runbooks/marketplace-operations.md)** - Listing lifecycle, payment processing, moderation

Each runbook documents:
- Daily health checks
- Alert thresholds and responses
- Common failure scenarios
- Escalation paths (L1 → L2 → L3)

### Observability Instrumentation (I1.T8)

- **Logs:** Include trace_id, user_id, flag states for correlation
- **Metrics:** Scraped via Prometheus, dashboards in Grafana (I3.T9, I6.T5)
- **Tracing:** Jaeger distributed tracing for cross-service requests
- **Documentation:** [Observability Guide](ops/observability.md)

### Alert Thresholds

Alerts are defined for:
- **AI Budget:** Approaching REDUCE/QUEUE/HARD_STOP thresholds
- **Queue Backlog:** Job queue depth exceeds capacity
- **Stripe Errors:** Payment failure rate > 5%
- **Screenshot Failures:** Capture job failure rate > 10%
- **Database:** Replication lag > 30 seconds for 10+ minutes

Thresholds are verified during smoke tests before GA release.

---

<!-- anchor: release-gates -->
## 8. Release Gates

Feature flags enable progressive rollout with well-defined exit criteria.

### Feature Flags (I2.T2)

| Flag | Module | Exit Criteria |
|------|--------|---------------|
| `stocks_widget` | Content (I3) | Test coverage ≥80%, latency p95 < 200ms, 7 days stable beta |
| `social_integration` | Content (I3) | Token refresh working, staleness detection tested, 7 days beta |
| `marketplace` | Marketplace (I4) | Payment flows tested, email relay working, 14 days beta |
| `good_sites` | Directory (I5) | Screenshot capture stable, voting tested, karma system validated |
| `profiles` | Profiles (I6) | Template rendering tested, curation working, analytics validated |

### Rollout Strategy

- **Cohort Assignment:** Stable MD5 hash of `flagKey + ":" + subjectId` ensures deterministic rollout
- **Whitelist Support:** Force-enable flags for specific user IDs or session hashes
- **Analytics Logging:** Consent-aware evaluation logging (Policy P14 compliance)
- **Monitoring:** KPI thresholds must be satisfied before increasing rollout percentage

### Beta Testing

- Limited cohorts (5% → 10% → 25% → 50% → 100%)
- Monitor dashboards for 24-48 hours between increases
- Rollback if error budgets exceeded or critical bugs detected

---

<!-- anchor: plan-manifest-anchoring -->
## 9. Plan Manifest & Anchoring

The plan manifest enables autonomous agents to navigate architecture and plan documents precisely.

### Anchor Strategy

- **Format:** HTML comments before headings: `<!-- anchor: name -->`
- **Naming:** lowercase-with-hyphens slug convention
- **Placement:** Always before headings, never after
- **Scope:** Top-level sections, subsections, glossary entries

### Manifest Generation (I6.T9)

- **File:** `.codemachine/artifacts/plan/plan_manifest.json`
- **Content:** Maps anchors → files + descriptions + section metadata
- **Validation:** CI script cross-checks anchors vs manifest entries
- **Purpose:** Enable precise section retrieval by autonomous agents

### Anchor Validation

Automated script verifies:
- All manifest anchors exist in target files
- Anchor naming follows slug convention
- No duplicate anchors across files
- Links to anchors resolve correctly

---

<!-- anchor: data-migration-strategy -->
## 10. Data Migration Strategy

Database schema changes are managed via MyBatis migrations with rollback procedures.

### Migration Workflow

```bash
# Apply migrations in development
cd migrations && mvn migration:up -Dmigration.env=development

# Apply migrations in staging
mvn migration:up -Dmigration.env=staging

# Apply migrations in production (with approval)
mvn migration:up -Dmigration.env=production
```

### Migration Standards

- **Numbering:** Sequential `YYYYMMDD000X00_description.sql`
- **Task Citation:** Comments include iteration.task format (e.g., `-- TASK: I4.T1`)
- **Rollback Documentation:** Each migration includes rollback steps in file header or separate `*_rollback_*.sql`
- **Checksum Logging:** Geo imports (I4.T1) and directory seeds (I5.T1) log checksums for validation

### Schema Changes Affecting JSONB (I2, I6)

- **Tables:** user_preferences (layout), profile_templates (template_config)
- **Migration Tasks:** Include upgrade scripts and backfill watchers
- **Regression Tests:** Confirm schema_version increments correctly
- **Backward Compatibility:** Maintain compatibility for 1 version back during rolling updates

### Pre-Release Dry-Run

- Execute migrations in staging environment first
- Verify zero destructive operations on prod data
- Confirm rollback procedures work as documented
- Review migration output logs for warnings/errors

---

<!-- anchor: security-validation -->
## 11. Security Validation

Security validation includes automated scanning, manual pen-testing, and secrets management.

### Automated Security Scanning

- **OWASP ZAP:** Automated scans against key endpoints (homepage, marketplace, Good Sites, profiles)
- **Cookie Flags:** Verify Secure, HttpOnly, SameSite attributes
- **CORS Configuration:** Validate allowed origins match deployment environments
- **CSP Headers:** Ensure Content-Security-Policy headers restrict inline scripts
- **Dependency Scanning:** OWASP Dependency Check in CI flags known vulnerabilities

### Secrets Management

- **Rotation:** Stripe + OAuth secrets rotated in staging before prod to ensure zero-downtime process
- **Storage:** Kubernetes sealed secrets, GitHub Encrypted Secrets for CI
- **Policy Compliance:** P8 (Secrets Management), P3 (PCI Compliance)

### Penetration Testing Scenarios

Manual pen-tests focus on:
- **Masked Email Relay Abuse:** Attempt to forge marketplace reply addresses
- **Click-Tracking URL Forgery:** Attempt to manipulate /track/click URLs
- **Reserved Username Brute-Force:** Test username validation and reservation logic
- **IDOR (Insecure Direct Object Reference):** Test authorization for user-specific resources
- **SQL Injection:** Test PostGIS query parameterization

Findings are documented in `docs/ops/security/` with mitigation steps per runbook.

---

<!-- anchor: rollout-playbook -->
## 12. Rollout Playbook

The rollout playbook documents step-by-step procedures for enabling modules in production.

### Pre-Launch Checklist

- [ ] Flush application caches (Redis/Caffeine)
- [ ] Reindex Elasticsearch with latest mappings
- [ ] Warm screenshot pool (pre-capture for Good Sites top links)
- [ ] Ensure AI budget resets at start-of-month
- [ ] Verify backup/restore procedures tested in past 30 days
- [ ] Confirm monitoring dashboards deployed with new metrics
- [ ] Review on-call rotation and escalation paths
- [ ] Stage rollback runbooks in incident channel

### Module Enablement Steps

For each module (stocks_widget, social_integration, marketplace, good_sites, profiles):

1. **Update Feature Flag:** Increase rollout percentage (0% → 5% → 10% → 25% → 50% → 100%)
2. **Monitor Dashboards:** Watch error rates, latency, throughput for 24 hours
3. **Check Error Budgets:** Confirm SLO compliance (e.g., p95 latency < 500ms, error rate < 1%)
4. **Communicate Status:** Post update to stakeholder channel (#homepage-releases)
5. **Wait Period:** Allow 24-48 hours of stable metrics before next increment

### Rollback Procedures

If issues detected:
1. **Disable Feature Flag:** Set rollout percentage to 0%
2. **Publish Message:** Notify stakeholders in #homepage-releases
3. **Revert Release Checklist:** Mark module as rolled back in I6.T9 checklist
4. **RCA Creation:** Create incident report within 48 hours
5. **Follow-up Actions:** Create backlog tickets for fixes

---

<!-- anchor: support-incident-response -->
## 13. Support & Incident Response

Incident response procedures ensure rapid detection, mitigation, and resolution.

### On-Call Rotation

- **Tooling:** PagerDuty alerts route to on-call engineer
- **Dashboards:** Annotated with links to relevant runbooks
- **Escalation Path:**
  - L1: Feature owner (ExperienceShell, Domain Service squad)
  - L2: Ops team (infrastructure, database, observability)
  - L3: Compliance team (GDPR, payment issues, security incidents)

### Incident Severity Levels

| SEV | Description | Response Time | Examples |
|-----|-------------|---------------|----------|
| SEV1 | Production outage or data breach | 15 minutes | Database down, S3 bucket publicly exposed |
| SEV2 | Major feature downtime | 1 hour | Marketplace payments failing, Good Sites not loading |
| SEV3 | Degraded performance with workarounds | 4 hours | Slow AI tagging, screenshot queue backlog |

### Incident Workflow

1. **Detection:** Alert fires via Prometheus → PagerDuty
2. **Acknowledgement:** On-call engineer acknowledges within SLA
3. **Incident Channel:** Create `#inc-homepage-YYYYMMDD-sevX` Slack channel
4. **Incident Commander:** Assign IC, communications lead, SMEs
5. **Mitigation:** Follow relevant runbook from `docs/ops/runbooks/`
6. **Capture Context:** kubectl describe outputs, Kibana logs with trace_id filtering
7. **Resolution:** Fix issue, verify via smoke tests
8. **Post-Incident:** RCA within 48 hours, follow-up action items

### Simulated Incidents (Pre-GA)

Before GA release, conduct incident simulations:
- **AI Budget Exhaustion:** Trigger HARD_STOP action, verify queueing works
- **Stripe Outage:** Simulate payment provider downtime, test fallback messaging
- **Screenshot Backlog:** Flood screenshot queue, verify concurrency controls
- **Database Failover:** Simulate primary failure, verify replica promotion

**For detailed runbooks, see [docs/ops/runbooks/](ops/runbooks/).**

---

<!-- anchor: documentation-review-signoff -->
## 14. Documentation Review & Sign-off

Documentation quality and stakeholder approval are required before release.

### Documentation Standards

Each iteration delivers:
- **Ops Runbooks:** Daily checks, troubleshooting, escalation paths
- **UI Guides:** User-facing feature documentation
- **Policy References:** Citations to architecture policies (P1-P14)

### Pre-Release Documentation Checklist

- [ ] All runbooks reviewed and tested against staging environment
- [ ] Anchors validated via automated script
- [ ] Cross-links tested and working
- [ ] Markdown linting passed (tables, lists, code blocks)
- [ ] API docs (OpenAPI) validated with swagger-cli
- [ ] Architecture diagrams (PlantUML) render cleanly
- [ ] Glossary terms reviewed for accuracy and completeness

### Stakeholder Approval

Required sign-offs:
- **Ops Lead:** Confirms runbooks are actionable and complete
- **Compliance Lead:** Confirms GDPR, consent, audit trail procedures documented
- **Product Lead:** Confirms user-facing documentation aligns with features
- **Tech Lead:** Confirms technical accuracy and completeness

Approvals are stored in repo as `docs/approvals/YYYY-MM-DD_iteration_signoff.md`.

### Peer Review Requirements

Glossary, verification strategy, and manifest updates require:
- Peer review by 2+ engineers
- Validation that anchors match manifest entries
- Confirmation that terminology is consistent across docs

---

## Summary

This Verification & Integration Strategy provides the strategic framework for testing, releasing, and operating Village Homepage. It ties together detailed operational documentation across testing, CI/CD, security, compliance, and incident response.

**Key Principles:**
1. **Test thoroughly** - Unit, integration, E2E, performance
2. **Automate quality gates** - Formatting, linting, coverage, security scanning
3. **Release progressively** - Feature flags with stable cohorts and monitoring
4. **Operate proactively** - Runbooks, dashboards, alerts, incident simulations
5. **Document comprehensively** - Anchors, cross-links, stakeholder approval

For detailed procedures, refer to the linked operational documentation throughout this guide.

---

**Document Version:** 1.0
**Last Updated:** 2025-01-21
**Task:** I6.T8 - Verification & Integration Strategy
