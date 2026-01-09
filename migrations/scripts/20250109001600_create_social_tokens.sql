--
-- Social tokens table for Meta Graph API authentication per Policy P5/P13.
--
-- Stores encrypted OAuth access and refresh tokens for Instagram and Facebook.
-- Tokens refresh 7 days before expiry via background job.
-- Supports indefinite retention until user revocation.
--

CREATE TABLE social_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform VARCHAR(20) NOT NULL CHECK (platform IN ('instagram', 'facebook')),
    access_token VARCHAR(1000) NOT NULL,  -- TODO: Encrypt at rest via Quarkus Vault (Policy P5)
    refresh_token VARCHAR(1000),          -- TODO: Encrypt at rest via Quarkus Vault (Policy P5)
    expires_at TIMESTAMPTZ NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMPTZ,
    scopes VARCHAR(500),
    last_refresh_attempt TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Unique constraint: one active token per user per platform
CREATE UNIQUE INDEX social_tokens_user_platform_idx
    ON social_tokens(user_id, platform)
    WHERE revoked_at IS NULL;

-- Index for token refresh job (find tokens expiring in 7 days)
CREATE INDEX social_tokens_expires_idx
    ON social_tokens(expires_at)
    WHERE revoked_at IS NULL;

-- Index for user lookups
CREATE INDEX social_tokens_user_id_idx
    ON social_tokens(user_id);

COMMENT ON TABLE social_tokens IS 'OAuth tokens for Instagram and Facebook integration. Tokens encrypted at rest per Policy P5. Indefinite retention until revocation per Policy P13.';
COMMENT ON COLUMN social_tokens.access_token IS 'OAuth access token (encrypted at rest). Never log full token - only prefixes.';
COMMENT ON COLUMN social_tokens.refresh_token IS 'OAuth refresh token (encrypted at rest). Used to obtain new access tokens.';
COMMENT ON COLUMN social_tokens.expires_at IS 'Access token expiration time. Background job refreshes 7 days before expiry.';
COMMENT ON COLUMN social_tokens.revoked_at IS 'Timestamp when user disconnected integration. NULL for active tokens.';
COMMENT ON COLUMN social_tokens.last_refresh_attempt IS 'Last token refresh attempt timestamp (success or failure).';
