-- // create_marketplace_listings
-- Creates marketplace listings table for classifieds platform per Features F12.4-F12.7.
--
-- This migration defines the schema for marketplace_listings supporting:
-- - User-created classified ads with title, description, price, category, location
-- - Status lifecycle: draft → pending_payment → active → expired
-- - 30-day expiration with reminder emails 2-3 days before
-- - Contact info stored as JSONB (email, phone, masked_email for relay)
-- - Soft-delete for audit trail (status = 'removed')
-- - PostGIS location reference via geo_cities for radius filtering
--
-- Policy References:
--   F12.4: Listing creation and draft functionality
--   F12.5: Image upload support (up to 12 images per listing)
--   F12.6: Contact system with masked email relay
--   F12.7: Expiration schedule (30 days from activation)
--   P1: GDPR compliance (user owns their listings, CASCADE delete on user deletion)
--   P3: Stripe payment integration (pending_payment status)
--   P6: PostGIS location reference (geo_city_id for radius filtering)
--   P14: Data retention (soft-delete to 'removed' status before purge)

CREATE TABLE marketplace_listings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES marketplace_categories(id) ON DELETE RESTRICT,
    geo_city_id BIGINT REFERENCES geo_cities(id) ON DELETE SET NULL,

    -- Core content fields
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    price DECIMAL(10,2),

    -- Contact info stored as JSONB: {"email": "...", "phone": "...", "masked_email": "listing-uuid@villagecompute.com"}
    contact_info JSONB NOT NULL,

    -- Status lifecycle: draft → pending_payment → active → expired | removed | flagged
    status TEXT NOT NULL DEFAULT 'draft',

    -- Expiration tracking (set when status → active, auto-expire after 30 days)
    expires_at TIMESTAMPTZ,

    -- Promotion tracking (for future bump feature)
    last_bumped_at TIMESTAMPTZ,

    -- Reminder email tracking (sent 2-3 days before expiration)
    reminder_sent BOOLEAN NOT NULL DEFAULT false,

    -- Audit timestamps
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Enforce status enum values
    CONSTRAINT chk_marketplace_listings_status CHECK (
        status IN ('draft', 'pending_payment', 'active', 'expired', 'removed', 'flagged')
    ),

    -- Price must be non-negative when set
    CONSTRAINT chk_marketplace_listings_price CHECK (
        price IS NULL OR price >= 0
    )
);

-- Index on user_id for finding user's own listings
CREATE INDEX idx_marketplace_listings_user_id ON marketplace_listings(user_id);

-- Index on category_id for category browsing
CREATE INDEX idx_marketplace_listings_category_id ON marketplace_listings(category_id);

-- Index on geo_city_id for location filtering
CREATE INDEX idx_marketplace_listings_geo_city_id ON marketplace_listings(geo_city_id);

-- Index on status for filtering by lifecycle state
CREATE INDEX idx_marketplace_listings_status ON marketplace_listings(status);

-- Index on expires_at for expiration job queries
CREATE INDEX idx_marketplace_listings_expires_at ON marketplace_listings(expires_at);

-- Composite index for expiration queries (find expired or expiring soon)
-- This partial index only includes active listings to optimize the daily expiration job
CREATE INDEX idx_marketplace_listings_status_expires_at
    ON marketplace_listings(status, expires_at)
    WHERE status = 'active';

-- Composite index for reminder queries (find listings expiring soon that haven't been reminded)
CREATE INDEX idx_marketplace_listings_reminder
    ON marketplace_listings(status, expires_at, reminder_sent)
    WHERE status = 'active' AND reminder_sent = false;

-- Index on created_at for sorting recent listings
CREATE INDEX idx_marketplace_listings_created_at ON marketplace_listings(created_at DESC);

-- Example queries:
--
-- 1. Find user's own listings (all statuses):
--    SELECT * FROM marketplace_listings
--    WHERE user_id = '...'
--    ORDER BY created_at DESC;
--
-- 2. Find active listings in a category:
--    SELECT * FROM marketplace_listings
--    WHERE category_id = '...' AND status = 'active'
--    ORDER BY created_at DESC;
--
-- 3. Find expired listings (for expiration job):
--    SELECT * FROM marketplace_listings
--    WHERE status = 'active' AND expires_at <= NOW();
--
-- 4. Find listings expiring soon for reminder (2-3 days before):
--    SELECT * FROM marketplace_listings
--    WHERE status = 'active'
--      AND expires_at > NOW()
--      AND expires_at <= NOW() + INTERVAL '3 days'
--      AND reminder_sent = false;
--
-- 5. Find active listings near a location (with PostGIS):
--    SELECT l.*, ST_Distance(c.location, target_location) AS distance_meters
--    FROM marketplace_listings l
--    JOIN geo_cities c ON l.geo_city_id = c.id
--    WHERE l.status = 'active'
--      AND ST_DWithin(c.location, target_location, 80467)  -- 50 miles in meters
--    ORDER BY distance_meters, l.created_at DESC;

-- //@UNDO

DROP TABLE IF EXISTS marketplace_listings;
