CREATE TABLE IF NOT EXISTS feature_flag_evaluations (
    flag_key VARCHAR(255) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    result BOOLEAN NOT NULL,
    consent_granted BOOLEAN NOT NULL,
    rollout_percentage_snapshot SMALLINT NOT NULL,
    evaluation_reason VARCHAR(255),
    trace_id VARCHAR(64),
    timestamp TIMESTAMP NOT NULL
);

-- AI usage tracking table for budget enforcement (P2/P10)
CREATE TABLE IF NOT EXISTS ai_usage_tracking (
    id UUID PRIMARY KEY DEFAULT random_uuid(),
    month DATE NOT NULL,
    provider VARCHAR(255) NOT NULL DEFAULT 'anthropic',
    total_requests INT NOT NULL DEFAULT 0,
    total_tokens_input BIGINT NOT NULL DEFAULT 0,
    total_tokens_output BIGINT NOT NULL DEFAULT 0,
    estimated_cost_cents INT NOT NULL DEFAULT 0,
    budget_limit_cents INT NOT NULL DEFAULT 50000,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(month, provider)
);

CREATE INDEX IF NOT EXISTS idx_ai_usage_tracking_month ON ai_usage_tracking(month DESC);
