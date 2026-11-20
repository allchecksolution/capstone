-- Expand the schema without changing V1 media/analysis records or backfilling their owners.
-- Media/analysis/request identifiers remain logical references until their integrations
-- validate existing links and add the corresponding foreign keys in later migrations.
CREATE TABLE receptions (
    reception_id VARCHAR(64) PRIMARY KEY,
    owner_admin_id VARCHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    caller_phone VARCHAR(20) NULL,
    call_started_at TIMESTAMPTZ NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version INTEGER NOT NULL DEFAULT 1,
    persistence_version BIGINT NOT NULL DEFAULT 0,
    ai_original JSONB NULL,
    final_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    recording_file_id VARCHAR(64) NULL,
    transcript_file_id VARCHAR(64) NULL,
    latest_analysis_request_id VARCHAR(64) NULL,
    request_id VARCHAR(64) NULL,
    confirmed_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_receptions_request UNIQUE (request_id),
    CONSTRAINT ck_receptions_identifiers CHECK (
        char_length(reception_id) > 0 AND char_length(owner_admin_id) > 0
        AND (recording_file_id IS NULL OR char_length(recording_file_id) > 0)
        AND (transcript_file_id IS NULL OR char_length(transcript_file_id) > 0)
        AND (latest_analysis_request_id IS NULL OR char_length(latest_analysis_request_id) > 0)
        AND (request_id IS NULL OR char_length(request_id) > 0)
    ),
    CONSTRAINT ck_receptions_versions CHECK (version >= 1 AND persistence_version >= 0),
    CONSTRAINT ck_receptions_json CHECK (
        jsonb_typeof(final_data) = 'object'
        AND (ai_original IS NULL OR jsonb_typeof(ai_original) = 'object')
    ),
    CONSTRAINT ck_receptions_review_state CHECK (
        (review_status = 'DRAFT' AND confirmed_at IS NULL)
        OR (review_status = 'CONFIRMED' AND confirmed_at IS NOT NULL)
    ),
    CONSTRAINT ck_receptions_registered_state CHECK (request_id IS NULL OR review_status = 'CONFIRMED'),
    CONSTRAINT ck_receptions_confirmed_data CHECK (
        review_status <> 'CONFIRMED'
        OR COALESCE(
            final_data ->> 'workType' IN ('EXTERIOR_LEAK', 'INTERIOR_LEAK', 'BLOCKAGE')
            AND jsonb_typeof(final_data -> 'symptoms') = 'string'
            AND char_length(final_data ->> 'symptoms') BETWEEN 1 AND 2000
            AND final_data ->> 'symptoms' ~ '[^[:space:]]'
            AND jsonb_typeof(final_data -> 'address') = 'string'
            AND char_length(final_data ->> 'address') BETWEEN 1 AND 300
            AND final_data ->> 'address' ~ '[^[:space:]]'
            AND jsonb_typeof(final_data -> 'contactPhone') = 'string'
            AND final_data ->> 'contactPhone' ~ '^[0-9+-]{9,20}$'
            AND jsonb_typeof(final_data -> 'desiredVisitFrom') = 'string'
            AND jsonb_typeof(final_data -> 'desiredVisitTo') = 'string'
            AND (final_data ->> 'desiredVisitTo')::timestamptz
                > (final_data ->> 'desiredVisitFrom')::timestamptz,
            FALSE
        )
    ),
    CONSTRAINT ck_receptions_notes CHECK (
        final_data -> 'notes' IS NULL OR final_data -> 'notes' = 'null'::jsonb
        OR (jsonb_typeof(final_data -> 'notes') = 'string'
            AND char_length(final_data ->> 'notes') <= 2000)
    ),
    CONSTRAINT ck_receptions_times CHECK (
        updated_at >= received_at
        AND (confirmed_at IS NULL OR (confirmed_at >= received_at AND confirmed_at <= updated_at))
    )
);

CREATE INDEX ix_receptions_owner_received ON receptions (owner_admin_id, received_at);

-- The future-end rule runs only in the review-confirmation command, never in a CHECK
-- using CURRENT_TIMESTAMP: historical reads and later request registration stay valid.
