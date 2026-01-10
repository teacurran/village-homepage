# Good Sites Screenshot Capture Operations

## Overview

This guide covers operational procedures for the Good Sites directory screenshot capture system. Screenshots are automatically captured when sites are submitted and can be manually recaptured by administrators.

**Related Documentation:**
- [Good Sites Setup](./good-sites-setup.md) - Category and site creation
- [Good Sites Submission](./good-sites-submission.md) - Submission flow and moderation
- [Storage Operations](./storage.md) - R2 storage and CDN configuration

**Policy References:**
- **P4: Indefinite Retention** - Screenshots retained indefinitely with full version history
- **P12: Concurrency Limits** - Max 3 concurrent browser instances per pod

---

## Architecture

### Components

1. **ScreenshotService** - Browser pool management with semaphore-limited concurrency
2. **ScreenshotCaptureJobHandler** - Job execution for screenshot capture workflow
3. **DirectoryScreenshotVersion** - Version history entity (database table)
4. **jvppeteer** - Java Puppeteer wrapper for headless Chromium automation
5. **StorageGateway** - R2 upload/download with CDN URL generation

### Capture Workflow

```
1. User submits site → DirectoryService creates DirectorySite record
2. DirectoryService enqueues SCREENSHOT_CAPTURE job (JobQueue.SCREENSHOT)
3. ScreenshotCaptureJobHandler acquires semaphore slot (max 3 concurrent)
4. Handler launches headless Chromium via jvppeteer
5. Browser navigates to URL with 30-second timeout
6. Capture full screenshot (1280x800 viewport)
7. Capture thumbnail screenshot (320x200 viewport)
8. Upload both variants to R2 via StorageGateway
9. Create directory_screenshot_versions record
10. Update DirectorySite.screenshot_url and screenshot_captured_at
11. Release semaphore slot
12. On failure: Log error, create failed version record, fallback to og_image_url
```

### Screenshot Variants

| Variant | Dimensions | Use Case | Storage Key Format |
|---------|------------|----------|-------------------|
| Thumbnail | 320x200 | Category browse pages, site lists | `{site-id}/v{version}/thumbnail.webp` |
| Full | 1280x800 | Site detail pages, previews | `{site-id}/v{version}/full.webp` |

---

## Browser Pool Configuration

### Policy P12: Concurrency Limits

**Semaphore Configuration:**
- Max concurrent browsers: **3 per pod**
- Memory per browser: ~200-300 MB
- Total memory impact: ~1 GB max per pod

**Why 3 concurrent browsers?**
- Memory constraints: Each Chromium instance consumes 200-300 MB
- Pod memory limit: Typically 2 GB (leaves room for application heap)
- Horizontal scaling: Multiple pods can run concurrently

**Monitoring Semaphore Health:**
```java
// Check available browser slots (should return 3 when idle)
int available = screenshotService.getAvailableBrowserSlots();
```

**Metrics:**
- `screenshot.browser.active` - Gauge showing active browser count (should be 0-3)
- `screenshot.captures.total{status=success}` - Counter for successful captures
- `screenshot.captures.total{status=failed}` - Counter for failed captures
- `screenshot.captures.total{status=timeout}` - Counter for timeout captures

---

## Performance Characteristics

### Capture Duration Breakdown

| Phase | Duration | Notes |
|-------|----------|-------|
| Browser launch | 2-3 seconds | Chromium startup |
| Page load | 5-15 seconds | Depends on site complexity |
| Screenshot capture | <1 second | PNG generation |
| **Total** | **10-20 seconds** | Per variant (2 captures total) |

**Total job duration:** 20-40 seconds (full + thumbnail)

### Timeout Configuration

- **Browser launch timeout:** 30 seconds
- **Page navigation timeout:** 30 seconds
- **Network idle timeout:** 10 seconds (best-effort, not required)

**Retry Strategy:**
- Max retries: 2
- Backoff: Exponential
- Final action: Create failed version record, keep og_image_url as fallback

---

## Version History (Policy P4)

### Indefinite Retention

All screenshot versions are retained **indefinitely** in R2 storage. Old versions are never automatically deleted.

**Use Cases:**
- Audit trail of site changes over time
- Comparison between current and historical screenshots
- Recovery from bad captures (admin can revert to previous version)

### Version Increment Logic

```sql
-- Get next version number for a site
SELECT COALESCE(MAX(version), 0) + 1 FROM directory_screenshot_versions WHERE site_id = ?;

-- Example version progression:
-- Initial capture: version 1
-- Admin recapture: version 2
-- Another recapture: version 3
-- ... (no limit)
```

### Storage Keys

**Object key pattern:** `{site-id}/v{version}/{variant}.webp`

**Examples:**
```
site-123e4567-e89b-12d3-a456-426614174000/v1/thumbnail.webp
site-123e4567-e89b-12d3-a456-426614174000/v1/full.webp
site-123e4567-e89b-12d3-a456-426614174000/v2/thumbnail.webp  (recapture)
site-123e4567-e89b-12d3-a456-426614174000/v2/full.webp      (recapture)
```

### Querying Version History

```java
// Get all versions for a site (newest first)
List<DirectoryScreenshotVersion> versions = DirectoryScreenshotVersion.findBySiteId(siteId);

// Get latest version number
int latestVersion = DirectoryScreenshotVersion.getLatestVersion(siteId);

// Get specific version
Optional<DirectoryScreenshotVersion> v2 = DirectoryScreenshotVersion.findByVersion(siteId, 2);

// Find failed captures for retry analysis
List<DirectoryScreenshotVersion> failed = DirectoryScreenshotVersion.findFailedCaptures();
```

---

## Failure Handling

### Failure Types

| Status | Cause | Example | Fallback |
|--------|-------|---------|----------|
| `timeout` | Page load exceeds 30 seconds | Slow site, infinite redirects | og_image_url |
| `failed` | Network error, DNS failure | Domain doesn't exist, firewall block | og_image_url |
| `failed` | SSL error | Invalid certificate, expired cert | og_image_url |
| `failed` | JavaScript error | Page throws exception during load | og_image_url |

### Failed Version Records

Even failed captures create version records for audit trail:

```sql
SELECT * FROM directory_screenshot_versions WHERE status IN ('failed', 'timeout');

-- Example failed record:
-- version: 1
-- thumbnail_storage_key: '' (empty)
-- full_storage_key: '' (empty)
-- status: 'timeout'
-- error_message: 'Page load timeout: https://slow-site.com'
-- capture_duration_ms: 30500
```

### OpenGraph Fallback

When screenshot capture fails, DirectorySite keeps `og_image_url` for display:

```java
DirectorySite site = DirectorySite.findById(siteId);

// If screenshot_url is null, use og_image_url
String imageUrl = site.screenshotUrl != null ? site.screenshotUrl : site.ogImageUrl;
```

---

## Troubleshooting

### Browser Launch Failures

**Symptom:** Jobs fail immediately with "Failed to launch browser"

**Possible Causes:**
1. Chromium not installed in container
2. Missing system libraries
3. Pod memory limit too low

**Solution:**

Check Dockerfile includes Chromium dependencies:
```dockerfile
# Install Chromium and dependencies
RUN apk add --no-cache \
    chromium \
    nss \
    freetype \
    harfbuzz \
    ttf-freefont \
    libx11 \
    libxcomposite \
    libxdamage \
    libxext \
    libxfixes \
    libxrandr \
    libgbm \
    ca-certificates

# Set Chromium executable path for jvppeteer
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium-browser
```

**Memory Check:**
```bash
# Check pod memory limits
kubectl describe pod <pod-name> | grep -A 5 Limits

# Increase if needed (minimum 1 GB recommended)
resources:
  limits:
    memory: 2Gi
  requests:
    memory: 1Gi
```

### Semaphore Deadlock

**Symptom:** All screenshot jobs stuck in "running" state, no progress

**Cause:** Semaphore not released due to uncaught exception

**Detection:**
```bash
# Check active browser slots (should be 0-3)
curl http://localhost:8080/q/health | jq '.checks[] | select(.name == "screenshot-service")'
```

**Solution:**

Force release semaphore (requires application restart):
```bash
# Restart pod to reset semaphore state
kubectl rollout restart deployment village-homepage

# Verify jobs resume after restart
kubectl logs -f deployment/village-homepage | grep "Screenshot capture"
```

**Prevention:** All `ScreenshotService.captureScreenshot()` calls must be in try-finally block with semaphore release.

### Timeout on Complex Sites

**Symptom:** Captures consistently timeout on specific sites (e.g., heavy JavaScript SPAs)

**Workaround:**

1. Check if site is actually accessible:
```bash
curl -I https://problem-site.com
```

2. If accessible, increase timeout (requires code change):
```java
// In ScreenshotService.java
GoToOptions goToOptions = new GoToOptions();
goToOptions.setTimeout(60000);  // Increase from 30s to 60s
```

3. If site uses aggressive bot detection, add user-agent override:
```java
page.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
```

### R2 Upload Failures

**Symptom:** Screenshots captured successfully but upload fails

**Logs:**
```
Screenshot captured successfully: 123456 bytes (url=https://example.com)
ERROR: Failed to upload screenshot to R2: AccessDenied
```

**Solution:**

Check R2 credentials and bucket permissions:
```bash
# Verify R2 credentials in application.yml
grep -A 5 "aws:" src/main/resources/application.yml

# Test bucket write access
aws s3 cp test.txt s3://screenshots/test.txt --endpoint-url=https://YOUR_ACCOUNT.r2.cloudflarestorage.com
```

---

## Monitoring and Alerts

### Key Metrics

```promql
# Capture success rate (should be >95%)
rate(screenshot_captures_total{status="success"}[5m]) /
rate(screenshot_captures_total[5m])

# Average capture duration (should be <30s)
histogram_quantile(0.95, rate(screenshot_capture_duration_bucket[5m]))

# Failed capture rate (alert if >5%)
rate(screenshot_captures_total{status="failed"}[5m]) /
rate(screenshot_captures_total[5m]) > 0.05

# Timeout rate (alert if >10%)
rate(screenshot_captures_total{status="timeout"}[5m]) /
rate(screenshot_captures_total[5m]) > 0.10

# Browser pool exhaustion (alert if sustained)
screenshot_browser_active == 3 for 5m
```

### Recommended Alerts

1. **High failure rate:** >5% captures failing (network issues, invalid sites)
2. **High timeout rate:** >10% captures timing out (slow sites, overloaded pod)
3. **Browser pool exhaustion:** All 3 slots occupied for >5 minutes (backlog building)
4. **R2 upload failures:** Any upload errors (credential issues, quota exceeded)

### Grafana Dashboard

Import dashboard from `docs/ops/dashboards/good-sites-screenshots.json`:

**Panels:**
- Capture rate (success/failed/timeout) - Time series graph
- Average capture duration - Histogram
- Browser pool utilization - Gauge (0-3)
- Version history growth - Counter
- Top failed sites - Table

---

## Manual Operations

### Trigger Manual Recapture

**Use Case:** Admin wants to update screenshot for outdated or incorrect capture

**Steps:**

1. Navigate to Good Sites admin moderation queue
2. Find site in listing (filter by category if needed)
3. Click "Recapture Screenshot" button
4. Job is enqueued with `isRecapture: true` payload
5. New version created (e.g., v1 → v2)
6. Old version remains in R2 and version history table

**Via API (Admin only):**
```bash
curl -X POST https://homepage.villagecompute.com/admin/api/directory/sites/{site-id}/recapture \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Via Database (Emergency):**
```sql
-- Manually enqueue screenshot job
INSERT INTO delayed_jobs (job_type, queue, payload, status, created_at, updated_at)
VALUES (
  'SCREENSHOT_CAPTURE',
  'SCREENSHOT',
  '{"siteId": "123e4567-e89b-12d3-a456-426614174000", "url": "https://example.com", "isRecapture": true}',
  'pending',
  NOW(),
  NOW()
);
```

### View Screenshot Version History

**Query all versions for a site:**
```sql
SELECT
  version,
  status,
  captured_at,
  capture_duration_ms,
  error_message
FROM directory_screenshot_versions
WHERE site_id = '123e4567-e89b-12d3-a456-426614174000'
ORDER BY version DESC;
```

**Compare two versions (check if site changed):**
```bash
# Download both versions from R2
aws s3 cp s3://screenshots/site-123/v1/full.webp v1.webp
aws s3 cp s3://screenshots/site-123/v2/full.webp v2.webp

# Compare file sizes (large difference indicates visual change)
ls -lh v1.webp v2.webp

# Visual comparison (requires imagemagick)
compare v1.webp v2.webp diff.webp
```

### Retry Failed Captures

**Find all failed captures:**
```java
List<DirectoryScreenshotVersion> failed = DirectoryScreenshotVersion.findFailedCaptures();

// Filter for recent failures (last 24 hours)
Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
List<DirectoryScreenshotVersion> recentFailed = failed.stream()
  .filter(v -> v.capturedAt.isAfter(yesterday))
  .toList();

// Re-enqueue jobs for each failed site
for (DirectoryScreenshotVersion version : recentFailed) {
  DirectorySite site = DirectorySite.findById(version.siteId);
  if (site != null) {
    // Enqueue recapture job
    delayedJobService.enqueue(JobType.SCREENSHOT_CAPTURE, JobQueue.SCREENSHOT,
      Map.of("siteId", site.id.toString(), "url", site.url, "isRecapture", true));
  }
}
```

---

## Storage Management

### Bucket Configuration

**Bucket:** `screenshots` (Cloudflare R2)

**Retention Policy:** Indefinite (Policy P4)

**Object Metadata:**
```json
{
  "retention-policy": "indefinite",
  "site-id": "123e4567-e89b-12d3-a456-426614174000",
  "version": "1",
  "variant": "full",
  "content-type": "image/webp"
}
```

### CDN Integration

**Signed URL Generation:**
```java
// Generate 7-day signed URL for public access
String signedUrl = storageGateway.generateSignedUrl(
  BucketType.SCREENSHOTS,
  "site-123/v1/full.webp",
  10080  // 7 days in minutes
);
```

**Cache Headers:**
- `Cache-Control: public, max-age=604800` (7 days)
- `ETag: <object-version-id>`

### Storage Costs

**Estimated monthly costs (Cloudflare R2):**
- Storage: $0.015/GB/month
- Operations: Class A (write): $4.50 per million, Class B (read): $0.36 per million
- Bandwidth: FREE (no egress charges)

**Example calculation (10,000 sites):**
- Avg screenshot size: 200 KB (thumbnail) + 800 KB (full) = 1 MB total per version
- Total storage: 10,000 sites × 1 MB = 10 GB
- Monthly cost: 10 GB × $0.015 = **$0.15/month**

**With recaptures (avg 2 versions per site):**
- Total storage: 20 GB
- Monthly cost: **$0.30/month**

---

## Security Considerations

### SSRF Protection

**Risk:** Malicious user submits internal URL (e.g., `http://localhost:8080/admin`)

**Mitigation:**

1. URL validation in DirectoryService before enqueue:
```java
// Block localhost and private IPs
if (url.contains("localhost") || url.contains("127.0.0.1") || url.contains("192.168.")) {
  throw new ValidationException("Internal URLs not allowed");
}
```

2. Network policy in Kubernetes (block pod egress to internal services)
3. Chromium sandbox flags (`--no-sandbox` only in development)

### Browser Isolation

**Each browser instance runs in isolated process:**
- No shared state between captures
- Cookies/localStorage cleared between sessions
- No access to pod filesystem (besides temporary screenshot storage)

### Resource Limits

**Pod resource limits prevent DoS:**
```yaml
resources:
  limits:
    memory: 2Gi
    cpu: 2000m
  requests:
    memory: 1Gi
    cpu: 500m
```

---

## Future Enhancements

**Planned improvements (not in I5.T3 scope):**

1. **Smart thumbnail cropping** - Detect important content regions, crop intelligently
2. **Screenshot comparison** - Automatically flag significant visual changes
3. **Scheduled recapture** - Monthly refresh for top-ranked sites
4. **Mobile viewport** - Capture additional mobile variant (375x667)
5. **Video preview** - Short screen recording for interactive sites
6. **Dark mode detection** - Capture both light/dark theme variants
7. **Accessibility checks** - Integrate axe-core for automated a11y scanning

---

## Related Tables

### directory_screenshot_versions

```sql
CREATE TABLE directory_screenshot_versions (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL REFERENCES directory_sites(id) ON DELETE CASCADE,
    version INT NOT NULL,
    thumbnail_storage_key TEXT NOT NULL,
    full_storage_key TEXT NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    capture_duration_ms INT,
    status TEXT NOT NULL,  -- success, failed, timeout
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_directory_screenshot_versions_site_version
        UNIQUE (site_id, version)
);
```

**Indexes:**
- `uq_directory_screenshot_versions_site_version` - Unique version per site
- `idx_directory_screenshot_versions_site_id` - Latest version queries
- `idx_directory_screenshot_versions_status` - Failed capture monitoring
- `idx_directory_screenshot_versions_duration` - Performance analysis

---

## Support and Escalation

**Common issues:**
- Browser launch failures → Check Dockerfile and memory limits
- Timeout on all captures → Check pod CPU/memory utilization
- R2 upload failures → Verify credentials and bucket permissions

**Escalation path:**
1. Check logs: `kubectl logs -f deployment/village-homepage | grep Screenshot`
2. Check metrics: Grafana dashboard "Good Sites Screenshots"
3. Verify queue health: `/admin/api/jobs/status`
4. Manual retry: Enqueue job via admin API or database
5. If unresolved: Create incident ticket with logs and metrics snapshot

**Related documentation:**
- [Async Workloads](./async-workloads.md) - Delayed job system overview
- [Storage Operations](./storage.md) - R2 bucket configuration
- [Observability](./observability.md) - Metrics and logging setup
