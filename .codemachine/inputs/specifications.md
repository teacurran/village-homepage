# objective

This is a half finished quarkus applicaiton with a lot of errors.  tonights task is to refactor the app and fix all knowna nd unknown errors.

## Known issues:
1. The project contains TODO comments where logic needs to be impletmented (refer to .codemachine-2/inputs/specifications.md for original feature specification)
2. Named queries are refereced but never defined. for example this bit of code is using a constant that appears to define a named query, but is trying to use it as if the constant contains JPQL. `return find("#" + QUERY_FIND_BY_URL + " WHERE url = :url", Parameters.with("url", url)).firstResultOptional();` the constnat should be used to defined a named query, and the named query MUST be defined in a @NamedQuery annotation on the entity class so it gets validated at startup.  If JPQL concationation is required for sorting (not supported in Named queries) then the named query should be defined using a constnat for the JPQL portion.  the JPQL constnat then can be used to create a dynamic query, the portion used in the named query will benifit from validation at startup.
3. All finder methods that call named queries shoudl be defined as Static methods on the entity class.
4. Unit tests must cover 95% of all lines and code branches. Double check to make sure all unit tests make sense and validate correct logic according to the goals of the application.

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

## Refactoring Decision Responses

Based on the Specification Review (`.codemachine/artifacts/requirements/00_Specification_Review.md`), the following decisions have been made:

### Decision 1: TODO Implementation Strategy

**Selected: Option B - Complete Implementation**

All 65+ TODO comments must be fully implemented before v1 release. This includes:
- AI service integration (AiTaggingService, AiCategorizationService, FraudDetectionService)
- Email notification system (GDPR, ban notifications, listing reminders)
- OAuth flow completion and social token refresh logic
- WebP image conversion
- Advanced notifications (moderator alerts, budget warnings)
- Feature flag logging integration
- Rate limiting IP extraction and bucket tracking
- All other incomplete business logic

No TODOs are deferred to v2.

### Decision 2: Test Coverage Strategy

**Selected: Option B - Comprehensive Coverage with Minimal Mocking**

Requirements:
- **95% line coverage** across all packages
- **95% branch coverage** across all packages
- **Minimal or no mocking** - tests should use real implementations where possible
- Use `@QuarkusTest` with test containers for database integration
- Use WireMock or similar only for external API boundaries (Alpha Vantage, Open-Meteo, Meta Graph API)
- Entity finders, services, and job handlers must be tested with real database interactions
- Qute template rendering issues must be resolved (noted in GoodSitesResourceTest)

Test approach:
1. Prefer integration tests over unit tests with mocks
2. Use `@TestTransaction` for database isolation
3. Use embedded/containerized PostgreSQL for realistic query validation
4. Mock only at system boundaries (external HTTP APIs)
5. Parameterized tests for edge cases and boundary values

### DRY Principles for Tests (Critical)

**Minimizing duplication in test code is mandatory.** Apply these principles rigorously:

1. **Constants for Repeated Strings**
   - Define constants for any string used more than once (URLs, error messages, test data values, JSON paths)
   - Place shared constants in a `TestConstants` class or as static fields in test base classes
   - Example: `private static final String VALID_EMAIL = "test@example.com";`

2. **Parameterized Tests for Similar Scenarios**
   - Use `@ParameterizedTest` with `@MethodSource`, `@CsvSource`, or `@ValueSource` for tests that vary only by input/output
   - Never copy-paste a test method to test different values
   - Example: Testing validation with valid/invalid emails, boundary values, edge cases

3. **Shared Test Fixtures**
   - Extract common setup into `@BeforeEach` methods or test base classes
   - Use factory methods for creating test entities (e.g., `createTestUser()`, `createTestListing()`)
   - Share WireMock stubs via helper methods

4. **Test Base Classes**
   - Create abstract base classes for common test infrastructure (e.g., `BaseIntegrationTest`, `BaseResourceTest`)
   - Centralize `@QuarkusTest` configuration, test containers, and common assertions

5. **Custom Assertions**
   - Extract repeated assertion patterns into reusable assertion methods
   - Example: `assertValidationError(response, "email", "must not be blank")`

6. **No Magic Numbers or Strings**
   - Every literal value that appears more than once must be a named constant
   - Test data builders preferred over inline object construction

### Decision 3: Named Query Architecture

**Selected: Option B - Hybrid Approach**

- Use `@NamedQuery` annotations for all simple lookup queries (validated at startup)
- For queries requiring dynamic sorting/filtering, define the static JPQL portion as a constant
- The JPQL constant can be used both in `@NamedQuery` (for validation) and composed with dynamic parts at runtime
- Never use raw string JPQL without a constant

Example pattern for dynamic queries:
```java
// Static portion validated at startup via @NamedQuery
public static final String JPQL_FIND_ACTIVE = "SELECT l FROM MarketplaceListing l WHERE l.status = 'ACTIVE'";
public static final String QUERY_FIND_ACTIVE = "MarketplaceListing.findActive";

@NamedQuery(name = QUERY_FIND_ACTIVE, query = JPQL_FIND_ACTIVE)

// Dynamic sorting uses the validated JPQL constant
public static List<MarketplaceListing> findActiveSorted(String sortField, String sortDir) {
    return find(JPQL_FIND_ACTIVE + " ORDER BY l." + sortField + " " + sortDir).list();
}
```

### Decision 4: AI Service Configuration

**Selected: Option A - Claude (Anthropic) with Graceful Degradation**

- Primary AI provider: Claude via LangChain4j
- No fallback provider configured
- Graceful degradation strategy:
  - If AI service unavailable, disable AI-dependent features (tagging, categorization, fraud detection)
  - Log warnings but do not fail user-facing operations
  - Queue failed AI jobs for retry with exponential backoff
  - Admin dashboard shows AI service health status
- Budget enforcement per P2 policy remains active regardless of availability

### Decision 5: Email Infrastructure

**Selected: Option D - SMTP Relay (Self-hosted)**

- Use self-hosted SMTP relay (existing infrastructure)
- Delivery guarantee: Best-effort with comprehensive logging
- All email sends logged to database for audit trail
- Failed sends logged with error details for manual review
- No webhook confirmation required
- Delayed job retry for transient failures (network timeouts)
- Email templates via Qute (consistent with village-calendar)

### Decision 6: OAuth Token Encryption

**Selected: Option D - Defer to v2**

- For v1, store OAuth tokens (Meta access/refresh tokens) as plaintext in database
- Document this as a known security limitation for v1
- Token encryption via application-level AES or Vault integration planned for v2
- Mitigations for v1:
  - Database access restricted to application service account
  - Tokens not exposed via API responses
  - Audit logging for token access

### Decision 7: WebP Image Processing

**Selected: Option A - ImageIO with WebP Plugin**

- Image uploads stored immediately in original format
- Delayed job queued for WebP conversion (BULK queue)
- Original image served until WebP version is available
- Once converted, CDN serves WebP with fallback to original for unsupported browsers
- Job handler: `ListingImageProcessingJobHandler`
- **Conversion library: sejda-imageio WebP plugin (pure Java, no native dependencies)**
- Quality parameter: 85%
- Thumbnail generation: 320x200
- Full image: 1280x800
- Error handling with retry logic for transient conversion failures

---

## Review Checkpoint Decisions (2026-01-22)

Based on the Specification Review checkpoint, the following additional decisions have been confirmed:

### Decision 8: AI Service Implementation Strategy

**Selected: Option A - Full Implementation**

Complete all AI service implementations before v1 release:
- Configure Anthropic API key via secure environment variable or Vault
- Implement all AI services: AiTaggingService, AiCategorizationService, FraudDetectionService
- LangChain4j ChatLanguageModel configuration in application.properties
- Batch processing logic per P2 specification
- Integration with AiTaggingBudgetService for cost enforcement ($500/month budget)
- Comprehensive error handling with queue retry logic and exponential backoff
- Remove all stub/TODO methods from AI services

### Decision 9: Email Infrastructure - Enhanced Implementation

**Selected: Option C - Enhanced with Tracking (Tier 3)**

Full email implementation with delivery analytics:
- Implement all 15+ email templates specified across features F1-F13
- Quarkus Mailer extension configuration for self-hosted SMTP relay
- Qute template structure in `src/main/resources/templates/email/`
- Database audit logging for all email sends
- **Email delivery tracking** - track sent/delivered/bounced status
- **Bounce handling** - automatically flag bad email addresses
- **Engagement analytics integration** - open/click tracking for future optimization
- Delayed job retry for transient SMTP failures

Email templates required:
- GDPR export ready notification
- GDPR deletion confirmation
- Payment confirmation (marketplace)
- Listing posted confirmation
- Listing expiring reminder
- Listing expired notification
- Ban notification
- Moderator alert (new item pending review)
- Budget warning (AI spend approaching limit)
- Social token expiry notice
- Password reset (if applicable)
- Welcome email (on first login)

### Decision 10: OAuth Integration

**Selected: Option A - Full OAuth Implementation**

Complete OAuth integration for all three providers:
- **Google OAuth** - Quarkus OIDC extension configuration
- **Facebook OAuth** - Meta Graph API integration
- **Apple Sign-In** - Full implementation (not deferred)
- OAuth client credentials stored in environment variables or Vault
- JWT claim extraction utility in `AuthIdentityService.getCurrentUserId()`
- Replace all "TODO: Extract user ID from JWT/session" with SecurityIdentity injection
- Token refresh interceptor for expiring access tokens
- Account merge flow when anonymous user authenticates (per P1 specification)
- Audit logging with user ID population for authenticated requests

### Decision 11: Geographic Data Import (PostGIS)

**Selected: Option C - Seed Data Job**

Implement geographic data population via delayed job:
- Create `GeoDataSeedJobHandler` that runs once at first startup
- Populate `geo_cities` table from dr5hn/countries-states-cities-database
- **Scope: US and Canada only** (approximately 40,000 cities) for v1
- PostGIS extension enablement: `CREATE EXTENSION IF NOT EXISTS postgis;`
- Geographic column: `geography(Point, 4326)` for latitude/longitude
- GIST spatial index for efficient radius queries
- MarketplaceSearchService integration with `ST_DWithin` queries
- Performance target: p95 < 100ms for radius searches (5mi to 250mi)
- Job is re-runnable and idempotent (skip if data already exists)

### Decision 12: Test Infrastructure (Confirmed)

**Selected: Option A - Test-First Refactoring**

Establish comprehensive test infrastructure before implementing features:
- JaCoCo Maven plugin with **95% line/branch coverage enforcement** in build
- `BaseIntegrationTest` abstract class with `@QuarkusTest` and PostgreSQL 17 + PostGIS test containers
- `TestConstants` class for all repeated test data strings
- `TestFixtures` class with factory methods: `createTestUser()`, `createTestListing()`, `createTestFeedItem()`, etc.
- WireMock integration for external API boundaries only
- Parameterized tests for validation logic and edge cases
- Custom assertion methods for repeated validation patterns
- Zero tolerance for test code duplication (DRY principles)
- Resolve Qute template rendering issues in GoodSitesResourceTest

### Decision 13: Named Query Architecture (Confirmed)

**Selected: Option A - Full Compliance Refactor**

Refactor all 37 entity models to comply with named query hybrid approach:
- All entities must have `@NamedQuery` annotations at class level
- Named query constants: `public static final String QUERY_FIND_BY_X = "Entity.findByX";`
- JPQL constants for dynamic queries: `public static final String JPQL_FIND_ACTIVE = "SELECT e FROM Entity e WHERE...";`
- Dynamic queries compose using validated JPQL constants as base
- Never use raw string JPQL without a named constant
- All named queries must validate successfully at application startup
- Estimated effort: 4-6 hours across all entities

---

## Implementation Priority Order

Based on dependencies and risk, implement in this order:

1. **Test Infrastructure** (Decision 12) - Must be first to validate all other changes
2. **Named Query Refactoring** (Decision 13) - Enables startup validation
3. **OAuth Integration** (Decision 10) - Unblocks user-dependent features
4. **AI Service Implementation** (Decision 8) - High business value
5. **Email Infrastructure** (Decision 9) - Required for notifications
6. **Geographic Data Seed Job** (Decision 11) - Required for marketplace search
7. **WebP Image Processing** (Decision 7) - Lower priority, can run in background

