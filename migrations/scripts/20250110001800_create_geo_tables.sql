-- // create_geo_tables
-- Creates geographic reference tables for marketplace location filtering per Policy P6.
--
-- This migration defines the schema for geo_countries, geo_states, and geo_cities.
-- After applying this migration, run the data loader to populate tables:
--   ./scripts/import-geo-data-to-app-schema.sh
--
-- The loader script downloads and imports the dr5hn/countries-states-cities-database
-- which provides 153K+ global cities. Per Policy P6, we filter to US + Canada only (~40K cities).
--
-- Policy References:
--   P6: PostGIS spatial indexing for radius queries (5-250 mile scope)
--   P11: Marketplace search optimization (p95 < 100ms for ≤100mi radius)
--   F12.1: Geographic data requirements from dr5hn dataset
--
-- PostGIS Extension:
--   Requires PostGIS to be enabled (handled by bootstrap/init-postgis.sql)
--   Uses GEOGRAPHY(Point, 4326) for accurate distance calculations
--   GIST spatial indexes enable sub-200ms radius queries per P11

-- Countries table (filtered to US + Canada per P6)
CREATE TABLE geo_countries (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    iso2 TEXT NOT NULL,
    iso3 TEXT NOT NULL,
    phone_code TEXT,
    capital TEXT,
    currency TEXT,
    native_name TEXT,
    region TEXT,
    subregion TEXT,
    latitude NUMERIC(10, 7),
    longitude NUMERIC(10, 7),
    emoji TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Unique constraint on ISO codes
CREATE UNIQUE INDEX idx_geo_countries_iso2 ON geo_countries(iso2);
CREATE UNIQUE INDEX idx_geo_countries_iso3 ON geo_countries(iso3);
CREATE INDEX idx_geo_countries_name ON geo_countries(name);

-- States/Provinces table
CREATE TABLE geo_states (
    id BIGSERIAL PRIMARY KEY,
    country_id BIGINT NOT NULL REFERENCES geo_countries(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    state_code TEXT NOT NULL,
    latitude NUMERIC(10, 7),
    longitude NUMERIC(10, 7),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for state lookups
CREATE INDEX idx_geo_states_country_id ON geo_states(country_id);
CREATE INDEX idx_geo_states_state_code ON geo_states(state_code);
CREATE INDEX idx_geo_states_name ON geo_states(name);
CREATE UNIQUE INDEX idx_geo_states_country_code ON geo_states(country_id, state_code);

-- Cities table with PostGIS spatial column
CREATE TABLE geo_cities (
    id BIGSERIAL PRIMARY KEY,
    state_id BIGINT NOT NULL REFERENCES geo_states(id) ON DELETE CASCADE,
    country_id BIGINT NOT NULL REFERENCES geo_countries(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    latitude NUMERIC(10, 7) NOT NULL,
    longitude NUMERIC(10, 7) NOT NULL,
    timezone TEXT,
    location geography(Point, 4326),  -- PostGIS spatial column for radius queries
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Spatial index (CRITICAL for P11 performance targets)
-- GIST index enables fast ST_DWithin queries for radius filtering
CREATE INDEX idx_geo_cities_location ON geo_cities USING GIST(location);

-- Standard indexes for common query patterns
CREATE INDEX idx_geo_cities_state_id ON geo_cities(state_id);
CREATE INDEX idx_geo_cities_country_id ON geo_cities(country_id);
CREATE INDEX idx_geo_cities_name ON geo_cities(name);

-- Composite index for state+city lookups (e.g., "Seattle, WA")
CREATE INDEX idx_geo_cities_state_name ON geo_cities(state_id, name);

-- Constraint: location column must be populated from latitude/longitude
-- This will be enforced by the import script which sets:
--   location = ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)

-- Example validation queries (run after data import):
--
-- 1. Verify PostGIS is enabled:
--    SELECT PostGIS_Version();
--
-- 2. Count cities by country:
--    SELECT c.name, COUNT(ci.id) as city_count
--    FROM geo_countries c
--    JOIN geo_cities ci ON ci.country_id = c.id
--    GROUP BY c.name
--    ORDER BY city_count DESC;
--
-- 3. Test radius query (50 miles from Seattle):
--    SELECT name,
--           ST_Distance(location, ST_MakePoint(-122.3321, 47.6062)::geography) / 1609.34 as distance_miles
--    FROM geo_cities
--    WHERE ST_DWithin(location, ST_MakePoint(-122.3321, 47.6062)::geography, 80467)
--    ORDER BY distance_miles
--    LIMIT 10;
--
-- 4. Benchmark query performance (should be < 100ms per P11):
--    EXPLAIN ANALYZE
--    SELECT COUNT(*)
--    FROM geo_cities
--    WHERE ST_DWithin(location, ST_MakePoint(-74.0060, 40.7128)::geography, 160934);  -- 100 miles from NYC

-- //@UNDO

DROP TABLE IF EXISTS geo_cities;
DROP TABLE IF EXISTS geo_states;
DROP TABLE IF EXISTS geo_countries;
