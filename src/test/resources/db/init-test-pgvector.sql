-- Test database initialization for PostgreSQL 17 + pgvector + PostGIS
-- This script is executed when the testcontainer starts

-- Enable pgvector extension (for semantic search embeddings)
-- NOTE: pgvector/pgvector:pg17 image has pgvector pre-installed
CREATE EXTENSION IF NOT EXISTS vector;

-- Enable PostGIS extension (for geographic queries - Feature I6.T2)
-- NOTE: postgis/postgis images have PostGIS pre-installed
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;
