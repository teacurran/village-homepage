-- Migration: Create delayed_jobs table for async job orchestration
-- Description: Database-backed job queue for Policy P7 unified job framework
-- Date: 2025-01-21

CREATE TABLE delayed_jobs (
    id BIGSERIAL PRIMARY KEY,
    job_type TEXT NOT NULL,
    queue TEXT NOT NULL CHECK (queue IN ('DEFAULT', 'HIGH', 'LOW', 'BULK', 'SCREENSHOT')),
    priority INT NOT NULL DEFAULT 0,
    payload JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    scheduled_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    locked_at TIMESTAMPTZ,
    locked_by TEXT,
    completed_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for worker polling and monitoring
CREATE INDEX idx_delayed_jobs_ready ON delayed_jobs(queue, priority DESC, scheduled_at ASC)
    WHERE status = 'PENDING' AND scheduled_at <= NOW() AND (locked_at IS NULL OR locked_at < NOW() - INTERVAL '5 minutes');
CREATE INDEX idx_delayed_jobs_status ON delayed_jobs(status);
CREATE INDEX idx_delayed_jobs_job_type ON delayed_jobs(job_type);
CREATE INDEX idx_delayed_jobs_scheduled_at ON delayed_jobs(scheduled_at);

-- Trigger to update updated_at timestamp
CREATE TRIGGER trigger_delayed_jobs_updated_at
    BEFORE UPDATE ON delayed_jobs
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Policy P7 comments
COMMENT ON TABLE delayed_jobs IS 'Unified async job queue for all background operations (Policy P7)';
COMMENT ON COLUMN delayed_jobs.job_type IS 'JobType enum value (RSS_FEED_REFRESH, GDPR_EXPORT, etc)';
COMMENT ON COLUMN delayed_jobs.queue IS 'JobQueue family (DEFAULT, HIGH, LOW, BULK, SCREENSHOT)';
COMMENT ON COLUMN delayed_jobs.priority IS 'Within-queue priority (higher = more urgent)';
COMMENT ON COLUMN delayed_jobs.payload IS 'Job parameters as JSONB (deserialized by handler)';
COMMENT ON COLUMN delayed_jobs.scheduled_at IS 'Earliest execution time (used for retry backoff)';
COMMENT ON COLUMN delayed_jobs.locked_at IS 'Timestamp when worker claimed job (distributed locking)';
COMMENT ON COLUMN delayed_jobs.locked_by IS 'Worker identifier (hostname:pid)';
