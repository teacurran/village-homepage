-- Test database initialization for PostgreSQL 17 + PostGIS
-- This script is executed when the testcontainer starts

-- Enable PostGIS extension (for geographic queries - Feature I6.T2)
-- NOTE: postgis/postgis:17-3.5 image has PostGIS pre-installed
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;

-- Enable pgvector extension if available (for semantic search embeddings)
-- NOTE: pgvector is NOT pre-installed on postgis/postgis image
-- We skip it for tests since we don't test semantic search in integration tests
-- CREATE EXTENSION IF NOT EXISTS vector;
