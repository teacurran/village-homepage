<!-- anchor: verification-and-integration-strategy -->
## 6. Verification and Integration Strategy

1. **Testing Levels & Ownership:**
    - **Unit Tests:** Mandatory for services, integrations, DTO serializers across all iterations. Coverage targets align with Section 4 (≥80% line/branch) and enforced via CI (I1.T7). Module owners: Auth/Preferences (I2), Content/AI (I3), Marketplace (I4), Good Sites (I5), Profiles (I6).
    - **Integration Tests:** Use Quarkus `@QuarkusTest` + Testcontainers for Postgres/PostGIS, Elasticsearch, Stripe mock servers, Mailpit, IMAP; ensure migrations run before tests. Integration coverage prioritized for OAuth merges, AI tagging, PostGIS search, screenshot capture, GDPR export.
    - **End-to-End Tests:** Playwright/Cypress suites per iteration (home personalization, marketplace lifecycle, Good Sites submissions, profiles). Execute in CI nightly + pre-release (I4.T9, I5.T9, I6.T9). Snapshots verified for SSR/React hydration.
    - **Performance Tests:** Focus on PostGIS/Elasticsearch queries, screenshot queue throughput, AI tagging budget transitions, gridstack interactions. Run targeted `k6` or JMeter scripts before enabling feature flags at scale.

2. **CI/CD Workflow:**
    - Pipeline `build.yml` (I1.T7) handles formatting, `npm run build`, unit+integration suites, Playwright smoke. On main merges, run Sonar scan, Jib image build, publish to registry.
    - Beta deploy triggered automatically with feature flags gating risky modules; production deploy follows manual approval with release checklist from I6.T9.
    - Pipeline caches Maven + npm artifacts, uses secrets for Stripe/Meta keys via GitHub Encrypted Secrets referencing Kubernetes secrets, ensuring compliance with P8 & P3.

3. **Quality Gates & Code Review:**
    - Enforce Spotless formatting, Sonar rules (0 blocker issues), dependency scanning for vulnerabilities (OWASP). PRs must cite relevant iteration task ID and policy references.
    - Feature flag toggles require audit entry + screenshot of analytics impact. Migrations need rollback documentation before merge.
    - Lint/typecheck required for TypeScript assets; failing lint blocks merge (I1.T5, I2.T9, I4.T9, I5.T9).

4. **Integration Validation:**
    - **OAuth/OIDC:** Test tokens from sandbox providers; mock failure states for rate limiting + consent flows (I2.T1/T4).
    - **LangChain4j/AI:** Use sandbox key for tests; record budgets with fake data to validate AiTaggingBudgetService (I3.T3, I5.T6).
    - **Stripe:** Hit testmode endpoints, simulate payment_intent succeeded/failed/refunded, ensure webhook signatures validated (I4.T4).
    - **Meta Graph API:** Use test pages for token refresh/staleness banners (I3.T6), verifying color-coded states.
    - **Cloudflare R2:** Local MinIO for dev (I1.T6, I3.T7); integration tests ensure signed URLs + WebP conversions.

5. **Artifact Validation:**
    - Diagrams (PlantUML) validated via CI script using `plantuml` command; docs under `docs/diagrams` must render cleanly. Applies to context/container/ERD/sequence/async matrix (I1, I2, I3).
    - OpenAPI spec validated using `swagger-cli validate api/openapi/v1.yaml` (I2.T7, I3.T8, I4.T5) and referenced by frontend typed clients.
    - Storage + screenshot artifacts verified through sample capture job; recorded in docs/ops/screenshot.md with health metrics (I3.T7, I5.T3).

6. **Data Integrity & Compliance:**
    - Partition retention (link_clicks, feature_flag_evaluations) tested by CI job that simulates dropping old partitions and verifying rollups (I3.T9, I6.T4).
    - GDPR exports tested by fixture user; automated tests confirm data zipped to R2 and deletion cascades (I6.T6). Consent logs verified via database query.
    - Audit trails (feature flag changes, refunds, merges) reviewed weekly via dashboards; tasks I2.T2, I4.T4, I2.T4 produce reports referenced in compliance meetings.

7. **Operational Readiness:**
    - Runbooks for jobs (I1.T4, I3.T9, I4.T9, I5.T7) stored in `docs/ops/`; each includes detection, troubleshooting, escalation instructions.
    - Observability instrumentation (I1.T8) ensures logs include trace_id/user_id/flag states; metrics scraped via Prometheus, dashboards built in I3.T9 + I6.T5.
    - Alert thresholds defined for AI budget, queue backlog, Stripe errors, screenshot failures. Verified during smoke tests before GA release.

8. **Release Gates:**
    - Feature flags `stocks_widget`, `social_integration`, `marketplace`, `good_sites`, `profiles` toggled progressively. Each has exit criteria (test coverage, metrics, documentation) recorded in release checklist (I6.T9).
    - Beta testing uses limited cohorts (stable hash segments). Monitoring ensures KPI thresholds satisfied before increasing rollout.

9. **Plan Manifest & Anchoring:**
    - All plan files contain anchors; manifest generated in I6.T9 ensures autonomous agents can fetch sections/tasks precisely. Verification includes script to cross-check anchors vs manifest entries.

10. **Data Migration Strategy:**
    - MyBatis migrations executed per env with `migration:up`; rollback steps documented in each migration file. Geo imports (I4.T1) and directory seeds (I5.T1) run via CLI with checksum logging. Pre-release dry-run ensures zero destructive operations on prod data.
    - For schema changes affecting JSONB (preferences, template_config), migration scripts include upgrade tasks and backfill watchers; regression tests confirm schema_version increments.

11. **Security Validation:**
    - Perform automated security scans (OWASP ZAP) against key endpoints, verify cookie flags, CORS, CSP (profiles + Good Sites). Stripe + OAuth secrets rotated in staging before prod to ensure zero downtime process.
    - Pen-test scenarios: abusing masked email relay, forging click-tracking URLs, brute-forcing reserved usernames. Document mitigation/resolution per runbook.

12. **Rollout Playbook:**
    - Documented steps for enabling each module: update feature flag percentage, monitor dashboards for 24 hours, check error budgets, communicate status to stakeholders. Includes rollback instructions (disable flag, publish message, revert release checklist).
    - Pre-launch tasks: flush caches, reindex Elasticsearch, warm screenshot pool, ensure AI budget resets at start-of-month.

13. **Support & Incident Response:**
    - On-call rotation gets annotated dashboards with links to runbooks. Escalation path: L1 (feature owner), L2 (ops), L3 (compliance). Incidents recorded in `docs/ops/incidents/` referencing anchors + RCA checklists.
    - Simulated incidents (AI budget exhaustion, Stripe outage, screenshot backlog) run before GA to practice response.

14. **Documentation Review & Sign-off:**
    - Each iteration delivers docs (ops runbooks, UI guides, policy references). Prior to release, docs are linted for anchors, cross-links validated, and stakeholders (ops/compliance/product) provide written approval stored in repo.
    - Glossary, verification strategy, and manifest updates require peer review to guarantee clarity for future autonomous agents.

<!-- anchor: glossary -->
## 7. Glossary

1. **AiTaggingBudgetService:** Service enforcing AI spend policies (P2/P10) returning actions NORMAL/REDUCE/QUEUE/HARD_STOP to job handlers.
2. **AccountMergeAudit:** Table logging anonymous→auth merge events with consent metadata for GDPR compliance.
3. **BulkImportJobHandler:** Good Sites job processing CSV/JSON submissions with AI categorization suggestions.
4. **ClickStatsRollupJobHandler:** Job aggregating raw click logs into daily summary tables used by admin analytics (I3.T9, I6.T4).
5. **ConfigProvider Tokens:** Ant Design theming mechanism used by React islands to match server-rendered Qute styles.
6. **DirectoryService:** Domain service powering Good Sites submissions, voting, bubbling, screenshot usage (I5).
7. **ExperienceShell:** Qute controller + layout orchestrator delivering homepage + profile templates with React mounts.
8. **FeatureFlagService:** Cohort-hashing system (P7/P14) providing rollout control + analytics logging per iteration I2.
9. **Gridstack Editor:** React-based widget layout tool enabling drag/drop, edit mode, and keyboard adjustments (I2.T6).
10. **LangChain4j Connector:** AI abstraction for Claude Sonnet used in feed tagging, fraud detection, Good Sites categorization (I3, I5).
11. **MarketplaceService:** Domain service managing categories, listings, payments, promotions, moderation (I4).
12. **RateLimitService:** Tiered limiter storing violations, providing headers + admin tuning endpoints (I2.T3).
13. **ScreenshotService:** jvppeteer-based capture pipeline storing WebP versions with version history + concurrency controls (I5.T3).
14. **StorageGateway:** Cloudflare R2 abstraction handling uploads, WebP conversion, signed URLs, retention policies (I3.T7).
15. **UserPreferenceService:** JSONB layout/config manager supporting anonymous + auth personalization (I2.T5).
16. **Verification & Integration Strategy:** Section summarizing testing + release process referencing tasks across iterations.
17. **Widget Resources:** REST endpoints `/api/widgets/{news|weather|stocks|social}` providing data for homepage experiences (I3.T8).
18. **Your Times / Your Report:** Profile template modes replicating newspaper and link-aggregation layouts with curated slot tooling (I6.T2).
19. **ClickTrackingService:** REST resource `/track/click` logging link engagements and redirecting users; foundation for analytics + cohort measurement (I3, I6).
20. **AiUsageTracking:** Monthly ledger storing AI token counts/costs powering budget enforcement + alerts (I3.T3).
21. **MarketplaceMessage Relay:** Masked email infrastructure bridging buyers/sellers using MessageRelayService + InboundEmailProcessor (I4.T7).
22. **GoodSites Karma:** Reputation system awarding points for quality submissions/votes, controlling auto-publish + moderator eligibility (I5.T4).
23. **ProfileCurationService:** Backend component managing curated articles, manual links, slot assignments for public templates (I6.T3).
24. **Plan Manifest:** JSON index mapping anchors → files/descriptions enabling autonomous agent navigation (I6.T9).
25. **Runbook:** Ops document describing monitoring, remediation, escalation for jobs/services stored in `docs/ops/runbooks/`.
26. **GDPR Export Package:** Zipped JSON archive generated by DataExportJobHandler containing layout, feeds, marketplace, Good Sites, profile content per user (I6.T6).
27. **PromotionExpirationJobHandler:** Marketplace job expiring featured listings/bump add-ons while coordinating refunds + badges (I4.T4).
28. **LinkHealthCheckJobHandler:** Directory job validating URL availability, marking entries dead, and informing moderators (I5.T7).
29. **AnalyticsResource:** Admin REST resource exposing aggregated stats for dashboards (I3.T9, I6.T5).
30. **ProfileMetadataRefreshJobHandler:** Job refreshing curated article metadata when sources change, ensuring template content stays current (I6.T3).
