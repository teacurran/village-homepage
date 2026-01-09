<!-- anchor: iteration-3-plan -->
### Iteration 3: Marketplace Domain & Payment Architecture

- **Iteration ID:** `I3`
- **Goal:** Define marketplace data models, search flows, payments, moderation, communications, and background processing so engineering teams can implement listings, promotions, and masked messaging with confidence.
- **Prerequisites:** I1 artifacts (architecture intake, ERD, job/gov docs) plus I2 outputs (OpenAPI draft, migration notes, AI tagging spec, mount scaffolding).
- **Iteration Narrative:** Marketplace functionality spans taxonomy ingestion, PostGIS radius search, Elasticsearch ranking, listing lifecycle (draft→active→expired), screenshot/image workflows, Stripe posting fees/promotions, refund/fraud compliance (Policy P3), masked email relays, and moderation/flagging. This iteration delivers specs, migration notes, payment blueprints, and ops playbooks so subsequent engineering iterations can code features with minimal ambiguity.

<!-- anchor: task-i3-t1 -->
- **Task 3.1:**
  - **Task ID:** `I3.T1`
  - **Description:** Document marketplace taxonomy import strategy mapping the provided hierarchical list into `marketplace_categories`. Define slug rules, sorting, reserved keywords, seeding tooling, and integration with dr5hn geo dataset for location tags.
  - **Agent Type Hint:** `DataArchitectureAgent`
  - **Inputs:** Requirements F12.3, ERD, migration guidance.
  - **Input Files:** ["CLAUDE.md", "docs/architecture/data_model.puml"]
  - **Target Files:** ["docs/marketplace/taxonomy_plan.md", "migrations/notes/iteration3_taxonomy.md"]
  - **Deliverables:** Taxonomy plan + migration note describing parent-child structure, slug patterns, translation/internationalization considerations, and CLI/SQL seeding instructions.
  - **Acceptance Criteria:** Document lists full tree, numbering scheme, future expansion approach, and cross-links to UI/UX plan; migration note outlines idempotent seeding script with rollback steps.
  - **Dependencies:** `I2.T3`
  - **Parallelizable:** Yes

<!-- anchor: task-i3-t2 -->
- **Task 3.2:**
  - **Task ID:** `I3.T2`
  - **Description:** Produce PostGIS + Elasticsearch search specification detailing radius handling, bounding box approximations, sort orders, filters, caching, and instrumentation. Provide SQL/DSL examples and index requirements.
  - **Agent Type Hint:** `BackendAgent`
  - **Inputs:** ERD, architecture diagrams, policy P6/P11, requirements F12.2, F14.8.
  - **Input Files:** ["docs/architecture/data_model.puml", "docs/architecture/component_overview.mmd"]
  - **Target Files:** ["docs/marketplace/search_spec.md"]
  - **Deliverables:** Spec with diagrams, sample queries, fallback strategy, and API contract alignment with `MarketplaceSearchType`.
  - **Acceptance Criteria:** Document covers radius options (5–250 miles), PostGIS indexes + queries, Elasticsearch analyzers, ranking weights, caching windows, and instrumentation plan tied to click tracking.
  - **Dependencies:** `I3.T1`
  - **Parallelizable:** Partially

<!-- anchor: task-i3-t3 -->
- **Task 3.3:**
  - **Task ID:** `I3.T3`
  - **Description:** Extend OpenAPI + DTO definitions for marketplace listing CRUD, promotions, contact masked email flows, moderation actions, and refund endpoints. Capture validation rules, feature flag hooks, and error responses.
  - **Agent Type Hint:** `BackendAgent`
  - **Inputs:** Existing OpenAPI, taxonomy plan, policy P3.
  - **Input Files:** ["api/openapi.yaml", "docs/marketplace/taxonomy_plan.md", "docs/ops/feature_flag_playbook.md"]
  - **Target Files:** ["api/openapi.yaml", "docs/marketplace/listing_contract.md"]
  - **Deliverables:** Updated OpenAPI spec plus listing contract describing state machine (draft/pending/active/flagged/expired/sold), DTO fields, and samples.
  - **Acceptance Criteria:** Spec validated, new schemas added, endpoints documented with rate-limit headers, listing contract cross-references policies and queue usage for status transitions.
  - **Dependencies:** `I3.T2`
  - **Parallelizable:** No

<!-- anchor: task-i3-t4 -->
- **Task 3.4:**
  - **Task ID:** `I3.T4`
  - **Description:** Design Stripe integration blueprint covering posting fees, promotion purchases, webhook lifecycle, refund triggers, chargeback handling, and evidence preservation per Policy P3. Include diagrams, metadata mapping, and alerting requirements.
  - **Agent Type Hint:** `PaymentsAgent`
  - **Inputs:** Requirements F12.8, Policy P3, ERD.
  - **Input Files:** ["CLAUDE.md", "docs/architecture/data_model.puml"]
  - **Target Files:** ["docs/marketplace/payments_blueprint.md"]
  - **Deliverables:** Payment blueprint with flowcharts, metadata tables, webhook handler pseudo-code, failure recovery steps, and admin dashboard requirements.
  - **Acceptance Criteria:** Document references `payment_refunds`, `listing_promotions`, Stripe event names, budgets, sandbox/test plan, and compliance controls.
  - **Dependencies:** `I3.T3`
  - **Parallelizable:** Yes

<!-- anchor: task-i3-t5 -->
- **Task 3.5:**
  - **Task ID:** `I3.T5`
  - **Description:** Define listing image pipeline: upload limits, file validation, Cloudflare R2 key structure, thumbnail generation, BULK queue handler design, CDN invalidation, retention/cleanup when listings expire or are removed.
  - **Agent Type Hint:** `StorageAgent`
  - **Inputs:** StorageGateway spec, job blueprint, policy P4.
  - **Input Files:** ["docs/architecture/job_orchestration.md", "docs/architecture/component_overview.mmd"]
  - **Target Files:** ["docs/marketplace/image_pipeline.md"]
  - **Deliverables:** Pipeline doc + PlantUML sequence showing upload→queue→processing→R2 storage→CDN; includes error handling + monitoring.
  - **Acceptance Criteria:** Document covers concurrency, timeouts, thumbnail/full-size specs, cleanup job frequency, and instrumentation for processing duration + failures.
  - **Dependencies:** `I3.T3`
  - **Parallelizable:** Yes

<!-- anchor: task-i3-t6 -->
- **Task 3.6:**
  - **Task ID:** `I3.T6`
  - **Description:** Draft moderation, flagging, and fraud detection playbook mapping Policy P3 heuristics. Outline AI scoring, manual queues, thresholds, notification templates, and integration with rate limit + feature flag audits.
  - **Agent Type Hint:** `OpsDocumentationAgent`
  - **Inputs:** Requirements F12.9, AI tagging spec, policy table.
  - **Input Files:** ["CLAUDE.md", "docs/architecture/ai_tagging_widget_spec.md"]
  - **Target Files:** ["docs/marketplace/moderation_playbook.md"]
  - **Deliverables:** Playbook describing submission review states, flag categories, automated actions, SLA targets, and reporting metrics.
  - **Acceptance Criteria:** Document ties heuristics to data points, defines auto-hide thresholds, describes manual override process, and references audit logging + delayed job usage.
  - **Dependencies:** `I3.T3`
  - **Parallelizable:** Yes

<!-- anchor: task-i3-t7 -->
- **Task 3.7:**
  - **Task ID:** `I3.T7`
  - **Description:** Specify masked email relay + inbound processing architecture referencing Policy F14.3: email address schema, masked tokens, Mailpit dev setup, inbound queue handler, spam filtering, rate limiting, logging, and analytics.
  - **Agent Type Hint:** `IntegrationAgent`
  - **Inputs:** Requirements F12.6, job blueprint, rate limit plan.
  - **Input Files:** ["docs/architecture/job_orchestration.md", "docs/ops/rate_limit_playbook.md", "docs/architecture/data_model.puml"]
  - **Target Files:** ["docs/marketplace/messaging_spec.md"]
  - **Deliverables:** Messaging spec with flow diagrams, payload schemas, email templates, inbound parsing rules, and monitoring checklist.
  - **Acceptance Criteria:** Document details masked address format, inbound job scheduling, spam filter heuristics, user notifications, and retention for `marketplace_messages` + `inbound_emails`.
  - **Dependencies:** `I3.T3`
  - **Parallelizable:** Yes

- **Iteration Workstreams & Owners:**
  - **Data & Search Pod:** Owns taxonomy (I3.T1), PostGIS/Elasticsearch spec (I3.T2), listing contract contributions.
  - **Payments & Media Pod:** Leads OpenAPI extension, Stripe blueprint, image pipeline docs.
  - **Ops & Trust Pod:** Handles moderation playbook and messaging spec with support from platform team.

- **Dependency Mapping:** I3 outputs rely on I2 migrations for user/flag schema, leverage AI tagging spec for fraud detection, and feed I4 tasks (public profiles referencing listings) plus I5 analytics dashboards through click tracking/Stripe data.

- **Parallelization Guidance:** Start taxonomy + payments documentation immediately; search spec must precede OpenAPI updates; moderation + messaging docs can proceed after listing contract solidifies; image pipeline can run parallel with payments once queue requirements known.

- **Risks & Mitigations:** Taxonomy growth (handle via slug strategy and ADR triggers), Stripe downtime (document retries + monitoring), queue overload for images (set concurrency + alerts), email abuse (rate limiting + spam heuristics), search performance (load testing plan referenced in spec).

- **Metrics & Validation Hooks:** Validate PostGIS queries with EXPLAIN; run Stripe sandbox flows; simulate image uploads; test masked email path using Mailpit; capture results in docs + QA plan.

- **Timeline & Milestones:** Week 1 taxonomy/search drafts; Week 2 OpenAPI + payments blueprint; Week 3 image pipeline + moderation playbook; Week 4 messaging spec + review.

- **Review & Sign-off Participants:** Marketplace PM, Backend lead, Finance/Payments owner, Ops/Trust lead, Compliance representative.

- **Hand-off Checklist:** Taxonomy + migration note merged; OpenAPI listing sections validated; payments blueprint + image pipeline + moderation/messaging docs stored in `docs/marketplace`; backlog tickets reference anchors.

- **Governance Alignment Notes:**
  - Policy P3 enforced via payment + moderation playbooks and refund workflows.
  - Policy P4 applied to listing image retention + WebP processing.
  - Policy P6/P11 implemented through PostGIS spec with explicit radius caps.
  - Policy P14 acknowledged in messaging spec for logging retention + consent handling.

- **Stakeholder Communication Plan:** Weekly sync with PM + finance to review payment spec; async review doc for moderation/fraud; Slack channel for search spec questions; end-of-iteration walkthrough recorded for onboarding.

- **Testing & QA Alignment:** QA team drafts test suites for listing CRUD + payments based on contracts; load tests planned for search queries; image pipeline includes failure injection scenarios; messaging spec includes mock SMTP tests for inbound/outbound flows.

- **Monitoring Deliverables:** Define dashboards for Stripe webhook success, image processing backlog, search latency, and masked email throughput; include Grafana panel descriptions in respective specs.

- **Open Questions to Track:** Promotional inventory caps, refund exception cases, potential use of CAPTCHA for inquiries, CDN invalidation tooling for listing assets.

- **Post-Iteration Actions:** Schedule engineering kickoff for marketplace implementation, open ADR placeholders if policy clarifications emerge, and assign doc owners for future updates.

- **Iteration Exit Criteria:**
  - Marketplace specs approved and linked from README/plan manifest.
  - Migration + queue requirements queued for implementation, with tasks created for engineering squads.
  - Stripe, image, moderation, and messaging blueprints reference policies and include monitoring/alerting requirements.
  - Dependency tickets created for future iterations relying on marketplace data (profiles, analytics dashboards).
  - QA owners confirm test suites + load scenarios are documented for upcoming build iterations.
