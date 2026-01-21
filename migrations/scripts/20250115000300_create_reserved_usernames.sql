-- //
-- // File: 20250115000300_create_reserved_usernames.sql
-- // Description: Create reserved_usernames table for namespace protection
-- //
-- // Feature: F11.2 - Reserved Names
-- // Policy: P13 - User-generated content moderation (prevent impersonation)
-- //
-- // This table stores usernames reserved for system use, features, and
-- // common terms to prevent impersonation and namespace conflicts.
-- //
-- // Reserved categories:
-- //   - System: admin, api, cdn, www, assets, static, public
-- //   - Features: good-sites, marketplace, calendar, directory, search
-- //   - Admin roles: support, ops, moderator, root, superuser
-- //   - Common: help, about, contact, terms, privacy, blog, news
-- //
-- // Enforcement: Username validation checks this table before allowing
-- // profile creation. Admins can add/remove reserved names via admin API.
-- //

-- Create reserved_usernames table
CREATE TABLE reserved_usernames (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username TEXT NOT NULL,
    reason TEXT NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Unique constraint on username (case-insensitive enforcement)
CREATE UNIQUE INDEX idx_reserved_usernames_username ON reserved_usernames(username);

-- Example queries:
-- Check if username is reserved:
--   SELECT EXISTS(SELECT 1 FROM reserved_usernames WHERE username = ?);
--
-- List all reserved names:
--   SELECT * FROM reserved_usernames ORDER BY username;
--
-- Find by reason category:
--   SELECT * FROM reserved_usernames WHERE reason LIKE 'System:%';

-- //@UNDO
-- SQL to undo the change goes here.

DROP INDEX IF EXISTS idx_reserved_usernames_username;
DROP TABLE IF EXISTS reserved_usernames;
