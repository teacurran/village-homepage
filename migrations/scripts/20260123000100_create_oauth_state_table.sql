-- OAuth state token storage for CSRF protection (Policy P9)
CREATE TABLE oauth_states (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    state TEXT NOT NULL UNIQUE,
    session_id TEXT NOT NULL,
    provider TEXT NOT NULL CHECK (provider IN ('google', 'facebook', 'apple')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL
);

-- Index for state lookup and expiration cleanup
CREATE INDEX idx_oauth_states_expires_at ON oauth_states(expires_at);

COMMENT ON TABLE oauth_states IS 'CSRF state tokens for OAuth flows - 5 minute TTL';
COMMENT ON COLUMN oauth_states.state IS 'Cryptographically random UUID v4 token';
COMMENT ON COLUMN oauth_states.session_id IS 'Anonymous session hash or authenticated user ID';
