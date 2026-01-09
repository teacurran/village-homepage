--
-- Migration: Add rate limit configurations for user preference endpoints
-- Author: Claude Code
-- Date: 2026-01-09
-- Task: I2.T5 - User Preferences Service
--
-- Description:
-- Adds rate limit configurations for preferences_read and preferences_update actions
-- across all three user tiers (anonymous, logged_in, trusted).
--
-- Rate limits:
-- - Anonymous users: 10 reads/hour, 5 updates/hour (strict limits)
-- - Logged-in users: 100 reads/hour, 50 updates/hour (moderate limits)
-- - Trusted users (karma >= 10): 500 reads/hour, 200 updates/hour (generous limits)
--

-- // Up Migration

-- Preferences read rate limits
INSERT INTO rate_limit_config (id, action_type, tier, limit_count, window_seconds, updated_by_user_id, updated_at)
VALUES
    (gen_random_uuid(), 'preferences_read', 'anonymous', 10, 3600, NULL, NOW()),
    (gen_random_uuid(), 'preferences_read', 'logged_in', 100, 3600, NULL, NOW()),
    (gen_random_uuid(), 'preferences_read', 'trusted', 500, 3600, NULL, NOW());

-- Preferences update rate limits (stricter than reads)
INSERT INTO rate_limit_config (id, action_type, tier, limit_count, window_seconds, updated_by_user_id, updated_at)
VALUES
    (gen_random_uuid(), 'preferences_update', 'anonymous', 5, 3600, NULL, NOW()),
    (gen_random_uuid(), 'preferences_update', 'logged_in', 50, 3600, NULL, NOW()),
    (gen_random_uuid(), 'preferences_update', 'trusted', 200, 3600, NULL, NOW());

COMMENT ON TABLE rate_limit_config IS 'Rate limit configurations for preferences_read and preferences_update actions added by migration 20250109000700';

-- // Down Migration

DELETE FROM rate_limit_config WHERE action_type IN ('preferences_read', 'preferences_update');
