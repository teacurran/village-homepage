# Project Specifications

## Project Overview

**Project Name**: Village Homepage
**Type**: Java Quarkus SaaS web application
**Purpose**: Customizable homepage portal (similar to Yahoo/Bing Homepage) for VillageCompute

Users can view aggregated content from news, weather, stocks, and social media. Anonymous users see a default homepage. Logged-in users can fully customize their experience with drag-and-drop widgets, topic preferences, and social media integrations.

## Technology Stack

### Backend
- **Java 21** (LTS) - minimum version
- **Quarkus** framework (latest stable, currently 3.26.x)
- **Maven** build system
- **PostgreSQL 17** database
- **MyBatis Migrations** for database schema changes
- **Panache** for ORM with ActiveRecord pattern
- **LangChain4j** for AI integration (model-agnostic, initially Claude)

### Frontend
- **Qute templates** for server-rendered HTML
- **TypeScript** for all frontend JavaScript code
- **gridstack.js** for drag-and-drop widget layout
- **Ant Design ecosystem** for UI components and data visualization:
  - **@antv/g2plot** - Charts (line, bar, pie, area, etc.)
  - **@antv/s2** - Data tables, spreadsheets, pivot tables
  - **@antv/g6** - Graph/network diagrams (if needed)
  - **@antv/l7** - Geospatial maps (for marketplace location features)
  - **antd** (Ant Design) - UI components (forms, buttons, modals, selects, etc.)
- **Mini component mounts** - React components mounted into specific DOM elements in server-rendered pages (not full SPA)
- **No full SPA, No Quinoa, No Vue.js** - server-rendered with targeted React islands for interactive components

**Frontend Architecture:**
```
Server-rendered Qute HTML
    │
    ├── Static content (text, images, links)
    │
    └── Mount points for interactive components
        ├── <div id="chart-container" data-config="..."></div>
        ├── <div id="data-table" data-endpoint="..."></div>
        └── <div id="location-picker" data-props="..."></div>
```

**Component Mount Pattern:**
```typescript
// src/main/resources/META-INF/resources/assets/ts/mounts.ts
import React from 'react';
import { createRoot } from 'react-dom/client';
import { ConfigProvider } from 'antd';

// Mount a React component into a DOM element
export function mount<P>(
  Component: React.ComponentType<P>,
  elementId: string,
  getProps: (el: HTMLElement) => P
): void {
  const element = document.getElementById(elementId);
  if (!element) return;

  const props = getProps(element);
  const root = createRoot(element);
  root.render(
    <ConfigProvider theme={{ token: { colorPrimary: '#1890ff' } }}>
      <Component {...props} />
    </ConfigProvider>
  );
}

// Auto-mount all components with data-component attribute
document.querySelectorAll('[data-component]').forEach((el) => {
  const componentName = el.getAttribute('data-component');
  const props = JSON.parse(el.getAttribute('data-props') || '{}');
  // Dynamic import and mount based on componentName
});
```

**Available Ant Design Components (commonly used):**
| Category | Components |
|----------|------------|
| Forms | Input, Select, DatePicker, Checkbox, Radio, Form, Upload |
| Data Display | Table, List, Card, Descriptions, Statistic, Tag, Badge |
| Feedback | Modal, Drawer, Message, Notification, Popconfirm |
| Navigation | Menu, Breadcrumb, Pagination, Tabs, Dropdown |
| Layout | Grid (Row/Col), Space, Divider |
| Charts | Line, Bar, Pie, Area, Gauge, Heatmap (via @antv/g2plot) |
| Tables | S2 PivotSheet, S2 TableSheet (via @antv/s2) |

### External APIs
- **Alpha Vantage** - Stock market data
- **Open-Meteo** - Weather data (international)
- **National Weather Service** - Weather data (US, official)
- **Meta Graph API** - Instagram and Facebook integration

### Infrastructure
- **Container images** built with Jib (quarkus-container-image-jib)
- **Kubernetes** deployment to k3s cluster
- Infrastructure managed separately in `../villagecompute` repository

## Deployment URLs

- **Production**: homepage.villagecompute.com
- **Beta**: homepage-beta.villagecompute.com

---

## Policy Decisions

This section documents explicit policy decisions that affect architecture and implementation.

### P1: Anonymous Account Data & Compliance

**Decision:** Full Merge with Audit Trail + GDPR/CCPA Compliance (US + EU)

- When anonymous users authenticate via OAuth, ALL anonymous account data is merged into the authenticated account
- Original anonymous records are soft-deleted with **90-day retention** for audit purposes
- Merge includes: widget layout, topic preferences, marketplace drafts, directory votes
- **GDPR Consent Flow Required:**
  - Display consent modal during OAuth login explaining data merge
  - Provide clear opt-out to discard anonymous data
  - Store consent timestamp and version
- **Data Rights Support:**
  - Data export endpoint (GDPR Article 15)
  - Right to deletion endpoint (GDPR Article 17)
  - Data portability in JSON format
- **Audit Table:**
  ```
  account_merge_audit
  ├── id (UUID, PK)
  ├── anonymous_user_id (UUID)
  ├── authenticated_user_id (UUID)
  ├── merged_data_summary (JSONB)
  ├── consent_given (boolean)
  ├── consent_timestamp
  ├── ip_address
  ├── user_agent
  ├── created_at
  └── purge_after (90 days from merge)
  ```

### P2: AI Tagging Cost Management

**Decision:** Full AI Tagging with $500/month cost ceiling

- ALL feed items are tagged via Claude API (LangChain4j)
- **Cost Control Mechanisms:**
  - Batch processing: Group items into batches of 10-20 for single API calls
  - Deduplication: Skip items already tagged (by URL hash)
  - Priority queue: System feeds processed first, user feeds in off-peak hours
  - Rate limiting: Maximum API calls per hour enforced
  - Budget alerts: Email admin at 75%, 90%, 100% of monthly budget
- **Fallback when budget exhausted:**
  - Queue items for next billing cycle
  - Use cached tags from similar URLs if available
  - Display items without tags (graceful degradation)
- **Cost Tracking Table:**
  ```
  ai_usage_tracking
  ├── id (UUID, PK)
  ├── month (DATE, first of month)
  ├── provider (anthropic, openai, etc.)
  ├── total_requests
  ├── total_tokens_input
  ├── total_tokens_output
  ├── estimated_cost_cents
  ├── budget_limit_cents (50000 = $500)
  ├── updated_at
  ```

### P3: Marketplace Payment & Fraud Policy

**Decision:** Conditional Refund Window + AI-Assisted Fraud Detection

- **Refund Policy (24-hour window):**
  - Automatic refund if listing fails to publish due to technical error
  - Automatic refund if listing rejected by moderation within 24 hours
  - Manual admin review for refund requests after 24 hours
  - No refunds for user-initiated deletion or changes
- **Chargeback Handling:**
  - Document all transactions with Stripe metadata
  - Contest fraudulent chargebacks with evidence
  - Ban users with 2+ successful chargebacks
- **AI-Assisted Fraud Detection:**
  - Analyze listing content for scam patterns (too-good pricing, urgency language, suspicious contact info)
  - Flag accounts with rapid posting behavior
  - Detect copied/duplicate content across listings
  - Cross-reference known scam patterns database
  - Auto-flag for human review (not auto-reject)
- **Refund Tracking Table:**
  ```
  payment_refunds
  ├── id (UUID, PK)
  ├── stripe_payment_intent_id
  ├── stripe_refund_id
  ├── listing_id (FK)
  ├── user_id (FK)
  ├── amount_cents
  ├── reason (technical_failure, moderation_rejection, user_request, chargeback)
  ├── status (pending, approved, rejected, processed)
  ├── reviewed_by_user_id (FK, nullable)
  ├── review_notes
  ├── created_at
  └── processed_at
  ```

### P4: Screenshot Storage Policy

**Decision:** Unlimited Retention with Version History (Self-Hosted Storage)

- **Retention:** All screenshots retained indefinitely with full version history
- **Version History:** Keep previous screenshots when refreshed (enables time-travel browsing)
- **Storage Backend:** S3-compatible object storage (self-hosted MinIO or Cloudflare R2)
- **Image Optimization:**
  - WebP format with 85% quality
  - Generate thumbnail (320x200) and full (1280x800) sizes
  - Lazy-load thumbnails, full images on-demand
- **Storage Schema:**
  ```
  directory_screenshot_versions
  ├── id (UUID, PK)
  ├── site_id (FK)
  ├── version_number (INT)
  ├── storage_key_thumbnail
  ├── storage_key_full
  ├── captured_at
  ├── file_size_bytes
  ├── is_current (boolean)
  └── created_at
  ```
- **Budget Assumption:** Moderate (~$100/month) with option to self-host for cost reduction

### P5: Social Token & Post Retention Policy

**Decision:** Proactive Refresh with Fallback + Indefinite Post Retention

- **Token Refresh Strategy:**
  - Background job checks token expiry daily
  - Refresh tokens 7 days before expiration
  - Email user if refresh fails (with re-auth link)
  - If refresh fails: display cached posts with "reconnect" banner
- **Maximum Staleness:** 7 days of cached posts shown before prompting re-auth
- **Post Retention After Disconnection:**
  - Cached posts retained indefinitely unless user explicitly deletes
  - Posts marked as "archived" (not live) after disconnect
  - User can re-authenticate to resume live feed
- **Meta API Contingency:**
  - If Meta changes API terms, gracefully degrade to archived-only mode
  - Notify affected users via email
  - Feature flag to disable social integration entirely if needed

### P6: Geographic Search Configuration

**Decision:** PostGIS with Spatial Indexes + US/Canada v1 Scope

- **Technology:** PostgreSQL PostGIS extension with GIST spatial indexes
- **Geographic Scope v1:** United States + Canada only
  - Import only US and Canada cities from dr5hn dataset
  - ~40,000 cities (vs 153k global)
  - Simplified distance calculations within North America
- **Performance Targets:**
  - p95 search latency: < 100ms
  - p99 search latency: < 200ms
- **Implementation:**
  ```sql
  -- Enable PostGIS
  CREATE EXTENSION IF NOT EXISTS postgis;

  -- Add geography column to geo_cities
  ALTER TABLE geo_cities ADD COLUMN location geography(Point, 4326);
  UPDATE geo_cities SET location = ST_SetSRID(ST_MakePoint(longitude, latitude), 4326);
  CREATE INDEX idx_geo_cities_location ON geo_cities USING GIST(location);

  -- Radius query example
  SELECT * FROM marketplace_listings ml
  JOIN geo_cities gc ON ml.city_id = gc.id
  WHERE ST_DWithin(gc.location, ST_MakePoint(:lon, :lat)::geography, :radius_meters);
  ```
- **Future:** International expansion post-v1 with additional regional data

### P7: Feature Flag Strategy

**Decision:** Stable Cohorts via User ID Hash + A/B Testing & Deployment Control

- **Cohort Assignment:**
  - Use consistent hash of user ID (MD5 mod 100) for rollout percentage
  - Same user always sees same flag state at given percentage
  - Anonymous users: use session cookie hash with session-stable behavior
- **Purpose:** Support both A/B testing and deployment control
- **Analytics Integration:**
  - Log flag evaluations for analysis
  - Track conversion/engagement metrics per cohort
  - Export data for external analytics tools
- **Audit Requirements:**
  - Log all flag changes (who, when, what)
  - Retain flag evaluation logs for 90 days
- **Enhanced Feature Flag Schema:**
  ```
  feature_flags (updated)
  ├── id (UUID, PK)
  ├── key (unique)
  ├── name
  ├── description
  ├── is_enabled (boolean)
  ├── rollout_percentage (0-100)
  ├── user_whitelist (JSONB array of user IDs)
  ├── purpose (deployment, ab_test, both)
  ├── analytics_enabled (boolean)
  ├── updated_by_user_id
  ├── updated_at
  ├── created_at

  feature_flag_audit
  ├── id (UUID, PK)
  ├── flag_id (FK)
  ├── changed_by_user_id (FK)
  ├── previous_state (JSONB)
  ├── new_state (JSONB)
  ├── change_reason
  ├── created_at

  feature_flag_evaluations
  ├── id (UUID, PK)
  ├── flag_key
  ├── user_id (FK, nullable)
  ├── session_id
  ├── result (boolean)
  ├── evaluated_at
  -- Partitioned by date, retained 90 days
  ```

### P8: TypeScript Build Integration

**Decision:** Maven Frontend Plugin with optional parallel watch mode

- **Build Integration:**
  - Use `frontend-maven-plugin` to run npm/esbuild during `mvn compile`
  - Single-command builds: `./mvnw compile` builds both Java and TypeScript
  - Production builds: `./mvnw package` includes minified frontend assets
- **Developer Workflow:**
  - Option 1 (Simple): `./mvnw quarkus:dev` - rebuilds frontend on each request (slower)
  - Option 2 (Fast): Two terminals - `./mvnw quarkus:dev` + `npm run watch` for instant frontend updates
- **CI/CD:** Single `mvn package` command produces complete deployable artifact

```xml
<!-- pom.xml -->
<plugin>
    <groupId>com.github.eirslett</groupId>
    <artifactId>frontend-maven-plugin</artifactId>
    <version>1.15.0</version>
    <configuration>
        <nodeVersion>v20.10.0</nodeVersion>
        <workingDirectory>${project.basedir}</workingDirectory>
    </configuration>
    <executions>
        <execution>
            <id>install-node-and-npm</id>
            <goals><goal>install-node-and-npm</goal></goals>
        </execution>
        <execution>
            <id>npm-install</id>
            <goals><goal>npm</goal></goals>
            <configuration>
                <arguments>ci</arguments>
            </configuration>
        </execution>
        <execution>
            <id>npm-build</id>
            <goals><goal>npm</goal></goals>
            <configuration>
                <arguments>run build</arguments>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### P9: Anonymous User Data Architecture

**Decision:** Shared Users Table with `is_anonymous` flag

- **Storage Model:** Anonymous and authenticated users share the same `users` table
- **Identification:** Anonymous users identified by `is_anonymous = true` and cookie-based UUID
- **Cookie Security Requirements:**
  - `HttpOnly`: Yes (prevent XSS access)
  - `Secure`: Yes (HTTPS only in production)
  - `SameSite`: Lax (allow normal navigation)
  - `Max-Age`: 30 days (matches retention policy)
  - Cookie name: `vu_anon_id` (Village User Anonymous ID)
- **Cleanup Job:** Daily job deletes anonymous users where `last_active_at < NOW() - INTERVAL '30 days'`
- **Merge on Login:** When anonymous user authenticates via OAuth, preferences merged per Policy P1

```sql
-- Indexes for efficient anonymous user queries
CREATE INDEX idx_users_anonymous_active ON users (is_anonymous, last_active_at)
    WHERE is_anonymous = true;
```

### P10: AI Tagging Budget Enforcement

**Decision:** Tiered Throttling with gradual degradation

- **Enforcement Strategy:**
  | Budget Usage | Action |
  |--------------|--------|
  | 0-74% | Normal operation, full batch sizes (20 items) |
  | 75-89% | Reduce batch size to 10 items, email alert to admin |
  | 90-99% | Queue new items for next billing cycle (reduced priority), email alert |
  | 100% | Hard stop - no new AI jobs until next cycle, critical alert |
- **"Queue for next cycle" behavior:** Items remain in delayed job queue with `run_at` set to first day of next month
- **Budget Reset:** Monthly on billing cycle (1st of month at 00:00 UTC)
- **Admin Override:** Super admins can manually increase budget ceiling or force-process queued items

```java
@ApplicationScoped
public class AiTaggingBudgetService {

    public AiTaggingAction checkBudget() {
        AiUsageTracking usage = AiUsageTracking.findCurrentMonth();
        int percentUsed = (usage.estimatedCostCents * 100) / usage.budgetLimitCents;

        if (percentUsed >= 100) {
            return AiTaggingAction.HARD_STOP;
        } else if (percentUsed >= 90) {
            return AiTaggingAction.QUEUE_FOR_NEXT_CYCLE;
        } else if (percentUsed >= 75) {
            return AiTaggingAction.REDUCE_BATCH_SIZE;
        }
        return AiTaggingAction.NORMAL;
    }
}
```

### P11: Marketplace Location Search Optimization

**Decision:** Optimize for 10-250 mile radius, "Any" radius deferred to v2

- **v1 Scope:**
  - Supported radius options: 5mi, 10mi, 25mi, 50mi, 100mi, 250mi
  - No "Any" (nationwide) option in v1
  - Maximum search radius: 250 miles
- **Performance Targets:**
  - p95 latency: < 100ms for radius ≤ 100mi
  - p95 latency: < 200ms for radius 100-250mi
  - p99 latency: < 300ms for all queries
- **Index Strategy:**
  - PostGIS GIST index on `geo_cities.location` sufficient for this scope
  - No Elasticsearch geo-spatial needed for v1
- **v2 Roadmap:** "Any" radius will require either:
  - Pre-computed regional clusters, or
  - Elasticsearch geo-spatial integration

### P12: Screenshot Capture Architecture

**Decision:** Delayed job queue with browser pool, configurable per-pod concurrency

- **Job Queue Integration:**
  - Screenshot capture uses dedicated delayed job queue: `SCREENSHOT`
  - App pods can be configured to process specific queues via environment variable
  - Default: all pods process all queues including screenshots
- **Browser Pool Configuration:**
  - Each pod maintains a pool of Puppeteer browser instances
  - Default pool size: 3 browsers (configurable via `SCREENSHOT_POOL_SIZE`)
  - Max concurrent screenshots per pod: 3 (one per browser)
  - Browser instances are long-lived, reused across jobs
- **Resource Requirements:**
  - Minimum pod memory: 4GB (Quarkus + 3 Chrome instances)
  - Recommended: 6GB for pods processing screenshot queue
- **SLA:** No strict SLA - background processing with completion notification
- **Bulk Import:** Admin notified via email when bulk import screenshot batch completes

```properties
# application.properties
screenshot.pool.size=${SCREENSHOT_POOL_SIZE:3}
screenshot.timeout.seconds=30
screenshot.queue.name=SCREENSHOT

# Delayed job queue configuration
delayed-job.queues.enabled=${DELAYED_JOB_QUEUES:DEFAULT,HIGH,LOW,BULK,SCREENSHOT}
```

```java
@ApplicationScoped
public class ScreenshotService {

    @ConfigProperty(name = "screenshot.pool.size", defaultValue = "3")
    int poolSize;

    private final Semaphore browserSemaphore;
    private final List<Browser> browserPool;

    @PostConstruct
    void init() {
        browserSemaphore = new Semaphore(poolSize);
        browserPool = new CopyOnWriteArrayList<>();
        for (int i = 0; i < poolSize; i++) {
            browserPool.add(launchBrowser());
        }
    }

    public CompletableFuture<byte[]> captureAsync(String url) {
        return CompletableFuture.supplyAsync(() -> {
            browserSemaphore.acquire();
            try {
                Browser browser = getAvailableBrowser();
                return captureWithBrowser(browser, url);
            } finally {
                browserSemaphore.release();
            }
        });
    }
}
```

### P13: Social Media Token Failure UX

**Decision:** Graceful Archive Mode with banner notification

- **Token Failure Behavior:**
  - Social widget remains visible indefinitely showing cached posts
  - Prominent banner: "Showing posts from [X days ago]. [Reconnect your account]"
  - Posts displayed normally (no grayscale or visual degradation)
  - Banner color changes based on staleness:
    - 1-3 days: Info (blue)
    - 4-7 days: Warning (yellow)
    - 7+ days: Alert (orange)
- **Post Retention:** Cached posts retained indefinitely regardless of token status
- **User Actions:**
  - "Reconnect" button triggers OAuth re-authentication
  - "Hide widget" option available (removes from layout, preserves data)
  - "Delete social data" in settings (permanent removal)
- **Meta API Contingency:** If Meta revokes API access entirely:
  - Feature flag `social_integration` disabled
  - All social widgets show "Feature temporarily unavailable" message
  - Cached data preserved for potential future restoration

```java
public record SocialWidgetStateType(
    boolean isConnected,
    boolean isStale,
    int staleDays,
    String bannerMessage,
    String bannerLevel,  // info, warning, alert
    Instant lastSuccessfulRefresh,
    List<SocialPostType> cachedPosts
) {}
```

### P14: Feature Flag Analytics Privacy

**Decision:** Dual logging with GDPR-aware user ID handling, immediate purge on deletion

- **Logging Strategy:**
  - Authenticated users with analytics consent: log `user_id`
  - Authenticated users without consent: log `session_hash` only
  - Anonymous users: log `session_hash` only
  - GDPR jurisdiction users: respect `analytics_consent` preference
- **Consent Model:**
  - `analytics_consent` field in user preferences (default: true for US, false for EU)
  - Consent banner shown to EU users on first visit
  - Users can change preference in settings
- **Account Deletion (Right to Erasure):**
  - Immediate purge of all `feature_flag_evaluations` records for user
  - Synchronous deletion during account deletion transaction
  - No anonymization - full removal
- **Retention:** 90 days for all evaluation logs (user ID and session-only)

```sql
-- Updated feature_flag_evaluations schema
feature_flag_evaluations
├── id (UUID, PK)
├── flag_key
├── user_id (FK, nullable)         -- Only populated if consent given
├── session_hash VARCHAR(64)       -- Always populated
├── is_authenticated (boolean)     -- Whether user was logged in
├── result (boolean)
├── evaluated_at
-- Partitioned by date, retained 90 days

-- Index for deletion
CREATE INDEX idx_ff_evaluations_user ON feature_flag_evaluations (user_id)
    WHERE user_id IS NOT NULL;
```

```java
@Transactional
public void deleteUserAccount(UUID userId) {
    // ... other deletion logic ...

    // Immediate purge of feature flag evaluations (GDPR compliance)
    entityManager.createQuery(
        "DELETE FROM FeatureFlagEvaluation e WHERE e.userId = :userId")
        .setParameter("userId", userId)
        .executeUpdate();

    // ... complete account deletion ...
}
```

---

## Architecture Constraints

### Database Access Pattern

All database access MUST be via **static methods on model entities** using the Panache ActiveRecord pattern. Do NOT create separate repository classes.

```java
@Entity
public class User extends PanacheEntityBase {

    public static final String QUERY_FIND_BY_EMAIL = "User.findByEmail";

    @NamedQuery(name = QUERY_FIND_BY_EMAIL,
                query = "SELECT u FROM User u WHERE u.email = :email")

    public static Optional<User> findByEmail(String email) {
        return find("#" + QUERY_FIND_BY_EMAIL,
                    Parameters.with("email", email))
               .firstResultOptional();
    }
}
```

### Background Job Processing

Use the **Delayed Job pattern** for all asynchronous operations. Reference: `../village-calendar/src/main/java/villagecompute/calendar/services/DelayedJobService.java`

### REST API Design

- Traditional REST endpoints (not GraphQL)
- OpenAPI spec-first design when applicable
- Follow Quarkus REST best practices

### Code Standards

Follow VillageCompute Java Project Standards (`../village-storefront/docs/java-project-standards.adoc`)

### Package Structure

```
src/main/java/villagecompute/homepage/
├── api/
│   ├── rest/              # REST resources
│   └── types/             # API DTOs (Type suffix)
├── config/                # Configuration classes
├── data/
│   └── models/            # JPA entities with static finder methods
├── exceptions/            # Custom exceptions
├── integration/
│   ├── weather/           # Open-Meteo, NWS clients
│   ├── stocks/            # Alpha Vantage client
│   ├── social/            # Meta API client
│   └── ai/                # LangChain4j AI services
├── jobs/                  # Delayed job handlers
│   ├── FeedRefreshJobHandler.java
│   ├── WeatherRefreshJobHandler.java
│   ├── StockRefreshJobHandler.java
│   └── AiTaggingJobHandler.java
├── services/              # Business logic
└── util/                  # Utilities
```

---

## Features

### F1: Authentication & Authorization

Copy the permission model from `../village-storefront`.

#### F1.1: Bootstrap URL
- `/bootstrap` endpoint for creating the first superuser when no admins exist
- Once first admin is created, bootstrap endpoint returns 403

#### F1.2: User Authentication
- **OAuth Providers**: Google, Facebook, Apple
- **OIDC integration** via Quarkus OIDC extension
- JWT-based session tokens (no server-side sessions)

#### F1.3: Anonymous Accounts
- Create server-side anonymous user on first visit
- Store user ID in secure cookie
- Anonymous users can customize their homepage
- When user logs in via OAuth, merge anonymous account preferences into authenticated account (see **Policy P1**)
- Delete orphaned anonymous accounts after 30 days of inactivity
- **GDPR/CCPA compliance required** - consent flow during OAuth merge, data export/deletion endpoints

#### F1.4: Admin Roles (from village-storefront)
```java
public static final String ROLE_SUPER_ADMIN = "super_admin";
public static final String ROLE_SUPPORT = "support";
public static final String ROLE_OPS = "ops";
public static final String ROLE_READ_ONLY = "read_only";
```

#### F1.5: Permissions
```java
public static final String PERMISSION_MANAGE_USERS = "manage_users";
public static final String PERMISSION_MANAGE_FEEDS = "manage_feeds";
public static final String PERMISSION_VIEW_ANALYTICS = "view_analytics";
public static final String PERMISSION_MANAGE_SYSTEM = "manage_system";
```

---

### F2: Homepage Layout System

#### F2.1: Widget Grid
- Use **gridstack.js** library for drag-and-drop widget layout
- Responsive grid: 12 columns on desktop, 6 on tablet, 2 on mobile
- Widgets can span multiple columns/rows
- Widget positions persisted per-user in database (JSONB)

#### F2.2: Widget Types
| Widget Type | Default Size | Description |
|-------------|--------------|-------------|
| `news_feed` | 6x4 | Aggregated news from RSS feeds |
| `weather` | 3x2 | Current weather and forecast |
| `stocks` | 3x3 | Stock watchlist with quotes |
| `social_feed` | 4x4 | Instagram/Facebook posts |
| `rss_feed` | 4x3 | Custom RSS feed |
| `quick_links` | 2x2 | User's bookmarked links |
| `search_bar` | 12x1 | Search widget (top of page) |

#### F2.3: Default Layout (Anonymous/New Users)
```
Row 1: [Search Bar (12 cols)]
Row 2: [News Feed (6 cols)] [Weather (3 cols)] [Stocks (3 cols)]
Row 3: [News Feed continued] [Quick Links (3 cols)]
```

#### F2.4: Layout Persistence
- Store layout as JSONB in `user_preferences.layout`
- Schema: `{ widgets: [{ type, x, y, width, height, config }] }`

---

### F3: News Aggregation

#### F3.1: System RSS Feeds
- Admin-managed list of RSS feed URLs
- Categories: World, US, Business, Technology, Science, Health, Sports, Entertainment
- Configurable refresh rate per feed (default: 15 minutes)

#### F3.2: User Custom RSS Feeds
- Users can add custom RSS URLs
- Backend fetches and parses feeds (do not expose RSS URLs to frontend)
- Limit: 20 custom feeds per user
- Validate RSS URL format and accessibility before saving

#### F3.3: Feed Refresh Jobs
- Delayed job per feed source
- Configurable refresh intervals:
  - Breaking news feeds: 5 minutes
  - Standard news: 15 minutes
  - Blogs/personal: 1 hour
- Store fetched items in `feed_items` table with deduplication by URL

#### F3.4: AI Tagging
- After fetching feed items, queue AI tagging job
- Use **LangChain4j** with configurable AI provider (Claude initially)
- Extract: topics, entities, sentiment, categories
- Store tags in `feed_items.ai_tags` (JSONB)
- **Cost Management:** $500/month budget with batch processing and deduplication (see **Policy P2**)

```java
public record AiTagsType(
    List<String> topics,      // e.g., ["politics", "economy"]
    List<String> entities,    // e.g., ["Joe Biden", "Federal Reserve"]
    String sentiment,         // "positive", "neutral", "negative"
    List<String> categories   // mapped to system categories
) {}
```

#### F3.5: User Interest Matching
- Users select topics of interest from predefined list
- Users can follow specific publications (by RSS source)
- Homepage news widget shows items matching user interests first
- Fallback to general news for non-matching slots

---

### F4: Weather Widget

#### F4.1: Weather Data Sources
- **Open-Meteo**: International coverage, free API
- **National Weather Service**: US locations, official data
- Use NWS for US locations, Open-Meteo for international

#### F4.2: User Location
- Ask for location permission on first visit
- Allow manual city/ZIP entry
- Store preferred location in user preferences
- Support multiple saved locations

#### F4.3: Weather Display
- Current conditions: temp, feels like, humidity, wind
- 7-day forecast
- Hourly forecast (next 24 hours)
- Weather alerts (NWS only)

#### F4.4: Refresh Rate
- Weather data: 1 hour refresh interval
- Severe weather alerts: 15 minute refresh

---

### F5: Stock Market Widget

#### F5.1: Alpha Vantage Integration
- REST API for stock quotes
- Support: stocks, ETFs, indices
- Rate limit handling (25 requests/day free tier)

#### F5.2: User Watchlist
- Users create personal stock watchlist
- Default watchlist: S&P 500 (^GSPC), DJIA (^DJI), NASDAQ (^IXIC)
- Limit: 20 symbols per user

#### F5.3: Stock Display
- Symbol, company name
- Current price, change, percent change
- Mini sparkline chart (last 5 days)
- Market status (open/closed)

#### F5.4: Refresh Rate
- During market hours: 5 minutes
- After hours: 1 hour
- Weekends: 6 hours

---

### F6: Social Media Integration

#### F6.1: Meta (Instagram + Facebook)
- OAuth integration with Meta Graph API
- Required scopes: `instagram_basic`, `pages_read_engagement`
- Fetch recent posts from user's Instagram feed
- Fetch posts from followed Facebook pages

#### F6.2: Social Feed Widget
- Display Instagram photos with captions
- Display Facebook posts
- Chronological or engagement-based ordering
- Link to original post

#### F6.3: OAuth Token Management
- Store encrypted access tokens in database
- Handle token refresh automatically (proactive refresh 7 days before expiry - see **Policy P5**)
- Graceful degradation if token expires: show cached posts with "reconnect" banner
- Posts retained indefinitely after account disconnection

#### F6.4: Refresh Rate
- Social feeds: 30 minutes

---

### F7: User Preferences

#### F7.1: Preference Categories
```java
public record UserPreferencesType(
    LayoutType layout,                    // Widget positions
    List<String> newsTopics,              // Selected interest topics
    List<UUID> followedPublications,      // RSS sources to prioritize
    List<String> stockWatchlist,          // Stock symbols
    List<LocationType> weatherLocations,  // Saved locations
    ThemeType theme,                      // Light/dark/system
    Map<String, Object> widgetConfigs     // Per-widget settings
) {}
```

#### F7.2: Storage
- Store as JSONB in `users.preferences` column
- Versioned schema for migrations

---

### F8: Admin Features

#### F8.1: Feed Management
- CRUD for system RSS feeds
- Set category, refresh interval, priority
- Enable/disable feeds
- View feed health (last fetch, error count)

#### F8.2: User Management
- View user list with search/filter
- View user activity (last login, preferences)
- Suspend/delete users
- Impersonate users (for support)

#### F8.3: Analytics Dashboard
- Active users (DAU, WAU, MAU)
- Popular topics and feeds
- Widget usage statistics
- API usage and rate limit status

#### F8.4: System Health
- Background job queue status
- External API health checks
- Error logs and alerts

---

### F9: Background Jobs

#### F9.1: Job Types
| Job Handler | Queue | Refresh Interval |
|-------------|-------|------------------|
| `RssFeedRefreshJobHandler` | DEFAULT | Per-feed (5-60 min) |
| `WeatherRefreshJobHandler` | DEFAULT | 1 hour |
| `StockRefreshJobHandler` | HIGH | 5 min (market hours) |
| `SocialFeedRefreshJobHandler` | LOW | 30 minutes |
| `AiTaggingJobHandler` | BULK | On-demand |
| `AnonymousUserCleanupJobHandler` | LOW | Daily |

#### F9.2: Job Scheduling
- Use Quarkus `@Scheduled` for recurring jobs
- Delayed job table for retryable async work
- Exponential backoff for failed jobs

---

### F10: Data Models

#### F10.1: Core Entities
```
users
├── id (UUID, PK)
├── email (nullable for anonymous)
├── oauth_provider (google, facebook, apple, null)
├── oauth_id
├── display_name
├── avatar_url
├── preferences (JSONB)
├── is_anonymous (boolean)
├── last_active_at
├── created_at
└── updated_at

admin_roles
├── id (UUID, PK)
├── email
├── role (super_admin, support, ops, read_only)
├── permissions (JSONB array)
├── status (active, suspended)
├── created_at
└── updated_at

rss_sources
├── id (UUID, PK)
├── name
├── url
├── category
├── is_system (boolean)
├── user_id (FK, nullable - null for system feeds)
├── refresh_interval_minutes
├── last_fetched_at
├── error_count
├── status (active, paused, error)
├── created_at
└── updated_at

feed_items
├── id (UUID, PK)
├── source_id (FK -> rss_sources)
├── title
├── url (unique per source)
├── description
├── image_url
├── published_at
├── ai_tags (JSONB)
├── fetched_at
└── created_at

user_feed_subscriptions
├── id (UUID, PK)
├── user_id (FK)
├── source_id (FK -> rss_sources)
├── created_at

stock_quotes
├── id (UUID, PK)
├── symbol
├── company_name
├── price
├── change
├── change_percent
├── market_cap
├── updated_at

weather_cache
├── id (UUID, PK)
├── location_key (lat,lon or zip)
├── provider (open_meteo, nws)
├── current_data (JSONB)
├── forecast_data (JSONB)
├── alerts (JSONB)
├── fetched_at
└── expires_at

social_tokens
├── id (UUID, PK)
├── user_id (FK)
├── provider (instagram, facebook)
├── access_token (encrypted)
├── refresh_token (encrypted)
├── expires_at
├── scopes
├── created_at
└── updated_at

social_posts
├── id (UUID, PK)
├── user_id (FK)
├── provider
├── external_id
├── content
├── media_url
├── posted_at
├── fetched_at

delayed_jobs
├── id (UUID, PK)
├── queue
├── handler_class
├── payload (JSONB)
├── run_at
├── locked_at
├── locked_by
├── attempts
├── last_error
├── failed_at
├── created_at
└── updated_at
```

---

### F11: Public Profile Pages

Users can create a public profile page at `/u/username` to showcase curated content and present a customizable public-facing homepage.

#### F11.1: Profile Basics

- **URL:** `/u/{username}`
- Username requirements:
  - 3-30 characters
  - Alphanumeric + underscores only
  - Case-insensitive (stored lowercase)
  - Reserved words blocked (admin, support, help, api, etc.)
- **Required Fields:**
  - Username (unique, required)
  - Display name (optional, falls back to username)
  - Bio (optional, max 500 chars, plain text)
- **Optional Fields:**
  - Avatar (upload or URL)
  - Location (text, e.g., "Boston, MA")
  - Website URL
  - Social links (Twitter, LinkedIn, etc.)

```
user_profiles
├── id (UUID, PK)
├── user_id (FK, unique)
├── username (unique, lowercase)
├── display_name
├── bio
├── avatar_url
├── location_text
├── website_url
├── social_links (JSONB)
├── template (public_homepage, your_times, your_report)
├── template_config (JSONB)
├── is_published (boolean)
├── view_count
├── created_at
└── updated_at
```

#### F11.2: Profile Page Templates

Users choose a template that controls layout and presentation style. Each template has different content curation capabilities.

| Template | Style | Content Model |
|----------|-------|---------------|
| `public_homepage` | Widget grid (like private homepage) | Widgets + text/headline blocks |
| `your_times` | NYT newspaper layout | Curated article slots |
| `your_report` | Drudge Report style | Text links + headline photo |

#### F11.3: Template - Public Homepage

A public version of the user's private homepage widget grid, customized for external audiences.

- **Same widget system** as private homepage (gridstack.js)
- **Additional block types for public pages:**
  - `headline_block`: Large text headline (customizable font size, color)
  - `text_block`: Rich text content block (markdown supported)
  - `image_block`: Featured image with caption
  - `embed_block`: YouTube, Twitter, or other embeds
- Users can choose which widgets to show publicly vs. keep private
- Layout persisted separately from private homepage

```java
public record PublicHomepageConfigType(
    List<PublicWidgetType> widgets,
    String headerText,           // Optional page header
    String headerStyle,          // minimal, bold, banner
    String backgroundColor,      // Hex color or preset
    String accentColor
) {}

public record PublicWidgetType(
    String type,                 // news_feed, weather, headline_block, etc.
    int x, int y, int width, int height,
    Map<String, Object> config   // Widget-specific settings
) {}
```

#### F11.4: Template - Your Times

A newspaper-style layout inspired by The New York Times. Users curate specific articles from their subscribed feeds into designated layout slots.

**Layout Structure:**
```
┌─────────────────────────────────────────────────┐
│                  MASTHEAD / TITLE               │
├─────────────────────────────────────────────────┤
│  HEADLINE STORY      │  SECONDARY  │  SIDEBAR  │
│  (large photo +      │  STORIES    │  STORIES  │
│   headline + blurb)  │  (2-3 slots)│  (4-6)    │
├──────────────────────┴─────────────┴───────────┤
│              SECTION DIVIDER                    │
├─────────────────────────────────────────────────┤
│  MORE STORIES (grid of 4-8 article cards)       │
└─────────────────────────────────────────────────┘
```

**Article Curation:**
- User browses their subscribed feeds
- Selects articles to place in specific slots
- Can customize for each placed article:
  - **Headline text** (override original)
  - **Blurb/description** (override original)
  - **Image** (override or remove original)
  - **Link preserved** (always links to original source)
- Only shows blurb with link - no full article content (respects copyright)

**Slot Types:**
| Slot | Image | Headline | Blurb | Typical Position |
|------|-------|----------|-------|------------------|
| `headline` | Large (16:9) | Large | Full | Top center |
| `secondary` | Medium (4:3) | Medium | Truncated | Top right |
| `sidebar` | Thumbnail | Small | None | Right column |
| `grid` | Medium (4:3) | Medium | Truncated | Bottom section |

```java
public record YourTimesConfigType(
    String masthead,              // e.g., "Jane's Daily Digest"
    String tagline,               // Optional subtitle
    List<YourTimesSlotType> slots,
    String colorScheme,           // classic, modern, dark
    LocalDate lastUpdated
) {}

public record YourTimesSlotType(
    String slotId,                // headline, secondary_1, sidebar_3, etc.
    String slotType,              // headline, secondary, sidebar, grid
    UUID feedItemId,              // Original article from feed_items
    String customHeadline,        // User's override (nullable)
    String customBlurb,           // User's override (nullable)
    String customImageUrl,        // User's override (nullable)
    String originalUrl,           // Preserved link to source
    Instant placedAt
) {}
```

**Article Picker UI:**
- Modal showing user's subscribed feeds
- Filter by feed source, date, topic
- Search within feeds
- Preview article before placing
- Drag-and-drop to slots

#### F11.5: Template - Your Report

A link-aggregation style inspired by Drudge Report. Primarily text links organized in columns with occasional headline photo.

**Layout Structure:**
```
┌─────────────────────────────────────────────────┐
│              MAIN HEADER (customizable)         │
├────────────────┬────────────────┬───────────────┤
│   COLUMN 1     │   COLUMN 2     │   COLUMN 3    │
│                │                │               │
│ • Link         │ ┌────────────┐ │ • Link        │
│ • Link         │ │  HEADLINE  │ │ • Link        │
│ • Link         │ │   PHOTO    │ │ • Link        │
│                │ │  + caption │ │               │
│ [SECTION]      │ └────────────┘ │ [SECTION]     │
│ • Link         │ • Link         │ • Link        │
│ • Link         │ • Link         │ • Link        │
└────────────────┴────────────────┴───────────────┘
```

**Content Elements:**
- **Main Header:** Large customizable text (site title)
- **Section Headers:** Dividers within columns (e.g., "WORLD", "TECH")
- **Text Links:** Headline text linking to original article
  - Can customize link text (headline)
  - Preserves original URL
  - Optional: ALL CAPS styling, red/bold highlights
- **Headline Photo:** One featured image slot
  - Large image with caption
  - Links to featured story
- **Columns:** 3-column layout (configurable to 2 on mobile)

```java
public record YourReportConfigType(
    String mainHeader,            // e.g., "SMITH REPORT"
    String headerStyle,           // classic (red/black), modern, minimal
    List<YourReportColumnType> columns,
    YourReportHeadlinePhotoType headlinePhoto,  // nullable
    String backgroundColor,
    String linkColor
) {}

public record YourReportColumnType(
    int columnIndex,              // 0, 1, 2
    List<YourReportItemType> items
) {}

public record YourReportItemType(
    String type,                  // link, section_header
    String text,                  // Link text or section name
    String url,                   // nullable for section headers
    UUID feedItemId,              // Source article (nullable for manual links)
    String style                  // normal, caps, bold, highlight
) {}

public record YourReportHeadlinePhotoType(
    String imageUrl,
    String caption,
    String linkUrl,
    UUID feedItemId               // Source article (nullable)
) {}
```

#### F11.6: Curated Article Management

For Your Times and Your Report templates, users curate articles from their feeds.

```
profile_curated_articles
├── id (UUID, PK)
├── profile_id (FK -> user_profiles)
├── feed_item_id (FK -> feed_items, nullable for manual)
├── original_url
├── original_title
├── original_description
├── original_image_url
├── custom_headline
├── custom_blurb
├── custom_image_url
├── slot_assignment (JSONB: {template, slotId})
├── is_active (boolean)
├── created_at
└── updated_at
```

**Manual Link Entry:**
- Users can add links not from their feeds
- Enter URL → system fetches metadata (title, description, image)
- User customizes as needed

#### F11.7: Profile Page URLs & Routing

```
/u/{username}                    → Public profile page
/u/{username}/edit               → Edit profile (owner only)
/u/{username}/curate             → Article curation UI (owner only)
/settings/profile                → Profile settings (username, bio, etc.)
```

#### F11.8: Profile Visibility & Privacy

- **Published/Draft:** Users can save changes without publishing
- **Profile discovery:** Published profiles appear in search and directory
- **View counter:** Track profile page views (not logged-in visitors)
- **No comments/reactions:** Profiles are read-only for visitors (v1)

#### F11.9: Profile SEO

- Meta tags with username, bio, avatar
- Open Graph for social sharing
- JSON-LD Person schema
- Canonical URL: `https://homepage.villagecompute.com/u/{username}`

```html
<title>{displayName} | Village Homepage</title>
<meta name="description" content="{bio}">
<meta property="og:type" content="profile">
<meta property="og:title" content="{displayName}">
<meta property="og:description" content="{bio}">
<meta property="og:image" content="{avatarUrl}">
<meta property="profile:username" content="{username}">
```

#### F11.10: Profile Data Models Summary

```
user_profiles
├── id, user_id, username, display_name, bio
├── avatar_url, location_text, website_url, social_links
├── template, template_config, is_published
├── view_count, created_at, updated_at

profile_curated_articles
├── id, profile_id, feed_item_id
├── original_url, original_title, original_description, original_image_url
├── custom_headline, custom_blurb, custom_image_url
├── slot_assignment, is_active, created_at, updated_at

reserved_usernames
├── id (UUID, PK)
├── username (unique)
├── reason (system, trademark, offensive)
├── created_at
```

#### F11.11: Background Jobs for Profiles

| Job Handler | Queue | Trigger |
|-------------|-------|---------|
| `ProfileMetadataRefreshJobHandler` | LOW | When curated article source updates |
| `ProfileViewCountAggregatorJobHandler` | LOW | Hourly (batch view count updates) |

---

### F12: Marketplace (Classifieds)

A Craigslist-style classifieds marketplace with location-based filtering. Users must be logged in to post ads. UI uses text links like Craigslist but with a more modern appearance.

#### F12.1: Geographic Data

Import location data from **dr5hn/countries-states-cities-database**:
- Source: https://github.com/dr5hn/countries-states-cities-database
- Use PostgreSQL export format
- **v1 Scope: US + Canada only** (~40,000 cities) - see **Policy P6**
- Each city includes latitude, longitude, timezone
- **PostGIS extension required** for spatial indexing and radius queries

```
geo_countries
├── id (INT, PK)
├── name
├── iso2
├── iso3
├── phone_code
├── capital
├── currency
├── native_name
├── region
├── subregion
├── latitude
├── longitude
└── emoji

geo_states
├── id (INT, PK)
├── country_id (FK)
├── name
├── state_code
├── latitude
└── longitude

geo_cities
├── id (INT, PK)
├── state_id (FK)
├── country_id (FK)
├── name
├── latitude
├── longitude
└── timezone
```

#### F12.2: Location-Based Filtering

- Detect user location via browser geolocation API (with permission)
- Allow manual city/ZIP entry as fallback
- Display location context: "Boston Area", "Greater New York", etc.
- Radius filter options: 5mi, 10mi, 25mi, 50mi, 100mi, 250mi, Any
- **Use PostGIS with GIST spatial indexes** for sub-100ms radius queries (see **Policy P6**)
- Performance targets: p95 < 100ms, p99 < 200ms

```java
public record MarketplaceSearchType(
    Double latitude,
    Double longitude,
    Integer radiusMiles,
    UUID categoryId,
    String query,
    BigDecimal minPrice,
    BigDecimal maxPrice,
    Boolean hasImages,
    SortOrder sortBy  // NEWEST, PRICE_LOW, PRICE_HIGH, DISTANCE
) {}
```

#### F12.3: Category Hierarchy (Craigslist-style)

```
FOR SALE
├── Antiques
├── Appliances
├── Arts & Crafts
├── Auto Parts
├── Baby & Kids
├── Beauty & Health
├── Bicycles
├── Boats
├── Books & Magazines
├── Business Equipment
├── Cars & Trucks
├── Cell Phones
├── Clothing & Accessories
├── Collectibles
├── Computer Parts
├── Computers
├── Electronics
├── Farm & Garden
├── Free Stuff
├── Furniture
├── Garage & Moving Sales
├── General For Sale
├── Heavy Equipment
├── Household Items
├── Jewelry
├── Materials
├── Motorcycles
├── Musical Instruments
├── Photo & Video
├── RVs & Campers
├── Sporting Goods
├── Tickets
├── Tools
├── Toys & Games
├── Trailers
├── Video Gaming
└── Wanted

HOUSING
├── Apartments / Housing For Rent
├── Rooms & Shares
├── Sublets & Temporary
├── Housing Wanted
├── Real Estate For Sale
├── Parking & Storage
├── Office & Commercial
└── Vacation Rentals

JOBS
├── Accounting / Finance
├── Admin / Office
├── Architect / Engineer
├── Art / Media / Design
├── Biotech / Science
├── Business / Management
├── Customer Service
├── Education / Teaching
├── Food / Beverage / Hospitality
├── General Labor
├── Government
├── Healthcare
├── Human Resources
├── Legal / Paralegal
├── Manufacturing
├── Marketing / PR / Advertising
├── Nonprofit
├── Real Estate
├── Retail / Wholesale
├── Sales
├── Salon / Spa / Fitness
├── Security
├── Skilled Trades
├── Software / QA / DBA
├── Systems / Networking
├── Technical Support
├── Transportation
├── TV / Film / Video / Radio
├── Web / HTML / Info Design
├── Writing / Editing
└── Other

SERVICES
├── Automotive
├── Beauty
├── Cell Phone / Mobile
├── Computer
├── Creative
├── Cycle
├── Event
├── Farm & Garden
├── Financial
├── Health / Wellness
├── Household
├── Labor / Hauling / Moving
├── Legal
├── Lessons & Tutoring
├── Marine
├── Pet
├── Real Estate
├── Skilled Trade
├── Small Business
├── Travel / Vacation
└── Write / Edit / Translate

COMMUNITY
├── Activities
├── Artists
├── Childcare
├── Classes
├── Events
├── General Community
├── Groups
├── Local News
├── Lost & Found
├── Missed Connections
├── Musicians
├── Pets
├── Politics
├── Rideshare
└── Volunteers
```

#### F12.4: Listing Creation (Authenticated Users Only)

- Require login to create listings (not anonymous accounts)
- Listing fields:
  - Title (required, max 100 chars)
  - Category (required, from hierarchy)
  - Description (required, max 5000 chars, plain text)
  - Price (optional, supports "Free" and "Contact for price")
  - Images (up to 12, max 10MB each, resize to 1200px max)
  - Location (auto-detect or manual, required)
  - Contact method: email (platform-masked), phone (optional)
- Listing moderation status: pending, active, flagged, removed, expired

```java
public record CreateListingType(
    String title,
    UUID categoryId,
    String description,
    BigDecimal price,
    Boolean isFree,
    Boolean contactForPrice,
    List<MultipartFile> images,
    Double latitude,
    Double longitude,
    UUID cityId,
    String contactEmail,    // Will be masked
    String contactPhone     // Optional
) {}
```

#### F12.5: Listing Images

- Store images in object storage (S3/MinIO)
- On upload: validate, resize, generate thumbnails
- Image sizes:
  - Thumbnail: 150x150 (cropped square)
  - List view: 300x225
  - Detail view: 1200x900 (max, maintain aspect)
- Serve via CDN with signed URLs
- Delete images when listing expires/removed

#### F12.6: Contact System (No Payments)

- **Email masking**: Hide real email addresses
  - Generate masked address: `listing-abc123@reply.homepage.villagecompute.com`
  - Relay messages through platform
  - Track message counts for spam detection
- **Phone display**: Optional, shown directly if provided
- **No in-app messaging MVP**: Email relay only

#### F12.7: Listing Lifecycle

| Status | Description |
|--------|-------------|
| `draft` | Saved but not published |
| `pending` | Awaiting moderation (if enabled) |
| `active` | Live and searchable |
| `flagged` | Reported by users, under review |
| `expired` | Past expiration date (30 days default) |
| `removed` | Deleted by user or admin |
| `sold` | Marked as sold by poster |

- Listings expire after 30 days (configurable)
- Users can renew before expiration
- Email reminder 3 days before expiration

#### F12.8: Listing Fees & Monetization

**Category-Based Posting Fees:**

Most categories are **free to post**. Only high-value categories require a posting fee:

| Category | Posting Fee | Duration |
|----------|-------------|----------|
| For Sale (all) | Free | 30 days |
| Services (all) | Free | 30 days |
| Community (all) | Free | 30 days |
| **Housing** | $5 | 30 days |
| **Jobs** | $10 | 30 days |
| **Cars & Trucks** | $5 | 30 days |
| **Motorcycles** | $3 | 30 days |
| **RVs & Campers** | $5 | 30 days |
| **Boats** | $5 | 30 days |

**Promoted Listings (Optional Add-ons):**

- **Featured listing**: $5 for 7 days
  - Highlighted in search results
  - Appears at top of category
  - "Featured" badge
- **Bump to top**: $2 per bump
  - Resets listing to top of chronological order
  - Limited to once per 24 hours

**Payment:** Stripe (one-time charges, not subscriptions)

**Refund Policy:** 24-hour conditional refund window for technical failures and moderation rejections (see **Policy P3**)

**Fraud Detection:** AI-assisted analysis for scam patterns with auto-flagging for human review (see **Policy P3**)

```
listing_promotions
├── id (UUID, PK)
├── listing_id (FK)
├── type (featured, bump)
├── stripe_payment_intent_id
├── amount_cents
├── starts_at
├── expires_at
├── created_at
└── updated_at
```

#### F12.9: Flagging & Moderation

- Users can flag listings (spam, prohibited, scam, miscategorized, other)
- Flag threshold: 3 flags → auto-hide pending review
- Admin moderation queue:
  - View flagged listings
  - Approve, remove, or warn user
  - Ban repeat offenders
- Prohibited content detection (basic keyword filter)

```
listing_flags
├── id (UUID, PK)
├── listing_id (FK)
├── user_id (FK)
├── reason (spam, prohibited, scam, miscategorized, other)
├── details (text)
├── created_at
└── resolved_at
```

#### F12.10: Search & Browse

- Full-text search on title and description (PostgreSQL tsvector)
- Filter by:
  - Category (with subcategory drill-down)
  - Location + radius
  - Price range
  - Has images
  - Posted date (today, week, month)
- Sort by: Newest, Price (low/high), Distance
- Pagination: 25 listings per page

#### F12.11: UI Design (Modern Craigslist)

- **Text-link based**: Like Craigslist, primarily text links
- **Modern typography**: Clean sans-serif, good spacing
- **Minimal images in browse**: Only thumbnails in list view
- **Category sidebar**: Collapsible category tree
- **Location header**: "Boston Area (25 mi)" with change link
- **Responsive**: Works on mobile, tablet, desktop

Page structure:
```
/marketplace                      → Category landing (all categories listed)
/marketplace/for-sale             → Main category with subcategories
/marketplace/for-sale/electronics → Subcategory listings
/marketplace/listing/{id}         → Individual listing detail
/marketplace/post                 → Create new listing (auth required)
/marketplace/my-listings          → User's listings (auth required)
```

#### F12.12: Marketplace Data Models

```
marketplace_categories
├── id (UUID, PK)
├── parent_id (FK, nullable)
├── name
├── slug
├── sort_order
├── is_active
├── created_at
└── updated_at

marketplace_listings
├── id (UUID, PK)
├── user_id (FK)
├── category_id (FK)
├── city_id (FK -> geo_cities)
├── title
├── description
├── price (nullable)
├── is_free
├── contact_for_price
├── latitude
├── longitude
├── location_text ("Boston, MA")
├── contact_email_masked
├── contact_phone (nullable)
├── status (draft, pending, active, flagged, expired, removed, sold)
├── view_count
├── reply_count
├── flag_count
├── expires_at
├── published_at
├── created_at
└── updated_at

marketplace_listing_images
├── id (UUID, PK)
├── listing_id (FK)
├── storage_key
├── original_filename
├── content_type
├── size_bytes
├── width
├── height
├── sort_order
├── created_at

marketplace_messages
├── id (UUID, PK)
├── listing_id (FK)
├── from_user_id (FK)
├── to_user_id (FK)
├── masked_email_token
├── subject
├── body
├── is_read
├── created_at

marketplace_saved_searches
├── id (UUID, PK)
├── user_id (FK)
├── name
├── search_params (JSONB)
├── notify_new_matches
├── created_at
└── updated_at
```

#### F12.13: Background Jobs for Marketplace

| Job Handler | Queue | Interval |
|-------------|-------|----------|
| `ListingExpirationJobHandler` | DEFAULT | Daily |
| `ListingExpirationReminderJobHandler` | LOW | Daily |
| `FlaggedListingReviewJobHandler` | DEFAULT | Hourly |
| `MessageRelayJobHandler` | HIGH | On-demand |
| `ListingImageProcessingJobHandler` | BULK | On-demand |

---

### F13: Good Sites (Web Directory)

A hand-curated web directory inspired by the classic Yahoo Directory and DMOZ. Users browse hierarchical categories to discover quality websites. Each link has a screenshot and description, with Reddit-style voting to surface the best sites per category.

#### F13.1: Category Hierarchy

- Admin-created hierarchical categories (unlimited depth)
- Examples: Technology > Programming > Java, Arts > Music > Jazz
- Each category has:
  - Name, slug, description
  - Icon (optional)
  - Parent category (nullable for top-level)
  - Sort order within parent
- Categories are global (not user-specific)

```
directory_categories
├── id (UUID, PK)
├── parent_id (FK, nullable)
├── name
├── slug
├── description
├── icon_url (nullable)
├── sort_order
├── link_count (denormalized)
├── is_active
├── created_at
└── updated_at
```

#### F13.2: Site Links

- Links can exist in **multiple categories** with separate votes per category
- Each link-category association tracks its own vote score
- Link metadata fetched automatically by backend job

```
directory_sites
├── id (UUID, PK)
├── url (unique)
├── domain
├── title
├── description
├── screenshot_url
├── screenshot_captured_at
├── og_image_url (from OpenGraph)
├── favicon_url
├── custom_image_url (user-provided override)
├── submitted_by_user_id (FK)
├── status (pending, active, rejected, dead)
├── last_checked_at
├── is_dead (boolean, 404/unreachable)
├── created_at
└── updated_at

directory_site_categories
├── id (UUID, PK)
├── site_id (FK)
├── category_id (FK)
├── score (net votes: upvotes - downvotes)
├── upvotes
├── downvotes
├── rank_in_category (computed)
├── submitted_by_user_id (FK)
├── approved_by_user_id (FK, nullable)
├── status (pending, approved, rejected)
├── created_at
└── updated_at
UNIQUE(site_id, category_id)
```

#### F13.3: Screenshot Capture Service

- **Self-hosted Puppeteer/Playwright** service for screenshot capture
- Capture settings:
  - Viewport: 1280x800
  - Full page: No (above-fold only)
  - Wait for network idle
  - Timeout: 30 seconds
- Store screenshots in S3-compatible storage (MinIO self-hosted or Cloudflare R2)
- Generate sizes:
  - Thumbnail: 320x200 (for grid view)
  - Full: 1280x800 (for detail view)
- **WebP format with 85% quality** for storage optimization
- Fallback to OpenGraph image if screenshot fails
- Re-capture screenshots every 30 days or on-demand
- **Unlimited retention with version history** - previous screenshots preserved (see **Policy P4**)

```java
public interface ScreenshotService {
    /**
     * Capture screenshot of URL.
     * @return S3 key of captured screenshot
     */
    CompletableFuture<String> capture(String url);

    /**
     * Extract metadata from URL (title, description, OG tags).
     */
    SiteMetadataType extractMetadata(String url);
}

public record SiteMetadataType(
    String title,
    String description,
    String ogImage,
    String favicon,
    List<String> keywords
) {}
```

#### F13.4: Voting System (Reddit-style)

- **Upvote/Downvote** per link within each category
- One vote per user per link-category (can change vote)
- Score = upvotes - downvotes
- Ranking within category by score (descending), then by submission date
- Login required to vote; anonymous users see rankings only

```
directory_votes
├── id (UUID, PK)
├── site_category_id (FK -> directory_site_categories)
├── user_id (FK)
├── vote (1 for upvote, -1 for downvote)
├── created_at
└── updated_at
UNIQUE(site_category_id, user_id)
```

Vote handling:
```java
@Transactional
public void vote(UUID siteCategoryId, UUID userId, int vote) {
    // vote: 1 (up), -1 (down), 0 (remove vote)
    DirectoryVote existing = DirectoryVote.findByUserAndSiteCategory(userId, siteCategoryId);

    if (existing != null) {
        int oldVote = existing.vote;
        if (vote == 0) {
            existing.delete();
            updateScore(siteCategoryId, -oldVote);
        } else {
            existing.vote = vote;
            existing.persist();
            updateScore(siteCategoryId, vote - oldVote);
        }
    } else if (vote != 0) {
        DirectoryVote newVote = new DirectoryVote();
        newVote.siteCategoryId = siteCategoryId;
        newVote.userId = userId;
        newVote.vote = vote;
        newVote.persist();
        updateScore(siteCategoryId, vote);
    }
}
```

#### F13.5: Bubbling Up to Parent Categories

- When viewing a category, show:
  1. Links directly in this category (sorted by score)
  2. Top-ranked links from subcategories (if score exceeds threshold)
- Bubbling threshold: score >= 10 AND rank <= 3 in subcategory
- Display indicates which subcategory the link is from
- Configurable per-category: `show_bubbled_links` (boolean)

```java
public record CategoryViewType(
    DirectoryCategoryType category,
    List<DirectoryCategoryType> subcategories,
    List<DirectorySiteLinkType> directLinks,      // Links in this category
    List<DirectorySiteLinkType> bubbledLinks,     // Top links from subcategories
    List<DirectoryCategoryType> breadcrumbs
) {}
```

#### F13.6: User Submissions with Karma-Based Trust

**Karma System:**
- New users start with karma = 0
- Karma earned:
  - +5 for approved submission
  - +1 for each upvote on your submissions
  - -1 for each downvote on your submissions
  - -10 for rejected submission
- Trust thresholds:
  - karma < 10: All submissions go to moderation queue
  - karma >= 10: Auto-publish, can be flagged later
  - karma >= 50: Can edit link metadata
  - karma >= 100: Can become category moderator

```
users (add fields)
├── directory_karma (INT, default 0)
├── directory_trust_level (untrusted, trusted, editor, moderator)
└── directory_submissions_count
```

**Submission Flow:**
1. User submits URL + selects category
2. Backend fetches metadata (title, description, screenshot)
3. User can override title/description, provide custom image
4. If trusted: publish immediately
5. If untrusted: add to moderation queue

```java
public record SubmitSiteType(
    String url,
    UUID categoryId,
    String customTitle,        // Optional override
    String customDescription,  // Optional override
    String customImageUrl      // Optional user-provided image
) {}
```

#### F13.7: Moderation System

**Roles:**
- **Super Admin**: Full access to all categories
- **Category Moderator**: Assigned to specific categories (and their subcategories)
  - Approve/reject submissions
  - Edit link metadata
  - Remove links
  - Cannot delete categories

```
directory_category_moderators
├── id (UUID, PK)
├── category_id (FK)
├── user_id (FK)
├── granted_by_user_id (FK)
├── created_at
UNIQUE(category_id, user_id)
```

**Moderation Queue:**
- View pending submissions for assigned categories
- Bulk approve/reject
- See submitter's karma and history
- Add rejection reason (optional, sent to user)

**Flagging:**
- Any logged-in user can flag a link (spam, broken, wrong category, inappropriate)
- 3 flags → auto-hide pending review
- Moderators resolve flags

```
directory_flags
├── id (UUID, PK)
├── site_category_id (FK)
├── user_id (FK)
├── reason (spam, broken, wrong_category, inappropriate, other)
├── details (text)
├── created_at
└── resolved_at
```

#### F13.8: Link Health Checking

- Background job checks all active links periodically
- Check for:
  - HTTP status (200 OK, redirects, 404, etc.)
  - SSL certificate validity
  - Domain changes
- Mark as "dead" if unreachable 3 times in a row
- Dead links hidden from listings but not deleted
- Notify moderators of newly dead links

```java
public record LinkHealthType(
    int httpStatus,
    boolean sslValid,
    String finalUrl,           // After redirects
    boolean domainChanged,
    Instant checkedAt
) {}
```

#### F13.9: Browse & Search

**Browse by Category:**
- Homepage shows top-level categories with icons
- Click category → see subcategories + links
- Infinite depth navigation via breadcrumbs
- Links sorted by score within category

**Search:**
- Full-text search on title, description, URL, domain
- Filter by category (optional)
- Results ranked by relevance + score

**URL Endpoints:**
```
/good-sites                           → Top-level categories
/good-sites/technology                → Category with subcategories + links
/good-sites/technology/programming    → Subcategory
/good-sites/site/{id}                 → Site detail page
/good-sites/submit                    → Submit new site (auth required)
/good-sites/my-submissions            → User's submissions (auth required)
/good-sites/moderate                  → Moderation queue (moderators only)
```

#### F13.10: Site Detail Page

- Large screenshot
- Title, description, URL (clickable)
- Category breadcrumb (which category this view is from)
- Other categories this site is in (with their scores)
- Vote buttons (if logged in)
- Flag button
- "Visit Site" button (external link)
- Submitted by, submission date
- Related sites (same category, similar score)

#### F13.11: UI Design

- Clean, modern grid layout for categories
- Card-based display for sites with screenshot thumbnails
- Category icons for visual navigation
- Breadcrumb navigation
- Score displayed prominently (+123 or -5)
- Mobile-responsive grid (3 cols → 2 cols → 1 col)

#### F13.12: Good Sites Data Models Summary

```
directory_categories
├── id, parent_id, name, slug, description
├── icon_url, sort_order, link_count
├── is_active, created_at, updated_at

directory_sites
├── id, url, domain, title, description
├── screenshot_url, screenshot_captured_at
├── og_image_url, favicon_url, custom_image_url
├── submitted_by_user_id, status
├── last_checked_at, is_dead
├── created_at, updated_at

directory_site_categories
├── id, site_id, category_id
├── score, upvotes, downvotes, rank_in_category
├── submitted_by_user_id, approved_by_user_id
├── status, created_at, updated_at

directory_votes
├── id, site_category_id, user_id
├── vote (+1/-1), created_at, updated_at

directory_category_moderators
├── id, category_id, user_id
├── granted_by_user_id, created_at

directory_flags
├── id, site_category_id, user_id
├── reason, details, created_at, resolved_at
```

#### F13.13: Background Jobs for Good Sites

| Job Handler | Queue | Interval |
|-------------|-------|----------|
| `ScreenshotCaptureJobHandler` | BULK | On-demand |
| `MetadataRefreshJobHandler` | LOW | On-demand |
| `LinkHealthCheckJobHandler` | LOW | Weekly |
| `ScreenshotRefreshJobHandler` | BULK | Monthly |
| `RankRecalculationJobHandler` | DEFAULT | Hourly |

#### F13.14: Bulk Import & AI Auto-Categorization

**Superuser Tools:**

1. **Bulk Import**
   - Upload CSV/JSON with URLs
   - Background job processes each URL
   - Auto-fetch metadata and screenshot
   - AI suggests category placement
   - Admin reviews and approves batch

2. **Quick Single Link Insert**
   - Enter URL
   - System fetches metadata/screenshot
   - AI analyzes content and suggests category
   - Admin confirms or overrides category
   - Publish immediately

```java
public record BulkImportRowType(
    String url,
    String suggestedCategory,    // Optional hint
    String customTitle,          // Optional override
    String customDescription     // Optional override
) {}

public record AiCategorizationType(
    UUID suggestedCategoryId,
    String categoryPath,         // e.g., "Technology > Programming > Java"
    double confidence,           // 0.0 - 1.0
    List<String> reasoning       // Why this category
) {}
```

**AI Categorization Prompt:**
```
Analyze this website and suggest the most appropriate category from our directory.

Website URL: {url}
Title: {title}
Description: {description}
Keywords: {keywords}

Available top-level categories: {categoryList}

Respond with:
1. The best matching category path (e.g., "Technology > Programming > Java")
2. Confidence level (0-100%)
3. Brief reasoning
```

---

### F14: Cross-Cutting Concerns

#### F14.1: Caching Strategy

Use **Hibernate 2nd Level Cache** for entity caching:
- Cache provider: Caffeine (in-process)
- Cache frequently-read, rarely-changed entities
- Query cache for common lookups

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-hibernate-orm</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-cache</artifactId>
</dependency>
```

```java
@Entity
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class DirectoryCategory extends PanacheEntityBase {
    // ...
}
```

**Feed Refresh Frequencies (to limit writes):**
| Feed Type | Default Interval |
|-----------|------------------|
| Breaking news | 15 minutes |
| Standard news | 1 hour |
| Blogs/personal | 6 hours |
| Low-priority | Daily |

#### F14.2: Rate Limiting

Implement rate limiting for API endpoints and user actions.

**Rate Limit Tiers:**
| Action | Anonymous | Logged In | Trusted |
|--------|-----------|-----------|---------|
| Page views | 100/min | 300/min | 500/min |
| Search | 20/min | 60/min | 120/min |
| Votes | N/A | 30/min | 60/min |
| Submissions | N/A | 5/hour | 20/hour |
| API calls | 10/min | 60/min | 120/min |

**Implementation:**
```java
@ApplicationScoped
public class RateLimitService {

    @Inject
    Cache<String, RateLimitBucket> rateLimitCache;

    public boolean isAllowed(String key, RateLimitTier tier) {
        RateLimitBucket bucket = rateLimitCache.get(key,
            k -> new RateLimitBucket(tier.getLimit(), tier.getWindowSeconds()));
        return bucket.tryConsume();
    }
}
```

**Admin Dashboard:**
- Real-time activity graphs (requests/min, votes/hour, submissions/day)
- Rate limit violations log
- Adjust rate limits per tier
- IP-based blocking for abuse
- User-specific rate limit overrides

```
rate_limit_config
├── id (UUID, PK)
├── action_type (page_view, search, vote, submission, api)
├── tier (anonymous, logged_in, trusted)
├── limit_count
├── window_seconds
├── updated_by_user_id
├── updated_at

rate_limit_violations
├── id (UUID, PK)
├── user_id (FK, nullable)
├── ip_address
├── action_type
├── endpoint
├── violation_count
├── first_violation_at
├── last_violation_at
```

#### F14.3: Email System

**Email Templates (Qute):**
Follow village-calendar pattern with Qute templates in `src/main/resources/templates/email-templates/`.

| Template | Trigger |
|----------|---------|
| `welcome.html` | User first login |
| `listing-published.html` | Marketplace listing goes live |
| `listing-expiring.html` | 3 days before listing expires |
| `listing-expired.html` | Listing has expired |
| `submission-approved.html` | Good Sites submission approved |
| `submission-rejected.html` | Good Sites submission rejected |
| `message-received.html` | New marketplace inquiry |
| `flag-resolved.html` | User's flag was reviewed |
| `moderator-invite.html` | Promoted to category moderator |

```java
@Inject
Template listingExpiring;

public void sendExpiringReminder(MarketplaceListing listing) {
    String html = listingExpiring
        .data("listing", listing)
        .data("renewUrl", buildRenewUrl(listing))
        .data("daysRemaining", 3)
        .render();

    emailService.send(listing.user.email, "Your listing expires in 3 days", html);
}
```

**Inbound Email Processing:**

IMAP background job polls mailbox for replies to marketplace listings.

```
Email format: homepage-{type}-{id}@villagecompute.com

Examples:
- homepage-listing-abc123@villagecompute.com  → Marketplace inquiry
- homepage-reply-xyz789@villagecompute.com    → Reply to conversation
```

```java
@ApplicationScoped
public class InboundEmailProcessor {

    @ConfigProperty(name = "email.imap.host")
    String imapHost;

    @ConfigProperty(name = "email.imap.username")
    String imapUsername;

    @ConfigProperty(name = "email.imap.password")
    String imapPassword;

    @Scheduled(every = "1m")
    public void checkInbox() {
        // Connect to IMAP
        // Fetch unread messages
        // Parse recipient address to extract type and ID
        // Route to appropriate handler
        // Mark as read or delete
    }
}

public record InboundEmailType(
    String from,
    String to,
    String subject,
    String bodyText,
    String bodyHtml,
    List<AttachmentType> attachments,
    Instant receivedAt
) {}
```

```
inbound_emails
├── id (UUID, PK)
├── from_address
├── to_address
├── subject
├── body_text
├── body_html
├── message_id (from email headers)
├── in_reply_to (for threading)
├── processed_at
├── error_message (if processing failed)
├── created_at
```

#### F14.4: Feature Flags

Admin-controlled feature flags for gradual rollout and A/B testing (see **Policy P7**).

```
feature_flags
├── id (UUID, PK)
├── key (unique, e.g., "stocks_widget", "social_integration")
├── name
├── description
├── is_enabled (boolean)
├── rollout_percentage (0-100, for gradual rollout)
├── user_whitelist (JSONB array of user IDs)
├── updated_by_user_id
├── updated_at
├── created_at
```

**Superuser Admin UI:**
- View all feature flags
- Toggle enabled/disabled
- Set rollout percentage
- Add users to whitelist
- View flag usage analytics

```java
@ApplicationScoped
public class FeatureFlagService {

    public boolean isEnabled(String flagKey, UUID userId) {
        FeatureFlag flag = FeatureFlag.findByKey(flagKey);
        if (flag == null || !flag.isEnabled) {
            return false;
        }

        // Check whitelist
        if (flag.userWhitelist != null && flag.userWhitelist.contains(userId)) {
            return true;
        }

        // Check rollout percentage
        if (flag.rolloutPercentage >= 100) {
            return true;
        }
        if (flag.rolloutPercentage <= 0) {
            return false;
        }

        // Stable cohort via consistent hash (MD5 mod 100)
        // Same user always gets same result for given percentage
        String hashInput = flagKey + ":" + userId.toString();
        int hash = Math.abs(hashInput.hashCode() % 100);
        boolean result = hash < flag.rolloutPercentage;

        // Log evaluation for analytics if enabled
        if (flag.analyticsEnabled) {
            logFlagEvaluation(flagKey, userId, result);
        }

        return result;
    }

    private void logFlagEvaluation(String flagKey, UUID userId, boolean result) {
        // Async log to feature_flag_evaluations table
    }
}
```

**Initial Feature Flags:**
| Flag Key | Description | Initial State |
|----------|-------------|---------------|
| `stocks_widget` | Stock market widget on homepage | Disabled |
| `social_integration` | Instagram/Facebook integration | Disabled |
| `marketplace` | Classifieds marketplace | Enabled |
| `good_sites` | Web directory | Enabled |
| `ai_tagging` | AI-powered news tagging | Enabled |
| `promoted_listings` | Paid listing promotions | Disabled |

#### F14.5: SEO

**Meta Tags (Qute layout):**
```html
<head>
    <title>{page.title} | Village Homepage</title>
    <meta name="description" content="{page.description}">
    <meta name="keywords" content="{page.keywords}">

    <!-- Open Graph -->
    <meta property="og:title" content="{page.title}">
    <meta property="og:description" content="{page.description}">
    <meta property="og:image" content="{page.ogImage}">
    <meta property="og:url" content="{page.canonicalUrl}">
    <meta property="og:type" content="{page.ogType}">

    <!-- Twitter Card -->
    <meta name="twitter:card" content="summary_large_image">
    <meta name="twitter:title" content="{page.title}">
    <meta name="twitter:description" content="{page.description}">
    <meta name="twitter:image" content="{page.ogImage}">

    <!-- Canonical URL -->
    <link rel="canonical" href="{page.canonicalUrl}">

    <!-- JSON-LD Structured Data -->
    <script type="application/ld+json">
    {page.structuredData.raw}
    </script>
</head>
```

**Sitemaps:**
- `/sitemap.xml` - Index sitemap
- `/sitemap-categories.xml` - Good Sites categories
- `/sitemap-sites.xml` - Good Sites entries (paginated)
- `/sitemap-marketplace.xml` - Active marketplace listings
- Regenerate daily via background job

```java
@Path("/sitemap.xml")
public class SitemapResource {

    @GET
    @Produces(MediaType.APPLICATION_XML)
    public String sitemapIndex() {
        // Return sitemap index pointing to sub-sitemaps
    }
}
```

**Robots.txt:**
```
User-agent: *
Allow: /
Disallow: /admin/
Disallow: /api/
Disallow: /my-listings
Disallow: /my-submissions

Sitemap: https://homepage.villagecompute.com/sitemap.xml
```

**Structured Data (JSON-LD):**
- `WebSite` schema for homepage
- `ItemList` for category pages
- `Product` for marketplace listings (with price, availability)
- `WebPage` for Good Sites entries

#### F14.6: Screenshot Service (jvppeteer)

Use **jvppeteer** to call Puppeteer from Java within the same container.

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.github.fanyong920</groupId>
    <artifactId>jvppeteer</artifactId>
    <version>3.4.1</version>
</dependency>
```

**Service Implementation:**
```java
@ApplicationScoped
public class ScreenshotService {

    private Browser browser;

    @PostConstruct
    void init() {
        // Launch browser on startup
        LaunchOptions options = new LaunchOptionsBuilder()
            .withHeadless(true)
            .withArgs(Arrays.asList(
                "--no-sandbox",
                "--disable-setuid-sandbox",
                "--disable-dev-shm-usage"
            ))
            .build();
        browser = Puppeteer.launch(options);
    }

    @PreDestroy
    void shutdown() {
        if (browser != null) {
            browser.close();
        }
    }

    public byte[] captureScreenshot(String url) throws Exception {
        Page page = browser.newPage();
        try {
            page.setViewport(new Viewport(1280, 800));
            page.goTo(url, new GoToOptions().withWaitUntil(
                Arrays.asList(WaitForSelectorOptions.NETWORKIDLE2)));

            ScreenshotOptions options = new ScreenshotOptions();
            options.setType("png");
            options.setFullPage(false);

            return page.screenshot(options);
        } finally {
            page.close();
        }
    }

    public SiteMetadataType extractMetadata(String url) throws Exception {
        Page page = browser.newPage();
        try {
            page.goTo(url);

            String title = page.title();
            String description = (String) page.evaluate(
                "() => document.querySelector('meta[name=\"description\"]')?.content || ''");
            String ogImage = (String) page.evaluate(
                "() => document.querySelector('meta[property=\"og:image\"]')?.content || ''");
            String favicon = (String) page.evaluate(
                "() => document.querySelector('link[rel=\"icon\"]')?.href || " +
                "document.querySelector('link[rel=\"shortcut icon\"]')?.href || ''");

            return new SiteMetadataType(title, description, ogImage, favicon, List.of());
        } finally {
            page.close();
        }
    }
}
```

**Docker Configuration:**
```dockerfile
# Dockerfile
FROM eclipse-temurin:21-jre

# Install Chrome dependencies
RUN apt-get update && apt-get install -y \
    chromium \
    chromium-sandbox \
    fonts-liberation \
    libappindicator3-1 \
    libasound2 \
    libatk-bridge2.0-0 \
    libatk1.0-0 \
    libcups2 \
    libdbus-1-3 \
    libdrm2 \
    libgbm1 \
    libgtk-3-0 \
    libnspr4 \
    libnss3 \
    libx11-xcb1 \
    libxcomposite1 \
    libxdamage1 \
    libxrandr2 \
    xdg-utils \
    && rm -rf /var/lib/apt/lists/*

ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium

COPY target/quarkus-app /app
WORKDIR /app
CMD ["java", "-jar", "quarkus-run.jar"]
```

#### F14.7: Object Storage (Cloudflare R2)

Use **Cloudflare R2** (S3-compatible) for all file storage.

```properties
# application.properties
quarkus.s3.endpoint-override=${R2_ENDPOINT}
quarkus.s3.aws.region=auto
quarkus.s3.aws.credentials.type=static
quarkus.s3.aws.credentials.static-provider.access-key-id=${R2_ACCESS_KEY_ID}
quarkus.s3.aws.credentials.static-provider.secret-access-key=${R2_SECRET_ACCESS_KEY}

storage.bucket.screenshots=homepage-screenshots
storage.bucket.listings=homepage-listings
storage.cdn.base-url=https://cdn.homepage.villagecompute.com
```

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.quarkiverse.amazonservices</groupId>
    <artifactId>quarkus-amazon-s3</artifactId>
</dependency>
```

**Storage Service:**
```java
@ApplicationScoped
public class StorageService {

    @Inject
    S3Client s3;

    @ConfigProperty(name = "storage.bucket.screenshots")
    String screenshotsBucket;

    @ConfigProperty(name = "storage.cdn.base-url")
    String cdnBaseUrl;

    public String uploadScreenshot(byte[] data, String filename) {
        String key = "screenshots/" + UUID.randomUUID() + "/" + filename;

        s3.putObject(
            PutObjectRequest.builder()
                .bucket(screenshotsBucket)
                .key(key)
                .contentType("image/png")
                .build(),
            RequestBody.fromBytes(data)
        );

        return cdnBaseUrl + "/" + key;
    }
}
```

#### F14.8: Search (Hibernate Search + Elasticsearch)

Use **Hibernate Search** with Elasticsearch backend for full-text search.

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-hibernate-search-orm-elasticsearch</artifactId>
</dependency>
```

```properties
# application.properties
quarkus.hibernate-search-orm.elasticsearch.version=8
quarkus.hibernate-search-orm.elasticsearch.hosts=${ELASTICSEARCH_HOSTS:localhost:9200}
quarkus.hibernate-search-orm.elasticsearch.protocol=http

# Auto-create indexes in dev
%dev.quarkus.hibernate-search-orm.schema-management.strategy=create-or-update
%prod.quarkus.hibernate-search-orm.schema-management.strategy=create-or-validate
```

**Indexed Entities:**
```java
@Entity
@Indexed
public class MarketplaceListing extends PanacheEntityBase {

    @FullTextField(analyzer = "english")
    public String title;

    @FullTextField(analyzer = "english")
    public String description;

    @KeywordField
    public String status;

    @GenericField
    public Double latitude;

    @GenericField
    public Double longitude;

    // ...
}

@Entity
@Indexed
public class DirectorySite extends PanacheEntityBase {

    @FullTextField(analyzer = "english")
    public String title;

    @FullTextField(analyzer = "english")
    public String description;

    @KeywordField
    public String domain;

    @KeywordField
    public String url;

    // ...
}
```

**Search Service:**
```java
@ApplicationScoped
public class SearchService {

    @Inject
    SearchSession searchSession;

    public List<MarketplaceListing> searchListings(
            String query, UUID categoryId, Double lat, Double lon, Integer radiusMiles) {

        return searchSession.search(MarketplaceListing.class)
            .where(f -> f.bool()
                .must(f.match().fields("title", "description").matching(query))
                .filter(f.match().field("status").matching("active"))
                .filter(categoryId != null
                    ? f.match().field("categoryId").matching(categoryId)
                    : f.matchAll())
                .filter(lat != null && lon != null
                    ? f.spatial().within().circle(lat, lon, radiusMiles, DistanceUnit.MILES)
                    : f.matchAll())
            )
            .sort(f -> f.score())
            .fetchHits(25);
    }
}
```

**docker-compose.yml (add Elasticsearch):**
```yaml
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.11.0
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
      - "ES_JAVA_OPTS=-Xms512m -Xmx512m"
    ports:
      - "9200:9200"
    volumes:
      - elasticsearch-data:/usr/share/elasticsearch/data
    healthcheck:
      test: curl -s http://localhost:9200 >/dev/null || exit 1
      interval: 30s
      timeout: 10s
      retries: 5

volumes:
  elasticsearch-data:
```

#### F14.9: Click Tracking & Analytics

All link clicks across the platform must be tracked for analytics and reporting.

**Tracked Click Types:**
| Source | Description |
|--------|-------------|
| `feed_item` | News/RSS feed article clicks |
| `directory_site` | Good Sites link clicks |
| `marketplace_listing` | Marketplace listing views |
| `profile_curated` | Curated article clicks on profile pages |
| `external_link` | Any external link click |

**Click Tracking Implementation:**
- Links wrapped with tracking endpoint: `/track/click?t={type}&id={id}&url={encoded_url}`
- Endpoint logs click and redirects to destination
- Async write to tracking table (non-blocking)
- Client-side fallback for direct navigation (beacon API)

```java
@Path("/track")
public class ClickTrackingResource {

    @Inject
    ClickTrackingService clickTrackingService;

    @GET
    @Path("/click")
    public Response trackClick(
            @QueryParam("t") String type,
            @QueryParam("id") String id,
            @QueryParam("url") String url,
            @Context HttpHeaders headers) {

        // Async log - don't block redirect
        clickTrackingService.logClickAsync(
            type, id, url,
            headers.getHeaderString("User-Agent"),
            headers.getHeaderString("Referer")
        );

        return Response.temporaryRedirect(URI.create(url)).build();
    }
}
```

**Raw Click Tracking Table (Daily Partitioned):**

Table is partitioned by day for efficient querying and retention management.

```sql
-- Partitioned by click_date for daily granularity
CREATE TABLE link_clicks (
    id UUID NOT NULL,
    click_date DATE NOT NULL,
    click_timestamp TIMESTAMPTZ NOT NULL,
    click_type VARCHAR(50) NOT NULL,  -- feed_item, directory_site, etc.
    target_id UUID,                    -- ID of clicked item (nullable for external)
    target_url TEXT NOT NULL,
    user_id UUID,                      -- nullable for anonymous
    session_id VARCHAR(100),
    ip_address INET,
    user_agent TEXT,
    referer TEXT,
    category_id UUID,                  -- For category-level rollups
    PRIMARY KEY (click_date, id)
) PARTITION BY RANGE (click_date);

-- Create partitions automatically via pg_partman or manually
CREATE TABLE link_clicks_2026_01 PARTITION OF link_clicks
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

-- Indexes for common queries
CREATE INDEX idx_link_clicks_type_date ON link_clicks (click_type, click_date);
CREATE INDEX idx_link_clicks_target ON link_clicks (target_id, click_date);
CREATE INDEX idx_link_clicks_category ON link_clicks (category_id, click_date);
CREATE INDEX idx_link_clicks_user ON link_clicks (user_id, click_date);
```

**Daily Stats Rollup Tables:**

Hourly job aggregates raw clicks into daily summary tables per category.

```sql
-- Daily stats by category
CREATE TABLE click_stats_daily (
    id UUID PRIMARY KEY,
    stat_date DATE NOT NULL,
    click_type VARCHAR(50) NOT NULL,
    category_id UUID,                  -- nullable for uncategorized
    category_name VARCHAR(255),        -- denormalized for reporting
    total_clicks BIGINT NOT NULL DEFAULT 0,
    unique_users BIGINT NOT NULL DEFAULT 0,
    unique_sessions BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(stat_date, click_type, category_id)
);

-- Daily stats by individual item (top performers)
CREATE TABLE click_stats_daily_items (
    id UUID PRIMARY KEY,
    stat_date DATE NOT NULL,
    click_type VARCHAR(50) NOT NULL,
    target_id UUID NOT NULL,
    target_title VARCHAR(500),         -- denormalized
    target_url TEXT,
    category_id UUID,
    total_clicks BIGINT NOT NULL DEFAULT 0,
    unique_users BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(stat_date, click_type, target_id)
);

-- Indexes
CREATE INDEX idx_click_stats_daily_date ON click_stats_daily (stat_date DESC);
CREATE INDEX idx_click_stats_daily_category ON click_stats_daily (category_id, stat_date DESC);
CREATE INDEX idx_click_stats_daily_items_clicks ON click_stats_daily_items (stat_date, total_clicks DESC);
```

**Rollup Job:**

```java
@ApplicationScoped
public class ClickStatsRollupJobHandler implements DelayedJobHandler {

    @Override
    @Transactional
    public void handle(DelayedJob job) {
        LocalDate targetDate = job.payload.get("date", LocalDate.class);

        // Aggregate by category
        String categoryRollupSql = """
            INSERT INTO click_stats_daily (id, stat_date, click_type, category_id, category_name,
                                           total_clicks, unique_users, unique_sessions)
            SELECT gen_random_uuid(), :date, click_type, category_id,
                   COALESCE(dc.name, mc.name, 'Uncategorized'),
                   COUNT(*),
                   COUNT(DISTINCT user_id),
                   COUNT(DISTINCT session_id)
            FROM link_clicks lc
            LEFT JOIN directory_categories dc ON lc.category_id = dc.id AND lc.click_type = 'directory_site'
            LEFT JOIN marketplace_categories mc ON lc.category_id = mc.id AND lc.click_type = 'marketplace_listing'
            WHERE click_date = :date
            GROUP BY click_type, category_id, COALESCE(dc.name, mc.name, 'Uncategorized')
            ON CONFLICT (stat_date, click_type, category_id)
            DO UPDATE SET
                total_clicks = EXCLUDED.total_clicks,
                unique_users = EXCLUDED.unique_users,
                unique_sessions = EXCLUDED.unique_sessions,
                updated_at = NOW()
            """;

        // Aggregate top items per type
        String itemRollupSql = """
            INSERT INTO click_stats_daily_items (id, stat_date, click_type, target_id,
                                                  target_title, target_url, category_id, total_clicks, unique_users)
            SELECT gen_random_uuid(), :date, click_type, target_id,
                   -- Get title from appropriate table based on type
                   CASE click_type
                       WHEN 'feed_item' THEN (SELECT title FROM feed_items WHERE id = target_id)
                       WHEN 'directory_site' THEN (SELECT title FROM directory_sites WHERE id = target_id)
                       WHEN 'marketplace_listing' THEN (SELECT title FROM marketplace_listings WHERE id = target_id)
                       ELSE target_url
                   END,
                   target_url, category_id,
                   COUNT(*),
                   COUNT(DISTINCT user_id)
            FROM link_clicks
            WHERE click_date = :date AND target_id IS NOT NULL
            GROUP BY click_type, target_id, target_url, category_id
            ON CONFLICT (stat_date, click_type, target_id)
            DO UPDATE SET
                total_clicks = EXCLUDED.total_clicks,
                unique_users = EXCLUDED.unique_users
            """;

        // Execute rollups
        entityManager.createNativeQuery(categoryRollupSql)
            .setParameter("date", targetDate)
            .executeUpdate();

        entityManager.createNativeQuery(itemRollupSql)
            .setParameter("date", targetDate)
            .executeUpdate();
    }
}
```

**Background Job Schedule:**

| Job Handler | Queue | Schedule |
|-------------|-------|----------|
| `ClickStatsRollupJobHandler` | DEFAULT | Hourly (rolls up previous hour + previous day at midnight) |
| `ClickDataRetentionJobHandler` | LOW | Daily (drop partitions older than 90 days) |

**Superuser Admin Reports:**

Analytics dashboard at `/admin/analytics` with the following views:

1. **Overview Dashboard**
   - Total clicks (today, 7d, 30d)
   - Clicks by type (pie chart)
   - Daily trend (line chart)

2. **Category Performance**
   - Clicks per category (bar chart)
   - Filter by: click type, date range
   - Drill-down to individual items

3. **Top Performers**
   - Top clicked items by type
   - Filter by: category, date range
   - Sortable table with sparkline trends

4. **Traffic Sources**
   - Clicks by referer domain
   - User agent breakdown (device types)

**Admin Report API Endpoints:**

```java
@Path("/admin/api/analytics")
@RolesAllowed({Roles.SUPER_ADMIN, Roles.OPS, Roles.READ_ONLY})
public class AnalyticsResource {

    @GET
    @Path("/overview")
    public AnalyticsOverviewType getOverview(
            @QueryParam("from") LocalDate from,
            @QueryParam("to") LocalDate to) { ... }

    @GET
    @Path("/by-category")
    public List<CategoryStatsType> getByCategory(
            @QueryParam("clickType") String clickType,
            @QueryParam("from") LocalDate from,
            @QueryParam("to") LocalDate to) { ... }

    @GET
    @Path("/top-items")
    public List<ItemStatsType> getTopItems(
            @QueryParam("clickType") String clickType,
            @QueryParam("categoryId") UUID categoryId,
            @QueryParam("from") LocalDate from,
            @QueryParam("to") LocalDate to,
            @QueryParam("limit") @DefaultValue("50") int limit) { ... }

    @GET
    @Path("/daily-trend")
    public List<DailyTrendType> getDailyTrend(
            @QueryParam("clickType") String clickType,
            @QueryParam("categoryId") UUID categoryId,
            @QueryParam("from") LocalDate from,
            @QueryParam("to") LocalDate to) { ... }
}
```

**Response Types:**

```java
public record AnalyticsOverviewType(
    long totalClicksToday,
    long totalClicks7d,
    long totalClicks30d,
    Map<String, Long> clicksByType,
    long uniqueUsersToday,
    long uniqueUsers7d
) {}

public record CategoryStatsType(
    UUID categoryId,
    String categoryName,
    String clickType,
    long totalClicks,
    long uniqueUsers,
    double percentOfTotal
) {}

public record ItemStatsType(
    UUID targetId,
    String title,
    String url,
    String clickType,
    UUID categoryId,
    String categoryName,
    long totalClicks,
    long uniqueUsers,
    List<DailyCountType> trend  // Last 7 days mini-trend
) {}

public record DailyTrendType(
    LocalDate date,
    long totalClicks,
    long uniqueUsers,
    long uniqueSessions
) {}

public record DailyCountType(
    LocalDate date,
    long count
) {}
```

**Frontend Chart Integration (AntV):**

Charts rendered via TypeScript with @antv/g2plot mounted into server-rendered pages.

```html
<!-- Qute template for analytics page -->
<div id="clicks-trend-chart" data-endpoint="/admin/api/analytics/daily-trend"></div>
<div id="category-pie-chart" data-endpoint="/admin/api/analytics/by-category"></div>
<div id="top-items-table" data-endpoint="/admin/api/analytics/top-items"></div>

<script type="module" src="/assets/js/admin-analytics.js"></script>
```

```typescript
// src/main/resources/META-INF/resources/assets/ts/admin-analytics.ts
import { Line, Pie, Column } from '@antv/g2plot';

interface DailyTrend {
  date: string;
  totalClicks: number;
  uniqueUsers: number;
}

async function initDailyTrendChart(container: HTMLElement): Promise<void> {
  const endpoint = container.dataset.endpoint;
  const params = new URLSearchParams(window.location.search);
  const response = await fetch(`${endpoint}?${params}`);
  const data: DailyTrend[] = await response.json();

  const chart = new Line(container, {
    data,
    xField: 'date',
    yField: 'totalClicks',
    seriesField: 'type',
    smooth: true,
    point: { size: 3 },
    tooltip: { showMarkers: true },
    legend: { position: 'top' },
  });

  chart.render();
}

async function initCategoryPieChart(container: HTMLElement): Promise<void> {
  const endpoint = container.dataset.endpoint;
  const params = new URLSearchParams(window.location.search);
  const response = await fetch(`${endpoint}?${params}`);
  const data = await response.json();

  const chart = new Pie(container, {
    data,
    angleField: 'totalClicks',
    colorField: 'categoryName',
    radius: 0.8,
    label: {
      type: 'outer',
      content: '{name}: {percentage}',
    },
    interactions: [{ type: 'element-active' }],
  });

  chart.render();
}

// Mount charts on page load
document.addEventListener('DOMContentLoaded', () => {
  const trendChart = document.getElementById('clicks-trend-chart');
  if (trendChart) initDailyTrendChart(trendChart);

  const pieChart = document.getElementById('category-pie-chart');
  if (pieChart) initCategoryPieChart(pieChart);
});
```

**TypeScript Build Configuration:**

```json
// tsconfig.json
{
  "compilerOptions": {
    "target": "ES2020",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "outDir": "src/main/resources/META-INF/resources/assets/js",
    "rootDir": "src/main/resources/META-INF/resources/assets/ts",
    "declaration": false,
    "esModuleInterop": true
  },
  "include": ["src/main/resources/META-INF/resources/assets/ts/**/*"]
}
```

```json
// package.json (for frontend assets)
{
  "name": "village-homepage-frontend",
  "scripts": {
    "build": "node esbuild.config.js",
    "watch": "node esbuild.config.js --watch",
    "typecheck": "tsc --noEmit"
  },
  "devDependencies": {
    "@types/react": "^18.2.0",
    "@types/react-dom": "^18.2.0",
    "esbuild": "^0.20.0",
    "typescript": "^5.3.0"
  },
  "dependencies": {
    "react": "^18.2.0",
    "react-dom": "^18.2.0",
    "antd": "^5.12.0",
    "@antv/g2plot": "^2.4.0",
    "@antv/s2": "^2.0.0",
    "@antv/s2-react": "^2.0.0",
    "@antv/l7": "^2.20.0",
    "@antv/l7-react": "^2.5.0",
    "dayjs": "^1.11.0"
  }
}
```

```javascript
// esbuild.config.js
const esbuild = require('esbuild');
const watch = process.argv.includes('--watch');

const config = {
  entryPoints: [
    'src/main/resources/META-INF/resources/assets/ts/main.ts',
    'src/main/resources/META-INF/resources/assets/ts/admin-analytics.tsx',
    'src/main/resources/META-INF/resources/assets/ts/components/index.tsx',
  ],
  bundle: true,
  outdir: 'src/main/resources/META-INF/resources/assets/js',
  format: 'esm',
  splitting: true,
  minify: !watch,
  sourcemap: watch,
  external: [],
  loader: {
    '.tsx': 'tsx',
    '.ts': 'ts',
  },
  jsx: 'automatic',
};

if (watch) {
  esbuild.context(config).then(ctx => ctx.watch());
  console.log('Watching for changes...');
} else {
  esbuild.build(config);
}
```

**Data Retention Policy:**
- Raw `link_clicks` partitions: 90 days (then dropped)
- `click_stats_daily`: 2 years
- `click_stats_daily_items`: 1 year

---

## Code Quality Requirements

- **Test Coverage**: 80% line and branch coverage
- **Code Formatting**: Spotless with Eclipse formatter
- **Quality Gate**: SonarCloud with 0 bugs, 0 vulnerabilities

## Local Development

```bash
# Start local services (PostgreSQL, Elasticsearch, Mailpit, MinIO, Jaeger)
docker-compose up -d

# Run migrations
cd migrations && mvn migration:up -Dmigration.env=development

# Start Quarkus in dev mode
./mvnw quarkus:dev
```

**docker-compose.yml services:**
- PostgreSQL 17 with PostGIS extension (port 5432)
- Elasticsearch 8.x (port 9200)
- Mailpit (SMTP port 1025, UI port 8025)
- MinIO (S3-compatible, port 9000, console port 9001)
- Jaeger (OTLP port 4318, UI port 16686)

## Configuration Properties

```properties
# Application
quarkus.application.name=village-homepage
quarkus.http.port=8080

# Database
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/homepage
quarkus.datasource.username=homepage
quarkus.datasource.password=homepage

# Hibernate 2nd Level Cache
quarkus.hibernate-orm.cache."villagecompute.homepage.data.models".expiration.max-idle=3600

# Elasticsearch
quarkus.hibernate-search-orm.elasticsearch.version=8
quarkus.hibernate-search-orm.elasticsearch.hosts=${ELASTICSEARCH_HOSTS:localhost:9200}

# Cloudflare R2 (S3-compatible)
quarkus.s3.endpoint-override=${R2_ENDPOINT}
quarkus.s3.aws.region=auto
quarkus.s3.aws.credentials.type=static
quarkus.s3.aws.credentials.static-provider.access-key-id=${R2_ACCESS_KEY_ID}
quarkus.s3.aws.credentials.static-provider.secret-access-key=${R2_SECRET_ACCESS_KEY}
storage.bucket.screenshots=homepage-screenshots
storage.bucket.listings=homepage-listings
storage.cdn.base-url=${CDN_BASE_URL:https://cdn.homepage.villagecompute.com}

# Alpha Vantage
alphavantage.api-key=${ALPHAVANTAGE_API_KEY}

# Open-Meteo (no key needed)
openmeteo.base-url=https://api.open-meteo.com/v1

# National Weather Service (no key needed)
nws.base-url=https://api.weather.gov

# Meta Graph API
meta.app-id=${META_APP_ID}
meta.app-secret=${META_APP_SECRET}

# LangChain4j / AI
quarkus.langchain4j.anthropic.api-key=${ANTHROPIC_API_KEY}
quarkus.langchain4j.anthropic.chat-model.model-name=claude-sonnet-4-20250514

# Email (outbound)
quarkus.mailer.host=${SMTP_HOST:localhost}
quarkus.mailer.port=${SMTP_PORT:1025}
quarkus.mailer.from=Village Homepage <noreply@villagecompute.com>

# Email (inbound IMAP)
email.imap.host=${IMAP_HOST}
email.imap.port=${IMAP_PORT:993}
email.imap.username=${IMAP_USERNAME}
email.imap.password=${IMAP_PASSWORD}
email.imap.ssl=true

# Puppeteer/Chrome
puppeteer.executable-path=${PUPPETEER_EXECUTABLE_PATH:/usr/bin/chromium}

# Stripe
stripe.api-key=${STRIPE_API_KEY}
stripe.webhook-secret=${STRIPE_WEBHOOK_SECRET}

# Job refresh intervals (minutes)
jobs.rss.default-interval=60
jobs.rss.breaking-news-interval=15
jobs.weather.interval=60
jobs.stocks.market-hours-interval=5
jobs.stocks.after-hours-interval=60
jobs.social.interval=30
jobs.inbound-email.interval=1
```

## Reference Projects

- **village-calendar**: Delayed Job pattern, deployment config, Jib container builds
- **village-storefront**: Permission model, admin roles, java-project-standards.adoc
- **villagecompute**: Infrastructure and Kubernetes configuration
