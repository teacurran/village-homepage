# Database Migrations

This module contains the MyBatis Migrations project for the Village Homepage database schema. All schema changes, baseline tables, and seed data flows live here.

## 1. Overview

- **Tooling:** [MyBatis Migrations](https://mybatis.org/migrations/)
- **Plugin:** `org.mybatis.maven:migrations-maven-plugin:1.2.0`
- **Policy Coverage:**
  - **P6** – PostgreSQL 17 with PostGIS
  - **P7** – Versioned migrations with rollback capability
  - **P11** – Geographic dataset availability (via helper script)

The module is intentionally decoupled from the main Maven build so migrations can run without compiling Java sources. Every engineer should run migrations locally before starting the Quarkus dev server.

## 2. Directory Layout

```
migrations/
├── README.md
├── pom.xml                       # Standalone MyBatis Migrations POM
├── bootstrap/
│   └── init-postgis.sql          # Mounted by docker-compose to enable PostGIS
├── environments/                 # Connection profiles per environment
│   ├── development.properties
│   ├── test.properties
│   ├── beta.properties
│   └── production.properties
├── scripts/                      # Timestamped migration files + bootstrap stub
│   ├── 20250108000100_create_changelog_table.sql
│   ├── 20250108000200_create_feature_flags.sql
│   └── bootstrap.sql
└── seeds/                        # Optional manual data seeds
    └── feature_flags.sql
```

> **Note:** `scripts/` follows the canonical MyBatis layout. Do **not** rename files after they are applied or the changelog history will break.

## 3. Prerequisites

1. Copy `.env.example` to `.env` and customize values if needed.
2. Start the docker-compose stack: `docker-compose up -d`.
3. Ensure PostgreSQL is healthy: `docker-compose ps postgres`.
4. Confirm `psql` is installed (`psql --version`).

## 4. Running Migrations

All commands are executed from the `migrations/` directory.

```bash
cd migrations

# Load environment variables from the root .env (required for ${env.*} placeholders)
set -a && source ../.env && set +a

# Apply all pending migrations to the development database
mvn migration:up -Dmigration.env=development

# View current status
mvn migration:status -Dmigration.env=development

# Rollback the last migration (use with caution)
mvn migration:down -Dmigration.env=development
```

### Other Environments

| Environment  | Properties File        | Credentials Source                         |
|--------------|------------------------|--------------------------------------------|
| `test`       | `environments/test.properties`       | Jenkins/CI secrets                         |
| `beta`       | `environments/beta.properties`       | `VC_HP_BETA_DB_*` environment variables    |
| `production` | `environments/production.properties` | Secrets manager referenced in `../villagecompute` |

Set the environment via `-Dmigration.env=<name>`.

## 5. Geographic Dataset Loading

The [dr5hn/countries-states-cities](https://github.com/dr5hn/countries-states-cities-database) dataset powers marketplace geo search. Per **Policy P6**, Village Homepage imports **US + Canada only** (~40K cities) with PostGIS spatial indexing for efficient radius queries.

### 5.1 Import Process Overview

Geographic data import follows a **two-phase process**:

1. **Migration Phase** (`20250110001800_create_geo_tables.sql`):
   - Creates application schema tables: `geo_countries`, `geo_states`, `geo_cities`
   - Defines PostGIS `location` column (GEOGRAPHY Point) on `geo_cities`
   - Creates spatial indexes (GIST) and foreign key constraints

2. **Data Load Phase** (`import-geo-data-to-app-schema.sh`):
   - Downloads dr5hn dataset (153K+ global cities)
   - Loads into temporary native tables (`countries`, `states`, `cities`)
   - Transforms and copies to application tables with US + Canada filtering
   - Populates PostGIS `location` column from latitude/longitude
   - Cleans up temporary tables

### 5.2 Running the Import

**Prerequisites:**
- Run migrations first to create schema
- Start docker-compose services
- Ensure PostGIS is enabled (automatic via `bootstrap/init-postgis.sql`)

```bash
# Step 1: Apply migration to create schema
cd migrations
set -a && source ../.env && set +a
mvn migration:up -Dmigration.env=development
cd ..

# Step 2: Import data (idempotent, ~5-10 minutes)
./scripts/import-geo-data-to-app-schema.sh

# Optional: Force reload
./scripts/import-geo-data-to-app-schema.sh --force

# Debug: Keep temporary native tables after import
./scripts/import-geo-data-to-app-schema.sh --keep-temp-tables
```

### 5.3 Validating PostGIS Setup

After import, verify the data and spatial indexes:

```bash
# Connect to database
psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB"
```

**Verify PostGIS Version:**
```sql
SELECT PostGIS_Version();
-- Expected: PostGIS 3.4.x or higher
```

**Check Import Counts:**
```sql
SELECT
    (SELECT COUNT(*) FROM geo_countries) as countries,
    (SELECT COUNT(*) FROM geo_states) as states,
    (SELECT COUNT(*) FROM geo_cities) as cities,
    (SELECT COUNT(*) FROM geo_cities WHERE location IS NOT NULL) as cities_with_location;

-- Expected Results (approximate):
-- countries: 2 (US + Canada)
-- states: 65-70 (US states + Canadian provinces)
-- cities: 38,000-42,000 (US + Canada per Policy P6)
-- cities_with_location: Same as cities (100% coverage)
```

**Test Radius Query (50 miles from Seattle):**
```sql
SELECT name,
       ST_Distance(location, ST_MakePoint(-122.3321, 47.6062)::geography) / 1609.34 as distance_miles
FROM geo_cities
WHERE ST_DWithin(location, ST_MakePoint(-122.3321, 47.6062)::geography, 80467)  -- 50 miles in meters
ORDER BY distance_miles
LIMIT 10;

-- Should return cities like Tacoma, Bellevue, Everett, etc.
```

**Verify Spatial Index Usage:**
```sql
EXPLAIN (FORMAT TEXT)
SELECT COUNT(*) FROM geo_cities
WHERE ST_DWithin(location, ST_MakePoint(-74.0060, 40.7128)::geography, 160934);  -- 100 miles from NYC

-- Look for: "Index Scan using idx_geo_cities_location on geo_cities"
-- If you see "Seq Scan", the spatial index is not being used
```

**Benchmark Query Performance (Policy P11 target: p95 < 100ms):**
```sql
EXPLAIN ANALYZE
SELECT COUNT(*) FROM geo_cities
WHERE ST_DWithin(location, ST_MakePoint(-74.0060, 40.7128)::geography, 160934);

-- Check "Execution Time" in output - should be well under 100ms for 100-mile radius
```

### 5.4 Troubleshooting

**Import script reports "Application schema not found":**
- Ensure migration `20250110001800_create_geo_tables.sql` is applied first
- Check: `psql -c "SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'geo_%';"`

**Fewer cities than expected (~40K):**
- Verify US + Canada filtering is working: `SELECT iso2 FROM geo_countries;` should return only `US` and `CA`
- Check dr5hn dataset version - city counts may vary slightly between releases

**Spatial queries are slow (> 100ms):**
- Verify GIST index exists: `\d geo_cities` should show `idx_geo_cities_location GIST (location)`
- Run `ANALYZE geo_cities;` to update query planner statistics
- Ensure PostGIS extension is enabled: `SELECT PostGIS_Version();`

**PostGIS extension not found:**
- The docker container should enable PostGIS automatically via `bootstrap/init-postgis.sql`
- If using external DB: `psql -c 'CREATE EXTENSION IF NOT EXISTS postgis;'`

### 5.5 Example Use Cases

**Find cities within radius of a known city:**
```sql
SELECT c2.name, c2.state_id,
       ST_Distance(c1.location, c2.location) / 1609.34 as distance_miles
FROM geo_cities c1
CROSS JOIN geo_cities c2
WHERE c1.name = 'Portland' AND c1.state_id = (SELECT id FROM geo_states WHERE state_code = 'OR')
  AND ST_DWithin(c1.location, c2.location, 160934)  -- 100 miles
  AND c1.id != c2.id
ORDER BY distance_miles
LIMIT 20;
```

**Count cities by state (top 10):**
```sql
SELECT s.name, s.state_code, COUNT(c.id) as city_count
FROM geo_states s
JOIN geo_cities c ON c.state_id = s.id
GROUP BY s.name, s.state_code
ORDER BY city_count DESC
LIMIT 10;
```

**Find nearest city to arbitrary coordinates:**
```sql
SELECT name, state_id,
       ST_Distance(location, ST_MakePoint(-118.2437, 34.0522)::geography) / 1609.34 as distance_miles
FROM geo_cities
ORDER BY location <-> ST_MakePoint(-118.2437, 34.0522)::geography
LIMIT 1;

-- Should return Los Angeles, CA
```

See the script header (`scripts/import-geo-data-to-app-schema.sh`) and **Policy P6**, **P11** for additional context.

## 6. Feature Flags

The feature flag system is managed through database tables with enhanced schema supporting stable cohort evaluation, whitelist overrides, and audit logging (Policy P7 & P14 compliance).

### Schema Migrations

- **Bootstrap migration** (`20250108000200_create_feature_flags.sql`) - Initial minimal table
- **Enhanced migration** (`20250109000300_enhance_feature_flags.sql`) - Full I2.T2 schema with:
  - Stable cohort hashing support (whitelist JSONB, analytics toggle)
  - Audit table (`feature_flag_audit`) for mutation traceability
  - Partitioned evaluation logs (`feature_flag_evaluations`) for 90-day retention

### Manual Seeding

To re-seed feature flags manually:

```bash
psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -f migrations/seeds/feature_flags.sql
```

### Validation

Validate loaded flags:

```bash
psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "SELECT flag_key, enabled, rollout_percentage, analytics_enabled FROM feature_flags ORDER BY flag_key;"
```

Check audit trail:

```bash
psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "SELECT flag_key, action, actor_type, timestamp FROM feature_flag_audit ORDER BY timestamp DESC LIMIT 10;"
```

### Evaluation Logs Maintenance

Partitioned evaluation logs require monthly partition creation. To create partitions:

```sql
-- Example: Create partition for March 2025
CREATE TABLE feature_flag_evaluations_2025_03 PARTITION OF feature_flag_evaluations
    FOR VALUES FROM ('2025-03-01') TO ('2025-04-01');
```

To drop old partitions (90-day retention per Policy P14):

```bash
# Drop partitions older than 90 days
psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "DROP TABLE IF EXISTS feature_flag_evaluations_2024_10;"
```

**Note:** Partition management should be automated via scheduled job in production.

## 7. Creating New Migrations

```bash
cd migrations
set -a && source ../.env && set +a
mvn migration:new -Dmigration.env=development -Dmigration.description="add_user_preferences_table"
```

This generates a timestamped file inside `scripts/`. Edit the file with your DDL:

```sql
-- // add_user_preferences_table
-- Migration SQL goes here.

CREATE TABLE user_preferences (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    preferences JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- //@UNDO
DROP TABLE user_preferences;
```

Then apply it with `mvn migration:up -Dmigration.env=development`.

## 8. Troubleshooting

### Connection Issues

- Ensure docker-compose stack is running (`docker-compose ps`).
- Confirm environment variables are exported (`env | grep POSTGRES`).
- Inspect logs: `docker-compose logs postgres`.

### Checksum Mismatches

Do **not** edit previously applied migrations. Create a new migration instead. For local-only fixes, run `mvn migration:down` until the offending version is rolled back, fix the file, then `mvn migration:up`.

### PostGIS Extension Missing

The docker Postgres container runs `bootstrap/init-postgis.sql` automatically. If using an external DB, run:

```bash
psql -d "$POSTGRES_DB" -c 'CREATE EXTENSION IF NOT EXISTS postgis;'
```

## 9. Additional Resources

- [`docs/ops/dev-services.md`](../docs/ops/dev-services.md)
- [`scripts/load-geo-data.sh`](../scripts/load-geo-data.sh)
- [PostgreSQL 17 Docs](https://www.postgresql.org/docs/17/)
- [PostGIS Documentation](https://postgis.net/documentation/)
- [MyBatis Migrations Docs](https://mybatis.org/migrations/)
