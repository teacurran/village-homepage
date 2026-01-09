--
-- Copyright (c) 2025 VillageCompute Inc.
-- All rights reserved.
--

-- // Create weather_cache table
-- Migration SQL that makes the change goes here.

CREATE TABLE weather_cache (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    location_key TEXT NOT NULL,
    provider TEXT NOT NULL,
    forecast_data JSONB NOT NULL,
    alerts JSONB,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Unique index for cache lookups
CREATE UNIQUE INDEX idx_weather_cache_location ON weather_cache(location_key);

-- Index for expiration cleanup queries
CREATE INDEX idx_weather_cache_expires ON weather_cache(expires_at);

-- Index for provider-based queries (metrics/debugging)
CREATE INDEX idx_weather_cache_provider ON weather_cache(provider);

-- Trigger to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_weather_cache_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_update_weather_cache_timestamp
    BEFORE UPDATE ON weather_cache
    FOR EACH ROW
    EXECUTE FUNCTION update_weather_cache_timestamp();

-- // @UNDO
-- SQL to undo the change goes here.

DROP TRIGGER IF EXISTS trigger_update_weather_cache_timestamp ON weather_cache;
DROP FUNCTION IF EXISTS update_weather_cache_timestamp();
DROP TABLE IF EXISTS weather_cache;
