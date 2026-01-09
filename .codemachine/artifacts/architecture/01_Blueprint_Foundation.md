<!-- anchor: 01-blueprint-foundation -->
# 01_Blueprint_Foundation.md

<!-- anchor: 1-0-project-scale-directives -->
### **1.0 Project Scale & Directives for Architects**

*   **Classification:** Large
*   **Rationale:** The platform fuses a customizable homepage, classifieds marketplace, curated directory, public profiles, and AI-enhanced content services on a Java 21 + Quarkus stack, with strict compliance, PostGIS, Elasticsearch, delayed-job orchestration, and multi-channel integrations; this breadth, coupled with numerous domain tables and background workloads, clearly exceeds medium-scale boundaries.
*   **Core Directive for Architects:** This is a **Large-scale** program; every blueprint MUST emphasize horizontal scalability, Panache-based modularity, and separation between experience delivery, domain engines, async workers, and integration adapters so independent teams can evolve features without cross-cutting regressions.
*   **Execution Mandate:** Architects MUST treat feature policies P1-P14 as hard constraints, drive reuse of standards from sibling repos (village-storefront, village-calendar), and produce designs that allow parallel development by homepage, marketplace, AI, and ops squads with minimal coordination overhead via explicit contracts.
*   **Quality Gate:** All downstream designs MUST preserve the 80%+ automated coverage target, align with Sonar quality gates, and assume multi-env rollout (beta → prod) under feature flag control to enable safe experimentation.

<!-- anchor: 2-0-standard-kit -->
### **2.0 The "Standard Kit" (Mandatory Technology Stack)**

*   **Architectural Style:** Modular layered monolith in Quarkus 3.26.x with clearly partitioned packages (api, data, integration, services, jobs) and delayed-job orchestration for async work; Hibernate Search + Elasticsearch backplane for search domains.
*   **Frontend:** Server-rendered Qute templates with targeted React 18 TypeScript mounts bootstrapped via `mounts.ts`, gridstack.js for widget layout, Ant Design system (antd + @antv visual suites) for UI, and esbuild-based bundling triggered through `frontend-maven-plugin` (P8) aligning with Maven lifecycle.
*   **Backend Language/Framework:** Java 21 LTS with Quarkus, Panache ActiveRecord for data access, LangChain4j bindings for Claude/AI tasks, jvppeteer for screenshot capture, Quarkus OIDC, and Quarkus Scheduler for recurring jobs referencing village-calendar delayed job style.
*   **Database(s):** PostgreSQL 17 primary with PostGIS extension (P6), JSONB-heavy tables for preferences/layouts, dedicated audit/analytics tables, and Hibernate ORM 2nd-level cache via Caffeine; Elasticsearch 8 cluster for Hibernate Search indices; Redis-like caches are not permitted beyond the ORM cache.
*   **Cloud Platform:** VillageCompute-managed Kubernetes (k3s) cluster with infra defined in sibling repo; assumes Cloudflare R2 (S3-compatible) for object storage plus CDN distribution, Mailpit-compatible SMTP for local dev, and integration env parity across beta/prod endpoints.
*   **Containerization:** Jib-powered OCI images from Maven builds, running on Kubernetes deployments with per-queue worker toggles (P12) and memory sizing to accommodate Puppeteer browsers; docker-compose stack for local Postgres/PostGIS, Elasticsearch, MinIO, Mailpit, Jaeger.
*   **Messaging/Queues:** Database-backed delayed job tables with explicit queue segregation (DEFAULT/HIGH/LOW/BULK/SCREENSHOT) and Quarkus scheduled enqueuers; no external brokers allowed, but jobs MUST respect exponential backoff and policy-specific throttles (P10, P12).
*   **AI Runtime:** LangChain4j abstraction over Anthropic Claude-Sonnet 4 (initial provider), cost-limited by `ai_usage_tracking` governance (P2/P10) with batch evaluation pipelines triggered post-feed ingestion and for Good Sites auto-categorization.
*   **CI/CD Expectations:** Single `./mvnw package` artifact containing backend and compiled TypeScript assets; Quarkus dev services for local iterations, SonarCloud scans on merge, and feature-flag-based safe rollout toggles for risky modules (stocks/social/promotions).

<!-- anchor: 3-0-rulebook -->
### **3.0 The "Rulebook" (Cross-Cutting Concerns)**

*   **Feature Flag Strategy:** Enforce Policy P7—flags stored in database tables with rollout percentages based on stable cohort hashing (user ID or session hash for anonymous); every net-new widget, integration, or admin view MUST launch behind a flag with analytics logging gated by consent (P14) and evaluation logs purged inside 90 days.
*   **Observability (Logging, Metrics, Tracing):** Emit structured JSON logs to stdout (captured by k3s) with request IDs propagated through REST, jobs, and LangChain4j flows; expose metrics via SmallRye Metrics, include Delayed Job health gauges, and wire OpenTelemetry exporters to Jaeger for tracing feed refreshes, AI tagging, screenshot capture, and marketplace payment flows.
*   **Security:** Quarkus OIDC handles Google/Facebook/Apple sign-in; JWT enforcement on REST resources with `@RolesAllowed` mirroring village-storefront roles; anonymous accounts tracked with secure `vu_anon_id` cookie (P9); every integration credential (Stripe, Meta, S3, AI) injected through config properties and rotated via Kubernetes secrets; inter-feature boundaries treat FeatureFlagService, Auth guards, and RateLimitService as authorities.
*   **Data Governance & Compliance:** Implement GDPR/CCPA flows (P1, P14) with consent modal, merge audit logs, export/deletion endpoints, and immediate purge of feature flag evaluations on account deletion; apply retention windows (90-day audits, indefinite screenshots per P4, indefinite social data per P5/P13) with scheduled cleanup jobs; ensure account merges reference `account_merge_audit` and soft deletes respect 90-day purge timers.
*   **Cost & Budget Controls:** All AI workloads must run through AiTaggingBudgetService (P2/P10) so feeds, Good Sites categorization, and fraud detection share the $500/month ceiling; screenshot storage assumes ~$100/month and uses WebP compression (P4); marketplace payment refunds abide by Policy P3 with audit trails, ensuring ops dashboards get automated budget threshold alerts (75/90/100%).
*   **Background Jobs & Rate Limiting:** Every async action (feeds, weather, stocks, social, listings, screenshot capture, profile view aggregation, link health, click stats rollups) MUST enqueue a delayed job payload conforming to the shared handler contract; RateLimitService tiering (F14.2) is mandatory for search, submissions, voting, listing contact, and API endpoints and must feed violation logs for admin dashboards.
*   **Performance & Caching:** Cache immutable reference data via Hibernate 2nd-level cache; rely on PostGIS indexes for geographic queries (≤250 miles per P11) and Hibernate Search + Elasticsearch for textual queries; widget layout fetching, anonymous merges, and profile rendering must remain within target p95 latencies (<100ms for key queries) by keeping read models slim and offloading heavy processing to jobs; CDN-signed URLs serve media to keep app pods stateless.
*   **Deployment Governance:** Beta and production share identical artifacts; feature flags differentiate experiences; Quarkus config profiles MUST separate secrets and endpoints; SLOs per module (e.g., screenshot queue throughput, feed ingest cadence) are tracked and reported through ops dashboards so ops/doc architects can codify runbooks.

<!-- anchor: 4-0-blueprint -->
### **4.0 The "Blueprint" (Core Components & Boundaries)**

*   **System Overview:** Village Homepage is a layered Quarkus application where Qute-rendered experiences orchestrate personalized widgets from domain services (news, weather, stocks, social), while parallel sub-systems (Marketplace, Good Sites, Public Profiles) share infrastructure but interact through REST DTOs, FeatureFlagService gating, and delayed jobs for IO-heavy work.
*   **Core Architectural Principle:** Enforce strict Separation of Concerns—experience shell renders UI via DTOs; each domain service owns its Panache models, jobs, and integrations; asynchronous pipelines isolate network-/AI-intensive work; shared utilities (RateLimitService, StorageService, FeatureFlagService) expose narrow APIs to avoid cross-layer leakage.
*   **Key Components/Services:**
    *   **ExperienceShell (Qute + React Islands):** Server-rendered layouts, widget mount orchestration, SEO/meta tags, gridstack configuration delivery, and anchor for analytics mount points; consumes typed DTOs only.
    *   **AuthIdentityService:** Implements bootstrap endpoint, OAuth/OIDC login, anonymous account issuance, merge workflows per P1/P9, and role/permission enforcement synced with village-storefront schema.
    *   **UserPreferenceService:** Manages JSONB preferences (layout, topics, watchlists, weather locations, widget configs), feature flag cohorts, consent flags, and profile template selections with Panache models and validation.
    *   **FeedAggregationService:** Handles RSS source management, feed ingestion jobs, deduplication, user subscription filtering, AI tagging requests, and interest-ranked result assembly; respects P2 cost rules.
    *   **AiTaggingService:** LangChain4j orchestrator for tagging feed items, fraud content analysis, Good Sites categorization assistance, and screenshot metadata enrichment, constrained by AiTaggingBudgetService decisions and evaluation logging.
    *   **WeatherService:** Integrates Open-Meteo + NWS (per P4) with caching, hourly refresh jobs, severe alert polling, and user location resolution; surfaces multi-location data to widgets and public profiles.
    *   **StockService:** Alpha Vantage integration with watchlist persistence, refresh cadence controls (market hours, after hours, weekends), sparkline prep, and graceful degradation when rate limits hit.
    *   **SocialIntegrationService:** Meta Graph API client for Instagram + Facebook, token management per P5/P13, proactive refresh jobs, cached post retention, reconnect banner state machine, and feature-flag-triggered disablement.
    *   **MarketplaceService:** Owns categories, listings, payments (Stripe), promotions, search (PostGIS + Elasticsearch), moderation queue, refunds (P3), fraud heuristics, email relay, and listing lifecycle jobs (expiration, reminders, image processing).
    *   **DirectoryService (Good Sites):** Category hierarchy, submissions, karma-based moderation, voting, screenshot capture pipeline, metadata extraction, bubbling logic, click ranking, AI categorization, and link health monitoring.
    *   **ProfileService:** User profiles, template configs (public_homepage, your_times, your_report), curated article management, slot assignment, publication workflow, SEO metadata, and view aggregation jobs.
    *   **ClickTrackingService:** `/track/click` endpoint, raw partitioned logging, rollup jobs (category + item stats), analytics DTOs for admin dashboards, and data retention enforcement.
    *   **FeatureFlagService:** Implements Policy P7/P14 evaluation, logging, auditing, whitelist overrides, and admin management API powering rollout controls across modules.
    *   **StorageGateway:** Abstraction over Cloudflare R2 for listings, screenshots, avatars, social media, ensuring WebP conversion, thumbnail generation, signed URL creation, and version history retention per policy.
    *   **JobOrchestrator:** Consolidated delayed job framework referencing `DelayedJobService`, queue configuration, concurrency toggles (P12), telemetry for failures, and policy-specific throttles (AI budget, screenshot pool size).
    *   **OpsAnalyticsPortal:** Admin REST + UI endpoints for rate limits, job health, feed errors, click analytics, AI budget usage, fraud flags, and compliance exports; integrates with AntV charts via React mounts.

*   **Interaction Notes:**
    *   ExperienceShell consumes versioned DTOs from each domain service through REST resources in `api/rest`; it never accesses Panache entities directly.
    *   Domain services interact via asynchronous jobs or explicit REST APIs (e.g., Marketplace ingest uses ClickTrackingService via HTTP, not shared DB tables).
    *   Shared utilities (FeatureFlagService, RateLimitService, StorageGateway) are injected as Quarkus beans; their interfaces MUST be stable to prevent cascading refactors.
    *   Integrations (Meta, Alpha Vantage, Open-Meteo, Stripe, LangChain4j, S3/R2) MUST reside under `integration/*` packages with DTOs bridging to services, ensuring swap-ability and mocking in tests.

<!-- anchor: 5-0-contract -->
### **5.0 The "Contract" (API & Data Definitions)**

*   **Primary API Style:** RESTful APIs documented via OpenAPI; `/api` namespace for authenticated JSON endpoints, `/admin/api` for privileged analytics/ops, `/track/click` for redirects, and Qute-rendered HTML for public endpoints; DTOs live in `api/types` and version bumping is mandatory when breaking changes occur.
*   **Data Model - Core Entities:**
    *   **User:** `id`, `email`, `oauth_provider`, `oauth_id`, `display_name`, `avatar_url`, `preferences` (JSONB), `is_anonymous`, `directory_karma`, `analytics_consent`, `created_at`, `updated_at`.
    *   **UserPreferencesType (JSONB contract):** `layout.widgets[]`, `newsTopics[]`, `followedPublications[]`, `stockWatchlist[]`, `weatherLocations[]`, `theme`, `widgetConfigs`, version metadata.
    *   **AccountMergeAudit:** `id`, `anonymous_user_id`, `authenticated_user_id`, `merged_data_summary`, `consent_given`, `consent_timestamp`, `ip_address`, `user_agent`, `purge_after`, `created_at`.
    *   **RssSource:** `id`, `name`, `url`, `category`, `is_system`, `user_id`, `refresh_interval_minutes`, `status`, `last_fetched_at`, `error_count`.
    *   **FeedItem:** `id`, `source_id`, `title`, `url`, `description`, `image_url`, `published_at`, `ai_tags`, `fetched_at`, `created_at`.
    *   **AiUsageTracking:** `id`, `month`, `provider`, `total_requests`, `total_tokens_input`, `total_tokens_output`, `estimated_cost_cents`, `budget_limit_cents`, `updated_at` (drive enforcement per P2/P10).
    *   **WeatherCache:** `id`, `location_key`, `provider`, `current_data`, `forecast_data`, `alerts`, `fetched_at`, `expires_at`.
    *   **StockQuote:** `id`, `symbol`, `company_name`, `price`, `change`, `change_percent`, `market_cap`, `sparkline`, `updated_at`.
    *   **SocialToken:** `id`, `user_id`, `provider`, `access_token` (encrypted), `refresh_token`, `expires_at`, `scopes`, `last_refresh_attempt`, `status` (connected, stale, archived).
    *   **SocialPost:** `id`, `user_id`, `provider`, `external_id`, `content`, `media_url`, `engagement_metrics`, `posted_at`, `fetched_at`, `is_archived`.
    *   **MarketplaceCategory:** `id`, `parent_id`, `name`, `slug`, `sort_order`, `is_active`, `fee_schedule`, `created_at`, `updated_at`.
    *   **MarketplaceListing:** `id`, `user_id`, `category_id`, `city_id`, `title`, `description`, `price`, `is_free`, `contact_for_price`, `latitude`, `longitude`, `location_text`, `contact_email_masked`, `contact_phone`, `status`, `view_count`, `flag_count`, `expires_at`, `published_at`, `created_at`, `updated_at`.
    *   **ListingPromotion:** `id`, `listing_id`, `type` (featured, bump), `stripe_payment_intent_id`, `amount_cents`, `starts_at`, `expires_at`, `created_at`.
    *   **PaymentRefund:** `id`, `stripe_payment_intent_id`, `stripe_refund_id`, `listing_id`, `user_id`, `amount_cents`, `reason`, `status`, `reviewed_by_user_id`, `review_notes`, `created_at`, `processed_at`.
    *   **DirectoryCategory:** `id`, `parent_id`, `name`, `slug`, `description`, `icon_url`, `sort_order`, `link_count`, `is_active`, `created_at`, `updated_at`.
    *   **DirectorySite:** `id`, `url`, `domain`, `title`, `description`, `screenshot_url`, `screenshot_captured_at`, `og_image_url`, `favicon_url`, `custom_image_url`, `submitted_by_user_id`, `status`, `last_checked_at`, `is_dead`, `created_at`, `updated_at`.
    *   **DirectorySiteCategory:** `id`, `site_id`, `category_id`, `score`, `upvotes`, `downvotes`, `rank_in_category`, `submitted_by_user_id`, `approved_by_user_id`, `status`, `created_at`, `updated_at`.
    *   **DirectoryVote:** `id`, `site_category_id`, `user_id`, `vote`, `created_at`, `updated_at`.
    *   **ScreenshotVersion:** `id`, `site_id`, `version_number`, `storage_key_thumbnail`, `storage_key_full`, `captured_at`, `file_size_bytes`, `is_current`, `created_at` (implements P4 versioning and retention).
    *   **UserProfile:** `id`, `user_id`, `username`, `display_name`, `bio`, `avatar_url`, `location_text`, `website_url`, `social_links`, `template`, `template_config`, `is_published`, `view_count`, `created_at`, `updated_at`.
    *   **ProfileCuratedArticle:** `id`, `profile_id`, `feed_item_id`, `original_url`, `original_title`, `original_description`, `original_image_url`, `custom_headline`, `custom_blurb`, `custom_image_url`, `slot_assignment`, `is_active`, `created_at`, `updated_at`.
    *   **FeatureFlag:** `id`, `key`, `name`, `description`, `is_enabled`, `rollout_percentage`, `user_whitelist`, `purpose`, `analytics_enabled`, `updated_by_user_id`, `updated_at`, `created_at`.
    *   **FeatureFlagEvaluation:** `id`, `flag_key`, `user_id`, `session_hash`, `is_authenticated`, `result`, `evaluated_at` (partitioned for 90-day retention, deletable per GDPR).
    *   **DelayedJob:** `id`, `queue`, `handler_class`, `payload`, `run_at`, `locked_at`, `locked_by`, `attempts`, `last_error`, `failed_at`, `created_at`, `updated_at`.
    *   **LinkClick:** `id`, `click_date`, `click_timestamp`, `click_type`, `target_id`, `target_url`, `user_id`, `session_id`, `ip_address`, `user_agent`, `referer`, `category_id` (partitioned ranges, retention 90 days).
    *   **ClickStatsDaily / ClickStatsDailyItems:** Aggregated rollup tables keyed by `stat_date`, `click_type`, `category_id`/`target_id`, storing `total_clicks`, `unique_users`, `unique_sessions`, `trend data`, `created_at`, `updated_at` for analytics views.

<!-- anchor: 6-0-safety-net -->
### **6.0 The "Safety Net" (Ambiguities & Assumptions)

*   **Identified Ambiguities:**
    *   Clarification needed on third-party social networks beyond Meta (e.g., future X/Twitter support) and whether connectors must be stubbed now.
    *   Marketplace moderation triggers mention AI heuristics but lack thresholds/automation extent.
    *   Directory karma thresholds specify numeric gates but omit downgrade mechanics or automated demotion criteria.
    *   Public profile templates reference markdown/rich text but omit sanitization and allowed embeds policy.
    *   Screenshot pool scaling policy (P12) notes per-pod settings yet not the global queue prioritization during traffic spikes.
    *   Payment workflows cite Stripe metadata but do not define webhook event payload contracts or reconciliation cadence.
    *   Email relay addresses are prescribed, but inbound handling specifics (bounce management, spam filtering) remain open.
    *   Feature flag analytics retention/purge interplay with click tracking is underspecified—no guidance on correlating cohorts with link stats after anonymization.
*   **Governing Assumptions:**
    *   Non-Meta social integrations are deferred; architects must design pluggable adapters but only implement Meta Graph API flows for v1, gating future networks behind flags.
    *   Marketplace AI fraud engine only flags/listings for human review; automatic takedowns require manual moderator confirmation, so Behavior and Ops architects must retain human-in-loop steps.
    *   Directory karma demotions occur via moderator/Admin UI actions rather than automated decay; architects should expose APIs for manual adjustments and log all changes for audit.
    *   Public profile markdown content MUST be sanitized (CommonMark subset + allowlist for embeds) using server-side sanitization utilities, and Behavior architect should define embed whitelist (YouTube, Vimeo, social posts) with CSP updates.
    *   Screenshot queue prioritization: SCREENSHOT queue jobs use FIFO with configurable concurrency per pod; during spikes, JobOrchestrator may assign dedicated pods via env vars but not auto-scale browsers beyond defined pool size unless Ops approves.
    *   Stripe webhooks: assume standard events (`payment_intent.succeeded`, `payment_intent.payment_failed`, `charge.refunded`) delivered via signed webhook endpoint with idempotent handling; reconciliation job runs daily to cross-check promotions/refunds.
    *   Email relay: InboundEmailProcessor filters spam via basic heuristics (DKIM/SPF checks optional) and stores failed parsing attempts for manual review; bounce handling is deferred but mailbox polling MUST idempotently skip previously processed Message-IDs.
    *   Feature flag analytics are decoupled from click tracking; when consent withdrawal occurs, feature flag evaluation data is purged immediately, but click stats remain aggregated without personal identifiers; analytics dashboards must respect this by using only aggregated click tables for cohort comparisons.

<!-- anchor: 1-1-delivery-pillars -->
#### **1.1 Delivery Pillars**

*   **Team Topology Alignment:** ExperienceShell, MarketplaceService, DirectoryService, and AI/Intelligence squads MUST operate as semi-autonomous streams while sharing cross-cutting enablement pods (AuthIdentityService, FeatureFlagService, StorageGateway) to prevent ownership overlap.
*   **Progressive Disclosure of Complexity:** Architects must stage releases through feature flags so anonymous homepage → authenticated personalization → marketplace → directory → profiles can be rolled out sequentially while backend foundations (click tracking, delayed jobs, AI cost controls) exist from day one.
*   **Inter-Repo Continuity:** Reuse conventions from `village-storefront` for permissions/roles and `village-calendar` for job orchestration to reduce decision churn; deviations require Foundation Architect approval.
*   **Documentation Discipline:** Each architect supplies ADR-style notes when diverging from this foundation; Ops_Docs architect collates them into ops runbooks to guarantee operational handoff is synchronized with code drops.

<!-- anchor: 1-2-risk-radar -->
#### **1.2 Risk Radar**

*   **Scope Creep:** Marketplace + Good Sites + Profiles risk runaway requirements; Behavior architect must push any net-new module that lacks policy coverage into v2 backlog.
*   **AI Budget Exhaustion:** Feed ingestion and fraud detection share the same Claude budget; Structural architect must design instrumentation hooks to downshift gracefully before P10 thresholds hit.
*   **Compliance Exposure:** Data merges, analytics consent, and retention windows require early legal review; Ops_Docs architect must template consent notices and purge scripts before beta launch.
*   **Infrastructure Load:** Screenshot capture and Elasticsearch indexing strain pods; plan dedicated worker pools and resource quotas now instead of retrofitting post-launch.

<!-- anchor: 2-1-frontend-governance -->
#### **2.1 Frontend Governance**

*   **Mount Registry:** Maintain a single `mounts.ts` registry mapping `data-component` identifiers to React components; Behavior architect must define prop schemas in TypeScript interfaces stored beside components for discoverability.
*   **Gridstack Profiles:** Desktop/tablet/mobile breakpoints and widget resizing rules must be encoded via shared JSON config served from UserPreferenceService to avoid drift between server-rendered layout and client interactions.
*   **Ant Design Theming:** Use ConfigProvider tokens to align brand colors and dark-mode toggles; expose theme tokens through FeatureFlagService so experiments (e.g., high-contrast view) can be toggled without redeploying.
*   **Asset Pipeline:** `frontend-maven-plugin` ensures `npm ci` + `npm run build` fold into Maven phases; build failures fail the entire pipeline, preventing backend-only deploys with stale JS.
*   **Type Safety:** All fetches from Qute data attributes to TypeScript components must pass through zod-esque runtime validation (or handcrafted guards) before React render, preventing corrupted JSONB preferences from breaking UI islands.

<!-- anchor: 2-2-backend-build-testing -->
#### **2.2 Backend Build & Testing Discipline**

*   **Testing Pyramid:** Unit tests for services/integration clients, Panache entity tests via Quarkus `@QuarkusTest` with Testcontainers Postgres/PostGIS, contract tests for REST resources (OpenAPI snapshots), and job handler tests using in-memory delayed-job tables.
*   **Coverage Enforcement:** Failing the 80% line/branch coverage gate blocks merge; architects must define critical-path tests for anonymous merge flow, AI budget transitions, marketplace payments, and screenshot pool usage.
*   **Static Analysis:** Spotless (Java + TypeScript) runs before packaging; SonarCloud rules for injection safety, SQL parameterization, and nullability must be enforced; any suppression requires documented rationale in code review.
*   **Build Profiles:** `%dev`, `%test`, `%prod` config segments load different secrets/ports; Behavior architect should not rely on dev-only features such as Quarkus Dev Services for production flows.

<!-- anchor: 2-3-data-infra-baseline -->
#### **2.3 Data & Infrastructure Baseline**

*   **Migrations:** MyBatis migrations own schema changes; Structural architect ensures policy tables (feature_flag_audit, ai_usage_tracking, account_merge_audit, payment_refunds) get indexes per usage patterns (lookups by user_id, created_at).
*   **Search Infrastructure:** Elasticsearch container co-located in dev; prod cluster managed separately but requires index templates defined in code to avoid manual drift; Behavior architect must design search queries that degrade gracefully when ES is unavailable (fallback to Postgres).
*   **Object Storage Namespacing:** StorageGateway prefixes (e.g., `screenshots/`, `listings/`, `profiles/`) must align with retention rules; version history for screenshots stored via `directory_screenshot_versions` linking to object keys.
*   **Network Access:** Outbound calls (Alpha Vantage, Open-Meteo, Meta Graph, Stripe) must run through HTTP clients with circuit breakers/retry policies defined centrally to avoid inconsistent behavior.

<!-- anchor: 3-1-observability-implementation -->
#### **3.1 Observability Implementation Blueprint**

*   **Logging Fields:** Every request log includes `trace_id`, `span_id`, `user_id/anon_id`, `feature_flags`, `request_origin`, `rate_limit_bucket`, and `job_id` when applicable.
*   **Metrics Catalog:** Define gauges for delayed job depth per queue, timers for feed ingestion latency, counters for AI tag batches, histograms for screenshot capture duration, and meters for rate-limit violations.
*   **Tracing Coverage:** Instrument feed refresh, AI tagging, screenshot capture, payment lifecycles, and inbound email flows; propagate trace context across async jobs using job payload metadata.
*   **Alerting Hooks:** Observability stack must emit alerts for AI budget >90%, screenshot queue backlog > threshold, Elasticsearch unreachable, Stripe webhook failures, and OIDC login errors > baseline.

<!-- anchor: 3-2-security-hardening -->
#### **3.2 Security Hardening Guidelines**

*   **Token Storage:** Encrypt social tokens and Stripe secrets with Quarkus Vault or KMS-backed secret engine; never log tokens even at debug level.
*   **Input Sanitization:** All user-generated content (profiles, marketplace descriptions, Good Sites submissions) must flow through sanitizers before persistence and again before rendering to prevent stored XSS.
*   **Rate Limit Enforcement:** Use combined keys (user_id/IP/user_agent) for sensitive endpoints (votes, submissions, OAuth merges); violation logs feed ops dashboards and can auto-trigger temporary bans.
*   **Audit Trails:** Feature flag changes, admin impersonations, refunds, and karma adjustments require audit events referencing actor, timestamp, and reason; stored in dedicated tables for forensic review.
*   **Secrets Rotation:** Provide documented steps (Ops_Docs) for rotating OAuth credentials, Stripe keys, and S3 credentials without downtime by leveraging Kubernetes secrets + Quarkus live reload.

<!-- anchor: 3-3-data-lifecycle -->
#### **3.3 Data Lifecycle & Retention Controls**

*   **Partition Strategy:** `link_clicks` partitions created monthly via pg_partman; retention job drops partitions older than 90 days while ensuring rollups exist.
*   **Soft vs Hard Delete:** Listings/platform records often soft-delete for audit; GDPR deletion path must cascade to remove personal data (preferences, evaluations, social tokens) synchronously.
*   **Versioned JSONB:** Store `schema_version` inside JSONB preference/layout blobs; migration jobs upgrade historical records, preventing runtime logic from branching on null fields.
*   **Export Tooling:** Provide asynchronous export job leveraging Delayed Job queue, storing zipped JSON in R2 with signed download links; Behavior architect ensures export includes layout, feeds, marketplace posts, Good Sites submissions, and social data per policy.

<!-- anchor: 3-4-async-playbook -->
#### **3.4 Asynchronous Playbook**

*   **Queue Ownership:** Each job handler declares queue + concurrency expectations; e.g., `AiTaggingJobHandler` in BULK with limited concurrency, `ListingExpirationJobHandler` in DEFAULT daily, `ScreenshotCaptureJobHandler` in SCREENSHOT.
*   **Payload Design:** Payloads must remain serializable JSON referencing entity IDs, not nested full entities; include schema version and tracer metadata.
*   **Retry Semantics:** Use exponential backoff with max attempts defined per handler (AI jobs 3, screenshot 5, email relay 10); persist terminal errors for ops consoles.
*   **Idempotency:** Idempotent keys (feed source + published_at, listing IDs, screenshot version) prevent duplicate processing when workers retry.

<!-- anchor: 4-1-component-interactions -->
#### **4.1 Component Interaction Matrix**

*   **ExperienceShell ↔ Domain Services:** Interactions occur via REST resources returning DTOs; ExperienceShell never queries Panache models; caching happens in domain services.
*   **UserPreferenceService ↔ FeatureFlagService:** Preference reads include flag cohorts; updates to consent/analytics propagate to FeatureFlagEvaluation purge routines.
*   **FeedAggregationService ↔ AiTaggingService:** After feed ingestion, FeedAggregationService enqueues AI tagging jobs with dedupe tokens; AiTaggingService writes tags back and logs cost usage.
*   **MarketplaceService ↔ StorageGateway:** Listing uploads stream via signed URLs; StorageGateway returns keys persisted in `marketplace_listing_images` and handles clean-up when listings expire.
*   **DirectoryService ↔ ScreenshotService:** Submissions trigger screenshot job; DirectoryService monitors job results before publishing site entries; screenshot failures fallback to OG images.
*   **ProfileService ↔ ClickTrackingService:** Public profile widgets link through `/track/click`; ClickTrackingService attaches profile template metadata for analytics dashboards.
*   **OpsAnalyticsPortal ↔ All Services:** Ops portal reads aggregated stats only; it must not hit operational tables with heavy queries—use rollups, job metrics, and event logs.

<!-- anchor: 4-2-async-workload-matrix -->
#### **4.2 Async Workload Matrix**

*   **DEFAULT Queue:** Feed refresh, weather refresh, listing expirations, click stats rollups, link health recalculations.
*   **HIGH Queue:** Stock refresh, message relay (email contact), critical inbound email parsing.
*   **LOW Queue:** Anonymous cleanup, profile view aggregation, metadata refresh, rate limit violation aggregation.
*   **BULK Queue:** AI tagging, screenshot capture batch refreshes, marketplace image processing, bulk Good Sites imports.
*   **SCREENSHOT Queue:** Dedicated per P12, limited concurrency per pod; jobs contain capture presets (thumbnail/full) and version sequencing info.
*   **Runbook Guidance:** Ops_Docs architect must map queue thresholds to scaling actions (e.g., spawn dedicated BULK worker when backlog > 500 jobs for >15 min).

<!-- anchor: 4-3-deployment-matrix -->
#### **4.3 Deployment & Environment Matrix**

*   **Local:** docker-compose for Postgres/PostGIS, Elasticsearch, Mailpit, MinIO, Jaeger; developers run `./mvnw quarkus:dev` plus optional `npm run watch`.
*   **Beta:** Mirrors production infra but flagged features (stocks, social integration, promoted listings) remain disabled unless QA cohort assigned; synthetic data populates marketplace/directory to validate load.
*   **Production:** Multi-replica pods with dedicated queue workers, feature flags controlling blast radius, CDN-backed assets, Stripe live keys, AI budget monitors tied into alerting.
*   **Release Flow:** Git mainline merges trigger CI → Sonar → container build → deploy to beta → smoke tests → progressive flag rollouts → prod deployment once metrics stable.

<!-- anchor: 5-1-api-surface -->
#### **5.1 API Surface & Endpoint Families**

*   **Public Web Endpoints:** `/`, `/marketplace/*`, `/good-sites/*`, `/u/{username}` served via Qute; they rely on server-side caching and structured data injection for SEO.
*   **Authenticated REST (`/api`):** Widgets (`/api/widgets/news`, `/api/widgets/weather`), preferences (`/api/preferences`), marketplace listing CRUD, Good Sites submissions, profile authoring, click tracking opt-outs.
*   **Admin REST (`/admin/api`):** Feed management, feature flags, rate limit configs, analytics dashboards, AI budget overview, job queue health.
*   **Integration Endpoints:** `/track/click`, Stripe webhooks `/webhooks/stripe`, inbound email webhook/polling entry, OAuth callback endpoints.
*   **OpenAPI Contract:** Maintained in `src/main/resources/openapi.yaml`; Behavior architect updates contract before implementation; clients rely on generated DTOs.

<!-- anchor: 5-2-dto-versioning -->
#### **5.2 DTO Versioning & Compatibility**

*   **Version Tags:** DTOs include `version` field; major changes increment number and new fields default to backward-compatible values.
*   **Deprecation Policy:** Maintain previous DTO behavior for at least two beta cycles; ExperienceShell toggles new behavior via feature flags to gradually migrate.
*   **Validation:** `api/types` records enforce mandatory fields; REST resources validate inbound payloads using Bean Validation annotations before service invocation.
*   **Serialization:** Use Jackson with snake_case JSON for API responses; Qute obtains DTOs pre-serialized to avoid template logic performing transformations.

<!-- anchor: 5-3-data-validation -->
#### **5.3 Data Validation & Integrity Rules**

*   **Widget Layout Schema:** Validate widget coordinates against grid bounds (12 columns desktop, etc.); reject overlapping or out-of-range placements server-side.
*   **Marketplace Listings:** Title/description length enforced, price validation (non-negative), image count <= 12, location requirement (city_id + lat/lon) validated against PostGIS.
*   **Directory Submissions:** URLs canonicalized (HTTPS enforced), metadata fetch success required before publish, duplicate detection by domain + normalized path.
*   **Profiles:** Username uniqueness check case-insensitive, reserved names referenced via `reserved_usernames`, bio length and markdown sanitized.
*   **Payments:** Posting fees + promotions validated against category fee schedule; Stripe responses recorded atomically with listing state transitions to avoid orphaned charges.

<!-- anchor: 6-1-ambiguity-mitigation -->
#### **6.1 Ambiguity Mitigation Playbook**

*   **Change Log:** Track clarifications per ambiguity in centralized ADR doc; each resolution references assumption it supersedes.
*   **Decision Latency:** If ambiguity blocks implementation >2 days, escalate to Foundation Architect for ruling; do not let squads improvise divergent behaviors.
*   **Design Workshops:** Structural architect runs mini-workshops with Behavior + Ops to resolve ambiguous flows (e.g., embed policy) before coding.
*   **User Research Loop:** Behavior architect coordinates usability tests for profile templates and marketplace flows to validate assumptions before locking UI contract.

<!-- anchor: 6-2-assumption-review -->
#### **6.2 Assumption Review Cadence**

*   **Sprint Boundary Checks:** At each sprint review, architects revisit Governing Assumptions; outdated ones convert into new requirements or removal notes in this blueprint addendum.
*   **Compliance Sync:** Monthly meeting with legal/compliance ensures GDPR consent, retention, and refund policies remain accurate; updates propagate to Ops runbooks and developer guides.
*   **Metrics-Driven Validation:** Click analytics, AI budget usage, and queue telemetry feed into assumption validation—if reality diverges (e.g., screenshot demand > forecast), update blueprint and assign mitigation tasks.
*   **Versioning:** Each blueprint revision increments semantic version; specialized architects must reference version numbers in their documents to ensure alignment.

<!-- anchor: 2-4-frontend-modularity -->
#### **2.4 TypeScript Module Partitioning**

*   **Widgets Bundle:** Contains React widgets (news, weather, stocks, social, quick links) plus shared hooks (useIntlDate, useChartTheme); tree-shake unused charts via dynamic imports triggered by `data-component` attributes.
*   **Admin Analytics Bundle:** Hosts AntV charts, data tables, and Qute-mounted dashboards; fetch clients include CSRF tokens and handle pagination/filter states via URL params for shareable links.
*   **Marketplace Bundle:** Manages form validation, drag/drop image uploads, and location selectors; integrates with PostGIS-backed endpoints for city search suggestions via debounced fetch.
*   **Profiles Bundle:** Offers template-specific editors (grid editor, article slot picker, link aggregator) with deterministic serialization matching backend template_config schema.
*   **Shared Utilities:** Provide instrumentation wrapper to send user interactions to ClickTrackingService via Beacon API fallback, ensuring consistent analytics even when JavaScript errors occur elsewhere.

<!-- anchor: 3-5-rate-limiting -->
#### **3.5 Rate Limiting Implementation Details**

*   **Bucket Storage:** In-memory Caffeine cache with eviction aligned to rate window; persistent violation logs captured in `rate_limit_violations` for ops review.
*   **Tier Assignment:** Anonymous users default to `anonymous` tier, logged-in to `logged_in`, and karma ≥10 or verified sellers to `trusted`; admin endpoints bypass user limits but still log requests.
*   **Blocklists:** Expose admin UI to block IP ranges or user IDs temporarily; block entries stored with TTL and audit metadata.
*   **Client Feedback:** REST endpoints return `429` with headers (`X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Retry-After`) so frontend can show friendly messaging.

<!-- anchor: 4-4-data-flow-narratives -->
#### **4.4 Data Flow Narratives**

*   **Anonymous Visit → Personalization:** Visitor receives `vu_anon_id`, ExperienceShell renders default layout, RateLimitService tracks interactions; as user customizes layout, UserPreferenceService persists JSONB tied to anonymous user; upon OAuth login, AuthIdentityService merges data and writes audit record while FeatureFlagService recomputes cohorts.
*   **Feed Refresh → AI Tagging → Widget Display:** Scheduled job fetches RSS, stores FeedItems, enqueues AiTagging job referencing feed IDs; AiTaggingService batches items, respects budget action from AiTaggingBudgetService, stores tags; ExperienceShell requests news widget API which filters by user topics + categories, sorts, and provides click-tracking URLs.
*   **Marketplace Listing Lifecycle:** Authenticated user submits listing via REST; StorageGateway stores images; listing enters `pending` status until moderation or auto-approval; expiration job transitions to `expired`, sending email reminders and cleaning up storage if listing removed.
*   **Good Sites Submission:** User submits URL; DirectoryService fetches metadata, enqueues screenshot, consults AI categorization; moderators approve, at which point DirectorySiteCategory updates scores and invites to vote; LinkClick logs feed analytics.
*   **Profile Curation:** User selects template, drags articles into slots; ProfileService persists template_config, triggers screenshot or metadata refresh for manual links; public visitors see curated layout with `track/click` wrappers feeding analytics.

<!-- anchor: 5-4-reporting-entities -->
#### **5.4 Reporting & Analytics Entities**

*   **RateLimitConfig:** `id`, `action_type`, `tier`, `limit_count`, `window_seconds`, `updated_by_user_id`, `updated_at`—drives runtime limiter settings.
*   **RateLimitViolation:** `id`, `user_id`, `ip_address`, `action_type`, `endpoint`, `violation_count`, `first_violation_at`, `last_violation_at`—feeds ops dashboards and potential bans.
*   **ClickStatsRollupJobPayload:** JSON with `date`, `scope` (category/item/all), `rebuild` (boolean) for reruns.
*   **AnalyticsOverviewType DTO:** `totalClicksToday`, `totalClicks7d`, `totalClicks30d`, `clicksByType`, `uniqueUsersToday`, `uniqueUsers7d`—contract for admin UI consumption.
*   **DailyTrendType DTO:** `date`, `totalClicks`, `uniqueUsers`, `uniqueSessions`—drives line charts; APIs guarantee chronological ordering.

<!-- anchor: 6-3-open-questions -->
#### **6.3 Outstanding Questions To Resolve**

*   **Meta Rate Limits:** Need confirmation on throughput per user/app to size SocialIntegrationService queues; Behavior architect to coordinate with Meta docs.
*   **Marketplace Promotion Inventory:** Clarify whether simultaneous featured listings per category are capped; Structural architect proposes default cap (e.g., 10) unless Product specifies otherwise.
*   **Directory Moderator Incentives:** Determine if karma bonuses or notifications exist when moderators approve submissions; Ops_Docs architect to capture policy.
*   **Inbound Email Attachments:** Decide size/type limits and whether attachments should be proxied or stripped before contacting listing owners.
*   **Profile SEO Customization:** Need guidance on allowing custom meta descriptions beyond bio; Behavior architect to outline editing UX and validation.
