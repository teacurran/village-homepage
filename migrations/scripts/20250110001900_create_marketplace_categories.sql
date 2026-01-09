-- // create_marketplace_categories
-- Creates marketplace category hierarchy for classifieds listings per Feature F12.3.
--
-- This migration defines the schema for marketplace_categories supporting:
-- - Hierarchical parent-child relationships with unlimited depth
-- - Category-specific fee schedules for monetization (Policy P3)
-- - Admin controls for active/inactive categories and sort ordering
-- - URL-friendly slugs for category browsing
--
-- After applying this migration, run seed data to populate default categories:
--   psql -h $POSTGRES_HOST -p $POSTGRES_PORT -U $POSTGRES_USER -d $POSTGRES_DB \
--        -f migrations/seeds/marketplace_categories.sql
--
-- Policy References:
--   F12.3: Category structure (For Sale, Housing, Jobs, Services, Community)
--   P3: Monetization via Stripe (fee schedules for posting/featured/bump)
--   P6: Craigslist-style UI with hierarchical categories
--
-- Category Structure:
--   Main categories: For Sale, Housing, Jobs, Services, Community
--   Each main category has 20-30 subcategories mirroring Craigslist structure
--   Fee schedules stored as JSONB: {"posting_fee": 0, "featured_fee": 5.00, "bump_fee": 2.00}

CREATE TABLE marketplace_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id UUID REFERENCES marketplace_categories(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    slug TEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    fee_schedule JSONB NOT NULL DEFAULT '{"posting_fee": 0, "featured_fee": 0, "bump_fee": 0}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Unique constraint on slug (URL-friendly identifier)
CREATE UNIQUE INDEX idx_marketplace_categories_slug ON marketplace_categories(slug);

-- Index on parent_id for hierarchical queries (finding children)
CREATE INDEX idx_marketplace_categories_parent_id ON marketplace_categories(parent_id);

-- Index on is_active for filtering disabled categories
CREATE INDEX idx_marketplace_categories_is_active ON marketplace_categories(is_active);

-- Index on sort_order for ordering categories within same parent
CREATE INDEX idx_marketplace_categories_sort_order ON marketplace_categories(sort_order);

-- Composite index for common query pattern: active categories ordered by sort_order
CREATE INDEX idx_marketplace_categories_active_sorted ON marketplace_categories(is_active, sort_order) WHERE is_active = true;

-- Example queries:
--
-- 1. Find all root categories (for main navigation):
--    SELECT * FROM marketplace_categories
--    WHERE parent_id IS NULL AND is_active = true
--    ORDER BY sort_order;
--
-- 2. Find children of a category (for breadcrumb/subcategory display):
--    SELECT * FROM marketplace_categories
--    WHERE parent_id = '...' AND is_active = true
--    ORDER BY sort_order;
--
-- 3. Find category by slug (for URL routing):
--    SELECT * FROM marketplace_categories WHERE slug = 'electronics';
--
-- 4. Get full category path (recursive query):
--    WITH RECURSIVE category_path AS (
--      SELECT id, parent_id, name, slug, 1 as depth
--      FROM marketplace_categories
--      WHERE id = '...'
--      UNION ALL
--      SELECT mc.id, mc.parent_id, mc.name, mc.slug, cp.depth + 1
--      FROM marketplace_categories mc
--      JOIN category_path cp ON mc.id = cp.parent_id
--    )
--    SELECT * FROM category_path ORDER BY depth DESC;

-- //@UNDO

DROP TABLE IF EXISTS marketplace_categories;
