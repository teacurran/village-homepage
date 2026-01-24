-- Test database initialization for PostgreSQL 17 + pgvector
-- This script is executed when the testcontainer starts

-- Enable pgvector extension (for semantic search embeddings)
-- NOTE: pgvector/pgvector:pg17 image has pgvector pre-installed
CREATE EXTENSION IF NOT EXISTS vector;

-- NOTE: PostGIS is not available in pgvector/pgvector:pg17 image
-- Tests requiring geographic queries should use a different test resource or mock the queries
