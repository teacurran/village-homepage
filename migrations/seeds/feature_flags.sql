-- Manual feature flag seed helper. Execute via:
--   psql -h $POSTGRES_HOST -p $POSTGRES_PORT -U $POSTGRES_USER -d $POSTGRES_DB -f migrations/seeds/feature_flags.sql

INSERT INTO feature_flags (name, description, enabled, rollout_percentage)
VALUES
    ('stocks_widget', 'Display realtime stock data from Alpha Vantage', false, 0),
    ('social_integration', 'Enable Instagram/Facebook feed ingestion', false, 0),
    ('promoted_listings', 'Allow paid marketplace listing promotions', false, 0)
ON CONFLICT (name) DO UPDATE
    SET description = EXCLUDED.description,
        updated_at = NOW();
