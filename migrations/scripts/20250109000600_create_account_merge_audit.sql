-- // create_account_merge_audit
-- Creates audit trail table for anonymous-to-authenticated account merges per Policy P1 (GDPR/CCPA compliance).
-- Records explicit consent with timestamp, IP address, user agent, and data summary for 90-day retention.
-- Soft-deleted anonymous records are purged 90 days after consent via scheduled cleanup job.

CREATE TABLE account_merge_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    anonymous_user_id UUID NOT NULL,
    authenticated_user_id UUID NOT NULL,
    merged_data_summary JSONB NOT NULL,
    consent_given BOOLEAN NOT NULL,
    consent_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    consent_policy_version TEXT NOT NULL DEFAULT '1.0',
    ip_address INET NOT NULL,
    user_agent TEXT NOT NULL,
    purge_after TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Foreign key constraints to users table
ALTER TABLE account_merge_audit ADD CONSTRAINT fk_account_merge_anonymous_user
    FOREIGN KEY (anonymous_user_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE account_merge_audit ADD CONSTRAINT fk_account_merge_authenticated_user
    FOREIGN KEY (authenticated_user_id) REFERENCES users(id) ON DELETE CASCADE;

-- Indexes for efficient cleanup job queries and audit lookups
CREATE INDEX idx_account_merge_audit_anonymous_user ON account_merge_audit(anonymous_user_id);
CREATE INDEX idx_account_merge_audit_authenticated_user ON account_merge_audit(authenticated_user_id);
CREATE INDEX idx_account_merge_audit_purge_after ON account_merge_audit(purge_after) WHERE purge_after > NOW();
CREATE INDEX idx_account_merge_audit_consent_timestamp ON account_merge_audit(consent_timestamp DESC);

-- GIN index for merged_data_summary JSONB queries (compliance audits)
CREATE INDEX idx_account_merge_audit_data_summary ON account_merge_audit USING GIN(merged_data_summary);

-- Comment explaining P1 compliance requirements
COMMENT ON TABLE account_merge_audit IS 'Policy P1 compliance: Records explicit user consent for anonymous account merges with 90-day retention. Cleanup job (ACCOUNT_MERGE_CLEANUP) purges soft-deleted anonymous records after purge_after timestamp.';
COMMENT ON COLUMN account_merge_audit.merged_data_summary IS 'JSONB summary of merged data: preferences, layout, topic_subscriptions, etc. Used for compliance audits and data export requests.';
COMMENT ON COLUMN account_merge_audit.purge_after IS 'Soft-deleted anonymous user records are hard-deleted by cleanup job when NOW() > purge_after (typically consent_timestamp + 90 days).';

-- //@UNDO

DROP TABLE IF EXISTS account_merge_audit;
