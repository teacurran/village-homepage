# Village Homepage

> Customizable homepage portal SaaS with widgets, marketplace, and web directory

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

The following services are required for local development and will be available via Docker Compose (coming soon):

- PostgreSQL 17 with PostGIS extension
- Elasticsearch 8.x
- MinIO (S3-compatible object storage)
- Mailpit (SMTP test server)
- Jaeger (distributed tracing)

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
```

### 3. Run Development Server

```bash
# Start Quarkus in dev mode (hot reload enabled)
./mvnw quarkus:dev
```

The application will be available at:
- **Homepage:** http://localhost:8080
- **Dev UI:** http://localhost:8080/q/dev

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

# Run pending migrations
mvn migration:up -Dmigration.env=development

# Check migration status
mvn migration:status -Dmigration.env=development

# Create new migration
mvn migration:new -Dmigration.description="add_user_preferences_table"
```

---

## Development Workflows

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
quarkus.oidc.google.client-id: ${GOOGLE_CLIENT_ID}
quarkus.oidc.google.client-secret: ${GOOGLE_CLIENT_SECRET}

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

### Running Tests

```bash
# Unit tests only
./mvnw test

# Unit + integration tests
./mvnw verify

# Generate coverage report
./mvnw test jacoco:report
open target/site/jacoco/index.html
```

### Writing Tests

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
# Check PostgreSQL is running
docker ps | grep postgres

# Start PostgreSQL (if using Docker Compose)
docker-compose up -d postgres

# Test connection
psql -h localhost -U village -d village_homepage
```

---

## Resources

- [Quarkus Documentation](https://quarkus.io/guides/)
- [LangChain4j Documentation](https://docs.langchain4j.dev/)
- [Ant Design Components](https://ant.design/components/overview/)
- [Gridstack.js Documentation](https://gridstackjs.com/)
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
