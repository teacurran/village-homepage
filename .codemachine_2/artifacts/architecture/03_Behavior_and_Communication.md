<!-- anchor: 3-0-proposed-architecture -->
## 3. Proposed Architecture (Behavioral View)

Village Homepage orchestrates a layered set of Quarkus services so that every homepage render respects personalization, compliance, and cost constraints.
The behavioral blueprint focuses on how ExperienceShell drives downstream interactions across integration clients, job queues, and analytics observers.
Each interaction sequence honors the modular boundaries mandated by the foundation document, ensuring Panache entities stay hidden behind DTO-centric APIs.
Anonymous journeys, authenticated merges, and admin oversight all converge through consistent REST contracts that keep roles and policies enforceable.
Delayed job orchestration is highlighted wherever asynchronous processing protects request latency while preserving idempotent domain workflows.
Communication lines are annotated with the relevant policies (P1-P14) so that compliance, budgeting, and retention guardrails remain visible.
PlantUML notation captures the runtime handshake between ExperienceShell, FeatureFlagService, job orchestrators, and downstream widgets.
DTO definitions close the loop by expressing how payloads encode layout, click tracking, and marketplace intent data.
Sequence emphasis leans toward the homepage personalization run because it exercises authentication, feature flags, AI tagging, and click attribution together.
RESTful surfaces use JSON with snake_case fields; server-rendered HTML hydrates React islands based on the DTO payloads delivered here.
Rate limiting and consent checks intercept every journey early so that later services can trust the contextual headers they receive.
FeatureFlagService outputs are cached within the request to guarantee stable cohort treatment even when multiple widgets are fetched in parallel.
UserPreferenceService normalizes layouts for both anonymous and authenticated users, ensuring merge flows only reconcile once per OAuth handshake.
Integration services (weather, stocks, social) never talk to each other directly; ExperienceShell coordinates them via explicit DTO requests.
Marketplace, Directory, and Profile engines expose their behavior through dedicated endpoints to keep admin and public experiences decoupled.
ClickTrackingService and analytics rollups ride alongside every user action so that revenue and compliance dashboards stay precise.
StorageGateway controls access to Cloudflare R2 assets, issuing signed URLs only when services prove entitlement via their DTO context.
JobOrchestrator mediates every asynchronous path (AI tagging, screenshots, token refresh) and isolates queue semantics from business logic.
AiTaggingBudgetService centralizes spending decisions so that feed enrichment and fraud inspection can throttle together under Policy P2.
The behavioral view that follows documents these threads in detail, matching the mandated structure and anchor protocol.

*   **3.7. API Design & Communication:**

    *   **API Style:** Village Homepage adheres to the RESTful patterns codified in the foundation document, with JSON payloads and DTO records driving every interaction.
        - API Style Detail 01: REST resources live under /api for authenticated experiences and adopt JSON serialization with snake_case fields to match DTO contracts.
          Implication: This reinforces compliance guardrails while keeping service contracts observable.
        - API Style Detail 02: Anonymous bootstrap and consent flows remain RESTful, surfacing explicit status codes for GDPR opt-in gating before OAuth handoffs.
          Implication: This reinforces observability guardrails while keeping service contracts observable.
        - API Style Detail 03: Admin operations reside in /admin/api and share DTOs with the ExperienceShell only through well-defined Type records in api/types.
          Implication: This reinforces cost-control guardrails while keeping service contracts observable.
        - API Style Detail 04: Versioning is path-based (e.g., /api/v1/preferences) so beta experiments can coexist with prod without destabilizing clients.
          Implication: This reinforces personalization guardrails while keeping service contracts observable.
        - API Style Detail 05: Hypermedia-style link hints are embedded sparingly within DTOs to direct React mounts toward follow-up POST or PATCH operations.
          Implication: This reinforces performance guardrails while keeping service contracts observable.
        - API Style Detail 06: All REST controllers rely on Bean Validation and provide structured error payloads, keeping client retry logic deterministic.
          Implication: This reinforces feature-flag-governance guardrails while keeping service contracts observable.
        - API Style Detail 07: Authentication relies on Quarkus OIDC, so every endpoint expects Authorization headers carrying JWTs or anonymous cookie IDs for select GETs.
          Implication: This reinforces async-safety guardrails while keeping service contracts observable.
        - API Style Detail 08: Idempotent POST endpoints (e.g., layout saves) use client-supplied UUIDs so mobile or offline clients can safely retry.
          Implication: This reinforces operability guardrails while keeping service contracts observable.
        - API Style Detail 09: Pagination is standardized via limit/offset parameters with deterministic sorting, ensuring caching layers stay effective.
          Implication: This reinforces compliance guardrails while keeping service contracts observable.
        - API Style Detail 10: Every endpoint emits cache-control hints keyed by DTO version and feature flags so CDN and browser caches respect rollout boundaries.
          Implication: This reinforces observability guardrails while keeping service contracts observable.
        - API Style Detail 11: Webhooks (Stripe, inbound email) also land on REST resources so JobOrchestrator can enqueue follow-up tasks inside the same transaction.
          Implication: This reinforces cost-control guardrails while keeping service contracts observable.
        - API Style Detail 12: REST responses include server timestamps and trace IDs to aid log correlation across ExperienceShell and async workers.
          Implication: This reinforces personalization guardrails while keeping service contracts observable.
        - API Style Detail 13: Data export/deletion endpoints provide synchronous acknowledgement but offload heavy lifting to delayed jobs, returning job tokens via REST.
          Implication: This reinforces performance guardrails while keeping service contracts observable.
        - API Style Detail 14: RateLimitService injects limit headers (X-RateLimit-Remaining etc.) into REST responses as mandated by Policy F14.2.
          Implication: This reinforces feature-flag-governance guardrails while keeping service contracts observable.
        - API Style Detail 15: Multipart uploads (marketplace images, avatar updates) rely on REST endpoints that hand back signed StorageGateway URLs rather than streaming binary via the app pods.
          Implication: This reinforces async-safety guardrails while keeping service contracts observable.
        - API Style Detail 16: REST patch semantics are used for preference toggles and feature flag adjustments, minimizing payload size and allowing selective updates.
          Implication: This reinforces operability guardrails while keeping service contracts observable.
        - API Style Detail 17: Resource naming mirrors Panache entities (feed_items, marketplace_listings) to keep DTO/event nomenclature consistent across code and docs.
          Implication: This reinforces compliance guardrails while keeping service contracts observable.
        - API Style Detail 18: OpenAPI definitions live alongside the controllers and are published automatically so frontend TypeScript clients can regenerate fetch types.
          Implication: This reinforces observability guardrails while keeping service contracts observable.
        - API Style Detail 19: REST controllers never emit HTML; ExperienceShell controllers handle template rendering separately to avoid mixing presentation with data APIs.
          Implication: This reinforces cost-control guardrails while keeping service contracts observable.
        - API Style Detail 20: Health and readiness checks are also REST endpoints, returning queue depth summaries and AI budget states for Kubernetes probes.
          Implication: This reinforces personalization guardrails while keeping service contracts observable.
        - API Style Detail 21: GET /api/widgets/news supports the News Widget flow and returns AiTagsType-enriched feed items ordered by topic rank.
          Implication: DTO carries click tracking URLs and publish timestamps for dedupe.
          Compliance: Endpoint honors authentication/authorization boundaries while emitting traceable JSON DTOs.
        - API Style Detail 22: GET /api/widgets/weather supports the Weather Widget flow and delivers cached Open-Meteo or NWS payloads for saved locations.
          Implication: Response encodes provider metadata so the frontend can show attribution badges.
          Compliance: Endpoint honors authentication/authorization boundaries while emitting traceable JSON DTOs.
        - API Style Detail 23: GET /api/widgets/stocks supports the Stock Widget flow and serves Alpha Vantage quotes plus sparkline arrays.
          Implication: Endpoint honors market-hours flagging so UI can gray out stale data.
          Compliance: Endpoint honors authentication/authorization boundaries while emitting traceable JSON DTOs.
        - API Style Detail 24: GET /api/widgets/social supports the Social Widget flow and returns SocialWidgetStateType with cached posts and reconnect banners.
          Implication: Response indicates staleness tiers so UI colors match Policy P13.
          Compliance: Endpoint honors authentication/authorization boundaries while emitting traceable JSON DTOs.
        - API Style Detail 25: PUT /api/preferences supports the Preferences Save flow and stores UserPreferencesType JSONB payloads.
          Implication: Endpoint requires If-Match with schema_version to prevent accidental overwrites.
          Compliance: Endpoint honors authentication/authorization boundaries while emitting traceable JSON DTOs.
        - API Style Detail 26: POST /api/marketplace/search supports the Marketplace Search flow and accepts MarketplaceSearchType filters and streams paginated listings.
          Implication: Payload supports PostGIS radius inputs while limiting to 250 miles per P11.
          Compliance: Endpoint honors authentication/authorization boundaries while emitting traceable JSON DTOs.
        - API Style Detail 27: GET /api/marketplace/listings/{id} supports the Marketplace Listing Detail flow and returns listing detail along with signed image URLs.
          Implication: Response hides contact email behind relay token references.
          Compliance: Endpoint honors authentication/authorization boundaries while emitting traceable JSON DTOs.
        - API Style Detail 28: GET /api/good-sites/categories/{slug} supports the Directory Category View flow and exposes CategoryViewType with bubbling metadata.
          Implication: Endpoint powers both Qute rendering and admin audits.
          Compliance: Endpoint honors authentication/authorization boundaries while emitting traceable JSON DTOs.
        - API Style Detail 29: POST /api/good-sites/submissions supports the Directory Submission flow and intakes SubmitSiteType and returns moderation status.
          Implication: Response attaches karma deltas when approvals auto-publish.
          Compliance: Endpoint honors authentication/authorization boundaries while emitting traceable JSON DTOs.
        - API Style Detail 30: GET /api/profiles/{username} supports the Profile Template Fetch flow and serves template_config for public pages.
          Implication: DTO includes view counters and publish state so caching respects drafts.
          Compliance: Endpoint honors authentication/authorization boundaries while emitting traceable JSON DTOs.
        - API Style Detail 31: GET /admin/api/analytics/overview supports the Admin Analytics Overview flow and provides AnalyticsOverviewType with click totals.
          Implication: Endpoint accepts date filters and enforces view_analytics permission.
          Compliance: Endpoint honors authentication/authorization boundaries while emitting traceable JSON DTOs.
        - API Style Detail 32: PATCH /admin/api/feature-flags/{key} supports the Feature Flag Management flow and updates rollout_percentage, whitelist, and analytics toggle.
          Implication: Response echoes FeatureFlagType and records audit row synchronously.
          Compliance: Endpoint honors authentication/authorization boundaries while emitting traceable JSON DTOs.

    *   **Communication Patterns:** Behavioral interactions span synchronous REST calls for ExperienceShell-triggered widgets and asynchronous delayed jobs for IO-heavy workloads.
        - Communication Flow 01 (Synchronous): Homepage Rate Limiting
          Components: ExperienceShell ↔ RateLimitService.
          Behavior: ExperienceShell invokes RateLimitService before any widget call to enforce Policy F14.2 buckets using session or user keys.
          Policy Alignment: Violations log into rate_limit_violations so ops dashboards can alert when abuse surfaces.
          Observability: Rate limit decisions propagate trace metadata so downstream logs already know the applied tier.
        - Communication Flow 02 (Synchronous): Anonymous Identity Assignment
          Components: ExperienceShell ↔ AuthIdentityService.
          Behavior: When no JWT is present, ExperienceShell calls AuthIdentityService to mint vu_anon_id and set HttpOnly cookies per Policy P9.
          Policy Alignment: Audit rows capture IP and user agent to maintain merge eligibility windows per Policy P1.
          Observability: An event is logged for analytics_consent defaults so consent banners know how to render.
        - Communication Flow 03 (Synchronous): OAuth Merge Consent
          Components: ExperienceShell ↔ AuthIdentityService ↔ AccountMergeAudit.
          Behavior: During OAuth callbacks, AuthIdentityService tells ExperienceShell whether merge consent modal must be displayed, returning audit IDs once users opt in or discard.
          Policy Alignment: AccountMergeAudit rows store merged_data_summary JSONB to describe layout, topics, and widget configs included.
          Observability: Trace IDs tie the modal POST to the resulting audit insert for compliance review.
        - Communication Flow 04 (Synchronous): Feature Flag Cohort Evaluation
          Components: ExperienceShell ↔ FeatureFlagService.
          Behavior: ExperienceShell requests all relevant flag keys via one batched REST call so the homepage can treat cohorts consistently while rendering multiple widgets.
          Policy Alignment: Flag evaluations record session_hash or user_id depending on consent per Policy P14.
          Observability: Evaluation logs include rollout_percentage snapshot, empowering reproducible audits.
        - Communication Flow 05 (Synchronous): Preference Retrieval
          Components: ExperienceShell ↔ UserPreferenceService.
          Behavior: UserPreferenceService emits layout, topics, watchlists, and widget configs as a JSONB DTO that ExperienceShell caches per request.
          Policy Alignment: Schema_version field ensures migrations can refuse outdated payloads to avoid corruption.
          Observability: Preference fetch spans annotate whether data came from anonymous or authenticated rows.
        - Communication Flow 06 (Synchronous): News Widget Assembly
          Components: ExperienceShell ↔ FeedAggregationService.
          Behavior: ExperienceShell issues GET /api/widgets/news and receives AiTagsType-enriched entries filtered by interests and feature flags.
          Policy Alignment: FeedAggregationService respects AiTaggingBudgetService decisions before invoking LangChain4j per Policy P2/P10.
          Observability: Feed responses include tracer headers for each delayed job that recently refreshed the sources.
        - Communication Flow 07 (Synchronous): Weather Widget Assembly
          Components: ExperienceShell ↔ WeatherService.
          Behavior: WeatherService returns cached Open-Meteo or NWS data keyed by location preference, falling back gracefully when sensors timeout.
          Policy Alignment: Hourly refresh compliance is maintained by WeatherRefreshJobHandler scheduled per Policy F4.
          Observability: Payload metadata indicates cache age so ExperienceShell can render staleness warnings if thresholds breach.
        - Communication Flow 08 (Synchronous): Stock Widget Assembly
          Components: ExperienceShell ↔ StockService.
          Behavior: StockService ingests watchlists, checks Alpha Vantage status, and streams quotes sorted per user priorities.
          Policy Alignment: Requests honor per-user limits (20 symbols) and degrade after-hours intervals per Feature F5.
          Observability: Service returns a rate_limit_state flag so UI knows when Alpha Vantage quotas near exhaustion.
        - Communication Flow 09 (Synchronous): Social Feed Assembly
          Components: ExperienceShell ↔ SocialIntegrationService.
          Behavior: ExperienceShell fetches SocialWidgetStateType, which reports connection status, staleness, cached posts, and reconnect URLs.
          Policy Alignment: Service enforces Policy P5/P13 by switching to archive mode if tokens stale beyond 7 days.
          Observability: Response includes last_successful_refresh for audit dashboards.
        - Communication Flow 10 (Synchronous): Marketplace Search
          Components: ExperienceShell ↔ MarketplaceService.
          Behavior: MarketplaceService handles search POSTs with MarketplaceSearchType filters and returns listings annotated with radius and price filters.
          Policy Alignment: Search respects PostGIS radius caps per Policy P11 and Flag gating for promoted listings.
          Observability: Search spans annotate which filters were applied for future analytics.
        - Communication Flow 11 (Synchronous): Marketplace Listing Mutations
          Components: React Mini Mounts ↔ MarketplaceService ↔ StorageGateway.
          Behavior: React mounts upload images via signed URLs returned by StorageGateway while MarketplaceService tracks metadata and status transitions.
          Policy Alignment: Policy P3 ensures refunds are logged when moderation rejects paid categories within 24 hours.
          Observability: Upload responses carry image processing job IDs for later troubleshooting.
        - Communication Flow 12 (Synchronous): Directory Submission Handling
          Components: ExperienceShell ↔ DirectoryService ↔ StorageGateway.
          Behavior: Directory submissions fetch metadata and queue screenshot captures via StorageGateway-managed object keys.
          Policy Alignment: Karma thresholds decide whether submissions auto-publish or enter moderation queues per Section F13.6.
          Observability: Submission responses carry screenshot job references for the ops console.
        - Communication Flow 13 (Synchronous): Profile Template Editing
          Components: React Mini Mounts ↔ ProfileService.
          Behavior: ProfileService returns template_config and accepts curated slot updates, ensuring slot schemas remain consistent.
          Policy Alignment: Reserved usernames and sanitization are enforced server-side before persisting content.
          Observability: Profile edits emit audit logs that mention template type and publish state.
        - Communication Flow 14 (Synchronous with Async Persist): Click Tracking
          Components: ExperienceShell ↔ ClickTrackingService.
          Behavior: Every outbound link rewrites to /track/click and ClickTrackingService records the event asynchronously before redirecting.
          Policy Alignment: Link_clicks table partitions per day and retains 90 days, with rollups feeding admin analytics per Policy F14.9.
          Observability: Tracking service attaches category context so dashboards can aggregate by module.
        - Communication Flow 15 (Asynchronous): AI Tagging Pipeline
          Components: FeedAggregationService ↔ JobOrchestrator ↔ AiTaggingService.
          Behavior: New feed items trigger jobs that batch 10-20 URLs through LangChain4j, capturing tags and usage stats.
          Policy Alignment: AiTaggingBudgetService decides whether to reduce batch size, queue for next cycle, or hard stop per Policy P10.
          Observability: Each job logs estimated cost, tokens, and dedupe hashes for audits.
        - Communication Flow 16 (Asynchronous): Screenshot Capture
          Components: DirectoryService ↔ JobOrchestrator ↔ StorageGateway.
          Behavior: SCREENSHOT queue jobs spin Puppeteer browsers, upload WebP variants, and mark directory_screenshot_versions.
          Policy Alignment: Policy P4 enforces unlimited retention with version history so no screenshot is deleted.
          Observability: Job metrics expose pool utilization so ops can scale pods when backlog grows.
        - Communication Flow 17 (Asynchronous): Marketplace Image Processing
          Components: MarketplaceService ↔ JobOrchestrator ↔ StorageGateway.
          Behavior: Listings queue BULK image-processing tasks that resize, thumbnail, and store metadata.
          Policy Alignment: Listings exceeding 12 images are rejected before jobs schedule, matching Product requirement F12.4.
          Observability: Processing jobs tag listing IDs for traceability in case of CDN inconsistencies.
        - Communication Flow 18 (Asynchronous): Profile View Aggregation
          Components: ProfileService ↔ JobOrchestrator.
          Behavior: Profile view beacons buffer events until ProfileViewCountAggregatorJobHandler tallies them hourly.
          Policy Alignment: Published profiles only aggregate views; drafts ignore analytics to protect privacy.
          Observability: Aggregations emit metrics showing delta per template.
        - Communication Flow 19 (Asynchronous): Rate Limit Violations
          Components: RateLimitService ↔ JobOrchestrator.
          Behavior: When limit buckets overflow, RateLimitService writes violation rows and optionally queues notifications for ops review.
          Policy Alignment: Blocklist recommendations surface via admin UI but still require manual confirmation for enforcement.
          Observability: Violation jobs capture IP and action_type for future anomaly detection.
        - Communication Flow 20 (Asynchronous): Inbound Email Processing
          Components: InboundEmailProcessor ↔ JobOrchestrator ↔ MarketplaceService.
          Behavior: IMAP poller enqueues HIGH queue jobs to parse masked replies and connect them to marketplace_messages.
          Policy Alignment: Attachments respect configurable size caps and sanitized metadata before relay to listing owners.
          Observability: Failure retries carry exponential backoff counters for support diagnosis.
        - Communication Flow 21 (Asynchronous): Stripe Webhook Handling
          Components: StripeWebhookResource ↔ MarketplaceService.
          Behavior: Webhook payloads acknowledge immediately and schedule background verification of promotions/refunds.
          Policy Alignment: Refunds obey Policy P3 and update payment_refunds rows transactionally.
          Observability: Webhook spans log Stripe event IDs for idempotent correlations.
        - Communication Flow 22 (Asynchronous): GDPR Data Export
          Components: ComplianceController ↔ JobOrchestrator ↔ StorageGateway.
          Behavior: Export API accepts a POST, enqueues a job that collects user data, stores zipped JSON on R2, and emails signed links.
          Policy Alignment: Exports expire after configurable TTLs, and logs indicate consent version included.
          Observability: Job metadata references request IDs so support can trace completion.
        - Communication Flow 23 (Asynchronous): AI Budget Monitoring
          Components: AiTaggingService ↔ AiTaggingBudgetService ↔ Admin Alerts.
          Behavior: Usage counters update ai_usage_tracking rows and trigger notifications when hitting 75/90/100 percent thresholds.
          Policy Alignment: Budget resets on first of month, so scheduler primes counters at midnight UTC per Policy P10.
          Observability: Alerts propagate through ops dashboards and email to admin cohort.
        - Communication Flow 24 (Asynchronous): Feature Flag Analytics Purge
          Components: FeatureFlagService ↔ JobOrchestrator ↔ Database.
          Behavior: Account deletion flow triggers synchronous purge of evaluation rows plus scheduled jobs to remove expired partitions.
          Policy Alignment: Retention is capped at 90 days, aligning with Policy P14.
          Observability: Purge jobs log counts to confirm compliance.
        - Communication Flow 25 (Asynchronous): Click Stats Rollups
          Components: ClickTrackingService ↔ JobOrchestrator.
          Behavior: Hourly rollups aggregate raw clicks by category/item into click_stats_daily tables.
          Policy Alignment: Retained stats power admin analytics without exposing individual user IDs beyond policy windows.
          Observability: Job metrics show processed partitions to detect lags.

    *   **Key Interaction Flow (Sequence Diagram):** Requesting a personalized homepage exercises rate limiting, feature flags, widget aggregation, AI tagging, and analytics instrumentation in a single path.
        *   **Description:** The diagram narrates how ExperienceShell evaluates cohorts, fetches widget data from FeedAggregationService, WeatherService, StockService, SocialIntegrationService, MarketplaceService, DirectoryService, and ProfileService, while orchestrating AI tagging, storage access, and delayed jobs before rendering Qute templates.
        *   **Diagram (PlantUML):
            ~~~plantuml
            @startuml
            actor User
            participant "ExperienceShell" as ExperienceShell
            participant "RateLimitService" as RateLimitService
            participant "AuthIdentityService" as AuthIdentityService
            participant "FeatureFlagService" as FeatureFlagService
            participant "UserPreferenceService" as UserPreferenceService
            participant "FeedAggregationService" as FeedAggregationService
            participant "AiTaggingService" as AiTaggingService
            participant "AiTaggingBudgetService" as AiTaggingBudgetService
            participant "WeatherService" as WeatherService
            participant "StockService" as StockService
            participant "SocialIntegrationService" as SocialIntegrationService
            participant "MarketplaceService" as MarketplaceService
            participant "DirectoryService" as DirectoryService
            participant "ProfileService" as ProfileService
            participant "ClickTrackingService" as ClickTrackingService
            participant "StorageGateway" as StorageGateway
            participant "JobOrchestrator" as JobOrchestrator
            participant "DelayedJobQueue" as DelayedJobQueue
            User -> ExperienceShell : Step 01 - Initiate GET / with session cookie vu_anon_id or JWT
            activate ExperienceShell
            note right of ExperienceShell : Server-rendered entry point satisfies SEO and ensures React islands hydrate deterministically.
            ExperienceShell --> User : Stream initial HTML scaffold with Qute layout
            deactivate ExperienceShell
            ExperienceShell -> RateLimitService : Step 02 - Check page_view bucket for session/user
            activate RateLimitService
            note right of RateLimitService : Policy F14.2 enforces per-tier ceilings before downstream work begins.
            RateLimitService --> ExperienceShell : Return allowance with remaining tokens and retry hint
            deactivate RateLimitService
            ExperienceShell -> AuthIdentityService : Step 03 - Resolve identity context (anonymous or authenticated)
            activate AuthIdentityService
            note right of AuthIdentityService : AuthIdentityService merges OAuth metadata with anonymous traces per Policy P1.
            AuthIdentityService --> ExperienceShell : Provide user_id, roles, consent flags
            deactivate AuthIdentityService
            ExperienceShell -> FeatureFlagService : Step 04 - Evaluate homepage-related flag keys for user/session
            activate FeatureFlagService
            note right of FeatureFlagService : Hash-based cohorts guarantee sticky experiences even across multiple widget calls.
            FeatureFlagService --> ExperienceShell : Return deterministic cohort assignments and analytics toggles
            deactivate FeatureFlagService
            ExperienceShell -> UserPreferenceService : Step 05 - Fetch layout and preference JSONB blob
            activate UserPreferenceService
            note right of UserPreferenceService : Preferences tell ExperienceShell which widgets to call and how to configure gridstack.
            UserPreferenceService --> ExperienceShell : Respond with UserPreferencesType plus schema_version
            deactivate UserPreferenceService
            ExperienceShell -> ClickTrackingService : Step 06 - Request page_view token for aggregated analytics
            activate ClickTrackingService
            note right of ClickTrackingService : Tracking context later ties widget clicks back to the session.
            ClickTrackingService --> ExperienceShell : Issue tracking context ID to embed in DOM
            deactivate ClickTrackingService
            ExperienceShell -> StorageGateway : Step 07 - Request signed asset URLs for static widget resources
            activate StorageGateway
            note right of StorageGateway : Signed URLs prevent direct bucket exposure and honor Policy P4.
            StorageGateway --> ExperienceShell : Provide CDN URLs scoped to short TTL
            deactivate StorageGateway
            ExperienceShell -> JobOrchestrator : Step 08 - Check delayed job health summaries for observability banner
            activate JobOrchestrator
            note right of JobOrchestrator : Surface-level health indicators inform admin users when backlogs occur.
            JobOrchestrator --> ExperienceShell : Return queue depth snapshot per queue
            deactivate JobOrchestrator
            ExperienceShell -> MarketplaceService : Step 09 - Load quick summary of user's active listings for dashboard widget
            activate MarketplaceService
            note right of MarketplaceService : Dashboard highlights rely on same DTOs as marketplace pages to avoid duplication.
            MarketplaceService --> ExperienceShell : Provide listing counts, statuses, and expiring soon markers
            deactivate MarketplaceService
            ExperienceShell -> DirectoryService : Step 10 - Load curated Good Sites list for homepage tiles
            activate DirectoryService
            note right of DirectoryService : DirectoryService enforces karma-based filters even for read-only surfaces.
            DirectoryService --> ExperienceShell : Return top-ranked DirectorySite entries with bubbled metadata
            deactivate DirectoryService
            ExperienceShell -> ProfileService : Step 11 - Retrieve public profile snippet to display share card
            activate ProfileService
            note right of ProfileService : Profiles only render if is_published is true, preventing accidental leaks.
            ProfileService --> ExperienceShell : Provide template type, avatar, and CTA links
            deactivate ProfileService
            ExperienceShell -> MarketplaceService : Step 12 - Check saved search notifications for badge counts
            activate MarketplaceService
            note right of MarketplaceService : This call ensures header icons reflect unseen matches.
            MarketplaceService --> ExperienceShell : Return count of saved searches with new matches
            deactivate MarketplaceService
            ExperienceShell -> ClickTrackingService : Step 13 - Register initial hero link exposures
            activate ClickTrackingService
            note right of ClickTrackingService : Pre-register exposures to align with impression analytics.
            ClickTrackingService --> ExperienceShell : Return instrumentation tokens per hero widget
            deactivate ClickTrackingService
            ExperienceShell -> FeatureFlagService : Step 14 - Reconfirm analytics_enabled for high-risk widgets
            activate FeatureFlagService
            note right of FeatureFlagService : Double-check ensures that late-arriving preference toggles respect consent state.
            FeatureFlagService --> ExperienceShell : Affirm loggable widgets and anonymization mode
            deactivate FeatureFlagService
            ExperienceShell -> RateLimitService : Step 15 - Reserve quota for subsequent async widget fetches
            activate RateLimitService
            note right of RateLimitService : Reservation avoids race conditions when JS mounts fetch APIs moments later.
            RateLimitService --> ExperienceShell : Acknowledge reservation with expiry timestamp
            deactivate RateLimitService
            ExperienceShell -> JobOrchestrator : Step 16 - Emit tracing note for page assembly completion
            activate JobOrchestrator
            note right of JobOrchestrator : This ensures AI tagging or screenshot jobs can link back to the originating request.
            JobOrchestrator --> ExperienceShell : Confirm trace context persisted for async follow-ups
            deactivate JobOrchestrator
            ExperienceShell -> FeedAggregationService : Step 17 - GET /api/widgets/news with topics, followed_sources, and cohort flags
            activate FeedAggregationService
            note right of FeedAggregationService : FeedAggregationService sorts by interest match, freshness, and engagement metrics.
            FeedAggregationService --> ExperienceShell : Return personalized feed_items with AiTagsType payloads
            deactivate FeedAggregationService
            FeedAggregationService -> AiTaggingService : Step 18 - Submit batch of untagged feed_items
            activate AiTaggingService
            note right of AiTaggingService : LangChain4j pipelines enforce dedupe hashes before spending tokens.
            AiTaggingService --> FeedAggregationService : Acknowledge tagging pipeline job id
            deactivate AiTaggingService
            AiTaggingService -> AiTaggingBudgetService : Step 19 - Check cost horizon before invoking Claude
            activate AiTaggingBudgetService
            note right of AiTaggingBudgetService : Policy P10 defines thresholds for 75/90/100 percent usage.
            AiTaggingBudgetService --> AiTaggingService : Return budget action (NORMAL/REDUCE/QUEUE/HARD_STOP)
            deactivate AiTaggingBudgetService
            FeedAggregationService -> ClickTrackingService : Step 20 - Generate redirect tokens for each feed URL
            activate ClickTrackingService
            note right of ClickTrackingService : Tokens embed category context for analytics rollups.
            ClickTrackingService --> FeedAggregationService : Provide track IDs keyed by feed_item id
            deactivate ClickTrackingService
            FeedAggregationService -> JobOrchestrator : Step 21 - Schedule next refresh per source cadence
            activate JobOrchestrator
            note right of JobOrchestrator : Breaking news feeds schedule at 5-15 minute intervals per Feature F3.
            JobOrchestrator --> FeedAggregationService : Return job id for the relevant queue
            deactivate JobOrchestrator
            FeedAggregationService -> StorageGateway : Step 22 - Fetch signed URLs for cached thumbnails
            activate StorageGateway
            note right of StorageGateway : Images remain in WebP format stored within R2 buckets.
            StorageGateway --> FeedAggregationService : Deliver CDN-safe image links
            deactivate StorageGateway
            ExperienceShell -> WeatherService : Step 23 - GET /api/widgets/weather with location identifiers
            activate WeatherService
            note right of WeatherService : WeatherService selects NWS or Open-Meteo provider based on geography.
            WeatherService --> ExperienceShell : Return hourly and daily forecasts with alert summaries
            deactivate WeatherService
            WeatherService -> JobOrchestrator : Step 24 - Enqueue severe weather alert refresh
            activate JobOrchestrator
            note right of JobOrchestrator : Alerts refresh every 15 minutes for US locations.
            JobOrchestrator --> WeatherService : Provide LOW queue job handle
            deactivate JobOrchestrator
            WeatherService -> StorageGateway : Step 25 - Cache radar tiles or icon sprites
            activate StorageGateway
            note right of StorageGateway : Ensures consistent iconography even offline.
            StorageGateway --> WeatherService : Return signed icon URLs
            deactivate StorageGateway
            WeatherService -> FeatureFlagService : Step 26 - Confirm experimental radar overlay flag
            activate FeatureFlagService
            note right of FeatureFlagService : Allows limited rollout of advanced weather overlays.
            FeatureFlagService --> WeatherService : Return boolean for UI rendering
            deactivate FeatureFlagService
            WeatherService -> ClickTrackingService : Step 27 - Register alert CTA links
            activate ClickTrackingService
            note right of ClickTrackingService : Click tracking distinguishes weather CTA engagement from news.
            ClickTrackingService --> WeatherService : Issue tracking tokens
            deactivate ClickTrackingService
            WeatherService -> JobOrchestrator : Step 28 - Report cache miss metrics
            activate JobOrchestrator
            note right of JobOrchestrator : Helps ops tune refresh intervals for geographic hotspots.
            JobOrchestrator --> WeatherService : Acknowledge metric event
            deactivate JobOrchestrator
            ExperienceShell -> StockService : Step 29 - GET /api/widgets/stocks with watchlist payload
            activate StockService
            note right of StockService : StockService manages rate limit windows for Alpha Vantage calls.
            StockService --> ExperienceShell : Return quotes, percent change, and sparkline data
            deactivate StockService
            StockService -> JobOrchestrator : Step 30 - Schedule HIGH queue refresh job for market hours
            activate JobOrchestrator
            note right of JobOrchestrator : Market hours refresh every five minutes.
            JobOrchestrator --> StockService : Return job reference
            deactivate JobOrchestrator
            StockService -> FeatureFlagService : Step 31 - Check stocks_widget rollout
            activate FeatureFlagService
            note right of FeatureFlagService : Stocks widget only appears once flag is enabled for the cohort.
            FeatureFlagService --> StockService : Return flag state for user
            deactivate FeatureFlagService
            StockService -> StorageGateway : Step 32 - Load company logo sprites
            activate StorageGateway
            note right of StorageGateway : Logos cached to avoid hammering external APIs.
            StorageGateway --> StockService : Provide CDN sprite URLs
            deactivate StorageGateway
            StockService -> ClickTrackingService : Step 33 - Instrument symbol detail drilldown links
            activate ClickTrackingService
            note right of ClickTrackingService : Allows analytics to measure financial engagement separately.
            ClickTrackingService --> StockService : Issue redirect tokens
            deactivate ClickTrackingService
            StockService -> UserPreferenceService : Step 34 - Confirm watchlist size under limit
            activate UserPreferenceService
            note right of UserPreferenceService : Prevents storing more than twenty watchlist entries.
            UserPreferenceService --> StockService : Acknowledge compliance
            deactivate UserPreferenceService
            ExperienceShell -> SocialIntegrationService : Step 35 - GET /api/widgets/social with user_id
            activate SocialIntegrationService
            note right of SocialIntegrationService : Service balances cached posts and token refresh attempts.
            SocialIntegrationService --> ExperienceShell : Return SocialWidgetStateType including banner message
            deactivate SocialIntegrationService
            SocialIntegrationService -> JobOrchestrator : Step 36 - Schedule token refresh job seven days before expiry
            activate JobOrchestrator
            note right of JobOrchestrator : Implements proactive refresh per Policy P5.
            JobOrchestrator --> SocialIntegrationService : Return job id for LOW queue
            deactivate JobOrchestrator
            SocialIntegrationService -> StorageGateway : Step 37 - Retrieve cached media URLs
            activate StorageGateway
            note right of StorageGateway : Media retained even after disconnect per Policy P13.
            StorageGateway --> SocialIntegrationService : Return signed CDN links
            deactivate StorageGateway
            SocialIntegrationService -> AuthIdentityService : Step 38 - Validate OAuth scopes and permissions
            activate AuthIdentityService
            note right of AuthIdentityService : Ensures Meta Graph API scopes remain valid.
            AuthIdentityService --> SocialIntegrationService : Provide confirmation or errors
            deactivate AuthIdentityService
            SocialIntegrationService -> FeatureFlagService : Step 39 - Check social_integration flag
            activate FeatureFlagService
            note right of FeatureFlagService : Allows quick disable when Meta policy shifts.
            FeatureFlagService --> SocialIntegrationService : Return boolean for widget enablement
            deactivate FeatureFlagService
            SocialIntegrationService -> ClickTrackingService : Step 40 - Instrument reconnect CTA
            activate ClickTrackingService
            note right of ClickTrackingService : Helps gauge how many users respond to stale banner prompts.
            ClickTrackingService --> SocialIntegrationService : Issue tracking token
            deactivate ClickTrackingService
            ExperienceShell -> MarketplaceService : Step 41 - POST /api/marketplace/search for hero module
            activate MarketplaceService
            note right of MarketplaceService : MarketplaceService uses Hibernate Search to combine ES and PostGIS filters.
            MarketplaceService --> ExperienceShell : Return curated listings and counts
            deactivate MarketplaceService
            MarketplaceService -> JobOrchestrator : Step 42 - Schedule expiration reminder email job
            activate JobOrchestrator
            note right of JobOrchestrator : Reminder runs three days before listing expires.
            JobOrchestrator --> MarketplaceService : Provide LOW queue job detail
            deactivate JobOrchestrator
            MarketplaceService -> StorageGateway : Step 43 - Obtain signed thumbnail URLs
            activate StorageGateway
            note right of StorageGateway : Images resized and stored following F12.5.
            StorageGateway --> MarketplaceService : Return CDN-safe listing images
            deactivate StorageGateway
            MarketplaceService -> ClickTrackingService : Step 44 - Log hero listing impressions
            activate ClickTrackingService
            note right of ClickTrackingService : Impression logs feed monetization analytics.
            ClickTrackingService --> MarketplaceService : Return impression token
            deactivate ClickTrackingService
            MarketplaceService -> FeatureFlagService : Step 45 - Check promoted_listings flag
            activate FeatureFlagService
            note right of FeatureFlagService : Optional paid features only appear when enabled.
            FeatureFlagService --> MarketplaceService : Return roll-out state
            deactivate FeatureFlagService
            MarketplaceService -> RateLimitService : Step 46 - Confirm submission rate bucket
            activate RateLimitService
            note right of RateLimitService : Prevents spamming when posting new listings.
            RateLimitService --> MarketplaceService : Return allowance
            deactivate RateLimitService
            ExperienceShell -> DirectoryService : Step 47 - GET /api/good-sites/categories/highlights
            activate DirectoryService
            note right of DirectoryService : DirectoryService composes direct links and bubbled ones based on score thresholds.
            DirectoryService --> ExperienceShell : Return DirectorySiteLinkType entries with bubble metadata
            deactivate DirectoryService
            DirectoryService -> JobOrchestrator : Step 48 - Queue ScreenshotCaptureJobHandler for stale sites
            activate JobOrchestrator
            note right of JobOrchestrator : Screenshots refresh monthly per Policy P4.
            JobOrchestrator --> DirectoryService : Return BULK queue job id
            deactivate JobOrchestrator
            DirectoryService -> StorageGateway : Step 49 - Retrieve screenshot versions
            activate StorageGateway
            note right of StorageGateway : Version history maintained indefinitely.
            StorageGateway --> DirectoryService : Provide signed URIs
            deactivate StorageGateway
            DirectoryService -> ClickTrackingService : Step 50 - Issue vote action tracking tokens
            activate ClickTrackingService
            note right of ClickTrackingService : Supports Reddit-style voting analytics.
            ClickTrackingService --> DirectoryService : Return vote event IDs
            deactivate ClickTrackingService
            DirectoryService -> FeatureFlagService : Step 51 - Check good_sites flag for anonymous cohorts
            activate FeatureFlagService
            note right of FeatureFlagService : Allows staged launch of Good Sites to subsets.
            FeatureFlagService --> DirectoryService : Return boolean
            deactivate FeatureFlagService
            DirectoryService -> UserPreferenceService : Step 52 - Fetch directory_karma for submitter
            activate UserPreferenceService
            note right of UserPreferenceService : Determines auto-publish vs moderation.
            UserPreferenceService --> DirectoryService : Return scores
            deactivate UserPreferenceService
            ExperienceShell -> ProfileService : Step 53 - GET /api/profiles/{username} for embed on homepage
            activate ProfileService
            note right of ProfileService : ProfileService enforces reserved names and sanitized content.
            ProfileService --> ExperienceShell : Return PublicProfileType with template_config
            deactivate ProfileService
            ProfileService -> ClickTrackingService : Step 54 - Setup trackable links for curated articles
            activate ClickTrackingService
            note right of ClickTrackingService : Ensures curated articles feed admin analytics.
            ClickTrackingService --> ProfileService : Return token map
            deactivate ClickTrackingService
            ProfileService -> JobOrchestrator : Step 55 - Schedule ProfileViewCountAggregatorJobHandler
            activate JobOrchestrator
            note right of JobOrchestrator : Aggregates view counters hourly.
            JobOrchestrator --> ProfileService : Return LOW queue job id
            deactivate JobOrchestrator
            ProfileService -> StorageGateway : Step 56 - Request signed avatar and hero image URLs
            activate StorageGateway
            note right of StorageGateway : Images stored in profiles/ prefix for retention clarity.
            StorageGateway --> ProfileService : Provide CDN references
            deactivate StorageGateway
            ProfileService -> FeatureFlagService : Step 57 - Check template-specific flags
            activate FeatureFlagService
            note right of FeatureFlagService : Allows staged release of new templates like your_report.
            FeatureFlagService --> ProfileService : Return allowed template set
            deactivate FeatureFlagService
            ProfileService -> UserPreferenceService : Step 58 - Confirm analytics_consent for profile linking
            activate UserPreferenceService
            note right of UserPreferenceService : Prevents logging views when users opt out.
            UserPreferenceService --> ProfileService : Return consent state
            deactivate UserPreferenceService
            ExperienceShell -> ClickTrackingService : Step 59 - POST /track/click instrumentation for aggregated links
            activate ClickTrackingService
            note right of ClickTrackingService : ClickTrackingService handles synchronous redirect plus async logging.
            ClickTrackingService --> ExperienceShell : Acknowledge event and issue redirect
            deactivate ClickTrackingService
            ClickTrackingService -> JobOrchestrator : Step 60 - Queue ClickStatsRollupJobHandler trigger
            activate JobOrchestrator
            note right of JobOrchestrator : Hourly rollups rely on this handshake.
            JobOrchestrator --> ClickTrackingService : Return DEFAULT queue job id
            deactivate JobOrchestrator
            ClickTrackingService -> DelayedJobQueue : Step 61 - Enqueue asynchronous log write
            activate DelayedJobQueue
            note right of DelayedJobQueue : Raw clicks partitioned per day for retention.
            DelayedJobQueue --> ClickTrackingService : Return partition identifier
            deactivate DelayedJobQueue
            ClickTrackingService -> FeatureFlagService : Step 62 - Confirm analytics consent for user_id
            activate FeatureFlagService
            note right of FeatureFlagService : Switches to session_hash-only when consent withdrawn.
            FeatureFlagService --> ClickTrackingService : Return logging mode
            deactivate FeatureFlagService
            ClickTrackingService -> RateLimitService : Step 63 - Throttle excessive beacons
            activate RateLimitService
            note right of RateLimitService : Prevents malicious tracking floods.
            RateLimitService --> ClickTrackingService : Return allowance or 429 hint
            deactivate RateLimitService
            ClickTrackingService -> StorageGateway : Step 64 - Persist export bundles for GDPR requests
            activate StorageGateway
            note right of StorageGateway : Exports reference click history for user downloads.
            StorageGateway --> ClickTrackingService : Return signed URL
            deactivate StorageGateway
            JobOrchestrator -> DelayedJobQueue : Step 65 - Enqueue SCREENSHOT job payload
            activate DelayedJobQueue
            note right of DelayedJobQueue : SCREENSHOT queue enforces per-pod concurrency per Policy P12.
            DelayedJobQueue --> JobOrchestrator : Return job identifier
            deactivate DelayedJobQueue
            DelayedJobQueue -> JobOrchestrator : Step 66 - Dispatch ready SCREENSHOT job
            activate JobOrchestrator
            note right of JobOrchestrator : Locks prevent duplicate Puppeteer sessions.
            JobOrchestrator --> DelayedJobQueue : Acknowledge worker lock
            deactivate JobOrchestrator
            JobOrchestrator -> AiTaggingService : Step 67 - Invoke handler for queued AI tagging payload
            activate AiTaggingService
            note right of AiTaggingService : AiTaggingService updates feed_items.ai_tags fields.
            AiTaggingService --> JobOrchestrator : Confirm job execution results
            deactivate AiTaggingService
            AiTaggingService -> AiTaggingBudgetService : Step 68 - Report completed batch usage
            activate AiTaggingBudgetService
            note right of AiTaggingBudgetService : Monthly budgets tracked in ai_usage_tracking table.
            AiTaggingBudgetService --> AiTaggingService : Return updated usage summary
            deactivate AiTaggingBudgetService
            MarketplaceService -> JobOrchestrator : Step 69 - Queue message relay job for contact email
            activate JobOrchestrator
            note right of JobOrchestrator : Ensures near-real-time email forwarding without blocking user action.
            JobOrchestrator --> MarketplaceService : Return HIGH queue job reference
            deactivate JobOrchestrator
            JobOrchestrator -> MarketplaceService : Step 70 - Deliver relay job payload
            activate MarketplaceService
            note right of MarketplaceService : MarketplaceService logs reply count and spam heuristics.
            MarketplaceService --> JobOrchestrator : Confirm message persisted
            deactivate MarketplaceService
            DirectoryService -> JobOrchestrator : Step 71 - Queue LinkHealthCheck job
            activate JobOrchestrator
            note right of JobOrchestrator : Link health jobs mark sites dead after repeated 404s.
            JobOrchestrator --> DirectoryService : Return LOW queue handle
            deactivate JobOrchestrator
            JobOrchestrator -> DirectoryService : Step 72 - Deliver link health results
            activate DirectoryService
            note right of DirectoryService : DirectoryService decrements karma for repeated dead submissions.
            DirectoryService --> JobOrchestrator : Confirm status updates
            deactivate DirectoryService
            ProfileService -> ClickTrackingService : Step 73 - Log curated article impression
            activate ClickTrackingService
            note right of ClickTrackingService : Impressions feed curated ranking insights.
            ClickTrackingService --> ProfileService : Issue impression id
            deactivate ClickTrackingService
            JobOrchestrator -> FeatureFlagService : Step 74 - Notify when flag audit change occurs
            activate FeatureFlagService
            note right of FeatureFlagService : Audit hooks propagate to analytics for review.
            FeatureFlagService --> JobOrchestrator : Confirm audit replication
            deactivate FeatureFlagService
            FeatureFlagService -> JobOrchestrator : Step 75 - Request evaluation log purge batch
            activate JobOrchestrator
            note right of JobOrchestrator : Purge ensures GDPR compliance within 90 days.
            JobOrchestrator --> FeatureFlagService : Provide LOW queue job id
            deactivate JobOrchestrator
            StorageGateway -> JobOrchestrator : Step 76 - Report failed upload for retry
            activate JobOrchestrator
            note right of JobOrchestrator : Keeps CDN in sync even when underlying storage hiccups occur.
            JobOrchestrator --> StorageGateway : Acknowledge and schedule retry
            deactivate JobOrchestrator
            JobOrchestrator -> StorageGateway : Step 77 - Execute retry upload job
            activate StorageGateway
            note right of StorageGateway : Retries annotated with trace_id for debugging.
            StorageGateway --> JobOrchestrator : Confirm final asset URL
            deactivate StorageGateway
            RateLimitService -> JobOrchestrator : Step 78 - Queue violation notification job
            activate JobOrchestrator
            note right of JobOrchestrator : Admin alerts digest repeated violations per IP.
            JobOrchestrator --> RateLimitService : Return job reference
            deactivate JobOrchestrator
            JobOrchestrator -> RateLimitService : Step 79 - Deliver violation digest
            activate RateLimitService
            note right of RateLimitService : Digests feed the ops UI module.
            RateLimitService --> JobOrchestrator : Confirm digest stored
            deactivate RateLimitService
            ClickTrackingService -> JobOrchestrator : Step 80 - Kick off click_stats_daily rebuild
            activate JobOrchestrator
            note right of JobOrchestrator : Rebuild jobs recompute stats when historical fixes occur.
            JobOrchestrator --> ClickTrackingService : Return DEFAULT queue job id
            deactivate JobOrchestrator
            @enduml
            ~~~

    *   **Data Transfer Objects (DTOs):** DTOs anchor the REST contracts; the representative payloads below highlight structure, validation cues, and downstream consumers.
        - DTO Name: HomepageCompositionResponseType
          Endpoint: GET /api/widgets/layout
          Purpose: Encapsulates layout metadata plus widget mount descriptors for the current session.
          * `version` (int) – preference schema_version returned by UserPreferenceService.
          * `widgets` (array) – each entry includes `type`, `x`, `y`, `width`, `height`, and `config` map.
          * `featureFlags` (object) – keyed by flag key with boolean result for ExperienceShell hydration.
          * `consent` (object) – contains analytics_consent, marketing_consent, and timestamp.
          * `rateLimit` (object) – includes tier label and remaining quota for inline hints.
          * `trace` (object) – trace_id and span_id so React islands can log with continuity.
          * `userContext` (object) – describes user_id (nullable), isAnonymous flag, and roles array.
          Notes: DTO is cached per-request only; subsequent JS fetches call domain-specific endpoints.
        - DTO Name: NewsWidgetResponseType
          Endpoint: GET /api/widgets/news
          Purpose: Delivers personalized feed items annotated with AiTagsType and click tracking URLs.
          * `items` (array of FeedItemType) – includes title, description, image_url, ai_tags, published_at.
          * `pinnedSources` (array<UUID>) – user-selected source IDs prioritized in ranking.
          * `topicsMatched` (array<string>) – subset of user topics satisfied by the response.
          * `batchMetadata` (object) – contains refreshTimestamp, fetchDurationMs, and jobId that produced data.
          * `tracking` (object) – map of feed_item_id to /track/click URL.
          * `budgetState` (string) – NORMAL/REDUCE/QUEUE/HARD_STOP per AiTaggingBudgetService.
          * `fallbackReason` (string|null) – explanation when cached data served due to upstream outage.
          Notes: Used by both Qute-rendered news widget and React charting overlays.
        - DTO Name: WeatherWidgetResponseType
          Endpoint: GET /api/widgets/weather
          Purpose: Provides hourly/current/alert data for the active location selection.
          * `current` (WeatherObservationType) – temp, feels_like, humidity, wind_speed, provider.
          * `hourly` (array<HourlyForecastType>) – next 24 hours of conditions with icon keys.
          * `daily` (array<DailyForecastType>) – seven-day highs/lows plus precipitation chances.
          * `alerts` (array<WeatherAlertType>) – only present for NWS coverage, includes severity and expires_at.
          * `location` (LocationType) – lat, lon, city_id, provider, timezone.
          * `stalenessSeconds` (int) – seconds since cached data fetched.
          * `featureFlags` (object) – toggles for experimental layers like radar_overlay.
          Notes: Hourly staleness is tracked by WeatherService; UI colors warnings when greater than 3600 seconds.
        - DTO Name: StockWidgetResponseType
          Endpoint: GET /api/widgets/stocks
          Purpose: Bundles watchlist entries, market state, and sparkline arrays.
          * `symbols` (array<StockQuoteType>) – contains symbol, company_name, price, change, change_percent, sparkline.
          * `marketState` (string) – OPEN, CLOSED, AFTER_HOURS, or HOLIDAY.
          * `rateLimit` (object) – informs user about remaining Alpha Vantage calls.
          * `lastUpdated` (Instant) – timestamp of freshest quote in payload.
          * `featureFlags` (object) – includes `stocks_widget` evaluation result.
          * `notifications` (array<string>) – warnings about stale data or watchlist truncation.
          * `impressionToken` (string) – token to tie widget impressions to click analytics.
          Notes: Sparkline arrays are limited to five days of close prices for rendering mini charts.
        - DTO Name: SocialWidgetStateType
          Endpoint: GET /api/widgets/social
          Purpose: Already defined in foundation; DTO highlights connection health and cached posts.
          * `isConnected` (boolean) – indicates whether live API calls succeed.
          * `isStale` (boolean) – indicates cached posts older than 24 hours.
          * `staleDays` (int) – number of days since last refresh.
          * `bannerMessage` (string) – copy for reconnect or archive notices.
          * `bannerLevel` (string) – info, warning, or alert controlling Ant Design banner color.
          * `lastSuccessfulRefresh` (Instant) – timestamp for compliance records.
          * `cachedPosts` (array<SocialPostType>) – includes media_url, caption, provider, posted_at.
          Notes: Frontend uses bannerLevel to map to Ant Design tokens; reconnect button hits OAuth start endpoint.
        - DTO Name: MarketplaceSearchResponseType
          Endpoint: POST /api/marketplace/search
          Purpose: Wraps paginated marketplace_listings plus context describing filters and location summaries.
          * `results` (array<MarketplaceListingSummaryType>) – includes listing id, title, price, city, distance, thumbnail URL.
          * `pagination` (object) – limit, offset, total, hasMore boolean.
          * `appliedFilters` (MarketplaceSearchType) – echoes filters after server normalization.
          * `featureFlags` (object) – indicates promoted_listings and marketplace flag state.
          * `currency` (string) – currently USD but future-proofed for CAD.
          * `radiusSummary` (string) – example text such as Boston Area 25 mi for header display.
          * `observability` (object) – trace_id plus elasticsearchQueryId for debugging.
          Notes: Search results integrate PostGIS sorted by distance and fallback to alphabetical order when radius omitted.
        - DTO Name: MarketplaceListingDetailType
          Endpoint: GET /api/marketplace/listings/{id}
          Purpose: Delivers full listing details and contact relay metadata.
          * `listing` (MarketplaceListingType) – entire entity including description, price, status, expires_at.
          * `images` (array<ImageAssetType>) – sorted with signed CDN URLs and alt text.
          * `promotion` (object|null) – indicates featured/bump status plus expiry.
          * `contact` (object) – masked email token, optional phone, rate limit hints.
          * `moderation` (object) – shows review status, flag counts, and AI fraud indicators.
          * `audit` (object) – created_at, updated_at, published_at timestamps.
          * `relatedListings` (array<MarketplaceListingSummaryType>) – suggestions to keep users engaged.
          Notes: Detail DTO fuels both HTML render and JSON API for React mounts inside listing detail page.
        - DTO Name: DirectoryCategoryViewType
          Endpoint: GET /api/good-sites/categories/{slug}
          Purpose: Already referenced in foundation; DTO merges category info, subcategories, direct links, bubbled links, and breadcrumbs.
          * `category` (DirectoryCategoryType) – includes name, slug, description, icon_url.
          * `subcategories` (array<DirectoryCategoryType>) – for navigation trees.
          * `directLinks` (array<DirectorySiteLinkType>) – top ranked sites in category.
          * `bubbledLinks` (array<DirectorySiteLinkType>) – promoted links from subcategories meeting score threshold.
          * `breadcrumbs` (array<DirectoryCategoryType>) – path back to top-level.
          * `voteContext` (object) – indicates user vote, karma impact, and eligibility to vote.
          * `pagination` (object) – page number and hasMore flag for deeper lists.
          Notes: DTO powers both Good Sites browse pages and admin review consoles.
        - DTO Name: PublicProfileResponseType
          Endpoint: GET /api/profiles/{username}
          Purpose: Returns sanitized profile content for public rendering or editing contexts.
          * `profile` (UserProfileType) – includes display_name, bio, avatar_url, location_text, website_url.
          * `template` (string) – public_homepage, your_times, or your_report.
          * `templateConfig` (JSON) – schema differs per template but always carries slots/widgets referencing feed_item IDs.
          * `publication` (object) – is_published boolean, lastPublishedAt timestamp, view_count.
          * `curatedArticles` (array<ProfileCuratedArticleType>) – includes slot_assignment, custom headline/blurb, original URLs.
          * `featureFlags` (object) – toggles for experimental template capabilities.
          * `seo` (object) – computed meta title, description, canonical URL for share cards.
          Notes: Response is sanitized via markdown allowlists and embed whitelists before returning to clients.
        - DTO Name: AnalyticsOverviewType
          Endpoint: GET /admin/api/analytics/overview
          Purpose: Summarizes click and engagement metrics for admin dashboards.
          * `totalClicksToday` (long) – aggregated clicks for current day across modules.
          * `totalClicks7d` (long) – trailing seven-day total.
          * `totalClicks30d` (long) – trailing thirty-day total.
          * `clicksByType` (map<string,long>) – counts per click_type.
          * `uniqueUsersToday` (long) – deduped user_id/session_hash counts per consent rules.
          * `uniqueUsers7d` (long) – trailing week dedupe.
          * `budgetAlerts` (array<AiBudgetAlertType>) – optionally present when AiTaggingBudgetService crosses thresholds.
          Notes: DTO feeds AntV charts on admin analytics page; filters adjust query windows without altering schema.

