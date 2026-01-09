<!-- anchor: iteration-plan -->
## 5. Iteration Plan

*   **Total Iterations Planned:** 6 (I1 Setup Foundation, I2 Personalization Core, I3 Content & AI Pipelines, I4 Marketplace Delivery, I5 Good Sites Directory, I6 Profiles & Analytics).
*   **Iteration Dependencies:** Sequential layering—later iterations rely on platform scaffolding, auth/feature flag infrastructure, content ingestion, and marketplace/directory foundations from earlier sprints. Cross-iteration blockers (e.g., LangChain budget enforcement, StorageGateway) must be resolved in I1/I2 to unlock downstream work.

<!-- anchor: iteration-1-plan -->
### Iteration 1: Platform Setup & Architectural Baseline

*   **Iteration ID:** `I1`
*   **Goal:** Establish upgraded Quarkus/Java toolchain, dev services, CI hooks, architectural artifacts, migration seeds, and TypeScript build integration so subsequent squads can work in parallel with reliable scaffolding.
*   **Prerequisites:** None (initial iteration).
*   **Iteration KPIs:** Complete architectural diagrams + ERD, deliver validated docker-compose stack, upgrade Quarkus 3.26.x, confirm Spotless/Sonar gating, and document async queue guidelines with approval from sibling repos.
*   **Iteration Deliverables Summary:** Upgraded repo config, docs/diagrams, baseline migrations, TypeScript build pipeline, instrumentation templates, security/observability guardrails, and ADR-first policies for commands/plan instructions.
*   **Iteration Risks & Mitigations:** Upgrade regressions mitigated via ADR + CI gating; diagram scope creep minimized through template reuse; local env drift handled by .env templates; secret exposure prevented via vault docs produced in T1.9.

<!-- anchor: task-i1-t1 -->
*   **Task 1.1:**
    *   **Task ID:** `I1.T1`
    *   **Description:** Audit repo prerequisites, upgrade Quarkus BOM + Maven wrapper to Java 21 compatible 3.26.x release, and align plugin versions (Jib, frontend-maven-plugin 1.15.0) with policy requirements.
        Validate compatibility with Spotless, Surefire, LangChain4j, and Hibernate Search dependencies before tagging baseline.
        Document upgrade rationale and potential regressions in ADR to inform future merges and release playbooks.
    *   **Agent Type Hint:** `SetupAgent`
    *   **Inputs:** Sections 1–2, technology stack mandates, P8 TypeScript integration requirements, policy table.
    *   **Input Files:** `["pom.xml","README.md","mvnw","docs/adr/"]`
    *   **Target Files:** `["pom.xml","mvnw","docs/adr/0001-quarkus-upgrade.md"]`
    *   **Deliverables:** Updated Maven wrapper + Quarkus BOM, aligned plugin versions, ADR covering upgrade scope + rollback steps, README snippet about Java 21 target.
    *   **Acceptance Criteria:** Builds pass with `./mvnw quarkus:dev`, CI compiles with Java 21, plugin checks succeed, ADR merged with sign-off, no dependency conflicts.
    *   **Dependencies:** None.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i1-t2 -->
*   **Task 1.2:**
    *   **Task ID:** `I1.T2`
    *   **Description:** Generate PlantUML system context + container diagrams capturing actors (anonymous/auth users, admins, Stripe, Meta, Cloudflare R2, LangChain4j, Elasticsearch, Postgres) and Quarkus modules.
        Highlight enforced policies (P1–P14) near boundaries, queue segregation, deployment nodes, and Kubernetes runtime characteristics for ops alignment.
    *   **Agent Type Hint:** `DiagrammingAgent`
    *   **Inputs:** Sections 2, 2.1, 3, blueprint directives, UI/ops references.
    *   **Input Files:** `["docs/diagrams/"]`
    *   **Target Files:** `["docs/diagrams/context.puml","docs/diagrams/container.puml"]`
    *   **Deliverables:** System context + container diagrams with legends, exported previews (optional), README snippet referencing diagrams.
    *   **Acceptance Criteria:** PlantUML sources render without errors, include policy annotations, show integration boundaries + worker pods, approved by structural architect.
    *   **Dependencies:** `I1.T1` (ensures repo scaffolding + doc conventions).
    *   **Parallelizable:** Yes.

<!-- anchor: task-i1-t3 -->
*   **Task 1.3:**
    *   **Task ID:** `I1.T3`
    *   **Description:** Draft ERD covering core tables (users, rss_sources, feed_items, ai_usage_tracking, weather_cache, stock_quotes, social_tokens/posts, marketplace_*, directory_*, user_profiles, feature_flags, delayed_jobs, link_clicks, rate_limit tables, payment_refunds, directory_screenshot_versions).
        Include JSONB fields, partition keys, indexes, retention annotations, and future expansion placeholders for internationalization.
    *   **Agent Type Hint:** `DatabaseAgent`
    *   **Inputs:** Section 2 data model overview, policy decisions (P1–P14), architecture diagrams produced in T1.2.
    *   **Input Files:** `["docs/diagrams/"]`
    *   **Target Files:** `["docs/diagrams/erd.puml","docs/diagrams/erd.md"]`
    *   **Deliverables:** ERD source + explanatory markdown summarizing retention, indexes, relationships, and compliance tags.
    *   **Acceptance Criteria:** Diagram renders, references all mandated tables, indicates partitioning + indexes, includes textual summary linking to policies, reviewed by data architect.
    *   **Dependencies:** `I1.T2` (shares notation + legend patterns).
    *   **Parallelizable:** No.

<!-- anchor: task-i1-t4 -->
*   **Task 1.4:**
    *   **Task ID:** `I1.T4`
    *   **Description:** Document async workload matrix + job handler skeletons; define queue enum, handler interface, base `DelayedJobService` integration, SCREENSHOT pool semaphore plan per P12, and job telemetry fields.
        Provide PlantUML sequence for job dispatch plus markdown describing concurrency, retry/backoff defaults, and escalation paths.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Sections 2–4, queue requirements table, policy P12 + P10.
    *   **Input Files:** `["docs/diagrams/","src/main/java/villagecompute/homepage/jobs/"]`
    *   **Target Files:** `["docs/diagrams/async_matrix.puml","docs/ops/job-playbook.md","src/main/java/villagecompute/homepage/jobs/JobTypes.java","src/main/java/villagecompute/homepage/jobs/JobHandler.java"]`
    *   **Deliverables:** Diagram + doc + job types + handler interface skeleton referencing queues and metrics tags.
    *   **Acceptance Criteria:** Matrix enumerates DEFAULT/HIGH/LOW/BULK/SCREENSHOT with owners + SLA, code compiles, doc cross-links to policy IDs, review sign-off from ops architect.
    *   **Dependencies:** `I1.T2`, `I1.T3` (diagram style + data references).
    *   **Parallelizable:** No.

<!-- anchor: task-i1-t5 -->
*   **Task 1.5:**
    *   **Task ID:** `I1.T5`
    *   **Description:** Configure TypeScript toolchain: `package.json`, `tsconfig.json`, `esbuild.config.js`, Ant Design + gridstack dependencies, npm scripts, frontend-maven-plugin wiring, lint/typecheck commands, and React mount registry stub.
        Provide README instructions for dev (watch mode vs Maven), ensure `npm ci` invoked via plugin, and create placeholder `mounts.ts` with sample component mapping.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Sections 2 (Frontend stack), P8 plugin requirements, UI architecture doc.
    *   **Input Files:** `["package.json","tsconfig.json","esbuild.config.js","pom.xml","README.md"]`
    *   **Target Files:** `["package.json","tsconfig.json","esbuild.config.js","src/main/resources/META-INF/resources/assets/ts/mounts.ts","README.md"]`
    *   **Deliverables:** Working npm scripts, esbuild bundling config, instructions describing React island pattern, lint/typecheck baseline, sample widget stub.
    *   **Acceptance Criteria:** `./mvnw compile` triggers npm build, watch instructions verified, TypeScript strict mode on, README updated, sample mount compiles.
    *   **Dependencies:** `I1.T1` and `I1.T4` (ensures plugin + job docs references).
    *   **Parallelizable:** Yes.

<!-- anchor: task-i1-t6 -->
*   **Task 1.6:**
    *   **Task ID:** `I1.T6`
    *   **Description:** Stand up docker-compose stack (Postgres 17 + PostGIS, Elasticsearch 8, Mailpit, MinIO, Jaeger) with healthchecks, env examples, and scripts for quick bootstrap.
        Seed baseline config (.env, sample secrets) referencing `../villagecompute` repo guidance; confirm migrations run via MyBatis plugin and provide instructions for loading geo datasets + feature flags.
    *   **Agent Type Hint:** `DevOpsAgent`
    *   **Inputs:** Section 3 directory tree, infrastructure references, policy requirements for dependencies.
    *   **Input Files:** `["docker-compose.yml","migrations/","README.md","docs/ops/"]`
    *   **Target Files:** `["docker-compose.yml","migrations/README.md",".env.example","docs/ops/dev-services.md","scripts/load-geo-data.sh"]`
    *   **Deliverables:** Verified docker-compose config, instructions for seeding geo data + feature flags, .env sample, troubleshooting doc, helper script.
    *   **Acceptance Criteria:** `docker-compose up -d` brings services healthy, README instructions accurate, migrations succeed, secrets instructions align with compliance, helper script tested.
    *   **Dependencies:** `I1.T1` (build uses Java 21) and `I1.T5` (tie into frontend instructions referencing dev services).
    *   **Parallelizable:** No.

<!-- anchor: task-i1-t7 -->
*   **Task 1.7:**
    *   **Task ID:** `I1.T7`
    *   **Description:** Configure CI/CD skeleton (GitHub Actions or Jenkins) running lint, tests, sonar, and plan verification commands; embed Spotless, unit/integration jobs, npm cache, and artifact publishing steps with Jib push placeholders.
        Include badges + instructions in README and comment on gating policies (80% coverage, zero critical sonar issues, plan compliance) referencing Section 4 directives.
    *   **Agent Type Hint:** `DevOpsAgent`
    *   **Inputs:** Section 4 directives, quality gate requirements, sibling repo standards.
    *   **Input Files:** `[".github/workflows/","Jenkinsfile","README.md","docs/ops/ci.md"]`
    *   **Target Files:** `[".github/workflows/build.yml","README.md","docs/ops/ci.md"]`
    *   **Deliverables:** CI workflow file, documentation for pipeline, status badge snippet, caching strategy notes.
    *   **Acceptance Criteria:** Workflow runs `./mvnw verify`, `npm run build`, sonar stub; caching configured; README explains pipeline; ops sign-off recorded.
    *   **Dependencies:** `I1.T1`, `I1.T5`, `I1.T6` (ensures build/test instructions consistent).
    *   **Parallelizable:** Partial.

<!-- anchor: task-i1-t8 -->
*   **Task 1.8:**
    *   **Task ID:** `I1.T8`
    *   **Description:** Establish observability baseline—configure structured logging format (JSON), OpenTelemetry exporters, SmallRye Metrics endpoints, and Jaeger exporter config per Section 3.1.
        Produce `docs/ops/observability.md` describing trace IDs, log fields, metrics naming, and dashboard seeds for queue depth + AI budget.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Sections 2, 4, observability blueprint, ops directives.
    *   **Input Files:** `["src/main/resources/application.properties","docs/ops/"]`
    *   **Target Files:** `["src/main/resources/application.properties","src/main/java/villagecompute/homepage/observability/LoggingConfig.java","docs/ops/observability.md"]`
    *   **Deliverables:** Config entries for logging/metrics/tracing, helper class or producer, doc describing instrumentation expectations.
    *   **Acceptance Criteria:** Application emits JSON logs with trace_id/span_id, metrics endpoint exposes queue depth + HTTP metrics, doc reviewed by ops, toggle for Jaeger endpoints documented.
    *   **Dependencies:** `I1.T1`, `I1.T4` (job metrics), `I1.T6` (Jaeger service info).
    *   **Parallelizable:** No.

<!-- anchor: task-i1-t9 -->
*   **Task 1.9:**
    *   **Task ID:** `I1.T9`
    *   **Description:** Define security + secrets baseline: create `.gitignore`/`.env` templates, document secret sources (Kubernetes secrets, sealed secrets), configure Quarkus Vault or config injection stubs, and add lint rules preventing secret leaks.
        Draft `docs/ops/security.md` summarizing cookie policies (P9), OAuth bootstrap guardrails, Stripe key handling, and command usage constraints from Section 4.
    *   **Agent Type Hint:** `SecurityAgent`
    *   **Inputs:** Sections 1, 4, policy decisions P1, P3, P5, P9, P14.
    *   **Input Files:** `[".env.example",".gitignore","docs/ops/"]`
    *   **Target Files:** `[".env.example","docs/ops/security.md","config/secrets-template.yaml"]`
    *   **Deliverables:** Updated env template, security doc, optional secrets template referencing Kubernetes secret names, lint rule config.
    *   **Acceptance Criteria:** Documentation covers cookies, OAuth, Stripe, AI keys, S3 credentials; env template lists required variables with descriptions; lint/husky rule catches committed secrets; security review sign-off.
    *   **Dependencies:** `I1.T6` (env template) and `I1.T7` (CI enforcement for lint rule).
    *   **Parallelizable:** No.
