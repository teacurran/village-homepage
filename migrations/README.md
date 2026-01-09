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

The [dr5hn/countries-states-cities](https://github.com/dr5hn/countries-states-cities-database) dataset powers marketplace geo search.

```bash
# Validate setup without loading data
./scripts/load-geo-data.sh --dry-run

# Perform idempotent load (~5–10 minutes)
./scripts/load-geo-data.sh

# Force a full reload
./scripts/load-geo-data.sh --force
```

The script downloads the latest archive, extracts `sql/world.sql`, and streams it into PostgreSQL with spatial indexes. See the script header and `docs/ops/dev-services.md` for troubleshooting tips.

## 6. Feature Flags

The baseline `feature_flags` table and initial entries are created by `20250108000200_create_feature_flags.sql`. To re-seed manually:

```bash
psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -f migrations/seeds/feature_flags.sql
```

Validate loaded data:

```bash
psql -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "SELECT name, enabled, rollout_percentage FROM feature_flags ORDER BY name;"
```

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
