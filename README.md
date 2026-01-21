# Village Homepage

> Customizable homepage portal SaaS with widgets, marketplace, and web directory

![Build](https://github.com/VillageCompute/village-homepage/actions/workflows/build.yml/badge.svg)
[![Coverage](https://img.shields.io/badge/coverage-80%25-brightgreen)]()
[![Code Quality](https://img.shields.io/badge/quality-A-brightgreen)]()

Village Homepage is a Quarkus-based SaaS platform that provides users with a personalized homepage featuring news aggregation, weather, stocks, social media feeds, a classified marketplace, and a curated web directory.

---

## Prerequisites

### Required

- **Java 21 JDK** (LTS)
    - Amazon Corretto 21, OpenJDK 21, or Eclipse Temurin 21
    - Verify: `java -version` should show version 21.x.x

- **Maven 3.9.x** (via Maven Wrapper)
    - The project includes Maven Wrapper (`./mvnw`), no separate installation needed
    - Verify: `./mvnw --version` should show Maven 3.9.9

### Optional (for frontend development)

- **Node.js 20.10.0** (LTS)
    - Only required if running frontend builds independently
    - Maven will download Node/npm automatically via `frontend-maven-plugin`
    - Verify: `node --version` should show v20.10.0

### Development Services (Docker Compose)

The following services are required for local development and are provided via Docker Compose:

- **PostgreSQL 17 with PostGIS extension** - Primary database with geospatial support
- **Elasticsearch 8** - Full-text search and geo-spatial indexing
- **MinIO** - S3-compatible object storage (local R2 equivalent)
- **Mailpit** - SMTP test server with web UI
- **Jaeger** - Distributed tracing and observability

**Required for development:**
- Docker Engine 20.10+
- Docker Compose 2.0+
- At least 4GB RAM available for Docker
- **Apple Silicon (ARM64) users:** Enable Rosetta 2 emulation in Docker Desktop for best compatibility. See [docs/ops/arm64-compatibility.md](docs/ops/arm64-compatibility.md) for details.

---

## Quick Start

### 1. Clone the Repository

```bash
git clone https://github.com/VillageCompute/village-homepage.git
cd village-homepage
```

### 2. Verify Prerequisites

```bash
# Check Java version (must be 21)
java -version

# Check Maven wrapper
./mvnw --version

# Check Docker and Docker Compose
docker --version
docker-compose --version
```

### 3. Set Up Environment

```bash
# Copy environment template
cp .env.example .env

# (Optional) Edit .env for custom configuration
# Most developers can use defaults for local development
```

### 4. Start Development Services

```bash
# Start all backing services (Postgres, Elasticsearch, MinIO, Mailpit, Jaeger)
docker-compose up -d

# Verify all services are healthy (may take 30-60 seconds)
docker-compose ps

# Expected output: All services should show "Up (healthy)"
```

**Service Access Points:**
- **PostgreSQL:** localhost:5432 (user: `village`, password: `village_dev_pass`)
- **Elasticsearch:** http://localhost:9200
- **MinIO Console:** http://localhost:9001 (user: `minioadmin`, password: `minioadmin`)
- **Mailpit UI:** http://localhost:8025
- **Jaeger UI:** http://localhost:16686

### 5. Initialize Database

```bash
# Run database migrations
cd migrations
set -a && source ../.env && set +a
mvn migration:up -Dmigration.env=development
cd ..

# Load geographic dataset (153K+ cities, ~5-10 minutes)
./scripts/load-geo-data.sh

# Verify geo data loaded successfully
docker-compose exec postgres psql -U village -d village_homepage -c "SELECT COUNT(*) FROM cities;"
```

### 6. Run Development Server

```bash
# Start Quarkus in dev mode (hot reload enabled)
./mvnw quarkus:dev
```

The application will be available at:
- **Homepage:** http://localhost:8080
- **Dev UI:** http://localhost:8080/q/dev

**See [docs/ops/dev-services.md](docs/ops/dev-services.md) for detailed service management, troubleshooting, and operational procedures.**

---

## Build Commands

### Development

```bash
# Start Quarkus dev mode (hot reload for Java + frontend)
./mvnw quarkus:dev

# Compile only (includes TypeScript build)
./mvnw compile

# Clean build artifacts
./mvnw clean
```

### Testing

```bash
# Run all tests
./mvnw test

# Run tests with coverage report
./mvnw test jacoco:report
# Coverage report: target/site/jacoco/index.html

# Run integration tests
./mvnw verify

# Skip tests
./mvnw package -DskipTests
```

### Code Quality

```bash
# Apply code formatting (Eclipse formatter)
./mvnw spotless:apply

# Check formatting (fails build if not formatted)
./mvnw spotless:check

# Run dependency analysis
./mvnw dependency:tree
```

### Packaging

```bash
# Build uber-jar (includes all dependencies)
./mvnw package

# Build container image with Jib
./mvnw package -Dquarkus.container-image.build=true

# Run packaged application
java -jar target/village-homepage-1.0.0-SNAPSHOT-runner.jar
```

### Database Migrations

```bash
# Navigate to migrations directory
cd migrations

# Load environment variables for ${env.*} placeholders
set -a && source ../.env && set +a

# Run pending migrations
mvn migration:up -Dmigration.env=development

# Check migration status
mvn migration:status -Dmigration.env=development

# Create new migration
mvn migration:new -Dmigration.description="add_user_preferences_table"

# Return to project root when finished
cd ..
```

### Feed Management

```bash
# Navigate to migrations directory for seed data
cd migrations

# Load environment variables
set -a && source ../.env && set +a

# Load default RSS sources (TechCrunch, BBC, Reuters, etc.)
psql -h localhost -p 5432 -U village -d village_homepage -f seeds/rss_sources.sql

# Return to project root
cd ..
```

**Default System Feeds:**

The seed script loads 19 curated RSS sources across categories:
- **Technology:** TechCrunch, Hacker News, Ars Technica, The Verge
- **Business & Finance:** Reuters, Financial Times, MarketWatch
- **World News:** BBC World News, The Guardian, Al Jazeera
- **Science & Health:** Scientific American, Nature News, Science Daily
- **Politics:** Politico, The Hill
- **Entertainment:** Entertainment Weekly, Variety
- **Sports:** ESPN, Sports Illustrated

**Admin API Operations:**

Admin-only CRUD operations require `super_admin` role (bootstrap user or granted via admin UI):

```bash
# List all RSS sources
curl -X GET http://localhost:8080/admin/api/feeds/sources \
  -H "Authorization: Bearer <admin_token>"

# Create new system feed
curl -X POST http://localhost:8080/admin/api/feeds/sources \
  -H "Authorization: Bearer <admin_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "New Tech Feed",
    "url": "https://example.com/feed.xml",
    "category": "Technology",
    "refresh_interval_minutes": 60
  }'

# Update feed configuration (e.g., disable broken feed)
curl -X PATCH http://localhost:8080/admin/api/feeds/sources/{id} \
  -H "Authorization: Bearer <admin_token>" \
  -H "Content-Type: application/json" \
  -d '{"is_active": false}'

# Delete feed (cascade deletes items and subscriptions)
curl -X DELETE http://localhost:8080/admin/api/feeds/sources/{id} \
  -H "Authorization: Bearer <admin_token>"
```

**Health Monitoring:**

System feeds are auto-disabled after 5 consecutive fetch errors. Check feed health:

```sql
-- Find disabled feeds with errors
SELECT name, url, error_count, last_error_message, last_fetched_at
FROM rss_sources
WHERE is_active = false AND error_count > 0
ORDER BY last_fetched_at DESC NULLS LAST;

-- Re-enable after fixing (development only - use admin API in production)
UPDATE rss_sources
SET error_count = 0, last_error_message = NULL, is_active = true, updated_at = NOW()
WHERE id = '<source_uuid>';
```

**See [docs/ops/feed-governance.md](docs/ops/feed-governance.md) for detailed operational procedures.**

---

## CI/CD Pipeline

### Overview

The project uses GitHub Actions for continuous integration and deployment. The pipeline automatically runs on every push and pull request to `main` and `develop` branches.

### Pipeline Stages

The CI/CD workflow (`.github/workflows/build.yml`) executes the following stages in sequence:

1. **Plan Compliance Verification (Section 4)**
   - Regenerates `.codemachine/artifacts/tasks` from the iteration plan
   - Fails when plan artifacts drift from `.codemachine/artifacts/plan`
   - Command: `node .codemachine/scripts/extract_tasks.js && git diff --quiet -- .codemachine/artifacts/tasks`

2. **Code Formatting Check (Spotless)**
   - Validates Java code against Eclipse formatter rules
   - Checks XML, YAML, and Markdown formatting
   - Fails fast to ensure consistent code style
   - Command: `./mvnw spotless:check`

3. **Frontend Linting**
   - Runs ESLint on TypeScript/React code
   - Enforces React hooks rules and TypeScript conventions
   - Command: `npm run lint`

4. **TypeScript Type Checking**
   - Validates TypeScript types without emitting output
   - Ensures type safety across React islands
   - Command: `npm run typecheck`

5. **Frontend Build**
   - Compiles TypeScript/React using esbuild
   - Generates production bundles in `assets/js/`
   - Command: `npm run build`

6. **Unit and Integration Tests**
   - Runs Surefire (unit tests) and Failsafe (integration tests)
   - Executes with JaCoCo coverage instrumentation
   - Command: `./mvnw verify -DskipITs=false`
   - Environment: Quarkus test containers for Postgres, Elasticsearch

7. **Coverage Report Generation**
   - Generates JaCoCo XML and HTML reports
   - Enforces 80% line and branch coverage minimums
   - Output: `target/site/jacoco/jacoco.xml`

8. **SonarCloud Scan (Planned)**
   - Static code analysis for bugs, vulnerabilities, code smells
   - Quality gate enforcement (zero critical issues, 80% coverage)
   - Requires: `SONAR_TOKEN` secret configuration

9. **Container Image Build (Jib)**
   - Builds OCI-compliant container image without Docker daemon
   - Validates packaging and dependencies
   - Command: `./mvnw package jib:dockerBuild`
   - Note: Push to registry is commented out until credentials are configured

Section 4 directives call for zero plan drift, ≥80% coverage, and zero critical Sonar issues; the stages above embed those gates plus the plan compliance verification step so every merge honors the iteration blueprint.

### Quality Gates

The following quality gates must pass for a build to succeed:

| Gate | Requirement | Enforcement |
|------|-------------|-------------|
| **Plan Compliance (Section 4)** | `.codemachine/artifacts/tasks` matches the published plan | `node .codemachine/scripts/extract_tasks.js` + `git diff --quiet -- .codemachine/artifacts/tasks` |
| **Code Formatting** | 100% compliance with Spotless rules | Spotless Maven plugin |
| **Frontend Linting** | Zero ESLint errors | npm lint script |
| **Type Safety** | Zero TypeScript errors | tsc --noEmit |
| **Unit Tests** | All tests pass | Maven Surefire |
| **Integration Tests** | All tests pass | Maven Failsafe |
| **Line Coverage** | ≥ 80% | JaCoCo Maven plugin |
| **Branch Coverage** | ≥ 80% | JaCoCo Maven plugin |
| **Sonar Quality Gate** | Zero blocker/critical issues | SonarCloud (planned) |
| **Container Build** | Image builds successfully | Jib Maven plugin |

These gates implement Section 4 quality mandates (P4/P5/P8/P10), ensuring the plan stays synchronized, coverage remains ≥80%, and Sonar blocks merges on critical findings.

### Caching Strategy

The pipeline uses multiple caching layers to optimize build times:

- **Maven Repository Cache:** `~/.m2/repository` keyed by `pom.xml` hash
- **npm Cache:** Automatically cached by `actions/setup-node@v4`
- **Node Installation Cache:** `target/node` keyed by Node version and `pom.xml`

**Expected build times:**
- First run (cold cache): ~8-12 minutes
- Subsequent runs (warm cache): ~4-6 minutes
- PR builds with no dependency changes: ~3-4 minutes

### Local Validation

Before pushing, validate your changes locally to catch issues early:

```bash
# Run full CI validation locally
node .codemachine/scripts/extract_tasks.js  # Section 4 plan compliance
./mvnw spotless:check                      # Code formatting
npm run lint                               # Frontend linting
npm run typecheck                          # TypeScript types
./mvnw verify -DskipITs=false              # Tests with coverage
./mvnw package jib:dockerBuild             # Container build
```

Or use the convenience script:

```bash
# Run the same checks as CI
node tools/test.cjs --integration
# Review `git status` after running the plan script; CI will fail if `.codemachine/artifacts/tasks` diverges from `main`.
```

### Status Badge

The build status badge at the top of this README shows the current state of the `main` branch. Click it to view recent workflow runs and detailed logs.

### Configuration

Pipeline configuration is stored in `.github/workflows/build.yml`. Key environment variables:

- `JAVA_VERSION`: JDK version (21)
- `NODE_VERSION`: Node.js version (20.10.0)
- `MAVEN_CLI_OPTS`: Maven CLI flags (`-B --no-transfer-progress`)

### Future Enhancements

- **SonarCloud Integration:** Enable static analysis with quality gate enforcement
- **Container Registry Push:** Automated push to GitHub Container Registry on main merges
- **Codecov Integration:** Visual coverage reports and PR comments
- **E2E Tests:** Playwright tests for critical user flows
- **Nightly Builds:** Full test suite with performance benchmarks
- **Release Automation:** Automated tagging and changelog generation

---

## Development Workflows

### Daily Development Routine

For typical day-to-day development after initial setup:

```bash
# 1. Start backing services (if not already running)
docker-compose up -d

# 2. Check service health
docker-compose ps

# 3. Start Quarkus development server
./mvnw quarkus:dev

# When finished (optional - can leave services running):
# docker-compose stop
```

**Tip:** Leave Docker Compose services running across sessions to avoid startup delays. They consume minimal resources when idle.

### Single-Terminal Workflow (Default)

Maven handles both backend and frontend builds automatically:

```bash
./mvnw quarkus:dev
```

**What happens:**
- Maven downloads Node/npm to `target/node` (first run only)
- Runs `npm ci` to install dependencies (first run or when package.json changes)
- Runs `npm run build` to compile TypeScript via esbuild
- Bundles React islands from `src/main/resources/META-INF/resources/assets/ts/`
- Outputs production-ready JavaScript to `src/main/resources/META-INF/resources/assets/js/`
- Starts Quarkus with hot reload

**Use this workflow for:**
- Getting started quickly
- CI/CD builds
- Full project compilation
- Backend-focused development

### Two-Terminal Workflow (Faster Frontend Iteration)

For faster frontend development, run builds separately:

**Terminal 1 (Backend):**
```bash
./mvnw quarkus:dev
```

**Terminal 2 (Frontend Watch Mode):**
```bash
npm run watch
```

**What happens in watch mode:**
- esbuild runs in incremental watch mode
- Detects TypeScript file changes in `assets/ts/` directory
- Rebuilds only changed modules (sub-second rebuild times)
- Uses inline source maps for faster debugging
- Outputs to same `assets/js/` directory (Quarkus serves automatically)

This avoids triggering Maven's full build cycle on every TypeScript change.

**Use this workflow for:**
- Rapid frontend development
- Styling/UI tweaks
- React component development
- Widget implementation

### Frontend-Only Commands

If you need to work exclusively on frontend code:

```bash
# Install dependencies (if not already installed by Maven)
npm ci

# Build TypeScript (production mode)
npm run build

# Watch mode (incremental rebuilds)
npm run watch

# Type checking (no output, just validation)
npm run typecheck

# Lint TypeScript/React code
npm run lint

# Auto-fix linting issues
npm run lint:fix
```

**Note:** The `npm` commands above use the system-installed Node.js. Maven uses its own downloaded Node.js in `target/node/`, which may be a different version. For consistency with CI builds, prefer the Maven workflow.

---

## React Island Architecture

Village Homepage uses the **islands architecture** pattern for its frontend. This approach combines the performance benefits of server-side rendering (via Qute templates) with the interactivity of React components.

### What Are React Islands?

React islands are isolated, interactive components embedded within server-rendered HTML. Unlike a traditional SPA (Single Page Application), only specific regions of the page are "hydrated" with JavaScript, reducing bundle sizes and improving performance.

**Benefits:**
- **Faster initial page load** - Server renders most content as static HTML
- **Smaller JavaScript bundles** - Only interactive widgets load React
- **Progressive enhancement** - Page works without JavaScript, enhanced with it
- **SEO-friendly** - Content is server-rendered and crawlable

### How It Works

#### 1. Server-Side (Qute Templates)

Templates render placeholder elements with special attributes:

```html
{* homepage.html - Qute template *}
<div class="widget-container">
  <div data-mount="WeatherWidget"
       data-props='{"location": "San Francisco", "units": "metric"}'>
    {* Optional: Server-rendered fallback content *}
    <p>Loading weather...</p>
  </div>
</div>

{* Include the React island bundle *}
<script type="module" src="/assets/js/mounts-[hash].js"></script>
```

**Attributes:**
- `data-mount`: Specifies which React component to render
- `data-props`: JSON-serialized props passed to the component

#### 2. Client-Side (TypeScript/React)

The `mounts.tsx` entry point scans for `[data-mount]` elements and hydrates them:

```typescript
// src/main/resources/META-INF/resources/assets/ts/mounts.tsx
const COMPONENT_REGISTRY = {
  WeatherWidget: {
    component: WeatherWidget,
    propsSchema: z.object({
      location: z.string(),
      units: z.enum(['metric', 'imperial']),
    }),
  },
};

// Auto-mounts on DOMContentLoaded
mountAll();
```

#### 3. Component Implementation

Components are standard React with TypeScript:

```typescript
// src/main/resources/META-INF/resources/assets/ts/components/WeatherWidget.tsx
import { Card } from 'antd';

interface WeatherWidgetProps {
  location: string;
  units: 'metric' | 'imperial';
}

export default function WeatherWidget({ location, units }: WeatherWidgetProps) {
  const [weather, setWeather] = useState(null);

  useEffect(() => {
    fetch(`/api/weather?location=${location}&units=${units}`)
      .then(res => res.json())
      .then(setWeather);
  }, [location, units]);

  return <Card>...</Card>;
}
```

### Adding a New React Island

1. **Create the component** in `src/main/resources/META-INF/resources/assets/ts/components/`:

   ```typescript
   // StockWidget.tsx
   export interface StockWidgetProps {
     symbol: string;
     interval: '1m' | '5m' | '1h';
   }

   export default function StockWidget({ symbol, interval }: StockWidgetProps) {
     // Implementation
   }
   ```

2. **Register in `mounts.tsx`**:

   ```typescript
   import StockWidget from './components/StockWidget';

   const COMPONENT_REGISTRY = {
     SampleWidget: { /* ... */ },
     StockWidget: {
       component: StockWidget,
       propsSchema: z.object({
         symbol: z.string().min(1).max(5),
         interval: z.enum(['1m', '5m', '1h']),
       }),
     },
   };
   ```

3. **Use in Qute template**:

   ```html
   <div data-mount="StockWidget"
        data-props='{"symbol": "AAPL", "interval": "5m"}'>
   </div>
   ```

4. **Run watch mode** to see live updates:

   ```bash
   npm run watch
   ```

### Best Practices

- **Props validation**: Always define Zod schemas in `mounts.tsx` to catch invalid props early
- **Graceful degradation**: Provide server-rendered fallback content inside the mount element
- **Bundle size**: Import only what you need (e.g., `import { debounce } from 'lodash-es'`)
- **Type safety**: Use TypeScript strict mode with explicit `| undefined` for optional props
- **Error boundaries**: Wrap components in error boundaries for production resilience
- **Console statements**: Use `console.warn()` or `console.error()` for debugging (allowed by linter)

### Directory Structure

```
src/main/resources/META-INF/resources/assets/
├── ts/                          # TypeScript source (input)
│   ├── mounts.tsx              # Entry point, mount registry
│   ├── components/             # React island components
│   │   ├── SampleWidget.tsx   # Example widget
│   │   ├── WeatherWidget.tsx  # Future: weather widget
│   │   └── StockWidget.tsx    # Future: stock widget
│   └── utils/                  # Shared utilities
└── js/                          # Bundled JavaScript (output)
    ├── mounts-[hash].js        # Main bundle (auto-generated)
    ├── chunks/                 # Code-split vendor chunks
    └── manifest.json           # Bundle mapping for templates
```

### Development Tips

- **Hot reload**: Use two-terminal workflow (see Development Workflows above)
- **Debugging**: Browser DevTools work seamlessly with inline source maps
- **Component testing**: Add React Testing Library tests in `src/test/typescript/` (future)
- **Storybook**: Planned for isolated component development (future)

---

## Project Structure

```
village-homepage/
├── docs/
│   ├── adr/                    # Architecture Decision Records
│   └── api/                    # API documentation
├── migrations/                 # MyBatis database migrations
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── villagecompute/homepage/
│   │   │       ├── api/
│   │   │       │   ├── rest/   # REST resources
│   │   │       │   └── types/  # DTOs (Type suffix)
│   │   │       ├── config/     # Configuration classes
│   │   │       ├── data/
│   │   │       │   └── models/ # JPA entities (Panache ActiveRecord)
│   │   │       ├── exceptions/ # Custom exceptions
│   │   │       ├── integration/
│   │   │       │   ├── ai/     # LangChain4j AI services
│   │   │       │   ├── social/ # Meta Graph API
│   │   │       │   ├── stocks/ # Alpha Vantage
│   │   │       │   └── weather/# Open-Meteo, NWS
│   │   │       ├── jobs/       # Delayed job handlers
│   │   │       ├── services/   # Business logic
│   │   │       └── util/       # Utilities
│   │   └── resources/
│   │       ├── application.yaml
│   │       ├── templates/      # Qute templates
│   │       └── META-INF/
│   └── test/
│       └── java/               # Unit + integration tests
├── frontend/
│   ├── src/
│   │   ├── components/         # React components
│   │   ├── islands/            # React island mounts
│   │   ├── styles/             # CSS/SCSS
│   │   └── mounts.ts           # Entry point
│   ├── package.json
│   └── tsconfig.json
├── .editorconfig               # Code style rules
├── .gitignore
├── CLAUDE.md                   # AI assistant instructions
├── eclipse-formatter.xml       # Eclipse formatter config
├── mvnw                        # Maven wrapper script (Unix)
├── mvnw.cmd                    # Maven wrapper script (Windows)
├── pom.xml                     # Maven project configuration
└── README.md                   # This file
```

---

## Technology Stack

### Backend

- **Java 21 LTS** - Long-term support, virtual threads, modern language features
- **Quarkus 3.26.1** - Cloud-native Java framework
- **PostgreSQL 17 + PostGIS** - Primary database with geo-spatial support
- **Elasticsearch 8** - Full-text search and geo-spatial filtering
- **Hibernate ORM + Panache** - ActiveRecord pattern for data access
- **LangChain4j** - AI integration (Claude Sonnet 4 via Anthropic API)
- **jvppeteer** - Screenshot capture service (Java Puppeteer wrapper)

### Frontend

- **Qute** - Server-rendered templates (primary rendering)
- **React 18 + TypeScript** - Interactive islands for widgets
- **Ant Design** - UI component library
- **gridstack.js** - Drag-and-drop widget layout
- **@antv/g2plot, @antv/l7** - Data visualization and maps
- **esbuild** - Fast TypeScript bundling

### Infrastructure

- **Cloudflare R2** - S3-compatible object storage + CDN
- **Kubernetes (k3s)** - Container orchestration
- **Jib** - Container image building (no Docker daemon required)
- **Stripe** - Payment processing for marketplace features

---

## Key Features

- **Personalized Homepage:** Drag-and-drop widgets (news, weather, stocks, social feeds, quick links, search bar)
- **Marketplace (Classifieds):** Location-based listings with image uploads, email masking, and payment processing
- **Good Sites Directory:** Curated web directory with voting, screenshots, and AI categorization
- **Public Profiles:** Shareable homepage templates with SEO optimization
- **OAuth Authentication:** Google, Facebook, Apple sign-in with anonymous account merging
- **Admin RBAC:** Four-tier role system (super_admin, support, ops, read_only) with audit logging and impersonation tracking - see [RBAC Documentation](docs/ops/rbac.md)
- **AI-Powered:** Content tagging, categorization, and recommendations via LangChain4j
- **Delayed Job System:** Background processing for feed refresh, AI tasks, screenshots, and email relay

---

## Configuration

### Environment Variables

Create an `application.yaml` in `src/main/resources/` or use environment variables:

```yaml
# Database
quarkus.datasource.db-kind: postgresql
quarkus.datasource.jdbc.url: jdbc:postgresql://localhost:5432/village_homepage
quarkus.datasource.username: village
quarkus.datasource.password: ${DB_PASSWORD}

# Elasticsearch
quarkus.hibernate-search-orm.elasticsearch.hosts: localhost:9200

# S3 / Cloudflare R2
quarkus.s3.endpoint-override: https://account-id.r2.cloudflarestorage.com
quarkus.s3.aws.region: auto
quarkus.s3.aws.credentials.static-provider.access-key-id: ${R2_ACCESS_KEY}
quarkus.s3.aws.credentials.static-provider.secret-access-key: ${R2_SECRET_KEY}

# OAuth (Google, Facebook, Apple)
quarkus.oidc.tenant-enabled: true
quarkus.oidc."google".client-id: ${GOOGLE_CLIENT_ID}
quarkus.oidc."google".credentials.secret: ${GOOGLE_CLIENT_SECRET}
quarkus.oidc."facebook".client-id: ${FACEBOOK_APP_ID}
quarkus.oidc."facebook".credentials.secret: ${FACEBOOK_APP_SECRET}
quarkus.oidc."apple".client-id: ${APPLE_CLIENT_ID}
quarkus.oidc."apple".credentials.secret: ${APPLE_CLIENT_SECRET}

# AI (LangChain4j + Anthropic)
quarkus.langchain4j.anthropic.api-key: ${ANTHROPIC_API_KEY}
quarkus.langchain4j.anthropic.model: claude-sonnet-4

# Stripe
stripe.api-key: ${STRIPE_SECRET_KEY}
```

---

## Testing

### Coverage Requirements

- **Line Coverage:** 80% minimum (enforced by JaCoCo)
- **Branch Coverage:** 80% minimum (enforced by JaCoCo)
- **Quality Gate:** Build fails if coverage drops below 80%
- **Modules Covered:** Auth/preferences modules maintain ≥80% coverage
- **Rate Limit Tests:** All 429 HTTP responses verified in test suite

### Running Tests

```bash
# Unit tests only
./mvnw test

# Unit + integration tests
./mvnw verify

# Run specific test class
./mvnw test -Dtest=RateLimitServiceTest

# Generate coverage report
./mvnw test jacoco:report
open target/site/jacoco/index.html

# Run with coverage enforcement (fail if <80%)
./mvnw verify jacoco:check

# Run Good Sites directory tests
./mvnw test -Dtest="*Directory*Test,Good*Test"

# Run specific Good Sites test suites
./mvnw test -Dtest=DirectoryVotingServiceTest  # Voting logic (14/14 passing)
./mvnw test -Dtest=GoodSitesResourceTest      # Browse/search (18/18 passing)
./mvnw test -Dtest=KarmaServiceTest           # Karma calculations (known transaction isolation issue)

# Run E2E tests (Playwright)
npm run test:e2e
npx playwright test tests/e2e/good-sites.spec.ts  # Good Sites browsing flows

# Run load tests (k6)
k6 run tests/load/screenshot-queue.js  # Screenshot capture performance
```

### Test Categories

#### Unit Tests
- **RateLimitServiceTest**: Rate limiting logic, sliding window, tier differentiation
- **AccountMergeServiceTest**: Anonymous-to-authenticated merge, consent recording
- **AuthIdentityServiceTest**: Anonymous cookies, bootstrap guard, role management
- **UserPreferenceServiceTest**: Preference CRUD, validation, schema migration
- **FeatureFlagServiceTest**: Feature flag evaluation, cohort hashing, analytics opt-out
- **DirectoryVotingServiceTest**: Good Sites voting logic (cast, change, remove votes)
- **KarmaServiceTest**: Karma adjustments for submissions and votes (see known issues)

#### Integration Tests
- **RateLimitFilterTest**: Database configuration, config CRUD operations
- **HomepageResourceTest**: SSR output, gridstack attributes, React mount points
- **GoodSitesResourceTest**: Good Sites browsing, search, pagination
- **DirectoryCategoryResourceTest**: Category CRUD operations
- **DirectoryImportResourceTest**: Bulk CSV import and AI categorization

#### E2E Tests
E2E tests use Playwright to test user flows with a real browser:
- Homepage edit mode functionality
- Widget drag-and-drop operations
- Anonymous vs authenticated user flows
- **Good Sites directory** (tests/e2e/good-sites.spec.ts):
  - Category browsing and navigation
  - Search functionality
  - Site detail pages
  - Voting flows (skipped until React component implemented)

**Run E2E tests:**
```bash
npm run test:e2e
# or specific file
npx playwright test tests/e2e/good-sites.spec.ts
```

#### Load Tests
Load tests use k6 to test performance under concurrent load:
- **Screenshot capture queue** (tests/load/screenshot-queue.js):
  - Tests screenshot capture under concurrent load
  - Monitors semaphore enforcement (max 3 concurrent browsers)
  - Measures p95/p99 latency and queue depth

**Run load tests:**
```bash
k6 run tests/load/screenshot-queue.js
```

See [docs/ops/testing.md](docs/ops/testing.md) for comprehensive testing documentation.

### Writing Tests

#### Unit Test Example (Quarkus)

```java
@QuarkusTest
@QuarkusTestResource(H2TestResource.class)
public class RateLimitServiceTest {

    @Inject
    RateLimitService rateLimitService;

    @Test
    void testCheckLimit_ExceedsLimit_DeniesRequest() {
        // Exhaust the limit
        for (int i = 0; i < 5; i++) {
            rateLimitService.checkLimit(null, "127.0.0.1", "test_action",
                RateLimitService.Tier.ANONYMOUS, "/test");
        }

        // 6th request should be denied (429 path)
        RateLimitService.RateLimitResult result = rateLimitService.checkLimit(
            null, "127.0.0.1", "test_action", RateLimitService.Tier.ANONYMOUS, "/test"
        );

        assertFalse(result.allowed());
        assertEquals(0, result.remaining());
    }
}
```

#### REST Integration Test Example

```java
@QuarkusTest
public class UserResourceTest {

    @Test
    public void testGetUser() {
        given()
            .when().get("/api/users/1")
            .then()
            .statusCode(200)
            .body("email", equalTo("test@example.com"));
    }
}
```

### Test Database

Tests use H2 in-memory database configured via `H2TestResource.class`:
- PostgreSQL compatibility mode enabled
- No external database dependencies required
- Fast test execution (~2-3 seconds per test class)
- Automatic cleanup between tests

### CI/CD Test Execution

Tests run automatically in CI pipeline (`.github/workflows/build.yml`):

1. **Unit Test Gate** (Stage 8): Maven Surefire, parallel execution
2. **Integration Test Gate** (Stage 9): Maven Failsafe, test containers
3. **Coverage Gate** (Stage 10): JaCoCo report generation and threshold check
4. **Quality Gate** (Stage 11): SonarCloud scan (planned)

**Artifacts:** Test reports and coverage data uploaded to GitHub Actions artifacts with 30-day retention.

---

## Deployment

### Building Container Image

```bash
# Build image with Jib (no Docker daemon required)
./mvnw package -Dquarkus.container-image.build=true

# Tag: villagecompute/village-homepage:1.0.0-SNAPSHOT
```

### Kubernetes Deployment

Helm charts and deployment manifests are in the `villagecompute` infrastructure repository:

```bash
# Deploy to beta environment
kubectl apply -k ../villagecompute/k8s/overlays/beta/homepage

# Deploy to production
kubectl apply -k ../villagecompute/k8s/overlays/production/homepage
```

### Environment URLs

- **Production:** https://homepage.villagecompute.com
- **Beta:** https://homepage-beta.villagecompute.com
- **Local Dev:** http://localhost:8080

---

## Architecture

### Architectural Diagrams

The system architecture is documented using PlantUML diagrams that capture the runtime structure, integration boundaries, and policy enforcement points:

- **[System Context Diagram](docs/diagrams/context.puml)** - Shows Village Homepage's interactions with external actors (users, administrators) and systems (OAuth providers, Stripe, Meta Graph API, weather/stock APIs, LangChain4j, Cloudflare R2, PostgreSQL, Elasticsearch). Includes policy annotations (P1-P14) governing GDPR compliance, AI budgets, payment audits, screenshot retention, and geographic filtering.

- **[Container Diagram](docs/diagrams/container.puml)** - Decomposes the Quarkus application into internal components (ExperienceShell, AuthIdentityService, FeedAggregationService, MarketplaceService, DirectoryService, etc.), async job queue families (DEFAULT/HIGH/LOW/BULK/SCREENSHOT), and Kubernetes deployment pods. Highlights delayed job orchestration, worker isolation, and cross-cutting services (FeatureFlagService, RateLimitService, StorageGateway).

**Key Points:**
- Diagrams align with ADR 0001 toolchain decisions (Java 21, Quarkus 3.26.1, Node 20.10.0)
- Policy tags reference cross-cutting rulebook (GDPR consent, AI cost controls, rate limiting)
- Queue segregation supports targeted scaling and priority management in Kubernetes
- All external integrations show protocol/policy annotations for ops alignment

**Rendering:**
- Use a PlantUML renderer (IDE plugin, online viewer, or `plantuml` CLI) to visualize `.puml` sources
- Diagrams are versioned alongside code for architecture reviews and onboarding

### Design Decisions

See [Architecture Decision Records](docs/adr/) for key technical decisions:

- [ADR 0001: Quarkus Toolchain Upgrade](docs/adr/0001-quarkus-toolchain-upgrade.md)

### Code Standards

This project follows the [VillageCompute Java Project Standards](../village-storefront/docs/java-project-standards.adoc):

- **No Lombok** - Use Java records for immutable data
- **Panache ActiveRecord** - Static finder methods on entities, no separate repositories
- **Control flow braces** - Required for all if/else/for/while statements
- **Type suffix** - All JSON DTOs end with `Type` (e.g., `UserProfileType`)
- **Line length** - 120 characters maximum
- **Delayed jobs** - All async work via database-backed job queues

---

## Contributing

### Code Formatting

Before committing, ensure code is formatted:

```bash
./mvnw spotless:apply
```

### Git Workflow

```bash
# Create feature branch
git checkout -b feature/your-feature-name

# Make changes, commit frequently
git add .
git commit -m "feat: add user profile widget"

# Push to remote
git push origin feature/your-feature-name

# Create pull request on GitHub
```

### Commit Message Convention

Follow [Conventional Commits](https://www.conventionalcommits.org/):

- `feat:` - New feature
- `fix:` - Bug fix
- `docs:` - Documentation changes
- `style:` - Code style changes (formatting, no logic changes)
- `refactor:` - Code refactoring
- `test:` - Adding or updating tests
- `chore:` - Maintenance tasks (dependencies, build config)

---

## Troubleshooting

### Java Version Issues

```bash
# Check Java version
java -version

# If wrong version, install Java 21
# macOS (Homebrew):
brew install openjdk@21

# Ubuntu/Debian:
sudo apt install openjdk-21-jdk

# Set JAVA_HOME
export JAVA_HOME=/path/to/java21
```

### Maven Wrapper Issues

```bash
# If mvnw is not executable
chmod +x mvnw

# If wrapper fails to download Maven
rm -rf ~/.m2/wrapper
./mvnw --version
```

### Frontend Build Issues

```bash
# Clear Node cache and dependencies
rm -rf node_modules target/node

# Reinstall dependencies and rebuild
./mvnw clean compile

# If npm ci fails with peer dependency errors
npm install --legacy-peer-deps

# If TypeScript errors appear but build succeeds
npm run typecheck

# If watch mode hangs or doesn't detect changes
# Kill any orphaned esbuild processes
pkill -f esbuild
npm run watch
```

### React Island Architecture Issues

If React components aren't mounting in the browser:

1. **Check browser console** for `[mounts]` log messages
2. **Verify Qute template** has correct `data-mount` and `data-props` attributes:
   ```html
   <div data-mount="SampleWidget"
        data-props='{"title": "Test", "count": 5}'>
   </div>
   ```
3. **Validate props JSON** matches the Zod schema in `mounts.ts`
4. **Check bundle output** exists in `src/main/resources/META-INF/resources/assets/js/`
5. **Verify script tag** in template references correct bundle:
   ```html
   <script type="module" src="/assets/js/mounts.js"></script>
   ```

### TypeScript Strict Mode Errors

This project uses TypeScript strict mode. Common fixes:

```typescript
// ❌ Error: Type 'string | undefined' is not assignable to 'string'
const value = props.optionalField;

// ✅ Fix: Use optional chaining and nullish coalescing
const value = props.optionalField ?? 'default';

// ❌ Error: Element implicitly has an 'any' type
const item = array[index];

// ✅ Fix: Enable noUncheckedIndexedAccess handling
const item = array[index];
if (item !== undefined) {
  // use item safely
}
```

### Database Connection Issues

```bash
# Check if services are running
docker-compose ps

# View PostgreSQL logs
docker-compose logs postgres

# Restart PostgreSQL
docker-compose restart postgres

# Test connection
psql -h localhost -p 5432 -U village -d village_homepage -c "SELECT 1;"

# Reset database (WARNING: destroys all data)
docker-compose down -v
docker-compose up -d
cd migrations
set -a && source ../.env && set +a
mvn migration:up -Dmigration.env=development
cd ..
./scripts/load-geo-data.sh
```

### Docker Compose Service Issues

```bash
# View all service logs
docker-compose logs -f

# View specific service logs
docker-compose logs -f postgres
docker-compose logs -f elasticsearch

# Restart all services
docker-compose restart

# Restart specific service
docker-compose restart elasticsearch

# Stop all services
docker-compose down

# Stop and remove volumes (full reset)
docker-compose down -v

# Check resource usage
docker stats --no-stream
```

**For detailed troubleshooting, see [docs/ops/dev-services.md](docs/ops/dev-services.md).**

---

## API Documentation

### OpenAPI Specification

The Village Homepage API is fully documented using **OpenAPI v3.0.3**. The specification covers:

- Authentication endpoints (OAuth, bootstrap, logout)
- User preferences API
- Widget data endpoints (news, weather, stocks, social)
- Admin endpoints (feature flags, rate limits)
- All DTO schemas with validation constraints
- Security schemes and rate limiting

**Specification Location:** [`api/openapi/v1.yaml`](api/openapi/v1.yaml)

**Version:** `1.0.0-alpha` (Iteration I2 milestone)

### Viewing the API Spec

#### During Development

When running Quarkus in dev mode, access the auto-generated API documentation:

```bash
./mvnw quarkus:dev
```

Then visit:
- **Swagger UI:** http://localhost:8080/q/swagger-ui (interactive API explorer)
- **OpenAPI YAML:** http://localhost:8080/q/openapi
- **OpenAPI JSON:** http://localhost:8080/q/openapi?format=json

#### Via Swagger Editor

View and validate the spec online:

1. Go to https://editor.swagger.io/
2. File → Import URL
3. Paste the raw GitHub URL to `api/openapi/v1.yaml`

Or load the file directly from your local checkout.

### Validating the Spec

Validate the OpenAPI specification locally:

```bash
# Validate using swagger-cli
npm run openapi:validate

# This runs: swagger-cli validate api/openapi/v1.yaml
```

The CI pipeline automatically validates the spec on every build. Validation failures will block merges.

### API Conventions

#### JSON Naming

All API responses use **snake_case** for JSON properties:

```json
{
  "schema_version": 1,
  "news_topics": ["technology", "science"],
  "widget_configs": {}
}
```

#### Rate Limiting

All endpoints enforce tier-based rate limiting. Rate limit information is returned in response headers:

```
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 45
X-RateLimit-Window: 60
```

When limits are exceeded, endpoints return `429 Too Many Requests` with a `Retry-After` header.

#### Authentication

The API supports three authentication methods:

1. **OAuth2** - Google, Facebook, Apple (user authentication)
2. **Cookie** - `vu_anon_id` cookie for anonymous users
3. **JWT Bearer** - Admin endpoints require `super_admin` role

#### Error Responses

All errors follow a consistent format:

```json
{
  "error": "Human-readable error message"
}
```

**Common status codes:**
- `400` - Validation error or invalid parameters
- `401` - Authentication required
- `403` - Insufficient permissions
- `404` - Resource not found
- `429` - Rate limit exceeded
- `500` - Internal server error

### Documentation

For detailed API documentation, see:

- **[OpenAPI Specification](api/openapi/v1.yaml)** - Complete API contract
- **[OpenAPI Notes](docs/api/openapi-notes.md)** - Spec conventions, validation, maintenance guide

### Generating Client SDKs

Use the OpenAPI spec to generate client SDKs for various languages:

```bash
# TypeScript/JavaScript client
npx @openapitools/openapi-generator-cli generate \
  -i api/openapi/v1.yaml \
  -g typescript-fetch \
  -o generated/typescript-client

# Python client
npx @openapitools/openapi-generator-cli generate \
  -i api/openapi/v1.yaml \
  -g python \
  -o generated/python-client
```

See [OpenAPI Generator documentation](https://openapi-generator.tech/docs/generators) for all supported languages.

---

## Resources

- [Quarkus Documentation](https://quarkus.io/guides/)
- [LangChain4j Documentation](https://docs.langchain4j.dev/)
- [Ant Design Components](https://ant.design/components/overview/)
- [Gridstack.js Documentation](https://gridstackjs.com/)
- [OpenAPI Specification](https://spec.openapis.org/oas/v3.0.3)
- [VillageCompute Standards](../village-storefront/docs/java-project-standards.adoc)

---

## License

Proprietary - VillageCompute, Inc. All rights reserved.

---

## Support

For issues and questions:
- **GitHub Issues:** https://github.com/VillageCompute/village-homepage/issues
- **Internal Slack:** #village-homepage
- **Email:** dev@villagecompute.com
