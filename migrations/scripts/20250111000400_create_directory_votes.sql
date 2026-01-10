-- //
-- // File: 20250111000400_create_directory_votes.sql
-- // Description: Create directory_votes table for Reddit-style voting
-- //
-- // Feature: F13.3 - Reddit-style up/down voting (login required)
-- // Policy: P13 - User-generated content moderation
-- //
-- // This table stores individual votes on directory sites. Voting is scoped to
-- // the site-category combination (not the site itself), following the principle
-- // that a site might be excellent in one category but mediocre in another.
-- //
-- // Vote mechanics:
-- //   - Vote value: +1 (upvote) or -1 (downvote)
-- //   - One vote per user per site+category (enforced by unique constraint)
-- //   - Login required (anonymous users cannot vote)
-- //   - Users can change their vote (update existing row)
-- //   - Deleting vote is allowed (removes row)
-- //
-- // Aggregation: Votes are aggregated into directory_site_categories
-- // (upvotes/downvotes/score columns) via trigger or application logic.
-- //

-- Create directory_votes table
CREATE TABLE directory_votes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_category_id UUID NOT NULL REFERENCES directory_site_categories(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    vote SMALLINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_directory_votes_vote CHECK (vote IN (1, -1)),

    -- One vote per user per site+category combination
    CONSTRAINT uq_directory_votes_user_site_category
        UNIQUE (site_category_id, user_id)
);

-- Index for vote aggregation queries (count upvotes/downvotes per site+category)
CREATE INDEX idx_directory_votes_site_category_id
    ON directory_votes(site_category_id);

-- Index for user's voting history
CREATE INDEX idx_directory_votes_user_id
    ON directory_votes(user_id, created_at DESC);

-- Composite index for vote value filtering
CREATE INDEX idx_directory_votes_site_category_vote
    ON directory_votes(site_category_id, vote);

-- Example queries:
-- Get user's vote on site+category:
--   SELECT vote FROM directory_votes
--   WHERE site_category_id = ? AND user_id = ?;
--
-- Count upvotes for site+category:
--   SELECT COUNT(*) FROM directory_votes
--   WHERE site_category_id = ? AND vote = 1;
--
-- Count downvotes for site+category:
--   SELECT COUNT(*) FROM directory_votes
--   WHERE site_category_id = ? AND vote = -1;
--
-- Find user's recent votes:
--   SELECT * FROM directory_votes
--   WHERE user_id = ?
--   ORDER BY created_at DESC
--   LIMIT 50;
--
-- Aggregate votes for site+category (for cache update):
--   SELECT
--     SUM(CASE WHEN vote = 1 THEN 1 ELSE 0 END) AS upvotes,
--     SUM(CASE WHEN vote = -1 THEN 1 ELSE 0 END) AS downvotes,
--     SUM(vote) AS score
--   FROM directory_votes
--   WHERE site_category_id = ?;

-- //@UNDO
-- SQL to undo the change goes here.

DROP INDEX IF EXISTS idx_directory_votes_site_category_vote;
DROP INDEX IF EXISTS idx_directory_votes_user_id;
DROP INDEX IF EXISTS idx_directory_votes_site_category_id;
DROP TABLE IF EXISTS directory_votes;
