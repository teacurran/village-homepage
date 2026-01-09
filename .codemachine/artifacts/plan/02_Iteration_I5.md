<!-- anchor: iteration-5-plan -->
### Iteration 5: Analytics, Compliance, and Deployment Readiness

- **Iteration ID:** `I5`
- **Goal:** Finalize analytics dashboards, click tracking rollups, AI budget controls, compliance/export tooling, observability pipelines, and release procedures so implementation teams can execute confidently.
- **Prerequisites:** Outputs from I1–I4 (architecture docs, OpenAPI spec, marketplace/directory plans, QA/telemetry plan, jobs matrix, consent flows, screenshot blueprint) plus policy approval stakeholders.
- **Iteration Narrative:** I5 aligns ops, analytics, and compliance deliverables. It defines admin analytics views, click rollup automation, AI budget enforcement, GDPR export/deletion workflows, CI/CD + beta rollout runbooks, observability instrumentation, and the master integration checklist. Every policy (P1–P14) is mapped to a concrete runbook before development begins.

<!-- anchor: task-i5-t1 -->
- **Task 5.1:**
  - **Task ID:** `I5.T1`
  - **Description:** Draft Analytics Dashboard specification for the admin portal covering click stats, AI usage, job health, rate limits, and marketplace/directory KPIs. Detail REST endpoints, filter behavior, caching, and AntV visualization choices.
  - **Agent Type Hint:** `ProductDocumentationAgent`
  - **Inputs:** Click tracking schema, AI budget requirements, rate limit playbook.
  - **Input Files:** ["docs/ops/homepage_validation_plan.md", "docs/architecture/job_orchestration.md", "docs/ops/feature_flag_playbook.md"]
  - **Target Files:** ["docs/analytics/admin_dashboard_spec.md"]
  - **Deliverables:** Spec listing charts/tables, KPI formulas, data sources, sample payloads, and UX wireframes.
  - **Acceptance Criteria:** Document details overview, per-category, top items, traffic sources, job health, and AI budget panels referencing policy IDs + thresholds.
  - **Dependencies:** `I2.T7`, `I3.T6`, `I4.T6`
  - **Parallelizable:** Yes

<!-- anchor: task-i5-t2 -->
- **Task 5.2:**
  - **Task ID:** `I5.T2`
  - **Description:** Define click tracking rollup + retention automation (partitions, SQL, schedules, monitoring, export API) supporting dashboard requirements and compliance retention windows.
  - **Agent Type Hint:** `DataArchitectureAgent`
  - **Inputs:** ERD, job blueprint, analytics spec.
  - **Input Files:** ["docs/architecture/data_model.puml", "docs/analytics/admin_dashboard_spec.md", "docs/architecture/job_orchestration.md"]
  - **Target Files:** ["docs/analytics/click_rollup_plan.md"]
  - **Deliverables:** Plan covering raw partitions, rollup jobs, retention enforcement, export interface, troubleshooting steps, and validation queries.
  - **Acceptance Criteria:** Document references `link_clicks`, `click_stats_daily`, `click_stats_daily_items`, includes SQL, cron schedules, alert thresholds, and testing checklists.
  - **Dependencies:** `I5.T1`
  - **Parallelizable:** No

<!-- anchor: task-i5-t3 -->
- **Task 5.3:**
  - **Task ID:** `I5.T3`
  - **Description:** Produce AI budget monitoring + enforcement runbook expanding Policies P2/P10 with instrumentation, alerting, throttling scripts, override approvals, and monthly reset tasks.
  - **Agent Type Hint:** `OpsDocumentationAgent`
  - **Inputs:** AiUsageTracking schema, job blueprint, analytics spec.
  - **Input Files:** ["docs/architecture/job_orchestration.md", "docs/ops/ai_budget_monitoring.md"]
  - **Target Files:** ["docs/ops/ai_budget_runbook.md"]
  - **Deliverables:** Runbook covering dashboards, alert channels (75/90/100%), throttle actions (batch reduction, queue pause, hard stop), override logging, and compliance reporting.
  - **Acceptance Criteria:** Document lists automation scripts, CLI examples, escalation contacts, and integration with feature flag toggles + release plan.
  - **Dependencies:** `I5.T1`
  - **Parallelizable:** Yes

<!-- anchor: task-i5-t4 -->
- **Task 5.4:**
  - **Task ID:** `I5.T4`
  - **Description:** Finalize GDPR/CCPA export + deletion workflow referencing Policies P1/P14: endpoints, delayed job payloads, R2 bundle storage, notification templates, consent record updates, and audit trails.
  - **Agent Type Hint:** `ComplianceAgent`
  - **Inputs:** Consent flows, storage blueprint, jobs matrix.
  - **Input Files:** ["docs/ui-guides/consent_flows.mmd", "docs/architecture/job_orchestration.md", "docs/good-sites/jobs_matrix.md"]
  - **Target Files:** ["docs/ops/compliance_export_plan.md"]
  - **Deliverables:** Workflow doc with diagrams, DTO schemas, retention schedule, manual review steps, and SLA tracking.
  - **Acceptance Criteria:** Document covers export packaging, deletion cascade, purge confirmation, legal approvals, and integration with click tracking + feature flag evaluation purge jobs.
  - **Dependencies:** `I4.T7`
  - **Parallelizable:** Yes

<!-- anchor: task-i5-t5 -->
- **Task 5.5:**
  - **Task ID:** `I5.T5`
  - **Description:** Author CI/CD + release runbook describing pipeline stages, promotion flow (dev→beta→prod), feature flag rollout strategy, smoke/regression tests, rollback procedures, and communication plan (beta announcements, incident updates).
  - **Agent Type Hint:** `InfrastructureAgent`
  - **Inputs:** Build tooling docs, QA plan, feature flag playbook.
  - **Input Files:** ["README.md", "docs/ops/homepage_validation_plan.md", "docs/ops/feature_flag_playbook.md"]
  - **Target Files:** ["docs/ops/release_runbook.md"]
  - **Deliverables:** Runbook with pipeline diagrams, gate criteria, checklists, on-call expectations, and message templates.
  - **Acceptance Criteria:** Document references Jib/TypeScript builds, sonar/lint/test gating, feature flag toggles, rollback commands, and go/no-go rules tied to dashboards.
  - **Dependencies:** `I2.T5`, `I2.T7`
  - **Parallelizable:** Yes

<!-- anchor: task-i5-t6 -->
- **Task 5.6:**
  - **Task ID:** `I5.T6`
  - **Description:** Expand observability + incident response plan covering logging schema, metrics catalog, tracing coverage, alert thresholds, on-call rotation, and incident workflow mapping.
  - **Agent Type Hint:** `SREAgent`
  - **Inputs:** Job blueprint, analytics spec, payments blueprint.
  - **Input Files:** ["docs/architecture/job_orchestration.md", "docs/analytics/admin_dashboard_spec.md", "docs/marketplace/payments_blueprint.md"]
  - **Target Files:** ["docs/ops/observability_plan.md"]
  - **Deliverables:** Plan mapping metrics/logs/traces to dashboards, severity thresholds, escalation chain, and runbook cross-references.
  - **Acceptance Criteria:** Document lists metrics per subsystem (homepage, marketplace, Good Sites, profiles, AI tagging, payments), defines severity levels, alert channels, and detection→response SLAs.
  - **Dependencies:** `I5.T1`, `I5.T2`
  - **Parallelizable:** No

<!-- anchor: task-i5-t7 -->
- **Task 5.7:**
  - **Task ID:** `I5.T7`
  - **Description:** Compile final integration checklist + plan manifest updates referencing all artifacts, verifying README + onboarding anchors, capturing residual risks, and preparing implementation backlog references.
  - **Agent Type Hint:** `DocumentationAgent`
  - **Inputs:** All iteration outputs, release runbook, compliance plan.
  - **Input Files:** ["README.md", "docs/analytics/admin_dashboard_spec.md", "docs/ops/release_runbook.md", "docs/ops/compliance_export_plan.md"]
  - **Target Files:** ["docs/ops/integration_checklist.md", "README.md"]
  - **Deliverables:** Checklist linking to every doc, policy mapping table, open ADR placeholders, README updates, and manifest instructions.
  - **Acceptance Criteria:** Checklist confirms artifacts exist, anchors recorded for plan_manifest, risks logged with owners, and backlog epics noted.
  - **Dependencies:** `I5.T1`–`I5.T6`
  - **Parallelizable:** No

- **Iteration Workstreams & Owners:** Analytics pod (5.1–5.2), Ops/Compliance pod (5.3–5.4), Platform/SRE pod (5.5–5.6), Documentation/PMO pod (5.7).
- **Dependency Mapping:** Analytics spec powers rollup + observability docs; AI budget runbook leverages analytics metrics; compliance export plan reuses job matrix/storage blueprint; release runbook references QA + feature flag docs; integration checklist consolidates policy coverage.
- **Parallelization Guidance:** Execute analytics/compliance docs first; rollup + observability wait for spec; release runbook depends on QA references; integration checklist finalizes once runbooks are signed off.
- **Risks & Mitigations:** Analytics scope creep (version outputs, backlog extras), compliance ambiguity (legal sign-off fields), release misalignment (pipeline dry run), alert fatigue (severity tiers + runbooks), manifest drift (single checklist owner).
- **Metrics & Validation Hooks:** Validate rollup SQL on sample partitions, simulate AI budget alerts, run mock export/deletion job, execute CI dry run + rollback scenario, and ensure dashboards render synthetic data.
- **Timeline & Milestones:** Week1 analytics spec + rollup draft; Week2 AI budget + compliance plans; Week3 release runbook + observability plan; Week4 integration checklist, beta rollout outline, risk review.
- **Stakeholder Communication Plan:** Weekly Analytics/Ops/Platform sync; legal/compliance review session; release runbook walkthrough with engineering leads; recorded final presentation for onboarding.
- **Beta Rollout Strategy Outline:** Define cohorts, monitoring gates, success metrics, feature flag sequencing, rollback triggers, and messaging templates; capture in release runbook appendix.
- **Monitoring Deliverables:** Observability plan must reference dashboards for click stats, AI budget, queue depth, Stripe webhooks, screenshot backlog, rate limit violations, Good Sites/profile KPIs, with Grafana/Prometheus panel IDs and owners.
- **Integration with Ops Tooling:** Document how analytics dashboards appear in admin portal, how alerts reach PagerDuty/Slack/email, how compliance exports create tickets, and how release messaging ties into Ops channels.
- **Documentation Deliverable Map:** Integration checklist enumerates each doc with paths and policy references; README quick-links updated accordingly.
- **Risk Register Updates:** Identify unresolved risks (audit cadence, BI integration, legal dependencies, staffing) and assign owners/dates in integration checklist.
- **Post-Iteration Actions:** Schedule engineering kickoff, create Jira epics for runbooks, set quarterly review cadence for analytics/compliance docs, and onboard on-call rotation using observability plan.
- **Review & Sign-off Participants:** Analytics lead, Ops lead, Compliance/legal counsel, Platform lead, Product/Engineering directors.
- **Hand-off Checklist:** Docs merged, README anchors updated, plan manifest extended, backlog tickets created, beta rollout plan circulated, risk log updated.
- **Governance Alignment Notes:** Policies P1, P2, P3, P4, P7, P10, P12, P14 explicitly mapped to runbooks; integration checklist includes policy↔doc table for auditors.
- **Testing & QA Alignment:** QA updates telemetry suites per analytics/observability specs, ensures release runbook includes smoke/regression criteria, and validates compliance export/deletion flows.
- **Success Metrics for I5:** Dashboard KPIs accepted, runbooks reviewed without major blockers, mock exports succeed within SLA, and CI dry run completes end-to-end.
- **Open Questions to Track:** Beta-to-prod percentages, external audit cadence, corporate BI integration, automation scope for compliance approvals, ops staffing for on-call rotations.
- **Iteration Exit Criteria:** Analytics, compliance, release, and observability docs approved; README + plan_manifest updated; integration checklist + risk register completed; beta rollout plan endorsed; on-call + alert ownership assigned; implementation kickoff scheduled.
