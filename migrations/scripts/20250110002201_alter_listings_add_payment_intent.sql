-- // alter_listings_add_payment_intent
-- Adds payment_intent_id column to marketplace_listings for tracking Stripe payments.
--
-- This migration enables correlation between listings and their Stripe Payment Intents,
-- required for webhook processing and refund lookups.
--
-- Policy References:
--   P3: Marketplace payment & fraud policy (payment tracking)
--   F12.8: Listing fees & monetization (posting fee enforcement)

ALTER TABLE marketplace_listings
ADD COLUMN payment_intent_id TEXT;

-- Index for webhook lookups
CREATE INDEX idx_marketplace_listings_payment_intent ON marketplace_listings(payment_intent_id)
    WHERE payment_intent_id IS NOT NULL;

-- Example queries:
--
-- 1. Find listing by Stripe Payment Intent ID (webhook processing):
--    SELECT * FROM marketplace_listings
--    WHERE payment_intent_id = 'pi_...';
--
-- 2. Find pending_payment listings without payment_intent_id (error detection):
--    SELECT * FROM marketplace_listings
--    WHERE status = 'pending_payment'
--      AND payment_intent_id IS NULL;

-- //@UNDO

DROP INDEX IF EXISTS idx_marketplace_listings_payment_intent;
ALTER TABLE marketplace_listings DROP COLUMN IF EXISTS payment_intent_id;
