-- // create_impersonation_audit
-- Creates audit trail table for admin user impersonation events per Section 3.7.1.
-- All impersonation actions are logged with source/destination user IDs, IP, and user agent
-- for investigative tracing and compliance with admin action audit requirements.
--
-- This table supports Task I2.T8 impersonation guard rails implementation.

CREATE TABLE impersonation_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    impersonator_id UUID NOT NULL REFERENCES users(id),
    target_user_id UUID NOT NULL REFERENCES users(id),
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at TIMESTAMPTZ,
    ip_address TEXT,
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for queries by impersonator (who did the impersonation)
CREATE INDEX idx_impersonation_impersonator ON impersonation_audit(impersonator_id, started_at DESC);

-- Index for queries by target (who was impersonated)
CREATE INDEX idx_impersonation_target ON impersonation_audit(target_user_id, started_at DESC);

-- Index for finding active impersonation sessions
CREATE INDEX idx_impersonation_active ON impersonation_audit(started_at DESC) WHERE ended_at IS NULL;

-- Index for audit log queries with time range filtering
CREATE INDEX idx_impersonation_created_at ON impersonation_audit(created_at DESC);

-- //@UNDO

DROP TABLE IF EXISTS impersonation_audit;
