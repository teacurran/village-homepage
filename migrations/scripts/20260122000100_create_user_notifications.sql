-- // create_user_notifications
-- Creates the user_notifications table for in-app notification system.
-- Tracks notifications for user actions (listing messages, site approvals, etc.).
-- Supports unread filtering via read_at timestamp (null = unread).
--
-- Privacy Compliance: P1, P2, P3, P7, P14
-- - P1: User data minimization (only essential notification data)
-- - P2: Cascade delete on user deletion
-- - P3: No third-party data sharing
-- - P7: Data retention (notifications deleted with user account)
-- - P14: Consent-aware (respects notification preferences)

CREATE TABLE user_notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type TEXT NOT NULL,
    title TEXT NOT NULL,
    message TEXT NOT NULL,
    action_url TEXT,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for common query patterns
CREATE INDEX idx_user_notifications_user_id ON user_notifications(user_id);
CREATE INDEX idx_user_notifications_created_at ON user_notifications(created_at DESC);

-- Partial index for unread notifications (performance optimization)
CREATE INDEX idx_user_notifications_unread ON user_notifications(user_id) WHERE read_at IS NULL;

-- //@UNDO

DROP TABLE IF EXISTS user_notifications;
