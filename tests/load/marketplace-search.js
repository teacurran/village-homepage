import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

/**
 * k6 Load Test for Marketplace Search (I4.T9)
 *
 * Tests search performance under load with focus on:
 * - Basic keyword search
 * - Radius-based PostGIS queries
 * - Complex filter combinations
 *
 * KPI Targets:
 * - p95 latency < 200ms
 * - p99 latency < 500ms
 * - Error rate < 1%
 *
 * Usage:
 *   k6 run tests/load/marketplace-search.js
 *
 * With custom thresholds:
 *   k6 run --vus 50 --duration 5m tests/load/marketplace-search.js
 */

// Custom metrics
const errorRate = new Rate('errors');
const searchLatency = new Trend('search_latency');
const radiusSearchLatency = new Trend('radius_search_latency');
const complexSearchLatency = new Trend('complex_search_latency');

// Test configuration
export const options = {
  stages: [
    { duration: '1m', target: 10 },  // Warm up: ramp to 10 users
    { duration: '3m', target: 20 },  // Normal load: 20 concurrent users
    { duration: '1m', target: 50 },  // Spike: 50 concurrent users
    { duration: '2m', target: 50 },  // Sustained spike
    { duration: '1m', target: 0 },   // Ramp down
  ],
  thresholds: {
    // KPI targets from iteration plan
    'http_req_duration': ['p(95)<200', 'p(99)<500'],
    'http_req_failed': ['rate<0.01'],  // <1% error rate
    'errors': ['rate<0.01'],

    // Per-query-type thresholds
    'search_latency': ['p(95)<200'],
    'radius_search_latency': ['p(95)<200'],  // PostGIS queries
    'complex_search_latency': ['p(95)<250'], // Slightly higher for complex filters
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Sample search queries
const keywords = ['bicycle', 'apartment', 'furniture', 'car', 'job', 'service'];
const categories = ['for-sale', 'housing', 'jobs', 'services', 'community'];
const zipCodes = ['94102', '94110', '94122', '10001', '90210', '60601'];
const radii = [5, 10, 25, 50, 100];

// Random selection helper
function randomItem(array) {
  return array[Math.floor(Math.random() * array.length)];
}

export default function () {
  // Test scenario mix: 60% basic, 30% radius, 10% complex
  const scenario = Math.random();

  if (scenario < 0.6) {
    // Scenario 1: Basic keyword search
    testBasicSearch();
  } else if (scenario < 0.9) {
    // Scenario 2: Radius search (PostGIS)
    testRadiusSearch();
  } else {
    // Scenario 3: Complex filter combination
    testComplexSearch();
  }

  // Think time: simulate user browsing
  sleep(1 + Math.random() * 2);  // 1-3 seconds
}

function testBasicSearch() {
  const keyword = randomItem(keywords);
  const url = `${BASE_URL}/api/marketplace/search?q=${keyword}&limit=25`;

  const startTime = Date.now();
  const res = http.get(url);
  const duration = Date.now() - startTime;

  searchLatency.add(duration);

  const success = check(res, {
    'basic search: status 200': (r) => r.status === 200,
    'basic search: has results': (r) => {
      try {
        const body = JSON.parse(r.body);
        return Array.isArray(body.results);
      } catch (e) {
        return false;
      }
    },
    'basic search: response time OK': (r) => r.timings.duration < 500,
  });

  if (!success) {
    errorRate.add(1);
  }
}

function testRadiusSearch() {
  const location = randomItem(zipCodes);
  const radius = randomItem(radii);
  const url = `${BASE_URL}/api/marketplace/search?location=${location}&radius=${radius}&limit=25`;

  const startTime = Date.now();
  const res = http.get(url);
  const duration = Date.now() - startTime;

  radiusSearchLatency.add(duration);

  const success = check(res, {
    'radius search: status 200': (r) => r.status === 200,
    'radius search: has results': (r) => {
      try {
        const body = JSON.parse(r.body);
        return Array.isArray(body.results);
      } catch (e) {
        return false;
      }
    },
    'radius search: PostGIS query performance': (r) => r.timings.duration < 300,
  });

  if (!success) {
    errorRate.add(1);
  }
}

function testComplexSearch() {
  const keyword = randomItem(keywords);
  const category = randomItem(categories);
  const location = randomItem(zipCodes);
  const radius = randomItem(radii);
  const minPrice = Math.floor(Math.random() * 500);
  const maxPrice = minPrice + 500;

  const url = `${BASE_URL}/api/marketplace/search?` +
    `q=${keyword}&` +
    `category=${category}&` +
    `location=${location}&` +
    `radius=${radius}&` +
    `min_price=${minPrice}&` +
    `max_price=${maxPrice}&` +
    `sort=price_asc&` +
    `limit=25`;

  const startTime = Date.now();
  const res = http.get(url);
  const duration = Date.now() - startTime;

  complexSearchLatency.add(duration);

  const success = check(res, {
    'complex search: status 200': (r) => r.status === 200,
    'complex search: response time acceptable': (r) => r.timings.duration < 500,
    'complex search: results sorted correctly': (r) => {
      try {
        const body = JSON.parse(r.body);
        if (!Array.isArray(body.results) || body.results.length < 2) {
          return true; // Not enough results to verify sorting
        }

        // Verify price sorting (ascending)
        for (let i = 1; i < body.results.length; i++) {
          if (body.results[i].price < body.results[i - 1].price) {
            return false;
          }
        }
        return true;
      } catch (e) {
        return false;
      }
    },
  });

  if (!success) {
    errorRate.add(1);
  }
}

// Test lifecycle hooks

export function setup() {
  // Verify server is reachable before starting load test
  const res = http.get(`${BASE_URL}/q/health/ready`);
  if (res.status !== 200) {
    throw new Error(`Server not ready: ${res.status} ${res.body}`);
  }
  console.log('Server health check passed, starting load test...');
}

export function teardown(data) {
  console.log('Load test completed. Check k6 summary for metrics.');
}

/**
 * Example output interpretation:
 *
 * ✓ http_req_duration..........: avg=150ms min=50ms med=120ms max=450ms p(90)=180ms p(95)=190ms
 * ✓ search_latency..............: avg=145ms p(95)=185ms  [PASS: <200ms]
 * ✓ radius_search_latency......: avg=175ms p(95)=195ms  [PASS: <200ms]
 * ✗ complex_search_latency.....: avg=220ms p(95)=260ms  [FAIL: >250ms]
 *
 * If complex search latency exceeds threshold:
 * 1. Check Elasticsearch query performance
 * 2. Review PostGIS index usage (EXPLAIN ANALYZE)
 * 3. Consider adding Hibernate query cache
 * 4. Profile slow queries in application logs
 */
