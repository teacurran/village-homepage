-- Add OAuth token storage fields to users table for token refresh functionality (I3.T5)
--
-- These fields enable the background job to refresh expiring OAuth access tokens:
-- - Google: Refresh tokens never expire (until revoked)
-- - Facebook: Long-lived tokens (60 days) that can be extended
-- - Apple: Refresh tokens expire after 6 months
--
-- Policy References:
-- - P5: Secure token storage (encrypted at rest via database encryption)
-- - P13: Social integration requires valid access tokens

-- Google OAuth token fields
ALTER TABLE users ADD COLUMN google_refresh_token TEXT;
ALTER TABLE users ADD COLUMN google_access_token_expires_at TIMESTAMPTZ;

-- Facebook OAuth token fields (no refresh token, uses long-lived access tokens)
ALTER TABLE users ADD COLUMN facebook_access_token TEXT;
ALTER TABLE users ADD COLUMN facebook_access_token_expires_at TIMESTAMPTZ;

-- Apple OAuth token fields
ALTER TABLE users ADD COLUMN apple_refresh_token TEXT;
ALTER TABLE users ADD COLUMN apple_access_token_expires_at TIMESTAMPTZ;

-- Partial indexes for efficient querying of users with expiring tokens
-- Only indexes rows where tokens exist (reduces index size)
CREATE INDEX idx_users_google_token_expiry
    ON users(google_access_token_expires_at)
    WHERE google_refresh_token IS NOT NULL;

CREATE INDEX idx_users_facebook_token_expiry
    ON users(facebook_access_token_expires_at)
    WHERE facebook_access_token IS NOT NULL;

CREATE INDEX idx_users_apple_token_expiry
    ON users(apple_access_token_expires_at)
    WHERE apple_refresh_token IS NOT NULL;

-- Comments for documentation
COMMENT ON COLUMN users.google_refresh_token IS 'Google OAuth refresh token (never expires until revoked)';
COMMENT ON COLUMN users.google_access_token_expires_at IS 'When the current Google access token expires (typically 1 hour from issue)';
COMMENT ON COLUMN users.facebook_access_token IS 'Facebook long-lived access token (60 days, can be extended)';
COMMENT ON COLUMN users.facebook_access_token_expires_at IS 'When the Facebook access token expires (60 days from issue)';
COMMENT ON COLUMN users.apple_refresh_token IS 'Apple refresh token (expires after 6 months)';
COMMENT ON COLUMN users.apple_access_token_expires_at IS 'When the current Apple access token expires (typically 1 hour from issue)';
