# Architectural Diagrams

This directory contains PlantUML diagrams documenting the Village Homepage system architecture.

## Diagrams

### System Context Diagram (`context.puml`)

**Purpose:** Shows Village Homepage's position in the broader ecosystem, including external actors and integration points.

**Key Elements:**
- **Actors:** Anonymous users, authenticated users, administrators
- **External Systems:** OAuth providers (Google, Facebook, Apple), Stripe, Meta Graph API, weather APIs (Open-Meteo, NWS), Alpha Vantage, LangChain4j/Claude, Cloudflare R2, PostgreSQL, Elasticsearch, email servers, Jaeger
- **Policy Annotations:** P1-P14 governance rules enforced at system boundaries
- **Protocols:** HTTPS, OAuth 2.0, REST APIs, JDBC, S3 API, SMTP/IMAP, OpenTelemetry

**Reference:** Based on ADR 0001 toolchain decisions and Blueprint Foundation requirements.

### Container Diagram (`container.puml`)

**Purpose:** Decomposes the Quarkus application into internal components, async queues, and deployment topology.

**Key Elements:**

**Experience Pod (Quarkus Runtime):**
- ExperienceShell (Qute + React Islands)
- AuthIdentityService, UserPreferenceService
- FeedAggregationService, WeatherService, StockService, SocialIntegrationService
- MarketplaceService, DirectoryService, ProfileService
- ClickTrackingService, FeatureFlagService, RateLimitService
- StorageGateway, AiTaggingService, ScreenshotService
- JobOrchestrator, OpsAnalyticsPortal

**Worker Pod (Async Job Handlers):**
- Queue families: DEFAULT, HIGH, LOW, BULK, SCREENSHOT
- Color-coded by priority/purpose
- Job handlers for feed refresh, weather, stocks, AI tagging, screenshots, listings, etc.

**Data Stores:**
- PostgreSQL 17 + PostGIS (users, widgets, listings, directory, jobs, configs)
- Elasticsearch 8 (marketplace + directory search indexes)

**Policy Enforcement Points:**
- P1/P9: AuthIdentityService (GDPR + anonymous merge)
- P2/P10: AiTaggingService ($500/month budget ceiling)
- P3: MarketplaceService (Stripe audit trails)
- P4: ScreenshotService (indefinite retention, WebP compression)
- P5/P13: SocialIntegrationService (token storage)
- P6/P11: MarketplaceService (PostGIS geographic queries)
- P7: FeatureFlagService (database-backed cohort rollouts)
- P8: ExperienceShell TypeScript build integration (frontend-maven-plugin, React islands)
- P12: ScreenshotService + SCREENSHOT queue (dedicated workers, Puppeteer pool)
- P14: ClickTrackingService (consent gating, 90-day purge)

**Deployment Characteristics:**
- Kubernetes (k3s cluster) with multi-replica experience pods and dedicated worker pods
- Local dev: docker-compose for dependencies
- Beta/prod: feature flag differentiation, Kubernetes secrets for credentials

### Entity Relationship Diagram (`erd.puml`)

**Purpose:** Comprehensive database schema covering all 40+ core tables with relationships, indexes, partitioning, and compliance annotations.

**Key Elements:**

**Core Domains:**
- **Users & Auth:** users, user_sessions, user_consents, user_profiles, admin_roles
- **Feed Aggregation:** rss_sources, feed_items, user_feed_subscriptions, ai_usage_tracking
- **Widget Caching:** weather_cache, stock_quotes, social_tokens, social_posts
- **Marketplace:** marketplace_categories, marketplace_listings, marketplace_images, marketplace_messages, payment_transactions, payment_refunds
- **Directory:** directory_categories, directory_sites, directory_site_categories, directory_votes, directory_rankings, directory_screenshot_versions
- **Infrastructure:** delayed_jobs, feature_flags, rate_limit_rules, rate_limit_violations
- **Analytics:** link_clicks, analytics_rollups
- **I18n Placeholders:** i18n_translations, geo_regions

**Technical Annotations:**
- **[PART]:** Partitioned tables (daily/monthly) for data lifecycle management
- **[TTL]:** Time-based retention policies (30/90 days, indefinite)
- **[GEO]:** PostGIS geographic indexes (GIST) for radius filtering (P6/P11)
- **[FTS]:** Elasticsearch full-text search indexes (Hibernate Search)
- **[JSONB]:** JSONB columns for flexible data storage (widget layouts, metadata, etc.)
- **[Px]:** Policy enforcement points (P1-P14) mapped to tables

**Companion Document:**
- **[ERD Guide](erd-guide.md):** 12-section deep-dive covering table purposes, indexing strategies, retention policies, policy mappings, performance considerations, and migration strategy

**Reference:** Derived from container.puml services and Section 2 data model requirements; supports MyBatis migration implementation in I1.

### Job Dispatch Sequence Diagram (`job-dispatch-sequence.puml`)

**Purpose:** Illustrates the async job lifecycle from enqueue to completion/retry, including distributed locking, retry backoff, and policy enforcement.

**Key Elements:**
- **Job Enqueue Flow:** Client triggers operation → business logic enqueues job with JSONB payload → database INSERT
- **Worker Polling:** Quarkus `@Scheduled` methods poll each queue family at different cadences (5s-60s)
- **Distributed Locking:** `SELECT ... FOR UPDATE SKIP LOCKED` ensures lock-free coordination across pods
- **Retry Logic:** Exponential backoff with jitter (2^attempt × 30s × [0.75-1.25])
- **P12 Enforcement:** SCREENSHOT queue semaphore acquisition (3 concurrent workers max)
- **Telemetry:** OpenTelemetry span attributes (job.id, job.type, job.queue, job.attempt)
- **Escalation:** PagerDuty/Slack/Email notifications when max attempts exceeded

**Companion Document:**
- **[Async Workload Strategy](../ops/async-workloads.md):** Comprehensive documentation of queue strategy, retry policies, concurrency controls, escalation paths, and handler implementation guide

**Policy References:**
- **P7:** Unified job orchestration framework
- **P10:** AI tagging budget enforcement ($500/month ceiling)
- **P12:** SCREENSHOT queue concurrency limits (semaphore-based)

**Reference:** Implements job dispatch strategy from container.puml Worker Pod specification; supports DelayedJobService implementation in I1.

## Rendering

### Online Viewers
- [PlantUML Web Server](https://www.plantuml.com/plantuml/uml/)
- [PlantText](https://www.planttext.com/)

### IDE Plugins
- **VS Code:** PlantUML extension
- **IntelliJ IDEA:** PlantUML integration plugin
- **Eclipse:** PlantUML plugin

### Command Line
```bash
# Install PlantUML (requires Java)
brew install plantuml  # macOS
apt install plantuml   # Ubuntu/Debian

# Generate PNG
plantuml docs/diagrams/context.puml
plantuml docs/diagrams/container.puml
plantuml docs/diagrams/erd.puml
plantuml docs/diagrams/job-dispatch-sequence.puml

# Generate SVG
plantuml -tsvg docs/diagrams/context.puml
plantuml -tsvg docs/diagrams/container.puml
plantuml -tsvg docs/diagrams/erd.puml
plantuml -tsvg docs/diagrams/job-dispatch-sequence.puml
```

## Validation Checklist

### System Context & Container Diagrams
- [x] PlantUML syntax valid (@startuml/@enduml tags present)
- [x] All policies P1-P14 annotated at relevant boundaries
- [x] Queue families (DEFAULT/HIGH/LOW/BULK/SCREENSHOT) color-coded and documented
- [x] Kubernetes deployment pods (experience pod, worker pod) shown
- [x] External integrations match pom.xml dependencies
- [x] Component names align with Blueprint component list
- [x] Legends explain policy abbreviations and queue colors
- [x] README.md updated with diagram references

### Entity Relationship Diagram
- [x] PlantUML syntax valid with entity relationship notation
- [x] All mandated tables present (40+ core tables from task description)
- [x] JSONB fields annotated with purpose
- [x] Partition keys specified (daily/monthly partitions)
- [x] Indexes documented (B-tree, GIST, partial, covering)
- [x] Retention policies linked to policies (TTL annotations)
- [x] PostGIS geographic columns marked with [GEO] annotation
- [x] Elasticsearch indexes marked with [FTS] annotation
- [x] Policy references (P1-P14) mapped to relevant tables
- [x] Relationships (foreign keys) connect domain entities
- [x] Future expansion placeholders (i18n, geo_regions) included
- [x] Companion erd-guide.md with comprehensive explanations

### Job Dispatch Sequence Diagram
- [x] PlantUML syntax valid with sequence diagram notation
- [x] Job enqueue flow documented (client → logic → database)
- [x] Worker polling strategy illustrated (per-queue cadence)
- [x] Distributed locking via FOR UPDATE SKIP LOCKED shown
- [x] Retry logic with exponential backoff + jitter explained
- [x] P12 SCREENSHOT semaphore acquisition flow detailed
- [x] OpenTelemetry span attributes documented
- [x] Escalation paths for failed jobs (PagerDuty/Slack/Email)
- [x] Companion async-workloads.md operational guide
- [x] Cross-references to JobQueue/JobType/JobHandler/DelayedJobService code

## Maintenance

When updating diagrams:
1. Keep policy annotations synchronized with `.codemachine/artifacts/architecture/01_Blueprint_Foundation.md`
2. Update queue routing when new job types are added
3. Add new external integrations with protocol/policy annotations
4. Regenerate exported images (PNG/SVG) if sharing outside the codebase
5. Cross-reference with ADRs when toolchain or deployment changes occur
6. When adding new tables to ERD:
   - Add indexes for foreign keys and common query patterns
   - Specify partitioning strategy if high-volume writes
   - Define retention policy (TTL or indefinite)
   - Map to relevant policies (P1-P14)
   - Update erd-guide.md with table purpose and features
7. Keep ERD synchronized with MyBatis migration scripts

## References

- [ADR 0001: Quarkus Toolchain Upgrade](../adr/0001-quarkus-toolchain-upgrade.md)
- [C4 Model Documentation](https://c4model.com/)
- [PlantUML C4 Library](https://github.com/plantuml-stdlib/C4-PlantUML)
