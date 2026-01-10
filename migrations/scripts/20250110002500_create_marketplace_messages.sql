-- Migration: Create marketplace_messages table for email relay tracking
-- Purpose: Store masked email relay messages for marketplace listings (Feature F12.6, F14.3)
-- Author: Claude Code
-- Date: 2025-01-10
--
-- Policy References:
-- - F12.6: Email masking for contact relay (buyer/seller communication)
-- - F14.3: IMAP polling for marketplace reply relay
-- - P1: GDPR compliance - CASCADE delete when listing deleted
-- - P6: Privacy via masked email relay

--changeset claude:20250110002500-create-marketplace-messages
--comment: Create marketplace_messages table for tracking email relay messages between buyers and sellers

CREATE TABLE marketplace_messages (
    -- Primary key
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Foreign key to listing (CASCADE delete for GDPR compliance P1)
    listing_id UUID NOT NULL REFERENCES marketplace_listings(id) ON DELETE CASCADE,

    -- Email metadata for threading
    message_id TEXT NOT NULL UNIQUE,  -- e.g., "msg-abc123@villagecompute.com"
    in_reply_to TEXT,  -- Parent message ID for threading (nullable for initial inquiries)
    thread_id UUID,  -- Group related messages in same conversation

    -- Participants (real email addresses, never masked)
    from_email TEXT NOT NULL,
    from_name TEXT,
    to_email TEXT NOT NULL,
    to_name TEXT,

    -- Content
    subject TEXT NOT NULL,
    body TEXT NOT NULL,

    -- Direction tracking (buyer→seller or seller→buyer)
    direction TEXT NOT NULL CHECK (direction IN ('buyer_to_seller', 'seller_to_buyer')),

    -- Audit fields
    sent_at TIMESTAMPTZ,  -- When email was successfully relayed (nullable if send failed)
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Spam/moderation flags (future I4.T8 moderation integration)
    is_spam BOOLEAN DEFAULT false,
    spam_score DECIMAL(3,2) CHECK (spam_score IS NULL OR (spam_score >= 0 AND spam_score <= 1)),  -- 0.00 to 1.00
    flagged_for_review BOOLEAN DEFAULT false
);

-- Indexes for query performance
CREATE INDEX idx_messages_listing_id ON marketplace_messages(listing_id);
CREATE INDEX idx_messages_thread_id ON marketplace_messages(thread_id) WHERE thread_id IS NOT NULL;
CREATE INDEX idx_messages_message_id ON marketplace_messages(message_id);  -- For IMAP reply lookup
CREATE INDEX idx_messages_created_at ON marketplace_messages(created_at DESC);  -- For admin listing views
CREATE INDEX idx_messages_flagged ON marketplace_messages(flagged_for_review) WHERE flagged_for_review = true;

-- Comments for documentation
COMMENT ON TABLE marketplace_messages IS 'Email relay messages for marketplace listings (F12.6, F14.3). Stores buyer-seller communication via masked email relay. CASCADE deletes when listing deleted (P1 GDPR).';
COMMENT ON COLUMN marketplace_messages.message_id IS 'Unique email Message-ID header (e.g., msg-{uuid}@villagecompute.com) for threading and reply tracking.';
COMMENT ON COLUMN marketplace_messages.in_reply_to IS 'Email In-Reply-To header - references parent message_id for threading.';
COMMENT ON COLUMN marketplace_messages.thread_id IS 'Groups related messages in same conversation for UI display.';
COMMENT ON COLUMN marketplace_messages.direction IS 'Message flow direction: buyer_to_seller (initial inquiry) or seller_to_buyer (reply to inquiry).';
COMMENT ON COLUMN marketplace_messages.spam_score IS 'AI-based spam probability score 0.00-1.00 (future feature, currently NULL).';
COMMENT ON COLUMN marketplace_messages.flagged_for_review IS 'Manual moderation flag for abusive/spam messages (I4.T8 integration).';

--rollback DROP TABLE marketplace_messages;
