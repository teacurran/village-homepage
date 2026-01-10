-- //
-- // File: 20250111000300_create_directory_site_categories.sql
-- // Description: Create directory_site_categories junction table
-- //
-- // Feature: F13.2 - Hand-curated web directory (Good Sites)
-- // Policy: P13 - User-generated content moderation
-- //
-- // This junction table enables many-to-many relationship between sites and
-- // categories. A single site can exist in multiple categories (e.g., a news
-- // site might be in both "News" and "Politics"). Each site-category membership
-- // has separate voting/scoring, following the DMOZ/Yahoo Directory model.
-- //
-- // Status lifecycle (per category):
-- //   pending -> approved (moderator approval specific to this category)
-- //   pending -> rejected (moderator rejection for this category)
-- //
-- // Vote aggregation: upvotes/downvotes/score are cached denormalized values
-- // updated whenever a vote is cast. Rank is computed periodically by background
-- // job (I5.T7) to order sites within each category.
-- //

-- Create directory_site_categories junction table
CREATE TABLE directory_site_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id UUID NOT NULL REFERENCES directory_sites(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES directory_categories(id) ON DELETE RESTRICT,
    score INT NOT NULL DEFAULT 0,
    upvotes INT NOT NULL DEFAULT 0,
    downvotes INT NOT NULL DEFAULT 0,
    rank_in_category INT,
    submitted_by_user_id UUID NOT NULL REFERENCES users(id),
    approved_by_user_id UUID REFERENCES users(id),
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_directory_site_categories_status
        CHECK (status IN ('pending', 'approved', 'rejected')),

    -- One site can only appear once per category
    CONSTRAINT uq_directory_site_categories_site_category
        UNIQUE (site_id, category_id)
);

-- Index for category browsing (find approved sites in category, sorted by score)
CREATE INDEX idx_directory_site_categories_category_approved
    ON directory_site_categories(category_id, status, score DESC)
    WHERE status = 'approved';

-- Index for site's categories (reverse lookup)
CREATE INDEX idx_directory_site_categories_site_id
    ON directory_site_categories(site_id);

-- Index for moderation queue (find pending in category)
CREATE INDEX idx_directory_site_categories_status
    ON directory_site_categories(status, created_at);

-- Index for ranking job (find approved sites to recalculate rank)
CREATE INDEX idx_directory_site_categories_category_score
    ON directory_site_categories(category_id, score DESC, created_at DESC)
    WHERE status = 'approved';

-- Composite index for submitter's pending submissions
CREATE INDEX idx_directory_site_categories_submitter_status
    ON directory_site_categories(submitted_by_user_id, status);

-- Example queries:
-- Browse approved sites in category, sorted by score:
--   SELECT * FROM directory_site_categories
--   WHERE category_id = ? AND status = 'approved'
--   ORDER BY score DESC, created_at DESC
--   LIMIT 50;
--
-- Find pending submissions in category for moderation:
--   SELECT * FROM directory_site_categories
--   WHERE category_id = ? AND status = 'pending'
--   ORDER BY created_at ASC;
--
-- Find all categories a site is in:
--   SELECT category_id FROM directory_site_categories WHERE site_id = ?;
--
-- Check if site already in category:
--   SELECT id FROM directory_site_categories WHERE site_id = ? AND category_id = ?;

-- //@UNDO
-- SQL to undo the change goes here.

DROP INDEX IF EXISTS idx_directory_site_categories_submitter_status;
DROP INDEX IF EXISTS idx_directory_site_categories_category_score;
DROP INDEX IF EXISTS idx_directory_site_categories_status;
DROP INDEX IF EXISTS idx_directory_site_categories_site_id;
DROP INDEX IF EXISTS idx_directory_site_categories_category_approved;
DROP TABLE IF EXISTS directory_site_categories;
