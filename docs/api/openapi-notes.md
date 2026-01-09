# OpenAPI Specification Notes

## Overview

This document describes the OpenAPI v3 specification for the Village Homepage API, located at `api/openapi/v1.yaml`. The specification documents all public and admin REST endpoints, DTO schemas, security schemes, and rate limiting headers.

**Spec Version**: `1.0.0-alpha` (Iteration I2 milestone)
**OpenAPI Version**: 3.0.3
**Last Updated**: 2026-01-09

## Specification Location

The canonical OpenAPI specification is maintained at:

```
api/openapi/v1.yaml
```

This file is version-controlled and serves as the source of truth for API contracts.

## Viewing the Specification

### In Development Mode

When running Quarkus in dev mode (`./mvnw quarkus:dev`), the SmallRye OpenAPI extension auto-generates a spec from JAX-RS annotations, available at:

- **YAML format**: http://localhost:8080/q/openapi
- **JSON format**: http://localhost:8080/q/openapi?format=json
- **Swagger UI**: http://localhost:8080/q/swagger-ui

**Note**: The auto-generated spec provides a baseline but may not include all manually-documented stub endpoints or complete schema details. Always refer to `api/openapi/v1.yaml` for the complete specification.

### Via Swagger Editor

You can validate and visualize the spec using the online Swagger Editor:

1. Go to https://editor.swagger.io/
2. File → Import URL
3. Paste: `https://raw.githubusercontent.com/VillageCompute/village-homepage/main/api/openapi/v1.yaml` (adjust branch as needed)

Or load the local file directly in the editor.

## Validation

### Local Validation

Validate the OpenAPI spec locally using swagger-cli:

```bash
npm run openapi:validate
```

This runs: `swagger-cli validate api/openapi/v1.yaml`

**Installation**: swagger-cli is installed as a dev dependency via `package.json`. Run `npm install` to install it.

### Continuous Integration

The CI pipeline validates the OpenAPI spec during the build:

1. **Schema Validation**: Ensures spec is valid OpenAPI 3.0.3 format
2. **Reference Resolution**: Verifies all `$ref` links resolve correctly
3. **Drift Detection**: Compares committed spec against auto-generated baseline (future enhancement)

CI will fail the build if:
- The spec fails swagger-cli validation
- There are unresolved `$ref` references
- Schema violations are detected

## Regenerating from Annotations

The SmallRye OpenAPI extension can generate a baseline spec from JAX-RS annotations. To regenerate:

1. Start Quarkus in dev mode:
   ```bash
   ./mvnw quarkus:dev
   ```

2. Download the auto-generated spec:
   ```bash
   curl http://localhost:8080/q/openapi > api/openapi/baseline-generated.yaml
   ```

3. Compare against the committed spec:
   ```bash
   diff api/openapi/v1.yaml api/openapi/baseline-generated.yaml
   ```

4. Manually merge any new endpoints or schemas into `v1.yaml`

**Important**: Do not blindly replace `v1.yaml` with the auto-generated spec, as it lacks:
- Stub endpoint definitions for future implementations
- Detailed examples and descriptions
- Custom security scheme configurations
- Rate limiting header documentation

## Structure and Conventions

### Schema Naming

All DTO schemas follow the `Type` suffix naming convention to match Java record classes in `src/main/java/villagecompute/homepage/api/types/`:

- `UserPreferencesType` → `UserPreferencesType.java`
- `FeatureFlagType` → `FeatureFlagType.java`
- `LayoutWidgetType` → `LayoutWidgetType.java`

### JSON Property Naming

All JSON properties use **snake_case** to match Jackson `@JsonProperty` annotations in Java Type classes:

✅ Correct:
```json
{
  "schema_version": 1,
  "news_topics": [],
  "widget_configs": {}
}
```

❌ Incorrect:
```json
{
  "schemaVersion": 1,
  "newsTopics": [],
  "widgetConfigs": {}
}
```

### Required vs Optional Fields

Schema fields marked as `required` in OpenAPI correspond to Java fields with `@NotNull` or `@NotBlank` Bean Validation annotations. Nullable fields use `nullable: true` in the OpenAPI spec.

### Validation Constraints

OpenAPI constraints map to Java Bean Validation annotations:

| Java Annotation | OpenAPI Constraint |
|-----------------|-------------------|
| `@NotNull` | `required: true` |
| `@NotBlank` | `required: true`, `minLength: 1` |
| `@Min(1)` | `minimum: 1` |
| `@Max(100)` | `maximum: 100` |
| `@Pattern(regexp = "...")` | `pattern: "..."` |

### Timestamps

All timestamp fields use ISO-8601 format with timezone:

```yaml
created_at:
  type: string
  format: date-time
  example: "2026-01-09T00:00:00Z"
```

Corresponding Java type: `java.time.Instant`

## Endpoint Organization

### Tags

Endpoints are organized into logical groups using OpenAPI tags:

- **Authentication**: OAuth login, logout, bootstrap
- **Preferences**: User homepage preferences and layout
- **Widgets**: Widget data endpoints (news, weather, stocks, social)
- **Admin - Feature Flags**: Feature flag configuration (admin only)
- **Admin - Rate Limits**: Rate limit configuration and violations (admin only)

### Path Conventions

| Path Prefix | Purpose | Authentication |
|-------------|---------|----------------|
| `/api/*` | User-facing REST APIs | OAuth2 or Cookie |
| `/admin/api/*` | Admin-only APIs | JWT Bearer (super_admin role) |
| `/auth/*` | Authentication flows | Public (varies) |
| `/bootstrap` | One-time setup | Public (guard after first use) |

### HTTP Methods

- **GET**: Retrieve resources (idempotent, cacheable)
- **POST**: Create resources or trigger actions
- **PUT**: Full replacement (all fields required)
- **PATCH**: Partial update (optional fields)

Admin endpoints use **PATCH** for partial updates to support updating individual configuration fields without sending the entire object.

## Security Schemes

### OAuth2 (oauth2)

Used for user authentication via Google, Facebook, Apple providers.

**Flow**: Authorization Code
**Authorization URL**: `/auth/oauth/{provider}`
**Token URL**: `/auth/token` (not yet implemented)

### Cookie Authentication (cookieAuth)

Used for anonymous users before authentication.

**Cookie Name**: `vu_anon_id`
**Purpose**: Stores preferences for anonymous users; merged with user account on login

### Bearer Token (bearerAuth)

Used for admin endpoints requiring `super_admin` role.

**Header Format**: `Authorization: Bearer <jwt_token>`
**Required Claim**: `role: super_admin`

## Rate Limiting

All API endpoints enforce tier-based rate limiting. Rate limit information is returned in response headers:

| Header | Description |
|--------|-------------|
| `X-RateLimit-Limit` | Maximum requests allowed in window |
| `X-RateLimit-Remaining` | Requests remaining in current window |
| `X-RateLimit-Window` | Window duration in seconds |
| `Retry-After` | (429 only) Seconds until limit resets |

**User Tiers**:
- `anonymous`: Anonymous users (via `vu_anon_id` cookie)
- `logged_in`: Authenticated users
- `trusted`: Users with elevated trust (karma-based)

Rate limit configurations are managed via `/admin/api/rate-limits` endpoints.

## Stub Endpoints

The following endpoints are documented in the OpenAPI spec but not yet fully implemented (marked as **[Stub Endpoint - Implementation Pending]**):

- `GET /api/widgets/news` - News feed widget data
- `GET /api/widgets/weather` - Weather widget data
- `GET /api/widgets/stocks` - Stocks widget data
- `GET /api/widgets/social` - Social feed widget data

These endpoints currently return:
```json
{
  "data": [],
  "status": "not_implemented"
}
```

Full implementations are planned for iteration I3.

## Error Response Format

All error responses use a consistent schema:

```json
{
  "error": "Human-readable error message"
}
```

**Common Status Codes**:
- `400 Bad Request`: Validation error or invalid parameters
- `401 Unauthorized`: Authentication required
- `403 Forbidden`: Insufficient permissions
- `404 Not Found`: Resource does not exist
- `429 Too Many Requests`: Rate limit exceeded
- `500 Internal Server Error`: Unexpected server error

## Examples

All endpoint definitions include request/response examples in the `example` fields. These examples use realistic data that matches the schema constraints.

**Example sources**:
- Default preferences: Based on `UserPreferencesType.createDefault()` in Java code
- Feature flags: Matches initial seeded flags (stocks_widget, social_integration, promoted_listings)
- Rate limits: Based on default configurations in database migrations

## Source Code Mappings

### Java Type Classes

All schema components in the OpenAPI spec map directly to Java record classes:

| OpenAPI Schema | Java Type Class | Location |
|----------------|-----------------|----------|
| `UserPreferencesType` | `UserPreferencesType.java` | `src/main/java/villagecompute/homepage/api/types/` |
| `LayoutWidgetType` | `LayoutWidgetType.java` | `src/main/java/villagecompute/homepage/api/types/` |
| `ThemeType` | `ThemeType.java` | `src/main/java/villagecompute/homepage/api/types/` |
| `WeatherLocationType` | `WeatherLocationType.java` | `src/main/java/villagecompute/homepage/api/types/` |
| `WidgetConfigType` | `WidgetConfigType.java` | `src/main/java/villagecompute/homepage/api/types/` |
| `FeatureFlagType` | `FeatureFlagType.java` | `src/main/java/villagecompute/homepage/api/types/` |
| `UpdateFeatureFlagRequestType` | `UpdateFeatureFlagRequestType.java` | `src/main/java/villagecompute/homepage/api/types/` |
| `RateLimitConfigType` | `RateLimitConfigType.java` | `src/main/java/villagecompute/homepage/api/types/` |
| `UpdateRateLimitConfigRequestType` | `UpdateRateLimitConfigRequestType.java` | `src/main/java/villagecompute/homepage/api/types/` |
| `RateLimitViolationType` | `RateLimitViolationType.java` | `src/main/java/villagecompute/homepage/api/types/` |

### REST Resources

Documented endpoints are implemented in JAX-RS resource classes:

| Endpoint Path | Resource Class | Location |
|---------------|----------------|----------|
| `/api/preferences` | `PreferencesResource.java` | `src/main/java/villagecompute/homepage/api/rest/` |
| `/admin/api/feature-flags` | `FeatureFlagResource.java` | `src/main/java/villagecompute/homepage/api/rest/admin/` |
| `/admin/api/rate-limits` | `RateLimitResource.java` | `src/main/java/villagecompute/homepage/api/rest/admin/` |
| `/bootstrap` | `AuthResource.java` | `src/main/java/villagecompute/homepage/api/rest/` |

## Versioning Strategy

The OpenAPI spec version follows the application version:

- **Current**: `1.0.0-alpha` (Iteration I2 milestone)
- **Next**: `1.0.0-beta` (after Iteration I3)
- **Stable**: `1.0.0` (production release)

Version bumping rules:
- **Major version (X.0.0)**: Breaking changes to existing endpoints or schemas
- **Minor version (0.X.0)**: New endpoints or backward-compatible schema additions
- **Patch version (0.0.X)**: Documentation updates or bug fixes

Breaking changes require:
1. Version tag increment in `info.version`
2. DTO `schema_version` field increment for affected types
3. Deprecation notice for previous versions (maintain for 2 beta cycles)

## Policy References

The API design adheres to Village Homepage policies:

- **P1 (GDPR/CCPA)**: Preferences are mergeable during anonymous account upgrades and deletable on account purge
- **P9 (Anonymous Cookie Security)**: `vu_anon_id` cookie enables preference storage before authentication
- **P14 (Rate Limiting)**: All endpoints enforce tier-based rate limits with header feedback

## Future Enhancements

### Planned for Iteration I3

1. **Full Widget Endpoints**: Implement news, weather, stocks, social widget APIs
2. **Pagination Standards**: Define consistent pagination schema for list endpoints
3. **Webhooks**: Document webhook payloads for async events (job completions, content updates)
4. **Bulk Operations**: Add bulk endpoints for admin operations (e.g., bulk flag updates)

### Drift Detection Script

A drift detection script is planned to automatically compare the committed spec against the auto-generated baseline during CI. This will catch undocumented endpoint changes:

```bash
# scripts/check-openapi-drift.sh
#!/bin/bash
curl http://localhost:8080/q/openapi > /tmp/generated-spec.yaml
diff -u api/openapi/v1.yaml /tmp/generated-spec.yaml
if [ $? -ne 0 ]; then
  echo "ERROR: OpenAPI spec has drifted from implementation"
  exit 1
fi
```

This will be integrated into `.github/workflows/build.yml` in a future iteration.

## Maintenance Checklist

When adding new endpoints or modifying existing ones:

- [ ] Update JAX-RS resource with proper annotations
- [ ] Create/update Type class with `@JsonProperty` annotations
- [ ] Add endpoint definition to `api/openapi/v1.yaml`
- [ ] Add schema component for new DTO types
- [ ] Include request/response examples
- [ ] Document rate limiting headers if applicable
- [ ] Add security requirements for admin endpoints
- [ ] Run `npm run openapi:validate` to verify spec
- [ ] Update this document if new conventions are introduced
- [ ] Commit both code and spec changes together

## Support

For questions about the OpenAPI specification or API design:

- Review VillageCompute Java Project Standards: `../village-storefront/docs/java-project-standards.adoc`
- Check CLAUDE.md for project-specific conventions
- Consult architecture documents in `docs/architecture/`
- Open an issue in the GitHub repository for clarification

## References

- **OpenAPI Specification 3.0.3**: https://spec.openapis.org/oas/v3.0.3
- **Swagger CLI Documentation**: https://apitools.dev/swagger-cli/
- **SmallRye OpenAPI Extension**: https://quarkus.io/guides/openapi-swaggerui
- **Jackson Annotations Reference**: https://github.com/FasterXML/jackson-annotations/wiki/Jackson-Annotations
- **Bean Validation (Jakarta)**: https://jakarta.ee/specifications/bean-validation/3.0/
