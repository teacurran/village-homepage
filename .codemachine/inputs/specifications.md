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
- **PrimeUI widgets** for controls and components
- **gridstack.js** for drag-and-drop widget layout
- If a control is not available in PrimeUI, use the most popular compatible alternative
- **No Quinoa, No Vue.js** - this is a server-rendered application

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
- When user logs in via OAuth, merge anonymous account preferences into authenticated account
- Delete orphaned anonymous accounts after 30 days of inactivity

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
- Handle token refresh automatically
- Graceful degradation if token expires

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

### F11: Marketplace (Classifieds)

A Craigslist-style classifieds marketplace with location-based filtering. Users must be logged in to post ads. UI uses text links like Craigslist but with a more modern appearance.

#### F11.1: Geographic Data

Import location data from **dr5hn/countries-states-cities-database**:
- Source: https://github.com/dr5hn/countries-states-cities-database
- Use PostgreSQL export format
- 250 countries, 5,299 states, 153,765 cities
- Each city includes latitude, longitude, timezone

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

#### F11.2: Location-Based Filtering

- Detect user location via browser geolocation API (with permission)
- Allow manual city/ZIP entry as fallback
- Display location context: "Boston Area", "Greater New York", etc.
- Radius filter options: 5mi, 10mi, 25mi, 50mi, 100mi, 250mi, Any
- Use PostGIS or Haversine formula for distance calculations

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

#### F11.3: Category Hierarchy (Craigslist-style)

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

#### F11.4: Listing Creation (Authenticated Users Only)

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

#### F11.5: Listing Images

- Store images in object storage (S3/MinIO)
- On upload: validate, resize, generate thumbnails
- Image sizes:
  - Thumbnail: 150x150 (cropped square)
  - List view: 300x225
  - Detail view: 1200x900 (max, maintain aspect)
- Serve via CDN with signed URLs
- Delete images when listing expires/removed

#### F11.6: Contact System (No Payments)

- **Email masking**: Hide real email addresses
  - Generate masked address: `listing-abc123@reply.homepage.villagecompute.com`
  - Relay messages through platform
  - Track message counts for spam detection
- **Phone display**: Optional, shown directly if provided
- **No in-app messaging MVP**: Email relay only

#### F11.7: Listing Lifecycle

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

#### F11.8: Listing Fees & Monetization

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

#### F11.9: Flagging & Moderation

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

#### F11.10: Search & Browse

- Full-text search on title and description (PostgreSQL tsvector)
- Filter by:
  - Category (with subcategory drill-down)
  - Location + radius
  - Price range
  - Has images
  - Posted date (today, week, month)
- Sort by: Newest, Price (low/high), Distance
- Pagination: 25 listings per page

#### F11.11: UI Design (Modern Craigslist)

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

#### F11.12: Marketplace Data Models

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

#### F11.13: Background Jobs for Marketplace

| Job Handler | Queue | Interval |
|-------------|-------|----------|
| `ListingExpirationJobHandler` | DEFAULT | Daily |
| `ListingExpirationReminderJobHandler` | LOW | Daily |
| `FlaggedListingReviewJobHandler` | DEFAULT | Hourly |
| `MessageRelayJobHandler` | HIGH | On-demand |
| `ListingImageProcessingJobHandler` | BULK | On-demand |

---

### F12: Good Sites (Web Directory)

A hand-curated web directory inspired by the classic Yahoo Directory and DMOZ. Users browse hierarchical categories to discover quality websites. Each link has a screenshot and description, with Reddit-style voting to surface the best sites per category.

#### F12.1: Category Hierarchy

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

#### F12.2: Site Links

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

#### F12.3: Screenshot Capture Service

- **Self-hosted Puppeteer/Playwright** service for screenshot capture
- Capture settings:
  - Viewport: 1280x800
  - Full page: No (above-fold only)
  - Wait for network idle
  - Timeout: 30 seconds
- Store screenshots in S3/MinIO
- Generate sizes:
  - Thumbnail: 320x200 (for grid view)
  - Full: 1280x800 (for detail view)
- Fallback to OpenGraph image if screenshot fails
- Re-capture screenshots every 30 days or on-demand

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

#### F12.4: Voting System (Reddit-style)

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

#### F12.5: Bubbling Up to Parent Categories

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

#### F12.6: User Submissions with Karma-Based Trust

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

#### F12.7: Moderation System

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

#### F12.8: Link Health Checking

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

#### F12.9: Browse & Search

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

#### F12.10: Site Detail Page

- Large screenshot
- Title, description, URL (clickable)
- Category breadcrumb (which category this view is from)
- Other categories this site is in (with their scores)
- Vote buttons (if logged in)
- Flag button
- "Visit Site" button (external link)
- Submitted by, submission date
- Related sites (same category, similar score)

#### F12.11: UI Design

- Clean, modern grid layout for categories
- Card-based display for sites with screenshot thumbnails
- Category icons for visual navigation
- Breadcrumb navigation
- Score displayed prominently (+123 or -5)
- Mobile-responsive grid (3 cols → 2 cols → 1 col)

#### F12.12: Good Sites Data Models Summary

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

#### F12.13: Background Jobs for Good Sites

| Job Handler | Queue | Interval |
|-------------|-------|----------|
| `ScreenshotCaptureJobHandler` | BULK | On-demand |
| `MetadataRefreshJobHandler` | LOW | On-demand |
| `LinkHealthCheckJobHandler` | LOW | Weekly |
| `ScreenshotRefreshJobHandler` | BULK | Monthly |
| `RankRecalculationJobHandler` | DEFAULT | Hourly |

#### F12.14: Bulk Import & AI Auto-Categorization

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

### F13: Cross-Cutting Concerns

#### F13.1: Caching Strategy

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

#### F13.2: Rate Limiting

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

#### F13.3: Email System

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

#### F13.4: Feature Flags

Admin-controlled feature flags for gradual rollout.

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

        // Consistent hash for user
        int hash = Math.abs(userId.hashCode() % 100);
        return hash < flag.rolloutPercentage;
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

#### F13.5: SEO

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

#### F13.6: Screenshot Service (jvppeteer)

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

#### F13.7: Object Storage (Cloudflare R2)

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

#### F13.8: Search (Hibernate Search + Elasticsearch)

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
- PostgreSQL 17 (port 5432)
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
