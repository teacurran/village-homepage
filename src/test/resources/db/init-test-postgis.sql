-- Initialize PostGIS extension for Testcontainers PostgreSQL 17
-- This script is executed automatically when the test container starts
-- Ref: Foundation Blueprint Section 3.5 (Testing Strategy)

CREATE EXTENSION IF NOT EXISTS postgis;

-- Verify PostGIS version
SELECT PostGIS_Version();
