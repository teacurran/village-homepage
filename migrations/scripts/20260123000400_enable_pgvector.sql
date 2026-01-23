-- Enable pgvector extension for semantic search (Feature I4.T5)
-- pgvector provides vector similarity search capabilities for AI-powered semantic search
-- using cosine similarity distance metrics

CREATE EXTENSION IF NOT EXISTS vector;

-- Verify extension is installed
SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
