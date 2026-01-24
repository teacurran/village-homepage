--// Add email_disabled field to users table for bounce handling
--// Migration: 20260123000800
--// Feature: I5.T5 - Email Bounce Handling

-- Add email_disabled flag to users table
-- Set to true when email address has excessive bounces (hard bounce or 5 consecutive soft)
-- Prevents further email delivery attempts to invalid/problematic addresses
ALTER TABLE users
ADD COLUMN email_disabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Comments
COMMENT ON COLUMN users.email_disabled IS 'Email delivery disabled due to bounces (hard bounce or 5+ consecutive soft bounces)';

--//@UNDO
-- Undo SQL: Remove email_disabled column

ALTER TABLE users DROP COLUMN IF EXISTS email_disabled;
