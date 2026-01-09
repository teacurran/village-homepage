-- // enhance_feature_flags_schema
-- Extends the bootstrap feature_flags table with cohort evaluation, whitelist, analytics, and audit logging per Policy P7 & P14.
-- This migration enhances the I1 baseline to support stable cohort hashing, GDPR-compliant evaluation logging, and admin audit trails.

-- Rename the old table to preserve any manual edits
ALTER TABLE IF EXISTS feature_flags RENAME TO feature_flags_old;

-- Create the enhanced feature_flags table with full I2.T2 schema
CREATE TABLE feature_flags (
    flag_key TEXT PRIMARY KEY,
    description TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    rollout_percentage SMALLINT NOT NULL DEFAULT 0 CHECK (rollout_percentage BETWEEN 0 AND 100),
    whitelist JSONB NOT NULL DEFAULT '[]'::jsonb,
    analytics_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_feature_flags_enabled ON feature_flags(enabled);
CREATE INDEX idx_feature_flags_whitelist ON feature_flags USING GIN(whitelist);

-- Migrate existing data from old table (map 'name' to 'flag_key')
INSERT INTO feature_flags (flag_key, description, enabled, rollout_percentage, created_at, updated_at)
SELECT name, description, enabled, rollout_percentage, created_at, updated_at
FROM feature_flags_old
ON CONFLICT (flag_key) DO UPDATE
    SET description = EXCLUDED.description,
        enabled = EXCLUDED.enabled,
        rollout_percentage = EXCLUDED.rollout_percentage,
        updated_at = NOW();

-- Drop the old table now that migration is complete
DROP TABLE IF EXISTS feature_flags_old;

-- Seed initial flags if not already present
INSERT INTO feature_flags (flag_key, description, enabled, rollout_percentage, whitelist, analytics_enabled)
VALUES
    ('stocks_widget', 'Display realtime stock data from Alpha Vantage', false, 0, '[]'::jsonb, true),
    ('social_integration', 'Enable Instagram/Facebook feed ingestion', false, 0, '[]'::jsonb, true),
    ('promoted_listings', 'Allow paid marketplace listing promotions', false, 0, '[]'::jsonb, true)
ON CONFLICT (flag_key) DO UPDATE
    SET description = EXCLUDED.description,
        updated_at = NOW();

-- Create audit table for flag mutation traceability (Policy P14 compliance)
CREATE TABLE feature_flag_audit (
    id BIGSERIAL PRIMARY KEY,
    flag_key TEXT NOT NULL,
    actor_id BIGINT,
    actor_type TEXT NOT NULL CHECK (actor_type IN ('user', 'system', 'admin')),
    action TEXT NOT NULL CHECK (action IN ('create', 'update', 'delete', 'toggle')),
    before_state JSONB,
    after_state JSONB NOT NULL,
    reason TEXT,
    trace_id TEXT,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_feature_flag_audit_flag_key ON feature_flag_audit(flag_key);
CREATE INDEX idx_feature_flag_audit_timestamp ON feature_flag_audit(timestamp DESC);
CREATE INDEX idx_feature_flag_audit_actor ON feature_flag_audit(actor_id, actor_type);

-- Create partitioned evaluation log table (90-day retention per Policy P14)
CREATE TABLE feature_flag_evaluations (
    flag_key TEXT NOT NULL,
    subject_type TEXT NOT NULL CHECK (subject_type IN ('user', 'session')),
    subject_id TEXT NOT NULL,
    result BOOLEAN NOT NULL,
    consent_granted BOOLEAN NOT NULL,
    rollout_percentage_snapshot SMALLINT NOT NULL,
    evaluation_reason TEXT,
    trace_id TEXT,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()
) PARTITION BY RANGE (timestamp);

-- Create index template for partitions
CREATE INDEX idx_feature_flag_eval_flag_key ON feature_flag_evaluations(flag_key, timestamp DESC);
CREATE INDEX idx_feature_flag_eval_subject ON feature_flag_evaluations(subject_type, subject_id, timestamp DESC);
CREATE INDEX idx_feature_flag_eval_timestamp ON feature_flag_evaluations(timestamp DESC);

-- Create first partition for current month (example: January 2025)
CREATE TABLE feature_flag_evaluations_2025_01 PARTITION OF feature_flag_evaluations
    FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

-- Create partition for February 2025
CREATE TABLE feature_flag_evaluations_2025_02 PARTITION OF feature_flag_evaluations
    FOR VALUES FROM ('2025-02-01') TO ('2025-03-01');

-- Note: Additional partitions should be created via scheduled job or manual migration
-- Retention policy: Drop partitions older than 90 days via scheduled cleanup job

-- //@UNDO

DROP TABLE IF EXISTS feature_flag_evaluations_2025_01;
DROP TABLE IF EXISTS feature_flag_evaluations_2025_02;
DROP TABLE IF EXISTS feature_flag_evaluations;
DROP TABLE IF EXISTS feature_flag_audit;
DROP TABLE IF EXISTS feature_flags;

-- Restore old table if needed
-- ALTER TABLE feature_flags_old RENAME TO feature_flags;
