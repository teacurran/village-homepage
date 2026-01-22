<!-- anchor: 4-0-design-rationale -->
## 4. Design Rationale & Trade-offs
This document captures why the operational blueprint embraces particular patterns, how alternatives compared, and which risks continue to track.
It doubles as the architectural narrative for future teams seeking context before proposing change.

<!-- anchor: 4-1-key-decisions -->
### 4.1 Key Decisions Summary
1. Kubernetes k3s with Jib-built Quarkus containers remains the deployment target because the foundation mandates VillageCompute-managed clusters with consistent tooling shared across sibling repos.
2. A modular layered monolith structure ensures Panache ActiveRecord models co-reside with services and job handlers, simplifying dependency management while still allowing squad autonomy via package boundaries.
3. PostgreSQL 17 with PostGIS plus JSONB storage backs every domain, guaranteeing transactional integrity for marketplace payments, GDPR audits, and feature flag evaluations.
4. Elasticsearch 8 through Hibernate Search powers text queries for marketplace and Good Sites so advanced ranking can evolve without rewriting persistence code.
5. Cloudflare R2 handles all binary assets (screenshots, listing images, profile avatars) because Policy P4 demands unlimited screenshot retention with CDN-backed delivery.
6. LangChain4j + Claude Sonnet 4 sits at the heart of AI workloads, governed by AiTaggingBudgetService to enforce the $500/month ceiling and degrade gracefully per Policy P10.
7. FeatureFlagService implements Policy P7 stable cohorts, logging analytics with consent awareness per P14, ensuring risk-controlled launches for stocks, social, promotions, and future experiments.
8. RateLimitService using Caffeine caches enforces tiered thresholding for anonymous vs logged-in vs trusted actions, addressing abuse prevention without external Redis dependencies.
9. Delayed job orchestration mirrors `village-calendar` so asynchronous work (feeds, screenshots, click rollups) behaves predictably and can be shared across squads.
10. Cloudflare CDN with WAF fronting k3s ingress satisfies security and performance requirements simultaneously while simplifying certificate management.
11. Observability relies on structured JSON logs, Prometheus metrics, and Jaeger traces, aligning with blueprint 3.1 mandates for comprehensive telemetry.
12. GDPR compliance and audit logging adopt dedicated tables (account_merge_audit, feature_flag_audit, payment_refunds) to enshrine regulatory evidence separate from operational data.
13. Marketplace monetization flows integrate Stripe Payment Intents plus refund automation to satisfy Policy P3 while preserving chargeback defense data.
14. Social integration uses Meta Graph API only, gating future networks behind feature flags to manage scope and compliance per blueprint assumptions.
15. Screenshot capture remains in-application via jvppeteer rather than an external service to reuse Quarkus deployment patterns, albeit with dedicated SCREENSHOT workers to protect responsiveness.
16. Qute + React islands architecture was upheld to honor frontend governance: server-rendered SEO-friendly pages with targeted interactive components orchestrated through `mounts.ts`.
17. Object storage versioning and CDN-signed URLs implement Policy P4 unlimited retention while guarding private content through short-lived access tokens.
18. Rate limit violation logging, feature flag evaluation retention, and click tracking rollups create a data foundation for Ops analytics without violating privacy constraints.
19. Deployment pipeline stays single `./mvnw package` to ensure deterministic artifacts containing both backend and TypeScript outputs per Policy P8.
20. Beta vs production environment divergence is handled exclusively through feature flags and config overlays, ensuring artifact parity for compliance and debugging.

<!-- anchor: 4-2-alternatives -->
### 4.2 Alternatives Considered
- Alternative: Multi-service microservices architecture separating marketplace, directory, and profiles into different deployables.
  - Rejected because the blueprint emphasizes a layered monolith for large-scale coordination and reuse of shared services (feature flags, rate limiting, jobs) without cross-service latency.
- Alternative: Managed screenshot service (e.g., Browserless) to offload Chromium operations.
  - Rejected to keep data residency and retention guarantees under direct control, eliminate new vendor risk, and align with Policy P12 requiring configurable per-pod browser pools.
- Alternative: Cloud-managed AI services per module (news tagging vs fraud detection vs categorization) with independent budgets.
  - Rejected because operations require a single AiTaggingBudgetService to enforce the strict $500/month cap and share batching mechanisms.
- Alternative: Traditional SPA frontend replacing Qute templates with full React.
  - Rejected to maintain SEO, performance, and minimization of compliance scope for anonymous sessions while still enabling React islands for interactive widgets.
- Alternative: Using Redis or external message queues for delayed jobs.
  - Rejected to remain consistent with `village-calendar` pattern, limit infra footprint, and allow SQL-based observability/auditing of async activity.
- Alternative: Deploying on public cloud managed Kubernetes (EKS/GKE) instead of VillageCompute k3s.
  - Rejected because the foundation requires using the internal platform for governance parity and cost control; VillageCompute already provides necessary integrations.
- Alternative: Use SaaS analytics for click tracking.
  - Rejected to keep GDPR control, support partitioned raw logs, and integrate with internal feature flag cohorts without sharing PII externally.

<!-- anchor: 4-3-risks -->
### 4.3 Known Risks & Mitigation
1. Risk: AI budget exhaustion stalling news tagging and fraud analysis during peak ingestion.
   - Mitigation: AiTaggingBudgetService throttles batch sizes, defers non-critical jobs, alerts at 75/90/100%, and allows manual overrides with audit logging.
2. Risk: Screenshot workers overloading pods due to Chromium memory leaks.
   - Mitigation: SCREENSHOT queue isolates workload, browser pools are bounded via semaphores, and dedicated nodes allocate 6GB per pod with restart automation.
3. Risk: Elasticsearch outages affecting marketplace search relevance.
   - Mitigation: Fallback SQL queries keep baseline functionality alive, while Kubernetes health probes and snapshot recovery scripts facilitate faster recovery.
4. Risk: GDPR/CCPA violations during anonymous-to-authenticated merges.
   - Mitigation: Consent modal gating login, `account_merge_audit` logging, and export/deletion workflows ensure compliance with audit trails.
5. Risk: Stripe chargebacks damaging revenue.
   - Mitigation: Payment metadata, refund policy enforcement, AI fraud flagging, and evidence packaging stored in R2 support dispute responses; bans trigger after repeated chargebacks.
6. Risk: Social API changes breaking integrations.
   - Mitigation: Feature flag kill switch, cached posts with banners per P13, proactive token refresh, and contingency plan for Meta API revocation.
7. Risk: Feature flag analytics storing PII without consent.
   - Mitigation: Consent-aware logging per P14, session_hash fallback, immediate purge upon preference changes, and 90-day retention limit with auto partition drop.
8. Risk: Marketplace location search scaling beyond PostGIS capacity when global expansion happens.
   - Mitigation: Blueprint defers “Any” radius to v2, uses PostGIS indexes for US/Canada dataset, and outlines future options (regional clusters or Elasticsearch geo) to revisit before international rollout.
9. Risk: Click tracking retention costs growing with traffic.
   - Mitigation: Partitioned tables with 90-day retention, daily rollups into aggregated tables, and auto-drop jobs so raw storage remains bounded.
10. Risk: Complex multi-module scope overwhelming teams.
    - Mitigation: Strict package ownership, feature flags for phased rollouts, Ops analytics portal for shared visibility, and defined team topology from the foundation.

<!-- anchor: 5-0-future-considerations -->
## 5. Future Considerations
Forward-looking items ensure the architecture evolves responsibly as requirements change.

<!-- anchor: 5-1-potential-evolution -->
### 5.1 Potential Evolution
- International Expansion: Extend PostGIS datasets beyond US/Canada, add localization, and consider CDN PoPs plus jurisdiction-specific compliance updates.
- Additional AI Providers: Introduce secondary models (OpenAI, internal LLMs) behind feature flags, diversifying cost structure and resilience.
- Extended Social Integrations: Add Twitter/X, LinkedIn, or Bluesky connectors with pluggable adapters once policies for retention and consent are defined.
- Marketplace Enhancements: Experiment with subscription tiers, seller reputation scores, and automated moderation using AI plus human review.
- Directory Gamification: Convert karma into badges or moderator incentives, potentially integrating leaderboards into the Ops analytics portal.
- Profile Customization: Offer deeper template theming, custom domains via CNAME mapping, and editorial tools for collaborative curation.
- Observability Automation: Adopt auto-remediation bots adjusting scaling, toggling feature flags, or pausing queues when telemetry crosses thresholds.
- Edge Rendering: Investigate Cloudflare Workers for caching read-only Qute fragments to reduce origin load during heavy anonymous traffic.
- Compliance Automation: Expand purge pipelines to automatically verify data removal and send signed completion receipts to requesting users.
- AI Cost Marketplace: Provide admin UI sliders for adjusting batch sizes and job prioritization, enabling quicker response to budget pressures.

<!-- anchor: 5-2-deeper-dive -->
### 5.2 Areas for Deeper Dive
1. CI/CD Blueprint: Document ArgoCD pipelines, approval gates, automated testing suites, and rollback orchestration for auditors and developers alike.
2. Moderation Workflows: Flesh out UI/UX, escalation paths, and tooling for marketplace and directory moderation, including AI-human collaboration guidelines.
3. Marketplace Payment Reconciliation: Define detailed Stripe webhook retry policies, ledger reconciliation steps, and financial reporting integrations.
4. Public Profile Security: Specify Markdown sanitization rules, embed allowlists, CSP headers, and preview workflows to guard against injection attacks.
5. Inbound Email Processing: Clarify spam detection heuristics, attachment handling, and storage policies for message bodies and metadata.
6. AI Observability: Expand budget dashboards with token-level insights, provider latency tracking, and forecasting models.
7. Disaster Recovery Drills: Outline full multi-region failover simulations including timeline, staffing, and communications scripts.
8. Search Relevance Tuning: Develop experiments for weighting AI tags, user preferences, and engagement data to personalize feeds and marketplace listings.
9. Accessibility Roadmap: Document WCAG compliance targets for homepage widgets, marketplace flows, and directories, including testing automation.
10. Data Export Packager: Provide schema for JSON exports, encryption at rest during staging, and verification steps before releasing to the requester.

<!-- anchor: 6-0-glossary -->
## 6. Glossary
- **AiTaggingBudgetService**: Service enforcing AI cost policies (P2/P10) with budget-aware throttling decisions.
- **ArgoCD**: GitOps controller applying infra repo manifests to the k3s cluster.
- **BULK Queue**: Delayed job queue dedicated to heavy workloads such as AI tagging and screenshot batch processing.
- **Cloudflare R2**: S3-compatible object storage powering screenshot and listing image retention.
- **Delayed Job**: Database-backed async task modeled after `village-calendar`, storing payloads, queue metadata, and retry state.
- **FeatureFlagService**: Cohort evaluator implementing Policy P7 with rollout percentages, user whitelists, and analytics logging per P14.
- **Gridstack.js**: Frontend library enabling drag-and-drop widget layout on the homepage and public profile templates.
- **Hibernate Search**: Quarkus feature bridging ORM entities to Elasticsearch indices for marketplace/directory search.
- **HPAs (Horizontal Pod Autoscalers)**: Kubernetes controllers adjusting replica counts based on CPU and custom metrics.
- **Jib**: Maven-integrated OCI image builder producing Quarkus container images without Dockerfiles.
- **LangChain4j**: Java abstraction for AI providers, used to access Claude Sonnet 4 for tagging and categorization.
- **Panache ActiveRecord**: ORM pattern storing finder methods on entity classes, mandated by the blueprint.
- **PostGIS**: PostgreSQL extension providing spatial functions for marketplace and geographic scope features per P6/P11.
- **Qute**: Quarkus templating engine delivering server-rendered pages feeding React islands.
- **SCREENSHOT Queue**: Dedicated delayed job queue for Puppeteer-based captures with pool-aware concurrency.
- **SmallRye Metrics**: Quarkus metrics implementation exposing Prometheus-compatible endpoints.
- **Stripe Payment Intent**: Payment object storing listing fee charges, capturing metadata for refunds and chargeback disputes.
- **villagecompute**: Infra repository managing Kubernetes manifests, secrets, and deployment automation for the homepage project.
<!-- anchor: 4-4-decision-impacts -->
### 4.4 Decision Impact Narratives
Below narratives explain how selected decisions shape day-to-day operations so future architects understand ripple effects before proposing change.

<!-- anchor: 4-4-1-auth-merge -->
#### 4.4.1 Anonymous Merge Compliance
Choosing shared `users` table entries for anonymous visitors enables seamless upgrade to authenticated accounts but forces careful handling of cookies, consent storage, and merge auditing.
Audit trails (`account_merge_audit`) become first-class artifacts monitored for 90-day purge windows, and documentation must keep support teams ready to explain data handling during privacy reviews.
Rejecting separate anonymous tables simplified SQL queries but increases importance of rate limiting around bootstrap endpoints and OAuth flows to defend against enumeration.

<!-- anchor: 4-4-2-ai-budget-impact -->
#### 4.4.2 AI Budget Governance
Centralizing AI calls through LangChain4j plus AiTaggingBudgetService unifies instrumentation, yet also ties marketplace fraud detection, Good Sites categorization, and feed tagging together.
Budget exhaustion therefore affects multiple squads simultaneously; Ops needs clear communication channels and dashboards to prioritize which workload resumes first when funds reset.
Batching decisions bleed into UX expectations: reduced AI quality during late-month windows must be communicated to product so user messaging remains transparent.

<!-- anchor: 4-4-3-screenshot-lifecycle -->
#### 4.4.3 Screenshot Lifecycle Ownership
Deciding to keep jvppeteer within the Quarkus deployment grants maximum control over capture parameters, version history, and storage policies.
It also requires per-pod Chrome dependency maintenance, resource reservations, and security review (Chromium flags, sandboxing) that would otherwise be delegated to a vendor.
The Ops team must therefore maintain nightly smoke tests capturing known URLs, verifying both thumbnail/full versions upload correctly, and checking CDN invalidation logic.
Failure to do so jeopardizes Good Sites credibility and marketplace listing conversions, demonstrating the operational burden tied to this architectural choice.

<!-- anchor: 4-5-traceability -->
### 4.5 Documentation & Traceability Strategy
Decision hygiene matters because multiple squads iterate concurrently.
Each policy-mandated table or feature flag change requires an Architecture Decision Record referencing the blueprint anchor plus runbook updates, ensuring reviewers understand compliance implications.
Ops, legal, and product stakeholders access a shared change log summarizing which flags toggled, which migrations landed, and which external integrations changed configuration, enabling traceability during audits.
Git history ties each merge to a Jira/Linear ticket capturing rationale, testing evidence, and metrics impact, closing the loop between decision and operational results.

<!-- anchor: 5-1a-potential-evolution-details -->
#### 5.1.a Extended Evolution Notes
Edge caching strategies might graduate into HTML streaming or personalization at the CDN level; any such move requires secure session handling and encryption of per-user data.
When AI devices evolve, consider distillation models running on-prem to reduce cost but ensure GPU resource planning occurs early to avoid surprise capacity shortfalls.
Marketplace expansions to event tickets or services may require licensing/regulatory updates; architecture must remain modular to integrate compliance checks per category.
Good Sites might introduce user-generated comments; if so, moderation tooling, spam detection, and privacy policies must scale accordingly.
Public profiles could support collaborative editing or multi-author teams, introducing permission layers beyond the current single-owner model.

<!-- anchor: 5-1b-data-evolution -->
#### 5.1.b Data Evolution Considerations
As click tracking volume grows, data warehousing options (e.g., Snowflake or BigQuery) might supplement Postgres rollups for long-term analytics; connectors should preserve privacy constructs like session hashes.
Schema versioning for JSONB blobs (preferences, template configs) must continue; plan migration tooling that upgrades records in background jobs to prevent runtime schema branching.
International privacy laws (LGPD, POPIA) may demand additional consent fields or retention adjustments; designing preference objects with jurisdiction tags now prevents future rewrites.

<!-- anchor: 5-2a-deeper-dive-ops -->
#### 5.2.a Ops Deep-Dive Topics
- Rate Limit Governance: Evaluate automated tuning algorithms that adjust thresholds based on observed abuse vs legitimate spikes, and ensure dashboards expose rationale.
- Job Scheduler Reliability: Craft formal proofs or simulations verifying queue latency under various workloads, including AI budget curtailment scenarios.
- Storage Lifecycle Automation: Document workflows that detect orphaned R2 objects (e.g., deleted listings) and ensure cleanup routines remain deterministic.
- Privacy Engineering: Build redaction tooling for support exports, ensuring logs or data extracts omit unnecessary PII when assisting users.
- SEO & Performance: Research server-side caching or pre-rendering options for high-traffic landing pages without introducing stale personalization states.

<!-- anchor: 5-2b-deeper-dive-product -->
#### 5.2.b Product Deep-Dive Topics
- Marketplace Promotions: Model user experience, revenue projections, and fairness policies when multiple sellers want featured slots simultaneously.
- Good Sites Moderation Rewards: Explore gamification loops that translate karma into privileges or tangible rewards, aligning community behavior with curation goals.
- Profile Discovery: Determine how public profiles rank in internal directories or search results while respecting opt-out preferences and analytics consent.
- AI Transparency: Design UI elements showing whether AI tagged a feed item, with ability to flag incorrect tags for reprocessing.
- Social Token Recovery: Provide wizards guiding users through reauthentication when provider policies change, including fallback communication channels.

<!-- anchor: 6-1-extended-glossary -->
### 6.1 Glossary Addendum
- **ADR (Architecture Decision Record)**: Lightweight document capturing context, decision, alternatives, and consequences for traceability.
- **AiUsageTracking**: Database table storing per-month token counts, request totals, and dollars spent for AI interactions.
- **Analytics Consent**: User preference toggling whether feature flag evaluation logs store user IDs or anonymized session hashes per Policy P14.
- **CDN PoP**: Cloudflare Points of Presence delivering cached assets geographically closer to users.
- **ClickStatsRollupJobHandler**: Delayed job summarizing raw click logs into daily aggregates for analytics dashboards.
- **Config Overlay**: Kustomize/Helm technique injecting environment-specific settings into Kubernetes manifests without forking base files.
- **Data Export Package**: GDPR-compliant archive containing user layouts, feeds, marketplace listings, social posts, and audit entries.
- **Delayed Job Payload Schema Version**: Field marking JSON payload format revisions to ensure backward-compatible parsing.
- **Feature Flag Audit**: Table capturing before/after state for flag changes with actor identity and reason.
- **IMAP Poller**: Scheduled component fetching inbound marketplace replies via email for relay through the platform.
- **JobOrchestrator**: Collective term for queue pollers, schedulers, and monitoring utilities ensuring asynchronous workloads execute reliably.
- **Karma**: Directory participation score controlling trust levels and moderation privileges.
- **LinkClick Partition**: Monthly Postgres table storing click events, enabling efficient retention enforcement.
- **MinIO**: Local S3-compatible service used only in developer environments to mimic Cloudflare R2 behavior.
- **OpenTelemetry**: Observability standard for propagating traces across services and async jobs.
- **PostGIS Radius Query**: Spatial SQL enabling marketplace searches constrained to 5-250 mile ranges per Policy P11.
- **Qute Template**: Server-side view file delivering HTML shells plus metadata for SEO and React mounting.
- **RateLimitViolation**: Table logging overage events, including user or IP, action name, and timestamps for enforcement analytics.
- **Runbook**: Step-by-step operational document guiding responders through incidents or maintenance tasks.
- **Unit of Work (UoW)**: Transactional boundary inside Quarkus service methods ensuring consistent writes across tables.
- **Widget Config**: JSONB map storing per-widget preferences, e.g., RSS filters or weather location choices.
<!-- anchor: 5-3-future-metrics -->
### 5.3 Future Metrics & KPI Enhancements
- Expand SLO catalog to include queue latency targets per handler, AI tagging freshness windows, and screenshot turnaround times.
- Introduce leading indicators (budget burn rate, rate limit spikes, moderation backlog) so Ops can act before user-facing degradation occurs.
- Automate KPI rollups into shareable dashboards so leadership can monitor beta vs production deltas and plan feature flag rollouts with data.
- Tie KPIs to incentive structures or OKRs for each squad, ensuring accountability for uptime, compliance, and cost stewardship.
- Evaluate anomaly detection tooling to flag sudden shifts in click patterns that might indicate abuse or instrumentation failure.

<!-- anchor: 5-4-collaboration-roadmap -->
### 5.4 Cross-Team Collaboration Roadmap
1. Establish quarterly architecture syncs spanning homepage, storefront, calendar, and infra teams to reconcile shared module changes.
2. Build shared testing harnesses for OAuth flows, delayed job behaviors, and feature flag evaluation to avoid duplication.
3. Maintain rotating on-call schedules pairing developers with SREs for joint incident handling, reinforcing systems thinking.
4. Share analytics data (e.g., click trends, search queries) with product research teams while respecting consent constraints, enabling data-driven prioritization.
5. Coordinate compliance updates (privacy banners, consent toggles, retention policies) across all VillageCompute apps for consistent user messaging.

<!-- anchor: 6-2-glossary-extended -->
### 6.2 Additional Glossary Entries
- **Access Token Refresh Job**: Scheduled task renewing social OAuth tokens seven days before expiry per Policy P5.
- **AnalyticsOverviewType**: DTO powering admin dashboards summarizing click trends and user engagement.
- **Bump Promotion**: Marketplace add-on allowing sellers to reorder their listing for a fee, enforced via job-scheduled resets.
- **ClickTrackingResource**: REST endpoint logging clicks then redirecting to target URLs; essential for analytics accuracy.
- **DirectorySiteCategory**: Join table tracking votes, scores, and moderation status for each site per category.
- **ElasticSearch StatefulSet**: Kubernetes deployment type persisting search indices on attached volumes.
- **Feature Flag Cohort Hash**: Deterministic MD5 hash of flag key + user ID or session ID used to maintain rollout stability.
- **Good Sites Bubbling**: Logic promoting high-scoring subcategory links to parent listings when thresholds exceed configured values.
- **HPA**: Horizontal Pod Autoscaler; automatically scales deployment replicas based on metrics.
- **InboundEmailProcessor**: Scheduled service that polls IMAP for replies to masked listing emails and routes them into platform workflows.
- **K3s**: Lightweight Kubernetes distribution powering VillageCompute clusters.
- **LangChain Prompt**: Template describing AI categorization or tagging tasks, e.g., Good Sites classification prompts in Policy F13.14.
- **MarketplaceListingImages**: Table linking listing IDs to stored object keys, tracked for cleanup when status changes.
- **Mini Component Mount**: React island inserted into server-rendered Qute markup via `mounts.ts` helper.
- **Ops Analytics Portal**: Admin interface where rate limits, job queues, AI budgets, and click stats can be monitored interactively.
- **PostgreSQL Patroni**: HA framework orchestrating leader election and failover for the primary database cluster.
- **Quarkus Scheduler**: Framework scheduling recurring jobs (feed refresh, weather updates) within the application runtime.
- **RateLimitService**: Component enforcing per-action quotas using Caffeine caches and logging violations.
- **ScreenshotService**: Quarkus bean managing jvppeteer browser pools for Good Sites and listing captures.
- **StorageGateway**: Abstraction uploading binary assets to Cloudflare R2 with metadata tagging and URL generation.
- **UserPreferencesType**: JSONB schema storing layout, topics, watchlists, locations, themes, and widget configs per user.
<!-- anchor: 6-3-glossary-supplement -->
### 6.3 Glossary Supplement
- **FeatureFlagEvaluation**: Partitioned log capturing each flag decision with consent-aware identifiers for 90 days.
- **Job Queue Backlog Threshold**: Configured count of pending jobs that triggers alerts and auto-scaling actions.
- **LangChain4j Connector**: Plugin bridging Quarkus services to third-party AI providers; swappable when new vendors join.
- **Puppeteer Semaphore**: Concurrency throttle ensuring screenshot captures never exceed configured browser pool size per pod.
- **Quarkus Dev Services**: Local development conveniences (Postgres, Elasticsearch) intentionally avoided in prod but useful for engineers.
- **Stripe Webhook Endpoint**: Secure HTTP path ingesting payment events, verifying signatures, and updating marketplace state.
- **Trace Sampling Policy**: Rules governing how many OpenTelemetry traces get recorded under normal vs incident conditions.
- **Widget Layout Schema**: JSON definition validating widget coordinates, preventing overlaps across 12-column desktop grid.
