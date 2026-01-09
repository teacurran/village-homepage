# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Village Homepage is a customizable homepage portal SaaS (similar to Yahoo/Bing Homepage) built with Java Quarkus. Users can view aggregated news, weather, stocks, and social media content. Anonymous users see a default homepage; logged-in users get full customization with drag-and-drop widgets.

## Build Commands

```bash
# Compile
./mvnw compile

# Run tests
./mvnw test

# Run tests with coverage report
./mvnw test jacoco:report

# Apply code formatting
./mvnw spotless:apply

# Run development server
./mvnw quarkus:dev

# Run migrations
cd migrations && mvn migration:up -Dmigration.env=development

# Build container image
./mvnw package -Dquarkus.container-image.build=true
```

## Technology Stack

- **Java 21** (LTS)
- **Quarkus** framework
- **Maven** build system
- **PostgreSQL 17** database
- **MyBatis Migrations** for schema changes
- **Qute templates** with **PrimeUI widgets** and **gridstack.js** for frontend
- **LangChain4j** for AI integration (model-agnostic)
- **Traditional REST APIs** (not GraphQL)
- **Jib** for container image building

### External APIs
- **Alpha Vantage** - Stock market data
- **Open-Meteo** - Weather (international)
- **National Weather Service** - Weather (US)
- **Meta Graph API** - Instagram/Facebook integration

## Project Standards

This project follows the VillageCompute Java Project Standards. See `../village-storefront/docs/java-project-standards.adoc` for the complete reference.

### Database Access Pattern

All database access is via **static methods on model entities** (Panache ActiveRecord pattern). Do NOT create separate repository classes.

```java
@Entity
public class User extends PanacheEntityBase {

    public static final String QUERY_FIND_BY_EMAIL = "User.findByEmail";

    public static Optional<User> findByEmail(String email) {
        return find("#" + QUERY_FIND_BY_EMAIL,
                    Parameters.with("email", email))
               .firstResultOptional();
    }
}
```

### Delayed Job Pattern

Use the delayed job pattern for all async operations (feed refresh, AI tagging, social sync). See `../village-calendar/src/main/java/villagecompute/calendar/services/DelayedJobService.java`.

### Code Standards

- All control flow statements must use braces
- All exceptions extend `RuntimeException`
- All JSON marshalled through Type classes (no direct `JsonNode` traversal)
- Type names end with `Type` suffix
- Line length: 120 characters
- No Lombok - use Java records for immutable data

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
├── services/              # Business logic
└── util/                  # Utilities
```

## Key Features

### Authentication
- OAuth: Google, Facebook, Apple
- Anonymous accounts (upgrade on login)
- Admin roles: super_admin, support, ops, read_only
- Bootstrap URL for first superuser creation

### Homepage Widgets
- **gridstack.js** for drag-and-drop layout
- Widget types: news_feed, weather, stocks, social_feed, rss_feed, quick_links, search_bar
- Layout persisted as JSONB per user

### Content Sources
- RSS feeds (system-managed + user custom)
- AI tagging via LangChain4j for topic categorization
- Weather from Open-Meteo (international) + NWS (US)
- Stocks from Alpha Vantage
- Social from Instagram/Facebook

### Marketplace (Classifieds)
- Craigslist-style classifieds with location-based filtering
- **Login required** to post listings (not anonymous)
- Geographic data from dr5hn/countries-states-cities-database (153K+ cities)
- Radius filtering: 5mi to 250mi or Any
- Categories: For Sale, Housing, Jobs, Services, Community (with subcategories)
- Up to 12 images per listing (S3/MinIO storage)
- Email masking for contact (relay through platform)
- Monetization: Free listings + paid featured/bump options via Stripe
- Modern text-link UI (like Craigslist but cleaner)

### Good Sites (Web Directory)
- Hand-curated web directory (like classic Yahoo Directory / DMOZ)
- Hierarchical categories with unlimited depth
- Sites can exist in multiple categories with separate votes per category
- Reddit-style up/down voting; **login required to vote**
- Auto-captured screenshots via self-hosted Puppeteer/Playwright
- OpenGraph metadata extraction (title, description, image)
- Karma-based trust: untrusted users → moderation queue, trusted users → auto-publish
- Category moderators can be assigned by super admins
- Top-ranked links bubble up to parent categories
- Link health checking (detect dead links)

### Background Jobs
| Job | Queue | Interval |
|-----|-------|----------|
| RSS Refresh | DEFAULT | 15min-daily (configurable) |
| Weather Refresh | DEFAULT | 1 hour |
| Stock Refresh | HIGH | 5 min (market hours) |
| Social Refresh | LOW | 30 min |
| AI Tagging | BULK | On-demand |
| Listing Expiration | DEFAULT | Daily |
| Message Relay | HIGH | On-demand |
| Image Processing | BULK | On-demand |
| Screenshot Capture | BULK | On-demand |
| Link Health Check | LOW | Weekly |
| Rank Recalculation | DEFAULT | Hourly |
| Inbound Email | DEFAULT | 1 minute |
| Sitemap Generation | LOW | Daily |

## Cross-Cutting Concerns

### Caching
- Hibernate 2nd Level Cache (Caffeine) for entities
- Query cache for common lookups

### Rate Limiting
- Configurable per action type and user tier
- Admin dashboard for monitoring and adjustment
- IP-based blocking for abuse

### Feature Flags
- **FeatureFlagService** - Application-scoped service for flag evaluation and management
- **Stable cohort assignment** - MD5 hash of `flagKey + ":" + subjectId` for deterministic rollout
- **Evaluation priority**: master kill switch → whitelist → rollout percentage → stable cohort
- **Whitelist support** - Force-enable flags for specific user IDs or session hashes
- **Analytics toggle** - Consent-aware evaluation logging (Policy P14 compliance)
- **Audit trail** - All mutations logged to `feature_flag_audit` with before/after state
- **Partitioned evaluation logs** - 90-day retention via monthly partitions
- **Admin endpoints**:
  - `GET /admin/api/feature-flags` - List all flags
  - `GET /admin/api/feature-flags/{key}` - Get single flag
  - `PATCH /admin/api/feature-flags/{key}` - Update flag configuration
- **Initial flags**: `stocks_widget`, `social_integration`, `promoted_listings` (all disabled by default)
- **Database schema**: `feature_flags`, `feature_flag_audit`, `feature_flag_evaluations` (partitioned)

### Search
- **Hibernate Search** with **Elasticsearch** backend
- Full-text search on listings and directory sites
- Geo-spatial filtering for marketplace

### Screenshot Service
- **jvppeteer** (Java Puppeteer wrapper) in same container
- Chromium installed in Docker image
- Captures 1280x800 viewport screenshots

### Email
- Outbound: Qute templates (like village-calendar)
- Inbound: IMAP polling for marketplace reply relay
- Address format: `homepage-{type}-{id}@villagecompute.com`

### Object Storage
- **Cloudflare R2** (S3-compatible)
- Buckets: screenshots, listings
- CDN for public assets

### SEO
- Meta tags, Open Graph, Twitter Cards
- JSON-LD structured data
- Sitemaps (auto-generated daily)
- robots.txt

## Deployment

- **Production**: homepage.villagecompute.com
- **Beta**: homepage-beta.villagecompute.com
- **Container**: Jib (quarkus-container-image-jib)
- **Target**: k3s cluster
- **Infrastructure**: `../villagecompute` repository

## Code Quality

- **Coverage**: 80% line and branch coverage
- **Formatting**: Spotless with Eclipse formatter
- **Quality Gate**: SonarCloud (0 bugs, 0 vulnerabilities)
