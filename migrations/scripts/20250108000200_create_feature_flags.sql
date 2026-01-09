-- // create_feature_flags_table
-- Establishes the feature_flags table with the initial rollout switches required by the I1 scope.

CREATE TABLE IF NOT EXISTS feature_flags (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    description TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    rollout_percentage SMALLINT NOT NULL DEFAULT 0 CHECK (rollout_percentage BETWEEN 0 AND 100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_feature_flags_enabled ON feature_flags(enabled);

INSERT INTO feature_flags (name, description, enabled, rollout_percentage)
VALUES
    ('stocks_widget', 'Display realtime stock data from Alpha Vantage', false, 0),
    ('social_integration', 'Enable Instagram/Facebook feed ingestion', false, 0),
    ('promoted_listings', 'Allow paid marketplace listing promotions', false, 0)
ON CONFLICT (name) DO UPDATE
    SET description = EXCLUDED.description,
        rollout_percentage = LEAST(EXCLUDED.rollout_percentage, 100),
        updated_at = NOW();

-- //@UNDO

DROP TABLE IF EXISTS feature_flags;
