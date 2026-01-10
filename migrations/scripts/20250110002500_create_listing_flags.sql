-- Create listing_flags table for moderation and fraud detection (I4.T8)
-- Per Policy P3 and Feature F12.9

CREATE TABLE listing_flags (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id UUID NOT NULL REFERENCES marketplace_listings(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Flag details
    reason TEXT NOT NULL CHECK (reason IN (
        'spam',
        'prohibited_item',
        'fraud',
        'duplicate',
        'misleading',
        'inappropriate',
        'other'
    )),
    details TEXT,

    -- Moderation workflow
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'dismissed')),
    reviewed_by_user_id UUID REFERENCES users(id),
    reviewed_at TIMESTAMPTZ,
    review_notes TEXT,

    -- AI fraud score (optional, populated by FraudDetectionService)
    fraud_score DECIMAL(3,2),  -- 0.00 to 1.00
    fraud_reasons JSONB,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for performance
CREATE INDEX idx_listing_flags_listing_id ON listing_flags(listing_id);
CREATE INDEX idx_listing_flags_status ON listing_flags(status);
CREATE INDEX idx_listing_flags_created_at ON listing_flags(created_at DESC);
CREATE INDEX idx_listing_flags_user_id ON listing_flags(user_id);
CREATE INDEX idx_listing_flags_reviewed_by ON listing_flags(reviewed_by_user_id) WHERE reviewed_by_user_id IS NOT NULL;

-- Trigger to auto-flag listing at 3 pending flags
CREATE OR REPLACE FUNCTION check_flag_threshold() RETURNS TRIGGER AS $$
BEGIN
    -- Only check if this is a new pending flag
    IF NEW.status = 'pending' THEN
        -- Auto-flag listing if 3+ pending flags exist
        UPDATE marketplace_listings
        SET status = 'flagged'
        WHERE id = NEW.listing_id
          AND status = 'active'
          AND (SELECT COUNT(*) FROM listing_flags
               WHERE listing_id = NEW.listing_id AND status = 'pending') >= 3;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_flag_threshold
AFTER INSERT OR UPDATE ON listing_flags
FOR EACH ROW
EXECUTE FUNCTION check_flag_threshold();

-- Comment
COMMENT ON TABLE listing_flags IS 'User-submitted flags for marketplace listings requiring moderation review';
COMMENT ON COLUMN listing_flags.fraud_score IS 'AI-generated fraud probability (0.00-1.00) from FraudDetectionService';
COMMENT ON COLUMN listing_flags.fraud_reasons IS 'JSON array of AI-detected fraud indicators with prompt_version';
