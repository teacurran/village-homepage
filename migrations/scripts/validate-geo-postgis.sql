-- validate-geo-postgis.sql
-- PostGIS Validation and Testing Script for Geographic Data
--
-- This script validates the geo tables import and tests PostGIS spatial queries
-- per Policy P6 and P11 requirements.
--
-- Usage:
--   psql -h $POSTGRES_HOST -p $POSTGRES_PORT -U $POSTGRES_USER -d $POSTGRES_DB \
--     -f migrations/scripts/validate-geo-postgis.sql
--
-- Expected Results:
--   - All queries should execute without errors
--   - Spatial index should be used (verify with EXPLAIN)
--   - Query execution time should be < 100ms per Policy P11

\echo ''
\echo '========================================='
\echo 'PostGIS Geographic Data Validation'
\echo '========================================='
\echo ''

-- 1. Verify PostGIS Extension
\echo '1. PostGIS Extension Status:'
SELECT PostGIS_Version() as postgis_version;
\echo ''

-- 2. Check Table Existence
\echo '2. Application Schema Tables:'
SELECT table_name
FROM information_schema.tables
WHERE table_name IN ('geo_countries', 'geo_states', 'geo_cities')
  AND table_schema = 'public'
ORDER BY table_name;
\echo ''

-- 3. Verify Data Counts
\echo '3. Record Counts:'
SELECT
    (SELECT COUNT(*) FROM geo_countries) as countries,
    (SELECT COUNT(*) FROM geo_states) as states,
    (SELECT COUNT(*) FROM geo_cities) as cities,
    (SELECT COUNT(*) FROM geo_cities WHERE location IS NOT NULL) as cities_with_location;
\echo ''
\echo 'Expected: countries=2 (US+CA), states=60-70, cities=38k-42k, 100% location coverage'
\echo ''

-- 4. Verify US + Canada Filtering (Policy P6)
\echo '4. Country Filter Compliance (Policy P6):'
SELECT iso2, name, COUNT(*) OVER () as total_countries
FROM geo_countries
ORDER BY iso2;
\echo ''
\echo 'Expected: Only US and CA'
\echo ''

-- 5. Check Spatial Indexes
\echo '5. Spatial Indexes:'
SELECT
    schemaname,
    tablename,
    indexname,
    indexdef
FROM pg_indexes
WHERE tablename = 'geo_cities'
  AND indexdef LIKE '%GIST%'
ORDER BY indexname;
\echo ''
\echo 'Expected: idx_geo_cities_location with GIST'
\echo ''

-- 6. Test Radius Query - 50 miles from Seattle
\echo '6. Radius Query Test: 50 miles from Seattle, WA (-122.3321, 47.6062):'
SELECT
    name,
    ROUND(ST_Distance(location, ST_MakePoint(-122.3321, 47.6062)::geography) / 1609.34, 2) as distance_miles
FROM geo_cities
WHERE ST_DWithin(location, ST_MakePoint(-122.3321, 47.6062)::geography, 80467)  -- 50 miles
ORDER BY distance_miles
LIMIT 10;
\echo ''

-- 7. Test Radius Query - 100 miles from New York City
\echo '7. Radius Query Test: 100 miles from NYC (-74.0060, 40.7128):'
SELECT
    name,
    ROUND(ST_Distance(location, ST_MakePoint(-74.0060, 40.7128)::geography) / 1609.34, 2) as distance_miles
FROM geo_cities
WHERE ST_DWithin(location, ST_MakePoint(-74.0060, 40.7128)::geography, 160934)  -- 100 miles
ORDER BY distance_miles
LIMIT 10;
\echo ''

-- 8. Nearest City to Arbitrary Coordinates (Los Angeles area)
\echo '8. Nearest City Query: Coordinates (-118.2437, 34.0522):'
SELECT
    name,
    ROUND(ST_Distance(location, ST_MakePoint(-118.2437, 34.0522)::geography) / 1609.34, 2) as distance_miles
FROM geo_cities
ORDER BY location <-> ST_MakePoint(-118.2437, 34.0522)::geography
LIMIT 1;
\echo ''
\echo 'Expected: Los Angeles or nearby city in CA'
\echo ''

-- 9. Cities Count by State (Top 10)
\echo '9. Cities per State (Top 10):'
SELECT
    s.state_code,
    s.name as state_name,
    COUNT(c.id) as city_count
FROM geo_states s
JOIN geo_cities c ON c.state_id = s.id
GROUP BY s.state_code, s.name
ORDER BY city_count DESC
LIMIT 10;
\echo ''

-- 10. Cross-City Radius Query (100 miles from Portland, OR)
\echo '10. Cities within 100 miles of Portland, OR:'
SELECT
    c2.name,
    ROUND(ST_Distance(c1.location, c2.location) / 1609.34, 2) as distance_miles
FROM geo_cities c1
CROSS JOIN geo_cities c2
WHERE c1.name = 'Portland'
  AND c1.state_id = (SELECT id FROM geo_states WHERE state_code = 'OR' LIMIT 1)
  AND ST_DWithin(c1.location, c2.location, 160934)  -- 100 miles
  AND c1.id != c2.id
ORDER BY distance_miles
LIMIT 15;
\echo ''

-- 11. Verify Spatial Index Usage (Query Plan Analysis)
\echo '11. Query Plan Analysis - Verify Index Usage:'
\echo 'EXPLAIN output for 100-mile NYC radius query:'
EXPLAIN (FORMAT TEXT)
SELECT COUNT(*)
FROM geo_cities
WHERE ST_DWithin(location, ST_MakePoint(-74.0060, 40.7128)::geography, 160934);
\echo ''
\echo 'Expected: "Index Scan using idx_geo_cities_location" (not Seq Scan)'
\echo ''

-- 12. Performance Benchmark (Policy P11: p95 < 100ms for <= 100mi)
\echo '12. Performance Benchmark - 100 mile radius from NYC:'
\echo 'Policy P11 target: < 100ms for p95, < 200ms for p99'
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT COUNT(*)
FROM geo_cities
WHERE ST_DWithin(location, ST_MakePoint(-74.0060, 40.7128)::geography, 160934);
\echo ''
\echo 'Check "Execution Time" above - should be < 100ms'
\echo ''

-- 13. Location Column Integrity
\echo '13. Location Column Integrity Check:'
SELECT
    COUNT(*) as total_cities,
    COUNT(location) as cities_with_location,
    COUNT(*) - COUNT(location) as cities_missing_location,
    ROUND(100.0 * COUNT(location) / COUNT(*), 2) as location_coverage_pct
FROM geo_cities;
\echo ''
\echo 'Expected: 100% coverage (0 missing)'
\echo ''

-- 14. Coordinate Validity Check
\echo '14. Coordinate Validity (US + Canada bounding box):'
SELECT
    COUNT(*) as total,
    COUNT(*) FILTER (WHERE latitude BETWEEN 24.396308 AND 83.11611) as valid_lat_count,
    COUNT(*) FILTER (WHERE longitude BETWEEN -168.0 AND -52.6) as valid_lng_count,
    COUNT(*) FILTER (
        WHERE latitude BETWEEN 24.396308 AND 83.11611
          AND longitude BETWEEN -168.0 AND -52.6
    ) as within_bounding_box
FROM geo_cities;
\echo ''
\echo 'Expected: All cities should fall within US+Canada bounding box'
\echo ''

\echo ''
\echo '========================================='
\echo 'Validation Complete'
\echo '========================================='
\echo ''
\echo 'If all queries executed successfully:'
\echo '  ✓ PostGIS extension is enabled'
\echo '  ✓ Geographic tables are populated'
\echo '  ✓ Spatial indexes are in place'
\echo '  ✓ US + Canada filtering is working (Policy P6)'
\echo '  ✓ Radius queries are functional'
\echo '  ✓ Query performance meets Policy P11 targets'
\echo ''
\echo 'Next Steps:'
\echo '  - Create Java entity classes (GeoCountry, GeoState, GeoCity)'
\echo '  - Implement GeoService with radius query helpers'
\echo '  - Build marketplace listing location filtering'
\echo ''
