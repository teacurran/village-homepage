<!-- anchor: iteration-plan -->
## 5. Iteration Plan

- **Total Iterations Planned:** 5
- **Iteration Dependencies:** I1 lays down architecture, tooling, and governance artifacts. I2 depends on I1 diagrams/specs for homepage + personalization. I3 consumes I1+I2 outputs for marketplace/API expansion. I4 builds on I2 widgets and I3 API infrastructure. I5 depends on all prior iterations to finalize analytics, compliance, and deployment readiness.

<!-- anchor: iteration-1-plan -->
### Iteration 1: Foundation & Architectural Baseline

- **Iteration ID:** `I1`
- **Goal:** Capture authoritative architecture artifacts, bootstrap build system + repo structure, and document queue/policy guardrails enabling downstream squads to work in parallel.
- **Prerequisites:** Requirements package provided in CLAUDE.md and Policy decisions P1–P14.
- **Iteration Narrative:** This kickoff cycle focuses on codifying the system blueprint so specialized agents can execute safely afterward. Outcomes include finalized diagrams, ERDs, queue charter, feature flag guidelines, and verified build scripts tying Java + TypeScript. Artifacts must be referenced by anchors listed in Section 2.1 so later tasks can cite them precisely. Every deliverable must explicitly map policies to domains to satisfy compliance traceability before implementation work begins.

<!-- anchor: task-i1-t1 -->
- **Task 1.1:**
  - **Task ID:** `I1.T1`
  - **Description:** Consolidate the policy decisions (P1–P14) and feature requirements into a single Architecture Intake brief summarizing scope, assumptions, compliance mandates, stakeholder roles, and open questions. Produce a traceability matrix linking every policy to relevant domains (widgets, marketplace, Good Sites, profiles, analytics) plus callouts for required ADRs.
  - **Agent Type Hint:** `DocumentationAgent`
  - **Inputs:** CLAUDE.md policies, existing standards from sibling repos, blueprint foundation Section 6 assumptions.
  - **Input Files:** ["CLAUDE.md"]
  - **Target Files:** ["docs/architecture/architecture_intake.md"]
  - **Deliverables:** Markdown brief with executive summary, policy-to-domain matrix, risk register, assumption log, and stakeholder sign-off blocks.
  - **Acceptance Criteria:** Document references all P1–P14 items, differentiates hard vs soft requirements, lists unknowns requiring ADRs, highlights dependencies per Section 6, and is cross-linked from README + future tasks.
  - **Dependencies:** None
  - **Parallelizable:** Yes

<!-- anchor: task-i1-t2 -->
- **Task 1.2:**
  - **Task ID:** `I1.T2`
  - **Description:** Establish repo scaffolding and build integration: ensure Maven `pom.xml` includes frontend plugin (Policy P8), TypeScript workspace, linting configs, esbuild entry points, and docker-compose stack for Postgres/PostGIS, Elasticsearch, Mailpit, MinIO, Jaeger. Document workflows for `./mvnw quarkus:dev`, `npm run watch`, docker-compose spin-up, and Sonar/lint hooks.
  - **Agent Type Hint:** `SetupAgent`
  - **Inputs:** Architecture intake, stack requirements, existing skeleton files.
  - **Input Files:** ["pom.xml", "package.json", "docker-compose.yml", "README.md"]
  - **Target Files:** ["pom.xml", "package.json", "esbuild.config.js", "tsconfig.json", "docker-compose.yml", "README.md"]
  - **Deliverables:** Updated build files, docs describing local dev workflow, `.editorconfig` additions aligning formatting, and verification script outputs appended to README.
  - **Acceptance Criteria:** Maven build succeeds with frontend plugin steps, esbuild entry points stubbed, TypeScript config established, docker-compose services defined with volumes + healthchecks, README instructions align with Section 2 stack, and lint/test stubs execute without failure.
  - **Dependencies:** `I1.T1`
  - **Parallelizable:** Partially

<!-- anchor: task-i1-t3 -->
- **Task 1.3:**
  - **Task ID:** `I1.T3`
  - **Description:** Produce system component + container diagrams (Mermaid/PlantUML) capturing ExperienceShell, domain services, shared utilities, delayed job orchestrator, worker pools, and external providers (Stripe, Meta, Cloudflare R2, Postgres, Elasticsearch, k3s). Include flow annotations for policies (P2 AI budget, P4 screenshots, P7 feature flags, P12 screenshot pool) and queue responsibilities.
  - **Agent Type Hint:** `DiagrammingAgent`
  - **Inputs:** Architecture intake, repo scaffolding knowledge, requirements for services.
  - **Input Files:** ["docs/architecture/architecture_intake.md"]
  - **Target Files:** ["docs/architecture/component_overview.mmd", "docs/architecture/deployment_views.puml"]
  - **Deliverables:** Two diagrams (component + container) with legends, policy annotations, and captions referencing anchors from Section 2.1.
  - **Acceptance Criteria:** Diagrams render without syntax errors, cover every major component listed in Section 2, highlight queue pods, label worker pods vs experience pods, and include callouts for external systems + security posture.
  - **Dependencies:** `I1.T1`
  - **Parallelizable:** Yes

<!-- anchor: task-i1-t4 -->
- **Task 1.4:**
  - **Task ID:** `I1.T4`
  - **Description:** Author comprehensive data model ERD covering users, feeds, ai_usage_tracking, marketplace, directory, profiles, feature flags, delayed jobs, analytics tables, geo datasets, screenshot versions, and inbound emails. Document JSONB payload columns, indexes, relationships, and retention windows within diagram notes.
  - **Agent Type Hint:** `DatabaseAgent`
  - **Inputs:** Architecture intake, component diagram, requirements Section F10–F14.
  - **Input Files:** ["docs/architecture/architecture_intake.md", "docs/architecture/component_overview.mmd"]
  - **Target Files:** ["docs/architecture/data_model.puml"]
  - **Deliverables:** PlantUML ERD file with grouped domains, legend for policy references, and exported PNG referenced from README + ops docs.
  - **Acceptance Criteria:** ERD depicts all tables enumerated in Section 2, includes JSONB columns, PostGIS indicators, partitioning callouts, screenshot retention notes, and cross-links to feature flag + click tracking tables.
  - **Dependencies:** `I1.T3`
  - **Parallelizable:** No

<!-- anchor: task-i1-t5 -->
- **Task 1.5:**
  - **Task ID:** `I1.T5`
  - **Description:** Draft Job Orchestration Blueprint describing queue definitions, handler contracts, payload schema versioning, retry policies, instrumentation requirements, backlog alert thresholds, and manual override procedures aligning with Policies P12, P10, F9, and analytics rollups.
  - **Agent Type Hint:** `DocumentationAgent`
  - **Inputs:** Component + container diagrams, requirements for jobs (Section F9, F13.13, F14.3, F14.8), ERD relationships.
  - **Input Files:** ["docs/architecture/deployment_views.puml", "docs/architecture/component_overview.mmd", "docs/architecture/data_model.puml"]
  - **Target Files:** ["docs/architecture/job_orchestration.md"]
  - **Deliverables:** Markdown blueprint with queue table, handler checklist, instrumentation guidelines, failure handling ladder, and references to AI budget + screenshot pool semantics.
  - **Acceptance Criteria:** Document lists DEFAULT/HIGH/LOW/BULK/SCREENSHOT queues, handler responsibilities, concurrency settings, idempotency rules, instrumentation hooks, alert triggers, and escalation process; reviewed with ops stakeholders.
  - **Dependencies:** `I1.T3`
  - **Parallelizable:** Yes

<!-- anchor: task-i1-t6 -->
- **Task 1.6:**
  - **Task ID:** `I1.T6`
  - **Description:** Define Feature Flag & Rate Limit governance package: schema extensions, evaluation flow, consent handling, rollout dashboard requirements, analytics sampling, cohort hashing formula, violation thresholds, and log retention policy aligning with Policies P7 and P14.
  - **Agent Type Hint:** `BackendAgent`
  - **Inputs:** Architecture intake, job blueprint (for evaluation logging), requirements for feature flags + rate limiting, ERD relationships.
  - **Input Files:** ["docs/architecture/architecture_intake.md", "docs/architecture/job_orchestration.md", "docs/architecture/data_model.puml"]
  - **Target Files:** ["docs/ops/feature_flag_playbook.md", "docs/ops/rate_limit_playbook.md"]
  - **Deliverables:** Two Markdown playbooks describing schema fields, evaluation algorithm pseudo-code, consent fallback handling, analytics logging pipeline, violation handling, admin UI expectations, and future automation notes.
  - **Acceptance Criteria:** Documents map to Section 2 components, include hashing formula, consent fallback logic, rate tier table (anonymous/logged-in/trusted), alert triggers, cross-links to click tracking + analytics modules, and guidance for GDPR purge jobs.
  - **Dependencies:** `I1.T1`
  - **Parallelizable:** Yes

<!-- anchor: task-i1-t7 -->
- **Task 1.7:**
  - **Task ID:** `I1.T7`
  - **Description:** Prepare README + onboarding supplement summarizing outputs from I1 (diagrams, ERD, job + flag playbooks) and establishing reference anchors for future iterations; include contribution guidelines (branching, ADR process, lint/test expectations, policy references) and architecture digest.
  - **Agent Type Hint:** `DocumentationAgent`
  - **Inputs:** All artifacts from I1 tasks.
  - **Input Files:** ["README.md", "docs/architecture/architecture_intake.md", "docs/architecture/component_overview.mmd", "docs/architecture/data_model.puml", "docs/architecture/job_orchestration.md", "docs/ops/feature_flag_playbook.md", "docs/ops/rate_limit_playbook.md"]
  - **Target Files:** ["README.md", "docs/ops/onboarding_overview.md"]
  - **Deliverables:** Updated README referencing anchors, onboarding overview enumerating available diagrams/docs, link table to future iteration tasks, and checklist for verifying compliance references.
  - **Acceptance Criteria:** README includes quick links to sections 1–4 artifacts, describes iteration cadence, references docker-compose + build steps, highlights compliance obligations, and instructs contributors on ADR + feature flag requirements.
  - **Dependencies:** `I1.T2`, `I1.T3`, `I1.T4`, `I1.T5`, `I1.T6`
  - **Parallelizable:** No

- **Iteration Workstreams & Owners:**
  - Architecture & Compliance – architecture pod handles I1.T1, I1.T3, I1.T4, I1.T5 and coordinates approvals.
  - Build & Tooling – platform pod delivers I1.T2 and ensures CI proof documented in onboarding supplement.
  - Governance & Ops – ops pod leads I1.T6 and wraps up I1.T7 referencing enforcement runbooks.

- **Risks & Mitigations:**
  - *Scope overload:* Requirements breadth may inflate diagrams; mitigate via assumption log + deferral list in intake doc.
  - *Toolchain drift:* Frontend build integration could reintroduce stale config; mitigate by wiring CI smoke scripts verifying `npm run typecheck` and capturing logs in README.
  - *Compliance ambiguity:* GDPR merge flow details still emerging; mitigate by tagging ambiguous steps in intake doc and scheduling ADR in I2 backlog.
  - *Queue misalignment:* Without clear orchestration doc, later teams could misuse queues; mitigate via enforcement checklist + gating criteria in job blueprint.

- **Metrics & Validation Hooks:**
  - Successful execution of `./mvnw package` + `npm run build` documented via console summary stored in onboarding notes.
  - Diagrams + ERD validated through CI PlantUML/Mermaid lint outputs appended to PR description template.
  - Governance docs reviewed with stakeholders (sign-off table inside architecture intake) and tracked via checklist in onboarding supplement.

- **Timeline & Milestones:**
  - Days 1–2: Complete policy intake (I1.T1) and build scaffolding spike (I1.T2) to unblock diagrams.
  - Days 3–4: Produce component/container diagrams and ERD (I1.T3, I1.T4), circulate for review.
  - Day 5: Finalize job orchestration + governance playbooks (I1.T5, I1.T6), capture review notes.
  - Day 6: Refresh README/onboarding (I1.T7), conduct cross-team walkthrough to confirm readiness for I2 kickoff.

- **Review & Sign-off Participants:**
  - Architecture Lead – validates diagrams/ERD alignment with policies and approves intake.
  - Platform Lead – validates build + docker-compose instructions, confirms CI integrations.
  - Ops/Compliance Lead – reviews governance docs, ensures references to policies P7, P10, P12, P14 are complete before closing iteration.

- **Hand-off Checklist:**
  - ✅ Architecture intake linked from README + plan anchors.
  - ✅ Diagrams + ERD referenced in docs + future tasks with stable filenames.
  - ✅ Job + governance playbooks stored under `docs/architecture` / `docs/ops` with cross-links.
  - ✅ Build + docker-compose instructions verified on clean environment and recorded for downstream agents.
  - ✅ Risks/resolutions exported to planning board for monitoring.

- **Iteration Exit Criteria:**
  - Architecture intake approved and archived; diagrams + ERD checked into repo with anchor references.
  - Job orchestration + feature flag/rate limit playbooks published and cross-referenced by README + onboarding overview.
  - Build tooling validated via `./mvnw package` and `npm run build`, docker-compose stack documented with env vars + health checks.
  - Risks + open questions captured in intake doc and flagged for follow-up in I2 planning sessions.
