-- //
-- // File: 20250115000100_create_user_profiles.sql
-- // Description: Create user_profiles table for public profile pages
-- //
-- // Feature: F11 - Public Profiles
-- // Policy: P1 - GDPR/CCPA Compliance (soft delete support)
-- // Policy: P4 - Data Retention (indefinite for user-generated content)
-- //
-- // This table stores user profile data for customizable public profile pages
-- // accessible at /u/{username}. Users can claim unique usernames, select
-- // templates, and curate articles for display.
-- //
-- // Profile lifecycle:
-- //   Create -> Draft (is_published = false, returns 404 when accessed)
-- //   Publish -> Live (is_published = true, accessible at /u/{username})
-- //   Unpublish -> Returns to draft state (404)
-- //   Delete -> Soft delete (deleted_at timestamp, 90-day retention per P1)
-- //
-- // Username validation:
-- //   - Length: 3-30 characters
-- //   - Characters: a-z, 0-9, underscore, dash (no spaces)
-- //   - Case-insensitive (stored lowercase)
-- //   - Unique constraint
-- //   - Reserved names blocked via reserved_usernames table
-- //
-- // Templates:
-- //   - public_homepage: Classic homepage-style layout
-- //   - your_times: Newspaper-style with masthead
-- //   - your_report: Report/newsletter style
-- //

-- Create user_profiles table
CREATE TABLE user_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    username TEXT NOT NULL,
    display_name TEXT,
    bio TEXT,
    avatar_url TEXT,
    location_text TEXT,
    website_url TEXT,
    social_links JSONB NOT NULL DEFAULT '{}',
    template TEXT NOT NULL DEFAULT 'public_homepage',
    template_config JSONB NOT NULL DEFAULT '{}',
    is_published BOOLEAN NOT NULL DEFAULT false,
    view_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,

    CONSTRAINT chk_user_profiles_template
        CHECK (template IN ('public_homepage', 'your_times', 'your_report')),
    CONSTRAINT chk_user_profiles_username_length
        CHECK (char_length(username) >= 3 AND char_length(username) <= 30),
    CONSTRAINT chk_user_profiles_username_format
        CHECK (username ~ '^[a-z0-9_-]+$')
);

-- One profile per user
CREATE UNIQUE INDEX idx_user_profiles_user_id ON user_profiles(user_id) WHERE deleted_at IS NULL;

-- Unique usernames (case-insensitive, excluding soft-deleted)
CREATE UNIQUE INDEX idx_user_profiles_username ON user_profiles(username) WHERE deleted_at IS NULL;

-- Public profile lookup (fast path for /u/{username})
CREATE INDEX idx_user_profiles_username_published ON user_profiles(username, is_published) WHERE deleted_at IS NULL;

-- Find profiles by publish status
CREATE INDEX idx_user_profiles_published ON user_profiles(is_published, created_at DESC) WHERE deleted_at IS NULL;

-- View count analytics (top profiles)
CREATE INDEX idx_user_profiles_view_count ON user_profiles(view_count DESC, created_at DESC) WHERE deleted_at IS NULL;

-- Soft delete queries
CREATE INDEX idx_user_profiles_deleted_at ON user_profiles(deleted_at) WHERE deleted_at IS NOT NULL;

-- Example queries:
-- Find profile by username (public page):
--   SELECT * FROM user_profiles WHERE username = ? AND deleted_at IS NULL;
--
-- Find user's profile:
--   SELECT * FROM user_profiles WHERE user_id = ? AND deleted_at IS NULL;
--
-- Find published profiles (directory):
--   SELECT * FROM user_profiles WHERE is_published = true AND deleted_at IS NULL ORDER BY view_count DESC;
--
-- Top profiles by views:
--   SELECT * FROM user_profiles WHERE deleted_at IS NULL ORDER BY view_count DESC LIMIT 10;
--
-- Check username availability:
--   SELECT EXISTS(SELECT 1 FROM user_profiles WHERE username = ? AND deleted_at IS NULL);

-- //@UNDO
-- SQL to undo the change goes here.

DROP INDEX IF EXISTS idx_user_profiles_deleted_at;
DROP INDEX IF EXISTS idx_user_profiles_view_count;
DROP INDEX IF EXISTS idx_user_profiles_published;
DROP INDEX IF EXISTS idx_user_profiles_username_published;
DROP INDEX IF EXISTS idx_user_profiles_username;
DROP INDEX IF EXISTS idx_user_profiles_user_id;
DROP TABLE IF EXISTS user_profiles;
