# Object Storage Operations Guide

This document provides comprehensive operational guidance for managing Cloudflare R2 object storage in the Village Homepage application.

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Bucket Strategy](#bucket-strategy)
4. [Signed URL Policies](#signed-url-policies)
5. [WebP Conversion](#webp-conversion)
6. [Retention Metadata](#retention-metadata)
7. [Development vs Production](#development-vs-production)
8. [Configuration Reference](#configuration-reference)
9. [Monitoring & Metrics](#monitoring--metrics)
10. [Troubleshooting](#troubleshooting)
11. [Future Roadmap](#future-roadmap)

---

## Overview

The StorageGateway service provides a unified abstraction over S3-compatible object storage, implementing **Policy P4** requirements for indefinite screenshot retention with versioning.

**Key Features:**
- Cloudflare R2 for production (S3-compatible with zero egress fees)
- MinIO for local development
- WebP conversion with thumbnail/full variants (stub implementation, roadmap item)
- Signed URL generation with TTL-based access control
- Bucket prefixing strategy for organizational clarity
- Retention metadata mapping for lifecycle management
- Full OpenTelemetry tracing and Micrometer metrics

**Service Location:**
`src/main/java/villagecompute/homepage/services/StorageGateway.java`

---

## Architecture

### Component Diagram

```
┌─────────────────┐      ┌──────────────────┐      ┌─────────────────┐
│  Quarkus App    │─────▶│ StorageGateway   │─────▶│ Cloudflare R2   │
│  (Job Handlers) │      │ (S3 Abstraction) │      │ (S3-compatible) │
└─────────────────┘      └──────────────────┘      └─────────────────┘
                                  │
                                  ▼
                         ┌──────────────────┐
                         │  S3Presigner     │
                         │ (Signed URLs)    │
                         └──────────────────┘
```

### Data Flow

1. **Upload Flow:**
   - Application calls `storageGateway.upload(bucket, entityId, variant, bytes, contentType)`
   - Service generates object key with prefix: `{bucket}/{entityId}/{variant}/image.webp`
   - WebP conversion applied (currently stub)
   - Metadata added (retention policy, upload timestamp, service identifier)
   - S3Client uploads to R2 via PutObject API
   - Returns `StorageUploadResultType` with object key and metadata

2. **Download Flow:**
   - Application calls `storageGateway.download(bucket, objectKey)`
   - S3Client fetches via GetObject API
   - Returns raw bytes for processing

3. **Signed URL Flow:**
   - Application calls `storageGateway.generateSignedUrl(bucket, objectKey, ttlMinutes)`
   - S3Presigner generates pre-signed URL with embedded signature
   - Returns `SignedUrlType` with URL, expiry timestamp, and TTL

---

## Bucket Strategy

### Bucket Names

Village Homepage uses **three dedicated buckets** per asset domain (Policy P4):

| Bucket Type   | Production Name              | Dev Name      | Purpose                          |
|---------------|------------------------------|---------------|----------------------------------|
| SCREENSHOTS   | `village-homepage-screenshots` | `screenshots` | Good Sites directory screenshots |
| LISTINGS      | `village-homepage-listings`    | `listings`    | Marketplace classified images    |
| PROFILES      | `village-homepage-profiles`    | `profiles`    | User profile avatars             |

### Object Key Prefixing

Objects are organized with a hierarchical prefix strategy:

```
{entity-id}/{variant}/{filename}

Examples (within their respective buckets):
  site-123/v1/thumbnail.webp      (in screenshots bucket)
  site-123/v1/full.webp            (in screenshots bucket)
  listing-456/thumbnail/image.webp (in listings bucket)
  listing-456/full/image.webp      (in listings bucket)
  user-789/avatar/avatar.webp      (in profiles bucket)
```

**Prefix Components:**
- `{entity-id}`: Entity identifier (site ID, listing ID, user ID)
- `{variant}`: Image variant type (`thumbnail`, `full`, `original`, version identifier)
- `{filename}`: Target filename (typically `image.webp` after conversion)

**Benefits:**
- Logical grouping for easy auditing
- Efficient prefix-based listing for entity-specific queries
- Version history tracking via version identifiers in variant segment
- CDN cache invalidation via prefix purges

---

## Signed URL Policies

### TTL Configuration

Signed URLs provide time-limited access without exposing bucket credentials.

| Asset Type         | TTL (Minutes) | TTL (Duration) | Use Case                                |
|--------------------|---------------|----------------|-----------------------------------------|
| **Private Assets** | 1440          | 24 hours       | Draft listings, unpublished screenshots |
| **Public Assets**  | 10080         | 7 days         | Published listings, directory screenshots |

**Configuration:**
```yaml
villagecompute:
  storage:
    signed-url-ttl:
      private-minutes: 1440   # 24 hours
      public-minutes: 10080    # 7 days
```

### Access Control

**Private Assets (24 hours):**
- Marketplace drafts (images not yet published)
- Profile avatars (user-visible only during upload)
- Admin previews

**Public Assets (7 days):**
- Published Good Sites screenshots
- Published marketplace listings
- Cached in CDN with cache-busting query params

**Security:**
- Signed URLs include HMAC signature preventing tampering
- Expiry enforced server-side by S3/R2
- Short TTL minimizes exposure window for leaked URLs
- Regeneration required after expiry (no refresh mechanism)

**Example Usage:**
```java
// Generate 24-hour signed URL for private draft image
SignedUrlType url = storageGateway.generateSignedUrl(
    BucketType.LISTINGS,
    "listings/draft-123/thumbnail/image.webp",
    1440  // 24 hours
);

// Generate 7-day signed URL for published screenshot
SignedUrlType url = storageGateway.generateSignedUrl(
    BucketType.SCREENSHOTS,
    "screenshots/site-456/v2/full.webp",
    10080  // 7 days
);
```

---

## WebP Conversion

### Current Status: Stub Implementation

**As of I3.T7, WebP conversion is a placeholder pass-through.** Uploaded images are stored as-is without format conversion or resizing.

**Stub Behavior:**
```java
private byte[] convertToWebP(byte[] originalBytes, String variant) {
    LOG.warnf("WebP conversion not yet implemented - returning original image (Policy P4 stub)");
    return originalBytes;  // Pass-through
}
```

### Target Implementation (Roadmap)

When implemented, the conversion logic will:

1. **Resize to Target Dimensions:**
   - Thumbnail: 320x200 (per UI/UX Architecture Policy P4)
   - Full: 1280x800
   - Maintain aspect ratio with letterboxing if needed

2. **Convert to WebP Format:**
   - Quality: 85 (configurable via `villagecompute.storage.webp.quality`)
   - Preserve transparency for PNGs
   - Progressive encoding for fast loading

3. **Strip EXIF Metadata:**
   - Remove location data for privacy (Policy P4)
   - Preserve orientation tag only

4. **Library Options:**
   - **Thumbnailator** (Java native, good for basic resizing)
   - **ImageMagick via ProcessBuilder** (requires native install, best quality)
   - **WebP Java Bindings** (native library, most control)

**Configuration:**
```yaml
villagecompute:
  storage:
    webp:
      quality: 85                # Compression quality (0-100)
      thumbnail-width: 320
      thumbnail-height: 200
      full-width: 1280
      full-height: 800
```

**Expected Signature:**
```java
public byte[] convertToWebP(byte[] originalBytes, String variant) {
    int targetWidth = variant.equals("thumbnail") ? thumbnailWidth : fullWidth;
    int targetHeight = variant.equals("thumbnail") ? thumbnailHeight : fullHeight;

    return ImageProcessor.builder()
        .source(originalBytes)
        .targetFormat("webp")
        .width(targetWidth)
        .height(targetHeight)
        .quality(webpQuality)
        .maintainAspectRatio(true)
        .stripExif(true)
        .build()
        .convert();
}
```

---

## Retention Metadata

### Policy P4 Compliance

**Policy P4 mandates indefinite screenshot retention with version history.** All objects uploaded to the `screenshots` bucket must never be deleted automatically.

### Object Metadata Tags

StorageGateway attaches custom metadata to every uploaded object:

| Metadata Key           | Value Example            | Purpose                                      |
|------------------------|--------------------------|----------------------------------------------|
| `content-type-original`| `image/jpeg`             | Original MIME type before WebP conversion    |
| `retention-policy`     | `indefinite`             | Retention rule identifier                    |
| `uploaded-at`          | `2026-01-09T10:30:00Z`   | ISO 8601 upload timestamp                    |
| `service`              | `village-homepage`       | Originating service identifier               |
| `version` (optional)   | `v2`                     | Version identifier for screenshot versioning |

**Example Metadata:**
```java
Map<String, String> metadata = new HashMap<>();
metadata.put("content-type-original", "image/jpeg");
metadata.put("retention-policy", "indefinite");
metadata.put("uploaded-at", Instant.now().toString());
metadata.put("service", "village-homepage");
metadata.put("version", "v2");
```

### Lifecycle Policies (Ops Configuration)

**Note:** Lifecycle policies are **configured at the Cloudflare R2 dashboard level**, not in application code.

**Screenshots Bucket (`village-homepage-screenshots`):**
- **Retention:** Indefinite (never delete)
- **Tiering:** Transition objects older than 18 months to infrequent access tier
- **Versioning:** Enabled (all versions retained)

**Listings Bucket (`village-homepage-listings`):**
- **Retention:** 90 days after listing expiry/deletion
- **Tiering:** Standard access tier only

**Profiles Bucket (`village-homepage-profiles`):**
- **Retention:** Until user account deletion (GDPR right to be forgotten)
- **Tiering:** Standard access tier only

**Ops Checklist:**
1. Navigate to Cloudflare R2 → Bucket Settings → Lifecycle Rules
2. Configure tiering rule: `Transition to Infrequent Access after 547 days (18 months)`
3. Verify deletion rules are **disabled** for screenshots bucket
4. Enable versioning on screenshots bucket
5. Document policy in runbook

---

## Development vs Production

### Local Development (MinIO)

**Service:** MinIO (S3-compatible open-source object storage)

**Startup:**
```bash
# Start all dev services including MinIO
docker-compose up -d

# Verify MinIO is healthy
docker-compose ps minio

# Access MinIO Console: http://localhost:9001
# Credentials: minioadmin / minioadmin
```

**Buckets:**
- `screenshots`
- `listings`
- `profiles`

**Initialization:**
MinIO buckets are auto-created by the `minio-init` container on startup (see `docker-compose.yml`).

**Configuration:**
```yaml
villagecompute:
  storage:
    endpoint: http://localhost:9000
    access-key: minioadmin
    secret-key: minioadmin
    region: auto
    buckets:
      screenshots: screenshots
      listings: listings
      profiles: profiles
```

### Production (Cloudflare R2)

**Service:** Cloudflare R2 (S3-compatible with zero egress fees)

**Setup:**
1. Create R2 buckets via Cloudflare dashboard:
   - `village-homepage-screenshots`
   - `village-homepage-listings`
   - `village-homepage-profiles`

2. Generate API tokens with scoped permissions:
   - Scopes: `Object Read and Write`
   - Buckets: All three buckets
   - Expiry: Quarterly rotation (90 days)

3. Store credentials in Kubernetes secret:
   ```bash
   kubectl create secret generic homepage-s3-credentials \
     --from-literal=S3_ENDPOINT='https://[account-id].r2.cloudflarestorage.com' \
     --from-literal=S3_ACCESS_KEY='[R2_ACCESS_KEY_ID]' \
     --from-literal=S3_SECRET_KEY='[R2_SECRET_ACCESS_KEY]' \
     --from-literal=S3_REGION='auto' \
     -n village-homepage
   ```

4. Enable CDN integration (optional):
   - Navigate to R2 Bucket → Settings → Public Access
   - Connect to Cloudflare CDN domain (e.g., `assets.villagecompute.com`)
   - Configure cache rules: 7-day TTL for public assets

**Configuration:**
```yaml
villagecompute:
  storage:
    endpoint: https://[account-id].r2.cloudflarestorage.com
    access-key: ${S3_ACCESS_KEY}
    secret-key: ${S3_SECRET_KEY}
    region: auto
    buckets:
      screenshots: village-homepage-screenshots
      listings: village-homepage-listings
      profiles: village-homepage-profiles
```

---

## Configuration Reference

### Environment Variables

| Variable                    | Default                   | Description                                    |
|-----------------------------|---------------------------|------------------------------------------------|
| `S3_ENDPOINT`               | `http://localhost:9000`   | S3/R2 API endpoint URL                         |
| `S3_ACCESS_KEY`             | `minioadmin`              | S3 access key ID                               |
| `S3_SECRET_KEY`             | `minioadmin`              | S3 secret access key                           |
| `S3_REGION`                 | `auto`                    | S3 region (R2 uses 'auto')                     |
| `S3_BUCKET_SCREENSHOTS`     | `screenshots`             | Screenshots bucket name                        |
| `S3_BUCKET_LISTINGS`        | `listings`                | Listings bucket name                           |
| `S3_BUCKET_PROFILES`        | `profiles`                | Profiles bucket name                           |
| `SIGNED_URL_PRIVATE_TTL`    | `1440`                    | Private asset signed URL TTL (minutes)         |
| `SIGNED_URL_PUBLIC_TTL`     | `10080`                   | Public asset signed URL TTL (minutes)          |
| `WEBP_QUALITY`              | `85`                      | WebP compression quality (0-100)               |

### Kubernetes Secret Structure

See `config/secrets-template.yaml` for full reference:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: homepage-s3-credentials
  namespace: village-homepage
type: Opaque
stringData:
  s3-endpoint: "https://ACCOUNT_ID.r2.cloudflarestorage.com"
  s3-access-key: "CHANGE_ME"
  s3-secret-key: "CHANGE_ME"
  s3-region: "auto"
  s3-bucket-screenshots: "village-homepage-screenshots"
  s3-bucket-listings: "village-homepage-listings"
  s3-bucket-profiles: "village-homepage-profiles"
```

### Application YAML

Full configuration in `src/main/resources/application.yaml`:

```yaml
villagecompute:
  storage:
    endpoint: ${S3_ENDPOINT:http://localhost:9000}
    access-key: ${S3_ACCESS_KEY:minioadmin}
    secret-key: ${S3_SECRET_KEY:minioadmin}
    region: ${S3_REGION:auto}

    buckets:
      screenshots: ${S3_BUCKET_SCREENSHOTS:screenshots}
      listings: ${S3_BUCKET_LISTINGS:listings}
      profiles: ${S3_BUCKET_PROFILES:profiles}

    signed-url-ttl:
      private-minutes: ${SIGNED_URL_PRIVATE_TTL:1440}
      public-minutes: ${SIGNED_URL_PUBLIC_TTL:10080}

    webp:
      quality: ${WEBP_QUALITY:85}
      thumbnail-width: 320
      thumbnail-height: 200
      full-width: 1280
      full-height: 800
```

---

## Monitoring & Metrics

### Prometheus Metrics

StorageGateway exports comprehensive metrics via Micrometer:

**Upload Metrics:**
```
storage_uploads_total{bucket="screenshots",status="success"} 1234
storage_uploads_total{bucket="screenshots",status="failure"} 5
storage_bytes_uploaded{bucket="screenshots"} 987654321
storage_upload_duration{bucket="screenshots",status="success"} histogram
```

**Download Metrics:**
```
storage_downloads_total{bucket="screenshots",status="success"} 5678
storage_bytes_downloaded{bucket="screenshots"} 123456789
storage_download_duration{bucket="screenshots",status="success"} histogram
```

**Signed URL Metrics:**
```
storage_signed_urls_total{bucket="screenshots",status="success"} 910
```

**Delete Metrics:**
```
storage_deletes_total{bucket="screenshots",status="success"} 2
```

### OpenTelemetry Traces

All StorageGateway operations emit distributed traces with spans:

| Span Name                      | Attributes                                                        |
|--------------------------------|-------------------------------------------------------------------|
| `storage.upload`               | `bucket`, `entity_id`, `variant`, `size_bytes`, `upload_success`  |
| `storage.download`             | `bucket`, `object_key`, `size_bytes`, `download_success`          |
| `storage.generate_signed_url`  | `bucket`, `ttl_minutes`, `url_generated`                          |
| `storage.delete`               | `bucket`, `object_key`, `delete_success`                          |
| `storage.list_objects`         | `bucket`, `prefix`, `max_results`, `object_count`                 |

**Trace Visualization:**
Access Jaeger UI at `http://localhost:16686` (dev) or centralized Jaeger cluster (prod).

### Alerts (Recommended)

Configure Prometheus alerts for operational visibility:

```yaml
groups:
  - name: storage_alerts
    rules:
      - alert: HighStorageUploadFailureRate
        expr: rate(storage_uploads_total{status="failure"}[5m]) > 0.05
        for: 5m
        annotations:
          summary: "High storage upload failure rate ({{ $value }})"

      - alert: SlowStorageUploads
        expr: histogram_quantile(0.95, storage_upload_duration{status="success"}) > 5000
        for: 10m
        annotations:
          summary: "95th percentile upload latency > 5s"

      - alert: R2CredentialExpirySoon
        expr: time() - s3_credential_last_rotation_timestamp > 7776000  # 90 days
        annotations:
          summary: "R2 credentials due for quarterly rotation"
```

---

## Troubleshooting

### Common Issues

#### 1. Upload Fails with "Access Denied"

**Symptoms:**
```
ERROR [villagecompute.homepage.services.StorageGateway] Failed to upload to SCREENSHOTS/site-123: Access Denied
```

**Diagnosis:**
- S3 credentials are invalid or expired
- Bucket policy does not grant write permissions
- Network policy blocking S3 API endpoint

**Resolution:**
```bash
# Verify credentials are set correctly
kubectl get secret homepage-s3-credentials -n village-homepage -o yaml

# Test S3 connectivity with AWS CLI
aws s3 ls s3://village-homepage-screenshots \
  --endpoint-url https://[account-id].r2.cloudflarestorage.com \
  --profile homepage-r2

# Rotate credentials if expired (see security.md)
```

#### 2. Signed URLs Return 403 Forbidden

**Symptoms:**
- Generated signed URL works initially
- Returns 403 after some time
- Browser console shows "Signature expired"

**Diagnosis:**
- TTL expired (24h for private, 7d for public)
- System clock skew between app and S3
- URL tampered with (query params modified)

**Resolution:**
```bash
# Check current TTL configuration
kubectl get configmap homepage-config -n village-homepage -o yaml | grep SIGNED_URL

# Verify system time sync
timedatectl status  # On pod/node

# Regenerate signed URL with fresh TTL
# (URLs are single-use and cannot be refreshed)
```

#### 3. MinIO Buckets Not Initialized

**Symptoms:**
```
ERROR [software.amazon.awssdk.services.s3.S3Client] NoSuchBucket: The specified bucket does not exist
```

**Diagnosis:**
- minio-init container failed on startup
- MinIO healthcheck not passing before init runs
- Race condition in docker-compose startup

**Resolution:**
```bash
# Check minio-init logs
docker-compose logs minio-init

# Manually create buckets
docker-compose exec minio-init /bin/sh -c "
  mc alias set local http://minio:9000 minioadmin minioadmin;
  mc mb --ignore-existing local/screenshots;
  mc mb --ignore-existing local/listings;
  mc mb --ignore-existing local/profiles;
"

# Restart containers in correct order
docker-compose restart minio minio-init
```

#### 4. WebP Conversion Not Working

**Expected Behavior:**
WebP conversion is currently a **stub implementation** (I3.T7). Images are stored as-is without format conversion.

**Future Resolution:**
When WebP conversion is implemented, verify:
- Image processing library is installed
- Source image format is supported (JPEG, PNG, GIF)
- Target dimensions are correctly configured
- Quality setting is between 0-100

#### 5. High Upload Latency

**Symptoms:**
- Uploads taking > 5 seconds
- `storage_upload_duration` P95 metric elevated
- User reports slow image uploads

**Diagnosis:**
- Network latency to R2 endpoint
- Large file sizes (> 5MB)
- CPU contention during WebP conversion (future)

**Resolution:**
```bash
# Check network latency to R2
curl -w "@curl-format.txt" -o /dev/null -s https://[account-id].r2.cloudflarestorage.com

# Review image sizes
aws s3api list-objects-v2 \
  --bucket village-homepage-screenshots \
  --query 'Contents[?Size > `5242880`]' \
  --endpoint-url https://[account-id].r2.cloudflarestorage.com

# Scale up pod resources if CPU-bound (future WebP processing)
kubectl scale deployment homepage --replicas=5 -n village-homepage
```

---

## Future Roadmap

### Planned Enhancements

#### 1. WebP Conversion Implementation (Priority: High)

**Milestone:** I4.T2 (estimated)

**Tasks:**
- [ ] Evaluate image processing libraries (Thumbnailator vs ImageMagick)
- [ ] Implement resize with aspect ratio preservation
- [ ] Add WebP encoding with quality control
- [ ] Strip EXIF metadata for privacy
- [ ] Add unit tests for conversion edge cases
- [ ] Performance benchmark (target < 500ms for thumbnail)

**Acceptance Criteria:**
- Uploads produce both thumbnail (320x200) and full (1280x800) WebP variants
- Original aspect ratio preserved with letterboxing
- EXIF location data stripped
- 95th percentile conversion time < 500ms

#### 2. Automatic Screenshot Versioning

**Milestone:** I5.T1 (estimated)

**Tasks:**
- [ ] Extend `directory_screenshot_versions` table schema
- [ ] Implement version comparison logic (detect content changes)
- [ ] Auto-increment version identifier in object key
- [ ] Retain version history indefinitely per Policy P4
- [ ] Expose version history in Good Sites admin UI

**Acceptance Criteria:**
- Screenshot refreshes create new version if content differs
- Version history queryable via StorageGateway API
- Old versions retained with `indefinite` retention metadata

#### 3. CDN Integration with Cache Invalidation

**Milestone:** I5.T3 (estimated)

**Tasks:**
- [ ] Configure Cloudflare CDN domain for R2 buckets
- [ ] Implement cache-busting query params (`?v=2`)
- [ ] Add API for purging CDN cache on version updates
- [ ] Document CDN configuration in ops guide

**Acceptance Criteria:**
- Public assets served via CDN with 7-day TTL
- Version bumps trigger automatic CDN purge
- 95th percentile CDN response time < 100ms

#### 4. Multi-Region Replication

**Milestone:** Future (post-launch)

**Tasks:**
- [ ] Configure Cloudflare R2 bucket replication
- [ ] Route uploads to nearest region
- [ ] Implement read-after-write consistency checks
- [ ] Monitor replication lag metrics

**Acceptance Criteria:**
- Objects replicated to 2+ regions within 60 seconds
- Regional failover transparent to application

#### 5. Compression & Deduplication

**Milestone:** Future (post-launch, cost optimization)

**Tasks:**
- [ ] Implement SHA-256 content hashing
- [ ] Detect duplicate uploads via hash
- [ ] Store single copy with multiple references
- [ ] Measure storage savings metrics

**Acceptance Criteria:**
- Duplicate uploads deduplicated automatically
- Storage cost reduced by estimated 20%

---

## References

- [Cloudflare R2 Documentation](https://developers.cloudflare.com/r2/)
- [AWS S3 API Reference](https://docs.aws.amazon.com/AmazonS3/latest/API/)
- [MinIO Quickstart Guide](https://min.io/docs/minio/linux/index.html)
- [WebP Image Format Specification](https://developers.google.com/speed/webp)
- [Policy P4: Screenshot Retention](../../architecture/01_Business_Context.md#policy-p4)
- [Security: Credential Rotation](./security.md#s3r2-credentials)

---

**Document Version:** 1.0
**Last Updated:** 2026-01-09
**Maintained By:** VillageCompute DevOps Team
**Review Cycle:** Quarterly or on major policy changes
