-- Manual feature flag seed helper. Execute via:
--   psql -h $POSTGRES_HOST -p $POSTGRES_PORT -U $POSTGRES_USER -d $POSTGRES_DB -f migrations/seeds/feature_flags.sql

INSERT INTO feature_flags (flag_key, description, enabled, rollout_percentage, whitelist, analytics_enabled)
VALUES
    ('stocks_widget', 'Display realtime stock data from Alpha Vantage', false, 0, '[]'::jsonb, true),
    ('social_integration', 'Enable Instagram/Facebook feed ingestion', false, 0, '[]'::jsonb, true),
    ('promoted_listings', 'Allow paid marketplace listing promotions', false, 0, '[]'::jsonb, true)
ON CONFLICT (flag_key) DO UPDATE
    SET description = EXCLUDED.description,
        analytics_enabled = EXCLUDED.analytics_enabled,
        whitelist = EXCLUDED.whitelist,
        rollout_percentage = LEAST(EXCLUDED.rollout_percentage, 100),
        updated_at = NOW();
