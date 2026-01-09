-- // create_user_feed_subscriptions
-- Creates the user_feed_subscriptions many-to-many join table between users and rss_sources per Policy P1.
-- Tracks user subscriptions to both system and custom RSS feeds with temporal tracking (subscribed/unsubscribed).
-- Soft delete pattern via unsubscribed_at preserves historical data for analytics and GDPR export.
-- User subscriptions included in data export per Policy P1.

CREATE TABLE user_feed_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_id UUID NOT NULL REFERENCES rss_sources(id) ON DELETE CASCADE,
    subscribed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    unsubscribed_at TIMESTAMPTZ
);

-- Indexes for common query patterns
CREATE UNIQUE INDEX idx_user_feed_subs_active ON user_feed_subscriptions(user_id, source_id) WHERE unsubscribed_at IS NULL;
CREATE INDEX idx_user_feed_subs_user ON user_feed_subscriptions(user_id);
CREATE INDEX idx_user_feed_subs_source ON user_feed_subscriptions(source_id);
CREATE INDEX idx_user_feed_subs_unsubscribed ON user_feed_subscriptions(unsubscribed_at) WHERE unsubscribed_at IS NOT NULL;

-- //@UNDO

DROP TABLE IF EXISTS user_feed_subscriptions;
