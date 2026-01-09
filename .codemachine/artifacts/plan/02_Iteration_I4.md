<!-- anchor: iteration-4-plan -->
### Iteration 4: Good Sites Directory & Public Profiles Enablement

- **Iteration ID:** `I4`
- **Goal:** Design the Good Sites directory architecture, voting/karma systems, screenshot workflows, and public profile templates so crews can implement curation, moderation, and profile publishing flows.
- **Prerequisites:** Outputs from I1–I3 (architecture docs, OpenAPI, marketplace specs) plus AI tagging and widget plans.
- **Iteration Narrative:** This iteration covers Good Sites category management, site submission & AI categorization, screenshot retention + job orchestration, voting/karma rules, moderation tooling, profile template configs (`public_homepage`, `your_times`, `your_report`), curated article flows, SEO requirements, and public profile publishing/analytics. Deliverables emphasize documentation, diagrams, schema notes, and process guides enabling parallel implementation.

<!-- anchor: task-i4-t1 -->
- **Task 4.1:**
  - **Task ID:** `I4.T1`
  - **Description:** Produce Good Sites category & submission spec describing category schema, slug strategy, moderation roles, karma thresholds, and site submission payloads referencing policies F13.1–F13.7.
  - **Agent Type Hint:** `ProductDocumentationAgent`
  - **Inputs:** Requirements (F13), ERD, feature flag playbook.
  - **Input Files:** ["CLAUDE.md", "docs/architecture/data_model.puml", "docs/ops/feature_flag_playbook.md"]
  - **Target Files:** ["docs/good-sites/category_submission_spec.md"]
  - **Deliverables:** Spec covering data fields, validation rules, duplicate detection, submission workflows, and ties to AI categorization + screenshot jobs.
  - **Acceptance Criteria:** Document enumerates workflows for trusted/untrusted users, reserved usernames, and moderation access control; includes diagrams for submission flow.
  - **Dependencies:** `I1.T4`
  - **Parallelizable:** Yes

<!-- anchor: task-i4-t2 -->
- **Task 4.2:**
  - **Task ID:** `I4.T2`
  - **Description:** Author screenshot capture + metadata refresh blueprint for Good Sites: jvppeteer pool usage, queue selection (SCREENSHOT/BULK), version retention (Policy P4), WebP conversion, and fallback logic when capture fails.
  - **Agent Type Hint:** `InfrastructureAgent`
  - **Inputs:** Job blueprint, storage spec, Policy P4.
  - **Input Files:** ["docs/architecture/job_orchestration.md", "docs/architecture/component_overview.mmd"]
  - **Target Files:** ["docs/good-sites/screenshot_pipeline.md"]
  - **Deliverables:** Blueprint with sequence diagrams, pool sizing recommendations, failure handling, R2 key patterns, and admin tooling for manual recapture.
  - **Acceptance Criteria:** Document references concurrency controls, retention policy, alerting, CLI commands for requeueing, and cross-links to Ops dashboards.
  - **Dependencies:** `I1.T5`
  - **Parallelizable:** Yes

<!-- anchor: task-i4-t3 -->
- **Task 4.3:**
  - **Task ID:** `I4.T3`
  - **Description:** Define voting, karma, and moderation playbook for Good Sites (Policies F13.4–F13.7). Include scoring formula, karma thresholds (trusted/editor/moderator), moderation queue UI requirements, and flag resolution flow.
  - **Agent Type Hint:** `OpsDocumentationAgent`
  - **Inputs:** Category spec, AI tagging spec, rate limit doc.
  - **Input Files:** ["docs/good-sites/category_submission_spec.md", "docs/architecture/ai_tagging_widget_spec.md", "docs/ops/rate_limit_playbook.md"]
  - **Target Files:** ["docs/good-sites/voting_moderation_playbook.md"]
  - **Deliverables:** Playbook describing state transitions, auto-hide rules, karma adjustments, leaderboard tie-in, and analytics/reporting expectations.
  - **Acceptance Criteria:** Document ties metrics to `directory_votes`, `directory_flags`, includes SLA targets, and references GDPR retention for evaluation logs.
  - **Dependencies:** `I4.T1`
  - **Parallelizable:** Yes

<!-- anchor: task-i4-t4 -->
- **Task 4.4:**
  - **Task ID:** `I4.T4`
  - **Description:** Create profile template configuration spec covering `public_homepage`, `your_times`, `your_report`: layout grids, configurable blocks, curated article slots, color/theme tokens, SEO metadata, and privacy controls.
  - **Agent Type Hint:** `UXDocumentationAgent`
  - **Inputs:** Requirements F11, widget matrix, AI tagging spec.
  - **Input Files:** ["CLAUDE.md", "docs/ui-guides/widget_matrix.md"]
  - **Target Files:** ["docs/profiles/template_spec.md"]
  - **Deliverables:** Template spec with diagrams, data schemas (template_config), slot definitions, validation rules, copy guidance, and accessibility notes.
  - **Acceptance Criteria:** Document details slot types, customization limits, feature flags, and references curated article data models.
  - **Dependencies:** `I2.T1`
  - **Parallelizable:** Yes

<!-- anchor: task-i4-t5 -->
- **Task 4.5:**
  - **Task ID:** `I4.T5`
  - **Description:** Define curated article pipeline spec for public profiles (`profile_curated_articles`): feed selection UI, manual link ingestion, AI metadata fetching, slot assignment, moderation guidelines, and audit logging.
  - **Agent Type Hint:** `ProductDocumentationAgent`
  - **Inputs:** Profile template spec, feed aggregation spec, AI tagging doc.
  - **Input Files:** ["docs/profiles/template_spec.md", "docs/architecture/ai_tagging_widget_spec.md", "docs/architecture/data_model.puml"]
  - **Target Files:** ["docs/profiles/curation_pipeline.md"]
  - **Deliverables:** Pipeline doc with flowcharts, DTO mappings, caching strategy, and instrumentation requirements.
  - **Acceptance Criteria:** Document explains curated article states, data retention, job triggers, and cross-links to click tracking + export flows.
  - **Dependencies:** `I4.T4`
  - **Parallelizable:** No

<!-- anchor: task-i4-t6 -->
- **Task 4.6:**
  - **Task ID:** `I4.T6`
  - **Description:** Draft SEO + analytics guide for Good Sites and profiles: meta tags, JSON-LD, sitemap entries, `/track/click` usage, profile view aggregation jobs, and admin dashboards for directory performance.
  - **Agent Type Hint:** `SEOAgent`
  - **Inputs:** Requirements F11.9, Section F14.5, click tracking spec.
  - **Input Files:** ["CLAUDE.md", "docs/architecture/component_overview.mmd", "docs/ops/homepage_validation_plan.md"]
  - **Target Files:** ["docs/good-sites/seo_analytics_guide.md"]
  - **Deliverables:** Guide describing meta tag templates, JSON-LD payloads, sitemap structure, analytics KPIs, and instrumentation tasks.
  - **Acceptance Criteria:** Document covers public profile pages, directory categories, profile directories, includes sample JSON-LD, and references click tracking retention policies.
  - **Dependencies:** `I2.T7`
  - **Parallelizable:** Yes

<!-- anchor: task-i4-t7 -->
- **Task 4.7:**
  - **Task ID:** `I4.T7`
  - **Description:** Produce job + background process matrix for Good Sites and profiles: screenshot refresh cadence, metadata refresh, link health checks, profile view aggregators, curated article expiration, and export/deletion hooks.
  - **Agent Type Hint:** `InfrastructureAgent`
  - **Inputs:** Job blueprint, screenshot spec, SEO guide.
  - **Input Files:** ["docs/architecture/job_orchestration.md", "docs/good-sites/screenshot_pipeline.md", "docs/good-sites/seo_analytics_guide.md"]
  - **Target Files:** ["docs/good-sites/jobs_matrix.md"]
  - **Deliverables:** Matrix describing queues, payload fields, retry logic, instrumentation, and KPI thresholds per job type.
  - **Acceptance Criteria:** Document lists every job from Good Sites/profiles requirements, references Policy P12, and integrates with AI tagging + screenshot budgets.
  - **Dependencies:** `I4.T2`, `I4.T6`
  - **Parallelizable:** No

- **Iteration Workstreams & Owners:**
  - Directory Experience pod (Tasks 4.1–4.3) oversees category, submission, voting, moderation.
  - Profile Experience pod (Tasks 4.4–4.6) handles templates, curation, SEO analytics.
  - Platform/Infra pod (Task 4.2, 4.7) manages screenshot + job orchestration alignment.

- **Dependency Mapping:** AI tagging outputs from I2 feed Good Sites categorization; screenshot pipelines reuse StorageGateway policies from I3; profile curation depends on feed items + marketplace listings for embed references; SEO guide influences I5 analytics tasks.

- **Parallelization Guidance:** Category spec + screenshot blueprint start concurrently; template + curation docs follow once widget matrix confirmed; jobs matrix waits for screenshot + SEO docs to finalize; QA/test planning derived from SEO guide.

- **Risks & Mitigations:**
  - Complex karma rules (document and simulate scenarios to avoid user confusion).
  - Screenshot cost overruns (define throttle + instrumentation in blueprint).
  - Profile template scope creep (lock MVP features + capture backlog items separately).
  - SEO compliance (review with marketing/legal before publishing templates).

- **Metrics & Validation Hooks:**
  - Run sample screenshot job in dev, record timing + storage usage in blueprint.
  - Create sample template configs + curated article JSON to validate schema.
  - Verify SEO guide with Lighthouse/Google Rich Results tests using mock data.
  - Job matrix includes monitoring KPIs (backlog depth, failure counts) for ops dashboards.

- **Timeline & Milestones:**
  - Week 1: Category + screenshot specs drafted.
  - Week 2: Voting/mode playbook + template spec delivered.
  - Week 3: Curation pipeline + SEO guide completed.
  - Week 4: Jobs matrix finalized; iteration walkthrough with stakeholders.

- **Review & Sign-off Participants:** Content/Community lead, UX lead, Platform lead, Compliance/privacy owner, Ops lead.

- **Hand-off Checklist:** All docs stored under `docs/good-sites/` or `docs/profiles/`, README anchor updates, backlog items created for engineering tasks (UI + backend), QA plan updated with new sections.

- **Governance Alignment Notes:**
  - Policy P4 applied to Good Sites screenshots.
  - Policy P7 feature flags used for enabling templates + directory exposures.
  - Policy P10 AI budget impacts Good Sites AI categorization script.
  - Policy P14 influences profile analytics + directory votes retention.

- **Testing & QA Alignment:** Outline manual + automated tests covering submissions, karma transitions, screenshot fallback, template editing, SEO validation, click analytics; coordinate with QA doc from I2 for instrumentation.

- **Open Questions to Track:** Internationalization timeline for directory; embed whitelist updates; profile view privacy toggles; integration with marketplace listings for curated content.

- **Iteration Exit Criteria:**
  - Good Sites + profile specs approved with anchors referenced in README.
  - Screenshot blueprint + job matrix validated with ops stakeholders.
  - SEO/analytics guide accepted by marketing/legal; instrumentation tasks created.
  - Feature flag + QA plans updated to include directory/profile rollout gating.
