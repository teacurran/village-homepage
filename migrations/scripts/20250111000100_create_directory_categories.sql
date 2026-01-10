-- // create_directory_categories
-- Creates directory category hierarchy for Good Sites web directory per Feature F13.1.
--
-- This migration defines the schema for directory_categories supporting:
-- - Hierarchical parent-child relationships with unlimited depth (Yahoo Directory / DMOZ style)
-- - Category-specific icon URLs for visual directory navigation
-- - Cached link counts for performance (updated by background jobs)
-- - Admin controls for active/inactive categories and sort ordering
-- - URL-friendly slugs for category browsing
--
-- After applying this migration, run seed data to populate default categories:
--   psql -h $POSTGRES_HOST -p $POSTGRES_PORT -U $POSTGRES_USER -d $POSTGRES_DB \
--        -f migrations/seeds/directory_categories.sql
--
-- Policy References:
--   F13.1: Good Sites Directory hierarchical categories with voting and moderation
--   P4: Screenshot storage with WebP compression (~$100/month budget)
--   P12: Screenshot capture pool with semaphore limits for browser resource management
--
-- Category Structure:
--   Root categories: Arts, Business, Computers, News, Recreation, Science, Society
--   Each root category has 15-25 subcategories following classic web directory taxonomy
--   Unlimited depth for future expansion (e.g., Computers > Software > Open Source)
--   link_count is a denormalized cache updated by rank recalculation job (hourly)

CREATE TABLE directory_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id UUID REFERENCES directory_categories(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    slug TEXT NOT NULL,
    description TEXT,
    icon_url TEXT,
    sort_order INT NOT NULL DEFAULT 0,
    link_count INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Unique constraint on slug (URL-friendly identifier, globally unique)
CREATE UNIQUE INDEX idx_directory_categories_slug ON directory_categories(slug);

-- Index on parent_id for hierarchical queries (finding children, breadcrumb navigation)
CREATE INDEX idx_directory_categories_parent_id ON directory_categories(parent_id);

-- Index on is_active for filtering disabled categories in public browsing
CREATE INDEX idx_directory_categories_is_active ON directory_categories(is_active);

-- Index on sort_order for ordering categories within same parent level
CREATE INDEX idx_directory_categories_sort_order ON directory_categories(sort_order);

-- Composite index for common query pattern: active categories ordered by sort_order
-- Used on every directory page load to show active categories
CREATE INDEX idx_directory_categories_active_sorted ON directory_categories(is_active, sort_order) WHERE is_active = true;

-- Composite index for link count sorting (popular categories bubble up)
-- Used for "Most Popular" category sorting and bubbling algorithm
CREATE INDEX idx_directory_categories_link_count_desc ON directory_categories(link_count DESC) WHERE is_active = true;

-- Example queries:
--
-- 1. Find all root categories (for main directory navigation):
--    SELECT * FROM directory_categories
--    WHERE parent_id IS NULL AND is_active = true
--    ORDER BY sort_order;
--
-- 2. Find children of a category (for breadcrumb/subcategory display):
--    SELECT * FROM directory_categories
--    WHERE parent_id = '...' AND is_active = true
--    ORDER BY sort_order;
--
-- 3. Find category by slug (for URL routing):
--    SELECT * FROM directory_categories WHERE slug = 'computers-software';
--
-- 4. Get full category path (recursive query for breadcrumbs):
--    WITH RECURSIVE category_path AS (
--      SELECT id, parent_id, name, slug, 1 as depth
--      FROM directory_categories
--      WHERE id = '...'
--      UNION ALL
--      SELECT dc.id, dc.parent_id, dc.name, dc.slug, cp.depth + 1
--      FROM directory_categories dc
--      JOIN category_path cp ON dc.id = cp.parent_id
--    )
--    SELECT * FROM category_path ORDER BY depth DESC;
--
-- 5. Find most popular categories (for homepage/trending):
--    SELECT * FROM directory_categories
--    WHERE is_active = true AND link_count > 0
--    ORDER BY link_count DESC
--    LIMIT 10;
--
-- 6. Count total categories per root (for admin analytics):
--    WITH RECURSIVE category_tree AS (
--      SELECT id, parent_id, name
--      FROM directory_categories
--      WHERE parent_id IS NULL
--      UNION ALL
--      SELECT dc.id, dc.parent_id, dc.name
--      FROM directory_categories dc
--      JOIN category_tree ct ON dc.parent_id = ct.id
--    )
--    SELECT COUNT(*) FROM category_tree;

-- //@UNDO

DROP TABLE IF EXISTS directory_categories;
