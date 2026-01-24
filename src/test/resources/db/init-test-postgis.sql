-- Test database initialization for PostgreSQL 17 + PostGIS
-- This script is executed when the testcontainer starts

-- Enable PostGIS extension (for geographic queries - Feature I6.T2)
-- NOTE: postgis/postgis:17-3.5-alpine image has PostGIS pre-installed
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;

-- pgvector is not available in PostGIS images
-- Tests requiring vector search should use a different test container or mock pgvector queries
