# Village Homepage - Deployment Guide

**Version:** 1.0
**Last Updated:** 2026-01-24
**Owner:** Platform Squad
**Status:** Active

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Environment Variables](#environment-variables)
3. [Deployment Procedures](#deployment-procedures)
4. [Rollback Procedure](#rollback-procedure)
5. [Post-Deployment Verification](#post-deployment-verification)
6. [Troubleshooting](#troubleshooting)

---

## Prerequisites

### 1. Infrastructure Requirements

Before deploying Village Homepage, ensure the following infrastructure is in place:

| Component | Version | Location | Purpose |
|-----------|---------|----------|---------|
| **K3s Cluster** | v1.28.4+ | 10.50.0.20 (VillageCompute Proxmox) | Container orchestration |
| **PostgreSQL** | 17+ with PostGIS | 10.50.0.10 | Primary database (OLTP) |
| **Elasticsearch** | 8.x | External cluster | Full-text search (Hibernate Search) |
| **Container Registry** | GitHub Container Registry | ghcr.io | Container image storage |
| **VPN Access** | WireGuard | VillageCompute network | kubectl access to K3s |
| **Cloudflare R2** | S3-compatible | R2 storage | Object storage (images, screenshots) |

### 2. Required Kubernetes Secrets

The following Kubernetes secrets must exist before deployment. **IMPORTANT:** Never commit actual secret values to version control.

```bash
# Create namespace if not exists
kubectl create namespace homepage-prod

# Database credentials
kubectl create secret generic db-secret-homepage-prod \
  --from-literal=DB_USERNAME=homepage_prod \
  --from-literal=DB_PASSWORD=<REDACTED> \
  -n homepage-prod

# OAuth client secrets (Google, Facebook, Apple)
kubectl create secret generic oauth-secret-homepage-prod \
  --from-literal=GOOGLE_CLIENT_ID=<REDACTED> \
  --from-literal=GOOGLE_CLIENT_SECRET=<REDACTED> \
  --from-literal=FACEBOOK_APP_ID=<REDACTED> \
  --from-literal=FACEBOOK_APP_SECRET=<REDACTED> \
  --from-literal=APPLE_CLIENT_ID=<REDACTED> \
  --from-literal=APPLE_TEAM_ID=<REDACTED> \
  --from-literal=APPLE_KEY_ID=<REDACTED> \
  -n homepage-prod

# AI API keys (Anthropic, OpenAI)
kubectl create secret generic ai-secret-homepage-prod \
  --from-literal=ANTHROPIC_API_KEY=<REDACTED> \
  --from-literal=OPENAI_API_KEY=<REDACTED> \
  -n homepage-prod

# S3/R2 credentials (Cloudflare R2 storage)
kubectl create secret generic s3-secret-homepage-prod \
  --from-literal=S3_ACCESS_KEY=<REDACTED> \
  --from-literal=S3_SECRET_KEY=<REDACTED> \
  -n homepage-prod

# SMTP credentials (email delivery)
kubectl create secret generic smtp-secret-homepage-prod \
  --from-literal=SMTP_USERNAME=<REDACTED> \
  --from-literal=SMTP_PASSWORD=<REDACTED> \
  -n homepage-prod

# Stripe payment credentials
kubectl create secret generic stripe-secret-homepage-prod \
  --from-literal=STRIPE_SECRET_KEY=<REDACTED> \
  --from-literal=STRIPE_WEBHOOK_SECRET=<REDACTED> \
  -n homepage-prod

# JWT session signing secret
kubectl create secret generic jwt-secret-homepage-prod \
  --from-literal=JWT_SESSION_SECRET=<REDACTED_RANDOM_64_CHAR_STRING> \
  -n homepage-prod

# OIDC state encryption secret
kubectl create secret generic oidc-secret-homepage-prod \
  --from-literal=OIDC_STATE_SECRET=<REDACTED_RANDOM_64_CHAR_STRING> \
  -n homepage-prod

# Bootstrap superuser token (one-time use)
kubectl create secret generic bootstrap-secret-homepage-prod \
  --from-literal=BOOTSTRAP_TOKEN=<REDACTED_ONE_TIME_TOKEN> \
  -n homepage-prod

# Email unsubscribe token secret
kubectl create secret generic email-secret-homepage-prod \
  --from-literal=EMAIL_UNSUBSCRIBE_SECRET=<REDACTED_RANDOM_64_CHAR_STRING> \
  -n homepage-prod

# Apple Sign-In private key (P8 file)
kubectl create secret generic apple-key-secret-homepage-prod \
  --from-file=apple-auth-key.p8=<PATH_TO_P8_FILE> \
  -n homepage-prod
```

---

## Environment Variables

### ConfigMap (Non-Sensitive Configuration)

Create ConfigMap `app-config-homepage-prod` with the following non-sensitive environment variables:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config-homepage-prod
  namespace: homepage-prod
data:
  # Database
  DB_HOST: "10.50.0.10"
  DB_PORT: "5432"
  DB_NAME: "village_homepage_prod"

  # Elasticsearch
  ELASTICSEARCH_HOSTS: "elasticsearch.villagecompute.com:9200"
  ELASTICSEARCH_SCHEMA_STRATEGY: "validate"

  # S3/R2 Storage
  S3_ENDPOINT: "https://r2.cloudflarestorage.com/villagecompute"
  S3_REGION: "auto"
  S3_BUCKET_SCREENSHOTS: "homepage-screenshots-prod"
  S3_BUCKET_LISTINGS: "homepage-listings-prod"
  S3_BUCKET_PROFILES: "homepage-profiles-prod"
  S3_BUCKET_GDPR_EXPORTS: "homepage-gdpr-exports-prod"

  # SMTP
  SMTP_HOST: "smtp.sendgrid.net"
  SMTP_PORT: "587"

  # Email configuration
  EMAIL_FROM: "noreply@villagecompute.com"
  EMAIL_IMAP_HOST: "imap.sendgrid.net"
  EMAIL_IMAP_PORT: "993"
  EMAIL_IMAP_FOLDER: "INBOX"
  EMAIL_IMAP_SSL: "true"
  EMAIL_PLATFORM_NAME: "Village Homepage"
  EMAIL_BASE_URL: "https://homepage.villagecompute.com"
  EMAIL_OPS_ALERT: "ops@villagecompute.com"

  # Application URLs
  ENVIRONMENT: "prod"
  JWT_ISSUER: "https://homepage.villagecompute.com"
  HOMEPAGE_BASE_URL: "https://homepage.villagecompute.com"
  CDN_BASE_URL: "https://cdn.villagecompute.com"

  # OpenTelemetry (distributed tracing)
  OTEL_EXPORTER_OTLP_ENDPOINT: "http://jaeger-collector.observability.svc.cluster.local:4318"

  # Cookie configuration
  COOKIE_SECURE: "true"
  COOKIE_DOMAIN: ""

  # Stripe
  STRIPE_PUBLISHABLE_KEY: "pk_live_CHANGEME"

  # AI configuration
  AI_MODEL_SONNET: "claude-3-5-sonnet-20241022"
  AI_MODEL_HAIKU: "claude-3-haiku-20240307"
  AI_MODEL_TEMPERATURE: "0.7"
  AI_MODEL_MAX_TOKENS: "4096"
  AI_MODEL_TIMEOUT_SECONDS: "60"
  AI_MODEL_MAX_RETRIES: "3"
  AI_RATE_LIMIT_RPM: "60"
  AI_RATE_LIMIT_TOKENS_PER_DAY: "1000000"
  AI_LOG_REQUESTS: "false"
  AI_LOG_RESPONSES: "false"

  # Auth configuration
  AUTH_LOGIN_MAX_REQUESTS: "20"
  AUTH_LOGIN_WINDOW_SECONDS: "60"
  BOOTSTRAP_MAX_REQUESTS: "5"
  BOOTSTRAP_WINDOW_SECONDS: "3600"
  JWT_EXPIRATION_MINUTES: "60"

  # Storage configuration
  SIGNED_URL_PRIVATE_TTL: "1440"  # 24 hours
  SIGNED_URL_PUBLIC_TTL: "10080"  # 7 days
  WEBP_QUALITY: "85"

  # GDPR configuration
  GDPR_EXPORT_TTL_DAYS: "7"
  GDPR_DELETION_DELAY_HOURS: "0"
```

Apply the ConfigMap:

```bash
kubectl apply -f app-config-homepage-prod.yaml
```

### Complete Environment Variables Reference

The application expects the following environment variables (from Secrets + ConfigMap):

| Variable | Source | Required | Description |
|----------|--------|----------|-------------|
| `DB_HOST` | ConfigMap | Yes | PostgreSQL hostname |
| `DB_PORT` | ConfigMap | Yes | PostgreSQL port (5432) |
| `DB_NAME` | ConfigMap | Yes | PostgreSQL database name |
| `DB_USERNAME` | Secret | Yes | PostgreSQL username |
| `DB_PASSWORD` | Secret | Yes | PostgreSQL password |
| `ELASTICSEARCH_HOSTS` | ConfigMap | Yes | Elasticsearch cluster endpoint |
| `ELASTICSEARCH_SCHEMA_STRATEGY` | ConfigMap | Yes | Schema validation strategy (validate) |
| `GOOGLE_CLIENT_ID` | Secret | Yes | Google OAuth client ID |
| `GOOGLE_CLIENT_SECRET` | Secret | Yes | Google OAuth client secret |
| `FACEBOOK_APP_ID` | Secret | Yes | Facebook OAuth app ID |
| `FACEBOOK_APP_SECRET` | Secret | Yes | Facebook OAuth app secret |
| `APPLE_CLIENT_ID` | Secret | Yes | Apple Sign-In client ID |
| `APPLE_TEAM_ID` | Secret | Yes | Apple Developer Team ID |
| `APPLE_KEY_ID` | Secret | Yes | Apple Auth Key ID |
| `APPLE_PRIVATE_KEY_PATH` | Secret | Yes | Path to Apple P8 private key |
| `ANTHROPIC_API_KEY` | Secret | Yes | Anthropic Claude API key |
| `OPENAI_API_KEY` | Secret | Yes | OpenAI API key |
| `S3_ENDPOINT` | ConfigMap | Yes | S3-compatible storage endpoint |
| `S3_ACCESS_KEY` | Secret | Yes | S3 access key |
| `S3_SECRET_KEY` | Secret | Yes | S3 secret key |
| `SMTP_HOST` | ConfigMap | Yes | SMTP server hostname |
| `SMTP_PORT` | ConfigMap | Yes | SMTP port (587 for TLS) |
| `SMTP_USERNAME` | Secret | Yes | SMTP authentication username |
| `SMTP_PASSWORD` | Secret | Yes | SMTP authentication password |
| `STRIPE_SECRET_KEY` | Secret | Yes | Stripe API secret key |
| `STRIPE_WEBHOOK_SECRET` | Secret | Yes | Stripe webhook signing secret |
| `STRIPE_PUBLISHABLE_KEY` | ConfigMap | Yes | Stripe publishable key |
| `JWT_SESSION_SECRET` | Secret | Yes | JWT signing secret (64+ chars) |
| `OIDC_STATE_SECRET` | Secret | Yes | OIDC state encryption secret (64+ chars) |
| `BOOTSTRAP_TOKEN` | Secret | No | Bootstrap superuser token (one-time use) |
| `EMAIL_UNSUBSCRIBE_SECRET` | Secret | Yes | Email unsubscribe token secret |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | ConfigMap | Yes | OpenTelemetry collector endpoint |

---

## Deployment Procedures

### Option 1: Automated Deployment via GitHub Actions (Recommended)

This is the recommended deployment method for production. It provides automated testing, image building, and deployment with minimal manual intervention.

#### Steps:

1. **Tag the release:**
   ```bash
   # Ensure your working directory is clean
   git status

   # Create and push a version tag (triggers deploy-prod.yml workflow)
   git tag -a v1.0.0 -m "Production release 1.0.0"
   git push origin v1.0.0
   ```

2. **Monitor GitHub Actions:**
   - Navigate to https://github.com/VillageCompute/village-homepage/actions
   - Find the "Deploy to Production" workflow run
   - Monitor progress through each stage:
     - Code checkout
     - Dependency installation
     - Frontend build
     - Test execution with coverage
     - Container image build
     - Container push to ghcr.io
     - K8s deployment (manual step if VPN required)
     - Smoke tests

3. **Verify deployment:**
   ```bash
   # Check pod status
   kubectl get pods -n homepage-prod

   # Watch deployment rollout
   kubectl rollout status deployment/village-homepage -n homepage-prod

   # View recent logs
   kubectl logs -n homepage-prod deployment/village-homepage --tail=100

   # Check health endpoints
   curl https://homepage.villagecompute.com/q/health/ready
   curl https://homepage.villagecompute.com/q/health/live
   ```

4. **Monitor application health:**
   - Grafana dashboards: https://grafana.villagecompute.com
   - Jaeger tracing: https://jaeger.villagecompute.com
   - Application logs: https://grafana.villagecompute.com/d/logs

### Option 2: Manual Deployment

Use this method for emergency deployments, testing, or when GitHub Actions is unavailable.

#### Prerequisites for Manual Deployment:
- WireGuard VPN connection to VillageCompute network
- kubectl configured with K3s cluster credentials
- Docker or Podman installed locally
- Maven 3.9+ and JDK 21

#### Steps:

1. **Build frontend assets:**
   ```bash
   # Install dependencies
   node tools/install.cjs

   # Build frontend
   npm run build
   ```

2. **Run tests with coverage:**
   ```bash
   # Run unit and integration tests
   ./mvnw verify -DskipITs=false

   # Verify coverage thresholds (must pass)
   ./mvnw jacoco:report
   ```

3. **Build and push container image:**
   ```bash
   # Set version variable
   VERSION=v1.0.0

   # Authenticate with GitHub Container Registry
   echo $GITHUB_TOKEN | docker login ghcr.io -u $GITHUB_USERNAME --password-stdin

   # Build and push with Jib
   ./mvnw package jib:build \
     -DskipTests \
     -Djib.to.image=ghcr.io/villagecompute/village-homepage:${VERSION} \
     -Djib.to.tags=latest,prod-${VERSION}
   ```

4. **Run database migrations:**
   ```bash
   cd migrations

   # Configure database connection (use prod credentials)
   export DB_HOST=10.50.0.10
   export DB_NAME=village_homepage_prod
   export DB_USERNAME=homepage_prod
   export DB_PASSWORD=<REDACTED>

   # Run migrations
   mvn migration:up -Dmigration.env=prod

   # Verify migration status
   mvn migration:status -Dmigration.env=prod
   ```

5. **Update Kubernetes deployment:**
   ```bash
   # Update image tag in deployment manifest
   kubectl set image deployment/village-homepage \
     village-homepage=ghcr.io/villagecompute/village-homepage:${VERSION} \
     -n homepage-prod

   # Or apply updated manifests
   kubectl apply -k k8s/prod/
   ```

6. **Wait for rollout to complete:**
   ```bash
   # Watch rollout progress
   kubectl rollout status deployment/village-homepage -n homepage-prod

   # Check pod status
   kubectl get pods -n homepage-prod -w
   ```

7. **Run smoke tests (see Post-Deployment Verification below)**

---

## Rollback Procedure

If deployment fails or critical issues are detected, follow this procedure to rollback to the previous version.

### Automated Rollback (Recommended)

```bash
# Rollback to previous revision
kubectl rollout undo deployment/village-homepage -n homepage-prod

# Verify rollback
kubectl rollout status deployment/village-homepage -n homepage-prod
```

### Rollback to Specific Revision

```bash
# List deployment revision history
kubectl rollout history deployment/village-homepage -n homepage-prod

# Example output:
# REVISION  CHANGE-CAUSE
# 1         Initial deployment
# 2         Update to v1.0.0
# 3         Update to v1.1.0

# Rollback to specific revision (e.g., revision 2)
kubectl rollout undo deployment/village-homepage --to-revision=2 -n homepage-prod

# Verify rollback
kubectl rollout status deployment/village-homepage -n homepage-prod
```

### Database Migration Rollback

**WARNING:** Database rollbacks are **not automatic** and must be performed manually with extreme caution.

```bash
cd migrations

# List applied migrations
mvn migration:status -Dmigration.env=prod

# Rollback last migration (USE WITH CAUTION)
mvn migration:down -Dmigration.env=prod

# Verify migration status
mvn migration:status -Dmigration.env=prod
```

**Best Practice:** Avoid database rollbacks in production. Instead:
1. Deploy a hotfix with forward-compatible schema changes
2. Use feature flags to disable broken functionality
3. Plan data migration repairs during maintenance window

---

## Post-Deployment Verification

After deployment completes, perform the following checks to verify system health:

### 1. Health Checks

```bash
# Readiness probe (application ready to serve traffic)
curl -f https://homepage.villagecompute.com/q/health/ready
# Expected: HTTP 200 with JSON response {"status": "UP"}

# Liveness probe (application is alive)
curl -f https://homepage.villagecompute.com/q/health/live
# Expected: HTTP 200 with JSON response {"status": "UP"}
```

### 2. Metrics Endpoint

```bash
# Metrics endpoint (Prometheus format)
curl -f https://homepage.villagecompute.com/q/metrics | grep "jvm_memory"
# Expected: Metrics output with jvm_memory_* metrics
```

### 3. Application Functionality

```bash
# Homepage loads (anonymous user)
curl -f https://homepage.villagecompute.com/
# Expected: HTTP 200 with HTML content

# API health check
curl -f https://homepage.villagecompute.com/api/health
# Expected: HTTP 200 with JSON response
```

### 4. Database Connectivity

```bash
# Check application logs for database connection
kubectl logs -n homepage-prod deployment/village-homepage | grep "database"
# Expected: No connection errors
```

### 5. External API Connectivity

Verify connectivity to external services:

- **Google OAuth:** Check logs for successful OAuth initialization
- **Facebook OAuth:** Check logs for successful OAuth initialization
- **Anthropic AI:** Check logs for AI service initialization
- **Elasticsearch:** Check logs for Hibernate Search initialization
- **S3/R2 Storage:** Check logs for storage gateway initialization

```bash
# Check logs for external service initialization
kubectl logs -n homepage-prod deployment/village-homepage | grep -E "(OAuth|Anthropic|Elasticsearch|S3)"
```

### 6. Background Jobs

```bash
# Check delayed jobs queue depth
curl -s https://homepage.villagecompute.com/q/metrics | grep "homepage_jobs_depth"
# Expected: Metrics showing queue depths
```

### 7. Email Delivery

Send a test email to verify SMTP configuration:

```bash
# Use admin panel or API to send test email
curl -X POST https://homepage.villagecompute.com/admin/api/test-email \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"to": "test@villagecompute.com", "subject": "Deployment Test"}'
```

### 8. Log Aggregation

Verify logs are appearing in Grafana Loki:

1. Navigate to https://grafana.villagecompute.com/d/logs
2. Filter by `service.name=village-homepage`
3. Verify recent log entries are visible
4. Check for any ERROR level logs

### 9. Distributed Tracing

Verify traces are appearing in Jaeger:

1. Navigate to https://jaeger.villagecompute.com
2. Select service: `village-homepage`
3. Verify recent traces are visible
4. Check trace completeness (no missing spans)

### Deployment Verification Checklist

Use this checklist to ensure all verification steps are completed:

- [ ] Health check endpoints return 200
- [ ] Metrics endpoint accessible and returning data
- [ ] Homepage loads successfully
- [ ] Database connectivity confirmed (no errors in logs)
- [ ] External APIs initialized successfully
- [ ] Background jobs processing (queue depth metrics)
- [ ] Email delivery working (SMTP connection confirmed)
- [ ] Logs appearing in Grafana Loki
- [ ] Traces appearing in Jaeger
- [ ] No critical alerts in Prometheus Alertmanager
- [ ] Grafana dashboards showing live data

---

## Troubleshooting

For common deployment issues and solutions, see [docs/troubleshooting.md](./troubleshooting.md).

Quick reference:

- **Pods not starting:** Check logs with `kubectl describe pod <pod-name> -n homepage-prod`
- **Database connection errors:** Verify secrets and ConfigMap values
- **Image pull errors:** Check container registry authentication
- **Readiness probe failures:** Check health endpoint and application logs
- **High memory usage:** Review resource limits in deployment manifest

---

## References

- **Operations Guide:** [docs/operations.md](./operations.md) - Monitoring, scaling, backup procedures
- **Troubleshooting Guide:** [docs/troubleshooting.md](./troubleshooting.md) - Common issues and solutions
- **CI/CD Pipeline:** [docs/ops/ci-cd-pipeline.md](./ops/ci-cd-pipeline.md) - Build and test workflow
- **Observability:** [docs/ops/observability.md](./ops/observability.md) - Logging, tracing, metrics
- **VillageCompute Infrastructure:** `../villagecompute/infra/` - K3s cluster configuration

---

## Changelog

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-01-24 | Platform Squad | Initial deployment guide (Task I6.T8) |
