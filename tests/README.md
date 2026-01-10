# Tests Directory

This directory contains E2E tests (Playwright) and load tests (k6) for Village Homepage.

## Directory Structure

```
tests/
├── e2e/              # Playwright end-to-end tests
│   └── marketplace.spec.ts
├── fixtures/         # Test data fixtures
│   └── marketplace.ts
├── helpers/          # Test utilities
│   └── auth.ts
├── load/             # k6 load tests
│   └── marketplace-search.js
└── README.md         # This file
```

## Running Tests

### E2E Tests (Playwright)

```bash
# Start dev server (required)
./mvnw quarkus:dev

# Run all E2E tests
npm run test:e2e

# Run with UI (interactive mode)
npm run test:e2e:ui

# Run in headed mode (see browser)
npm run test:e2e:headed

# Run specific test file
npx playwright test marketplace.spec.ts

# View test report
npx playwright show-report
```

### Load Tests (k6)

```bash
# Install k6 first
brew install k6  # macOS
# OR: sudo apt-get install k6  # Linux
# OR: choco install k6  # Windows

# Run load test
k6 run tests/load/marketplace-search.js

# Custom configuration
k6 run --vus 50 --duration 5m tests/load/marketplace-search.js
```

## Test Status

**Current Status:** Most E2E tests are **skipped** (`test.skip()`) pending:
- Stripe test mode integration
- Database seeding with fixtures
- Frontend components with `data-testid` attributes
- Authentication enabled with test mode

**Ready to Run:**
- `loads marketplace homepage` - Basic page load test
- Load tests (requires running server + seeded data)

## Prerequisites

### E2E Tests
- Quarkus dev server running on http://localhost:8080
- Playwright browsers installed: `npx playwright install`
- (Optional) Test user accounts configured
- (Optional) Stripe test mode keys for payment flows

### Load Tests
- k6 installed
- Quarkus server running
- Database seeded with 1000+ listings
- Elasticsearch indexed

## CI Integration

E2E tests run automatically in GitHub Actions CI pipeline:
- Stage: After unit/integration tests, before container build
- Browsers: Chromium only in CI
- Artifacts: Screenshots, videos, traces uploaded on failure

See `.github/workflows/build.yml` for configuration.

## Documentation

- **Full Testing Guide:** `docs/ops/testing.md`
- **Analytics:** `docs/ops/analytics.md`
- **Playwright Config:** `playwright.config.ts`

## Troubleshooting

**Playwright tests timeout?**
- Ensure dev server is running: `./mvnw quarkus:dev`
- Check baseURL in playwright.config.ts
- Increase timeout: Edit `timeout` in config

**Load tests fail?**
- Verify server is running: `curl http://localhost:8080/q/health`
- Check database is seeded
- Review k6 error logs for specific failures

**Can't install Playwright browsers?**
```bash
# Manual installation with dependencies
npx playwright install --with-deps chromium
```

---

For more details, see `docs/ops/testing.md`
