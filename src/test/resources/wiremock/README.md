# WireMock Stub Files

This directory contains JSON stub files for mocking external API responses in integration tests.

## Purpose

These stubs allow tests to run without making actual HTTP calls to external APIs. This:
- Prevents rate limit errors (e.g., Alpha Vantage's 25 requests/day limit)
- Eliminates network dependencies (tests run offline)
- Ensures consistent test data (no flaky tests from changing API responses)
- Speeds up test execution (no network latency)

## Directory Structure

```
wiremock/
├── alpha-vantage/      # Stock market data API stubs
├── open-meteo/         # International weather API stubs
├── nws/                # US National Weather Service stubs
├── meta-graph/         # Facebook/Instagram API stubs
└── anthropic/          # Claude AI API stubs
```

## Naming Conventions

Stub files follow the pattern: `{api-name}/{endpoint-name}-{scenario}.json`

Examples:
- `alpha-vantage/stock-quote-success.json` → Successful stock quote response
- `alpha-vantage/stock-quote-rate-limit.json` → Rate limit error response (future)
- `anthropic/ai-tagging-success.json` → Successful AI tagging response

## How to Use in Tests

Extend `WireMockTestBase` and use helper methods:

```java
@QuarkusTest
public class MyTest extends WireMockTestBase {
    @Test
    @TestTransaction
    void testWithStub() {
        stubAlphaVantageStockQuote("AAPL");
        // Your test code that calls AlphaVantageClient...
    }
}
```

## Available Helper Methods

### Alpha Vantage (Stock Data)
```java
stubAlphaVantageStockQuote("AAPL");                           // Uses default stub
stubAlphaVantageStockQuote("AAPL", customJsonResponse);       // Custom response
```

### Open-Meteo (International Weather)
```java
stubOpenMeteoForecast(37.7749, -122.4194);                    // San Francisco coordinates
```

### National Weather Service (US Weather)
```java
stubNwsForecast("MTR", 90, 105);                              // MTR = San Francisco Bay Area office
```

### Meta Graph API (Instagram/Facebook)
```java
stubMetaGraphInstagramPosts("17841405793187218");             // Instagram user ID
```

### Anthropic Claude (AI Tagging)
```java
stubAnthropicAiTagging();                                     // Uses default stub
stubAnthropicAiTagging(customJsonResponse);                   // Custom response
```

## How to Add New Stubs

1. Create directory for the API if it doesn't exist:
   ```bash
   mkdir -p src/test/resources/wiremock/{api-name}
   ```

2. Create JSON file with realistic response data matching the actual API schema

3. Validate JSON syntax:
   ```bash
   cat stub.json | jq .   # Requires jq tool
   ```

4. Add helper method in `WireMockTestBase` to load and configure the stub

5. Document the stub in this README

## How to Update Stubs

When external API schemas change:

1. Check API documentation for new/changed fields
2. Update corresponding stub JSON file
3. Run tests to verify no breaking changes:
   ```bash
   mvn test
   ```
4. Update helper methods if endpoint URLs or parameters changed

## Stub File Contents

### Alpha Vantage Stock Quote
- **File:** `alpha-vantage/stock-quote-success.json`
- **Schema:** Global Quote object with symbol, price, volume, change data
- **Example:** AAPL stock at $174.50 with 1.36% gain

### Open-Meteo Weather Forecast
- **File:** `open-meteo/weather-forecast-success.json`
- **Schema:** Hourly and daily forecast arrays with temperature and weather codes
- **Example:** San Francisco forecast with 12-hour hourly data and 3-day daily data

### National Weather Service Forecast
- **File:** `nws/weather-us-success.json`
- **Schema:** JSON-LD Feature with periods array containing forecast periods
- **Example:** San Francisco Bay Area forecast with 3 periods (Today, Tonight, Tuesday)

### Meta Graph API Instagram Posts
- **File:** `meta-graph/instagram-posts-success.json`
- **Schema:** Data array with Instagram media objects, includes paging cursors
- **Example:** 3 Instagram posts (IMAGE, CAROUSEL_ALBUM) with engagement metrics

### Anthropic Claude AI Tagging
- **File:** `anthropic/ai-tagging-success.json`
- **Schema:** Message object with content array containing structured JSON response
- **Example:** AI-tagged article with topics, sentiment, category, keywords

## Reference

- **WireMock documentation:** http://wiremock.org/docs/
- **Alpha Vantage API:** https://www.alphavantage.co/documentation/
- **Open-Meteo API:** https://open-meteo.com/en/docs
- **NWS API:** https://www.weather.gov/documentation/services-web-api
- **Meta Graph API:** https://developers.facebook.com/docs/graph-api/
- **Anthropic API:** https://docs.anthropic.com/claude/reference/

## Testing Strategy Reference

This WireMock integration implements the testing strategy from Foundation Blueprint Section 3.5:

> **Mock only at system boundaries: WireMock for external HTTP APIs**
>
> Do NOT mock internal services. Integration tests should use real database (Testcontainers PostgreSQL 17 + PostGIS)
> and real internal service implementations. The ONLY acceptable mocking is external HTTP APIs.

**Task Reference:** I1.T5 (WireMock Integration)
