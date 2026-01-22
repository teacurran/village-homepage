<!-- anchor: iteration-6-plan -->
### Iteration 6: Public Profiles, Click Analytics, and Verification Strategy

*   **Iteration ID:** `I6`
*   **Goal:** Ship public profile templates, curated article tooling, click tracking rollups, admin analytics dashboards, GDPR export/deletion flows, and verification/glossary deliverables to close out release readiness.
*   **Prerequisites:** `I1`–`I5` (auth, personalization, content pipelines, marketplace, Good Sites) provide shared services.
*   **Iteration KPIs:** Profile publish rate, click tracking coverage, analytics freshness, GDPR request turnaround, test coverage ≥80%, documentation completeness.
*   **Iteration Testing Focus:** Profile template rendering, slot assignment, click tracking partitions/rollups, data export/deletion, analytics API contracts, ops runbooks, glossary accuracy.
*   **Iteration Exit Criteria:** Public profiles accessible with templates + SEO, analytics dashboards running, GDPR flows validated, verification & glossary sections authored, plan manifest finalized.
*   **Iteration Collaboration Notes:** Coordinate with marketing for profile SEO copy, ops for analytics dashboards, compliance for GDPR flows, UI for template polish.
*   **Iteration Documentation Outputs:** Profile builder guide, analytics configuration, GDPR export instructions, final verification + glossary text referencing anchors.
*   **Iteration Risks:** Click tracking partition misconfig or GDPR compliance gaps; mitigate via thorough tests + audits before release.
*   **Iteration Communications:** Send twice-weekly rollout readiness reports summarizing open bugs, analytics health, and compliance actions to stakeholders with anchor references.
*   **Iteration Support Needs:** Ops to monitor click rollup jobs + export pipelines closely and reserve capacity for final regression runs; compliance to review glossary & verification text.

<!-- anchor: task-i6-t1 -->
*   **Task 6.1:**
    *   **Task ID:** `I6.T1`
    *   **Description:** Implement `user_profiles`, `profile_curated_articles`, and `reserved_usernames` schema with Panache entities; enforce validation (username rules, reserved list, publish toggles, view counts) and admin tooling.
        Provide migrations + CLI to bootstrap reserved names and document naming policy.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Feature F11, Section 2 data model, policy references.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/data/models/","src/main/java/villagecompute/homepage/api/rest/profile/"]`
    *   **Target Files:** `["migrations/V26__user_profiles.sql","migrations/V27__profile_curated_articles.sql","migrations/V28__reserved_usernames.sql","src/main/java/villagecompute/homepage/data/models/UserProfile.java","src/main/java/villagecompute/homepage/services/ProfileService.java","src/main/java/villagecompute/homepage/api/rest/profile/ProfileResource.java"]`
    *   **Deliverables:** Schemas, services, REST endpoints, doc.
    *   **Acceptance Criteria:** CRUD operations enforce validation, reserved names blocked, view counts tracked, tests cover publish/draft/stats.
    *   **Dependencies:** `I2.T5`, `I3.T8`, `I5` outputs.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i6-t2 -->
*   **Task 6.2:**
    *   **Task ID:** `I6.T2`
    *   **Description:** Build profile templates (public_homepage, your_times, your_report): Qute layouts, React islands for editors, template_config schema, slot editors, theme controls, preview/publish workflows.
        Include SEO metadata injection per F11.9 and shareable preview links with canonical tags.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Feature F11.3–F11.5, UI architecture doc, Section 2 components.
    *   **Input Files:** `["src/main/resources/templates/profiles/","src/main/resources/META-INF/resources/assets/ts/profiles/"]`
    *   **Target Files:** `["src/main/resources/templates/profiles/public_homepage.html","src/main/resources/templates/profiles/your_times.html","src/main/resources/templates/profiles/your_report.html","src/main/resources/META-INF/resources/assets/ts/profile-builder.tsx","docs/ui-guides/profiles.md"]`
    *   **Deliverables:** Templates, React editor, doc.
    *   **Acceptance Criteria:** Templates render on mobile/desktop, editors support drag/drop + slot editing, SEO tags present, tests cover serialization.
    *   **Dependencies:** `I6.T1`, `I5` data.
    *   **Parallelizable:** No.

<!-- anchor: task-i6-t3 -->
*   **Task 6.3:**
    *   **Task ID:** `I6.T3`
    *   **Description:** Implement curated article tooling: feed picker modal pulling `feed_items`, manual link entry with metadata fetch, slot assignment, version history, schedule for `ProfileMetadataRefreshJobHandler`.
        Provide UI hints for slot capacity, publish times, and integrate with Good Sites curated content.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Feature F11.4–F11.7, Section 2 components.
    *   **Input Files:** `["src/main/java/villagecompute/homepage/services/ProfileCurationService.java","src/main/java/villagecompute/homepage/jobs/ProfileMetadataRefreshJobHandler.java","src/main/java/villagecompute/homepage/api/rest/profile/ProfileCurationResource.java"]`
    *   **Target Files:** `["src/main/java/villagecompute/homepage/services/ProfileCurationService.java","src/main/java/villagecompute/homepage/api/rest/profile/ProfileCurationResource.java","src/main/java/villagecompute/homepage/jobs/ProfileMetadataRefreshJobHandler.java","docs/ui-guides/profile-curation.md"]`
    *   **Deliverables:** Service, API, job, doc, tests.
    *   **Acceptance Criteria:** Users curate articles, manual links metadata captured, job refreshes OG data, UI reflects slot states, tests cover validation.
    *   **Dependencies:** `I6.T1`, `I3.T2`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i6-t4 -->
*   **Task 6.4:**
    *   **Task ID:** `I6.T4`
    *   **Description:** Extend click tracking + rollup jobs to include profile events (`profile_curated`, `profile_view`), daily summary tables, analytics endpoints powering admin dashboards, and charts for conversions.
        Update `/track/click` to support new types + metadata and verify partition retention.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** F14.9, Section 2 components, analytics blueprint.
    *   **Input Files:** `["src/main/java/villagecompute/homepage/api/rest/TrackingResource.java","src/main/java/villagecompute/homepage/jobs/ClickStatsRollupJobHandler.java","docs/ops/analytics.md"]`
    *   **Target Files:** `["src/main/java/villagecompute/homepage/api/rest/TrackingResource.java","src/main/java/villagecompute/homepage/jobs/ClickStatsRollupJobHandler.java","docs/ops/analytics.md"]`
    *   **Deliverables:** Updated tracking resource, rollup job tweaks, docs, tests.
    *   **Acceptance Criteria:** Click logs store profile metadata, rollups include new types, analytics dashboards display results, tests verify logging + privacy compliance.
    *   **Dependencies:** `I3.T9`, `I6.T1`.
    *   **Parallelizable:** No.

<!-- anchor: task-i6-t5 -->
*   **Task 6.5:**
    *   **Task ID:** `I6.T5`
    *   **Description:** Build admin analytics dashboards (AntV charts) covering clicks, AI budget, marketplace revenue, Good Sites stats, profile engagement; integrate with `/admin/api/analytics` endpoints; add filters, exports, and docs.
        Document chart theming, data source mappings, and guidance for ops to interpret anomalies.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Sections 2 components, analytics requirements from earlier iterations.
    *   **Input Files:** `["src/main/resources/templates/admin/analytics.html","src/main/resources/META-INF/resources/assets/ts/admin-analytics.tsx","src/main/java/villagecompute/homepage/api/rest/admin/AnalyticsResource.java"]`
    *   **Target Files:** `["src/main/resources/templates/admin/analytics.html","src/main/resources/META-INF/resources/assets/ts/admin-analytics.tsx","src/main/java/villagecompute/homepage/api/rest/admin/AnalyticsResource.java","docs/ui-guides/admin-analytics.md"]`
    *   **Deliverables:** Template, React charts, API updates, doc.
    *   **Acceptance Criteria:** Charts render with filters, export buttons produce CSV/JSON, API handles new params, doc instructs ops.
    *   **Dependencies:** `I3.T9`, `I4.T9`, `I5.T8`, `I6.T4`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i6-t6 -->
*   **Task 6.6:**
    *   **Task ID:** `I6.T6`
    *   **Description:** Implement GDPR export/deletion pipeline leveraging delayed jobs: user-facing endpoints to request export (ZIP JSON via R2) and delete account (cascade to preferences, evaluations, social tokens, marketplace data, profiles); update docs + tests.
        Add admin notifications for export ready/deletion complete, referencing retention windows.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Policy P1, P14, Section 4 directives.
    *   **Input Files:** `["src/main/java/villagecompute/homepage/api/rest/PrivacyResource.java","src/main/java/villagecompute/homepage/jobs/DataExportJobHandler.java","src/main/java/villagecompute/homepage/jobs/DataDeletionJobHandler.java","docs/ops/privacy.md"]`
    *   **Target Files:** `["src/main/java/villagecompute/homepage/api/rest/PrivacyResource.java","src/main/java/villagecompute/homepage/jobs/DataExportJobHandler.java","src/main/java/villagecompute/homepage/jobs/DataDeletionJobHandler.java","docs/ops/privacy.md"]`
    *   **Deliverables:** REST resource, job handlers, doc, tests verifying retention rules.
    *   **Acceptance Criteria:** Exports generated with signed URLs, deletion cascades to required tables instantly, audit logs recorded, tests cover consent change/purge.
    *   **Dependencies:** `I1.T4`, `I2.T5`, `I3.T7`, `I4.T3`, `I5.T2`.
    *   **Parallelizable:** No.

<!-- anchor: task-i6-t7 -->
*   **Task 6.7:**
    *   **Task ID:** `I6.T7`
    *   **Description:** Flesh out email templates + notification flows (profiles published/unpublished, analytics alerts, GDPR export ready) using Qute templates; integrate with Mailer service and RateLimitService.
        Ensure templates reuse Design System tokens and include localized copy placeholders for future languages.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Policy F14.3, Section 2 components, earlier email patterns.
    *   **Input Files:** `["src/main/resources/templates/email-templates/","src/main/java/villagecompute/homepage/services/NotificationService.java"]`
    *   **Target Files:** `["src/main/resources/templates/email-templates/profile-published.html","src/main/resources/templates/email-templates/profile-analytics.html","src/main/resources/templates/email-templates/export-ready.html","src/main/java/villagecompute/homepage/services/NotificationService.java"]`
    *   **Deliverables:** Templates, service updates, docs.
    *   **Acceptance Criteria:** Emails render, data binding validated, RateLimitService prevents spam, tests send to Mailpit.
    *   **Dependencies:** `I2.T6`, `I3.T9`, `I6.T1`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i6-t8 -->
*   **Task 6.8:**
    *   **Task ID:** `I6.T8`
    *   **Description:** Author Verification & Integration Strategy (Section 6) and Glossary (Section 7) markdowns summarizing testing levels, CI/CD, quality gates, artifact validation, and terminology; ensure anchors match manifest.
        Reference outputs from all iterations, include runbook pointers.
    *   **Agent Type Hint:** `DocumentationAgent`
    *   **Inputs:** Entire plan, directives, ops docs, analytics.
    *   **Input Files:** `[".codemachine/artifacts/plan/03_Verification_and_Glossary.md"]`
    *   **Target Files:** `[".codemachine/artifacts/plan/03_Verification_and_Glossary.md"]`
    *   **Deliverables:** Completed sections 6 & 7 with anchors, cross-links.
    *   **Acceptance Criteria:** Content aligns with Section 6+7 requirements, references tasks, anchors present for manifest.
    *   **Dependencies:** Completion of technical tasks feeding verification/glossary.
    *   **Parallelizable:** No.

<!-- anchor: task-i6-t9 -->
*   **Task 6.9:**
    *   **Task ID:** `I6.T9`
    *   **Description:** Final verification + hardening: run full regression suite (backend + frontend + e2e), security scans, performance tests (profiles, analytics, exports), finalize plan manifest with anchors, create release checklist referencing policies.
        Produce retrospective summary capturing risks + mitigations for leadership sign-off.
    *   **Agent Type Hint:** `QAAgent`
    *   **Inputs:** All iterations, Section 4 directives.
    *   **Input Files:** `["tests/e2e/","src/test/java/","docs/ops/testing.md",".codemachine/artifacts/plan/plan_manifest.json"]`
    *   **Target Files:** `["docs/ops/testing.md","docs/ops/release-checklist.md",".codemachine/artifacts/plan/plan_manifest.json"]`
    *   **Deliverables:** Regression run reports, updated docs, final manifest.
    *   **Acceptance Criteria:** Tests green, performance targets met, manifest accurate, release checklist approved by stakeholders, retrospective logged.
    *   **Dependencies:** All I6 tasks.
    *   **Parallelizable:** No.
