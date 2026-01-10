-- Description: Create link_clicks partitioned table for click event tracking with 90-day retention
-- Author: Code Machine
-- Date: 2026-01-11
-- Policy: F14.9 - 90-day retention for raw click logs with daily partitioning

-- Create partitioned link_clicks table
CREATE TABLE link_clicks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    click_date DATE NOT NULL,
    click_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    click_type TEXT NOT NULL CHECK (click_type IN (
        'directory_site_click',
        'directory_category_view',
        'directory_site_view',
        'directory_vote',
        'directory_search',
        'directory_bubbled_click',
        'marketplace_listing',
        'marketplace_view',
        'profile_view'
    )),
    target_id UUID,
    target_url TEXT,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    session_id TEXT,
    ip_address INET,
    user_agent TEXT,
    referer TEXT,
    category_id UUID,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
) PARTITION BY RANGE (click_date);

-- Create partitions for current month and next 3 months
-- This covers Q1 2026 (Jan-Apr)
CREATE TABLE link_clicks_2026_01 PARTITION OF link_clicks
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

CREATE TABLE link_clicks_2026_02 PARTITION OF link_clicks
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');

CREATE TABLE link_clicks_2026_03 PARTITION OF link_clicks
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');

CREATE TABLE link_clicks_2026_04 PARTITION OF link_clicks
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');

-- Indexes for efficient queries
-- Composite index for click type + date range queries
CREATE INDEX idx_link_clicks_type_date ON link_clicks (click_type, click_date);

-- Index for target lookups (site, listing, etc)
CREATE INDEX idx_link_clicks_target_date ON link_clicks (target_id, click_date) WHERE target_id IS NOT NULL;

-- Index for category analytics
CREATE INDEX idx_link_clicks_category_date ON link_clicks (category_id, click_date) WHERE category_id IS NOT NULL;

-- Index for user analytics
CREATE INDEX idx_link_clicks_user_date ON link_clicks (user_id, click_date) WHERE user_id IS NOT NULL;

-- Index for timestamp-based queries (rollup jobs)
CREATE INDEX idx_link_clicks_timestamp ON link_clicks (click_timestamp);

-- GIN index for JSONB metadata queries
CREATE INDEX idx_link_clicks_metadata ON link_clicks USING GIN (metadata);

-- Comments for documentation
COMMENT ON TABLE link_clicks IS 'Partitioned click tracking events with 90-day retention (Policy F14.9)';
COMMENT ON COLUMN link_clicks.click_date IS 'Date component for partitioning (must match click_timestamp date)';
COMMENT ON COLUMN link_clicks.click_timestamp IS 'Exact timestamp of click event';
COMMENT ON COLUMN link_clicks.click_type IS 'Type of click event (directory, marketplace, profile)';
COMMENT ON COLUMN link_clicks.target_id IS 'UUID of clicked entity (site, listing, user)';
COMMENT ON COLUMN link_clicks.target_url IS 'Destination URL';
COMMENT ON COLUMN link_clicks.user_id IS 'User who clicked (null for anonymous)';
COMMENT ON COLUMN link_clicks.session_id IS 'Session identifier for anonymous tracking';
COMMENT ON COLUMN link_clicks.category_id IS 'Directory category or marketplace category context';
COMMENT ON COLUMN link_clicks.metadata IS 'Additional context (is_bubbled, source_category_id, rank, score, search_query)';
