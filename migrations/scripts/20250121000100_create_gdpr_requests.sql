-- Migration: Create GDPR requests audit table
-- Description: Tracks user data export and deletion requests per Policy P1 (GDPR Article 15/17)
-- Date: 2025-01-21

CREATE TABLE gdpr_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_type TEXT NOT NULL CHECK (request_type IN ('EXPORT', 'DELETION')),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    job_id BIGINT REFERENCES delayed_jobs(id) ON DELETE SET NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    signed_url TEXT,
    signed_url_expires_at TIMESTAMPTZ,
    error_message TEXT,
    ip_address INET NOT NULL,
    user_agent TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for common query patterns
CREATE INDEX idx_gdpr_requests_user_id ON gdpr_requests(user_id);
CREATE INDEX idx_gdpr_requests_status ON gdpr_requests(status);
CREATE INDEX idx_gdpr_requests_requested_at ON gdpr_requests(requested_at DESC);
CREATE INDEX idx_gdpr_requests_job_id ON gdpr_requests(job_id) WHERE job_id IS NOT NULL;

-- Trigger to update updated_at timestamp
CREATE TRIGGER trigger_gdpr_requests_updated_at
    BEFORE UPDATE ON gdpr_requests
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Policy P1 comments
COMMENT ON TABLE gdpr_requests IS 'Audit trail for GDPR Article 15 (data export) and Article 17 (erasure) requests';
COMMENT ON COLUMN gdpr_requests.request_type IS 'EXPORT for Article 15 data portability, DELETION for Article 17 right to erasure';
COMMENT ON COLUMN gdpr_requests.status IS 'PENDING (enqueued), PROCESSING (job running), COMPLETED (success), FAILED (error)';
COMMENT ON COLUMN gdpr_requests.signed_url IS 'R2 signed URL for export download (exports only, 7-day TTL)';
COMMENT ON COLUMN gdpr_requests.ip_address IS 'IPv4/IPv6 address of request origin for audit compliance';
COMMENT ON COLUMN gdpr_requests.user_agent IS 'Browser/client user agent string for audit compliance';
