# Development Services Operations Guide

**Version:** 1.0
**Last Updated:** 2025-01-08
**Owner:** Platform Squad
**Status:** Active

## 1. Overview

This guide documents the Docker Compose development services stack for Village Homepage. It provides operational procedures for starting, monitoring, troubleshooting, and maintaining local development dependencies.

### 1.1 Purpose

The development services stack provides local equivalents of production infrastructure to enable:
- Full-stack development without external dependencies
- Integration testing with realistic service behavior
- Isolated experimentation without affecting shared environments
- Consistent development environment across team members

### 1.2 Service Inventory

| Service | Purpose | Production Equivalent | Local Port(s) |
|---------|---------|----------------------|---------------|
| PostgreSQL 17 + PostGIS | Primary database with spatial extensions | Managed HA PostgreSQL cluster | 5432 |
| Elasticsearch 8 | Full-text search and geo-spatial indexing | Dedicated Elasticsearch StatefulSet | 9200, 9300 |
| MinIO | S3-compatible object storage | Cloudflare R2 | 9000 (API), 9001 (Console) |
| Mailpit | SMTP testing with web UI | Managed SMTP relay | 1025 (SMTP), 8025 (UI) |
| Jaeger | Distributed tracing visualization | Centralized Jaeger cluster | 16686 (UI), 4317/4318 (OTLP) |

**ARM64/Apple Silicon Compatibility:** Some services require platform emulation on ARM64 Macs. Enable Rosetta 2 in Docker Desktop for best performance. See [arm64-compatibility.md](arm64-compatibility.md) for detailed guidance.

### 1.3 Policy References

This stack implements requirements from:
- **P6** - PostgreSQL 17 with PostGIS extension for spatial queries
- **P7** - MyBatis Migrations for schema management
- **P12** - Delayed job pattern with queue-based worker toggles
- **Infrastructure Standards** - See `../villagecompute` repository for production equivalents

## 2. Quick Start

### 2.1 Prerequisites

Before starting the development stack, ensure you have:

- Docker Engine 20.10+ and Docker Compose 2.0+
- At least 4GB RAM available for Docker
- Ports 5432, 9200, 9000, 9001, 1025, 8025, 16686 available
- Git repository cloned and up-to-date

### 2.2 Initial Setup

```bash
# 1. Navigate to project root
cd /path/to/village-homepage

# 2. Copy environment template
cp .env.example .env

# 3. (Optional) Edit .env for custom configuration
# Most developers can use the defaults for local development
nano .env

# 4. Start all services
docker-compose up -d

# 5. Verify services are healthy
docker-compose ps

# 6. Run database migrations
cd migrations
set -a && source ../.env && set +a
mvn migration:up -Dmigration.env=development
cd ..

# 7. Load geographic dataset (one-time, ~5-10 minutes)
./scripts/load-geo-data.sh

# 8. Start Quarkus development server
./mvnw quarkus:dev
```

Access points:
- Application: http://localhost:8080
- MinIO Console: http://localhost:9001
- Mailpit UI: http://localhost:8025
- Jaeger UI: http://localhost:16686
- Elasticsearch: http://localhost:9200

### 2.3 Daily Workflow

```bash
# Start services (if not already running)
docker-compose up -d

# Check service health
docker-compose ps

# Start development server
./mvnw quarkus:dev

# When finished, stop services (optional - can leave running)
docker-compose stop
```

## 3. Service Details

### 3.1 PostgreSQL 17 + PostGIS

#### Description
Primary relational database with PostGIS extension for geospatial queries. Stores all application data including users, listings, directory sites, and geographic datasets.

#### Configuration
- **Image:** `postgis/postgis:17-3.5`
- **Container Name:** `village-homepage-postgres`
- **Host Port:** 5432
- **Default Credentials:** `village` / `village_dev_pass` (customize in `.env`)
- **Default Database:** `village_homepage`
- **Data Volume:** Named volume `postgres-data` for persistence

#### Health Check
```bash
# Via Docker Compose
docker-compose ps postgres

# Direct connection test
psql -h localhost -p 5432 -U village -d village_homepage -c "SELECT 1;"

# PostGIS verification
psql -h localhost -p 5432 -U village -d village_homepage -c "SELECT PostGIS_Version();"
```

#### Common Operations

**Reset Database** (caution: destroys all data):
```bash
docker-compose down -v  # Stops and removes volumes
docker-compose up -d postgres
cd migrations
set -a && source ../.env && set +a
mvn migration:up -Dmigration.env=development
cd ..
./scripts/load-geo-data.sh
```

**Access psql Shell**:
```bash
docker-compose exec postgres psql -U village -d village_homepage
```

**View Logs**:
```bash
docker-compose logs -f postgres
```

**Backup Database**:
```bash
docker-compose exec postgres pg_dump -U village village_homepage > backup.sql
```

**Restore Database**:
```bash
cat backup.sql | docker-compose exec -T postgres psql -U village village_homepage
```

### 3.2 Elasticsearch 8

#### Description
Full-text search engine powering Hibernate Search indices for marketplace listings and web directory sites. Supports geo-spatial filtering for location-based searches.

#### Configuration
- **Image:** `docker.elastic.co/elasticsearch/elasticsearch:8.11.3`
- **Container Name:** `village-homepage-elasticsearch`
- **Host Ports:** 9200 (HTTP API), 9300 (Node Transport)
- **Cluster Name:** `village-homepage-dev`
- **Security:** Disabled for local development (xpack.security.enabled=false)
- **Heap Size:** 512MB (via ES_JAVA_OPTS)
- **Data Volume:** Named volume `elasticsearch-data` for persistence

#### Health Check
```bash
# Via Docker Compose
docker-compose ps elasticsearch

# Cluster health
curl -X GET "localhost:9200/_cluster/health?pretty"

# Node info
curl -X GET "localhost:9200/_nodes?pretty"

# List indices
curl -X GET "localhost:9200/_cat/indices?v"
```

#### Common Operations

**View Hibernate Search Indices**:
```bash
curl -X GET "localhost:9200/_cat/indices?v" | grep village
```

**Reindex All Entities** (via Quarkus Dev UI):
```
http://localhost:8080/q/dev/io.quarkus.quarkus-hibernate-search-orm-elasticsearch/indexing
```

**Delete and Recreate Indices**:
```bash
# Delete all village-homepage indices
curl -X DELETE "localhost:9200/village-*"

# Trigger reindex via Quarkus application
# Access Dev UI and use Hibernate Search panel
```

**Monitor Indexing Progress**:
```bash
docker-compose logs -f elasticsearch | grep -i "index\|search"
```

**Reset Elasticsearch** (caution: destroys all indices):
```bash
docker-compose down elasticsearch
docker volume rm village-homepage_elasticsearch-data
docker-compose up -d elasticsearch
```

### 3.3 MinIO (S3-Compatible Storage)

#### Description
Local object storage service providing S3-compatible API. Stores marketplace listing images and web directory screenshot captures. Development equivalent of Cloudflare R2.

#### Configuration
- **Image:** `minio/minio:RELEASE.2024-01-16T16-07-38Z`
- **Container Name:** `village-homepage-minio`
- **Host Ports:** 9000 (S3 API), 9001 (Web Console)
- **Default Credentials:** `minioadmin` / `minioadmin` (customize in `.env`)
- **Buckets:** `screenshots`, `listings` (auto-created on startup)
- **Public Access:** Both buckets set to download-only public access
- **Data Volume:** Named volume `minio-data` for persistence

#### Health Check
```bash
# Via Docker Compose
docker-compose ps minio

# S3 API test
curl -I http://localhost:9000/minio/health/live

# List buckets (requires mc client)
docker-compose exec minio mc ls local/
```

#### Common Operations

**Access Web Console**:
```
http://localhost:9001
Username: minioadmin
Password: minioadmin
```

**List Objects in Bucket**:
```bash
docker-compose exec minio mc ls local/screenshots/
docker-compose exec minio mc ls local/listings/
```

**Upload Test File**:
```bash
echo "test content" > test.txt
docker-compose exec -T minio mc cp - local/listings/test.txt < test.txt
```

**Delete All Objects in Bucket** (caution):
```bash
docker-compose exec minio mc rm --recursive --force local/screenshots/
docker-compose exec minio mc rm --recursive --force local/listings/
```

**View Storage Statistics**:
```bash
docker-compose exec minio mc du local/
```

**Reset MinIO** (caution: destroys all objects):
```bash
docker-compose down minio minio-init
docker volume rm village-homepage_minio-data
docker-compose up -d minio minio-init
```

### 3.4 Mailpit (SMTP Testing)

#### Description
Email testing tool with SMTP server and web UI. Captures all outbound emails sent by the application for local testing and debugging. Production uses managed SMTP relay.

#### Configuration
- **Image:** `axllent/mailpit:v1.12.0`
- **Container Name:** `village-homepage-mailpit`
- **Host Ports:** 1025 (SMTP), 8025 (Web UI)
- **Max Messages:** 5000
- **Authentication:** Accept any credentials (development only)
- **Data:** Persisted to `./data/mailpit/mailpit.db`

#### Health Check
```bash
# Via Docker Compose
docker-compose ps mailpit

# Web UI test
curl -I http://localhost:8025
```

#### Common Operations

**Access Web UI**:
```
http://localhost:8025
```

**Send Test Email** (via application):
```bash
# Trigger password reset email
curl -X POST http://localhost:8080/api/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com"}'

# View email in Mailpit UI
open http://localhost:8025
```

**View Messages via API**:
```bash
# List all messages
curl http://localhost:8025/api/v1/messages | jq

# Get specific message
curl http://localhost:8025/api/v1/message/{MESSAGE_ID} | jq
```

**Clear All Messages**:
```bash
curl -X DELETE http://localhost:8025/api/v1/messages
```

**View Logs**:
```bash
docker-compose logs -f mailpit
```

### 3.5 Jaeger (Distributed Tracing)

#### Description
Distributed tracing system for monitoring and troubleshooting microservices. Collects OpenTelemetry traces from Quarkus application to visualize request flows, identify bottlenecks, and debug performance issues.

#### Configuration
- **Image:** `jaegertracing/all-in-one:1.53`
- **Container Name:** `village-homepage-jaeger`
- **Host Ports:** 16686 (UI), 4317 (OTLP gRPC), 4318 (OTLP HTTP)
- **Collector:** OpenTelemetry Protocol (OTLP) enabled
- **Storage:** In-memory (traces not persisted across restarts)

#### Health Check
```bash
# Via Docker Compose
docker-compose ps jaeger

# Web UI test
curl -I http://localhost:16686
```

#### Common Operations

**Access Web UI**:
```
http://localhost:16686
```

**View Application Traces**:
1. Open Jaeger UI: http://localhost:16686
2. Select Service: `village-homepage`
3. Click "Find Traces"
4. Select a trace to view detailed span timeline

**Filter Traces by Operation**:
- Use "Operation" dropdown to filter by endpoint (e.g., `GET /api/marketplace/listings`)
- Adjust "Lookback" to control time range
- Use "Tags" for custom filtering (e.g., `http.status_code=500`)

**Monitor Trace Collection**:
```bash
docker-compose logs -f jaeger | grep -i "span\|trace"
```

**Generate Test Traces**:
```bash
# Make requests to application
for i in {1..10}; do
  curl http://localhost:8080/api/marketplace/listings
  sleep 1
done

# View traces in Jaeger UI
open http://localhost:16686
```

## 4. Troubleshooting

### 4.1 Common Issues

#### Services Fail to Start

**Symptom:** `docker-compose up -d` exits with errors

**Diagnosis:**
```bash
# Check service status
docker-compose ps

# View logs for failing service
docker-compose logs <service-name>

# Check port conflicts
netstat -an | grep -E '5432|9200|9000|1025|8025|16686'
```

**Resolution:**
- Ensure required ports are not in use by other processes
- Verify Docker has sufficient memory (4GB+ recommended)
- Check Docker daemon is running: `docker info`
- Review `.env` file for configuration errors

#### PostgreSQL Connection Refused

**Symptom:** `psql: could not connect to server: Connection refused`

**Diagnosis:**
```bash
# Check if container is running
docker-compose ps postgres

# View logs
docker-compose logs postgres

# Test connection from inside container
docker-compose exec postgres psql -U village -d village_homepage -c "SELECT 1;"
```

**Resolution:**
1. Wait for container to finish starting (check logs for "ready to accept connections")
2. Verify credentials in `.env` match `docker-compose.yml`
3. Ensure port 5432 is not blocked by firewall
4. Restart service: `docker-compose restart postgres`

#### Elasticsearch Unhealthy

**Symptom:** `curl localhost:9200/_cluster/health` returns red status

**Diagnosis:**
```bash
# Check cluster health
curl localhost:9200/_cluster/health?pretty

# View logs for errors
docker-compose logs elasticsearch | grep -i "error\|exception"

# Check disk space
docker-compose exec elasticsearch df -h
```

**Resolution:**
- Increase heap size in `docker-compose.yml` if memory errors
- Delete old indices to free space: `curl -X DELETE "localhost:9200/village-*"`
- Restart service: `docker-compose restart elasticsearch`
- Reset volume if corrupted: `docker-compose down elasticsearch && docker volume rm village-homepage_elasticsearch-data`

#### MinIO Buckets Not Created

**Symptom:** Application errors with "bucket does not exist"

**Diagnosis:**
```bash
# Check if minio-init completed
docker-compose logs minio-init

# List buckets
docker-compose exec minio mc ls local/
```

**Resolution:**
1. Ensure `minio-init` container ran successfully
2. Manually create buckets:
   ```bash
   docker-compose exec minio mc mb local/screenshots
   docker-compose exec minio mc mb local/listings
   docker-compose exec minio mc anonymous set download local/screenshots
   docker-compose exec minio mc anonymous set download local/listings
   ```
3. Restart init container: `docker-compose up -d minio-init`

#### Mailpit Not Receiving Emails

**Symptom:** No emails appear in Mailpit UI

**Diagnosis:**
```bash
# Check if Mailpit is running
docker-compose ps mailpit

# View SMTP logs
docker-compose logs -f mailpit

# Test SMTP connection
telnet localhost 1025
```

**Resolution:**
1. Verify application SMTP configuration points to `localhost:1025`
2. Check `.env` has correct `QUARKUS_MAILER_HOST=localhost` and `QUARKUS_MAILER_PORT=1025`
3. Ensure `QUARKUS_MAILER_TLS=false` and `QUARKUS_MAILER_AUTH=false` for local testing
4. Restart Mailpit: `docker-compose restart mailpit`

#### Jaeger Shows No Traces

**Symptom:** Jaeger UI displays no traces for `village-homepage` service

**Diagnosis:**
```bash
# Check Jaeger collector logs
docker-compose logs jaeger | grep -i "span\|trace"

# Verify application is sending traces
curl http://localhost:8080/q/health
docker-compose logs jaeger
```

**Resolution:**
1. Ensure Quarkus OpenTelemetry extension is configured:
   - Check `application.properties` for `quarkus.otel.exporter.otlp.endpoint=http://localhost:4317`
   - Verify `quarkus.otel.traces.enabled=true`
2. Restart Quarkus application to reconnect to Jaeger
3. Generate test traffic and wait 10-30 seconds for traces to appear
4. Check service name matches in Jaeger UI dropdown

### 4.2 Performance Optimization

#### Reduce Memory Usage

If Docker memory limits are reached:

```bash
# Reduce Elasticsearch heap size in docker-compose.yml
# Change: ES_JAVA_OPTS=-Xms512m -Xmx512m
# To:     ES_JAVA_OPTS=-Xms256m -Xmx256m

# Reduce Postgres memory limits
# Edit deploy.resources.limits.memory for postgres service

# Restart services
docker-compose down && docker-compose up -d
```

#### Speed Up Database Operations

```bash
# Increase Postgres shared_buffers (requires restart)
docker-compose exec postgres psql -U village -d village_homepage -c \
  "ALTER SYSTEM SET shared_buffers = '256MB';"
docker-compose restart postgres

# Update table statistics after large data loads
docker-compose exec postgres psql -U village -d village_homepage -c "ANALYZE;"
```

#### Elasticsearch Indexing Performance

```bash
# Increase refresh interval during bulk indexing
curl -X PUT "localhost:9200/village-*/_settings" -H 'Content-Type: application/json' -d'
{
  "index": {
    "refresh_interval": "30s"
  }
}'

# Reset to default after indexing
curl -X PUT "localhost:9200/village-*/_settings" -H 'Content-Type: application/json' -d'
{
  "index": {
    "refresh_interval": "1s"
  }
}'
```

### 4.3 Data Reset Procedures

#### Reset All Services (Clean Slate)

```bash
# WARNING: This destroys all data in volumes
docker-compose down -v
docker-compose up -d
cd migrations
set -a && source ../.env && set +a
mvn migration:up -Dmigration.env=development
cd ..
./scripts/load-geo-data.sh
```

#### Reset Single Service

```bash
# PostgreSQL
docker-compose down postgres
docker volume rm village-homepage_postgres-data
docker-compose up -d postgres
cd migrations
set -a && source ../.env && set +a
mvn migration:up -Dmigration.env=development
cd ..
./scripts/load-geo-data.sh

# Elasticsearch
docker-compose down elasticsearch
docker volume rm village-homepage_elasticsearch-data
docker-compose up -d elasticsearch

# MinIO
docker-compose down minio minio-init
docker volume rm village-homepage_minio-data
docker-compose up -d minio minio-init
```

## 5. Seeding Workflows

### 5.1 Database Migrations

See `migrations/README.md` for detailed instructions. Summary:

```bash
cd migrations
set -a && source ../.env && set +a
mvn migration:up -Dmigration.env=development
mvn migration:status -Dmigration.env=development
cd ..
```

### 5.2 Geographic Dataset

Loads dr5hn/countries-states-cities-database (153K+ cities) for marketplace location filtering:

```bash
./scripts/load-geo-data.sh
```

Options:
- `--dry-run` - Validate setup without loading
- `--force` - Drop and reload data

### 5.3 Feature Flags

Initial feature flags are seeded via migrations. To manually verify:

```bash
psql -h localhost -p 5432 -U village -d village_homepage -c \
  "SELECT name, enabled, rollout_percentage FROM feature_flags;"
```

Toggle flags via admin dashboard or directly:

```bash
psql -h localhost -p 5432 -U village -d village_homepage -c \
  "UPDATE feature_flags SET enabled = true WHERE name = 'stocks_widget';"
```

### 5.4 Test Data

Create test users and listings for development:

```bash
# Via Quarkus Dev UI
open http://localhost:8080/q/dev

# Or via REST API (requires authentication)
curl -X POST http://localhost:8080/api/dev/seed-test-data \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

## 6. Monitoring and Observability

### 6.1 Health Checks

```bash
# All services
docker-compose ps

# Individual health endpoints
curl http://localhost:9200/_cluster/health  # Elasticsearch
curl http://localhost:9000/minio/health/live  # MinIO
curl http://localhost:8025/api/v1/info  # Mailpit
curl http://localhost:16686  # Jaeger
psql -h localhost -p 5432 -U village -d village_homepage -c "SELECT 1;"  # Postgres
```

### 6.2 Log Aggregation

```bash
# Follow all service logs
docker-compose logs -f

# Follow specific service
docker-compose logs -f postgres

# Search logs for errors
docker-compose logs | grep -i "error\|exception\|fatal"

# Export logs to file
docker-compose logs > dev-services-logs.txt
```

### 6.3 Resource Usage

```bash
# Docker container stats
docker stats --no-stream

# Disk usage
docker-compose exec postgres df -h
docker-compose exec elasticsearch df -h
docker system df

# Volume sizes
docker volume ls
docker volume inspect village-homepage_postgres-data
```

## 7. Security Considerations

### 7.1 Development-Only Credentials

All credentials in `.env.example` are for **local development only**. Production secrets are managed via the `../villagecompute` infrastructure repository.

**Never commit**:
- Real API keys
- Production database credentials
- OAuth client secrets
- JWT signing keys

### 7.2 Network Isolation

All services run on an isolated `village-homepage-dev` Docker network. External access is limited to explicitly mapped ports.

### 7.3 Volume Permissions

Volumes are owned by Docker user namespaces. If permission errors occur:

```bash
# Fix volume permissions (MacOS/Linux)
docker-compose down
sudo chown -R $(id -u):$(id -g) data/mailpit
docker-compose up -d
```

## 8. Additional Resources

- [Docker Compose Documentation](https://docs.docker.com/compose/)
- [PostgreSQL 17 Documentation](https://www.postgresql.org/docs/17/)
- [PostGIS Documentation](https://postgis.net/documentation/)
- [Elasticsearch 8 Documentation](https://www.elastic.co/guide/en/elasticsearch/reference/8.11/index.html)
- [MinIO Documentation](https://min.io/docs/minio/linux/index.html)
- [Mailpit Documentation](https://github.com/axllent/mailpit)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/)
- [VillageCompute Infrastructure Repository](../villagecompute/README.md)
- [Village Homepage Migrations Guide](../../migrations/README.md)

## 9. Changelog

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-01-08 | Platform Squad | Initial documentation for I1.T6 |

---

**Feedback and Updates:**
This is a living document. Report issues or suggest improvements via GitHub issues or direct team communication.
