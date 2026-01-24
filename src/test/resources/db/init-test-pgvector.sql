-- Test database initialization for PostgreSQL 17 + pgvector + PostGIS
-- This script is executed when the testcontainer starts

-- Enable pgvector extension (for semantic search embeddings)
-- NOTE: pgvector/pgvector:pg17 image has pgvector pre-installed
CREATE EXTENSION IF NOT EXISTS vector;

-- Enable PostGIS extension (for geographic queries - Feature I6.T2)
-- NOTE: pgvector/pgvector image does NOT include PostGIS by default
-- PostGIS must be installed manually via apt-get in the container
-- For tests, we skip PostGIS since geo queries are tested separately
-- CREATE EXTENSION IF NOT EXISTS postgis;
-- CREATE EXTENSION IF NOT EXISTS postgis_topology;

-- Create mock geometry type to satisfy Hibernate schema validation
-- This allows tests to run without PostGIS installed
CREATE DOMAIN geometry AS bytea;
