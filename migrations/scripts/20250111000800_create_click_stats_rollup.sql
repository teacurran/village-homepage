-- Description: Create rollup tables for aggregated click analytics
-- Author: Code Machine
-- Date: 2026-01-11
-- Policy: F14.9 - Indefinite retention for aggregated stats

-- Daily rollup by click type and category
CREATE TABLE click_stats_daily (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stat_date DATE NOT NULL,
    click_type TEXT NOT NULL,
    category_id UUID,
    category_name TEXT,
    total_clicks BIGINT NOT NULL DEFAULT 0,
    unique_users BIGINT NOT NULL DEFAULT 0,
    unique_sessions BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (stat_date, click_type, category_id)
);

-- Daily rollup by item (site, listing, etc)
CREATE TABLE click_stats_daily_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stat_date DATE NOT NULL,
    click_type TEXT NOT NULL,
    target_id UUID NOT NULL,
    total_clicks BIGINT NOT NULL DEFAULT 0,
    unique_users BIGINT NOT NULL DEFAULT 0,
    unique_sessions BIGINT NOT NULL DEFAULT 0,
    avg_rank NUMERIC(10,2),
    avg_score NUMERIC(10,2),
    bubbled_clicks BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (stat_date, click_type, target_id)
);

-- Indexes for efficient queries
CREATE INDEX idx_click_stats_daily_date ON click_stats_daily (stat_date DESC);
CREATE INDEX idx_click_stats_daily_type ON click_stats_daily (click_type);
CREATE INDEX idx_click_stats_daily_category ON click_stats_daily (category_id) WHERE category_id IS NOT NULL;

CREATE INDEX idx_click_stats_daily_items_date ON click_stats_daily_items (stat_date DESC);
CREATE INDEX idx_click_stats_daily_items_type ON click_stats_daily_items (click_type);
CREATE INDEX idx_click_stats_daily_items_target ON click_stats_daily_items (target_id);

-- Composite indexes for dashboard queries
CREATE INDEX idx_click_stats_daily_type_date ON click_stats_daily (click_type, stat_date DESC);
CREATE INDEX idx_click_stats_daily_items_type_date ON click_stats_daily_items (click_type, stat_date DESC);

-- Comments
COMMENT ON TABLE click_stats_daily IS 'Daily aggregated click statistics by type and category';
COMMENT ON TABLE click_stats_daily_items IS 'Daily aggregated click statistics by individual items';
COMMENT ON COLUMN click_stats_daily_items.avg_rank IS 'Average rank in category when clicked';
COMMENT ON COLUMN click_stats_daily_items.avg_score IS 'Average score when clicked';
COMMENT ON COLUMN click_stats_daily_items.bubbled_clicks IS 'Clicks on bubbled sites (from child categories)';
