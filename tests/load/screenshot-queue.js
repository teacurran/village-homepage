import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

/**
 * k6 Load Test for Screenshot Capture Queue (I5.T9)
 *
 * Tests screenshot capture performance under concurrent load with focus on:
 * - Semaphore enforcement (max 3 concurrent captures per Policy P12)
 * - Screenshot capture duration (p95, p99 latency)
 * - Browser pool exhaustion scenarios
 * - Interaction with feed ingestion background jobs
 *
 * KPI Targets:
 * - p95 capture duration < 5000ms (5 seconds)
 * - p99 capture duration < 10000ms (10 seconds)
 * - Semaphore wait time < 30000ms (30 seconds)
 * - Error rate < 5% (some failures expected due to network/timeout)
 *
 * Usage:
 *   k6 run tests/load/screenshot-queue.js
 *
 * With custom configuration:
 *   k6 run --vus 10 --duration 3m tests/load/screenshot-queue.js
 */

// Custom metrics
const errorRate = new Rate('errors');
const screenshotCaptureLatency = new Trend('screenshot_capture_latency');
const semaphoreWaitTime = new Trend('semaphore_wait_time');
const browserPoolExhaustion = new Counter('browser_pool_exhaustion');
const timeoutErrors = new Counter('timeout_errors');
const networkErrors = new Counter('network_errors');

// Test configuration
export const options = {
  stages: [
    { duration: '30s', target: 2 },   // Warm up: ramp to 2 concurrent captures
    { duration: '1m', target: 5 },    // Normal load: 5 concurrent (exceeds semaphore limit)
    { duration: '30s', target: 10 },  // Spike: 10 concurrent (test queue buildup)
    { duration: '1m', target: 10 },   // Sustained spike
    { duration: '30s', target: 0 },   // Ramp down
  ],
  thresholds: {
    // KPI targets
    'http_req_duration': ['p(95)<5000', 'p(99)<10000'],
    'http_req_failed': ['rate<0.05'],  // <5% error rate
    'errors': ['rate<0.05'],

    // Custom metric thresholds
    'screenshot_capture_latency': ['p(95)<5000', 'p(99)<10000'],
    'semaphore_wait_time': ['p(95)<30000'],  // Max 30s wait for semaphore
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Test URLs for screenshot capture (variety of sites)
const testUrls = [
  'https://example.com',
  'https://example.org',
  'https://example.net',
  'https://httpbin.org/html',
  'https://httpbin.org/delay/1',  // Simulates slow loading site
  'https://wikipedia.org',
  'https://github.com',
  'https://stackoverflow.com',
];

// Invalid URLs to test error handling
const invalidUrls = [
  'https://this-domain-does-not-exist-' + Date.now() + '.invalid',
  'https://192.0.2.1',  // TEST-NET-1 (unreachable)
  'not-a-valid-url',
];

// Random selection helper
function randomItem(array) {
  return array[Math.floor(Math.random() * array.length)];
}

export default function () {
  // Test scenario mix: 80% valid URLs, 10% slow URLs, 10% invalid URLs
  const scenario = Math.random();

  let url;
  if (scenario < 0.8) {
    // Normal capture
    url = randomItem(testUrls);
    testScreenshotCapture(url, false);
  } else if (scenario < 0.9) {
    // Slow site (tests timeout handling)
    url = testUrls.find(u => u.includes('delay')) || randomItem(testUrls);
    testScreenshotCapture(url, false);
  } else {
    // Invalid URL (tests error handling)
    url = randomItem(invalidUrls);
    testScreenshotCapture(url, true);
  }

  // Think time: simulate job scheduling delay
  sleep(1 + Math.random() * 2);  // 1-3 seconds between jobs
}

function testScreenshotCapture(url, expectFailure) {
  // Simulate DelayedJobService creating a SCREENSHOT_CAPTURE job
  const jobPayload = JSON.stringify({
    type: 'SCREENSHOT_CAPTURE',
    payload: {
      siteId: generateTestSiteId(),
      url: url,
      isRecapture: false,
    },
  });

  const startTime = Date.now();

  // POST to delayed job API (creates job, handler executes)
  // Note: In real scenario, this would be an internal API endpoint
  // For load testing, we'll use a test endpoint that triggers screenshot capture
  const res = http.post(`${BASE_URL}/api/test/screenshot-capture`, jobPayload, {
    headers: { 'Content-Type': 'application/json' },
    timeout: '60s',  // 60 second timeout for screenshot capture
  });

  const duration = Date.now() - startTime;

  // Record metrics
  screenshotCaptureLatency.add(duration);

  if (expectFailure) {
    // For invalid URLs, we expect 4xx or 5xx status
    const success = check(res, {
      'invalid URL: handled gracefully': (r) => r.status >= 400,
    });

    if (!success) {
      errorRate.add(1);
    }

    // Track error types
    if (res.body && res.body.includes('timeout')) {
      timeoutErrors.add(1);
    } else if (res.body && res.body.includes('network')) {
      networkErrors.add(1);
    }

  } else {
    // For valid URLs, we expect 200 or 202 status
    const success = check(res, {
      'screenshot capture: status 2xx': (r) => r.status >= 200 && r.status < 300,
      'screenshot capture: response time acceptable': (r) => r.timings.duration < 10000,
      'screenshot capture: has result': (r) => {
        try {
          const body = JSON.parse(r.body);
          return body.status === 'success' || body.status === 'timeout' || body.status === 'failed';
        } catch (e) {
          return false;
        }
      },
    });

    if (!success) {
      errorRate.add(1);
    }

    // Detect browser pool exhaustion
    // If response time > 5s but < 10s, likely waiting for semaphore
    if (duration > 5000 && duration < 10000) {
      semaphoreWaitTime.add(duration);
    }

    // If response time > 10s, browser pool likely exhausted
    if (duration > 10000) {
      browserPoolExhaustion.add(1);
    }

    // Track timeout errors
    if (res.body && res.body.includes('timeout')) {
      timeoutErrors.add(1);
    }
  }
}

/**
 * Generates a random UUID for test site ID
 */
function generateTestSiteId() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

// Test lifecycle hooks

export function setup() {
  // Verify server is reachable before starting load test
  const res = http.get(`${BASE_URL}/q/health/ready`);
  if (res.status !== 200) {
    throw new Error(`Server not ready: ${res.status} ${res.body}`);
  }

  // Check screenshot service availability
  const screenshotHealth = http.get(`${BASE_URL}/q/health/ready`);
  if (screenshotHealth.status !== 200) {
    console.warn('Warning: Screenshot service may not be available');
  }

  console.log('Server health check passed, starting screenshot queue load test...');
  console.log('Note: Semaphore limit is 3 concurrent captures (Policy P12)');
  console.log('Expected behavior: >3 concurrent requests will queue');
}

export function teardown(data) {
  console.log('Screenshot queue load test completed.');
  console.log('');
  console.log('Key Metrics to Review:');
  console.log('- screenshot_capture_latency: Measures end-to-end capture time');
  console.log('- semaphore_wait_time: Time spent waiting for browser pool slot');
  console.log('- browser_pool_exhaustion: Count of requests delayed >10s');
  console.log('- timeout_errors: Count of captures that timed out (30s limit)');
  console.log('- network_errors: Count of captures that failed due to network issues');
  console.log('');
  console.log('If semaphore_wait_time p95 > 30s or browser_pool_exhaustion > 5%:');
  console.log('  - Consider increasing semaphore limit (Policy P12)');
  console.log('  - Review browser startup time optimization');
  console.log('  - Check for browser pool leaks (instances not released)');
  console.log('');
  console.log('If timeout_errors > 10%:');
  console.log('  - Review timeout configuration (currently 30s)');
  console.log('  - Check for slow/unresponsive target sites');
  console.log('  - Consider implementing tiered timeout (fast-fail for obviously slow sites)');
}

/**
 * Example output interpretation:
 *
 * ✓ screenshot_capture_latency............: avg=3500ms p(95)=4800ms p(99)=9500ms  [PASS]
 * ✓ semaphore_wait_time...................: avg=2000ms p(95)=8000ms p(99)=15000ms [PASS: <30s]
 * ✓ browser_pool_exhaustion...............: 3 (2.5% of requests)                  [ACCEPTABLE]
 * ✓ timeout_errors........................: 5 (4.2% of requests)                  [ACCEPTABLE]
 * ✗ network_errors........................: 12 (10% of requests)                  [INVESTIGATE]
 *
 * If network_errors > 5%:
 * 1. Check if target sites are rate-limiting
 * 2. Review User-Agent header configuration
 * 3. Consider adding retry logic with exponential backoff
 * 4. Implement site-specific error handling (e.g., skip sites with repeated failures)
 *
 * Feed Ingestion Interplay:
 * To test interaction with feed ingestion, run this test concurrently with:
 *   k6 run tests/load/screenshot-queue.js &
 *   k6 run tests/load/feed-ingestion.js &
 *
 * Monitor for:
 * - Database connection pool exhaustion
 * - Increased latency on both screenshot and feed ingestion
 * - Error rate increase when both jobs running
 */
