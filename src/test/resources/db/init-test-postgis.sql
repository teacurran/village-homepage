-- Test database initialization for PostgreSQL 17 + PostGIS + pgvector
-- This script is executed when the testcontainer starts

-- Enable PostGIS extension (for geographic queries - Feature I6.T2)
-- NOTE: postgis/postgis:17-3.5-alpine image has PostGIS pre-installed
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;

-- Enable pgvector extension (for vector embeddings)
-- NOTE: pgvector must be compiled and installed in the container
-- The postgres:17-alpine image doesn't have pgvector preinstalled
-- For tests, we use ankane/pgvector image which includes both PostgreSQL + pgvector
-- OR we can skip creating vector columns during schema generation for tests
-- TEMP WORKAROUND: Create a dummy "vector" type to satisfy Hibernate schema generation
-- This won't support actual vector operations, but allows tests to run
-- Real pgvector tests should use ankane/pgvector:pg17 image
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'vector') THEN
        CREATE DOMAIN vector AS TEXT;
    END IF;
END $$;

-- IMPORTANT: The spatial index is created AFTER Hibernate generates the schema
-- This is handled in the test setup via @PostConstruct or DDL callback
-- See GeoCityIntegrationTest.setupTestData() for index creation
