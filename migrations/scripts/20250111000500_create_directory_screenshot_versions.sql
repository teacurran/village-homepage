--
-- //
-- // Description: Create screenshot version history table for Good Sites
-- // Feature: F13.3 - Screenshot capture with indefinite retention
-- // Policy: P4 - Indefinite retention with full version history
-- // Policy: P12 - Screenshot queue concurrency limits
-- //

-- Create directory_screenshot_versions table
CREATE TABLE directory_screenshot_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id UUID NOT NULL REFERENCES directory_sites(id) ON DELETE CASCADE,
    version INT NOT NULL,
    thumbnail_storage_key TEXT NOT NULL,
    full_storage_key TEXT NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    capture_duration_ms INT,
    status TEXT NOT NULL,  -- success, failed, timeout
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_directory_screenshot_versions_status
        CHECK (status IN ('success', 'failed', 'timeout'))
);

-- Unique constraint: one version number per site
CREATE UNIQUE INDEX uq_directory_screenshot_versions_site_version
    ON directory_screenshot_versions(site_id, version);

-- Index for latest version queries (DESC order for max version lookups)
CREATE INDEX idx_directory_screenshot_versions_site_id
    ON directory_screenshot_versions(site_id, version DESC);

-- Index for status monitoring (find failed captures)
CREATE INDEX idx_directory_screenshot_versions_status
    ON directory_screenshot_versions(status, captured_at DESC);

-- Index for performance analysis
CREATE INDEX idx_directory_screenshot_versions_duration
    ON directory_screenshot_versions(capture_duration_ms)
    WHERE status = 'success';

-- //@UNDO
-- // Undo script for rollback

DROP INDEX IF EXISTS idx_directory_screenshot_versions_duration;
DROP INDEX IF EXISTS idx_directory_screenshot_versions_status;
DROP INDEX IF EXISTS idx_directory_screenshot_versions_site_id;
DROP INDEX IF EXISTS uq_directory_screenshot_versions_site_version;
DROP TABLE IF EXISTS directory_screenshot_versions;
