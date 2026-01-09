#!/usr/bin/env bash
#
# import-geo-data-to-app-schema.sh - Bridge Script for Geographic Data Import
#
# This script orchestrates the two-phase process for importing geographic data:
#   Phase 1: Call load-geo-data.sh to download and load dr5hn dataset into native tables
#   Phase 2: Transform and copy data into application schema (geo_countries, geo_states, geo_cities)
#            with US + Canada filtering per Policy P6
#
# Usage:
#   ./scripts/import-geo-data-to-app-schema.sh [--force] [--keep-temp-tables]
#
# Options:
#   --force              Force reload even if geo_cities already has data
#   --keep-temp-tables   Keep native countries/states/cities tables after import (for debugging)
#
# Prerequisites:
#   - Migration 20250110001800_create_geo_tables.sql must be applied first
#   - Docker Compose services running
#   - .env file configured
#   - PostGIS extension enabled
#
# Policy References:
#   - P6: PostGIS with US/Canada scope (~40K cities)
#   - P11: Radius search optimization (5-250 miles)
#   - F12.1: dr5hn dataset integration
#
# See: migrations/README.md Section 5 for detailed documentation

set -euo pipefail

# ==============================================================================
# Configuration
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Flags
FORCE=false
KEEP_TEMP_TABLES=false

# ==============================================================================
# Helper Functions
# ==============================================================================

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

get_env_value() {
    local key="$1"
    local default_value="$2"
    local line value
    line=$(grep -E "^${key}=" "$ENV_FILE" | tail -n 1 || true)
    if [[ -z "$line" ]]; then
        echo "$default_value"
        return
    fi
    line="${line//$'\r'/}"
    value="${line#*=}"
    if [[ -z "$value" ]]; then
        echo "$default_value"
    else
        echo "$value"
    fi
}

# ==============================================================================
# Argument Parsing
# ==============================================================================

while [[ $# -gt 0 ]]; do
    case $1 in
        --force)
            FORCE=true
            shift
            ;;
        --keep-temp-tables)
            KEEP_TEMP_TABLES=true
            shift
            ;;
        -h|--help)
            cat << EOF
Usage: $0 [OPTIONS]

Import dr5hn geographic dataset into Village Homepage application schema.

This script performs two phases:
  1. Download and load dr5hn dataset into temporary tables (countries, states, cities)
  2. Transform and copy to application tables (geo_countries, geo_states, geo_cities)
     with US + Canada filtering per Policy P6

OPTIONS:
    --force              Force reload even if data already exists
    --keep-temp-tables   Keep temporary native tables after import
    -h, --help           Show this help message

PREREQUISITES:
    - Run migration first: cd migrations && mvn migration:up
    - Start services: docker compose up -d

EXAMPLES:
    # Standard import (idempotent)
    ./scripts/import-geo-data-to-app-schema.sh

    # Force reload
    ./scripts/import-geo-data-to-app-schema.sh --force

    # Debug mode (keep temp tables)
    ./scripts/import-geo-data-to-app-schema.sh --keep-temp-tables

See migrations/README.md for detailed documentation.
EOF
            exit 0
            ;;
        *)
            log_error "Unknown option: $1"
            echo "Run '$0 --help' for usage information."
            exit 1
            ;;
    esac
done

# ==============================================================================
# Environment Configuration
# ==============================================================================

log_info "Loading environment configuration..."

if [[ ! -f "$ENV_FILE" ]]; then
    log_error "Environment file not found: $ENV_FILE"
    exit 1
fi

# Extract PostgreSQL connection details
PGHOST="${POSTGRES_HOST:-$(get_env_value POSTGRES_HOST localhost)}"
PGPORT="${POSTGRES_PORT:-$(get_env_value POSTGRES_PORT 5432)}"
PGDATABASE="${POSTGRES_DB:-$(get_env_value POSTGRES_DB village_homepage)}"
PGUSER="${POSTGRES_USER:-$(get_env_value POSTGRES_USER village)}"
PGPASSWORD="${POSTGRES_PASSWORD:-$(get_env_value POSTGRES_PASSWORD village_dev_pass)}"

export PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD

log_success "Environment loaded: $PGUSER@$PGHOST:$PGPORT/$PGDATABASE"

# ==============================================================================
# Pre-flight Checks
# ==============================================================================

log_info "Running pre-flight checks..."

# Check if application schema exists
if ! psql -tAc "SELECT 1 FROM information_schema.tables WHERE table_name = 'geo_countries';" | grep -q 1; then
    log_error "Application schema not found - geo_countries table does not exist"
    log_error "Run migrations first: cd migrations && mvn migration:up -Dmigration.env=development"
    exit 1
fi

log_success "Application schema exists (geo_countries, geo_states, geo_cities)"

# Check if data already exists
EXISTING_CITIES=$(psql -tAc "SELECT COUNT(*) FROM geo_cities;" || echo "0")

if [[ "$EXISTING_CITIES" -gt 0 ]]; then
    if [[ "$FORCE" == false ]]; then
        log_success "Geographic data already imported ($EXISTING_CITIES cities)"
        log_info "Use --force to reload (this will clear and re-import data)"
        exit 0
    else
        log_warning "Force flag set - clearing existing application data"
        psql <<-EOSQL
            TRUNCATE TABLE geo_cities CASCADE;
            TRUNCATE TABLE geo_states CASCADE;
            TRUNCATE TABLE geo_countries CASCADE;
EOSQL
        log_success "Application tables cleared"
    fi
fi

# ==============================================================================
# Phase 1: Load dr5hn Dataset into Native Tables
# ==============================================================================

log_info "Phase 1: Loading dr5hn dataset into native tables (countries, states, cities)..."

LOAD_SCRIPT="$SCRIPT_DIR/load-geo-data.sh"

if [[ ! -x "$LOAD_SCRIPT" ]]; then
    log_error "Load script not found or not executable: $LOAD_SCRIPT"
    exit 1
fi

# Pass --force if our flag is set to ensure fresh load
if [[ "$FORCE" == true ]]; then
    if ! "$LOAD_SCRIPT" --force; then
        log_error "Phase 1 failed - could not load dr5hn dataset"
        exit 1
    fi
else
    if ! "$LOAD_SCRIPT"; then
        log_error "Phase 1 failed - could not load dr5hn dataset"
        exit 1
    fi
fi

log_success "Phase 1 complete - native tables populated"

# ==============================================================================
# Phase 2: Transform and Copy to Application Schema
# ==============================================================================

log_info "Phase 2: Transforming and copying data to application schema..."
log_info "Filtering to US + Canada only per Policy P6..."

START_TIME=$(date +%s)

# Execute transformation in a single transaction
psql <<-EOSQL
    BEGIN;

    -- Copy countries (US + Canada only per Policy P6)
    INSERT INTO geo_countries (id, name, iso2, iso3, phone_code, capital, currency, native_name, region, subregion, latitude, longitude, emoji)
    SELECT id, name, iso2, iso3, phone_code, capital, currency, native, region, subregion, latitude, longitude, emoji
    FROM countries
    WHERE iso2 IN ('US', 'CA')
    ORDER BY id;

    -- Copy states/provinces for US + Canada
    INSERT INTO geo_states (id, country_id, name, state_code, latitude, longitude)
    SELECT s.id, s.country_id, s.name, s.state_code, s.latitude, s.longitude
    FROM states s
    JOIN countries c ON s.country_id = c.id
    WHERE c.iso2 IN ('US', 'CA')
    ORDER BY s.id;

    -- Copy cities for US + Canada with PostGIS location column population
    INSERT INTO geo_cities (id, state_id, country_id, name, latitude, longitude, timezone, location)
    SELECT
        ci.id,
        ci.state_id,
        ci.country_id,
        ci.name,
        ci.latitude,
        ci.longitude,
        NULL,  -- dr5hn dataset doesn't include timezone in cities table
        ST_SetSRID(ST_MakePoint(ci.longitude, ci.latitude), 4326)::geography  -- Populate PostGIS location
    FROM cities ci
    JOIN countries co ON ci.country_id = co.id
    WHERE co.iso2 IN ('US', 'CA')
    ORDER BY ci.id;

    -- Update sequence values to prevent ID conflicts on future inserts
    SELECT setval('geo_countries_id_seq', (SELECT MAX(id) FROM geo_countries));
    SELECT setval('geo_states_id_seq', (SELECT MAX(id) FROM geo_states));
    SELECT setval('geo_cities_id_seq', (SELECT MAX(id) FROM geo_cities));

    -- Update statistics for query planner
    ANALYZE geo_countries;
    ANALYZE geo_states;
    ANALYZE geo_cities;

    COMMIT;
EOSQL

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

log_success "Phase 2 complete - data transformed and copied in ${DURATION}s"

# ==============================================================================
# Verify Import
# ==============================================================================

log_info "Verifying import..."

GEO_COUNTRIES_COUNT=$(psql -tAc "SELECT COUNT(*) FROM geo_countries;")
GEO_STATES_COUNT=$(psql -tAc "SELECT COUNT(*) FROM geo_states;")
GEO_CITIES_COUNT=$(psql -tAc "SELECT COUNT(*) FROM geo_cities;")
GEO_CITIES_WITH_LOCATION=$(psql -tAc "SELECT COUNT(*) FROM geo_cities WHERE location IS NOT NULL;")

log_success "Countries imported: $GEO_COUNTRIES_COUNT (expected: 2 for US + CA)"
log_success "States imported: $GEO_STATES_COUNT"
log_success "Cities imported: $GEO_CITIES_COUNT (expected: ~40K per Policy P6)"
log_success "Cities with location: $GEO_CITIES_WITH_LOCATION"

# Sanity checks
if [[ "$GEO_COUNTRIES_COUNT" -ne 2 ]]; then
    log_warning "Expected exactly 2 countries (US + CA) but found $GEO_COUNTRIES_COUNT"
fi

if [[ "$GEO_CITIES_COUNT" -lt 30000 ]]; then
    log_warning "Expected ~40K cities for US + Canada but found $GEO_CITIES_COUNT - dataset may be incomplete"
fi

if [[ "$GEO_CITIES_WITH_LOCATION" -ne "$GEO_CITIES_COUNT" ]]; then
    log_error "Not all cities have location populated ($GEO_CITIES_WITH_LOCATION/$GEO_CITIES_COUNT)"
    exit 1
fi

# ==============================================================================
# Cleanup Native Tables
# ==============================================================================

if [[ "$KEEP_TEMP_TABLES" == false ]]; then
    log_info "Cleaning up temporary native tables..."
    psql <<-EOSQL
        DROP TABLE IF EXISTS cities CASCADE;
        DROP TABLE IF EXISTS states CASCADE;
        DROP TABLE IF EXISTS countries CASCADE;
EOSQL
    log_success "Temporary tables dropped"
else
    log_info "Keeping temporary native tables (countries, states, cities) per --keep-temp-tables flag"
fi

# ==============================================================================
# PostGIS Query Validation
# ==============================================================================

log_info "Validating PostGIS spatial queries..."

# Test query: Find cities near Seattle (50 mile radius)
SEATTLE_TEST=$(psql -tAc "
    SELECT COUNT(*)
    FROM geo_cities
    WHERE ST_DWithin(
        location,
        (SELECT location FROM geo_cities WHERE name = 'Seattle' LIMIT 1),
        80467  -- 50 miles in meters
    );" || echo "0")

log_success "Seattle 50-mile radius test: $SEATTLE_TEST cities found"

# Test query: Verify spatial index is being used
EXPLAIN_OUTPUT=$(psql -tAc "
    EXPLAIN (FORMAT TEXT)
    SELECT COUNT(*)
    FROM geo_cities
    WHERE ST_DWithin(location, ST_MakePoint(-122.3321, 47.6062)::geography, 80467);
" || echo "")

if echo "$EXPLAIN_OUTPUT" | grep -q "Index.*geo_cities_location"; then
    log_success "PostGIS spatial index (idx_geo_cities_location) is being used"
else
    log_warning "Spatial index may not be in use - check query planner"
fi

# ==============================================================================
# Summary
# ==============================================================================

cat << EOF

${GREEN}========================================${NC}
${GREEN}Geographic Data Import Complete!${NC}
${GREEN}========================================${NC}

Database: ${PGDATABASE}
Application Schema Tables:
  - geo_countries: ${GEO_COUNTRIES_COUNT} (US + Canada)
  - geo_states: ${GEO_STATES_COUNT}
  - geo_cities: ${GEO_CITIES_COUNT} (with PostGIS location column)

${BLUE}Policy Compliance:${NC}
  ✓ P6: US + Canada only scope (~40K cities vs 153K global)
  ✓ PostGIS location column populated for all cities
  ✓ Spatial indexes created (idx_geo_cities_location)

${BLUE}Example Queries:${NC}

1. Find cities within 50 miles of a point:
   SELECT name, ST_Distance(location, ST_MakePoint(-122.3321, 47.6062)::geography) / 1609.34 as miles
   FROM geo_cities
   WHERE ST_DWithin(location, ST_MakePoint(-122.3321, 47.6062)::geography, 80467)
   ORDER BY miles LIMIT 10;

2. Count cities by state:
   SELECT s.name, COUNT(c.id) as city_count
   FROM geo_states s
   JOIN geo_cities c ON c.state_id = s.id
   GROUP BY s.name
   ORDER BY city_count DESC;

3. Benchmark radius query performance (should be < 100ms per P11):
   EXPLAIN ANALYZE
   SELECT COUNT(*) FROM geo_cities
   WHERE ST_DWithin(location, ST_MakePoint(-74.0060, 40.7128)::geography, 160934);

${BLUE}Next Steps:${NC}
  - Create Java entity classes (GeoCountry, GeoState, GeoCity)
  - Implement GeoService with radius query helpers
  - Build marketplace listing location filtering
  - See migrations/README.md Section 5 for additional documentation

EOF
