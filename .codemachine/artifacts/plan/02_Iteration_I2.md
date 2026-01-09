<!-- anchor: iteration-2-plan -->
### Iteration 2: Identity, Feature Flags, and Personalization Core

*   **Iteration ID:** `I2`
*   **Goal:** Deliver OAuth bootstrap, anonymous account lifecycle, feature flag engine, rate limiting, preference storage, and gridstack-based homepage shell with OpenAPI contracts so personalization flows function securely for both anonymous and authenticated users.
*   **Prerequisites:** `I1` completion (tooling, diagrams, jobs, CI, security baseline).
*   **Iteration KPIs:** Successful OAuth login + bootstrap, consent modal integration, feature flag evaluation logging with GDPR toggles, layout persistence for anonymous + auth users, baseline OpenAPI covering auth/preferences/widgets, and rate limits enforced on key endpoints.
*   **Iteration Testing Focus:** Automated coverage for OAuth callback, cookie issuance, anonymous merge, flag evaluation hashing, and preference validation; integrate e2e smoke for homepage editing + rate limiting scenarios.
*   **Iteration Exit Criteria:** All auth/flag/rate-limit endpoints have unit + integration tests, consent diagram approved by compliance, default layout renders in prod parity env, and OpenAPI spec published with version tag `v1alpha`.
*   **Iteration Collaboration Notes:** Coordinate daily with UI squad for gridstack UX, with security/compliance on consent copy, and with infra for OAuth secrets + feature flag analytics retention to avoid rework.
*   **Iteration Documentation Outputs:** Update docs for security, privacy, preferences schema, UI layout guidelines, API spec notes, and RBAC instructions; ensure anchors added for manifest cross-linking.
*   **Iteration Risks:** OAuth provider quota issues, gridstack performance regressions, or consent UX disagreements may delay iteration; mitigate via mock providers, perf budgets, and early compliance reviews.
*   **Iteration Communications:** Provide weekly status summary referencing blueprint anchors to stakeholders, highlighting any policy escalations (P1/P7/P9/P14) triggered by discovered gaps.
*   **Iteration Dependencies:** I2 outputs unlock content services (I3) and vertical modules; feature flags + preferences required for later iterations to guard slow rollouts.

<!-- anchor: task-i2-t1 -->
*   **Task 2.1:**
    *   **Task ID:** `I2.T1`
    *   **Description:** Implement Quarkus OIDC multi-tenant config for Google, Facebook, Apple; wire `/bootstrap` superuser endpoint with guard (403 after first admin) and JWT session issuance.
        Create `AuthIdentityService` facade, configure `vu_anon_id` cookie issuance, and integrate with RateLimitService for login/bootstrap endpoints.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Sections 1–2, Policy F1, P1, P9.
    *   **Input Files:** `["src/main/resources/application.properties","src/main/java/villagecompute/homepage/api/rest/AuthResource.java"]`
    *   **Target Files:** `["src/main/java/villagecompute/homepage/services/AuthIdentityService.java","src/main/java/villagecompute/homepage/api/rest/AuthResource.java","src/main/resources/application.properties","docs/ops/security.md"]`
    *   **Deliverables:** OIDC config, Auth resource, bootstrap guard, cookie issuer logic, documentation for configuring providers.
    *   **Acceptance Criteria:** OAuth login works for all providers, `vu_anon_id` cookie adheres to HttpOnly/Secure/SameSite, bootstrap returns 403 after admin exists, rate limits logged.
    *   **Dependencies:** `I1.T1`, `I1.T9`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i2-t2 -->
*   **Task 2.2:**
    *   **Task ID:** `I2.T2`
    *   **Description:** Build FeatureFlagService with Panache entity, evaluation method using MD5 hash, whitelist support, analytics toggle, audit logging (`feature_flag_audit`).
        Implement REST endpoints `/admin/api/feature-flags` (list/update) and CLI seeding migration for initial flags.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Policies P7 & P14, Section 2 components, Section 4 directives.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/data/models/","src/main/java/villagecompute/homepage/services/"]`
    *   **Target Files:** `["migrations/V2__feature_flags.sql","src/main/java/villagecompute/homepage/data/models/FeatureFlag.java","src/main/java/villagecompute/homepage/services/FeatureFlagService.java","src/main/java/villagecompute/homepage/api/rest/admin/FeatureFlagResource.java"]`
    *   **Deliverables:** Schema migration, service, resource endpoints, DTOs, seed data.
    *   **Acceptance Criteria:** CRUD endpoints secured via roles, evaluations log to partitioned table respecting consent, unit tests cover cohort hashing + analytics opt-out, doc updated.
    *   **Dependencies:** `I2.T1`, `I1.T3`.
    *   **Parallelizable:** No.

<!-- anchor: task-i2-t3 -->
*   **Task 2.3:**
    *   **Task ID:** `I2.T3`
    *   **Description:** Implement RateLimitService with Caffeine caches, tier definitions (anonymous, logged_in, trusted), violation logging table, enforcement middleware for key endpoints (login, search, votes, submissions).
        Provide admin endpoints to view/update rate limit config, integrate with observability metrics.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Policy F14.2, Section 2 components, Section 4 directives, ERD from I1.T3.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/services/","src/main/java/villagecompute/homepage/api/rest/admin/"]`
    *   **Target Files:** `["migrations/V3__rate_limits.sql","src/main/java/villagecompute/homepage/services/RateLimitService.java","src/main/java/villagecompute/homepage/api/rest/admin/RateLimitResource.java","docs/ops/rate-limits.md"]`
    *   **Deliverables:** Migration, service, CDI interceptors, admin endpoints, docs.
    *   **Acceptance Criteria:** Rate limits enforced with headers + 429 responses, violation logs recorded with TTL, admin UI returns config, metrics exported.
    *   **Dependencies:** `I1.T4`, `I1.T8`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i2-t4 -->
*   **Task 2.4:**
    *   **Task ID:** `I2.T4`
    *   **Description:** Implement anonymous-to-auth merge flow: `account_merge_audit` table, consent modal API, merge logic merging layout/preferences/topic data, opt-out path, and 90-day purge scheduling.
        Provide PlantUML sequence diagram for login + consent + merge + audit + purge job and update docs.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Policies P1 & P9, Section 2 components, Section 4 command rules.
    *   **Input Files:** `["migrations/","docs/diagrams/","src/main/java/villagecompute/homepage/services/"]`
    *   **Target Files:** `["migrations/V4__account_merge_audit.sql","src/main/java/villagecompute/homepage/services/AccountMergeService.java","src/main/java/villagecompute/homepage/jobs/AccountMergeCleanupJobHandler.java","docs/diagrams/anon-merge-seq.puml","docs/ops/privacy.md"]`
    *   **Deliverables:** Migration, merge service, cleanup job handler, diagram, privacy doc.
    *   **Acceptance Criteria:** Consent recorded with timestamp/version/IP, merge summarises data, anonymized entries soft-delete, cleanup job scheduled for 90-day purge, diagram reviewed by compliance.
    *   **Dependencies:** `I2.T1`, `I1.T4`, `I1.T8`.
    *   **Parallelizable:** No.

<!-- anchor: task-i2-t5 -->
*   **Task 2.5:**
    *   **Task ID:** `I2.T5`
    *   **Description:** Design `UserPreferencesType` JSON schema (layout widgets array, news topics, watchlist, weather locations, theme, widget configs, schema_version) and persist to `users.preferences`.
        Provide migration default, service for CRUD, REST endpoints `/api/preferences`, validation, and schema upgrade strategy documentation.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Section 2 data models, P1/P9, UI governance doc.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/services/UserPreferenceService.java","src/main/java/villagecompute/homepage/api/rest/PreferencesResource.java"]`
    *   **Target Files:** `["migrations/V5__user_preferences_defaults.sql","src/main/java/villagecompute/homepage/services/UserPreferenceService.java","src/main/java/villagecompute/homepage/api/rest/PreferencesResource.java","docs/architecture/preferences-schema.md"]`
    *   **Deliverables:** Schema doc, service methods, REST endpoints, tests verifying anonymous + auth flows.
    *   **Acceptance Criteria:** Preferences validated, schema version stored, GET/PUT endpoints secured with rate limits, doc explains migrations.
    *   **Dependencies:** `I2.T4`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i2-t6 -->
*   **Task 2.6:**
    *   **Task ID:** `I2.T6`
    *   **Description:** Build homepage Qute layout + gridstack integration: implement `ExperienceShell` controllers, server-rendered widget placeholders, React mount attachments, `gridstack` JS config, and edit mode toggles.
        Add default layout definition (anonymous) and quick start instructions plus CSS tokens for theme alignment.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Sections 2 (frontend stack), `UserPreferenceService`, UI architecture doc.
    *   **Input Files:** `["src/main/resources/templates/homepage.html","src/main/resources/META-INF/resources/assets/ts/mounts.ts","src/main/resources/META-INF/resources/assets/ts/gridstack-editor.ts"]`
    *   **Target Files:** `["src/main/resources/templates/homepage.html","src/main/java/villagecompute/homepage/api/rest/HomepageResource.java","src/main/resources/META-INF/resources/assets/ts/gridstack-editor.ts","docs/ui-guides/homepage-layout.md"]`
    *   **Deliverables:** Template, controller, React island, documentation for editing controls.
    *   **Acceptance Criteria:** Anonymous + auth views render with correct default layout, edit mode toggles, data-props include preferences + flag states, tests cover SSR + React hydration.
    *   **Dependencies:** `I2.T5`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i2-t7 -->
*   **Task 2.7:**
    *   **Task ID:** `I2.T7`
    *   **Description:** Produce OpenAPI v3 draft covering auth, preferences, widget placeholders (news/weather/stocks stub), feature flags, rate limit introspection; share DTO definitions for frontend.
        Integrate spec generation with Maven build and publish to `api/openapi` directory.
    *   **Agent Type Hint:** `DocumentationAgent`
    *   **Inputs:** Section 2 API contract style, tasks T2.1–T2.6 outputs.
    *   **Input Files:** `["api/openapi/"]`
    *   **Target Files:** `["api/openapi/v1.yaml","docs/api/openapi-notes.md"]`
    *   **Deliverables:** OpenAPI file, generation script/README snippet.
    *   **Acceptance Criteria:** Spec validates with `swagger-cli`, includes schemas for DTOs, referenced in README, CI fails on drift.
    *   **Dependencies:** `I2.T1`–`I2.T6`.
    *   **Parallelizable:** No.

<!-- anchor: task-i2-t8 -->
*   **Task 2.8:**
    *   **Task ID:** `I2.T8`
    *   **Description:** Implement admin role + permission import referencing `village-storefront` constants; seed `admin_roles` table, create impersonation guard rails, add `/admin/api/users/roles` endpoints.
        Provide doc describing RBAC mapping and bootstrap steps for support/ops roles.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Section F1 roles/permissions, Section 4 directives.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/data/models/","src/main/java/villagecompute/homepage/api/rest/admin/"]`
    *   **Target Files:** `["migrations/V6__admin_roles.sql","src/main/java/villagecompute/homepage/data/models/AdminRole.java","src/main/java/villagecompute/homepage/api/rest/admin/UserAdminResource.java","docs/ops/rbac.md"]`
    *   **Deliverables:** Migration, entity, service/resource, RBAC doc.
    *   **Acceptance Criteria:** Roles seeded, endpoints secured, impersonation audit log stub created, doc describes provisioning.
    *   **Dependencies:** `I2.T1`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i2-t9 -->
*   **Task 2.9:**
    *   **Task ID:** `I2.T9`
    *   **Description:** Implement automated testing harness for personalization flows: create Quarkus tests covering OAuth callback, anonymous merge, preference CRUD, feature flag evaluation, rate limit enforcement; add Cypress/Playwright smoke covering homepage edit.
        Update CI pipeline to run new suites and publish coverage metrics + artifacts.
    *   **Agent Type Hint:** `QAAgent`
    *   **Inputs:** Outputs of T2.1–T2.6, Section 4 quality gate.
    *   **Input Files:** `["src/test/java/","package.json",".github/workflows/build.yml"]`
    *   **Target Files:** `["src/test/java/villagecompute/homepage/AuthResourceTest.java","src/test/java/villagecompute/homepage/PreferencesResourceTest.java","tests/e2e/homepage.spec.ts","docs/ops/testing.md"]`
    *   **Deliverables:** Unit/integration tests, e2e spec, doc updates, CI job snippet.
    *   **Acceptance Criteria:** Coverage ≥80% for auth/preferences modules, e2e smoke runs in CI, rate limit tests assert 429 path, documentation updated.
    *   **Dependencies:** All prior I2 tasks.
    *   **Parallelizable:** No.
