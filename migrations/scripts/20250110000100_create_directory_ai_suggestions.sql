--liquibase formatted sql

--changeset village:create_directory_ai_suggestions
--comment Creates table for AI-suggested category assignments during bulk import with admin review workflow

CREATE TABLE directory_ai_suggestions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    url TEXT NOT NULL,
    domain TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    og_image_url TEXT,

    -- AI-suggested categories (array of category IDs)
    suggested_category_ids UUID[],

    -- AI reasoning for category selection
    reasoning TEXT,

    -- Confidence score (0.0-1.0)
    confidence NUMERIC(3,2),

    -- Admin override (if different from AI suggestion)
    admin_selected_category_ids UUID[],

    -- Status: pending, approved, rejected
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),

    -- Who uploaded/approved
    uploaded_by_user_id UUID NOT NULL REFERENCES users(id),
    reviewed_by_user_id UUID REFERENCES users(id),

    -- Tokens/cost tracking
    tokens_input BIGINT,
    tokens_output BIGINT,
    estimated_cost_cents INT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_directory_ai_suggestions_status ON directory_ai_suggestions(status);
CREATE INDEX idx_directory_ai_suggestions_uploaded_by ON directory_ai_suggestions(uploaded_by_user_id);
CREATE INDEX idx_directory_ai_suggestions_url ON directory_ai_suggestions(url);
CREATE INDEX idx_directory_ai_suggestions_created_at ON directory_ai_suggestions(created_at DESC);

COMMENT ON TABLE directory_ai_suggestions IS 'AI-suggested category assignments for bulk-imported Good Sites, pending admin review per Feature F13.14';
COMMENT ON COLUMN directory_ai_suggestions.suggested_category_ids IS 'AI-recommended category IDs (1-3 categories)';
COMMENT ON COLUMN directory_ai_suggestions.reasoning IS 'AI explanation for category selections (audit trail for training data)';
COMMENT ON COLUMN directory_ai_suggestions.confidence IS 'AI confidence score (0.0-1.0) for suggestion quality';
COMMENT ON COLUMN directory_ai_suggestions.admin_selected_category_ids IS 'Admin-selected category IDs if overriding AI suggestion';
COMMENT ON COLUMN directory_ai_suggestions.tokens_input IS 'LangChain4j input token count for budget tracking';
COMMENT ON COLUMN directory_ai_suggestions.tokens_output IS 'LangChain4j output token count for budget tracking';
COMMENT ON COLUMN directory_ai_suggestions.estimated_cost_cents IS 'Estimated cost in cents for this categorization';

--rollback DROP TABLE directory_ai_suggestions;
