<!-- TASK: I4.T9 -->
# Testing Guide

This document provides comprehensive testing guidance for Village Homepage, covering unit tests, integration tests, E2E tests, load tests, and coverage reporting.

## Table of Contents

1. [Unit Tests](#unit-tests)
2. [Integration Tests](#integration-tests)
3. [End-to-End Tests](#end-to-end-tests)
4. [Load Tests](#load-tests)
5. [Coverage Reporting](#coverage-reporting)
6. [Running Tests Locally](#running-tests-locally)
7. [CI Pipeline](#ci-pipeline)
8. [Troubleshooting](#troubleshooting)

---

## Unit Tests

Unit tests verify individual service methods, utilities, and business logic in isolation.

### Location
- **Java:** `src/test/java/villagecompute/homepage/services/*Test.java`
- **TypeScript:** Not implemented yet (future: Jest/Vitest for frontend components)

### Technology
- **JUnit 5** (Jupiter)
- **Mockito** for mocking dependencies
- **AssertJ** for fluent assertions

### Example
```java
@QuarkusTest
public class UserPreferenceServiceTest {

    @Inject
    UserPreferenceService preferenceService;

    @Test
    @Transactional
    public void testCreateDefaultPreferences() {
        User user = new User();
        user.email = "test@example.com";
        user.persist();

        UserPreference pref = preferenceService.createDefaultPreferences(user.id);

        assertNotNull(pref);
        assertEquals("light", pref.theme);
        assertEquals("en", pref.language);
    }
}
```

### Running Unit Tests
```bash
# Run all unit tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=UserPreferenceServiceTest

# Run with coverage
./mvnw test jacoco:report
```

### Coverage Targets
- **Line Coverage:** ≥80%
- **Branch Coverage:** ≥80%
- **Package-level exceptions:** Test utilities, DTOs may have lower coverage

---

## Integration Tests

Integration tests verify REST API endpoints, database interactions, and external service integrations using `@QuarkusTest`.

### Location
- `src/test/java/villagecompute/homepage/api/rest/*Test.java`

### Technology
- **Quarkus Test Framework** (`@QuarkusTest`)
- **RestAssured** for HTTP testing
- **Testcontainers** (optional, for Postgres/Elasticsearch)
- **H2 in-memory database** (default for tests)

### Database Configuration
Test database is configured in `src/test/resources/application.yaml`:
```yaml
quarkus:
  datasource:
    db-kind: h2
    jdbc:
      url: jdbc:h2:mem:homepage-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
```

**Note:** PostGIS functions (ST_DWithin, etc.) are not available in H2. Radius search tests are skipped or use mocked data.

### Example
```java
@QuarkusTest
public class MarketplaceListingResourceTest {

    @Test
    public void testCreateListing_Success() {
        String requestBody = """
            {
              "title": "Vintage Bicycle",
              "description": "Great condition",
              "category": "for-sale",
              "price": 250.00
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(requestBody)
        .when()
            .post("/api/marketplace/listings")
        .then()
            .statusCode(201)
            .body("title", equalTo("Vintage Bicycle"));
    }
}
```

### Running Integration Tests
```bash
# Run all integration tests
./mvnw verify -DskipITs=false

# Run specific integration test
./mvnw test -Dtest=MarketplaceListingResourceTest

# Run with coverage
./mvnw verify jacoco:report
```

### Test Data Management
- Use `@BeforeEach` with `@Transactional` to set up test data
- Clean up data in `@BeforeEach` to avoid test pollution:
  ```java
  @BeforeEach
  @Transactional
  public void setup() {
      MarketplaceListing.deleteAll();
      User.deleteAll();
  }
  ```

---

## End-to-End Tests

E2E tests verify complete user workflows across the full stack using Playwright.

### Location
- `tests/e2e/*.spec.ts`

### Technology
- **Playwright** (Chromium browser automation)
- **TypeScript** for type safety

### Test Structure
```typescript
// tests/e2e/marketplace.spec.ts
test.describe('Marketplace - Listing Creation', () => {
  test('creates listing with payment', async ({ page }) => {
    await page.goto('/marketplace');
    await page.click('[data-testid="post-listing-btn"]');

    await page.fill('input[name="title"]', 'Vintage Bicycle');
    await page.fill('textarea[name="description"]', 'Great condition');

    await page.click('button[type="submit"]');

    await expect(page).toHaveURL(/\/marketplace\/listings\//);
  });
});
```

### Running E2E Tests
```bash
# Start dev server (required)
./mvnw quarkus:dev

# Run E2E tests (in separate terminal)
npm run test:e2e

# Run with UI mode (interactive)
npm run test:e2e:ui

# Run in headed mode (see browser)
npm run test:e2e:headed
```

### CI Configuration
E2E tests run automatically in CI pipeline (`.github/workflows/build.yml`):
1. Start Quarkus dev server in background
2. Install Playwright browsers
3. Run tests with `npm run test:e2e`
4. Upload test artifacts (screenshots, videos, traces)

### Debugging Flaky Tests
```bash
# Run tests with retries
npm run test:e2e -- --retries=2

# Run tests with trace on failure
npm run test:e2e -- --trace on

# View test report
npx playwright show-report
```

### Best Practices
- Use `data-testid` attributes for selectors (avoid relying on text)
- Wait for explicit conditions, not fixed timeouts
- Clean up test data in `afterEach` hooks
- Use page object models for reusable components

---

## Load Tests

Load tests measure performance under concurrent user load using k6.

### Location
- `tests/load/*.js`

### Technology
- **k6** (Grafana Labs load testing tool)

### Installation
```bash
# macOS
brew install k6

# Linux
sudo apt-get install k6

# Windows
choco install k6
```

### Running Load Tests
```bash
# Basic run with default configuration
k6 run tests/load/marketplace-search.js

# Custom VUs and duration
k6 run --vus 50 --duration 5m tests/load/marketplace-search.js

# Save results to JSON
k6 run --out json=results.json tests/load/marketplace-search.js
```

### KPI Targets (from I4 iteration plan)
- **Search Latency p95:** <200ms
- **Search Latency p99:** <500ms
- **Error Rate:** <1%

### Example Load Test
```javascript
// tests/load/marketplace-search.js
export const options = {
  stages: [
    { duration: '1m', target: 10 },  // Warm up
    { duration: '3m', target: 20 },  // Normal load
    { duration: '1m', target: 50 },  // Spike
  ],
  thresholds: {
    'http_req_duration': ['p(95)<200', 'p(99)<500'],
    'errors': ['rate<0.01'],
  },
};

export default function () {
  const res = http.get('http://localhost:8080/api/marketplace/search?q=bicycle');
  check(res, {
    'status 200': (r) => r.status === 200,
    'response time OK': (r) => r.timings.duration < 500,
  });
  sleep(1);
}
```

### Interpreting Results
```
✓ http_req_duration..........: avg=150ms p(95)=190ms p(99)=450ms
✓ http_req_failed............: 0.25% (5 of 2000 requests)
✓ iterations.................: 2000
✓ vus........................: 20
```

- **p95 <200ms:** PASS
- **p99 <500ms:** PASS
- **Error rate <1%:** PASS (0.25%)

### Load Test Prerequisites
Before running load tests:
1. **Seed database** with realistic data (1000+ listings)
2. **Start Elasticsearch** and verify index health
3. **Scale application** (2-3 instances recommended)
4. **Enable monitoring** (Prometheus, Grafana)
5. **Prime caches** (run warmup requests)

---

## Coverage Reporting

Coverage is measured using JaCoCo and enforced via Maven plugin.

### Generating Coverage Report
```bash
# Run tests with coverage
./mvnw test jacoco:report

# View HTML report
open target/site/jacoco/index.html
```

### Coverage Configuration
Located in `pom.xml`:
```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>0.8.11</version>
  <executions>
    <execution>
      <id>jacoco-check</id>
      <goals>
        <goal>check</goal>
      </goals>
      <configuration>
        <rules>
          <rule>
            <element>BUNDLE</element>
            <limits>
              <limit>
                <counter>LINE</counter>
                <value>COVEREDRATIO</value>
                <minimum>0.80</minimum> <!-- 80% -->
              </limit>
              <limit>
                <counter>BRANCH</counter>
                <value>COVEREDRATIO</value>
                <minimum>0.80</minimum> <!-- 80% -->
              </limit>
            </limits>
          </rule>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

### Coverage Targets
- **Overall:** ≥80% line and branch coverage
- **Per-module targets:**
  - Services: ≥85%
  - REST Resources: ≥80%
  - Data Models: ≥70% (Panache entities have generated methods)
  - DTOs/Types: No specific target (mostly data structures)

### Viewing Coverage in CI
Coverage reports are uploaded as artifacts in CI:
1. Go to GitHub Actions → Build workflow
2. Download `test-results` artifact
3. Extract and open `jacoco/index.html`

---

## Running Tests Locally

### Full Test Suite
```bash
# Run everything (unit + integration + E2E)
./mvnw verify jacoco:report && npm run test:e2e
```

### Quick Feedback Loop
```bash
# Run only unit tests (fast)
./mvnw test -Dtest=*ServiceTest

# Run only integration tests
./mvnw test -Dtest=*ResourceTest

# Run single test method
./mvnw test -Dtest=UserPreferenceServiceTest#testCreateDefaultPreferences
```

### Watch Mode
```bash
# Quarkus continuous testing (auto-run on file changes)
./mvnw quarkus:dev
# Press 'r' in terminal to re-run tests
```

---

## CI Pipeline

The CI pipeline (`.github/workflows/build.yml`) runs the following test stages:

1. **Formatting Check** (`./mvnw spotless:check`)
2. **Secret Scanning** (`npm run lint:secrets`)
3. **Lint TypeScript** (`npm run lint`)
4. **Type Check** (`npm run typecheck`)
5. **OpenAPI Validation** (`npm run openapi:validate`)
6. **Frontend Build** (`npm run build`)
7. **Unit + Integration Tests** (`./mvnw verify`)
8. **Coverage Report** (`./mvnw jacoco:report`)
9. **E2E Tests** (`npm run test:e2e`)
10. **SonarCloud Scan** (optional, when configured)
11. **Container Build** (`./mvnw jib:dockerBuild`)

### Test Artifacts
On test failure, the following artifacts are uploaded:
- `test-results/` - Surefire/Failsafe reports
- `playwright-report/` - E2E test results, screenshots, videos
- `target/site/jacoco/` - Coverage reports

### Viewing Test Results
1. Navigate to GitHub Actions → Build workflow run
2. Click on failed step to see logs
3. Download artifacts for detailed reports

---

## Troubleshooting

### Common Issues

#### 1. H2 Database Errors in Tests
**Symptom:** `Table "XYZ" not found` or SQL syntax errors

**Cause:** H2 compatibility mode with PostgreSQL

**Solution:**
- Verify `MODE=PostgreSQL` in JDBC URL
- Check migrations run before tests (`@BeforeEach`)
- Use `DATABASE_TO_LOWER=TRUE` for case-insensitive tables

#### 2. PostGIS Functions Not Available
**Symptom:** `Function "ST_DWITHIN" not found`

**Cause:** H2 does not support PostGIS

**Solution:**
- Skip radius search tests in H2: `@Test @Disabled("PostGIS not available in H2")`
- Use Testcontainers with Postgis image for full integration tests

#### 3. Playwright Tests Timeout
**Symptom:** `Timeout 30000ms exceeded waiting for selector`

**Cause:** Quarkus dev server not ready or page not loaded

**Solution:**
- Increase timeout in `playwright.config.ts`: `timeout: 60000`
- Use `page.waitForLoadState('networkidle')` before assertions
- Check dev server logs for errors

#### 4. Flaky E2E Tests
**Symptom:** Tests pass locally but fail in CI

**Cause:** Race conditions, timing issues, or test pollution

**Solution:**
- Use explicit waits: `page.waitForSelector()` instead of `setTimeout()`
- Clean up test data in `afterEach` hooks
- Use `test.describe.serial()` for tests that must run in order
- Enable retries: `retries: 1` in `playwright.config.ts`

#### 5. Low Coverage After Tests Pass
**Symptom:** Coverage <80% even though all tests pass

**Cause:** Failing tests prevent code paths from executing

**Solution:**
- Fix failing tests first
- Add tests for uncovered branches
- Review JaCoCo report: `target/site/jacoco/index.html`
- Identify untested code paths and add test cases

#### 6. Load Tests Fail with High Latency
**Symptom:** p95 >200ms during load test

**Cause:** Insufficient resources, unoptimized queries, or missing indexes

**Solution:**
- Check Elasticsearch cluster health
- Review PostGIS query plans: `EXPLAIN ANALYZE`
- Verify database indexes exist: `\d marketplace_listings`
- Scale application instances (2-3 pods)
- Profile slow queries in application logs

---

## Testing Checklist

Before merging a PR:
- [ ] All unit tests pass (`./mvnw test`)
- [ ] All integration tests pass (`./mvnw verify`)
- [ ] Coverage meets ≥80% target (`./mvnw jacoco:check`)
- [ ] E2E tests pass (`npm run test:e2e`)
- [ ] Code is formatted (`./mvnw spotless:apply`)
- [ ] No secrets leaked (`npm run lint:secrets`)
- [ ] OpenAPI spec is valid (`npm run openapi:validate`)
- [ ] CI pipeline is green

Before releasing to production:
- [ ] Load tests pass with KPI targets met
- [ ] Manual smoke tests on beta environment
- [ ] Feature flags configured for gradual rollout
- [ ] Monitoring dashboards created/updated
- [ ] Runbooks documented for new features

---

## Additional Resources

- **Quarkus Testing Guide:** https://quarkus.io/guides/getting-started-testing
- **Playwright Documentation:** https://playwright.dev/docs/intro
- **k6 Load Testing:** https://k6.io/docs/
- **JaCoCo Maven Plugin:** https://www.jacoco.org/jacoco/trunk/doc/maven.html
- **RestAssured Guide:** https://rest-assured.io/

---

**Document Version:** 1.0
**Last Updated:** 2026-01-10
**Task:** I4.T9
