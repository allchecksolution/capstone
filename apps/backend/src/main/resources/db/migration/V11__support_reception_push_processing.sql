ALTER TABLE reception_analysis_events
    ADD COLUMN persistence_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE reception_analysis_push_deliveries
    ADD COLUMN persistence_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN last_failure_code VARCHAR(40);

UPDATE reception_analysis_push_deliveries SET next_attempt_at = created_at;
ALTER TABLE reception_analysis_push_deliveries ALTER COLUMN next_attempt_at SET NOT NULL;

CREATE INDEX ix_reception_push_due
    ON reception_analysis_push_deliveries (next_attempt_at, delivery_id) WHERE status = 'PENDING';
