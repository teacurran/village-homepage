-- // create_payment_refunds
-- Creates payment_refunds table for refund tracking and chargeback management per Policy P3.
--
-- This migration defines the schema for payment refund workflow:
-- - Automatic refunds: technical failures, moderation rejections within 24h
-- - Manual refunds: user requests after 24h (requires admin approval)
-- - Chargeback tracking: dispute evidence and user ban logic (2+ chargebacks)
-- - Status tracking: pending → approved/rejected → processed
--
-- Policy References:
--   P3: Marketplace payment & fraud policy (refund window, chargeback handling)
--   F12.8: Listing fees & monetization (refund eligibility)

CREATE TABLE payment_refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Stripe identifiers
    stripe_payment_intent_id TEXT NOT NULL,
    stripe_refund_id TEXT,  -- NULL until refund is processed via Stripe API

    -- Related entities
    listing_id UUID REFERENCES marketplace_listings(id) ON DELETE SET NULL,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Refund details
    amount_cents BIGINT NOT NULL,
    reason TEXT NOT NULL CHECK (
        reason IN ('technical_failure', 'moderation_rejection', 'user_request', 'chargeback')
    ),
    status TEXT NOT NULL DEFAULT 'pending' CHECK (
        status IN ('pending', 'approved', 'rejected', 'processed')
    ),

    -- Admin review tracking
    reviewed_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    notes TEXT,

    -- Audit timestamps
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Ensure amount is positive
    CONSTRAINT chk_payment_refunds_amount CHECK (amount_cents > 0),

    -- Ensure reviewed fields are set together
    CONSTRAINT chk_payment_refunds_review CHECK (
        (reviewed_by_user_id IS NULL AND reviewed_at IS NULL) OR
        (reviewed_by_user_id IS NOT NULL AND reviewed_at IS NOT NULL)
    )
);

-- Index on user_id for finding user's refund history
CREATE INDEX idx_payment_refunds_user_id ON payment_refunds(user_id);

-- Index on listing_id for finding refunds by listing
CREATE INDEX idx_payment_refunds_listing_id ON payment_refunds(listing_id);

-- Index on stripe_payment_intent_id for webhook lookups
CREATE INDEX idx_payment_refunds_payment_intent ON payment_refunds(stripe_payment_intent_id);

-- Index on status for admin queue queries
CREATE INDEX idx_payment_refunds_status ON payment_refunds(status);

-- Composite index for finding pending manual reviews
CREATE INDEX idx_payment_refunds_pending ON payment_refunds(status, created_at)
    WHERE status = 'pending';

-- Index on reason for chargeback tracking
CREATE INDEX idx_payment_refunds_reason ON payment_refunds(reason);

-- Index on created_at for refund history queries
CREATE INDEX idx_payment_refunds_created_at ON payment_refunds(created_at DESC);

-- Example queries:
--
-- 1. Find pending refunds for admin review:
--    SELECT * FROM payment_refunds
--    WHERE status = 'pending'
--      AND reason = 'user_request'
--    ORDER BY created_at ASC;
--
-- 2. Count chargebacks for a user (for ban logic):
--    SELECT COUNT(*) FROM payment_refunds
--    WHERE user_id = '...'
--      AND reason = 'chargeback';
--
-- 3. Find refund by Stripe Payment Intent ID (webhook lookup):
--    SELECT * FROM payment_refunds
--    WHERE stripe_payment_intent_id = 'pi_...';
--
-- 4. Check if listing has existing refund (idempotency):
--    SELECT * FROM payment_refunds
--    WHERE listing_id = '...'
--      AND status IN ('approved', 'processed');
--
-- 5. Find all refunds processed in last 30 days (reporting):
--    SELECT * FROM payment_refunds
--    WHERE status = 'processed'
--      AND updated_at >= NOW() - INTERVAL '30 days'
--    ORDER BY updated_at DESC;

-- //@UNDO

DROP TABLE IF EXISTS payment_refunds;
