<!-- anchor: glossary -->
# Glossary

Comprehensive glossary of technical terms, services, components, and architectural concepts used throughout Village Homepage architecture and codebase.

**Navigation:** [A](#a) | [B](#b) | [C](#c) | [D](#d) | [E](#e) | [F](#f) | [G](#g) | [L](#l) | [M](#m) | [P](#p) | [R](#r) | [S](#s) | [U](#u) | [V](#v) | [W](#w) | [Y](#y)

**Related Documentation:**
- [Verification & Integration Strategy](VERIFICATION_STRATEGY.md) - Testing and release processes
- [Architecture Manifests](../.codemachine/artifacts/architecture/) - System design documents
- [Plan Documents](../.codemachine/artifacts/plan/) - Implementation plan by iteration

---

## A

<!-- anchor: glossary-account-merge-audit -->
### AccountMergeAudit

**Type:** Database Table

**Definition:** Table logging anonymous→auth merge events with consent metadata for GDPR compliance. Captures user_id, anonymous_session_hash, merge_timestamp, ip_address, consent_given, and user_agent for audit trail purposes.

**Related Policies:** P1 (Anonymous Account Lifecycle), P9 (Consent Tracking)

**Related Tasks:** I2.T4 (Anonymous-to-auth merge flow), I6.T6 (GDPR export includes merge history)

**Code Location:** `src/main/java/villagecompute/homepage/data/models/AccountMergeAudit.java`

**See Also:** [Anonymous Account](#glossary-anonymous-account), [UserPreferenceService](#glossary-user-preference-service)

---

<!-- anchor: glossary-ai-tagging-budget-service -->
### AiTaggingBudgetService

**Type:** Service

**Definition:** Service enforcing AI spend policies (P2/P10) returning actions NORMAL/REDUCE/QUEUE/HARD_STOP to job handlers. Monitors monthly AI usage via AiUsageTracking ledger and transitions between states based on configurable thresholds (e.g., 75% → REDUCE, 90% → QUEUE, 100% → HARD_STOP).

**Related Policies:** P2 (AI Budget Governance), P10 (AI Cost Controls)

**Related Tasks:** I3.T3 (AI tagging pipeline with budget enforcement), I5.T6 (Good Sites AI categorization)

**Code Location:** `src/main/java/villagecompute/homepage/services/AiTaggingBudgetService.java`

**See Also:** [AiUsageTracking](#glossary-ai-usage-tracking), [LangChain4j Connector](#glossary-langchain4j-connector)

---

<!-- anchor: glossary-ai-usage-tracking -->
### AiUsageTracking

**Type:** Database Table

**Definition:** Monthly ledger storing AI token counts and costs powering budget enforcement and alerts. Tracks provider, model, input_tokens, output_tokens, cost_usd, and timestamp per invocation. Aggregated monthly to enforce AiTaggingBudgetService thresholds.

**Related Policies:** P2 (AI Budget Governance), P10 (AI Cost Controls)

**Related Tasks:** I3.T3 (AI tagging pipeline), I5.T6 (AI categorization for Good Sites)

**Code Location:** `src/main/java/villagecompute/homepage/data/models/AiUsageTracking.java`

**See Also:** [AiTaggingBudgetService](#glossary-ai-tagging-budget-service), [LangChain4j Connector](#glossary-langchain4j-connector)

---

<!-- anchor: glossary-analytics-resource -->
### AnalyticsResource

**Type:** REST Resource

**Definition:** Admin REST resource exposing aggregated statistics for dashboards. Provides endpoints for click rollups, widget usage, marketplace metrics, Good Sites voting trends, and profile view counts. Powers admin analytics dashboards built in I3.T9 and I6.T5.

**Related Policies:** P14 (Analytics Consent)

**Related Tasks:** I3.T9 (Analytics dashboard foundation), I6.T5 (Admin analytics dashboards with React/AntV)

**Code Location:** `src/main/java/villagecompute/homepage/api/rest/AnalyticsResource.java`

**See Also:** [ClickStatsRollupJobHandler](#glossary-click-stats-rollup-job-handler), [ClickTrackingService](#glossary-click-tracking-service)

---

<!-- anchor: glossary-anonymous-account -->
### Anonymous Account

**Type:** Concept

**Definition:** Temporary user account identified by session hash, allowing homepage personalization without authentication. Layout preferences stored in JSONB. Upgraded to authenticated account via OAuth login with optional merge flow (I2.T4). Lifecycle governed by Policy P1.

**Related Policies:** P1 (Anonymous Account Lifecycle), P9 (Consent Tracking)

**Related Tasks:** I2.T5 (User preference service), I2.T4 (Anonymous-to-auth merge)

**Code Location:** `src/main/java/villagecompute/homepage/data/models/User.java` (user_type='anonymous')

**See Also:** [AccountMergeAudit](#glossary-account-merge-audit), [UserPreferenceService](#glossary-user-preference-service)

---

## B

<!-- anchor: glossary-bulk-import-job-handler -->
### BulkImportJobHandler

**Type:** Job Handler

**Definition:** Good Sites job processing CSV/JSON submissions with AI categorization suggestions. Accepts bulk uploads of links, extracts OpenGraph metadata, captures screenshots via ScreenshotService, and suggests categories using LangChain4j. Queued entries await moderator approval.

**Related Policies:** P2 (AI Budget Governance), P13 (Bulk Import)

**Related Tasks:** I5.T8 (Bulk import job handler for Good Sites)

**Code Location:** `src/main/java/villagecompute/homepage/jobs/BulkImportJobHandler.java`

**See Also:** [DirectoryService](#glossary-directory-service), [ScreenshotService](#glossary-screenshot-service), [LangChain4j Connector](#glossary-langchain4j-connector)

---

## C

<!-- anchor: glossary-click-stats-rollup-job-handler -->
### ClickStatsRollupJobHandler

**Type:** Job Handler

**Definition:** Job aggregating raw click logs into daily summary tables used by admin analytics (I3.T9, I6.T4). Runs hourly to summarize link_clicks partition data into rollup tables (clicks_daily, clicks_weekly) for dashboard queries. Supports partition retention by archiving rolled-up data.

**Related Policies:** P14 (Analytics Consent)

**Related Tasks:** I3.T9 (Analytics dashboard foundation), I6.T4 (Click tracking rollups)

**Code Location:** `src/main/java/villagecompute/homepage/jobs/ClickStatsRollupJobHandler.java`

**See Also:** [ClickTrackingService](#glossary-click-tracking-service), [AnalyticsResource](#glossary-analytics-resource)

---

<!-- anchor: glossary-click-tracking-service -->
### ClickTrackingService

**Type:** REST Resource

**Definition:** REST resource `/track/click` logging link engagements and redirecting users. Foundation for analytics and cohort measurement (I3, I6). Records user_id, session_hash, link_id, timestamp, referrer, user_agent to link_clicks partition. Used by widgets, Good Sites, profiles.

**Related Policies:** P14 (Analytics Consent)

**Related Tasks:** I3.T9 (Click tracking foundation), I6.T4 (Click tracking rollups)

**Code Location:** `src/main/java/villagecompute/homepage/api/rest/ClickTrackingService.java`

**See Also:** [ClickStatsRollupJobHandler](#glossary-click-stats-rollup-job-handler), [AnalyticsResource](#glossary-analytics-resource)

---

<!-- anchor: glossary-config-provider-tokens -->
### ConfigProvider Tokens

**Type:** Frontend Concept

**Definition:** Ant Design theming mechanism used by React islands to match server-rendered Qute styles. Tokens define colors, spacing, typography, and component styles. Injected into React islands via `<ConfigProvider>` wrapper to ensure visual consistency between SSR and client-side rendering.

**Related Policies:** None (frontend pattern)

**Related Tasks:** I2.T9 (Frontend build pipeline with ConfigProvider setup), I6.T2 (Profile templates with theme tokens)

**Code Location:** `src/main/webui/shared/theme/tokens.ts`

**See Also:** [ExperienceShell](#glossary-experience-shell), [Gridstack Editor](#glossary-gridstack-editor)

---

## D

<!-- anchor: glossary-directory-service -->
### DirectoryService

**Type:** Domain Service

**Definition:** Domain service powering Good Sites submissions, voting, bubbling, screenshot usage (I5). Handles category management, link validation, vote counting, karma updates, moderator actions, and screenshot lifecycle. Implements Policy P5 (Good Sites Karma) and P6 (Category Bubbling).

**Related Policies:** P5 (Good Sites Karma), P6 (Category Bubbling), P4 (Screenshot Lifecycle)

**Related Tasks:** I5.T2 (DirectoryService foundation), I5.T3 (Screenshot integration), I5.T4 (Karma system), I5.T5 (Category bubbling)

**Code Location:** `src/main/java/villagecompute/homepage/services/DirectoryService.java`

**See Also:** [ScreenshotService](#glossary-screenshot-service), [GoodSites Karma](#glossary-goodsites-karma), [BulkImportJobHandler](#glossary-bulk-import-job-handler)

---

## E

<!-- anchor: glossary-experience-shell -->
### ExperienceShell

**Type:** Qute Controller + Layout

**Definition:** Qute controller and layout orchestrator delivering homepage and profile templates with React mount points. Provides SSR HTML shell with embedded React islands for interactive widgets (gridstack, editor, analytics). Coordinates authentication state, feature flags, theme tokens, and widget data injection.

**Related Policies:** P7 (Feature Flag Evaluation)

**Related Tasks:** I2.T6 (Gridstack editor integration), I6.T2 (Profile template routing and preview)

**Code Location:** `src/main/java/villagecompute/homepage/api/rest/ExperienceShellResource.java`, `src/main/resources/templates/shell.html`

**See Also:** [Gridstack Editor](#glossary-gridstack-editor), [ConfigProvider Tokens](#glossary-config-provider-tokens)

---

## F

<!-- anchor: glossary-feature-flag-service -->
### FeatureFlagService

**Type:** Service

**Definition:** Cohort-hashing system (P7/P14) providing rollout control and analytics logging per iteration I2. Uses stable MD5 hash of `flagKey + ":" + subjectId` for deterministic cohort assignment. Supports master kill switch, whitelist, rollout percentage, and consent-aware evaluation logging.

**Related Policies:** P7 (Feature Flag Evaluation), P14 (Analytics Consent)

**Related Tasks:** I2.T2 (FeatureFlagService implementation), I6.T8 (Release gates documentation)

**Code Location:** `src/main/java/villagecompute/homepage/services/FeatureFlagService.java`

**See Also:** [Verification & Integration Strategy](#glossary-verification-and-integration-strategy)

---

## G

<!-- anchor: glossary-gdpr-export-package -->
### GDPR Export Package

**Type:** Artifact

**Definition:** Zipped JSON archive generated by DataExportJobHandler containing layout, feeds, marketplace, Good Sites, profile content per user. Includes user_preferences, custom_feeds, marketplace_listings, marketplace_messages, directory_links, directory_votes, profile_templates, and account_merge_audit. Stored in Cloudflare R2 with signed URL for download.

**Related Policies:** P1 (Data Export), P9 (Consent Tracking)

**Related Tasks:** I6.T6 (GDPR export and deletion flows)

**Code Location:** `src/main/java/villagecompute/homepage/jobs/DataExportJobHandler.java`

**See Also:** [ProfileCurationService](#glossary-profile-curation-service), [DirectoryService](#glossary-directory-service)

---

<!-- anchor: glossary-goodsites-karma -->
### GoodSites Karma

**Type:** Reputation System

**Definition:** Reputation system awarding points for quality submissions and votes, controlling auto-publish and moderator eligibility (I5.T4). Users earn karma for upvoted submissions (+10), successful reports (+5), and lose karma for rejected submissions (-5). Thresholds: trusted (50+), moderator-eligible (200+).

**Related Policies:** P5 (Good Sites Karma)

**Related Tasks:** I5.T4 (Karma system implementation)

**Code Location:** `src/main/java/villagecompute/homepage/data/models/DirectoryKarma.java`

**See Also:** [DirectoryService](#glossary-directory-service), [BulkImportJobHandler](#glossary-bulk-import-job-handler)

---

<!-- anchor: glossary-gridstack-editor -->
### Gridstack Editor

**Type:** React Component

**Definition:** React-based widget layout tool enabling drag-and-drop, edit mode, and keyboard adjustments (I2.T6). Uses gridstack.js for layout engine. Supports add/remove/resize/reorder widgets, persists layout to UserPreferenceService as JSONB. Integrated into ExperienceShell with ConfigProvider theme tokens.

**Related Policies:** P1 (Anonymous Account Lifecycle)

**Related Tasks:** I2.T6 (Gridstack editor implementation), I2.T5 (User preference service with JSONB layout)

**Code Location:** `src/main/webui/islands/GridstackEditor.tsx`

**See Also:** [UserPreferenceService](#glossary-user-preference-service), [ExperienceShell](#glossary-experience-shell)

---

## L

<!-- anchor: glossary-langchain4j-connector -->
### LangChain4j Connector

**Type:** AI Integration

**Definition:** AI abstraction for Claude Sonnet used in feed tagging, fraud detection, Good Sites categorization (I3, I5). Model-agnostic connector enables switching providers without code changes. Budget enforcement via AiTaggingBudgetService. Use cases: RSS feed topic extraction, marketplace spam detection, directory link categorization.

**Related Policies:** P2 (AI Budget Governance), P10 (AI Cost Controls)

**Related Tasks:** I3.T3 (AI tagging pipeline), I5.T6 (Good Sites AI categorization)

**Code Location:** `src/main/java/villagecompute/homepage/integration/ai/LangChain4jConnector.java`

**See Also:** [AiTaggingBudgetService](#glossary-ai-tagging-budget-service), [AiUsageTracking](#glossary-ai-usage-tracking)

---

<!-- anchor: glossary-link-health-check-job-handler -->
### LinkHealthCheckJobHandler

**Type:** Job Handler

**Definition:** Directory job validating URL availability, marking entries dead, and informing moderators. Runs weekly to check HTTP status codes for Good Sites links. Links returning 404/410/5xx for 3+ consecutive checks are marked as dead. Moderators receive notification to review/remove. Implements Policy P4 (Link Health).

**Related Policies:** P4 (Link Health)

**Related Tasks:** I5.T7 (Link health check job handler)

**Code Location:** `src/main/java/villagecompute/homepage/jobs/LinkHealthCheckJobHandler.java`

**See Also:** [DirectoryService](#glossary-directory-service), [Runbook](#glossary-runbook)

---

## M

<!-- anchor: glossary-marketplace-message-relay -->
### MarketplaceMessage Relay

**Type:** Email Infrastructure

**Definition:** Masked email infrastructure bridging buyers and sellers using MessageRelayService and InboundEmailProcessor (I4.T7). Email format: `homepage-marketplace-{listing_id}@villagecompute.com`. IMAP poller fetches inbound messages, validates sender, relays to recipient, and logs to marketplace_messages table. Prevents address harvesting and spam.

**Related Policies:** P11 (Payment Processing), P3 (PCI Compliance)

**Related Tasks:** I4.T7 (Marketplace message relay implementation)

**Code Location:** `src/main/java/villagecompute/homepage/services/MessageRelayService.java`, `src/main/java/villagecompute/homepage/jobs/InboundEmailProcessor.java`

**See Also:** [MarketplaceService](#glossary-marketplace-service), [Runbook](#glossary-runbook)

---

<!-- anchor: glossary-marketplace-service -->
### MarketplaceService

**Type:** Domain Service

**Definition:** Domain service managing categories, listings, payments, promotions, moderation (I4). Handles listing creation, image uploads via StorageGateway, geographic filtering (PostGIS radius search), payment processing via Stripe, promotion add-ons (featured/bump), and moderation queues. Implements Policy P11 (Payment Processing).

**Related Policies:** P11 (Payment Processing), P3 (PCI Compliance)

**Related Tasks:** I4.T2 (MarketplaceService foundation), I4.T3 (Listing lifecycle with images), I4.T4 (Payment flows), I4.T8 (Moderation)

**Code Location:** `src/main/java/villagecompute/homepage/services/MarketplaceService.java`

**See Also:** [StorageGateway](#glossary-storage-gateway), [PromotionExpirationJobHandler](#glossary-promotion-expiration-job-handler), [MarketplaceMessage Relay](#glossary-marketplace-message-relay)

---

## P

<!-- anchor: glossary-plan-manifest -->
### Plan Manifest

**Type:** Artifact

**Definition:** JSON index mapping anchors → files/descriptions enabling autonomous agent navigation (I6.T9). Generated via script that parses all plan and architecture documents for anchor comments. Includes file path, anchor name, section title, description, and parent relationships. Enables precise section retrieval by autonomous agents.

**Related Policies:** None (documentation pattern)

**Related Tasks:** I6.T9 (Release checklist with manifest generation)

**Code Location:** `.codemachine/artifacts/plan/plan_manifest.json`

**See Also:** [Verification & Integration Strategy](#glossary-verification-and-integration-strategy), [Glossary](#glossary)

---

<!-- anchor: glossary-profile-curation-service -->
### ProfileCurationService

**Type:** Backend Component

**Definition:** Backend component managing curated articles, manual links, slot assignments for public templates (I6.T3). Handles CRUD for curated_articles, refreshes metadata via ProfileMetadataRefreshJobHandler, validates slot types (headline/feature/brief), and powers "Your Times" and "Your Report" template modes with drag-and-drop slot assignment.

**Related Policies:** P1 (Data Export)

**Related Tasks:** I6.T3 (Profile curation tooling), I6.T2 (Profile templates)

**Code Location:** `src/main/java/villagecompute/homepage/services/ProfileCurationService.java`

**See Also:** [ProfileMetadataRefreshJobHandler](#glossary-profile-metadata-refresh-job-handler), [Your Times / Your Report](#glossary-your-times-your-report)

---

<!-- anchor: glossary-profile-metadata-refresh-job-handler -->
### ProfileMetadataRefreshJobHandler

**Type:** Job Handler

**Definition:** Job refreshing curated article metadata when sources change, ensuring template content stays current (I6.T3). Runs daily to fetch updated OpenGraph metadata (title, description, image) for curated_articles. Detects 404s and flags stale entries for moderator review. Updates screenshot via ScreenshotService if needed.

**Related Policies:** P4 (Screenshot Lifecycle)

**Related Tasks:** I6.T3 (Profile curation tooling)

**Code Location:** `src/main/java/villagecompute/homepage/jobs/ProfileMetadataRefreshJobHandler.java`

**See Also:** [ProfileCurationService](#glossary-profile-curation-service), [ScreenshotService](#glossary-screenshot-service)

---

<!-- anchor: glossary-promotion-expiration-job-handler -->
### PromotionExpirationJobHandler

**Type:** Job Handler

**Definition:** Marketplace job expiring featured listings and bump add-ons while coordinating refunds and badges (I4.T4). Runs daily to check promotion_expirations table, marks expired promotions, issues Stripe refunds if unused, removes featured/bump badges, and logs to audit trail.

**Related Policies:** P11 (Payment Processing)

**Related Tasks:** I4.T4 (Promotion add-ons with expiration)

**Code Location:** `src/main/java/villagecompute/homepage/jobs/PromotionExpirationJobHandler.java`

**See Also:** [MarketplaceService](#glossary-marketplace-service), [Runbook](#glossary-runbook)

---

## R

<!-- anchor: glossary-rate-limit-service -->
### RateLimitService

**Type:** Service

**Definition:** Tiered limiter storing violations, providing headers, and admin tuning endpoints (I2.T3). Enforces per-action rate limits based on user tier (anonymous/basic/premium). Returns RateLimitStatus with remaining quota, reset time, and violation flags. Admin endpoints adjust thresholds and view violation reports.

**Related Policies:** P8 (Rate Limiting)

**Related Tasks:** I2.T3 (Rate limiting service)

**Code Location:** `src/main/java/villagecompute/homepage/services/RateLimitService.java`

**See Also:** [FeatureFlagService](#glossary-feature-flag-service)

---

<!-- anchor: glossary-runbook -->
### Runbook

**Type:** Ops Document

**Definition:** Ops document describing monitoring, remediation, escalation for jobs and services stored in `docs/ops/runbooks/`. Each runbook includes: service overview, daily health checks, alert thresholds, common failure scenarios, troubleshooting steps, escalation paths (L1 → L2 → L3), and links to relevant dashboards.

**Related Policies:** None (operational pattern)

**Related Tasks:** I1.T4 (Job matrix with runbook template), I3.T9 (Analytics runbook), I5.T7 (Good Sites runbook)

**Code Location:** `docs/ops/runbooks/*.md`

**See Also:** [Verification & Integration Strategy](#glossary-verification-and-integration-strategy)

---

## S

<!-- anchor: glossary-screenshot-service -->
### ScreenshotService

**Type:** Service

**Definition:** jvppeteer-based capture pipeline storing WebP versions with version history and concurrency controls (I5.T3). Launches headless Chromium via jvppeteer, captures 1280x800 viewport screenshots, converts to WebP via StorageGateway, and stores in Cloudflare R2. Supports retry logic, concurrent capture limits, and screenshot versioning.

**Related Policies:** P4 (Screenshot Lifecycle), P12 (R2 Storage)

**Related Tasks:** I5.T3 (Screenshot service implementation), I3.T7 (StorageGateway integration)

**Code Location:** `src/main/java/villagecompute/homepage/services/ScreenshotService.java`

**See Also:** [StorageGateway](#glossary-storage-gateway), [DirectoryService](#glossary-directory-service), [BulkImportJobHandler](#glossary-bulk-import-job-handler)

---

<!-- anchor: glossary-storage-gateway -->
### StorageGateway

**Type:** Service

**Definition:** Cloudflare R2 abstraction handling uploads, WebP conversion, signed URLs, retention policies (I3.T7). S3-compatible client for screenshots, listings, profile assets. Supports multipart uploads, automatic WebP conversion via ImageIO, signed URL generation with expiration, and lifecycle policies for retention. Buckets: `homepage-screenshots`, `homepage-listings`.

**Related Policies:** P4 (Screenshot Lifecycle), P12 (R2 Storage)

**Related Tasks:** I3.T7 (StorageGateway implementation), I4.T6 (Listing image uploads), I5.T3 (Screenshot storage)

**Code Location:** `src/main/java/villagecompute/homepage/services/StorageGateway.java`

**See Also:** [ScreenshotService](#glossary-screenshot-service), [MarketplaceService](#glossary-marketplace-service)

---

## U

<!-- anchor: glossary-user-preference-service -->
### UserPreferenceService

**Type:** Service

**Definition:** JSONB layout and config manager supporting anonymous and auth personalization (I2.T5). Stores widget layout (gridstack positions), feed subscriptions, theme preferences, notification settings in user_preferences.config JSONB column. Supports merge flow from anonymous to authenticated accounts via AccountMergeAudit.

**Related Policies:** P1 (Anonymous Account Lifecycle), P9 (Consent Tracking)

**Related Tasks:** I2.T5 (User preference service implementation), I2.T4 (Anonymous-to-auth merge)

**Code Location:** `src/main/java/villagecompute/homepage/services/UserPreferenceService.java`

**See Also:** [Anonymous Account](#glossary-anonymous-account), [Gridstack Editor](#glossary-gridstack-editor), [AccountMergeAudit](#glossary-account-merge-audit)

---

## V

<!-- anchor: glossary-verification-and-integration-strategy -->
### Verification & Integration Strategy

**Type:** Documentation

**Definition:** Section summarizing testing and release process referencing tasks across iterations (I6.T8). Covers testing levels (unit/integration/E2E/performance), CI/CD workflow, quality gates, integration validation, artifact validation, data integrity, operational readiness, release gates, plan manifest, data migration, security validation, rollout playbook, support, and documentation review.

**Related Policies:** All policies (P1-P14)

**Related Tasks:** I6.T8 (Verification strategy authoring)

**Code Location:** `docs/VERIFICATION_STRATEGY.md`

**See Also:** [Glossary](#glossary), [Plan Manifest](#glossary-plan-manifest), [Runbook](#glossary-runbook)

---

## W

<!-- anchor: glossary-widget-resources -->
### Widget Resources

**Type:** REST Endpoints

**Definition:** REST endpoints `/api/widgets/{news|weather|stocks|social}` providing data for homepage experiences (I3.T8). Each widget resource fetches data from external integrations (RSS, Open-Meteo, Alpha Vantage, Meta Graph API), applies rate limiting, enforces feature flags, and returns typed responses for React islands.

**Related Policies:** P7 (Feature Flag Evaluation), P8 (Rate Limiting)

**Related Tasks:** I3.T8 (Widget resource endpoints for news/weather/stocks/social)

**Code Location:** `src/main/java/villagecompute/homepage/api/rest/WidgetResource.java`

**See Also:** [ExperienceShell](#glossary-experience-shell), [FeatureFlagService](#glossary-feature-flag-service)

---

## Y

<!-- anchor: glossary-your-times-your-report -->
### Your Times / Your Report

**Type:** Profile Templates

**Definition:** Profile template modes replicating newspaper and link-aggregation layouts with curated slot tooling (I6.T2). "Your Times" mimics newspaper layout with headline/feature/brief slots. "Your Report" mimics link aggregator with ranked links and comments. Both powered by ProfileCurationService with drag-and-drop slot assignment.

**Related Policies:** P1 (Data Export)

**Related Tasks:** I6.T2 (Profile template routing and preview), I6.T3 (Profile curation tooling)

**Code Location:** `src/main/resources/templates/profiles/your-times.html`, `src/main/resources/templates/profiles/your-report.html`

**See Also:** [ProfileCurationService](#glossary-profile-curation-service), [ExperienceShell](#glossary-experience-shell)

---

**Document Version:** 1.0
**Last Updated:** 2025-01-21
**Task:** I6.T8 - Glossary
**Total Entries:** 30
