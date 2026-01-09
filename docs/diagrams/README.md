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

# Generate SVG
plantuml -tsvg docs/diagrams/context.puml
plantuml -tsvg docs/diagrams/container.puml
```

## Validation Checklist

- [x] PlantUML syntax valid (@startuml/@enduml tags present)
- [x] All policies P1-P14 annotated at relevant boundaries
- [x] Queue families (DEFAULT/HIGH/LOW/BULK/SCREENSHOT) color-coded and documented
- [x] Kubernetes deployment pods (experience pod, worker pod) shown
- [x] External integrations match pom.xml dependencies
- [x] Component names align with Blueprint component list
- [x] Legends explain policy abbreviations and queue colors
- [x] README.md updated with diagram references

## Maintenance

When updating diagrams:
1. Keep policy annotations synchronized with `.codemachine/artifacts/architecture/01_Blueprint_Foundation.md`
2. Update queue routing when new job types are added
3. Add new external integrations with protocol/policy annotations
4. Regenerate exported images (PNG/SVG) if sharing outside the codebase
5. Cross-reference with ADRs when toolchain or deployment changes occur

## References

- [ADR 0001: Quarkus Toolchain Upgrade](../adr/0001-quarkus-toolchain-upgrade.md)
- [C4 Model Documentation](https://c4model.com/)
- [PlantUML C4 Library](https://github.com/plantuml-stdlib/C4-PlantUML)
