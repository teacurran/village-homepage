# Marketplace Search Operations Guide

**Last Updated:** 2025-01-10
**Version:** 1.0
**Iteration:** I4.T5

## Overview

The Village Homepage marketplace search system combines **Elasticsearch** full-text indexing with **PostGIS** geographic queries to deliver fast, accurate search results for classified listings. This guide covers operational procedures, configuration tuning, troubleshooting, and performance monitoring.

---

## Architecture

### Two-Phase Search Strategy

The search implementation uses a hybrid approach to meet Policy P11 performance targets (<200ms p99 for 250-mile radius queries):

1. **Phase 1: Elasticsearch**
   - Full-text search on `title` and `description` fields
   - Fuzzy matching with 2-character typo tolerance
   - Filters: category, price range, date range
   - Sorting: newest (created_at DESC), price_asc, price_desc
   - Returns up to 500 results before PostGIS filtering

2. **Phase 2: PostGIS (if geographic search)**
   - Fetches center location from `geo_cities` by `geoCityId`
   - Joins `marketplace_listings` with `geo_cities` on `geo_city_id`
   - Filters using `ST_DWithin(listing_location, center_location, radius_meters)`
   - Calculates distance for sorting: `ST_Distance() / 1609.34 AS distance_miles`
   - Returns final result set ordered by distance (if `sortBy=distance`)

### Resilience & Fallback

If Elasticsearch is unavailable, the service degrades gracefully to **Postgres-only queries**:

- Text search uses `ILIKE '%query%'` on title/description (slower, but functional)
- Filters applied via Panache queries with `Parameters`
- PostGIS radius filtering still used for geographic searches
- Metrics track `marketplace.search.elasticsearch.errors.total` counter

---

## Configuration

### Elasticsearch Settings

Located in `src/main/resources/application.yaml`:

```yaml
quarkus:
  hibernate-search-orm:
    elasticsearch:
      # Dev: localhost, Prod: ES cluster endpoint
      hosts: ${ELASTICSEARCH_HOSTS:localhost:9200}
      protocol: http

      # Automatic indexing: sync updates to ES on entity persist/update
      automatic-indexing:
        enabled: true
        synchronization-strategy: sync

      # Schema management: create-or-update for dev, validate in prod
      schema-management:
        strategy: ${ELASTICSEARCH_SCHEMA_STRATEGY:create-or-update}

      # Indexing queue configuration for async operations
      indexing:
        queue-count: 4
        queue-size: 50

      # Request timeout for ES operations
      request-timeout: 10s
```

### Environment Variables

| Variable | Description | Default | Production |
|----------|-------------|---------|------------|
| `ELASTICSEARCH_HOSTS` | ES cluster endpoint | `localhost:9200` | `es-cluster.internal:9200` |
| `ELASTICSEARCH_SCHEMA_STRATEGY` | Schema management mode | `create-or-update` | `validate` |

### Database Indexes

Created by migration `20250110002300_add_marketplace_search_indexes.sql`:

| Index Name | Columns | Purpose | Type |
|------------|---------|---------|------|
| `idx_marketplace_listings_search` | `(status, category_id, created_at DESC)` | Category browse with date sort | B-tree, partial (status='active') |
| `idx_marketplace_listings_price` | `(price)` | Price range filtering | B-tree, partial (status='active', price IS NOT NULL) |
| `idx_marketplace_listings_created_at` | `(created_at DESC)` | Date range filtering | B-tree, partial (status='active') |
| `idx_marketplace_listings_geo_city_id` | `(geo_city_id)` | PostGIS JOIN optimization | B-tree, partial (status='active', geo_city_id IS NOT NULL) |
| `idx_geo_cities_location` | `(location)` | PostGIS radius queries | GIST spatial (created in I4.T1) |

---

## Performance Tuning

### Elasticsearch Tuning Knobs

#### 1. Indexing Queue Configuration

**Parameter:** `quarkus.hibernate-search-orm.elasticsearch.indexing.queue-count`
**Default:** `4`
**Tuning:** Increase for higher throughput (e.g., 8 for bulk imports), decrease for memory-constrained environments (e.g., 2).

**Parameter:** `quarkus.hibernate-search-orm.elasticsearch.indexing.queue-size`
**Default:** `50`
**Tuning:** Increase for batch indexing (e.g., 100), decrease for low-latency updates (e.g., 25).

#### 2. Request Timeout

**Parameter:** `quarkus.hibernate-search-orm.elasticsearch.request-timeout`
**Default:** `10s`
**Tuning:** Increase for slow ES clusters (e.g., 30s), decrease for fail-fast behavior (e.g., 5s).

#### 3. Elasticsearch JVM Heap

**Docker Compose:** `ES_JAVA_OPTS=-Xms512m -Xmx512m` (dev)
**Production:** `-Xms2g -Xmx2g` (recommended for 100K+ listings)

**Formula:** Heap size = ~50% of available RAM, max 32GB (compressed oops limit)

### PostGIS Tuning Knobs

#### 1. GIST Index Build

**Migration:** `CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_geo_cities_location ON geo_cities USING GIST(location)`

**Tuning:** Use `CONCURRENTLY` to avoid table locks during index creation. For initial bulk load, drop `CONCURRENTLY` for faster build.

#### 2. Work Memory

**Parameter:** `work_mem` (Postgres)
**Default:** `4MB`
**Tuning:** Increase for faster spatial joins (e.g., `16MB`). Monitor with `EXPLAIN ANALYZE` to detect disk-based sorts.

#### 3. Effective Cache Size

**Parameter:** `effective_cache_size` (Postgres)
**Default:** 50% of RAM
**Tuning:** Set to ~75% of available RAM for read-heavy workloads.

### Search Service Configuration

#### Max ES Results Before Radius Filter

**Code:** `MarketplaceSearchService.MAX_ES_RESULTS_BEFORE_RADIUS_FILTER`
**Default:** `500`
**Tuning:**
- Increase for broader geographic searches (e.g., 1000 for 250-mile radius in dense urban areas)
- Decrease for faster response times (e.g., 250 for sparse rural areas)
- Monitor `marketplace.search.results.count` metric to tune

---

## Monitoring & Metrics

### Key Metrics

All metrics exported in Prometheus format at `/q/metrics`.

#### Request Metrics

```promql
# Total search requests by filter type
marketplace_search_requests_total{has_radius="true", has_category="true", has_filters="true"}

# Search query latency (p50, p95, p99)
marketplace_search_duration{quantile="0.99"}

# Result set sizes (histogram)
marketplace_search_results_count_summary
```

#### Error Metrics

```promql
# Elasticsearch failures (triggers fallback to Postgres)
marketplace_search_elasticsearch_errors_total

# Rate: ES errors per minute
rate(marketplace_search_elasticsearch_errors_total[1m])
```

#### Performance Metrics

```promql
# p99 latency (Policy P11 target: <200ms)
histogram_quantile(0.99, marketplace_search_duration_bucket)

# p95 latency
histogram_quantile(0.95, marketplace_search_duration_bucket)
```

### Alert Thresholds

| Metric | Threshold | Severity | Action |
|--------|-----------|----------|--------|
| `marketplace_search_duration{quantile="0.99"}` | > 200ms | Warning | Review slow query logs, check ES cluster health |
| `marketplace_search_duration{quantile="0.99"}` | > 500ms | Critical | Scale ES cluster, optimize indexes |
| `marketplace_search_elasticsearch_errors_total` | > 10/min | Warning | Check ES connectivity, review error logs |
| `marketplace_search_elasticsearch_errors_total` | > 50/min | Critical | ES cluster down, verify Postgres fallback working |
| `marketplace_search_results_count` | > 500 | Info | Consider pagination limits, monitor PostGIS filter efficiency |

### Grafana Dashboard Queries

#### Search Request Rate

```promql
sum(rate(marketplace_search_requests_total[5m])) by (has_radius, has_category)
```

#### Search Latency Heatmap

```promql
sum(rate(marketplace_search_duration_bucket[5m])) by (le)
```

#### ES Fallback Rate

```promql
rate(marketplace_search_elasticsearch_errors_total[1m]) /
rate(marketplace_search_requests_total[1m])
```

---

## Elasticsearch Index Management

### Reindexing

**When to reindex:**
- After schema changes (new fields, analyzer updates)
- After bulk data imports
- After ES cluster migration
- When mapping updates are required

**Procedure:**

```bash
# 1. Trigger mass indexer via API endpoint (future enhancement)
curl -X POST http://localhost:8080/admin/api/search/reindex

# 2. Or use Quarkus CLI (requires app restart)
./mvnw quarkus:dev -Dquarkus.hibernate-search-orm.schema-management.strategy=drop-and-create

# 3. Or programmatic reindex (via DevUI or custom endpoint)
SearchMapping mapping = Search.mapping(entityManager.getEntityManagerFactory());
mapping.scope(MarketplaceListing.class).massIndexer()
    .threadsToLoadObjects(4)
    .batchSizeToLoadObjects(50)
    .startAndWait();
```

**Estimated time:** ~10 seconds per 1,000 listings (depends on ES cluster performance)

### Index Health Check

```bash
# Check ES cluster health
curl http://localhost:9200/_cluster/health?pretty

# Check MarketplaceListing index status
curl http://localhost:9200/marketplacelisting-*/_stats?pretty

# Check index mapping
curl http://localhost:9200/marketplacelisting-*/_mapping?pretty
```

### Mapping Updates

**Example: Add new searchable field**

1. Update `MarketplaceListing.java` entity with Hibernate Search annotation:
   ```java
   @FullTextField(analyzer = "english")
   public String newField;
   ```

2. Update `ELASTICSEARCH_SCHEMA_STRATEGY=drop-and-create` (dev only)

3. Restart app to rebuild index

4. For production: Use reindexing procedure without downtime (see above)

---

## Troubleshooting

### Search Returns No Results

**Symptoms:** Query returns 0 results despite matching listings existing in database.

**Diagnosis:**

1. Check if Elasticsearch index exists:
   ```bash
   curl http://localhost:9200/_cat/indices?v | grep marketplace
   ```

2. Check index document count:
   ```bash
   curl http://localhost:9200/marketplacelisting-*/_count
   ```

3. Verify listing is indexed:
   ```bash
   curl http://localhost:9200/marketplacelisting-*/_search?q=title:bicycle
   ```

**Solutions:**

- If index missing: Restart app with `schema-management.strategy=create-or-update`
- If document count = 0: Trigger reindexing (see Index Management section)
- If listing not found: Check `status` field (only `active` listings indexed)
- If ES down: Verify Postgres fallback working by checking logs for "Elasticsearch search failed, falling back to Postgres"

### Search Query Slow (> 200ms p99)

**Symptoms:** High `marketplace_search_duration{quantile="0.99"}` metric.

**Diagnosis:**

1. Check ES cluster performance:
   ```bash
   curl http://localhost:9200/_nodes/stats/indices/search?pretty
   ```

2. Review slow query logs in ES:
   ```bash
   curl http://localhost:9200/marketplacelisting-*/_search/slowlog
   ```

3. Analyze PostGIS query plan:
   ```sql
   EXPLAIN ANALYZE
   SELECT l.id, ST_Distance(gc_listing.location, gc_center.location) / 1609.34 as distance_miles
   FROM marketplace_listings l
   JOIN geo_cities gc_listing ON gc_listing.id = l.geo_city_id
   CROSS JOIN geo_cities gc_center
   WHERE gc_center.id = 123
     AND l.status = 'active'
     AND ST_DWithin(gc_listing.location, gc_center.location, 50 * 1609.34)
   ORDER BY distance_miles ASC
   LIMIT 25;
   ```

**Solutions:**

- **ES cluster overloaded:** Scale horizontally (add nodes) or vertically (increase heap)
- **PostGIS query slow:** Verify GIST index exists on `geo_cities.location`
- **Too many results before PostGIS filter:** Reduce `MAX_ES_RESULTS_BEFORE_RADIUS_FILTER` (code change)
- **Missing database indexes:** Run migration `20250110002300_add_marketplace_search_indexes.sql`
- **Postgres work_mem too low:** Increase `work_mem` parameter

### Elasticsearch Connection Failures

**Symptoms:** Repeated `marketplace.search.elasticsearch.errors.total` counter increments.

**Diagnosis:**

1. Check ES health:
   ```bash
   curl http://localhost:9200/_cluster/health
   ```

2. Review application logs for connection errors:
   ```bash
   grep "Elasticsearch search failed" logs/application.log
   ```

3. Verify network connectivity:
   ```bash
   telnet localhost 9200
   ```

**Solutions:**

- **ES cluster down:** Restart ES container (`docker-compose restart elasticsearch`)
- **Network timeout:** Increase `request-timeout` in `application.yaml`
- **Wrong endpoint:** Update `ELASTICSEARCH_HOSTS` environment variable
- **Fallback working:** If Postgres fallback is handling queries, ES outage is non-critical (degraded performance expected)

### PostGIS Queries Inaccurate

**Symptoms:** Distance calculations incorrect, listings outside radius returned.

**Diagnosis:**

1. Verify PostGIS extension installed:
   ```sql
   SELECT PostGIS_Version();
   ```

2. Check geo_cities location data integrity:
   ```sql
   SELECT COUNT(*) FROM geo_cities WHERE location IS NULL;
   ```

3. Verify GIST index exists:
   ```sql
   SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'geo_cities' AND indexname = 'idx_geo_cities_location';
   ```

**Solutions:**

- **PostGIS not installed:** Run `20250110001800_create_geo_tables.sql` migration
- **Missing location data:** Run `./scripts/load-geo-data.sh` to import city coordinates
- **GIST index missing:** Run `CREATE INDEX idx_geo_cities_location ON geo_cities USING GIST(location);`
- **Coordinate projection mismatch:** Verify `SRID 4326` (WGS 84) used consistently

---

## Capacity Planning

### Elasticsearch Sizing

**Formula:** Shard size = (listing count × avg listing size) / shard count

**Example:**
- 100,000 listings
- Avg listing size: 5KB (title + description + metadata)
- Total index size: 500MB
- Recommended shard count: 1 (< 50GB per shard)
- Recommended replicas: 1 (for HA)

**Scaling triggers:**
- **> 100K listings:** Increase heap to 2GB
- **> 500K listings:** Add replica nodes (3-node cluster)
- **> 1M listings:** Shard across multiple indexes by date/category

### PostgreSQL Sizing

**PostGIS queries:** ~10MB RAM per concurrent query (work_mem + cache)

**Recommended specs:**
- **< 100K listings:** 2 CPU, 4GB RAM
- **< 500K listings:** 4 CPU, 8GB RAM
- **> 1M listings:** 8 CPU, 16GB RAM

---

## Operational Procedures

### Rolling Restart (Zero Downtime)

```bash
# 1. Drain traffic from pod 1
kubectl scale deployment village-homepage --replicas=2
kubectl cordon pod-1

# 2. Wait for pod 1 to finish active requests
sleep 30

# 3. Restart pod 1
kubectl delete pod pod-1

# 4. Verify pod 1 healthy
kubectl wait --for=condition=Ready pod/pod-1

# 5. Repeat for remaining pods
```

### Emergency ES Cluster Rebuild

If ES cluster is corrupted and reindexing fails:

```bash
# 1. Stop application pods to prevent writes
kubectl scale deployment village-homepage --replicas=0

# 2. Delete ES indexes
curl -X DELETE http://localhost:9200/marketplacelisting-*

# 3. Restart app with schema recreation
kubectl set env deployment/village-homepage ELASTICSEARCH_SCHEMA_STRATEGY=drop-and-create
kubectl scale deployment village-homepage --replicas=1

# 4. Wait for index creation
sleep 60

# 5. Trigger mass reindex
curl -X POST http://localhost:8080/admin/api/search/reindex

# 6. Restore normal schema mode
kubectl set env deployment/village-homepage ELASTICSEARCH_SCHEMA_STRATEGY=validate

# 7. Scale back to normal replica count
kubectl scale deployment village-homepage --replicas=3
```

---

## Future Enhancements

- **Saved searches (Feature F12.10):** Store `SearchCriteria` in `user_saved_searches` table
- **"Has images" filter:** After I4.T6 creates `marketplace_listing_images` table
- **Geographic autocomplete:** Integrate Elasticsearch geo-suggest for location input
- **Personalized ranking:** ML-based ranking using click-through data
- **Faceted search:** Add category tree navigation, price buckets, location clusters

---

## References

- **Policy P11:** PostGIS-Only Geographic Search (<200ms p99 target)
- **Feature F12.2:** Marketplace search with filters and sorting
- **Migration:** `20250110002300_add_marketplace_search_indexes.sql`
- **Service:** `src/main/java/villagecompute/homepage/services/MarketplaceSearchService.java`
- **Endpoint:** `src/main/java/villagecompute/homepage/api/rest/MarketplaceSearchResource.java`
- **Tests:** `src/test/java/villagecompute/homepage/services/MarketplaceSearchServiceTest.java`

---

**For support:** Contact ops team via #village-homepage Slack channel or ops@villagecompute.com
