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
