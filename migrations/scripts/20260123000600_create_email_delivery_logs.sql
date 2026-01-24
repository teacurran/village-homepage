--// Create email_delivery_logs table for async email queue
--// Migration: 20260123000600
--// Feature: I5.T3 - Email Notification Service

-- Create email_delivery_logs table for async email delivery queue
-- Supports async email delivery with retry logic and status tracking
-- Used by NotificationService to queue emails and EmailDeliveryJob to process queue
CREATE TABLE email_delivery_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    email_address VARCHAR(255) NOT NULL,
    template_name VARCHAR(100) NOT NULL,
    subject VARCHAR(500) NOT NULL,
    html_body TEXT,
    text_body TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    sent_at TIMESTAMPTZ,
    error_message TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for job query (status = 'QUEUED', ordered by created_at)
-- Enables fast lookup of queued emails for EmailDeliveryJob
CREATE INDEX idx_email_delivery_logs_status ON email_delivery_logs(status, created_at);

-- Index for user lookup (admin dashboard, user email history)
CREATE INDEX idx_email_delivery_logs_user_id ON email_delivery_logs(user_id);

-- Partial index for failed email queries (admin monitoring)
-- Only indexes failed emails to reduce index size
CREATE INDEX idx_email_delivery_logs_failed ON email_delivery_logs(status, retry_count) WHERE status = 'FAILED';

-- Table and column comments for documentation
COMMENT ON TABLE email_delivery_logs IS 'Queue for async email delivery with retry support (Feature I5.T3)';
COMMENT ON COLUMN email_delivery_logs.status IS 'Delivery status: QUEUED, SENDING, SENT, FAILED, BOUNCED';
COMMENT ON COLUMN email_delivery_logs.retry_count IS 'Number of retry attempts (max 3)';
COMMENT ON COLUMN email_delivery_logs.user_id IS 'User FK (nullable for ops alerts and system emails)';

--//@UNDO
-- Undo SQL: Drop email_delivery_logs table and indexes

DROP INDEX IF EXISTS idx_email_delivery_logs_failed;
DROP INDEX IF EXISTS idx_email_delivery_logs_user_id;
DROP INDEX IF EXISTS idx_email_delivery_logs_status;
DROP TABLE IF EXISTS email_delivery_logs;
