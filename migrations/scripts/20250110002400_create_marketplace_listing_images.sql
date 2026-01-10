-- // Create marketplace_listing_images table
-- Migration SQL that makes the change goes here.

-- Create marketplace_listing_images table for storing image metadata and variants
CREATE TABLE marketplace_listing_images (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id UUID NOT NULL REFERENCES marketplace_listings(id) ON DELETE CASCADE,
    storage_key TEXT NOT NULL,
    variant TEXT NOT NULL CHECK (variant IN ('original', 'thumbnail', 'list', 'full')),
    original_filename TEXT,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    display_order INTEGER DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'processed', 'failed')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for fast lookup of all images for a listing
CREATE INDEX idx_listing_images_listing_id ON marketplace_listing_images(listing_id);

-- Index for fast lookup of specific variant for a listing
CREATE INDEX idx_listing_images_variant ON marketplace_listing_images(listing_id, variant);

-- Index for fast lookup of images by status (for cleanup jobs)
CREATE INDEX idx_listing_images_status ON marketplace_listing_images(status) WHERE status IN ('pending', 'failed');

-- Unique constraint to prevent duplicate variants per listing per display order
CREATE UNIQUE INDEX idx_listing_images_unique_variant ON marketplace_listing_images(listing_id, variant, display_order);

-- //@UNDO
-- SQL to undo the change goes here.

DROP INDEX IF EXISTS idx_listing_images_unique_variant;
DROP INDEX IF EXISTS idx_listing_images_status;
DROP INDEX IF EXISTS idx_listing_images_variant;
DROP INDEX IF EXISTS idx_listing_images_listing_id;
DROP TABLE IF EXISTS marketplace_listing_images;
