#!/usr/bin/env bash
#
# load-geo-data.sh - Geographic Dataset Loader for Village Homepage
#
# Loads the dr5hn/countries-states-cities-database (153K+ cities) into PostgreSQL
# for marketplace location-based filtering and radius searches.
#
# Usage:
#   ./scripts/load-geo-data.sh [--dry-run] [--force]
#
# Options:
#   --dry-run    Validate dependencies and connection without loading data
#   --force      Drop and recreate tables if they already exist
#
# Prerequisites:
#   - Docker Compose services running (`docker compose up -d`)
#   - PostgreSQL healthy and PostGIS extension enabled
#   - .env file configured with database credentials
#   - psql, curl, unzip, and Docker CLI installed locally
#
# Policy References:
#   - P6: PostgreSQL 17 with PostGIS for spatial queries
#   - P11: Geographic dataset requirements for marketplace
#
# See: migrations/README.md for detailed documentation

set -euo pipefail

# ==============================================================================
# Configuration
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
TEMP_DIR="/tmp/village-geo-data-$$"
DATASET_URL="https://github.com/dr5hn/countries-states-cities-database/archive/refs/heads/master.zip"
DATASET_ARCHIVE="$TEMP_DIR/countries-states-cities-database.zip"
DATASET_EXTRACT_DIR="$TEMP_DIR/extracted"
WORLD_SQL_FILE=""

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Flags
DRY_RUN=false
FORCE=false

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

cleanup() {
    if [[ -d "$TEMP_DIR" ]]; then
        log_info "Cleaning up temporary files..."
        rm -rf "$TEMP_DIR"
    fi
}

trap cleanup EXIT

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
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --force)
            FORCE=true
            shift
            ;;
        -h|--help)
            cat << EOF
Usage: $0 [OPTIONS]

Load dr5hn/countries-states-cities-database into PostgreSQL for marketplace location filtering.

OPTIONS:
    --dry-run    Validate dependencies and connection without loading data
    --force      Drop and recreate tables if they already exist
    -h, --help   Show this help message

EXAMPLES:
    # Standard load (idempotent, skips if already loaded)
    ./scripts/load-geo-data.sh

    # Validate setup without loading
    ./scripts/load-geo-data.sh --dry-run

    # Force reload (caution: drops existing data)
    ./scripts/load-geo-data.sh --force

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
# Dependency Validation
# ==============================================================================

log_info "Validating dependencies..."

# Check for required commands
REQUIRED_COMMANDS=("psql" "curl" "unzip" "docker")
for cmd in "${REQUIRED_COMMANDS[@]}"; do
    if ! command -v "$cmd" &> /dev/null; then
        log_error "Required command not found: $cmd"
        log_error "Please install $cmd and try again."
        exit 1
    fi
done

log_success "All required commands found"

# Determine docker compose command syntax
if command -v docker-compose &> /dev/null; then
    DOCKER_COMPOSE_CMD=(docker-compose)
elif docker compose version &> /dev/null; then
    DOCKER_COMPOSE_CMD=(docker compose)
else
    log_error "Neither docker-compose nor 'docker compose' is available"
    exit 1
fi

# ==============================================================================
# Environment Configuration
# ==============================================================================

log_info "Loading environment configuration..."

if [[ ! -f "$ENV_FILE" ]]; then
    log_error "Environment file not found: $ENV_FILE"
    log_error "Please copy .env.example to .env and configure your database credentials."
    exit 1
fi

# Extract PostgreSQL connection details (fall back to .env defaults)
PGHOST="${POSTGRES_HOST:-$(get_env_value POSTGRES_HOST localhost)}"
PGPORT="${POSTGRES_PORT:-$(get_env_value POSTGRES_PORT 5432)}"
PGDATABASE="${POSTGRES_DB:-$(get_env_value POSTGRES_DB village_homepage)}"
PGUSER="${POSTGRES_USER:-$(get_env_value POSTGRES_USER village)}"
PGPASSWORD="${POSTGRES_PASSWORD:-$(get_env_value POSTGRES_PASSWORD village_dev_pass)}"

export PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD

log_success "Environment loaded: $PGUSER@$PGHOST:$PGPORT/$PGDATABASE"

# ==============================================================================
# Dry Run Exit
# ==============================================================================

if [[ "$DRY_RUN" == true ]]; then
    log_success "Dry run complete - .env loaded and dependencies available"
    log_info "Start services with: ${DOCKER_COMPOSE_CMD[*]} up -d, then rerun without --dry-run to import data."
    exit 0
fi

# ==============================================================================
# Docker Compose Service Health Check
# ==============================================================================

log_info "Checking Docker Compose services..."

if ! "${DOCKER_COMPOSE_CMD[@]}" -f "$PROJECT_ROOT/docker-compose.yml" ps postgres | grep -q "Up"; then
    log_error "PostgreSQL container is not running"
    log_error "Start services with: ${DOCKER_COMPOSE_CMD[*]} up -d"
    exit 1
fi

# Wait for PostgreSQL to be ready
log_info "Waiting for PostgreSQL to be ready..."
for i in {1..30}; do
    if psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -c "SELECT 1;" &>/dev/null; then
        log_success "PostgreSQL is ready"
        break
    fi
    if [[ $i -eq 30 ]]; then
        log_error "PostgreSQL is not responding after 30 attempts"
        log_error "Check logs with: ${DOCKER_COMPOSE_CMD[*]} logs postgres"
        exit 1
    fi
    sleep 1
done

# ==============================================================================
# PostGIS Extension Verification
# ==============================================================================

log_info "Verifying PostGIS extension..."

POSTGIS_VERSION=$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -tAc "SELECT PostGIS_Version();" 2>/dev/null || echo "")

if [[ -z "$POSTGIS_VERSION" ]]; then
    log_error "PostGIS extension is not enabled"
    log_error "Enable with: psql -c 'CREATE EXTENSION postgis;'"
    exit 1
fi

log_success "PostGIS version: $POSTGIS_VERSION"

# ==============================================================================
# Check Existing Data
# ==============================================================================

log_info "Checking for existing geographic data..."

CITIES_COUNT=$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -tAc "SELECT COUNT(*) FROM cities;" 2>/dev/null || echo "0")

if [[ "$CITIES_COUNT" -gt 0 ]]; then
    if [[ "$FORCE" == false ]]; then
        log_success "Geographic data already loaded ($CITIES_COUNT cities)"
        log_info "Use --force to reload (this will drop and recreate tables)"
        exit 0
    else
        log_warning "Force flag set - dropping existing geographic tables"
        psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" <<-EOSQL
            DROP TABLE IF EXISTS cities CASCADE;
            DROP TABLE IF EXISTS states CASCADE;
            DROP TABLE IF EXISTS countries CASCADE;
EOSQL
        log_success "Existing tables dropped"
    fi
fi

# ==============================================================================
# Download Dataset
# ==============================================================================

log_info "Downloading geographic dataset archive..."
mkdir -p "$TEMP_DIR" "$DATASET_EXTRACT_DIR"

if ! curl -L --fail --progress-bar "$DATASET_URL" -o "$DATASET_ARCHIVE"; then
    log_error "Failed to download dataset from $DATASET_URL"
    exit 1
fi

ARCHIVE_SIZE=$(du -h "$DATASET_ARCHIVE" | cut -f1)
log_success "Archive downloaded: $ARCHIVE_SIZE"

log_info "Extracting dataset..."
if ! unzip -q "$DATASET_ARCHIVE" -d "$DATASET_EXTRACT_DIR"; then
    log_error "Unable to extract dataset archive"
    exit 1
fi

WORLD_SQL_FILE=$(find "$DATASET_EXTRACT_DIR" -name world.sql -print -quit)

if [[ -z "$WORLD_SQL_FILE" || ! -f "$WORLD_SQL_FILE" ]]; then
    log_error "world.sql not found inside extracted dataset"
    exit 1
fi

SQL_SIZE=$(du -h "$WORLD_SQL_FILE" | cut -f1)
log_success "Using SQL payload: $SQL_SIZE from $WORLD_SQL_FILE"

# ==============================================================================
# Load Dataset
# ==============================================================================

log_info "Loading geographic data into PostgreSQL..."
log_info "This may take 5-10 minutes depending on your system..."

# Time the load operation
START_TIME=$(date +%s)

if psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -f "$WORLD_SQL_FILE" > "$TEMP_DIR/load.log" 2>&1; then
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    log_success "Data loaded successfully in ${DURATION}s"
else
    log_error "Failed to load dataset"
    log_error "Check logs at: $TEMP_DIR/load.log"
    cat "$TEMP_DIR/load.log"
    exit 1
fi

# ==============================================================================
# Verify Load
# ==============================================================================

log_info "Verifying data load..."

COUNTRIES_COUNT=$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -tAc "SELECT COUNT(*) FROM countries;")
STATES_COUNT=$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -tAc "SELECT COUNT(*) FROM states;")
CITIES_COUNT=$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -tAc "SELECT COUNT(*) FROM cities;")

log_success "Countries loaded: $COUNTRIES_COUNT"
log_success "States loaded: $STATES_COUNT"
log_success "Cities loaded: $CITIES_COUNT"

# Sanity check
if [[ "$CITIES_COUNT" -lt 100000 ]]; then
    log_warning "Expected 150K+ cities but found $CITIES_COUNT - dataset may be incomplete"
fi

# ==============================================================================
# Create Spatial Indexes
# ==============================================================================

log_info "Creating spatial indexes for efficient radius queries..."

psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" <<-EOSQL
    -- Ensure latitude/longitude columns exist and are indexed
    CREATE INDEX IF NOT EXISTS idx_cities_coordinates ON cities USING GIST (
        ST_MakePoint(longitude, latitude)::geography
    );

    CREATE INDEX IF NOT EXISTS idx_cities_country_id ON cities(country_id);
    CREATE INDEX IF NOT EXISTS idx_cities_state_id ON cities(state_id);
    CREATE INDEX IF NOT EXISTS idx_states_country_id ON states(country_id);

    ANALYZE countries;
    ANALYZE states;
    ANALYZE cities;
EOSQL

log_success "Spatial indexes created and statistics updated"

# ==============================================================================
# Summary
# ==============================================================================

cat << EOF

${GREEN}========================================${NC}
${GREEN}Geographic Data Load Complete!${NC}
${GREEN}========================================${NC}

Database: ${PGDATABASE}
Countries: ${COUNTRIES_COUNT}
States: ${STATES_COUNT}
Cities: ${CITIES_COUNT}

The marketplace can now perform location-based filtering
with radius searches using PostGIS spatial queries.

${BLUE}Next Steps:${NC}
1. Run migrations to create application schema:
   cd migrations
   set -a && source ../.env && set +a
   mvn migration:up -Dmigration.env=development
   cd ..

2. Verify Quarkus can connect to the database:
   ./mvnw quarkus:dev

3. See migrations/README.md for additional setup instructions

EOF
