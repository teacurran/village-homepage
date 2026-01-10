-- Add flag_count column to marketplace_listings for moderation (I4.T8)
-- Per Feature F12.9: Track number of pending flags for auto-hide threshold

ALTER TABLE marketplace_listings
ADD COLUMN flag_count BIGINT NOT NULL DEFAULT 0;

-- Index for filtering flagged listings in moderation queue
CREATE INDEX idx_marketplace_listings_flag_count
    ON marketplace_listings(flag_count)
    WHERE flag_count > 0;

-- Comment
COMMENT ON COLUMN marketplace_listings.flag_count IS 'Number of pending flags (auto-hide at 3 per F12.9)';
