-- Migration: Add marketplace search optimization indexes (I4.T5)
-- Purpose: Optimize Elasticsearch + PostGIS search queries per Policy P11 (<200ms p99 for 250-mile radius)
-- Dependencies: 20250110001800_create_geo_tables.sql, 20250110002000_create_marketplace_listings.sql

-- COMPOSITE INDEX: status + category_id + created_at
-- Purpose: Optimize filtering for active listings by category with date sorting
-- Used by: Elasticsearch fallback queries, category browse pages
-- Performance: Enables index-only scans for common "active listings in category, newest first" queries
-- Index size estimate: ~5MB for 100K active listings
-- Rationale: Avoids seq scan on marketplace_listings when filtering by status + category
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_marketplace_listings_search
ON marketplace_listings (status, category_id, created_at DESC)
WHERE status = 'active';

COMMENT ON INDEX idx_marketplace_listings_search IS
'Composite index for marketplace search queries filtering active listings by category and sorting by creation date. '
'Partial index includes only active listings to reduce index size. Policy P11 target: <200ms p99 for search queries.';

-- PRICE FILTERING INDEX
-- Purpose: Optimize price range queries (min_price, max_price filters)
-- Used by: Search queries with price filters, price-sorted results
-- Performance: Enables index scans for "active listings in price range" queries
-- Rationale: Price is frequently used filter in marketplace search
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_marketplace_listings_price
ON marketplace_listings (price)
WHERE status = 'active' AND price IS NOT NULL;

COMMENT ON INDEX idx_marketplace_listings_price IS
'Index for price range filtering in marketplace search. '
'Partial index includes only active listings with non-null prices to reduce index size.';

-- CREATED_AT INDEX FOR DATE RANGE FILTERING
-- Purpose: Optimize date range queries (min_date, max_date filters)
-- Used by: "Listings from last week", "Newest listings" queries
-- Performance: Enables index scans for date-based filtering
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_marketplace_listings_created_at
ON marketplace_listings (created_at DESC)
WHERE status = 'active';

COMMENT ON INDEX idx_marketplace_listings_created_at IS
'Index for date range filtering and sorting in marketplace search. '
'Partial index includes only active listings. Descending order matches default sort (newest first).';

-- GEO_CITY_ID INDEX FOR LOCATION LOOKUPS
-- Purpose: Optimize PostGIS JOIN on geo_cities table during radius filtering
-- Used by: Two-phase search (Elasticsearch results filtered by PostGIS radius)
-- Performance: Speeds up JOIN marketplace_listings -> geo_cities by geo_city_id
-- Rationale: PostGIS queries need fast FK lookup to resolve listing locations
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_marketplace_listings_geo_city_id
ON marketplace_listings (geo_city_id)
WHERE status = 'active' AND geo_city_id IS NOT NULL;

COMMENT ON INDEX idx_marketplace_listings_geo_city_id IS
'Index for geo_city_id foreign key lookups during PostGIS radius filtering. '
'Partial index includes only active listings with locations. '
'Enables fast JOIN to geo_cities for ST_DWithin() distance calculations.';

-- VERIFY GIST SPATIAL INDEX EXISTS (created in 20250110001800_create_geo_tables.sql)
-- Purpose: Ensure PostGIS spatial index is present for ST_DWithin() queries
-- NOTE: This is a validation check, not creating the index (already exists from I4.T1)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'geo_cities'
          AND indexname = 'idx_geo_cities_location'
    ) THEN
        RAISE EXCEPTION 'Missing GIST spatial index on geo_cities.location - run 20250110001800_create_geo_tables.sql';
    END IF;
END
$$;

-- PERFORMANCE VALIDATION QUERY
-- Purpose: Verify indexes are being used for common search patterns
-- Expected: All queries below should use index scans (not seq scans)
-- Usage: Run EXPLAIN ANALYZE on these queries to validate index effectiveness

-- Test 1: Category + date sort (should use idx_marketplace_listings_search)
-- EXPLAIN ANALYZE
-- SELECT id, title, created_at
-- FROM marketplace_listings
-- WHERE status = 'active' AND category_id = 'some-uuid'
-- ORDER BY created_at DESC
-- LIMIT 25;

-- Test 2: Price range filter (should use idx_marketplace_listings_price)
-- EXPLAIN ANALYZE
-- SELECT id, title, price
-- FROM marketplace_listings
-- WHERE status = 'active' AND price BETWEEN 50.00 AND 500.00
-- ORDER BY price ASC
-- LIMIT 25;

-- Test 3: PostGIS radius query (should use idx_geo_cities_location GIST index)
-- EXPLAIN ANALYZE
-- SELECT l.id, ST_Distance(gc_listing.location, gc_center.location) / 1609.34 as distance_miles
-- FROM marketplace_listings l
-- JOIN geo_cities gc_listing ON gc_listing.id = l.geo_city_id
-- CROSS JOIN geo_cities gc_center
-- WHERE gc_center.id = 123
--   AND l.status = 'active'
--   AND ST_DWithin(gc_listing.location, gc_center.location, 50 * 1609.34)
-- ORDER BY distance_miles ASC
-- LIMIT 25;

-- VACUUM ANALYZE for query planner statistics
ANALYZE marketplace_listings;
ANALYZE geo_cities;

-- Index build statistics
SELECT
    schemaname,
    tablename,
    indexname,
    pg_size_pretty(pg_relation_size(indexrelid)) as index_size,
    idx_scan as times_used,
    idx_tup_read as tuples_read,
    idx_tup_fetch as tuples_fetched
FROM pg_stat_user_indexes
WHERE tablename = 'marketplace_listings'
ORDER BY indexname;
