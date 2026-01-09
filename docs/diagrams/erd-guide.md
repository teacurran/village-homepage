# Village Homepage - Entity Relationship Diagram Guide

This document provides a comprehensive explanation of the Village Homepage database schema, including table purposes, relationships, indexing strategies, partitioning decisions, retention policies, and compliance mappings.

---

## Overview

The Village Homepage ERD covers **9 primary domains** with **40+ core tables** supporting:

- **Core User Management:** Authentication, profiles, consents, admin roles
- **Content Aggregation:** RSS feeds, AI tagging, usage tracking
- **Widget Caching:** Weather, stocks, social media
- **Marketplace:** Classified listings, messaging, payments, refunds
- **Directory:** Curated web directory with voting, rankings, screenshots
- **Infrastructure:** Delayed jobs, feature flags, rate limiting
- **Analytics:** Click tracking, aggregated rollups
- **Compliance:** Payment audits, consent tracking, data retention
- **Internationalization:** Future expansion placeholders

All tables follow VillageCompute standards:
- **Hibernate ORM + Panache** (ActiveRecord pattern with static finder methods)
- **PostgreSQL 17 + PostGIS** for geo-spatial support
- **Elasticsearch 8** for full-text search (Hibernate Search backend)
- **MyBatis Migrations** for schema evolution

---

## Table of Contents

1. [Core Domain: Users, Authentication, Profiles](#1-core-domain-users-authentication-profiles)
2. [Feed & Content Aggregation](#2-feed--content-aggregation)
3. [Widget Caching: Weather, Stocks, Social](#3-widget-caching-weather-stocks-social)
4. [Marketplace: Listings, Payments, Geo Filtering](#4-marketplace-listings-payments-geo-filtering)
5. [Good Sites Directory: Voting, Screenshots, Categories](#5-good-sites-directory-voting-screenshots-categories)
6. [Infrastructure: Jobs, Feature Flags, Rate Limits](#6-infrastructure-jobs-feature-flags-rate-limits)
7. [Analytics & Compliance](#7-analytics--compliance)
8. [Internationalization Placeholders](#8-internationalization-placeholders)
9. [Cross-Cutting Concerns](#9-cross-cutting-concerns)
10. [Policy Mapping Reference](#10-policy-mapping-reference)
11. [Performance & Scalability](#11-performance--scalability)
12. [Migration Strategy](#12-migration-strategy)

---

## 1. Core Domain: Users, Authentication, Profiles

### 1.1 `users`

**Purpose:** Central user identity table supporting OAuth, anonymous accounts, and account merging.

**Key Features:**
- **OAuth Integration:** Stores `oauth_provider` (google|facebook|apple) and `oauth_subject` for federated identity
- **Anonymous Support:** `vu_anon_id` UUID for cookie-based tracking (P9); `is_anonymous` flag for upgrade flow
- **Soft Deletion:** `deleted_at` timestamp for GDPR right-to-erasure (P1)

**Indexes:**
- `email_idx` (UNIQUE) - Login lookup
- `anon_id_idx` - Anonymous user resolution
- `oauth_idx` (oauth_provider, oauth_subject) UNIQUE WHERE NOT NULL - OAuth callback matching
- `status_created_idx` (account_status, created_at) - Admin dashboards

**Policies:**
- **P1:** GDPR consent management, account merge flow (anonymous → authenticated), data export/deletion
- **P9:** Anonymous user tracking via `vu_anon_id` cookie

**Retention:** Indefinite for active users; soft delete with 30-day grace period before hard deletion (P1)

---

### 1.2 `user_sessions`

**Purpose:** JWT session tracking for authentication and security auditing.

**Key Features:**
- `session_token` and `refresh_token` for token-based auth
- `ip_address` and `user_agent` for security logging
- `expires_at` for automatic session cleanup

**Indexes:**
- `session_token_idx` (UNIQUE) - Fast session validation
- `refresh_token_idx` (UNIQUE WHERE NOT NULL) - Token refresh flow
- `user_expires_idx` (user_id, expires_at) - User session listing

**Retention:** **TTL: 90 days after expiration** (P14)

---

### 1.3 `user_consents`

**Purpose:** GDPR/CCPA consent tracking for legal compliance.

**Consent Types:**
- `gdpr` - General data processing consent (P1)
- `analytics` - Click tracking and analytics (P14)
- `marketing` - Email communications
- `ai_tagging` - LangChain4j content tagging (P2/P10)

**Key Features:**
- `granted` boolean with `granted_at` and `revoked_at` timestamps
- `ip_address`, `user_agent`, `version` for audit trails

**Indexes:**
- `user_consent_type_idx` (user_id, consent_type) - Consent lookup
- `granted_idx` (granted, granted_at) - Active consent queries

**Retention:** **Indefinite** - Required for legal compliance (P1)

---

### 1.4 `user_profiles`

**Purpose:** Public user profiles with homepage templates and karma scores.

**Key Features:**
- **Public Templates:** `public_homepage_enabled`, `your_times_enabled`, `your_report_enabled` flags (P8)
- **Widget Preferences:** `preferences` JSONB stores gridstack layout, theme, locale
- **Karma Score:** `karma_score` INTEGER for directory trust (P7) - untrusted users go to moderation queue
- **Public Username:** `public_username` for shareable profile URLs

**Indexes:**
- `user_id_idx` (UNIQUE) - One profile per user
- `public_username_idx` (UNIQUE WHERE NOT NULL) - SEO-friendly URLs
- `karma_idx` (karma_score DESC) - Trust leaderboards
- `public_homepage_idx` (public_homepage_enabled, user_id) - Public profile discovery

**Policies:**
- **P1:** User data included in GDPR export/deletion
- **P8:** JSONB preferences store React island state (gridstack.js layouts)

---

### 1.5 `admin_roles`

**Purpose:** Admin role assignment with audit trails.

**Role Types:**
- `super_admin` - Full system access, user management, feature flags
- `support` - Customer support, content moderation
- `ops` - Infrastructure monitoring, job management
- `read_only` - View-only access to admin dashboards

**Key Features:**
- `granted_by` references admin who assigned role
- `revoked_at` for role revocation (soft delete pattern)

**Indexes:**
- `user_role_idx` (user_id, role_type) UNIQUE WHERE revoked_at IS NULL - Active roles only
- `role_active_idx` (role_type, granted_at) WHERE revoked_at IS NULL - Role listings

**Retention:** **Indefinite** for audit trails

---

## 2. Feed & Content Aggregation

### 2.1 `rss_sources`

**Purpose:** RSS feed registry supporting system-managed and user-custom feeds.

**Key Features:**
- **Source Types:**
  - `system` - Curated by VillageCompute (TechCrunch, BBC, etc.)
  - `user_custom` - User-added feeds (P1: included in data export)
- **Health Monitoring:** `last_fetch_status`, `last_error_message` for operational visibility
- **Configurable Refresh:** `refresh_interval_minutes` (15min to daily)

**Indexes:**
- `feed_url_idx` (UNIQUE) - Prevent duplicate feeds
- `source_type_active_idx` (source_type, is_active, refresh_interval_minutes) - Job scheduling
- `next_fetch_idx` (last_fetched_at, refresh_interval_minutes) WHERE is_active = true - DEFAULT queue job picker

**Policies:**
- **P1:** User-created feeds included in GDPR data export

**Retention:** Indefinite for active feeds; 90 days after deactivation

---

### 2.2 `feed_items`

**Purpose:** Aggregated news articles from RSS sources with AI tagging.

**Key Features:**
- **Deduplication:** `item_guid` from RSS feed (UNIQUE), `content_hash` for similarity detection
- **AI Tags:** `ai_tags` JSONB stores LangChain4j-generated topics, sentiment, categories (P2/P10)
- **Partitioning:** Monthly partitions by `published_at` for efficient pruning

**Indexes:**
- `guid_idx` (UNIQUE) - Deduplication on ingestion
- `source_published_idx` (rss_source_id, published_at DESC) - Feed-specific timelines
- `published_idx` (published_at DESC) - Global news feed
- `ai_tagged_idx` (ai_tagged) WHERE ai_tagged = false - BULK queue job picker for tagging

**Partitioning:**
- **Monthly partitions** by `published_at`
- Partition naming: `feed_items_y2026m01`, `feed_items_y2026m02`, etc.
- **Automated retention:** Drop partitions older than 90 days (except bookmarked items)

**Retention:**
- **TTL: 90 days** for non-bookmarked items (P14)
- Bookmarked items: Indefinite (user-facing feature)

**Policies:**
- **P2/P10:** AI tagging budget control - BULK queue jobs check `ai_usage_tracking` for $500/month ceiling

---

### 2.3 `user_feed_subscriptions`

**Purpose:** Many-to-many relationship between users and RSS sources.

**Key Features:**
- `subscribed_at` and `unsubscribed_at` for temporal tracking
- Soft delete pattern (keeps historical data)

**Indexes:**
- `user_source_idx` (user_id, rss_source_id) UNIQUE WHERE unsubscribed_at IS NULL - Active subscriptions only

**Policies:**
- **P1:** User subscriptions included in GDPR export

---

### 2.4 `ai_usage_tracking`

**Purpose:** Cost tracking and budget enforcement for LangChain4j API calls.

**Usage Types:**
- `feed_tagging` - Content categorization (P2)
- `fraud_detection` - Marketplace listing moderation
- `categorization` - Directory site auto-categorization

**Key Features:**
- Token counts (`prompt_tokens`, `completion_tokens`) for billing reconciliation
- `total_cost_usd` DECIMAL(10,6) for precise cost accounting
- `metadata` JSONB stores request/response samples for audit

**Indexes:**
- `usage_type_created_idx` (usage_type, created_at DESC) - Usage reports
- `cost_tracking_idx` (created_at, total_cost_usd) - Monthly budget aggregation (P2/P10)

**Partitioning:** Monthly partitions by `created_at`

**Retention:** **Indefinite** for cost audit trails (P10)

**Policies:**
- **P2/P10:** $500/month budget ceiling - AiTaggingService queries this table before API calls
- Admin dashboard shows daily/monthly spend trends

---

## 3. Widget Caching: Weather, Stocks, Social

### 3.1 `weather_cache`

**Purpose:** Location-based weather forecast caching to reduce external API calls.

**Key Features:**
- **Geo Hashing:** `location_hash` = MD5(ROUND(lat, 2) || ',' || ROUND(lng, 2)) for cache key
- **Provider Support:** `open_meteo` (international) and `nws` (US-only)
- **JSONB Forecasts:** `forecast_data` stores hourly + 7-day forecasts

**Indexes:**
- `location_hash_idx` (location_hash, expires_at DESC) - Cache lookup
- `expires_idx` (expires_at) - TTL cleanup job (LOW queue)

**Retention:** **TTL: 7 days after expiration**

**Policies:**
- **1-hour refresh cadence** via DEFAULT queue (per container.puml)

---

### 3.2 `stock_quotes`

**Purpose:** Real-time stock market data from Alpha Vantage with historical retention.

**Key Features:**
- `symbol` (e.g., AAPL, MSFT), `market` (NYSE, NASDAQ)
- `price_usd`, `change_percent`, `volume` for widget display
- `metadata` JSONB stores extended Alpha Vantage data (52-week high/low, etc.)

**Indexes:**
- `symbol_timestamp_idx` (symbol, quote_timestamp DESC) - Per-symbol timelines
- `market_timestamp_idx` (market, quote_timestamp DESC) - Market aggregations

**Partitioning:** Monthly partitions by `quote_timestamp`

**Retention:** **TTL: 30 days** for historical quotes (keep recent for charting)

**Policies:**
- **5-min refresh during market hours** via HIGH queue (per container.puml)
- Outside market hours: No refresh (saves API quota)

---

### 3.3 `social_tokens`

**Purpose:** OAuth token storage for Meta Graph API (Instagram/Facebook integration).

**Key Features:**
- **Platforms:** `instagram`, `facebook`
- **Token Security:** `access_token` and `refresh_token` encrypted at rest via PostgreSQL TDE
- **Expiration Tracking:** `expires_at` for automatic refresh (HIGH queue job)

**Indexes:**
- `user_platform_idx` (user_id, platform) UNIQUE WHERE revoked_at IS NULL - One token per platform per user
- `expires_idx` (expires_at) WHERE revoked_at IS NULL - Token refresh job picker

**Retention:** **Indefinite until revocation** (P5/P13)

**Policies:**
- **P5/P13:** Social token storage (indefinite per policy requirement)
- **P1:** Included in GDPR data export/deletion
- **Encryption:** Database Transparent Data Encryption (TDE) required in production

---

### 3.4 `social_posts`

**Purpose:** Cached Instagram/Facebook posts for social feed widgets.

**Key Features:**
- `post_type` (image|video|carousel|story)
- `media_urls` JSONB array of CDN URLs
- `engagement_data` JSONB (likes, comments, shares) refreshed periodically

**Indexes:**
- `token_posted_idx` (social_token_id, posted_at DESC) - User feed timeline
- `platform_post_id_idx` (platform, platform_post_id) UNIQUE - Deduplication

**Retention:** **TTL: 90 days after fetched_at** (P13)

**Policies:**
- **P5/P13:** Social integration data retention
- **30-min refresh cadence** via DEFAULT queue

---

## 4. Marketplace: Listings, Payments, Geo Filtering

### 4.1 `marketplace_categories`

**Purpose:** Hierarchical category tree for classified listings.

**Category Structure:**
```
For Sale
├── Automotive
├── Electronics
└── Furniture
Housing
├── Apartments
├── Houses
└── Roommates
Jobs
├── Full-Time
├── Part-Time
└── Gigs
Services
├── Home Services
├── Professional Services
└── Lessons
Community
├── Events
├── Groups
└── Volunteers
```

**Key Features:**
- Self-referential `parent_id` for unlimited depth
- `slug` for SEO-friendly URLs
- `display_order` for admin-controlled sorting

**Indexes:**
- `parent_display_idx` (parent_id, display_order) - Category tree queries
- `slug_idx` (slug) UNIQUE - URL routing

---

### 4.2 `marketplace_listings`

**Purpose:** Classified listings with geo-spatial filtering and image support.

**Key Features:**
- **Login Required:** `user_id` foreign key (P3: not anonymous)
- **Geo-Spatial:** `location_geog` GEOGRAPHY(POINT, 4326) with PostGIS GIST index (P6/P11)
- **Radius Filtering:** 5mi, 10mi, 25mi, 50mi, 100mi, 250mi, or Any (via PostGIS `ST_DWithin`)
- **Image Storage:** `images` JSONB array (up to 12 S3 object keys per listing)
- **Contact Masking:** `contact_method` (email_relay|phone), `contact_value` for privacy (P3)
- **Promotions:** `is_featured` flag, `featured_until` timestamp for paid promotions (P3: Stripe payments)

**Indexes:**
- `user_status_idx` (user_id, status, published_at DESC) - User listing management
- `category_status_pub_idx` (category_id, status, published_at DESC) - Category browsing
- `location_geog_gist` (location_geog) USING GIST - **Critical for radius search** (P6/P11)
- `status_expires_idx` (status, expires_at) WHERE status = 'active' - Expiration job (DEFAULT queue)
- `featured_idx` (is_featured, featured_until DESC) WHERE is_featured = true - Promoted listings

**Elasticsearch Index:**
- **Full-text search:** title, description
- **Geo-spatial filtering:** Combined with radius queries
- **Faceting:** category, price ranges, status

**Retention:**
- **Soft delete** after expiration (status = 'expired')
- **Hard delete** 1 year after expiration (compliance window)

**Policies:**
- **P3:** Login required to post; Stripe payments for featured/bump
- **P6/P11:** PostGIS geographic queries with ≤250mi radius limit
- **P4:** Cloudflare R2 storage for images via StorageGateway

---

### 4.3 `marketplace_images`

**Purpose:** Listing image metadata and CDN URLs.

**Key Features:**
- Up to **12 images per listing** (enforced by MarketplaceService)
- **WebP compression** (P4) for bandwidth optimization
- `cdn_url` generated via Cloudflare R2 CDN
- `display_order` for carousel sorting

**Indexes:**
- `listing_display_idx` (listing_id, display_order) - Image carousel queries

**Retention:** **TTL: Retained while parent listing exists; purged 30 days after listing deletion**

**Policies:**
- **P4:** Cloudflare R2 storage, WebP compression via BULK queue image processing

---

### 4.4 `marketplace_messages`

**Purpose:** Email relay for buyer-seller communication without exposing real email addresses.

**Key Features:**
- **Email Masking:** `relay_email_id` format: `homepage-marketplace-{id}@villagecompute.com` (P3)
- **Threading:** `parent_message_id` for conversation history
- **IMAP Polling:** HIGH queue job (1-min interval) polls for inbound replies

**Indexes:**
- `listing_sent_idx` (listing_id, sent_at DESC) - Listing conversation view
- `from_user_idx`, `to_user_idx` - User inbox/outbox
- `relay_idx` (relay_email_id) UNIQUE - IMAP message routing

**Retention:** **TTL: 90 days after sent_at** (P3)

**Policies:**
- **P3:** Email masking via relay (privacy protection)
- **HIGH queue:** 1-min IMAP polling for inbound email parsing (per container.puml)

---

### 4.5 `payment_transactions`

**Purpose:** Stripe payment tracking for marketplace promotions.

**Transaction Types:**
- `featured_listing` - Promoted listing placement
- `bump_listing` - Re-up to top of category
- `subscription` - Future: Pro accounts

**Key Features:**
- `stripe_payment_intent_id` and `stripe_charge_id` for Stripe reconciliation
- `net_amount_usd` = `amount_usd` - `stripe_fee_usd` for revenue accounting
- `metadata` JSONB stores full Stripe webhook payload for audit

**Indexes:**
- `stripe_payment_intent_idx` (UNIQUE) - Stripe webhook idempotency
- `user_status_created_idx` (user_id, status, created_at DESC) - User payment history
- `type_created_idx` (transaction_type, created_at DESC) - Revenue reports

**Retention:** **Indefinite** for Stripe audit trails (P3)

**Policies:**
- **P3:** All Stripe transactions logged with indefinite retention for compliance

---

### 4.6 `payment_refunds`

**Purpose:** Refund tracking for Stripe disputes and cancellations.

**Key Features:**
- Links to `payment_transactions` via foreign key
- `stripe_refund_id` for Stripe reconciliation
- `refund_reason` for customer support/analytics

**Indexes:**
- `stripe_refund_idx` (UNIQUE) - Stripe webhook idempotency
- `payment_transaction_idx` - Original payment lookup

**Retention:** **Indefinite** for audit compliance (P3)

---

## 5. Good Sites Directory: Voting, Screenshots, Categories

### 5.1 `directory_categories`

**Purpose:** Hierarchical category tree for curated web directory (like classic Yahoo Directory / DMOZ).

**Key Features:**
- **Unlimited Depth:** Self-referential `parent_id` for nested categories
- **Category Moderators:** `moderator_user_id` foreign key (P7: assigned by super_admin)
- `slug` for SEO-friendly URLs (e.g., `/directory/arts/music/classical`)

**Indexes:**
- `parent_display_idx` (parent_id, display_order) - Category tree queries
- `slug_idx` (slug) UNIQUE - URL routing
- `moderator_idx` (moderator_user_id) WHERE moderator_user_id IS NOT NULL - Moderator assignment

**Policies:**
- **P7:** Category moderators for curation (assigned by super_admin role)

---

### 5.2 `directory_sites`

**Purpose:** Submitted websites with OpenGraph metadata, screenshots, and health monitoring.

**Key Features:**
- **Karma-Based Trust:** `trust_status` (untrusted|trusted) determined by `user_profiles.karma_score` (P7)
  - **Untrusted users:** Sites go to moderation queue (`status = pending`)
  - **Trusted users:** Sites auto-publish (`status = approved`)
- **OpenGraph Extraction:** `opengraph_title`, `opengraph_description`, `opengraph_image_url`
- **Link Health:** `last_health_check`, `health_status` (healthy|dead|redirect) via LOW queue (weekly)
- **Screenshot Capture:** Automatic via ScreenshotService (P4/P12)

**Indexes:**
- `url_idx` (UNIQUE) - Prevent duplicate submissions
- `status_submitted_idx` (status, submitted_at DESC) - Moderation queue
- `trust_status_idx` (trust_status) - Trust-based filtering
- `health_check_idx` (last_health_check) WHERE status = 'approved' - Link health job picker (LOW queue)

**Elasticsearch Index:**
- **Full-text search:** title, description, OpenGraph text
- **Faceting:** category, submission date

**Retention:**
- **Indefinite** for approved sites
- **90 days** for rejected sites (then hard deleted)

**Policies:**
- **P4:** Auto-captured screenshots via jvppeteer (ScreenshotService)
- **P7:** Karma-based trust determines auto-publish vs moderation queue
- **LOW queue:** Weekly link health checks (per container.puml)

---

### 5.3 `directory_site_categories`

**Purpose:** Many-to-many relationship allowing sites in multiple categories with separate votes per category.

**Key Design Decision:**
- Sites can exist in **multiple categories** (e.g., a news site in both `/news` and `/technology`)
- **Votes are per category** (tracked in `directory_votes.category_id`)
- **Rankings are per category** (tracked in `directory_rankings`)

**Indexes:**
- `site_category_idx` (site_id, category_id) UNIQUE - Prevent duplicate categorizations
- `category_site_idx` (category_id, site_id) - Category browsing

---

### 5.4 `directory_votes`

**Purpose:** Reddit-style upvote/downvote system per category.

**Key Features:**
- **Login Required:** `user_id` foreign key (P7: voting requires authentication)
- **Per-Category Voting:** `category_id` foreign key - same site can have different scores in different categories
- **Soft Delete:** `retracted_at` for vote changes (keeps historical data)

**Indexes:**
- `user_site_category_idx` (user_id, site_id, category_id) UNIQUE WHERE retracted_at IS NULL - Prevent double-voting
- `site_category_vote_idx` (site_id, category_id, vote_type) WHERE retracted_at IS NULL - Vote aggregation
- `category_voted_idx` (category_id, voted_at DESC) - Category activity feeds

**Retention:** **Indefinite** for ranking algorithm historical data

**Policies:**
- **P7:** Reddit-style voting system, login required
- **DEFAULT queue:** Hourly rank recalculation (per container.puml)

---

### 5.5 `directory_rankings`

**Purpose:** Materialized view of site rankings per category with time-decay algorithm.

**Ranking Algorithm:**
```
score = (upvotes - downvotes) / (1 + age_in_days^1.8)
```
- Higher upvote-downvote difference = higher score
- **Time decay:** Older sites decay exponentially (Reddit-style "hot" algorithm)
- `rank` = ROW_NUMBER() OVER (PARTITION BY category_id ORDER BY score DESC)

**Key Features:**
- **Top-Ranked Bubble Up:** High-scoring sites in child categories appear in parent categories
- **Hourly Recalculation:** DEFAULT queue job recalculates `score` and `rank` (per container.puml)

**Indexes:**
- `site_category_idx` (site_id, category_id) UNIQUE - One ranking per site per category
- `category_rank_idx` (category_id, rank) - **Critical for fast category browsing**
- `score_idx` (score DESC, calculated_at DESC) - Trending sites across all categories

**Policies:**
- **DEFAULT queue:** Hourly rank recalculation job
- **Top-ranked links bubble up** to parent categories per algorithm

---

### 5.6 `directory_screenshot_versions`

**Purpose:** Screenshot history with version tracking for audit trails.

**Key Features:**
- **Screenshot Service:** jvppeteer (Java Puppeteer wrapper) in same container (P12)
- **Viewport:** 1280x800 pixels (standard desktop)
- **Format:** WebP compression (P4) for bandwidth savings
- **Current Flag:** `is_current` boolean (only one current screenshot per site)
- **Version History:** All screenshots retained indefinitely (P4)

**Indexes:**
- `site_captured_idx` (site_id, captured_at DESC) - Screenshot history timeline
- `site_current_idx` (site_id) WHERE is_current = true - Current screenshot lookup (partial index)

**Retention:** **P4: Indefinite retention for all versions** (policy requirement)

**Policies:**
- **P4:** jvppeteer + Chromium in same container, indefinite retention, WebP compression
- **P12:** SCREENSHOT queue with limited concurrency (dedicated Puppeteer pool per pod)

---

## 6. Infrastructure: Jobs, Feature Flags, Rate Limits

### 6.1 `delayed_jobs`

**Purpose:** Database-backed job queue for async workloads with priority routing.

**Queue Families (per container.puml):**
- **DEFAULT (Blue):** Feed refresh (15min-daily), weather refresh (1hr), listing expiration (daily), click rollups (hourly), link health (weekly), sitemap generation (daily)
- **HIGH (Salmon):** Stock refresh (5min market hours), message relay (on-demand), inbound email parsing (1min polling)
- **LOW (Green):** Anonymous cleanup (daily), profile view aggregation (hourly), metadata refresh, rate limit log processing
- **BULK (Yellow):** AI tagging (on-demand, budget-controlled), screenshot batches, image processing, bulk Good Sites imports
- **SCREENSHOT (Lavender):** Dedicated queue with limited concurrency per P12 (jvppeteer + Chromium pool)

**Key Features:**
- **Priority Routing:** `priority` INTEGER (0 = highest, 10 = lowest)
- **Locking:** `locked_at`, `locked_by` for distributed worker coordination
- **Retry Logic:** `attempts`, `max_attempts` with exponential backoff
- **Scheduling:** `scheduled_at` for delayed execution (e.g., bump expiration in 30 days)

**Indexes:**
- `queue_scheduled_idx` (queue_name, scheduled_at, status) WHERE status = 'pending' - **Critical for job picker**
- `job_type_status_idx` (job_type, status, created_at DESC) - Job monitoring dashboards
- `locked_idx` (locked_at, locked_by) WHERE locked_at IS NOT NULL - Worker health checks
- `completed_idx` (completed_at) WHERE completed_at IS NOT NULL - Retention cleanup

**Partitioning:** Monthly partitions by `created_at`

**Retention:**
- **TTL: 30 days after completion** for succeeded jobs
- **TTL: 90 days after completion** for failed jobs (for debugging)

**Policies:**
- **P7:** Job orchestration across all queue families
- Exponential backoff: `2^attempts * base_delay` seconds

---

### 6.2 `feature_flags`

**Purpose:** Database-backed feature toggles with cohort-based rollouts.

**Initial Flags (disabled):**
- `stocks_widget` - Alpha Vantage integration
- `social_integration` - Meta Graph API integration
- `promoted_listings` - Stripe payment processing

**Key Features:**
- **Rollout Percentage:** `rollout_percentage` (0-100) for gradual rollouts
- **Cohort Whitelist:** `cohort_whitelist` JSONB array of user IDs for beta testing
- **Cohort Hashing:** FeatureFlagService uses `CRC32(user_id) % 100 < rollout_percentage` for deterministic assignment

**Indexes:**
- `flag_key_idx` (flag_key) UNIQUE - Flag lookup
- `enabled_idx` (is_enabled, rollout_percentage) - Active flag queries

**Policies:**
- **P7:** Database-backed feature flag strategy (per Blueprint Foundation)
- Admin dashboard for real-time flag adjustments (OpsAnalyticsPortal)

---

### 6.3 `rate_limit_rules`

**Purpose:** Tiered rate limiting configuration by action type and user tier.

**User Tiers:**
- `anonymous` - IP-based (strictest)
- `free` - Authenticated users (moderate)
- `paid` - Pro accounts (lenient)
- `admin` - Admin roles (unlimited)

**Action Types:**
- `api_call` - General API rate limiting
- `listing_post` - Marketplace spam prevention
- `vote` - Directory voting abuse prevention
- `search` - Search query throttling

**Key Features:**
- `requests_per_window` and `window_seconds` define rate limits
- Example: Anonymous users = 60 requests per 60 seconds for `api_call`

**Indexes:**
- `rule_key_idx` (UNIQUE) - Rule lookup
- `action_tier_idx` (action_type, user_tier) WHERE is_active = true - RateLimitService queries

**Policies:**
- **P14:** Tiered rate limiting per F14.2 (Admin dashboard for monitoring + adjustment)

---

### 6.4 `rate_limit_violations`

**Purpose:** Violation logging for IP blocking and abuse detection.

**Key Features:**
- **Anonymous Tracking:** `ip_address` for IP-based blocking
- **Authenticated Tracking:** `user_id` for account-level enforcement
- `request_count` shows how many requests triggered violation

**Indexes:**
- `user_violated_idx` (user_id, violated_at DESC) WHERE user_id IS NOT NULL - User violation history
- `ip_violated_idx` (ip_address, violated_at DESC) - IP-based blocking
- `rule_violated_idx` (rule_id, violated_at DESC) - Per-rule analytics

**Partitioning:** Daily partitions by `violated_at`

**Retention:**
- **TTL: 90 days** for raw logs
- **Aggregated** into `analytics_rollups` for long-term dashboards

**Policies:**
- **P14:** Admin dashboard for monitoring + adjustment (OpsAnalyticsPortal)
- **LOW queue:** Hourly processing for IP blocking rules

---

## 7. Analytics & Compliance

### 7.1 `link_clicks`

**Purpose:** Click event tracking for analytics with consent gating.

**Click Target Types:**
- `feed_item` - News article clicks from RSS feeds
- `directory_site` - Directory outbound clicks
- `marketplace_listing` - Marketplace listing views

**Key Features:**
- **Anonymous Tracking:** `vu_anon_id` UUID cookie (P9)
- **Authenticated Tracking:** `user_id` foreign key
- **Consent Gating:** ClickTrackingService checks `user_consents.analytics` before logging (P14)
- **Session Tracking:** `session_id` for session analytics

**Indexes:**
- `user_target_clicked_idx` (user_id, click_target_type, clicked_at DESC) WHERE user_id IS NOT NULL - User activity feeds
- `anon_target_clicked_idx` (vu_anon_id, click_target_type, clicked_at DESC) - Anonymous activity feeds
- `target_idx` (click_target_type, target_id, clicked_at DESC) - Per-item click counts

**Partitioning:** Daily partitions by `clicked_at`

**Retention:**
- **TTL: 90 days per P14** (evaluation log purge)
- **Aggregated hourly** into `analytics_rollups` for long-term storage

**Policies:**
- **P9:** Anonymous user tracking via `vu_anon_id` cookie
- **P14:** Analytics consent gating; 90-day evaluation log purge
- **DEFAULT queue:** Hourly click rollups for profile analytics

---

### 7.2 `analytics_rollups`

**Purpose:** Aggregated analytics for dashboards without raw data retention.

**Rollup Types:**
- `daily_clicks` - Daily click counts per target
- `weekly_votes` - Weekly vote aggregations per directory category
- `monthly_listings` - Monthly marketplace listing counts per category

**Target Types:**
- `user` - Per-user aggregations (profile analytics)
- `category` - Per-category aggregations (directory/marketplace)
- `site` - Per-site aggregations (directory link popularity)
- `listing` - Per-listing aggregations (marketplace analytics)

**Key Features:**
- `metric_name` and `metric_value` for flexible aggregations
- `metadata` JSONB for additional context (e.g., top referrers, browser breakdown)

**Indexes:**
- `rollup_target_period_idx` (rollup_type, target_type, target_id, period_start) - **Critical for dashboard queries**
- `period_idx` (period_start, period_end) - Time-series queries

**Retention:** **Indefinite** for aggregated analytics

**Policies:**
- **P14:** Consent-gated; feeds OpsAnalyticsPortal dashboards
- **LOW queue:** Hourly rollup jobs aggregate raw click/vote data

---

## 8. Internationalization Placeholders

### 8.1 `i18n_translations`

**Purpose:** Future multi-language support for UI strings.

**Key Features:**
- `translation_key` (e.g., `navbar.home`, `form.submit`)
- `locale` (e.g., `en-US`, `es-ES`, `fr-FR`)
- `translated_text` for localized content

**Indexes:**
- `key_locale_idx` (translation_key, locale) UNIQUE - Translation lookup
- `locale_idx` (locale) - Locale-specific queries

**Status:** **Placeholder for future expansion** (not implemented in I1)

---

### 8.2 `geo_regions`

**Purpose:** Hierarchical geographic data for marketplace filtering.

**Data Source:** dr5hn/countries-states-cities-database (153K+ cities)

**Region Types:**
- `country` - Top-level (e.g., United States, Canada)
- `state` - Mid-level (e.g., California, Texas)
- `city` - Leaf-level (e.g., San Francisco, Austin)

**Key Features:**
- Self-referential `parent_id` for hierarchy (country → state → city)
- `latitude`, `longitude` for geo-spatial queries
- `code` for ISO country/state codes (e.g., `US`, `CA`, `TX`)

**Indexes:**
- `region_type_parent_idx` (region_type, parent_id) - Hierarchy traversal
- `code_idx` (code) UNIQUE WHERE code IS NOT NULL - ISO code lookups

**Policies:**
- **P6/P11:** Supports marketplace radius filtering (user selects city → query `location_geog` within radius)

---

## 9. Cross-Cutting Concerns

### 9.1 Caching Strategy

**Hibernate 2nd Level Cache (Caffeine):**
- `users` - 10,000 entries, 1-hour TTL
- `user_profiles` - 10,000 entries, 1-hour TTL
- `marketplace_categories` - 500 entries, 24-hour TTL (rarely changes)
- `directory_categories` - 500 entries, 24-hour TTL
- `feature_flags` - 100 entries, 5-minute TTL

**Query Cache:**
- Common lookups (e.g., `User.findByEmail`, `Category.findBySlug`) cached for 5 minutes
- Invalidated on entity mutation

**External Cache (Weather, Stocks):**
- Stored in dedicated cache tables (`weather_cache`, `stock_quotes`) with TTL cleanup

---

### 9.2 Partitioning Strategy

**Daily Partitions:**
- `link_clicks` (high write volume)
- `rate_limit_violations` (high write volume)

**Monthly Partitions:**
- `feed_items` (moderate write volume, 90-day retention)
- `stock_quotes` (moderate write volume, 30-day retention)
- `ai_usage_tracking` (low write volume, indefinite retention)
- `delayed_jobs` (high write volume, 30-90 day retention)

**Partition Management:**
- **Automated creation:** LOW queue job creates next month's partitions 7 days in advance
- **Automated deletion:** LOW queue job drops expired partitions daily (per retention policy)

---

### 9.3 Index Strategy

**Primary Indexes (Every Table):**
- Primary key (B-tree) on `id` column

**Foreign Key Indexes:**
- All foreign key columns indexed for join performance

**Unique Constraints:**
- Enforced via unique indexes (e.g., `email`, `slug`, `url`)

**Composite Indexes:**
- **Covering indexes** for common queries (e.g., `user_status_created_idx` on marketplace_listings)
- **Partial indexes** for filtered queries (e.g., `WHERE status = 'active'`)

**PostGIS Indexes:**
- **GIST indexes** on `location_geog` columns for radius queries (P6/P11)

**GIN Indexes (JSONB):**
- Not used by default; added on-demand if JSONB queries become frequent

---

### 9.4 Encryption

**Encryption at Rest:**
- **Database TDE (Transparent Data Encryption):** Entire PostgreSQL cluster encrypted at rest in production
- **Sensitive Columns:** `social_tokens.access_token`, `social_tokens.refresh_token` additionally encrypted via application-level AES-256

**Encryption in Transit:**
- **PostgreSQL SSL:** Required for all JDBC connections (enforced via `sslmode=require`)
- **HTTPS:** All web traffic via TLS 1.3

---

### 9.5 Backup & Recovery

**Automated Backups:**
- **Full backup:** Daily at 2 AM UTC (retained 30 days)
- **Incremental backup:** Every 4 hours (retained 7 days)
- **WAL archiving:** Continuous (retained 7 days)

**Recovery Time Objective (RTO):** 1 hour

**Recovery Point Objective (RPO):** 4 hours (incremental backup interval)

**Disaster Recovery:**
- Cross-region replication to secondary Kubernetes cluster (async streaming replication)
- Automated failover via Patroni + etcd

---

## 10. Policy Mapping Reference

This section maps each policy (P1-P14) to the database tables and features that enforce it.

### P1: GDPR/CCPA Consent, Account Merge, Data Export/Deletion

**Tables:**
- `users` - Soft delete via `deleted_at`, `vu_anon_id` for anonymous merge
- `user_sessions` - Session tracking for consent
- `user_consents` - Consent tracking (gdpr, analytics, marketing, ai_tagging)
- `user_profiles` - User data for export/deletion
- `rss_sources` - User-created feeds in export
- `user_feed_subscriptions` - Subscription data in export
- `social_tokens` - Token data in export/deletion

**Features:**
- **Account Merge:** `AuthIdentityService` merges `vu_anon_id` data to authenticated `user_id`
- **Data Export:** `UserPreferenceService` generates GDPR export JSON (all user-related tables)
- **Right to Erasure:** `users.deleted_at` soft delete + 30-day grace period before hard delete

---

### P2/P10: AI Budget Control ($500/month Ceiling)

**Tables:**
- `ai_usage_tracking` - Token counts and cost tracking
- `feed_items` - `ai_tags` JSONB from LangChain4j

**Features:**
- **Budget Enforcement:** `AiTaggingService` queries `ai_usage_tracking` monthly total before API calls
- **Cost Tracking:** `total_cost_usd` DECIMAL(10,6) for precise accounting
- **Admin Dashboard:** OpsAnalyticsPortal shows daily/monthly spend trends

**Algorithm:**
```java
BigDecimal monthlySpend = AiUsageTracking.sumCostForMonth(YearMonth.now());
if (monthlySpend.compareTo(new BigDecimal("500.00")) >= 0) {
    throw new AiBudgetExceededException();
}
```

---

### P3: Stripe Payment/Refund Audit Trails

**Tables:**
- `marketplace_listings` - `is_featured`, `featured_until` for paid promotions
- `marketplace_messages` - Email relay for contact masking
- `payment_transactions` - Indefinite retention for audit
- `payment_refunds` - Indefinite retention for compliance

**Features:**
- **Login Required:** `marketplace_listings.user_id` NOT NULL (no anonymous postings)
- **Email Masking:** `marketplace_messages.relay_email_id` (privacy protection)
- **Stripe Webhooks:** `metadata` JSONB stores full webhook payload for audit

---

### P4: Screenshot Capture and Indefinite Retention

**Tables:**
- `directory_screenshot_versions` - Indefinite retention for all versions
- `marketplace_images` - WebP compression

**Features:**
- **jvppeteer Integration:** ScreenshotService uses jvppeteer (Java Puppeteer wrapper)
- **Chromium in Container:** Same pod deployment (no external service)
- **WebP Compression:** BULK queue image processing for bandwidth savings

**Screenshot Workflow:**
1. `DirectoryService.submitSite()` → enqueue SCREENSHOT queue job
2. `ScreenshotHandler` picks job → launches jvppeteer → captures 1280x800 PNG
3. `ImageProcessingHandler` converts PNG → WebP → uploads to Cloudflare R2
4. `directory_screenshot_versions` row created with `is_current = true`

---

### P5/P13: Social Token Storage (Indefinite)

**Tables:**
- `social_tokens` - Indefinite retention until revocation
- `social_posts` - 90-day retention

**Features:**
- **Encryption:** TDE + application-level AES-256 for `access_token`, `refresh_token`
- **Token Refresh:** HIGH queue job checks `expires_at` and refreshes via Meta Graph API

---

### P6/P11: PostGIS Geographic Queries (≤250mi Radius)

**Tables:**
- `marketplace_listings` - `location_geog` GEOGRAPHY(POINT, 4326) with GIST index
- `geo_regions` - Hierarchical city/state/country data

**Features:**
- **Radius Filtering:** 5mi, 10mi, 25mi, 50mi, 100mi, 250mi, or Any
- **PostGIS Query:**
```sql
SELECT * FROM marketplace_listings
WHERE status = 'active'
  AND ST_DWithin(
      location_geog,
      ST_MakePoint(:lng, :lat)::geography,
      :radius_meters
  )
ORDER BY published_at DESC;
```

---

### P7: Feature Flag Database-Backed Rollouts

**Tables:**
- `feature_flags` - Rollout percentage + cohort whitelist
- `directory_categories` - `moderator_user_id` for assigned moderators
- `directory_sites` - `trust_status` (karma-based)

**Features:**
- **Cohort Hashing:** `CRC32(user_id) % 100 < rollout_percentage`
- **Admin Dashboard:** OpsAnalyticsPortal for real-time flag adjustments
- **Karma Trust:** `user_profiles.karma_score` determines auto-publish vs moderation queue

---

### P8: TypeScript Build Integration

**Tables:**
- `user_profiles` - `preferences` JSONB stores gridstack layout

**Features:**
- **frontend-maven-plugin:** Downloads Node 20.10.0, runs `npm ci` + `npm run build`
- **React Islands:** Mounted via `mounts.ts` (gridstack.js, Ant Design widgets)
- **Hot Reload:** Quarkus dev mode watches `target/frontend/dist/` for changes

---

### P9: Anonymous User Tracking (vu_anon_id Cookie)

**Tables:**
- `users` - `vu_anon_id` UUID for cookie-based tracking
- `link_clicks` - `vu_anon_id` for anonymous click tracking

**Features:**
- **Cookie Lifecycle:** Set on first visit, 1-year expiration
- **Account Merge:** `AuthIdentityService` merges `vu_anon_id` data to authenticated `user_id`

---

### P12: Dedicated SCREENSHOT Queue + Puppeteer Pool

**Tables:**
- `delayed_jobs` - `queue_name = 'SCREENSHOT'`
- `directory_screenshot_versions` - Screenshot storage

**Features:**
- **Limited Concurrency:** 2 workers per pod (Chromium resource limits)
- **jvppeteer Pool:** Reused browser instances for performance
- **Timeout:** 30 seconds per screenshot (prevents worker starvation)

---

### P14: Analytics Consent + 90-Day Purge

**Tables:**
- `user_consents` - `consent_type = 'analytics'`
- `link_clicks` - 90-day TTL
- `user_sessions` - 90-day TTL after expiration
- `rate_limit_violations` - 90-day TTL

**Features:**
- **Consent Gating:** `ClickTrackingService` checks `user_consents` before logging
- **Automated Purge:** LOW queue daily job drops expired partitions

---

## 11. Performance & Scalability

### 11.1 Query Performance

**Critical Queries:**
1. **Marketplace Radius Search:**
   - Index: `location_geog_gist` (PostGIS GIST)
   - Expected QPS: 100-500 (read-heavy)
   - Target latency: <100ms (95th percentile)

2. **Directory Category Browsing:**
   - Index: `category_rank_idx` (category_id, rank)
   - Expected QPS: 50-200
   - Target latency: <50ms

3. **Feed Item Timeline:**
   - Index: `source_published_idx` (rss_source_id, published_at DESC)
   - Expected QPS: 200-1000
   - Target latency: <50ms

**Optimization Techniques:**
- **Covering indexes** to avoid table lookups
- **Partial indexes** for filtered queries
- **EXPLAIN ANALYZE** in CI pipeline (fails build if query plan changes)

---

### 11.2 Write Throughput

**High-Volume Tables:**
- `link_clicks` - 1000+ inserts/sec (partitioned daily)
- `delayed_jobs` - 100+ inserts/sec (partitioned monthly)
- `feed_items` - 50+ inserts/sec (partitioned monthly)

**Optimization Techniques:**
- **Batch inserts** for feed refresh jobs (COPY instead of INSERT)
- **Connection pooling** (HikariCP: 20-50 connections per pod)
- **Async writes** for analytics (link_clicks buffered in-memory, flushed every 5 seconds)

---

### 11.3 Database Size Projections

**Year 1 Estimates:**
- `users` - 100K rows (50 MB)
- `feed_items` - 10M rows (5 GB, partitioned monthly)
- `marketplace_listings` - 500K rows (500 MB)
- `directory_sites` - 50K rows (100 MB)
- `directory_screenshot_versions` - 200K rows (metadata only; images in R2)
- `link_clicks` - 100M rows (20 GB, partitioned daily)
- `delayed_jobs` - 50M rows (10 GB, partitioned monthly)

**Total Database Size:** ~40 GB (Year 1), ~150 GB (Year 3)

**Storage Strategy:**
- PostgreSQL 17 on SSD (AWS gp3 or equivalent)
- Elasticsearch 8 on SSD (marketplace + directory indexes: 10 GB)
- Cloudflare R2 for images/screenshots (1 TB+)

---

### 11.4 Scaling Strategy

**Vertical Scaling (Initial):**
- PostgreSQL: 4 vCPU, 16 GB RAM (handles 10K-50K users)
- Elasticsearch: 2 nodes, 4 vCPU, 8 GB RAM each

**Horizontal Scaling (Future):**
- **Read Replicas:** 2-3 replicas for read-heavy queries (feed timelines, directory browsing)
- **Sharding:** Geographic sharding for marketplace (US-West, US-East, EU, APAC)
- **Elasticsearch Scaling:** 5+ nodes for high search volume

---

## 12. Migration Strategy

### 12.1 MyBatis Migrations Setup

**Directory Structure:**
```
migrations/
├── pom.xml
├── scripts/
│   ├── 20260108000001_create_users.sql
│   ├── 20260108000002_create_user_sessions.sql
│   ├── ...
│   └── 20260108000040_create_geo_regions.sql
└── environments/
    ├── development.properties
    ├── beta.properties
    └── production.properties
```

**Migration Naming:**
- `YYYYMMDDhhmmss_description.sql`
- Timestamp ensures lexicographic ordering

**Running Migrations:**
```bash
cd migrations
mvn migration:up -Dmigration.env=development
mvn migration:status -Dmigration.env=development
```

---

### 12.2 Migration Phases (I1)

**Phase 1: Core Tables**
1. `users`, `user_sessions`, `user_consents`, `user_profiles`, `admin_roles`
2. `feature_flags`, `rate_limit_rules`, `rate_limit_violations`
3. `delayed_jobs`

**Phase 2: Feed & Content**
4. `rss_sources`, `feed_items`, `user_feed_subscriptions`
5. `ai_usage_tracking`

**Phase 3: Widgets**
6. `weather_cache`, `stock_quotes`
7. `social_tokens`, `social_posts`

**Phase 4: Marketplace**
8. `marketplace_categories`, `marketplace_listings`, `marketplace_images`
9. `marketplace_messages`
10. `payment_transactions`, `payment_refunds`

**Phase 5: Directory**
11. `directory_categories`, `directory_sites`, `directory_site_categories`
12. `directory_votes`, `directory_rankings`
13. `directory_screenshot_versions`

**Phase 6: Analytics**
14. `link_clicks`, `analytics_rollups`

**Phase 7: I18n Placeholders**
15. `i18n_translations`, `geo_regions`

---

### 12.3 Seed Data

**Required Seeds (I1):**
1. **Bootstrap Superuser:** Created via `BOOTSTRAP_ADMIN_URL` (expires after first use)
2. **Default Marketplace Categories:** For Sale, Housing, Jobs, Services, Community (with subcategories)
3. **Default Directory Categories:** Arts, Business, News, Recreation, Science, Technology
4. **System RSS Sources:** TechCrunch, BBC News, Reuters, HackerNews
5. **Default Feature Flags:** All disabled (stocks_widget, social_integration, promoted_listings)
6. **Default Rate Limit Rules:** Anonymous, free, paid, admin tiers

**Seed Script:**
```bash
cd migrations
mvn exec:java -Dexec.mainClass="villagecompute.homepage.migrations.SeedRunner" -Dmigration.env=development
```

---

## Appendix A: Glossary

- **ActiveRecord Pattern:** Hibernate Panache approach where entities have static finder methods (no separate repository classes)
- **GIST Index:** Generalized Search Tree index for PostGIS geo-spatial queries
- **JSONB:** PostgreSQL binary JSON format with indexing support
- **Partitioning:** Table sharding by date for efficient data lifecycle management
- **PostGIS:** PostgreSQL extension for geographic objects and spatial queries
- **TTL (Time To Live):** Retention policy duration before automated deletion
- **WebP:** Modern image format with superior compression (vs JPEG/PNG)

---

## Appendix B: Rendering the ERD

### Online Viewers
- [PlantUML Web Server](https://www.plantuml.com/plantuml/uml/) - Paste `erd.puml` contents
- [PlantText](https://www.planttext.com/) - Alternative online renderer

### IDE Plugins
- **VS Code:** PlantUML extension by jebbs
- **IntelliJ IDEA:** PlantUML integration plugin
- **Eclipse:** PlantUML plugin

### Command Line
```bash
# Install PlantUML (requires Java)
brew install plantuml  # macOS
apt install plantuml   # Ubuntu/Debian

# Generate PNG
plantuml docs/diagrams/erd.puml

# Generate SVG
plantuml -tsvg docs/diagrams/erd.puml

# Output: docs/diagrams/erd.png or erd.svg
```

---

## Appendix C: Review Checklist

Data architect review checklist per acceptance criteria:

- [ ] Diagram renders successfully in PlantUML viewer
- [ ] All mandated tables present (40+ core tables)
- [ ] All policy annotations (P1-P14) documented
- [ ] Partitioning strategy defined (daily/monthly partitions)
- [ ] Index strategy documented (B-tree, GIST, partial, covering)
- [ ] Retention policies linked to compliance requirements
- [ ] JSONB fields annotated with purpose
- [ ] PostGIS geo-spatial columns marked with [GEO]
- [ ] Elasticsearch indexes marked with [FTS]
- [ ] Future expansion placeholders (i18n, geo_regions) included
- [ ] Cross-references to architecture diagrams (context.puml, container.puml)
- [ ] Relationships (foreign keys) accurately reflect domain model
- [ ] Migration strategy outlined for MyBatis
- [ ] Seed data requirements documented

---

**Document Version:** 1.0
**Last Updated:** 2026-01-08
**Reviewed By:** [Data Architect Name]
**Status:** Ready for Review
