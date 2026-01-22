<!-- anchor: 3-0-operational-architecture -->
## 3. Proposed Architecture (Operational View)
The operational view describes how the Village Homepage platform is provisioned, scaled, and supervised inside the VillageCompute-managed k3s estate while honoring every policy constraint documented in the blueprint foundation.
It frames the Quarkus modular monolith as a composition of operational lanes: the experience shell, domain engines, async workers, integration adapters, and shared enablers such as feature flags and rate limiting.
The section intentionally mirrors the separation of squads so that ownership can be traced from design through runbook execution, reducing toil during incident response.
Each topic below maps to actionable procedures that will be elaborated in future Ops guides and will be automated through GitOps manifests wherever possible.
All descriptions assume container images are published via Jib, stored in the private VillageCompute registry, and deployed through the infra repository located at `../villagecompute`.

<!-- anchor: 3-1-environmental-baseline -->
### 3.1 Environmental Baseline
The baseline articulates the non-negotiable runtime substrate shared by homepage pods, queue workers, cron-like schedulers, and integration sidecars.
It focuses on locking configuration precedence, ensuring the Kubernetes story satisfies the concurrency and memory profile mandated by policies P2, P4, P10, and P12.

<!-- anchor: 3-1-1-platform-posture -->
#### 3.1.1 Platform Posture
All environments run inside a managed k3s cluster administered by the VillageCompute platform team; application namespaces are delivered by IaC stored in the sibling `villagecompute` repo.
Each namespace pins to Kubernetes 1.29+ with PodSecurityStandards set to baseline, enforcing read-only root filesystems for Quarkus pods except when Chrome dependencies require shared memory mounts for jvppeteer.
Pods are scheduled on worker pools sized for Quarkus memory footprints (2.5GB for general-purpose pods, 6GB for screenshot-enabled pods per Policy P12).
Node pools integrate containerd-level seccomp profiles and mutual TLS for intra-cluster communication so service-to-service traffic remains encrypted even without exiting the cluster overlay network.
Cluster ingress leverages NGINX with cert-manager issued TLS certificates anchored to VillageCompute wildcard domains, ensuring `homepage.villagecompute.com` and beta equivalents terminate TLS consistently.

<!-- anchor: 3-1-2-configuration-management -->
#### 3.1.2 Configuration Management
Configuration is layered as: immutable container defaults → profile-specific `application-*.properties` → ConfigMap for non-secret toggles → Kubernetes Secret for credentials → feature flags for behavioral switches.
The infra repo codifies this stack via Helm-like templating even though Helm is not directly used; kustomize overlays apply environment-specific config to keep beta and prod aligned except for secret references.
Secret rotation is handled via sealed secrets; the ops team provides a quarterly rotation cadence for Alpha Vantage, Meta Graph, Stripe, S3, and LangChain credentials with emergency rotation drills documented in runbooks.
Feature flag bootstrapping occurs via migration scripts so baseline flags (`stocks_widget`, `social_integration`, etc.) exist before pod boot; FeatureFlagService reads them into cache on startup and listens for change events written to the audit table.
Every config change triggers GitOps reconciliation; drift detection ensures manual kubectl edits are rejected, reinforcing auditable operations per the blueprint mandates.

<!-- anchor: 3-1-3-networking-layout -->
#### 3.1.3 Networking Layout
East-west traffic between pods is routed through the cluster CNI with mTLS; service meshes are intentionally avoided to reduce complexity, but Quarkus emits OpenTelemetry headers to keep traces correlated.
North-south traffic flows through Cloudflare for CDN and WAF duties before reaching the cluster ingress; static assets hosted in Cloudflare R2 inherit the same CDN domain for cache efficiency and DDoS absorption.
Database connectivity uses PostgreSQL 17 with PostGIS enabled; prod deployments bind to a managed HA pair with streaming replication and automatic failover, while beta uses a single instance with PITR snapshots.
Elasticsearch is provisioned as a dedicated StatefulSet with SSD-backed volumes; heartbeat checks feed into alerts when shard allocation drifts, ensuring Hibernate Search indices remain healthy.
Mailpit, MinIO, and Jaeger exist only in local developer compose stacks; in shared envs these services map to managed SMTP relays, R2 storage, and the centralized Jaeger cluster respectively.

<!-- anchor: 3-2-runtime-workload-partitioning -->
### 3.2 Runtime Workload Partitioning
The Quarkus application remains a layered monolith, yet operationally it is decomposed into deployment sets aligned with traffic shapes, queue assignments, and compliance-critical workloads.
Partitioning enforces least-surprise behavior during scaling events and helps the platform team isolate heavy CPU tasks such as AI tagging from latency-sensitive widget rendering.

<!-- anchor: 3-2-1-experience-shell-pods -->
#### 3.2.1 Experience Shell Pods
Experience pods serve the Qute-rendered HTML, mount React islands, and expose REST APIs under `/api` and `/admin/api`.
They operate with `DEFAULT`, `HIGH`, and `LOW` delayed job pollers disabled to keep resource usage predictable; asynchronous tasks are delegated to worker pods described later.
Horizontal Pod Autoscalers (HPAs) monitor p95 latency and CPU utilization; scaling rules favor maintaining at least two replicas per AZ to satisfy availability expectations.
Pods include sidecars for log shipping (Fluent Bit) and for capturing Prometheus metrics via scraping endpoints; no init containers beyond DB migration checkers are required.
Feature flag evaluation caches are warmed during pod startup to avoid cold path latency spikes when anonymous traffic surges, aligning with Policy P7.

<!-- anchor: 3-2-2-domain-worker-pods -->
#### 3.2.2 Domain Worker Pods
Worker pods focus on delayed job queues and are labeled by queue responsibility: `worker-default`, `worker-high`, `worker-low`, `worker-bulk`, and `worker-screenshot`.
Each worker pod runs the identical Quarkus artifact but boots with environment variable `JOB_PROCESSING_MODE` to enable queue polling while disabling HTTP listeners, minimizing attack surface.
Scaling rules tie backlog depth to replica counts; e.g., BULK workers scale when AI tagging jobs exceed 200 pending or screenshot jobs exceed 50 to satisfy SLA-free yet timely completion requirements.
Screenshot workers mount ephemeral storage volumes sized for Chromium caching; they also expose node selectors to land on hosts with GPU-friendly libraries if future enhancements demand accelerated capture.
Worker restart policies enable exponential backoff; CrashLoopBackOff thresholds trigger alerts routed to the ops Slack channel defined in the runbook.

<!-- anchor: 3-2-3-integration-gateways -->
#### 3.2.3 Integration Gateways
Integration workloads such as Stripe webhooks, inbound email polling, and Meta callbacks are fronted by dedicated ingress paths with tight rate limits and WAF rules.
Webhook handling is deployed as part of the main Quarkus deployment but annotated with `nginx.ingress.kubernetes.io/whitelist-source-range` to restrict origins where possible (Stripe IP ranges, Mail service subnets).
For long-polling flows (e.g., IMAP checks), Quarkus scheduled tasks run inside low-priority worker pods to avoid starving user-facing CPU cycles.
Outbound requests to Alpha Vantage, Open-Meteo, Meta Graph, and Cloudflare R2 traverse egress gateways with policy-enforced allowlists so no unintended destinations are contacted.
All integrations adopt HTTP client timeouts of ≤3 seconds and circuit breaker patterns; states are surfaced to the Ops analytics portal for manual intervention when needed.

<!-- anchor: 3-3-data-platform-operations -->
### 3.3 Data Platform Operations
The platform owns a dense relational schema, spatial extensions, search indices, and object storage; operations focus on maintaining integrity, compliance, and performance.

<!-- anchor: 3-3-1-postgresql-governance -->
#### 3.3.1 PostgreSQL Governance
MyBatis migrations remain the single authority for schema evolution; migrations run inside a Kubernetes Job triggered during every deployment with `mvn migration:up` invoked via container init logic.
Production PostgreSQL leverages streaming replication; failover is coordinated by Patroni, and application pods connect through a virtual IP managed by the infra team to avoid DSN churn.
PostGIS is enabled on database creation; spatial indexes on `geo_cities.location` and `marketplace_listings` keep P11 and P6 targets achievable.
Partitioned tables such as `link_clicks` use pg_partman; retention jobs drop partitions older than 90 days, while rollup tables remain stored for a year or more per policy definitions.
Vacuum and analyze jobs run nightly with windows tuned to avoid overlapping AI tagging peaks; metrics feed into the Ops portal showing table bloat and query latency percentiles.

<!-- anchor: 3-3-2-elasticsearch-stewardship -->
#### 3.3.2 Elasticsearch Stewardship
Hibernate Search generates index schemas; the infra repo ensures index templates exist before pods start.
Single-node dev clusters auto-manage indices, but beta/prod clusters run as three-node StatefulSets with dedicated master/data roles to survive node failures.
Snapshot policies export indices to object storage weekly; restore procedures are documented for disaster recovery.
Index lifecycle policies shrink retention for transient data (e.g., queue monitoring) while preserving listing and directory indices long enough to support analytics.
Pods monitor Elasticsearch health via readiness probes; degraded states trigger failover to Postgres fallback queries with feature flags signaling degraded search to front-end components.

<!-- anchor: 3-3-3-object-storage-operations -->
#### 3.3.3 Object Storage Operations
Cloudflare R2 buckets are dedicated per asset domain: `homepage-screenshots`, `homepage-listings`, and `homepage-profiles`.
Uploads flow through the StorageGateway Quarkus service, which enforces WebP conversion, metadata tagging (content type, retention hints), and version recording inside `directory_screenshot_versions`.
Lifecycle policies never delete screenshots automatically (per Policy P4) but do transition old versions to infrequent access tiers after 18 months to control cost.
Signed URLs embed 24-hour expirations for private assets (listing images in drafts), while public content (Good Sites screenshots) uses CDN-cached URLs with cache-busting query params tied to version numbers.
Bucket access keys are rotated automatically via Cloudflare tokens; pods reference them through Kubernetes Secrets with least-privilege policies scoped per bucket.

<!-- anchor: 3-4-async-workload-orchestration -->
### 3.4 Asynchronous Workload Orchestration
Async orchestration ensures all policies referencing delayed jobs (feed refresh, AI tagging, screenshots, click rollups, rate limit aggregation, etc.) have deterministic lifecycles.

<!-- anchor: 3-4-1-delayed-job-service -->
#### 3.4.1 Delayed Job Service Implementation
The service follows the `village-calendar` contract: each job row stores handler class, payload JSON, schedule metadata, and lock details.
Handlers declare max attempts, exponential backoff parameters, and idempotency tokens so replays never double-process feed items or payments.
Queue pollers operate inside dedicated worker pods; concurrency is controlled via environment variables to respect CPU footprint caps.
Admin APIs expose queue depths, last run timestamps, and failure reasons; operators can pause queues (e.g., STOP AI tagging at 100% budget usage) through the Ops portal that writes to `delayed_job_control` tables.
Telemetry spans capture job IDs and payload versions, linking async work to tracing data.

<!-- anchor: 3-4-2-scheduling-and-throttling -->
#### 3.4.2 Scheduling and Throttling
Quarkus `@Scheduled` beans enqueue recurring jobs for each feature: RSS fetchers, weather refresh, stock refresh with market hour detection, screenshot refresh, and link health checks.
Schedules respect policy-defined cadences (e.g., 5-minute breaking news, 1-hour weather) and store the configured interval inside application properties to keep infra as code.
Throttling is applied via AiTaggingBudgetService: before job creation, feed ingestion flows consult the service, which may downshift batch size or defer new jobs until the next billing cycle.
Screen-capture handlers obey P12 concurrency by gating `captureWithBrowser` calls through resource semaphores; job payloads include timeouts so stuck Chromes can be recycled.
Queue fairness is maintained with priority weights: DEFAULT and HIGH jobs always preempt BULK when runners are saturated, ensuring user-facing freshness even if AI workloads spike.

<!-- anchor: 3-4-3-recovery-and-ops -->
#### 3.4.3 Recovery and Operational Controls
Failed jobs persist `last_error` stacks that are surfaced in the Ops portal and mirrored to structured logs.
Operators can requeue or delete specific jobs using admin APIs guarded by `manage_system` permissions; all changes are recorded in audit tables with actor IDs and reason strings.
Runbooks define auto-remediation for common issues such as stuck screenshot browsers, AI provider downtime, or Stripe webhook mismatches.
Disaster scenarios (database failover, Elasticsearch outage) include toggles that gate queue types; e.g., when Postgres fails over, workers pause to prevent cascading lock contention.
Metrics track attempt counts and average completion latency per handler; thresholds feed alerting pipelines when SLIs degrade.

<!-- anchor: 3-5-external-integration-operations -->
### 3.5 External Integration Operations
External integrations handle business-critical data (payments, AI, weather, social content) and must observe quotas, rate limits, and compliance requirements.

<!-- anchor: 3-5-1-financial-integration -->
#### 3.5.1 Financial Integration with Stripe
All paid categories and promotions route through Stripe Payment Intents; metadata encodes listing IDs, category, promotion type, and user ID to comply with Policy P3 audits.
Webhook endpoints process `payment_intent.succeeded`, `payment_intent.payment_failed`, `charge.refunded`, and `review.opened`; handlers validate Stripe signatures and operate idempotently by referencing stored intent IDs.
Refund windows follow the conditional policy; automated refunds for moderation rejection or technical failure within 24 hours are triggered by queue jobs, while manual reviews create `payment_refunds` rows awaiting admin action.
Chargeback evidence packages gather listing content, audit logs, and contact transcripts, stored in R2 and referenced via keys in the refund table.
Ops dashboards show revenue per category, refund rates, and chargeback counts with links to runbook checklists.

<!-- anchor: 3-5-2-intelligence-and-ai -->
#### 3.5.2 Intelligence and AI Providers
LangChain4j orchestrates calls to Claude Sonnet 4 through the Anthropic connector; AiTaggingBudgetService tracks cumulative spend per month and enforces Policy P10 thresholds.
Batching logic groups 10-20 feed items per call, with dedupe hashes preventing repeated tagging when the same URL reappears across aggregated feeds.
Fraud detection and Good Sites AI categorization share the same budget; when the service enters reduction or queue-for-next-cycle modes, jobs annotate their payloads so downstream handlers know to degrade gracefully.
Budget alerts at 75/90/100% are emitted via Email (Mailgun or SMTP) and Slack webhooks; ack events are recorded to demonstrate governance.
Future AI providers can be added by wiring LangChain4j connectors yet toggled via feature flags until validated.

<!-- anchor: 3-5-3-weather-social-market -->
#### 3.5.3 Weather, Social, and Market Data
Open-Meteo and National Weather Service clients are isolated per Policy F4; requests include caching headers, and responses populate the `weather_cache` table with expiration timestamps to limit API calls.
Alpha Vantage usage respects free-tier quotas by centralizing watchlist refresh; the Stock service schedules high-frequency jobs only during NYSE market hours determined via timezone logic.
Meta Graph API tokens are stored encrypted; a daily job refreshes tokens seven days before expiration (Policy P5) and flags stale tokens, triggering user notifications and banner adjustments per Policy P13.
Social data persists indefinitely unless the user deletes it; background jobs mark posts as archived when tokens lapse beyond seven days, preventing UI crashes while encouraging reconnection.
Rate limit telemetry for each provider flows into the Ops portal; repeated throttle events prompt configuration adjustments such as smaller watchlists or deferred refreshes.

<!-- anchor: 3-6-observability-telemetry -->
### 3.6 Observability & Telemetry
Observability combines tracing, logging, and metrics per Section 3.1 of the blueprint.

<!-- anchor: 3-6-1-logging -->
#### 3.6.1 Logging
Quarkus logs use JSON format with fields: timestamp, severity, trace_id, span_id, user_id, anon_id, feature_flags, queue, job_id, endpoint, and request latency.
Logs are shipped via Fluent Bit to the centralized ELK stack; retention is 30 days for app logs, 90 days for security logs.
Sensitive data (OAuth tokens, Stripe secrets, social payloads) is redacted at log emission; log macros enforce placeholders rather than string concatenation to avoid accidental leaks.
Error budgets tie to log severity counts; repeated ERROR bursts trigger incident workflows.
Anonymous merge events log `account_merge_audit` references to support GDPR audits without revealing PII directly in logs.

<!-- anchor: 3-6-2-metrics -->
#### 3.6.2 Metrics
Metrics are emitted via SmallRye Metrics and scraped by Prometheus; dashboards live in Grafana with per-squad views (Experience, Marketplace, Directory, AI, Ops).
Key metrics include: job queue depth, browser pool utilization, AI budget percent used, feed ingest latency, PostGIS query durations, rate-limit violations, screenshot success rate, and Stripe webhook lag.
Alert rules map to blueprint risk radar: AI budget >90%, screenshot queue backlog >500 for >10 minutes, Elasticsearch cluster yellow for >5 minutes, inbound email failures consecutive 5 polls, and OAuth login failure rate >2%.
Service level indicators (SLIs) for homepage render time and marketplace search latency feed into monthly SLO reviews documented by the Ops architect.

<!-- anchor: 3-6-3-tracing -->
#### 3.6.3 Tracing
OpenTelemetry instrumentation covers REST entry points, delayed job handlers, and integration clients; traces propagate through async tasks by injecting context into job payload metadata.
Jaeger stores traces for seven days; beyond that, aggregated metrics suffice.
Trace sampling is adaptive: baseline 10%, elevated to 50% when error rates climb, giving precise context during incidents.
Feed ingestion, AI tagging, screenshot capture, and payment flows are annotated with span tags referencing policy IDs so analysts can connect behavior to compliance requirements easily.
Tracing dashboards highlight queue handoffs, making backpressure patterns visible across ingestion pipelines.

<!-- anchor: 3-7-security-compliance-governance -->
### 3.7 Security, Compliance, & Governance
Security operations align with Policy decisions P1, P3, P5, P7, P9, P13, and P14.

<!-- anchor: 3-7-1-access-control -->
#### 3.7.1 Access Control & Identity
Quarkus OIDC handles OAuth logins with Google, Facebook, and Apple; tokens become JWTs validated per request.
Anonymous sessions rely on `vu_anon_id` cookies set with HttpOnly, Secure, SameSite=Lax, and 30-day max-age attributes; rotation occurs when merging into authenticated accounts.
Roles and permissions mirror `village-storefront` constants; enforcement uses `@RolesAllowed` and `@PermissionsAllowed` annotations plus PolicyEnforcer interceptors for custom checks (e.g., GDPR export allowed only for owning user or super admin).
Admin bootstrap endpoint `/bootstrap` is protected by rate limiting and returns 403 once the first super admin exists, preventing unauthorized bootstrap attempts.
Impersonation actions are logged in audit tables with source/destination user IDs, IP, and user agent for investigative tracing.

<!-- anchor: 3-7-2-data-protection -->
#### 3.7.2 Data Protection & Compliance
GDPR consent flows show modals during OAuth linking anonymous data; consent decisions and IP/user agent metadata populate `account_merge_audit` with 90-day retention before purge.
Data export/deletion endpoints schedule delayed jobs that gather JSON packages stored in R2 with signed download links; once the user retrieves data, the package expires after 7 days.
Feature flag evaluation logs honor analytics consent; `user_id` is recorded only when consent exists, while `session_hash` remains to maintain aggregated stats.
Account deletion triggers synchronous purge of evaluations and asynchronous cleanup of social tokens, listings, and cached posts; finalization events log completion for compliance evidence.
Directory karma adjustments and moderator assignments create audit entries to prove governance fairness.

<!-- anchor: 3-7-3-threat-model -->
#### 3.7.3 Threat Modeling & Hardening
All public endpoints enforce HTTPS; HTTP requests redirect at Cloudflare before reaching Kubernetes.
Input sanitization uses a centralized utility for Markdown (profiles, listings) and HTML (embed allowlists) to prevent stored XSS.
Rate limiting stands as the first line of defense against scraping, brute force, and spam submissions; `rate_limit_violations` feed machine-readable ban rules.
Secrets remain in Kubernetes Secrets referencing external KMS encryption; pods mount them as environment variables with fsGroup restrictions to avoid world-readable files.
Dependency scanning occurs in CI via OWASP Dependency Check; runtime CVE alerts feed into weekly backlog triage with severity-based SLAs.

<!-- anchor: 3-8-cross-cutting-concerns -->
### 3.8 Cross-Cutting Concerns
This section distills mandatory operational responses for shared concerns.

Authentication & Authorization:
- Authentication uses Quarkus OIDC multi-tenant configuration for Google, Facebook, and Apple providers, issuing JWT access tokens with 15-minute lifetimes and refresh tokens via the identity provider.
- Anonymous identification is handled by the `vu_anon_id` cookie; server-side `users` table entries with `is_anonymous = true` store layouts and preferences prior to OAuth merge.
- Authorization leans on role-based access control with `super_admin`, `support`, `ops`, and `read_only` roles mapped to permissions such as `manage_users` and `manage_system`.
- Feature flags cross-check cohorts before enabling sensitive functionality; whitelist overrides allow QA or rollback testing without redeploying pods.
- Admin-only APIs enforce `@RolesAllowed` plus audit logging so impersonation or rate limit override attempts always leave a forensic trail.

Logging & Monitoring:
- Structured JSON logs stream through Fluent Bit into Elasticsearch, with dashboards highlighting anomalies like OAuth failures or job retries.
- Prometheus scrapes `/q/metrics`; Grafana overlays trending budgets, queue depths, CPU usage, AI spend, and API quota consumption.
- Alertmanager routes incidents to Slack and PagerDuty per severity; runbooks specify first-responder actions for each alert signature.
- Synthetic probes check homepage, marketplace, Good Sites, and profile endpoints every minute from multiple regions to validate CDN and k3s ingress health.
- Ops analytics portal consolidates metrics, logs, and job states into a Qute + React dashboard, reducing tool sprawl for on-call responders.

Security Considerations:
- Cloudflare WAF policies shield login, marketplace posting, and webhook endpoints from OWASP Top 10 attack signatures.
- Secrets management uses Kubernetes Secrets encrypted with cluster KMS; rotation scripts regenerate tokens without downtime by leveraging Quarkus live reload of config maps.
- Input sanitization is centralized, ensuring Markdown (profiles) and HTML (directory descriptions) pass allowlists before render.
- Stripe, Meta, and Alpha Vantage integrations run through HTTP clients that enforce TLS 1.2+, pinned hostnames, and strict timeout budgets to reduce MITM exposure.
- Periodic penetration tests focus on anonymous merge flows, listing payments, and admin impersonation to ensure P1 and P3 compliance boundaries remain intact.

Scalability & Performance:
- Stateless Quarkus pods running inside Kubernetes support horizontal scaling driven by HPAs keyed to CPU and request latency.
- PostGIS-backed queries rely on tuned indexes and query plans; caching of user preferences and feature flag decisions keeps request bodies light.
- Elasticsearch clusters shard listing and directory indices per category domain, enabling concurrent search operations even during heavy traffic.
- Screenshot and AI workloads ride dedicated worker pools so spikes never starve the main request pods.
- CDN caching ensures static assets, screenshots, and listing thumbnails serve from Cloudflare PoPs, reducing origin load and improving global response times.

Reliability & Availability:
- Database replication and automated failover keep the persistence tier resilient; application pods reconnect using repeatable connection pools once the virtual IP fails over.
- Multi-replica pods across AZs ensure no single-node failure disrupts the homepage or marketplace experiences.
- Disaster recovery runbooks detail restoration steps from R2 backups for screenshots and listing images, plus Elasticsearch snapshot restores.
- Job handlers include retry with exponential backoff; poison jobs move to dead-letter tables for manual inspection.
- Health checks include HTTP readiness probes, delayed job heartbeat metrics, and integration smoke tests executed via scheduled diagnostic jobs.

<!-- anchor: 3-9-deployment-view -->
### 3.9 Deployment View
The deployment view enumerates the target cloud, rollout pipeline, and runtime topology diagrams required to operate the solution.

<!-- anchor: 3-9-1-target-environment -->
#### 3.9.1 Target Environment
Target cloud platform: VillageCompute-managed Kubernetes running k3s clusters in VillageCompute-operated data centers with Cloudflare CDN and R2 for global delivery.
Supporting services include managed PostgreSQL 17 with PostGIS, Elasticsearch 8, Mail relay, Jaeger, and Cloudflare R2 buckets dedicated to screenshots, listing images, and profile assets.
Beta and production share identical infrastructure definitions; divergence occurs only through feature flags controlling rollout percentages or enabling/disabling modules such as stocks and social integration.

<!-- anchor: 3-9-2-deployment-strategy -->
#### 3.9.2 Deployment Strategy
CI/CD pipeline: `./mvnw package` builds Quarkus artifact plus TypeScript assets (frontend-maven-plugin) and produces OCI images via Jib.
Images push to the private registry; ArgoCD (or Flux) in `../villagecompute` repo reconciles Kubernetes manifests referencing the new tag.
Blue/green deployment is not required; rolling updates with maxUnavailable=1 ensure minimal disruption while new pods warm caches and complete readiness probes.
Feature flags act as the safety net for progressively enabling modules; for example, `stocks_widget` stays disabled until metrics confirm stability.
Smoke tests run after deployment to verify key flows (anonymous homepage, login, marketplace listing creation, Good Sites browse, `/track/click` redirect).

<!-- anchor: 3-9-3-deployment-diagram -->
#### 3.9.3 Deployment Diagram (PlantUML)
~~~plantuml
@startuml
!include https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Deployment.puml
LAYOUT_WITH_LEGEND()

Deployment_Node(k3s,"VillageCompute k3s Cluster","Kubernetes 1.29") {
  Deployment_Node(ns,"homepage namespace","Isolated namespace") {
    Container(quarkus,"Quarkus App","Java 21 + Quarkus","HTTP + Delayed Jobs")
    Container(workerDefault,"Worker Pods","Quarkus","DEFAULT/LOW/HIGH queues")
    Container(workerBulk,"Bulk Workers","Quarkus","AI tagging, screenshots")
    Container(workerScreenshot,"Screenshot Workers","Quarkus + Chromium","SCREENSHOT queue")
  }
}

Deployment_Node(db,"PostgreSQL 17","Managed Service") {
  ContainerDb(postgres,"homepage database","PostgreSQL + PostGIS")
}

Deployment_Node(es,"Elasticsearch 8","StatefulSet") {
  ContainerDb(esdb,"Search Cluster","Hibernate Search indices")
}

Deployment_Node(r2,"Cloudflare R2","Object Storage") {
  Container(storage,"Buckets","Screenshots, Listing Images, Profile Assets")
}

Deployment_Node(cf,"Cloudflare CDN/WAF","Global Edge") {
  Deployment_Node(users,"End Users","Browsers + Mobile")
}

Rel(users,cf,"HTTPS Requests")
Rel(cf,k3s,"Proxy traffic")
Rel(quarkus,postgres,"JDBC")
Rel(workerDefault,postgres,"JDBC")
Rel(workerBulk,postgres,"JDBC")
Rel(workerScreenshot,postgres,"JDBC")
Rel(quarkus,esdb,"Elasticsearch REST")
Rel(workerBulk,r2,"Upload screenshots, listing images")
Rel(quarkus,r2,"Serve assets via signed URLs")
Rel(quarkus,users,"HTML + Assets via CDN")
@enduml
~~~
<!-- anchor: 3-10-operational-runbooks -->
### 3.10 Operational Runbooks & Procedures
This section curates the daily, weekly, and incident-specific procedures that Ops teams must follow to keep the platform within SLOs.
The guidance maps to the squads outlined in the blueprint so responsibilities are clear before incidents occur.

<!-- anchor: 3-10-1-daily-ops-checklist -->
#### 3.10.1 Daily Ops Checklist
Morning health review: verify Kubernetes deployments are at desired replica counts, check HPAs for recent scale events, and confirm no pods remain in CrashLoopBackOff.
Database health: inspect PostgreSQL replication lag, vacuum stats, and PostGIS index usage; escalate if lag exceeds 30 seconds for more than 10 minutes.
Queue posture: review delayed job dashboards for backlog drift, confirm AiTaggingBudgetService state, and sample job logs for anomalous error spikes.
Integration status: fetch Stripe webhook delivery reports, AI provider latency metrics, weather/social API rate limit counters, and inbound email poller success counts.
Security posture: examine audit log feed for admin activity, confirm rate limit violation counts remain under expected control limits, and ensure no feature flag changes occurred without recorded change reason.

<!-- anchor: 3-10-2-weekly-maintenance -->
#### 3.10.2 Weekly Maintenance
Apply dependency updates flagged by Dependabot or internal scanning, prioritizing Quarkus point releases and chromium security patches for screenshot pods.
Rotate low-risk credentials such as API keys for staging, rotate IMAP passwords, and validate sealed secret decryption using a dry-run apply.
Rebuild Elasticsearch snapshots and verify restore ability in a sandbox cluster; document success/failure in the Ops journal.
Review GDPR export/deletion queues to ensure no outstanding jobs exceed SLA; backlog items trigger escalation to the privacy steward.
Conduct threat review by sampling logs for unusual impersonation attempts or payment-related anomalies, aligning with Policy P3 and P1.

<!-- anchor: 3-10-3-incident-response -->
#### 3.10.3 Incident Response Flows
Adopt SEV ladder: SEV1 for production outage or data breach, SEV2 for major feature downtime (marketplace, Good Sites), SEV3 for degraded performance with workarounds.
PagerDuty alerts assign ExperienceShell or Domain Service SRE as incident commander; roles include communications lead and subject-matter experts depending on impacted module.
Incident channel naming convention: `#inc-homepage-YYYYMMDD-sevX`; all decisions logged for retrospective analysis.
Runbook steps include capturing kubectl describe outputs, tailing logs via Kibana with trace_id filtering, and optionally scaling pods manually if HPA fails.
Post-incident: create RCA within 48 hours detailing contributing factors, detection, response timeline, and follow-up action items tied to backlog tickets.

<!-- anchor: 3-11-capacity-and-cost -->
### 3.11 Capacity & Cost Management
Capacity planning covers compute sizing, storage footprint, AI budget governance, and screenshot retention costs; cost signals feed into the Ops analytics portal.

<!-- anchor: 3-11-1-compute-scaling -->
#### 3.11.1 Compute Scaling
Baseline compute: 3 experience pods, 2 default workers, 2 high workers, 2 low workers, 2 bulk workers, 2 screenshot workers in prod; beta runs half scale.
Autoscaling thresholds: CPU >60% or p95 latency >200ms for 5 minutes triggers scale-out; scale-in waits for 30-minute calm window to avoid flapping.
Node pool reservations: screenshot workers pin to nodes with 6GB RAM each; rest of pods share general pool sized with headroom for 2x load.
Resource quotas prevent runaway namespace consumption; each deployment budget includes CPU and memory requests aligned to Quarkus profiling data.
Chaos drills simulate pod eviction and node failure monthly to validate scaling logic and readiness probes.

<!-- anchor: 3-11-2-storage-forecasting -->
#### 3.11.2 Storage Forecasting
PostgreSQL growth tracked via table size dashboards; heavy hitters like `link_clicks` partitions and `marketplace_listings` attachments are monitored for bloat.
R2 storage budgets account for unlimited screenshot retention; cost modeling assumes 500KB per thumbnail pair per version multiplied by 12 captures/year per site.
Marketplace listing images expire when listings are deleted or marked sold; StorageGateway cleanup tasks reclaim keys and update CDN invalidations nightly.
Elasticsearch disk usage monitored with watermarks at 75/85/95%; hitting 85% triggers auto-shard rebalancing or warm/cold tier migration.
Backup storage includes PostgreSQL WAL archives and Elasticsearch snapshots; these costs are amortized in the infrastructure budget with quarterly reviews.

<!-- anchor: 3-11-3-ai-budget-ops -->
#### 3.11.3 AI Budget Operations
AiUsageTracking table stores monthly counters; Cron job runs hourly to compute percent usage and update Grafana panels.
When usage hits 75%, automatic notifications go to ops@villagecompute.com plus Slack `#ai-budget`; job scheduler reduces AI batch sizes automatically per Policy P10.
At 90%, new jobs enqueue with `run_at` set to next month for non-critical feeds; ops may override by raising budget_limit_cents through admin UI with change reason captured.
At 100%, AiTaggingAction becomes HARD_STOP; user-visible components degrade gracefully by showing untagged content badges until the next cycle.
Historical usage is archived for forecasting; data supports negotiation of higher budgets or switch to alternative models when ROI is proven.

<!-- anchor: 3-12-operational-dependencies -->
### 3.12 Operational Dependencies & Coordination
Large-scale operations demand clear interfacing with sibling teams (storefront, calendar, infrastructure) to avoid regressions.

<!-- anchor: 3-12-1-inter-repo-alignment -->
#### 3.12.1 Inter-Repo Alignment
Authentication, authorization, and delayed job patterns reuse modules from `village-storefront` and `village-calendar`; any divergence requires ADRs signed off by foundation architects.
Infra repo owners keep Kubernetes manifests in sync; homepage architects submit PRs to adjust resource requests, secrets, or ingress paths, referencing blueprint anchors in commit messages.
Shared libraries (e.g., RateLimitService) are versioned artifacts; upgrade testing happens in staging clusters before production adoption.
Regular guild meetings align on feature flag semantics, ensuring evaluations behave consistently across apps to prevent user confusion.
Incident communications include storefront/calendar stakeholders when shared services (auth, delayed jobs) degrade so downstream systems prepare compensating actions.

<!-- anchor: 3-12-2-compliance-collaboration -->
#### 3.12.2 Compliance Collaboration
Privacy and legal teams receive monthly exports of `account_merge_audit` and `payment_refunds` tables for external reporting.
Access to GDPR export tooling is limited to compliance officers plus super admins; audit logs note requester identity and reason.
Ops architects coordinate with legal before altering retention periods, ensuring policy updates propagate to documentation and code simultaneously.
Security reviews involve red-team exercises focusing on anonymous merges, listing moderation, and screenshot storage due to their elevated data sensitivity.
Feature flag analytics retention (90 days) is validated quarterly to confirm purges run successfully; compliance receives purge reports for sign-off.

<!-- anchor: 3-12-3-runway-for-future -->
#### 3.12.3 Runway for Future Enhancements
International expansion requires scaling PostGIS datasets, Elasticsearch shards, and CDN configurations; early planning ensures infra budgets cover additional storage and compute.
Potential addition of new social networks or AI providers mandates modular integration layers and contractual review for API terms.
Marketplace monetization experiments (promotions, tiered fees) should run behind feature flags with dedicated cost tracking to avoid revenue leakage.
Directory moderator incentives and profile SEO customization depend on future policy decisions; operations should prepare instrumentation hooks now so adoption only requires enabling features.
Documenting these dependencies keeps the Operational Architecture living, ensuring future architects inherit context without re-litigating foundational choices.
<!-- anchor: 3-13-operational-tooling -->
### 3.13 Operational Tooling & Automation
Automation minimizes manual toil and codifies recurrent operational actions.

<!-- anchor: 3-13-1-gitops-pipeline -->
#### 3.13.1 GitOps Pipeline
The infra repo defines ArgoCD Applications per environment; merges trigger reconciliation loops that compare desired vs actual state.
Preflight checks ensure manifests include PodDisruptionBudgets, HPAs, resource requests/limits, and ConfigMap hashes for immutable config.
ArgoCD notifications push sync status into Slack, highlighting drift or failed deploys before they impact SLOs.
Rollback actions simply revert git commits; ArgoCD detects change and rolls cluster back without manual kubectl commands, satisfying audit requirements.
Policy-as-code scripts validate YAML for prohibited patterns (privileged:true, hostNetwork) before merge, aligning with security posture.

<!-- anchor: 3-13-2-automation-scripts -->
#### 3.13.2 Automation Scripts
CLI utilities in the `ops-tools` folder wrap frequent tasks: triggering GDPR exports, scaling worker pools, toggling feature flags, and seeding test data.
Scripts authenticate via service accounts with scoped permissions; execution logs record command, actor, timestamp, and parameter summary.
Automation handles CDN cache invalidations whenever listing images or profiles are updated, preventing stale content after edits.
Database maintenance scripts orchestrate partition creation for `link_clicks` and `feature_flag_evaluations`, ensuring retention windows stay intact without manual psql sessions.
Stripe reconciliation jobs confirm ledger totals match `payment_refunds` and `listing_promotions`; discrepancies open tickets automatically.

<!-- anchor: 3-13-3-testing-in-prod-guardrails -->
#### 3.13.3 Testing-in-Production Guardrails
Feature flag cohorts restrict new widget rollouts to small percentages with MD5 hash stability; ops can view active cohorts via admin UI to correlate incidents with experiments.
Synthetic traffic patterns mimic anonymous visits and logged-in customizations; they run hourly using k6 scripts to detect regression before real users notice.
Observability gating ensures new modules emit metrics/logs/traces before enabling; lacking telemetry blocks the flag from being set above 5%.
Beta environment mirrors production manifest but has separate secrets and data, giving teams a safe staging zone for soak testing.
If a feature misbehaves, kill-switch flags exist (social_integration, promoted_listings) letting ops disable functionality instantly.

<!-- anchor: 3-14-business-continuity -->
### 3.14 Business Continuity & Disaster Recovery
BC/DR planning ensures data durability and service availability meet stakeholder expectations even during catastrophic failures.

<!-- anchor: 3-14-1-backup-strategy -->
#### 3.14.1 Backup Strategy
PostgreSQL logical backups run nightly, complemented by WAL archiving for point-in-time recovery; snapshots store in off-site R2 buckets with encryption at rest.
Elasticsearch snapshots captured weekly to R2; restore tests scheduled monthly to verify index compatibility with current cluster version.
R2 object storage leverages versioning for screenshots and listing images; cross-region replication replicates assets to a secondary account for resilience.
Configuration repositories (app code and infra) live in GitHub with redundant backups; release artifacts stored in the registry keep the last 20 versions to facilitate rapid rollback.
Backup monitoring includes checksum verification and alerting when backup duration deviates from norms, preventing silent failures.

<!-- anchor: 3-14-2-failover-runbooks -->
#### 3.14.2 Failover Runbooks
Database failover: Patroni promotes replica, DNS/virtual IP shifts, application pods recycle connections; ops verifies read/write health before closing incident.
Elasticsearch failover uses node cordoning plus cluster reroute commands; search functionality degrades gracefully with fallback queries until cluster rebalances.
Object storage failover invokes CDN origin swap to the replicated bucket; StorageGateway reconfigures base URLs through environment variables, and feature flags inform UI about limited screenshot availability.
Complete region failure triggers multi-region recovery: spin up pre-provisioned standby cluster, restore DB snapshot, replay WAL, recreate Kubernetes namespace via GitOps, and rehydrate indices from R2 snapshots.
Runbooks include RTO/RPO targets (RTO 1 hour, RPO 15 minutes) and communications templates for stakeholders.

<!-- anchor: 3-14-3-resilience-testing -->
#### 3.14.3 Resilience Testing
Quarterly game days simulate major incidents: database corruption, CDN outage, AI provider downtime, or queue saturation.
Chaos experiments employ tools like `chaoskube` to randomly kill pods while monitoring SLO impact.
Fire drills include manual operation of GDPR export/deletion pipelines to confirm they function without automation available.
Outcome metrics capture time-to-detect, time-to-mitigate, and data loss (if any); lessons feed backlog improvements and documentation updates.
Compliance teams observe tests affecting regulated workflows to maintain audit readiness.

<!-- anchor: 3-15-operational-kpis -->
### 3.15 Operational KPIs & Reporting
KPIs give leadership visibility into operational health and tie directly to service-level objectives.

<!-- anchor: 3-15-1-service-health-kpis -->
#### 3.15.1 Service Health KPIs
Homepage availability target: 99.9% monthly uptime measured via synthetic probes hitting anonymous and authenticated flows.
Marketplace search latency: p95 <100ms for ≤100-mile searches, <200ms for 100-250 miles; tracked via Application Metrics and SearchService instrumentation.
Good Sites ingestion throughput: average screenshot job completion <5 minutes after submission, ensuring curated content stays fresh.
Screenshot success rate: maintain 98% success; failure spikes trigger immediate investigation into Chromium pools or site-specific blocking.
Click tracking fidelity: <0.5% difference between raw click counts and aggregated stats to maintain trust in analytics dashboards.

<!-- anchor: 3-15-2-compliance-kpis -->
#### 3.15.2 Compliance KPIs
GDPR merge consent coverage: 100% of OAuth logins must capture consent version and timestamp.
Data export SLA: 95% of export requests delivered within 24 hours; metrics surfaced in Ops portal.
Deletion SLA: 99% of deletion requests finalized within 30 days, with zero residual feature flag evaluation rows remaining afterwards.
Refund SLA: automatic refunds processed within 1 hour of qualifying event; manual reviews completed within 48 hours.
Feature flag evaluation retention: partitions older than 90 days dropped successfully each week, ensuring compliance with P14.

<!-- anchor: 3-15-3-operational-reporting -->
#### 3.15.3 Operational Reporting Cadence
Weekly Ops report circulated to leadership summarizing uptime, incident count, queue depth trends, AI budget usage, and notable feature flag changes.
Monthly compliance packet aggregates audit logs, GDPR metrics, refund processing stats, and screenshot storage growth for legal review.
Quarterly business review includes KPI trends, cost breakdowns (compute, storage, AI, third-party APIs), and roadmap alignment for future scaling.
Reports are generated automatically via scheduled jobs that query analytics tables and render Qute templates emailed through the mailer service.
Historical reports stored in R2 for 2 years, enabling comparisons when planning expansions or negotiating vendor contracts.
<!-- anchor: 3-16-operational-data-lifecycle -->
### 3.16 Operational Data Lifecycle Stewardship
Data lifecycle covers collection, retention, purging, and archival duties demanded by policies and compliance expectations.

<!-- anchor: 3-16-1-retention-schedules -->
#### 3.16.1 Retention Schedules
Link clicks: raw partitions retained 90 days; nightly job verifies partitions exist for the next two months and drops those exceeding retention; rollups kept 1-2 years.
Feature flag evaluations: 90-day window with immediate purge when consent withdrawn or account deleted; job audit logs capture counts removed per run.
Screenshot versions: unlimited retention but flagged with `is_current`; storage analytics highlight dormant versions >3 years so leadership can reconsider policy if budget tightens.
Marketplace listings: expired listings archived after 30 days; attachments deleted but metadata retained for fraud investigation for 1 year.
Audit logs (feature flags, impersonation, refunds) retained 2 years minimum to satisfy governance and financial review requirements.

<!-- anchor: 3-16-2-data-quality -->
#### 3.16.2 Data Quality Monitoring
Scheduled checks validate referential integrity between core tables (users vs preferences, listings vs images, directory entries vs votes).
Drift detection scripts ensure JSONB schemas contain expected version markers; anomalies trigger migrations or cleanup tasks.
AI tagging completeness dashboards show percentage of feed items with tags vs total ingested; if the ratio drops below 80%, ops reviews queue health or budget gating decisions.
Click tracking reconciliation compares tracked counts with CDN logs to identify missing redirect hits.
Data quality incidents follow the same SEV process with remediation steps documented for transparency.
<!-- anchor: 3-17-ops-collaboration -->
### 3.17 Ops Collaboration & Knowledge Management
Effective operations depend on shared knowledge, rotation planning, and documentation hygiene.

<!-- anchor: 3-17-1-knowledge-base -->
#### 3.17.1 Knowledge Base Practices
Runbooks, RCA documents, architectural decisions, and configuration references live in a shared Confluence space with links back to blueprint anchors for traceability.
Each incident adds FAQ entries describing symptoms, detection signals, and fixes so future responders resolve issues faster.
Onboarding bundles include sandbox exercises covering delayed job tuning, feature flag toggling, AI budget overrides, and screenshot queue debugging.
Ops pairings rotate monthly between Experience, Marketplace, Directory, and Infra to cross-train practitioners and reduce silo risk.
Documentation reviews occur quarterly to prune stale content and incorporate new policies or feature rollouts.
