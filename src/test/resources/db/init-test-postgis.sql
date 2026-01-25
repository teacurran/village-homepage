-- Test database initialization for PostgreSQL 17 + PostGIS + pgvector
-- This script is executed when the testcontainer starts
-- Using ivanlonel/postgis-with-extensions:17-3.5 image which has all extensions

-- Enable PostGIS extension (for geographic queries - Feature I6.T2)
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;

-- Enable pgvector extension (for semantic search embeddings - Feature I4.T1)
CREATE EXTENSION IF NOT EXISTS vector;
