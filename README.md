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

- Maven downloads Node/npm to `target/node` (first run only)
- Runs `npm ci` to install dependencies
- Runs `npm run build` to compile TypeScript
- Starts Quarkus with hot reload

**Use this workflow for:**
- Getting started quickly
- CI/CD builds
- Full project compilation

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

This avoids rebuilding TypeScript on every Java hot-reload cycle.

**Use this workflow for:**
- Rapid frontend development
- Styling/UI tweaks
- React component development

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
# Clear Node cache
rm -rf node_modules target/node

# Reinstall dependencies
./mvnw clean compile
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
