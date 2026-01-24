--// Create email_bounces table for bounce tracking
--// Migration: 20260123000700
--// Feature: I5.T5 - Email Bounce Handling

-- Create email_bounces table for tracking email delivery bounces
-- Supports DSN (Delivery Status Notification) parsing and threshold-based email disabling
-- Used by BounceHandlingService to track hard/soft bounces and disable problematic addresses
CREATE TABLE email_bounces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    email_address VARCHAR(255) NOT NULL,
    bounce_type VARCHAR(20) NOT NULL,
    bounce_reason TEXT,
    diagnostic_code VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for email address lookup (threshold queries)
-- Enables fast counting of recent bounces for a given email address
CREATE INDEX idx_email_bounces_email_address ON email_bounces(email_address, created_at DESC);

-- Index for user lookup (admin dashboard, user bounce history)
CREATE INDEX idx_email_bounces_user_id ON email_bounces(user_id);

-- Index for time-based queries (cleanup old bounces, recent bounce counting)
CREATE INDEX idx_email_bounces_created_at ON email_bounces(created_at DESC);

-- Table and column comments for documentation
COMMENT ON TABLE email_bounces IS 'Email bounce tracking with DSN parsing (Feature I5.T5)';
COMMENT ON COLUMN email_bounces.bounce_type IS 'Bounce classification: HARD (5.x.x permanent), SOFT (4.x.x temporary)';
COMMENT ON COLUMN email_bounces.diagnostic_code IS 'DSN status code (e.g., 5.1.1, 4.2.2)';
COMMENT ON COLUMN email_bounces.bounce_reason IS 'Human-readable bounce reason extracted from DSN';
COMMENT ON COLUMN email_bounces.user_id IS 'User FK (nullable for non-user emails like ops alerts)';

--//@UNDO
-- Undo SQL: Drop email_bounces table and indexes

DROP INDEX IF EXISTS idx_email_bounces_created_at;
DROP INDEX IF EXISTS idx_email_bounces_user_id;
DROP INDEX IF EXISTS idx_email_bounces_email_address;
DROP TABLE IF EXISTS email_bounces;
