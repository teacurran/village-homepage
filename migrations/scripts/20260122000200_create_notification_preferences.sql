-- // create_notification_preferences
-- Creates the notification_preferences table for managing user opt-in/opt-out settings.
-- Stores per-channel notification preferences (email).
-- One preferences record per user (enforced via UNIQUE constraint on user_id).
-- Default preferences created on first user signup.
--
-- Privacy Compliance: P1, P2, P3, P7, P14
-- - P1: User data minimization (only preference flags)
-- - P2: Cascade delete on user deletion
-- - P3: No third-party data sharing
-- - P7: Data retention (preferences deleted with user account)
-- - P14: Explicit consent management (opt-in/opt-out controls)

CREATE TABLE notification_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    email_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    email_listing_messages BOOLEAN NOT NULL DEFAULT TRUE,
    email_site_approved BOOLEAN NOT NULL DEFAULT TRUE,
    email_site_rejected BOOLEAN NOT NULL DEFAULT TRUE,
    email_digest BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Unique index for one preferences record per user
CREATE UNIQUE INDEX idx_notification_prefs_user_id ON notification_preferences(user_id);

-- //@UNDO

DROP TABLE IF EXISTS notification_preferences;
