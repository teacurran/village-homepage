-- // create_rate_limits
-- Establishes the rate limiting schema with configuration management and violation tracking.

-- Rate limit configuration table
-- Stores action-specific rate limits per tier (anonymous, logged_in, trusted).
CREATE TABLE IF NOT EXISTS rate_limit_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type TEXT NOT NULL,
    tier TEXT NOT NULL,
    limit_count INT NOT NULL CHECK (limit_count > 0),
    window_seconds INT NOT NULL CHECK (window_seconds > 0),
    updated_by_user_id BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(action_type, tier)
);

CREATE INDEX IF NOT EXISTS idx_rate_limit_config_lookup ON rate_limit_config(action_type, tier);
CREATE INDEX IF NOT EXISTS idx_rate_limit_config_updated ON rate_limit_config(updated_at DESC);

COMMENT ON TABLE rate_limit_config IS 'Rate limit rules per action type and user tier';
COMMENT ON COLUMN rate_limit_config.action_type IS 'Action identifier (e.g., bootstrap, login, search, vote, submission)';
COMMENT ON COLUMN rate_limit_config.tier IS 'User tier: anonymous, logged_in, or trusted';
COMMENT ON COLUMN rate_limit_config.limit_count IS 'Maximum allowed requests within window';
COMMENT ON COLUMN rate_limit_config.window_seconds IS 'Time window in seconds for limit enforcement';

-- Rate limit violation tracking table
-- Records violations with first/last timestamps for abuse detection and analytics.
CREATE TABLE IF NOT EXISTS rate_limit_violations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id BIGINT,
    ip_address INET,
    action_type TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    violation_count INT NOT NULL DEFAULT 1,
    first_violation_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_violation_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rate_limit_violations_user ON rate_limit_violations(user_id, action_type);
CREATE INDEX IF NOT EXISTS idx_rate_limit_violations_ip ON rate_limit_violations(ip_address, action_type);
CREATE INDEX IF NOT EXISTS idx_rate_limit_violations_last ON rate_limit_violations(last_violation_at DESC);

COMMENT ON TABLE rate_limit_violations IS 'Audit log for rate limit violations (Policy P14/F14.2)';
COMMENT ON COLUMN rate_limit_violations.user_id IS 'Authenticated user ID (NULL for anonymous)';
COMMENT ON COLUMN rate_limit_violations.ip_address IS 'Source IP address for geoblocking/analysis';
COMMENT ON COLUMN rate_limit_violations.endpoint IS 'HTTP endpoint path that triggered violation';
COMMENT ON COLUMN rate_limit_violations.violation_count IS 'Number of violations since first_violation_at';

-- Seed default rate limit configuration
-- Default tiers:
-- - anonymous: Strict limits for unauthenticated users
-- - logged_in: Moderate limits for authenticated users
-- - trusted: Generous limits for users with karma >= 10
INSERT INTO rate_limit_config (action_type, tier, limit_count, window_seconds)
VALUES
    -- Bootstrap endpoint: Tight limit to prevent token exhaustion
    ('bootstrap', 'anonymous', 5, 3600),  -- 5 per hour

    -- OAuth login: Prevent credential stuffing
    ('login', 'anonymous', 10, 900),      -- 10 per 15 min
    ('login', 'logged_in', 20, 900),      -- 20 per 15 min (allow account switching)

    -- Search: Balance UX with abuse prevention
    ('search', 'anonymous', 20, 60),      -- 20 per minute
    ('search', 'logged_in', 50, 60),      -- 50 per minute
    ('search', 'trusted', 100, 60),       -- 100 per minute

    -- Votes (Good Sites directory): Prevent manipulation
    ('vote', 'logged_in', 100, 3600),     -- 100 per hour
    ('vote', 'trusted', 200, 3600),       -- 200 per hour

    -- Marketplace listings: Prevent spam
    ('submission', 'logged_in', 10, 3600),    -- 10 per hour
    ('submission', 'trusted', 20, 3600),      -- 20 per hour

    -- Contact messages: Prevent harassment
    ('contact', 'logged_in', 20, 3600),   -- 20 per hour
    ('contact', 'trusted', 50, 3600)      -- 50 per hour
ON CONFLICT (action_type, tier) DO UPDATE
    SET limit_count = EXCLUDED.limit_count,
        window_seconds = EXCLUDED.window_seconds,
        updated_at = NOW();

-- //@UNDO

DROP TABLE IF EXISTS rate_limit_violations;
DROP TABLE IF EXISTS rate_limit_config;
