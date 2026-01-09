<!-- anchor: iteration-2-plan -->
### Iteration 2: Homepage Personalization & Widget Contracts

- **Iteration ID:** `I2`
- **Goal:** Translate architectural artifacts into actionable specs for personalized homepage delivery, covering user preference schemas, widget contracts, consent/merge UX flows, and initial OpenAPI definitions so frontend/backend squads can begin implementation in parallel.
- **Prerequisites:** Completion of I1 artifacts (architecture intake, diagrams, ERD, job + governance playbooks, onboarding overview).
- **Iteration Narrative:** This iteration sets the blueprint for user-facing homepage experiences. It defines DTOs, OpenAPI endpoints, layout schema validations, widget behavior specs, TypeScript mount scaffolding, and compliance flows for anonymous merges. It also aligns AI tagging prioritization with feed lifecycles and ensures feature flag gating is embedded into widget contracts from the start.

<!-- anchor: task-i2-t1 -->
- **Task 2.1:**
  - **Task ID:** `I2.T1`
  - **Description:** Build the Homepage Requirements Backlog document summarizing widget capabilities, layouts per device, personalization rules, and default anonymous experiences. Include acceptance criteria for each widget (news, weather, stocks, social, quick links, RSS, search) referencing policies (P1, P2, P5, P9, P13) and mapping to user preference fields.
  - **Agent Type Hint:** `ProductDocumentationAgent`
  - **Inputs:** Architecture intake, Section 1 requirements, job blueprint.
  - **Input Files:** ["docs/architecture/architecture_intake.md", "CLAUDE.md"]
  - **Target Files:** ["docs/ui-guides/widget_matrix.md"]
  - **Deliverables:** Widget matrix with columns for state coverage, data sources, feature flags, merge behavior, instrumentation, rate limits, and dependencies.
  - **Acceptance Criteria:** Matrix covers all widget types, ties each to relevant policy IDs and delayed job triggers, and highlights analytics/feature flag requirements.
  - **Dependencies:** `I1.T1`
  - **Parallelizable:** Yes

<!-- anchor: task-i2-t2 -->
- **Task 2.2:**
  - **Task ID:** `I2.T2`
  - **Description:** Produce OpenAPI v1 draft covering authentication bootstrap, anonymous ID issuance, user preferences CRUD, widget data fetch endpoints, and `/track/click` instrumentation payloads. Include error models, rate limit headers, and examples for each endpoint.
  - **Agent Type Hint:** `BackendAgent`
  - **Inputs:** ERD, widget matrix, feature flag/rate limit playbooks.
  - **Input Files:** ["docs/architecture/data_model.puml", "docs/ui-guides/widget_matrix.md", "docs/ops/feature_flag_playbook.md", "docs/ops/rate_limit_playbook.md"]
  - **Target Files:** ["api/openapi.yaml"]
  - **Deliverables:** OpenAPI YAML with security schemes, schemas for UserPreferencesType, WidgetResponseType, Consent payloads, and ClickTrackingType.
  - **Acceptance Criteria:** Spec validates via `openapi-generator` or `spectral`, covers all endpoints required by homepage widgets, includes policy-relevant headers (consent, rate limits), and references DTO anchors.
  - **Dependencies:** `I2.T1`
  - **Parallelizable:** Partially

<!-- anchor: task-i2-t3 -->
- **Task 2.3:**
  - **Task ID:** `I2.T3`
  - **Description:** Design database migration plan and schema notes for user preference storage (`users.preferences` JSONB), anonymous user lifecycle (Policy P9), feature flag evaluation retention, and click tracking partitions specific to homepage interactions.
  - **Agent Type Hint:** `DatabaseAgent`
  - **Inputs:** ERD, OpenAPI draft, governance docs.
  - **Input Files:** ["docs/architecture/data_model.puml", "api/openapi.yaml", "docs/ops/feature_flag_playbook.md"]
  - **Target Files:** ["migrations/notes/iteration2_prefs_and_flags.md"]
  - **Deliverables:** Migration note with table/column definitions, JSONB schema versioning plan, indexes, retention jobs, and test strategies.
  - **Acceptance Criteria:** Document lists SQL statements for new columns/indexes, describes migration ordering, identifies rollback steps, and references policies P1, P7, P14.
  - **Dependencies:** `I2.T2`
  - **Parallelizable:** No

<!-- anchor: task-i2-t4 -->
- **Task 2.4:**
  - **Task ID:** `I2.T4`
  - **Description:** Specify consent + anonymous merge UX (copy, modals, flows, decision states). Deliver flow diagrams for OAuth merge, GDPR consent options, anonymous data discard, and audit logging triggers.
  - **Agent Type Hint:** `UXDocumentationAgent`
  - **Inputs:** Policy decisions P1, P9, Section 6 assumptions, widget requirements.
  - **Input Files:** ["docs/ui-guides/widget_matrix.md", "docs/architecture/architecture_intake.md"]
  - **Target Files:** ["docs/ui-guides/consent_flows.mmd", "docs/ui-guides/consent_copy.md"]
  - **Deliverables:** Mermaid sequence diagram, copy matrix, banner text, error states, and event logging checklist.
  - **Acceptance Criteria:** Flow covers accepted/declined/partial merges, references audit table fields, includes accessibility copy, and ties to OpenAPI endpoints.
  - **Dependencies:** `I2.T1`
  - **Parallelizable:** Yes

<!-- anchor: task-i2-t5 -->
- **Task 2.5:**
  - **Task ID:** `I2.T5`
  - **Description:** Scaffold TypeScript/React mount architecture: define `mounts.ts` registry pattern, shared hooks, widget skeleton components, and telemetry wrappers. Provide coding standards (naming, props validation, data-component usage) for islands.
  - **Agent Type Hint:** `FrontendAgent`
  - **Inputs:** Build scaffolding (I1.T2), widget matrix, OpenAPI spec.
  - **Input Files:** ["README.md", "docs/ui-guides/widget_matrix.md", "api/openapi.yaml"]
  - **Target Files:** ["src/main/resources/META-INF/resources/assets/ts/mounts.ts", "docs/ui-guides/ts_architecture.md"]
  - **Deliverables:** Implementation-ready TypeScript registry skeleton, documentation describing mount lifecycle, instrumentation hooks, error boundaries, and theming approach.
  - **Acceptance Criteria:** `mounts.ts` compiles, auto-mount logic documented, sample widget stub commits, and doc references tokens + analytics requirements.
  - **Dependencies:** `I2.T2`
  - **Parallelizable:** Yes

<!-- anchor: task-i2-t6 -->
- **Task 2.6:**
  - **Task ID:** `I2.T6`
  - **Description:** Create AI Tagging & Feed Prioritization spec covering batching, budget enforcement, dedupe hashing, personalization ranking, and click tracking alignment for news widgets. Document fallback behaviors for budget thresholds per Policy P10.
  - **Agent Type Hint:** `DataArchitectureAgent`
  - **Inputs:** Job blueprint, widget matrix, AI budget policy.
  - **Input Files:** ["docs/architecture/job_orchestration.md", "docs/ui-guides/widget_matrix.md", "docs/ops/ai_budget_monitoring.md"]
  - **Target Files:** ["docs/architecture/ai_tagging_widget_spec.md"]
  - **Deliverables:** Spec describing queue usage, scoring formula, caching windows, instrumentation, and user-facing badges for untagged stories.
  - **Acceptance Criteria:** Document references AiUsageTracking fields, defines ranking weights, lists fallback UI cues, and enumerates monitoring metrics.
  - **Dependencies:** `I2.T1`
  - **Parallelizable:** Yes

<!-- anchor: task-i2-t7 -->
- **Task 2.7:**
  - **Task ID:** `I2.T7`
  - **Description:** Produce Homepage QA & Telemetry plan describing unit/integration tests, accessibility audits, click-tracking validation, and feature flag cohort testing for personalization features.
  - **Agent Type Hint:** `QAAgent`
  - **Inputs:** Widget matrix, OpenAPI spec, mount architecture doc.
  - **Input Files:** ["docs/ui-guides/widget_matrix.md", "api/openapi.yaml", "docs/ui-guides/ts_architecture.md"]
  - **Target Files:** ["docs/ops/homepage_validation_plan.md"]
  - **Deliverables:** Plan with test matrix, automation targets, manual exploratory guidelines, telemetry dashboards, and gating criteria for enabling widgets via feature flags.
  - **Acceptance Criteria:** Document covers accessibility/performance/security checks, enumerates instrumentation requirements, and ties each test grouping to policies P2, P5, P7, P14.
  - **Dependencies:** `I2.T5`
  - **Parallelizable:** No

- **Iteration Workstreams & Owners:**
  - Product/UX – handles widget backlog (I2.T1), consent flows (I2.T4), QA plan (I2.T7).
  - Backend/API – delivers OpenAPI spec (I2.T2), migration notes (I2.T3), AI tagging spec (I2.T6).
  - Frontend/Platform – builds mount scaffolding (I2.T5) and coordinates instrumentation guidelines.

- **Dependency Mapping:** I1 diagrams inform widget architecture; ERD + job blueprint feed directly into I2.T2–I2.T6; onboarding overview ensures contributors know where to place outputs.

- **Parallelization Guidance:** Product + backend flows (I2.T1/2/4/6) can run concurrently once intake is referenced; migration note (I2.T3) waits for spec finalization; QA plan (I2.T7) begins after mount architecture doc is stable.

- **Risks & Mitigations:**
  - *Spec creep:* Additional widgets may surface; mitigate by capturing stretch goals in backlog doc and keeping OpenAPI modular.
  - *Consent confusion:* Users may misinterpret merge prompts; mitigate with copy reviews + legal sign-off embedded in Task I2.T4.
  - *Data schema churn:* Changing preferences schema later is costly; mitigate with JSONB versioning plan (I2.T3) and migration playbook.
  - *Telemetry gaps:* Without instrumentation, personalization success cannot be measured; mitigate with QA plan requiring instrumentation sign-off before release.

- **Metrics & Validation Hooks:**
  - OpenAPI validated via Spectral; diff recorded in repo to ensure downstream generators trust spec.
  - TypeScript mount scaffolding compiled + linted; sample widget stub demonstrates data-component usage.
  - Consent flow diagrams signed by legal/compliance and stored with revision history.

- **Timeline & Milestones:**
  - Week 1: Finalize widget matrix + OpenAPI draft (I2.T1, I2.T2).
  - Week 2: Complete migration notes + consent flows (I2.T3, I2.T4).
  - Week 3: Deliver mount scaffolding + AI tagging spec (I2.T5, I2.T6).
  - Week 4: Publish QA/telemetry plan (I2.T7) and review overall readiness.

- **Review & Sign-off Participants:** Product lead (widget requirements), Backend lead (OpenAPI + migrations), Compliance lead (consent flows + telemetry plan), Frontend lead (TS architecture).

- **Hand-off Checklist:** Widget matrix + consent docs linked in README; OpenAPI tagged v1.0-draft; migration notes filed for I3 use; TypeScript scaffolding committed; QA plan added to ops docs.

- **Open Questions to Carry Forward:**
  - Determine if additional widgets (marketplace snippet, profile teaser) should join personalization scope or remain feature-flagged for later iterations.
  - Confirm copy/localization strategy for consent modal across jurisdictions beyond US/EU.
  - Decide on fallback behavior when AI budget halts tagging mid-cycle (visual indicator vs silent degrade) and capture in widget specs.
  - Validate whether anonymous personalization should persist across device families or remain browser-only (impacts cookie policies).

- **Iteration Exit Criteria:**
  - Widget matrix, consent flows, and QA plan approved by stakeholders and referenced from README.
  - OpenAPI v1 draft merged with validation reports, and migration note queued for I3 implementation tasks.
  - TypeScript mount scaffolding + AI tagging spec documented, enabling engineering squads to start implementation.
  - Telemetry + testing requirements baselined so feature flags can be enabled only when instrumentation is ready.
