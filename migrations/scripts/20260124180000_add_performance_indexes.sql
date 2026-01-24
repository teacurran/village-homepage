-- Migration: Add performance indexes for query optimization
-- Task: I6.T6 - Database Performance Optimization
-- Description: Add strategic indexes to improve query performance across the application
-- Author: Claude Code
-- Date: 2026-01-24
--
-- Policy References:
-- - P11: Query performance targets <100ms for typical filters
-- - P7: Background job query optimization
-- - P14: Feature flag evaluation performance
--
-- Index Strategy:
-- - Foreign key indexes for JOIN performance
-- - Composite indexes for filter+sort patterns
-- - Partial indexes for hot data subsets
-- - Temporal indexes for date-based queries
--
-- NOTE: Many tables already have comprehensive indexing from previous iterations (I4.T5).
-- This migration adds MISSING indexes only, targeting:
-- - Delayed jobs history queries
-- - Feature flag evaluation lookups
-- - Weather cache lookups
-- - User feed subscription queries
-- - Account merge audit queries
-- - OAuth state cleanup queries

--changeset claude:20260124180000-add-performance-indexes
--comment: Add performance indexes for query optimization (Task I6.T6)

-- ============================================================================
-- DELAYED JOBS - History and Monitoring Queries
-- ============================================================================
-- Existing indexes cover polling (idx_delayed_jobs_ready), but missing
-- indexes for completed/failed job history queries used in admin dashboard

-- Completed jobs query: Recent completions for monitoring
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_delayed_jobs_completed_at
ON delayed_jobs(completed_at DESC)
WHERE status = 'COMPLETED' AND completed_at IS NOT NULL;

COMMENT ON INDEX idx_delayed_jobs_completed_at IS
'Partial index for completed job history queries. Supports admin dashboard "recent completions" view. '
'Partial WHERE clause reduces index size by excluding in-flight/failed jobs.';

-- Failed jobs query: Error monitoring and retry analysis
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_delayed_jobs_failed_at
ON delayed_jobs(failed_at DESC, job_type)
WHERE status = 'FAILED' AND failed_at IS NOT NULL;

COMMENT ON INDEX idx_delayed_jobs_failed_at IS
'Partial index for failed job monitoring. Supports admin dashboard error tracking and job type filtering. '
'Includes job_type column for filtering by specific job types in failed job queries.';

-- Job type history query: Track job type performance over time
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_delayed_jobs_type_created
ON delayed_jobs(job_type, created_at DESC);

COMMENT ON INDEX idx_delayed_jobs_type_created IS
'Composite index for job type history queries. Supports filtering by job_type with date sorting. '
'Used in analytics dashboards to track job throughput per type.';

-- ============================================================================
-- FEATURE FLAGS - Evaluation Cache Optimization
-- ============================================================================
-- Feature flag evaluations need fast lookups by flag_key + subject_id
-- for cohort assignment validation and audit queries

-- Feature flag evaluations lookup: Check if subject evaluated flag
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_feature_flag_eval_key_subject
ON feature_flag_evaluations(flag_key, subject_id, evaluated_at DESC);

COMMENT ON INDEX idx_feature_flag_eval_key_subject IS
'Composite index for feature flag evaluation lookups. Supports queries filtering by flag_key and subject_id. '
'Enables fast cohort assignment checks and audit trail queries. Includes evaluated_at for recent evaluation queries.';

-- Feature flag audit query: Track flag config changes
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_feature_flag_audit_key_changed
ON feature_flag_audit(flag_key, changed_at DESC);

COMMENT ON INDEX idx_feature_flag_audit_key_changed IS
'Composite index for feature flag audit trail. Supports filtering by flag_key with chronological sorting. '
'Used in admin UI to display flag change history.';

-- ============================================================================
-- WEATHER CACHE - Location-Based Lookups
-- ============================================================================
-- Weather cache needs fast lookups by location coordinates hash
-- Existing expires_at index covers cleanup, but missing location hash lookup

-- Weather cache location lookup: Find cached weather by location
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_weather_cache_location_hash
ON weather_cache(location_hash, fetched_at DESC)
WHERE expires_at > NOW();

COMMENT ON INDEX idx_weather_cache_location_hash IS
'Partial index for weather cache lookups by location. Supports finding fresh weather data by location_hash. '
'Partial WHERE clause includes only unexpired cache entries (hot data). '
'Includes fetched_at for staleness checks.';

-- ============================================================================
-- USER FEED SUBSCRIPTIONS - Personalized Feed Queries
-- ============================================================================
-- Feed subscriptions need efficient user_id lookups for feed aggregation
-- Existing foreign key has no index

-- User feed subscription lookup: Get user's active subscriptions
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_user_feed_sub_user_active
ON user_feed_subscriptions(user_id, is_active, created_at DESC);

COMMENT ON INDEX idx_user_feed_sub_user_active IS
'Composite index for user subscription queries. Supports filtering by user_id and is_active status. '
'Includes created_at for chronological ordering in subscription management UI.';

-- RSS source subscription count: Count active subscribers per source
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_user_feed_sub_source_active
ON user_feed_subscriptions(rss_source_id, is_active);

COMMENT ON INDEX idx_user_feed_sub_source_active IS
'Composite index for RSS source subscription counts. Supports aggregating active subscribers per source. '
'Used in admin dashboard to identify popular sources.';

-- ============================================================================
-- ACCOUNT MERGE AUDIT - User Merge History
-- ============================================================================
-- Account merge audit needs efficient lookups by source/target user IDs
-- for merge history queries

-- Source user merge history: Find accounts merged FROM this user
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_account_merge_source_user
ON account_merge_audit(source_user_id, merged_at DESC);

COMMENT ON INDEX idx_account_merge_source_user IS
'Index for source user merge history. Supports finding all accounts merged FROM a specific user. '
'Used in user detail pages to display merge history.';

-- Target user merge history: Find accounts merged INTO this user
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_account_merge_target_user
ON account_merge_audit(target_user_id, merged_at DESC);

COMMENT ON INDEX idx_account_merge_target_user IS
'Index for target user merge history. Supports finding all accounts merged INTO a specific user. '
'Used in user detail pages and admin audit queries.';

-- ============================================================================
-- OAUTH STATE - Cleanup and Security
-- ============================================================================
-- OAuth state table needs efficient cleanup of expired state tokens
-- and validation lookups by state token

-- OAuth state cleanup: Find expired state tokens for deletion
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_oauth_state_expires
ON oauth_state(expires_at)
WHERE expires_at <= NOW();

COMMENT ON INDEX idx_oauth_state_expires IS
'Partial index for OAuth state cleanup job. Includes only expired state tokens for efficient deletion. '
'Background job runs hourly to clean up expired OAuth state tokens.';

-- OAuth state validation: Fast lookup by state token
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_oauth_state_token
ON oauth_state(state_token)
WHERE used_at IS NULL AND expires_at > NOW();

COMMENT ON INDEX idx_oauth_state_token IS
'Partial index for OAuth state validation. Supports fast lookup by state_token during OAuth callback. '
'Partial WHERE clause includes only valid, unused tokens (hot data).';

-- ============================================================================
-- IMPERSONATION AUDIT - Security Monitoring
-- ============================================================================
-- Impersonation audit needs efficient lookups by admin_user_id and target_user_id
-- for security monitoring and compliance reporting

-- Admin impersonation history: Track impersonations BY admin
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_impersonation_admin_user
ON impersonation_audit(admin_user_id, started_at DESC);

COMMENT ON INDEX idx_impersonation_admin_user IS
'Index for admin impersonation history. Supports finding all impersonations performed BY a specific admin. '
'Used in security audits and compliance reporting.';

-- Target impersonation history: Track impersonations OF user
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_impersonation_target_user
ON impersonation_audit(target_user_id, started_at DESC);

COMMENT ON INDEX idx_impersonation_target_user IS
'Index for target user impersonation history. Supports finding all impersonations OF a specific user. '
'Used in user security audit trails.';

-- Active impersonations: Find currently active impersonation sessions
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_impersonation_active
ON impersonation_audit(admin_user_id, started_at DESC)
WHERE ended_at IS NULL;

COMMENT ON INDEX idx_impersonation_active IS
'Partial index for active impersonation sessions. Supports finding ongoing impersonations by admin. '
'Used in admin dashboard to display active impersonation sessions.';

-- ============================================================================
-- RATE LIMITS - Enforcement Queries
-- ============================================================================
-- Rate limits table needs efficient lookups by identifier for rate limit checks

-- Rate limit enforcement: Fast lookup by identifier
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_rate_limits_identifier
ON rate_limits(identifier, action_type, window_start DESC);

COMMENT ON INDEX idx_rate_limits_identifier IS
'Composite index for rate limit enforcement. Supports filtering by identifier and action_type. '
'Includes window_start for finding active rate limit windows. '
'Critical for <10ms rate limit check performance.';

-- Rate limit cleanup: Find expired rate limit windows
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_rate_limits_window_start
ON rate_limits(window_start)
WHERE window_start < NOW() - INTERVAL '1 day';

COMMENT ON INDEX idx_rate_limits_window_start IS
'Partial index for rate limit cleanup. Includes only expired windows (>24 hours old). '
'Background job runs daily to clean up old rate limit records.';

-- ============================================================================
-- AI USAGE TRACKING - Cost Monitoring
-- ============================================================================
-- AI usage tracking needs efficient aggregation by user_id and date
-- for cost monitoring and analytics

-- User AI usage query: Track AI costs by user
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ai_usage_user_created
ON ai_usage_tracking(user_id, created_at DESC)
WHERE user_id IS NOT NULL;

COMMENT ON INDEX idx_ai_usage_user_created IS
'Partial index for user AI usage queries. Supports filtering by user_id with date sorting. '
'Used in admin dashboard for per-user AI cost analysis. '
'Excludes system AI usage (user_id IS NULL) to reduce index size.';

-- Daily AI usage aggregation: Track AI costs by date
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ai_usage_date
ON ai_usage_tracking(created_at::date, model_name);

COMMENT ON INDEX idx_ai_usage_date IS
'Composite index for daily AI usage aggregation. Supports GROUP BY date queries with model breakdown. '
'Cast to date enables efficient daily rollup queries for cost monitoring.';

-- ============================================================================
-- RESERVED USERNAMES - Validation Lookups
-- ============================================================================
-- Reserved usernames table needs fast validation during username registration

-- Username reservation check: Validate username during registration
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_reserved_usernames_lower
ON reserved_usernames(LOWER(username));

COMMENT ON INDEX idx_reserved_usernames_lower IS
'Case-insensitive index for username reservation checks. Supports fast lookup during user registration. '
'LOWER() function ensures case-insensitive matching (e.g., "Admin" = "admin").';

-- ============================================================================
-- PROFILE CURATED ARTICLES - User Profile Queries
-- ============================================================================
-- Profile curated articles need efficient lookups by user_profile_id

-- User profile articles: Get articles for profile display
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_profile_articles_profile_order
ON profile_curated_articles(user_profile_id, display_order ASC);

COMMENT ON INDEX idx_profile_articles_profile_order IS
'Composite index for user profile article queries. Supports filtering by user_profile_id with custom ordering. '
'Enables fast retrieval of curated articles in display order for profile pages.';

-- ============================================================================
-- ANALYSIS COMMANDS
-- ============================================================================
-- Update query planner statistics after adding indexes

ANALYZE delayed_jobs;
ANALYZE feature_flag_evaluations;
ANALYZE feature_flag_audit;
ANALYZE weather_cache;
ANALYZE user_feed_subscriptions;
ANALYZE account_merge_audit;
ANALYZE oauth_state;
ANALYZE impersonation_audit;
ANALYZE rate_limits;
ANALYZE ai_usage_tracking;
ANALYZE reserved_usernames;
ANALYZE profile_curated_articles;

-- //@UNDO
-- Drop all indexes created by this migration

DROP INDEX IF EXISTS idx_delayed_jobs_completed_at;
DROP INDEX IF EXISTS idx_delayed_jobs_failed_at;
DROP INDEX IF EXISTS idx_delayed_jobs_type_created;
DROP INDEX IF EXISTS idx_feature_flag_eval_key_subject;
DROP INDEX IF EXISTS idx_feature_flag_audit_key_changed;
DROP INDEX IF EXISTS idx_weather_cache_location_hash;
DROP INDEX IF EXISTS idx_user_feed_sub_user_active;
DROP INDEX IF EXISTS idx_user_feed_sub_source_active;
DROP INDEX IF EXISTS idx_account_merge_source_user;
DROP INDEX IF EXISTS idx_account_merge_target_user;
DROP INDEX IF EXISTS idx_oauth_state_expires;
DROP INDEX IF EXISTS idx_oauth_state_token;
DROP INDEX IF EXISTS idx_impersonation_admin_user;
DROP INDEX IF EXISTS idx_impersonation_target_user;
DROP INDEX IF EXISTS idx_impersonation_active;
DROP INDEX IF EXISTS idx_rate_limits_identifier;
DROP INDEX IF EXISTS idx_rate_limits_window_start;
DROP INDEX IF EXISTS idx_ai_usage_user_created;
DROP INDEX IF EXISTS idx_ai_usage_date;
DROP INDEX IF EXISTS idx_reserved_usernames_lower;
DROP INDEX IF EXISTS idx_profile_articles_profile_order;
