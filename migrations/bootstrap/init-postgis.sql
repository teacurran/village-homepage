-- PostgreSQL initialization script for PostGIS extension
-- This script runs automatically on first database creation via docker-entrypoint-initdb.d
-- Enables PostGIS for geospatial queries (marketplace radius filtering, location-based search)

-- Enable PostGIS extension (includes geometry, geography, and spatial index support)
CREATE EXTENSION IF NOT EXISTS postgis;

-- Enable PostGIS topology support (optional, for future advanced spatial features)
CREATE EXTENSION IF NOT EXISTS postgis_topology;

-- Verify extension versions
SELECT PostGIS_Version();
SELECT PostGIS_Full_Version();
