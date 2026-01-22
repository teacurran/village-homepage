<!-- anchor: iteration-5-plan -->
### Iteration 5: Good Sites Directory, Screenshot Service, and Karma Systems

*   **Iteration ID:** `I5`
*   **Goal:** Launch Good Sites directory with hierarchical categories, submissions, voting, karma moderation, screenshot capture/versioning, AI-assisted categorization, browsing/search, and background maintenance jobs.
*   **Prerequisites:** `I1`–`I4` (StorageGateway, AI tagging, click tracking, admin RBAC) to reuse infrastructure.
*   **Iteration KPIs:** Submission throughput, screenshot success rate ≥98%, voting latency, AI categorization confidence, category ranking accuracy, moderation turnaround.
*   **Iteration Testing Focus:** Migrations, screenshot pool behavior, voting + karma calculations, AI suggestion flows, bubbled links, moderation/flagging, link health jobs.
*   **Iteration Exit Criteria:** Directory categories + sites available publicly, screenshot capture storing versions to R2, karma rules enforced, AI categorization suggestions live, link health + ranking jobs running.
*   **Iteration Collaboration Notes:** Coordinate with AI team for categorization prompts, ops for screenshot pools, UX for public templates, compliance for submission policies.
*   **Iteration Documentation Outputs:** New docs for directory submission guidelines, karma levels, screenshot operations, moderation workflow, AI prompting, bubbled link rules.
*   **Iteration Risks:** Screenshot resource spikes, inaccurate AI suggestions, or moderation backlog; mitigate via feature flags (`good_sites`), queue monitoring, and manual review fallbacks.
*   **Iteration Communications:** Weekly digest covering submission backlog, screenshot job depth, and AI budget usage delivered to stakeholders with anchor references.
*   **Iteration Dependency Notes:** Successful completion is required before I6 profile templates referencing Good Sites data can be finalized; StorageGateway + AI budget instrumentation must stay stable.
*   **Iteration Monitoring Goals:** Dashboards must expose screenshot queue length, AI suggestion confidence distribution, and karma distribution to inform release go/no-go decisions.
*   **Iteration Support Needs:** Ops must allocate SCREENSHOT workers with ≥6GB RAM and ensure Cloudflare R2 quotas expanded before enabling bulk imports.

<!-- anchor: task-i5-t1 -->
*   **Task 5.1:**
    *   **Task ID:** `I5.T1`
    *   **Description:** Create schema + models for `directory_categories` with unlimited depth, admin CRUD UI, caching, and seeds per requirements; include fields for icons, sort order, bubbled link settings.
        Provide CLI command hooking into Quarkus `CommandLineRunner` to import tree data from YAML/JSON so ops can adjust quickly.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Feature F13.1, Section 2 data model, Section 3 tree structure.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/data/models/","src/main/java/villagecompute/homepage/api/rest/admin/"]`
    *   **Target Files:** `["migrations/V20__directory_categories.sql","src/main/java/villagecompute/homepage/data/models/DirectoryCategory.java","src/main/java/villagecompute/homepage/api/rest/admin/DirectoryCategoryResource.java"]`
    *   **Deliverables:** Migration, entity, admin resource, seeds.
    *   **Acceptance Criteria:** Category tree manageable, caching layer works, admin controls active, tests cover recursion + sort.
    *   **Dependencies:** `I1.T3`, `I2.T8`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i5-t2 -->
*   **Task 5.2:**
    *   **Task ID:** `I5.T2`
    *   **Description:** Implement `directory_sites` + `directory_site_categories` + `directory_votes` schema, submission API (`SubmitSiteType`), moderation queue, status transitions (pending/approved/rejected/dead), and UI for submissions.
        Include metadata fetcher using StorageGateway for OG fallback and provide sanitization utilities for user overrides.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Feature F13.2–F13.6, Section 2 components.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/services/","src/main/java/villagecompute/homepage/api/rest/directory/"]`
    *   **Target Files:** `["migrations/V21__directory_sites.sql","migrations/V22__directory_site_categories.sql","migrations/V23__directory_votes.sql","src/main/java/villagecompute/homepage/services/DirectoryService.java","src/main/java/villagecompute/homepage/api/rest/directory/DirectoryResource.java"]`
    *   **Deliverables:** Schemas, services, submission endpoint, doc.
    *   **Acceptance Criteria:** Users submit sites with validation, queue records status, votes stored, doc explains flow, tests cover duplicates + pending moderation.
    *   **Dependencies:** `I5.T1`, `I3.T7`.
    *   **Parallelizable:** No.

<!-- anchor: task-i5-t3 -->
*   **Task 5.3:**
    *   **Task ID:** `I5.T3`
    *   **Description:** Build screenshot capture service using jvppeteer: browser pool initialization per P12, capture pipeline (thumbnail/full), version history table `directory_screenshot_versions`, queue handler, fallback OG images, admin retry UI.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Policy P4, F13.3, Section 2 components.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/services/","src/main/java/villagecompute/homepage/jobs/","docs/ops/screenshot.md"]`
    *   **Target Files:** `["migrations/V24__directory_screenshot_versions.sql","src/main/java/villagecompute/homepage/services/ScreenshotService.java","src/main/java/villagecompute/homepage/jobs/ScreenshotCaptureJobHandler.java","src/main/java/villagecompute/homepage/jobs/ScreenshotRefreshJobHandler.java","docs/ops/screenshot.md"]`
    *   **Deliverables:** Migration, service, job handlers, doc, tests.
    *   **Acceptance Criteria:** Pools respect concurrency, captures stored in R2, version history maintained, admin can request recapture, tests cover failure fallback.
    *   **Dependencies:** `I3.T7`, `I1.T4`.
    *   **Parallelizable:** No.

<!-- anchor: task-i5-t4 -->
*   **Task 5.4:**
    *   **Task ID:** `I5.T4`
    *   **Description:** Implement karma/trust system: extend `users` table with `directory_karma`, trust levels, submission/vote impact, auto-publish thresholds, moderator eligibility.
        Provide APIs for karma summary, admin adjustments, and UI messaging.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Feature F13.6, Section 2 components.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/services/","src/main/java/villagecompute/homepage/api/rest/directory/"]`
    *   **Target Files:** `["migrations/V25__directory_karma.sql","src/main/java/villagecompute/homepage/services/KarmaService.java","src/main/java/villagecompute/homepage/api/rest/directory/KarmaResource.java","docs/ops/karma.md"]`
    *   **Deliverables:** Migration, service, API, doc.
    *   **Acceptance Criteria:** Karma adjustments triggered by submissions/votes, thresholds enforced, admin overrides logged, doc clarifies points.
    *   **Dependencies:** `I5.T2`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i5-t5 -->
*   **Task 5.5:**
    *   **Task ID:** `I5.T5`
    *   **Description:** Build public Good Sites browsing UI: Qute templates for category pages, bubbled link logic, voting controls, filters, search, click tracking integration, and `DirectoryViewType` API.
        Provide `GoodSitesModerationResource` for queue + approvals and document bubbled badge semantics for accessibility.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Features F13.4–F13.11, Section 2 components, UI doc.
    *   **Input Files:** `["src/main/resources/templates/good-sites/","src/main/java/villagecompute/homepage/api/rest/directory/"]`
    *   **Target Files:** `["src/main/resources/templates/good-sites/category.html","src/main/java/villagecompute/homepage/api/rest/directory/GoodSitesResource.java","docs/ui-guides/good-sites.md"]`
    *   **Deliverables:** Templates, controllers, docs, tests.
    *   **Acceptance Criteria:** Category pages show direct+bubbled links, voting works with rate limits, search optional filter, doc outlines UX, tests ensure SSR + hydration.
    *   **Dependencies:** `I5.T1`–`I5.T4`.
    *   **Parallelizable:** No.

<!-- anchor: task-i5-t6 -->
*   **Task 5.6:**
    *   **Task ID:** `I5.T6`
    *   **Description:** Integrate AI categorization + bulk import: `BulkImportJobHandler`, LangChain prompt template, admin UI for reviewing suggestions, data structures for reasoning/confidence, CLI for CSV uploads.
        Provide admin diff view comparing AI suggestion vs moderator-seleted category to aid auditing and training data feedback.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Feature F13.14, Policy P2, Section 2 components.
    *   **Input Files:** `["src/main/java/villagecompute/homepage/jobs/","src/main/java/villagecompute/homepage/services/AiCategorizationService.java","docs/ops/ai-categorization.md"]`
    *   **Target Files:** `["src/main/java/villagecompute/homepage/services/AiCategorizationService.java","src/main/java/villagecompute/homepage/jobs/BulkImportJobHandler.java","src/main/java/villagecompute/homepage/api/rest/admin/DirectoryImportResource.java","docs/ops/ai-categorization.md"]`
    *   **Deliverables:** Service, job, admin resource, doc, tests.
    *   **Acceptance Criteria:** Bulk import processes rows asynchronously, AI suggestions stored with confidence, admin approves/overrides, prompts documented, budget tracked.
    *   **Dependencies:** `I3.T3`, `I5.T2`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i5-t7 -->
*   **Task 5.7:**
    *   **Task ID:** `I5.T7`
    *   **Description:** Implement link health monitoring + bubbling rules: `LinkHealthCheckJobHandler`, mark dead sites, notify moderators, compute bubble thresholds (score ≥10 and rank ≤3) and surface indicator in UI.
        Update analytics to report bubbled link performance.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Feature F13.5, F13.8, Section 2 components.
    *   **Input Files:** `["src/main/java/villagecompute/homepage/jobs/","src/main/java/villagecompute/homepage/services/"]`
    *   **Target Files:** `["src/main/java/villagecompute/homepage/jobs/LinkHealthCheckJobHandler.java","src/main/java/villagecompute/homepage/jobs/RankRecalculationJobHandler.java","docs/ops/link-health.md"]`
    *   **Deliverables:** Jobs, doc, tests.
    *   **Acceptance Criteria:** Health checks detect repeated failures, bubbled logic implemented, ranking job updates scores hourly, doc explains operations.
    *   **Dependencies:** `I5.T2`, `I5.T5`.
    *   **Parallelizable:** No.

<!-- anchor: task-i5-t8 -->
*   **Task 5.8:**
    *   **Task ID:** `I5.T8`
    *   **Description:** Extend click tracking + analytics for Good Sites: ensure `/track/click` stores directory metadata, create dashboards for submissions, votes, top sites, bubble contributions, integrate with admin analytics page.
    *   **Agent Type Hint:** `DevOpsAgent`
    *   **Inputs:** Click tracking spec, Section 2 components, analytics portal.
    *   **Input Files:** `["docs/ops/analytics.md","src/main/java/villagecompute/homepage/api/rest/admin/AnalyticsResource.java"]`
    *   **Target Files:** `["docs/ops/analytics.md","src/main/java/villagecompute/homepage/api/rest/admin/AnalyticsResource.java"]`
    *   **Deliverables:** Analytics endpoints updates, docs, charts definitions.
    *   **Acceptance Criteria:** Click logs include category IDs for Good Sites, dashboards show top sites + bubbled contributions, docs reference queries, tests verify endpoints.
    *   **Dependencies:** `I3.T9`, `I5.T5`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i5-t9 -->
*   **Task 5.9:**
    *   **Task ID:** `I5.T9`
    *   **Description:** Comprehensive QA/regression: tests for submissions, voting, karma impacts, screenshot capture, AI suggestions, bubbled display, moderation actions; load tests for screenshot queue + feed ingestion interplay.
        Document runbooks + checklist for release.
    *   **Agent Type Hint:** `QAAgent`
    *   **Inputs:** Outputs of T5.1–T5.8, Section 4 quality gate.
    *   **Input Files:** `["tests/e2e/","src/test/java/","docs/ops/testing.md"]`
    *   **Target Files:** `["tests/e2e/good-sites.spec.ts","src/test/java/villagecompute/homepage/directory/DirectoryResourceTest.java","docs/ops/testing.md"]`
    *   **Deliverables:** Automated tests, updated docs, release checklist.
    *   **Acceptance Criteria:** Tests cover success/failure flows, coverage metrics updated, load test results documented, release checklist approved.
    *   **Dependencies:** All prior I5 tasks.
    *   **Parallelizable:** No.
