# ARM64 (Apple Silicon) Compatibility Guide

**Version:** 1.0
**Last Updated:** 2026-01-08
**Owner:** Platform Squad
**Status:** Active

## Overview

This guide documents ARM64/Apple Silicon compatibility for Village Homepage development services. Some Docker images used in the stack do not provide native ARM64 builds and must run via platform emulation.

## Current Status

### Native ARM64 Support
These services have native ARM64 images and run without emulation:
- ✅ **PostgreSQL 17** - Use `postgis/postgis:16-3.5` instead of 17 for ARM64 support
- ✅ **MinIO** - Latest releases support ARM64 natively
- ✅ **Mailpit** - Native ARM64 support

### Requires Platform Emulation (linux/amd64)
These services require `platform: linux/amd64` specification:
- ⚠️ **Elasticsearch 8** - No ARM64 builds available for 8.17.x
- ⚠️ **Jaeger** - Limited ARM64 support in all-in-one image
- ⚠️ **MinIO Client (mc)** - Initialization container needs amd64

## Recommended Configuration

### Option 1: Use PostgreSQL 16 (Recommended for ARM64)

For Apple Silicon/ARM64 development, use PostgreSQL 16 with PostGIS instead of 17:

```yaml
postgres:
  image: postgis/postgis:16-3.5
  # ... rest of configuration
```

**Trade-offs:**
- ✅ Native ARM64 performance (no emulation overhead)
- ✅ PostGIS 3.5 fully supported on PostgreSQL 16
- ⚠️ Version mismatch with production (PostgreSQL 17)
- ⚠️ May miss PostgreSQL 17-specific features

### Option 2: Enable Rosetta 2 Emulation (Full Compatibility)

Enable Rosetta 2 emulation in Docker Desktop for better x86_64 performance:

1. Open Docker Desktop Settings
2. Navigate to "General"
3. Enable "Use Virtualization framework"
4. Navigate to "Features in development"
5. Enable "Use Rosetta for x86_64/amd64 emulation on Apple Silicon"
6. Restart Docker Desktop

```yaml
# Keep PostgreSQL 17 with platform emulation
postgres:
  image: postgis/postgis:17-3.5
  platform: linux/amd64
  # ... rest of configuration
```

**Trade-offs:**
- ✅ Exact version match with production
- ✅ All PostgreSQL 17 features available
- ⚠️ Slower performance due to emulation
- ⚠️ Higher CPU/memory usage

### Option 3: Use External PostgreSQL

Install PostgreSQL 17 + PostGIS natively via Homebrew:

```bash
# Install PostgreSQL 17 and PostGIS
brew install postgresql@17 postgis

# Start service
brew services start postgresql@17

# Create database
createdb village_homepage

# Enable PostGIS
psql village_homepage -c "CREATE EXTENSION postgis;"
```

Update `.env` to point to localhost:

```env
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
```

Comment out `postgres` service in `docker-compose.yml`.

**Trade-offs:**
- ✅ Best performance (native ARM64)
- ✅ Production version parity
- ⚠️ Manual PostgreSQL management
- ⚠️ Not portable across team (requires setup per developer)

## Current docker-compose.yml Configuration

The provided `docker-compose.yml` uses **Option 2** (platform emulation) for maximum compatibility:

```yaml
postgres:
  image: postgis/postgis:17-3.5
  platform: linux/amd64  # Emulation required
  # ...

elasticsearch:
  image: docker.elastic.co/elasticsearch/elasticsearch:8.17.2
  platform: linux/amd64  # Emulation required
  # ...

jaeger:
  image: jaegertracing/all-in-one:1.53
  platform: linux/amd64  # Emulation required
  # ...

minio-init:
  image: minio/mc:latest
  platform: linux/amd64  # Emulation required
  # ...
```

## Performance Considerations

### With Rosetta 2 Enabled
- PostgreSQL: ~70-80% of native performance
- Elasticsearch: ~60-70% of native performance
- Overall dev experience: Acceptable for local development

### Without Rosetta 2
- Services may fail to start or crash unexpectedly
- Severe performance degradation (10-20x slower)

### Recommended for ARM64 Users
1. Enable Rosetta 2 in Docker Desktop (see Option 2 above)
2. Increase Docker memory limit to 6GB+ (emulation uses more resources)
3. Monitor `docker stats` to ensure services aren't resource-starved

## Troubleshooting

### Error: "no matching manifest for linux/arm64/v8"

This error occurs when Docker cannot find an ARM64 image and platform emulation is not configured.

**Solution:**
1. Ensure `platform: linux/amd64` is specified for affected services
2. Enable Rosetta 2 emulation in Docker Desktop
3. Restart Docker Desktop and try `docker compose up -d` again

### Slow Performance on ARM64

**Symptoms:**
- Services take 2-3 minutes to become healthy
- PostgreSQL queries are unusually slow
- High CPU usage from `qemu-*` processes

**Solutions:**
1. Enable Rosetta 2 (see Option 2 above)
2. Increase Docker Desktop memory allocation to 6-8GB
3. Consider using native PostgreSQL 16 (Option 1) or external install (Option 3)

### Elasticsearch Fails to Start

**Symptoms:**
```
Elasticsearch Error: max virtual memory areas vm.max_map_count [65530] is too low
```

**Solution:**
```bash
# Increase vm.max_map_count (macOS with Docker Desktop)
screen ~/Library/Containers/com.docker.docker/Data/vms/0/tty
# Login as root (no password)
sysctl -w vm.max_map_count=262144
# Press Ctrl+A, then D to detach
```

## Future Improvements

**When ARM64 Images Become Available:**
- Remove `platform: linux/amd64` specifications
- Update to native ARM64 images for better performance
- Document version upgrades in this file

**Elasticsearch ARM64 Tracking:**
- Watch https://github.com/elastic/elasticsearch/issues/31339
- Check Elastic's official ARM64 support roadmap

**PostGIS 17 ARM64 Tracking:**
- Watch https://github.com/postgis/docker-postgis/issues

## References

- [Docker Desktop on Apple Silicon](https://docs.docker.com/desktop/mac/apple-silicon/)
- [Rosetta 2 Emulation](https://docs.docker.com/desktop/settings/mac/#use-rosetta-for-x86_64amd64-emulation-on-apple-silicon)
- [Elasticsearch Docker ARM64 Support](https://www.elastic.co/guide/en/elasticsearch/reference/current/docker.html#docker-arm64)
- [PostGIS Docker Hub](https://hub.docker.com/r/postgis/postgis)

---

**For Intel/AMD64 Users:**
This guide does not apply. The standard `docker-compose.yml` will work without modification on x86_64 architectures.
