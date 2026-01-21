-- //
-- // File: 20250115000200_create_profile_curated_articles.sql
-- // Description: Create profile_curated_articles table for profile content curation
-- //
-- // Feature: F11.4 - Curated Articles for public profiles
-- // Policy: P4 - Data Retention (indefinite for user-generated content)
-- //
-- // This table stores curated article selections for user profiles. Users can
-- // select articles from RSS feeds (via feed_items) or add manual links with
-- // custom headlines and blurbs. Template configuration (slot_assignment)
-- // determines where each article appears on the profile page.
-- //
-- // Article sources:
-- //   - RSS feeds: feed_item_id populated, original_* fields copied from feed_items
-- //   - Manual entries: feed_item_id NULL, original_* fields manually entered
-- //
-- // Customization:
-- //   - custom_headline: Override original title
-- //   - custom_blurb: Override original description
-- //   - custom_image_url: Override original image
-- //   - slot_assignment: Template-specific positioning (JSON)
-- //
-- // Active flag:
-- //   - is_active = true: Display on profile
-- //   - is_active = false: Hidden (draft/removed)
-- //

-- Create profile_curated_articles table
CREATE TABLE profile_curated_articles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES user_profiles(id) ON DELETE CASCADE,
    feed_item_id UUID REFERENCES feed_items(id) ON DELETE SET NULL,
    original_url TEXT NOT NULL,
    original_title TEXT NOT NULL,
    original_description TEXT,
    original_image_url TEXT,
    custom_headline TEXT,
    custom_blurb TEXT,
    custom_image_url TEXT,
    slot_assignment JSONB NOT NULL DEFAULT '{}',
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Find articles by profile
CREATE INDEX idx_profile_curated_articles_profile_id ON profile_curated_articles(profile_id, created_at DESC);

-- Active articles for display (fast path for public pages)
CREATE INDEX idx_profile_curated_articles_active ON profile_curated_articles(profile_id, is_active) WHERE is_active = true;

-- Link to feed items (for updates/sync)
CREATE INDEX idx_profile_curated_articles_feed_item_id ON profile_curated_articles(feed_item_id) WHERE feed_item_id IS NOT NULL;

-- Example queries:
-- Find all active articles for a profile:
--   SELECT * FROM profile_curated_articles
--   WHERE profile_id = ? AND is_active = true
--   ORDER BY created_at DESC;
--
-- Find articles from a specific feed item:
--   SELECT * FROM profile_curated_articles
--   WHERE feed_item_id = ?;
--
-- Count articles for a profile:
--   SELECT COUNT(*) FROM profile_curated_articles
--   WHERE profile_id = ? AND is_active = true;
--
-- Find manual entries (no feed link):
--   SELECT * FROM profile_curated_articles
--   WHERE profile_id = ? AND feed_item_id IS NULL;

-- //@UNDO
-- SQL to undo the change goes here.

DROP INDEX IF EXISTS idx_profile_curated_articles_feed_item_id;
DROP INDEX IF EXISTS idx_profile_curated_articles_active;
DROP INDEX IF EXISTS idx_profile_curated_articles_profile_id;
DROP TABLE IF EXISTS profile_curated_articles;
