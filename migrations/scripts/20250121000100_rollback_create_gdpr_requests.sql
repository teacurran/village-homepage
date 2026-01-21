-- Rollback Migration: Drop GDPR requests audit table
-- Date: 2025-01-21

DROP TRIGGER IF EXISTS trigger_gdpr_requests_updated_at ON gdpr_requests;
DROP INDEX IF EXISTS idx_gdpr_requests_job_id;
DROP INDEX IF EXISTS idx_gdpr_requests_requested_at;
DROP INDEX IF EXISTS idx_gdpr_requests_status;
DROP INDEX IF EXISTS idx_gdpr_requests_user_id;
DROP TABLE IF EXISTS gdpr_requests;
