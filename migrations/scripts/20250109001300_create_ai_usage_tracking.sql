--
-- Copyright 2025 VillageCompute Inc.
--
-- SPDX-License-Identifier: Apache-2.0
--

-- // Create AI usage tracking table for budget enforcement (P2/P10)
-- Migration SQL that makes the change goes here.

CREATE TABLE ai_usage_tracking (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    month DATE NOT NULL,
    provider TEXT NOT NULL DEFAULT 'anthropic',
    total_requests INT NOT NULL DEFAULT 0,
    total_tokens_input BIGINT NOT NULL DEFAULT 0,
    total_tokens_output BIGINT NOT NULL DEFAULT 0,
    estimated_cost_cents INT NOT NULL DEFAULT 0,
    budget_limit_cents INT NOT NULL DEFAULT 50000,  -- $500 monthly limit
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(month, provider)
);

CREATE INDEX idx_ai_usage_tracking_month ON ai_usage_tracking(month DESC);

COMMENT ON TABLE ai_usage_tracking IS 'Tracks AI API usage and enforces budget limits per P2/P10 policies';
COMMENT ON COLUMN ai_usage_tracking.month IS 'First day of the month for this usage period';
COMMENT ON COLUMN ai_usage_tracking.provider IS 'AI provider (anthropic, openai, etc.)';
COMMENT ON COLUMN ai_usage_tracking.total_requests IS 'Total number of API requests made';
COMMENT ON COLUMN ai_usage_tracking.total_tokens_input IS 'Total input tokens consumed';
COMMENT ON COLUMN ai_usage_tracking.total_tokens_output IS 'Total output tokens consumed';
COMMENT ON COLUMN ai_usage_tracking.estimated_cost_cents IS 'Estimated cost in cents based on token usage';
COMMENT ON COLUMN ai_usage_tracking.budget_limit_cents IS 'Monthly budget limit in cents (adjustable by admins)';

-- //@UNDO
-- SQL to undo the change goes here.

DROP TABLE IF EXISTS ai_usage_tracking;
