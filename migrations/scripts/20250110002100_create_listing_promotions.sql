-- // create_listing_promotions
-- Creates listing_promotions table for marketplace monetization per Feature F12.8 and Policy P3.
--
-- This migration defines the schema for tracking paid promotional features:
-- - Featured listings: $5 for 7 days, highlighted in search results
-- - Bump listings: $2 per bump, resets listing to top of chronological order (max 1/24h)
-- - Stripe Payment Intent tracking for payment processing
-- - Timestamp ranges for promotion activation and expiration
--
-- Policy References:
--   F12.8: Listing fees & monetization (posting fees + promotions)
--   P3: Marketplace payment & fraud policy (Stripe integration, refund window)

CREATE TABLE listing_promotions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id UUID NOT NULL REFERENCES marketplace_listings(id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('featured', 'bump')),
    stripe_payment_intent_id TEXT NOT NULL,
    amount_cents BIGINT NOT NULL,

    -- Promotion timeframe
    starts_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,  -- NULL for bump (instant effect), NOT NULL for featured (7 days)

    -- Audit timestamps
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Ensure amount is positive
    CONSTRAINT chk_listing_promotions_amount CHECK (amount_cents > 0),

    -- Ensure featured promotions have expiration, bumps do not
    CONSTRAINT chk_listing_promotions_expiration CHECK (
        (type = 'featured' AND expires_at IS NOT NULL) OR
        (type = 'bump' AND expires_at IS NULL)
    )
);

-- Index on listing_id for finding promotions by listing
CREATE INDEX idx_listing_promotions_listing_id ON listing_promotions(listing_id);

-- Composite index for finding active featured promotions
CREATE INDEX idx_listing_promotions_listing_type ON listing_promotions(listing_id, type);

-- Index on expires_at for expiration job queries
CREATE INDEX idx_listing_promotions_expires_at ON listing_promotions(expires_at)
    WHERE expires_at IS NOT NULL;

-- Index on stripe_payment_intent_id for webhook idempotency
CREATE UNIQUE INDEX idx_listing_promotions_payment_intent ON listing_promotions(stripe_payment_intent_id);

-- Example queries:
--
-- 1. Find active featured promotions for a listing:
--    SELECT * FROM listing_promotions
--    WHERE listing_id = '...'
--      AND type = 'featured'
--      AND starts_at <= NOW()
--      AND expires_at > NOW();
--
-- 2. Find expired featured promotions (for cleanup job):
--    SELECT * FROM listing_promotions
--    WHERE type = 'featured'
--      AND expires_at <= NOW();
--
-- 3. Check if listing was bumped in last 24 hours:
--    SELECT COUNT(*) FROM listing_promotions
--    WHERE listing_id = '...'
--      AND type = 'bump'
--      AND created_at >= NOW() - INTERVAL '24 hours';
--
-- 4. Find all promotions by Stripe Payment Intent ID (webhook lookup):
--    SELECT * FROM listing_promotions
--    WHERE stripe_payment_intent_id = 'pi_...';

-- //@UNDO

DROP TABLE IF EXISTS listing_promotions;
