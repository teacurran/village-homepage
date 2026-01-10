-- //
-- // File: 20250111000200_create_directory_sites.sql
-- // Description: Create directory_sites table for Good Sites web directory
-- //
-- // Feature: F13.2 - Hand-curated web directory (Good Sites)
-- // Policy: P13 - User-generated content moderation
-- //
-- // This table stores submitted websites in the directory. Sites can be
-- // submitted by any logged-in user and go through a karma-based moderation
-- // flow. Untrusted users' submissions enter a pending queue; trusted users'
-- // submissions auto-approve.
-- //
-- // Status lifecycle:
-- //   pending -> approved (moderator approval or auto-approve for trusted users)
-- //   pending -> rejected (moderator rejection)
-- //   approved -> dead (link health check failure)
-- //
-- // Duplicate detection: Enforced via unique index on URL (normalized to
-- // lowercase HTTPS with trailing slash removed).
-- //

-- Create directory_sites table
CREATE TABLE directory_sites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    url TEXT NOT NULL,
    domain TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    screenshot_url TEXT,
    screenshot_captured_at TIMESTAMPTZ,
    og_image_url TEXT,
    favicon_url TEXT,
    custom_image_url TEXT,
    submitted_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'pending',
    last_checked_at TIMESTAMPTZ,
    is_dead BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_directory_sites_status
        CHECK (status IN ('pending', 'approved', 'rejected', 'dead'))
);

-- Unique constraint: one site per normalized URL
CREATE UNIQUE INDEX idx_directory_sites_url ON directory_sites(url);

-- Index for user's submissions
CREATE INDEX idx_directory_sites_user_id ON directory_sites(submitted_by_user_id);

-- Index for moderation queue (find pending)
CREATE INDEX idx_directory_sites_status ON directory_sites(status);

-- Index for duplicate detection (find by domain)
CREATE INDEX idx_directory_sites_domain ON directory_sites(domain);

-- Index for link health checks
CREATE INDEX idx_directory_sites_dead ON directory_sites(is_dead, last_checked_at);

-- Composite index for status-based queries with date sorting
CREATE INDEX idx_directory_sites_status_created ON directory_sites(status, created_at DESC);

-- Example queries:
-- Find pending sites for moderation:
--   SELECT * FROM directory_sites WHERE status = 'pending' ORDER BY created_at DESC;
--
-- Find user's submissions:
--   SELECT * FROM directory_sites WHERE submitted_by_user_id = ? ORDER BY created_at DESC;
--
-- Find dead links for cleanup:
--   SELECT * FROM directory_sites WHERE is_dead = true ORDER BY last_checked_at DESC;
--
-- Check for duplicate URL:
--   SELECT id FROM directory_sites WHERE url = ?;

-- //@UNDO
-- SQL to undo the change goes here.

DROP INDEX IF EXISTS idx_directory_sites_status_created;
DROP INDEX IF EXISTS idx_directory_sites_dead;
DROP INDEX IF EXISTS idx_directory_sites_domain;
DROP INDEX IF EXISTS idx_directory_sites_status;
DROP INDEX IF EXISTS idx_directory_sites_user_id;
DROP INDEX IF EXISTS idx_directory_sites_url;
DROP TABLE IF EXISTS directory_sites;
