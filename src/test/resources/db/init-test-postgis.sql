-- Test database initialization for PostgreSQL + PostGIS + pgvector
-- This script is executed when the testcontainer starts
-- Using joshuasundance/postgis_pgvector:latest which has both extensions

-- Enable PostGIS extension (for geographic queries - Feature I6.T2)
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;

-- Enable pgvector extension (for semantic search embeddings - Feature I4.T1)
CREATE EXTENSION IF NOT EXISTS vector;
