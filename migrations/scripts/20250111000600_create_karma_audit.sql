-- Description: Create karma audit table for tracking directory karma adjustments
-- Author: Code Machine
-- Date: 2026-01-10

-- Create karma_audit table to track all karma changes with full audit trail
CREATE TABLE karma_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    old_karma INT NOT NULL,
    new_karma INT NOT NULL,
    delta INT NOT NULL,
    old_trust_level TEXT NOT NULL,
    new_trust_level TEXT NOT NULL,
    reason TEXT NOT NULL,
    trigger_type TEXT NOT NULL CHECK (trigger_type IN ('submission_approved', 'submission_rejected', 'vote_received', 'admin_adjustment', 'system_adjustment')),
    trigger_entity_type TEXT CHECK (trigger_entity_type IN ('site_category', 'vote', 'manual')),
    trigger_entity_id UUID,
    adjusted_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for efficient querying
CREATE INDEX idx_karma_audit_user_id ON karma_audit(user_id);
CREATE INDEX idx_karma_audit_created_at ON karma_audit(created_at DESC);
CREATE INDEX idx_karma_audit_trigger_type ON karma_audit(trigger_type);
CREATE INDEX idx_karma_audit_adjusted_by ON karma_audit(adjusted_by_user_id) WHERE adjusted_by_user_id IS NOT NULL;

-- Composite index for user history queries
CREATE INDEX idx_karma_audit_user_created ON karma_audit(user_id, created_at DESC);

COMMENT ON TABLE karma_audit IS 'Audit trail for all directory karma adjustments';
COMMENT ON COLUMN karma_audit.user_id IS 'User whose karma was adjusted';
COMMENT ON COLUMN karma_audit.old_karma IS 'Karma value before adjustment';
COMMENT ON COLUMN karma_audit.new_karma IS 'Karma value after adjustment';
COMMENT ON COLUMN karma_audit.delta IS 'Change in karma (can be positive or negative)';
COMMENT ON COLUMN karma_audit.old_trust_level IS 'Trust level before adjustment';
COMMENT ON COLUMN karma_audit.new_trust_level IS 'Trust level after adjustment';
COMMENT ON COLUMN karma_audit.reason IS 'Human-readable reason for karma adjustment';
COMMENT ON COLUMN karma_audit.trigger_type IS 'Type of event that triggered the adjustment';
COMMENT ON COLUMN karma_audit.trigger_entity_type IS 'Type of entity that triggered the adjustment (if applicable)';
COMMENT ON COLUMN karma_audit.trigger_entity_id IS 'ID of entity that triggered the adjustment (if applicable)';
COMMENT ON COLUMN karma_audit.adjusted_by_user_id IS 'User ID of admin who made manual adjustment (null for automatic)';
COMMENT ON COLUMN karma_audit.metadata IS 'Additional context about the adjustment (JSON)';
