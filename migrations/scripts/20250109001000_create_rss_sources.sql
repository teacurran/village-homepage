-- // create_rss_sources
-- Creates the rss_sources table for RSS feed registry supporting system-managed and user-custom feeds per Policy P1.
-- System feeds are curated by VillageCompute (TechCrunch, BBC, etc.), user-custom feeds are user-added.
-- Includes health monitoring (last_fetch_status, error tracking) and configurable refresh intervals (15min to daily).
-- User-created feeds are included in GDPR data export per Policy P1.

CREATE TABLE rss_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    category TEXT,
    is_system BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    refresh_interval_minutes INT NOT NULL DEFAULT 60 CHECK (refresh_interval_minutes BETWEEN 15 AND 1440),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    last_fetched_at TIMESTAMPTZ,
    error_count INT NOT NULL DEFAULT 0,
    last_error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for common query patterns
CREATE UNIQUE INDEX idx_rss_sources_url ON rss_sources(url);
CREATE INDEX idx_rss_sources_source_type_active ON rss_sources(is_system, is_active, refresh_interval_minutes);
CREATE INDEX idx_rss_sources_next_fetch ON rss_sources(last_fetched_at, refresh_interval_minutes) WHERE is_active = TRUE;
CREATE INDEX idx_rss_sources_user_id ON rss_sources(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_rss_sources_category ON rss_sources(category) WHERE category IS NOT NULL;

-- Constraint: system feeds must not have user_id, user feeds must have user_id
ALTER TABLE rss_sources ADD CONSTRAINT check_source_ownership
    CHECK (
        (is_system = TRUE AND user_id IS NULL) OR
        (is_system = FALSE AND user_id IS NOT NULL)
    );

-- //@UNDO

DROP TABLE IF EXISTS rss_sources;
