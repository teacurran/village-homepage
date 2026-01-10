-- Add ban fields to users table for chargeback enforcement (I4.T8)
-- Per Policy P3: Ban users with 2+ chargebacks

ALTER TABLE users
ADD COLUMN is_banned BOOLEAN NOT NULL DEFAULT FALSE,
ADD COLUMN banned_at TIMESTAMPTZ,
ADD COLUMN ban_reason TEXT;

-- Index for checking banned status during auth
CREATE INDEX idx_users_is_banned ON users(is_banned) WHERE is_banned = TRUE;

-- Comments
COMMENT ON COLUMN users.is_banned IS 'User banned from platform (typically for 2+ chargebacks per P3)';
COMMENT ON COLUMN users.banned_at IS 'Timestamp when user was banned';
COMMENT ON COLUMN users.ban_reason IS 'Reason for ban (e.g., "Repeated chargebacks (3)")';
