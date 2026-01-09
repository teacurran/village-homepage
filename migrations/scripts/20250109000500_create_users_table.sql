-- // create_users_table
-- Creates the core users table supporting both anonymous and authenticated accounts per Policy P1 & P9.
-- Anonymous users are tracked with secure vu_anon_id cookies; authenticated users link via OAuth provider.
-- This table is the foundation for account merging, preference storage, and directory karma/trust levels.

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT,
    oauth_provider TEXT CHECK (oauth_provider IN ('google', 'facebook', 'apple')),
    oauth_id TEXT,
    display_name TEXT,
    avatar_url TEXT,
    preferences JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_anonymous BOOLEAN NOT NULL DEFAULT FALSE,
    directory_karma INT NOT NULL DEFAULT 0,
    directory_trust_level TEXT NOT NULL DEFAULT 'untrusted' CHECK (directory_trust_level IN ('untrusted', 'trusted', 'moderator')),
    analytics_consent BOOLEAN NOT NULL DEFAULT FALSE,
    last_active_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

-- Indexes for common query patterns
CREATE UNIQUE INDEX idx_users_email ON users(email) WHERE deleted_at IS NULL AND is_anonymous = FALSE;
CREATE UNIQUE INDEX idx_users_oauth ON users(oauth_provider, oauth_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_anonymous ON users(is_anonymous) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_deleted ON users(deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX idx_users_last_active ON users(last_active_at DESC);

-- GIN index for preferences JSONB queries
CREATE INDEX idx_users_preferences ON users USING GIN(preferences);

-- Constraint: authenticated users must have email and oauth data
ALTER TABLE users ADD CONSTRAINT check_authenticated_fields
    CHECK (
        (is_anonymous = TRUE) OR
        (is_anonymous = FALSE AND email IS NOT NULL AND oauth_provider IS NOT NULL AND oauth_id IS NOT NULL)
    );

-- //@UNDO

DROP TABLE IF EXISTS users;
