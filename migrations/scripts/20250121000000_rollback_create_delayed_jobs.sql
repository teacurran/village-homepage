-- Rollback Migration: Drop delayed_jobs table
-- Date: 2025-01-21

DROP TRIGGER IF EXISTS trigger_delayed_jobs_updated_at ON delayed_jobs;
DROP INDEX IF EXISTS idx_delayed_jobs_scheduled_at;
DROP INDEX IF EXISTS idx_delayed_jobs_job_type;
DROP INDEX IF EXISTS idx_delayed_jobs_status;
DROP INDEX IF EXISTS idx_delayed_jobs_ready;
DROP TABLE IF EXISTS delayed_jobs;
