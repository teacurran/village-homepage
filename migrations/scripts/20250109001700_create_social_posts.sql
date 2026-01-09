--
-- Social posts table for cached Instagram and Facebook content per Policy P5/P13.
--
-- Caches posts for offline display when API unavailable or tokens stale.
-- Supports graceful degradation with staleness banners.
-- 90-day retention after fetch_at timestamp.
--

CREATE TABLE social_posts (
    id BIGSERIAL PRIMARY KEY,
    social_token_id BIGINT NOT NULL REFERENCES social_tokens(id) ON DELETE CASCADE,
    platform VARCHAR(20) NOT NULL CHECK (platform IN ('instagram', 'facebook')),
    platform_post_id VARCHAR(100) NOT NULL,
    post_type VARCHAR(50) NOT NULL CHECK (post_type IN ('image', 'video', 'carousel', 'story', 'reel')),
    caption TEXT,
    media_urls JSONB NOT NULL,         -- Array of media URLs [{url, type, thumbnail_url}]
    posted_at TIMESTAMPTZ NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    engagement_data JSONB,             -- {likes, comments, shares, views}
    is_archived BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for fetching recent posts by token (sorted by posted_at DESC)
CREATE INDEX social_posts_token_posted_idx
    ON social_posts(social_token_id, posted_at DESC);

-- Unique constraint to prevent duplicate posts
CREATE UNIQUE INDEX social_posts_platform_post_idx
    ON social_posts(platform, platform_post_id);

-- Index for cleanup job (find posts older than 90 days)
CREATE INDEX social_posts_fetched_idx
    ON social_posts(fetched_at)
    WHERE is_archived = false;

-- Index for archived posts
CREATE INDEX social_posts_archived_idx
    ON social_posts(is_archived);

COMMENT ON TABLE social_posts IS 'Cached social media posts from Instagram and Facebook. 90-day retention after fetched_at per Policy P5/P13.';
COMMENT ON COLUMN social_posts.platform_post_id IS 'Platform-specific post ID (e.g., Instagram media ID).';
COMMENT ON COLUMN social_posts.media_urls IS 'JSONB array of media URLs with type (image/video) and thumbnail URLs.';
COMMENT ON COLUMN social_posts.engagement_data IS 'JSONB object with likes, comments, shares, views counts.';
COMMENT ON COLUMN social_posts.is_archived IS 'True if post archived due to token expiration >7 days or manual deletion.';
COMMENT ON COLUMN social_posts.fetched_at IS 'When post was last fetched from API. Used for staleness calculation and 90-day TTL.';
