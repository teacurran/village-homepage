<!-- anchor: iteration-3-plan -->
### Iteration 3: Content Aggregation, AI Tagging, and External Integrations

*   **Iteration ID:** `I3`
*   **Goal:** Implement RSS ingestion, AI tagging pipelines, weather, stocks, social integrations, StorageGateway foundation, and widget REST endpoints so personalized homepage content becomes dynamic and policy-compliant.
*   **Prerequisites:** `I1` + `I2` (auth, preferences, feature flags, rate limits).
*   **Iteration KPIs:** Feed ingestion throughput, AI tagging budget enforcement, weather/stocks/social cache hit ratios, widget API latencies <200ms, error budget tracked via observability metrics.
*   **Iteration Testing Focus:** Unit + integration coverage for feed parsing, job handlers, LangChain budget gating, weather fallback logic, social staleness banners, storage upload mocks, and widget API contract tests.
*   **Iteration Exit Criteria:** News/weather/stocks/social widgets consume live data with graceful degradation, AI tagging respects budgets w alerts, StorageGateway emits signed URLs, and docs describe refresh intervals + cost controls.
*   **Iteration Collaboration Notes:** Coordinate with ops for AI budget alerts, UI squad for widget rendering states, infra for API keys, and compliance for AI tagging prompts referencing policy reasoning.
*   **Iteration Documentation Outputs:** Update docs for feeds, AI budget, weather, stock refresh, social banners, storage gateway, widget API usage, and monitoring dashboards with anchors.
*   **Iteration Risks:** External APIs (Alpha Vantage, Meta, NWS) may throttle or change contracts; mitigate via circuit breakers, fallback caches, and feature flag kill switches documented in runbooks.
*   **Iteration Communications:** Weekly digest to stakeholders summarizing backlog health (feeds/jobs), AI spend, and API incidents; escalate blockers within 24 hours via ops channel.
*   **Iteration Dependencies:** Outputs unlock marketplace listing metadata, Good Sites screenshot service, AI categorization, and analytics instrumentation.

<!-- anchor: task-i3-t1 -->
*   **Task 3.1:**
    *   **Task ID:** `I3.T1`
    *   **Description:** Implement `rss_sources`, `feed_items`, and `user_feed_subscriptions` models, migrations, Panache entities, and admin CRUD for system feeds (categories, refresh intervals, status metrics).
        Seed categories per requirements, add CLI to import default RSS list, document governance.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Section F3, ERD, architecture diagrams.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/data/models/","src/main/java/villagecompute/homepage/api/rest/admin/"]`
    *   **Target Files:** `["migrations/V7__rss_sources.sql","src/main/java/villagecompute/homepage/data/models/RssSource.java","src/main/java/villagecompute/homepage/data/models/FeedItem.java","src/main/java/villagecompute/homepage/api/rest/admin/FeedAdminResource.java","docs/ops/feed-governance.md"]`
    *   **Deliverables:** Schema migration, Panache entities, admin APIs, CLI seed, doc.
    *   **Acceptance Criteria:** CRUD endpoints secured, validations for URL/interval, CLI seeds default feeds, doc outlines moderation + error handling.
    *   **Dependencies:** `I1.T3`, `I2.T8`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i3-t2 -->
*   **Task 3.2:**
    *   **Task ID:** `I3.T2`
    *   **Description:** Build `RssFeedRefreshJobHandler` with queue scheduling per feed priority (5/15/60 mins), HTTP client with retries, dedupe by URL hash, `feed_items` persistence, and error tracking.
        Update job matrix doc, implement telemetry + rate limiting for fetch counts, and provide tests with mock feeds.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Section F3, P2, job blueprint from I1.T4.
    *   **Input Files:** `["src/main/java/villagecompute/homepage/jobs/","src/main/java/villagecompute/homepage/services/","docs/ops/job-playbook.md"]`
    *   **Target Files:** `["src/main/java/villagecompute/homepage/jobs/RssFeedRefreshJobHandler.java","src/main/java/villagecompute/homepage/services/FeedAggregationService.java","docs/ops/job-playbook.md"]`
    *   **Deliverables:** Job handler, service updates, telemetry notes, tests.
    *   **Acceptance Criteria:** Job schedules per feed config, dedupe works, metrics exported, tests cover error cases, documentation updated.
    *   **Dependencies:** `I3.T1`, `I1.T4`, `I1.T8`.
    *   **Parallelizable:** No.

<!-- anchor: task-i3-t3 -->
*   **Task 3.3:**
    *   **Task ID:** `I3.T3`
    *   **Description:** Implement AI tagging pipeline: `AiTaggingJobHandler`, LangChain4j client configuration, batching logic (10–20 items), dedupe using URL hash, `AiTagsType` serialization, `ai_usage_tracking` table updates, and `AiTaggingBudgetService` enforcement per P2/P10.
        Add admin report summarizing spend, thresholds, email/slack alerts.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Policies P2, P10, Section 2 components, job blueprint.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/services/","src/main/java/villagecompute/homepage/jobs/","docs/ops/ai-budget.md"]`
    *   **Target Files:** `["migrations/V8__ai_usage_tracking.sql","src/main/java/villagecompute/homepage/services/AiTaggingBudgetService.java","src/main/java/villagecompute/homepage/jobs/AiTaggingJobHandler.java","docs/ops/ai-budget.md"]`
    *   **Deliverables:** Migration, services, job handler, doc, tests.
    *   **Acceptance Criteria:** Budget states (NORMAL/REDUCE/QUEUE/HARD_STOP) follow thresholds, job persists tags, telemetry logs cost, admin endpoint exposes usage, tests verify throttles.
    *   **Dependencies:** `I3.T1`, `I3.T2`, `I1.T8`.
    *   **Parallelizable:** No.

<!-- anchor: task-i3-t4 -->
*   **Task 3.4:**
    *   **Task ID:** `I3.T4`
    *   **Description:** Integrate Open-Meteo + NWS: create WeatherService with provider-specific clients, caching table `weather_cache`, location resolution (lat/lon/city ID), severe alert polling job, and fallback logic.
        Build REST endpoint `/api/widgets/weather` referencing preferences.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Feature F4, Section 2 components, job blueprint.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/integration/weather/","src/main/java/villagecompute/homepage/services/","src/main/java/villagecompute/homepage/api/rest/widgets/"]`
    *   **Target Files:** `["migrations/V9__weather_cache.sql","src/main/java/villagecompute/homepage/integration/weather/OpenMeteoClient.java","src/main/java/villagecompute/homepage/integration/weather/NwsClient.java","src/main/java/villagecompute/homepage/services/WeatherService.java","src/main/java/villagecompute/homepage/jobs/WeatherRefreshJobHandler.java","src/main/java/villagecompute/homepage/api/rest/widgets/WeatherResource.java"]`
    *   **Deliverables:** Clients, cache, job, API resource, tests, docs.
    *   **Acceptance Criteria:** Hourly refresh + 15 min alerts, provider selection based on location, API returns current/hourly/daily/alerts, metrics track cache hits/misses, tests cover fallbacks.
    *   **Dependencies:** `I2.T5`, `I2.T6`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i3-t5 -->
*   **Task 3.5:**
    *   **Task ID:** `I3.T5`
    *   **Description:** Implement StockService with Alpha Vantage integration: watchlist persistence (limit 20), refresh scheduler with market hours detection, caching, rate limit handling, sparkline generation, and `/api/widgets/stocks` endpoint.
        Provide fallback data for rate limit exhaustion, display notifications.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Feature F5, Section 2 components, job blueprint.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/services/","src/main/java/villagecompute/homepage/api/rest/widgets/"]`
    *   **Target Files:** `["migrations/V10__stock_watchlist.sql","src/main/java/villagecompute/homepage/services/StockService.java","src/main/java/villagecompute/homepage/jobs/StockRefreshJobHandler.java","src/main/java/villagecompute/homepage/api/rest/widgets/StockResource.java","docs/ops/stock-refresh.md"]`
    *   **Deliverables:** Schema, service, job, endpoint, doc, tests.
    *   **Acceptance Criteria:** Market hour logic accurate, rate limit fallback triggers warnings + reduced refresh, sparkline arrays produced, watchlist limit enforced, integration tests pass.
    *   **Dependencies:** `I2.T5`, `I2.T6`, `I1.T4`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i3-t6 -->
*   **Task 3.6:**
    *   **Task ID:** `I3.T6`
    *   **Description:** Build SocialIntegrationService for Meta Graph API: store tokens encrypted, background token refresh 7 days before expiry, cached posts retention, staleness banner logic, reconnect CTA, degrade gracefully when tokens invalid.
        Implement `/api/widgets/social` endpoint returning `SocialWidgetStateType` and update UI to display banners.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Features F6, P5, P13, Section 2 components.
    *   **Input Files:** `["migrations/","src/main/java/villagecompute/homepage/integration/social/","src/main/java/villagecompute/homepage/services/","src/main/java/villagecompute/homepage/api/rest/widgets/"]`
    *   **Target Files:** `["migrations/V11__social_tokens.sql","src/main/java/villagecompute/homepage/integration/social/MetaClient.java","src/main/java/villagecompute/homepage/services/SocialIntegrationService.java","src/main/java/villagecompute/homepage/jobs/SocialFeedRefreshJobHandler.java","src/main/java/villagecompute/homepage/api/rest/widgets/SocialResource.java","docs/ui-guides/social-widget.md"]`
    *   **Deliverables:** Migration, client, service, job, endpoint, UI doc.
    *   **Acceptance Criteria:** Tokens encrypted, refresh job scheduled, stale banners show correct color per days, cached posts served offline, e2e tests validate degrade path.
    *   **Dependencies:** `I2.T1`, `I2.T5`, `I2.T6`.
    *   **Parallelizable:** Partial.

<!-- anchor: task-i3-t7 -->
*   **Task 3.7:**
    *   **Task ID:** `I3.T7`
    *   **Description:** Implement StorageGateway abstraction for Cloudflare R2: S3 client config, helper for WebP conversion + thumbnail/full variants, signed URL generation, prefixing strategy for screenshots/listings/profiles, and retention metadata mapping.
        Provide doc + tests mocking R2.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Policy P4, P12, Section 2 components.
    *   **Input Files:** `["src/main/java/villagecompute/homepage/services/","src/main/java/villagecompute/homepage/util/","docs/ops/storage.md"]`
    *   **Target Files:** `["src/main/java/villagecompute/homepage/services/StorageGateway.java","src/main/java/villagecompute/homepage/services/StorageConfig.java","docs/ops/storage.md"]`
    *   **Deliverables:** Service, config, doc, tests verifying conversions + signed URL TTL.
    *   **Acceptance Criteria:** Upload/download functions exist, WebP conversion stub ready, signed URLs include TTL/perms, doc references buckets + retention, unit tests pass.
    *   **Dependencies:** `I1.T8`, `I1.T9`.
    *   **Parallelizable:** No.

<!-- anchor: task-i3-t8 -->
*   **Task 3.8:**
    *   **Task ID:** `I3.T8`
    *   **Description:** Wire widget REST endpoints for news (personalized, AI tags), weather, stocks, social by composing services built in earlier tasks; return DTOs with click-tracking tokens and feature flag gating info.
        Add caching headers, integrate RateLimitService, update OpenAPI spec and docs.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Tasks T3.1–T3.7, Section 2 API contract style.
    *   **Input Files:** `["src/main/java/villagecompute/homepage/api/rest/widgets/","api/openapi/v1.yaml"]`
    *   **Target Files:** `["src/main/java/villagecompute/homepage/api/rest/widgets/NewsResource.java","src/main/java/villagecompute/homepage/api/rest/widgets/WeatherResource.java","src/main/java/villagecompute/homepage/api/rest/widgets/StockResource.java","src/main/java/villagecompute/homepage/api/rest/widgets/SocialResource.java","api/openapi/v1.yaml"]`
    *   **Deliverables:** REST controllers, DTO wiring, OpenAPI updates, tests.
    *   **Acceptance Criteria:** Endpoints respond <200ms median, include AiTags/alerts/watchlist data, respect feature flags + consent, OpenAPI spec updated, tests verifying serialization + caching headers.
    *   **Dependencies:** Prior I3 service tasks, `I2.T7`.
    *   **Parallelizable:** No.

<!-- anchor: task-i3-t9 -->
*   **Task 3.9:**
    *   **Task ID:** `I3.T9`
    *   **Description:** Build monitoring + alerting for content services: Grafana dashboards for feed throughput, AI budget usage, weather cache staleness, stock rate limit hits, social refresh failures, StorageGateway errors; configure alert rules + runbooks.
        Update docs with troubleshooting steps and on-call actions.
    *   **Agent Type Hint:** `DevOpsAgent`
    *   **Inputs:** I1 observability, tasks T3.1–T3.8 outputs, Section 3.1.
    *   **Input Files:** `["docs/ops/observability.md","docs/ops/runbooks/"]`
    *   **Target Files:** `["docs/ops/observability.md","docs/ops/runbooks/content-monitoring.md",".github/workflows/build.yml"]`
    *   **Deliverables:** Dashboard definitions (JSON/YAML), alert config descriptions, runbook.
    *   **Acceptance Criteria:** Metrics exported for each service, dashboards documented, alerts thresholded (AI budget 75/90/100, feed backlog >500, weather cache >90 min), runbook includes escalation path.
    *   **Dependencies:** `I1.T8`, all preceding I3 tasks.
    *   **Parallelizable:** No.
