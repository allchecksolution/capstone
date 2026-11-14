-- Backend-owned persistence for S3 files, synchronous AI orchestration, and FCM publication.
-- PostgreSQL 17. No AI job/webhook tables, raw transcripts, presigned URLs, or API/S3 credentials.
-- Reception/work/account tables do not exist yet. Their opaque IDs are logical references;
-- add external foreign keys in a later integer-versioned migration when those tables exist.
-- Application services own authorization, immutable originals/terminal states, reception
-- version CAS, cross-table readiness checks, and atomic acceptance/result + event writes.
-- Common API idempotency-response storage is outside V1. Decode API Base64 SHA-256
-- digests to 32-byte BYTEA. S3 objects are canonical; file rows hold metadata only.

CREATE TABLE reception_recordings (
    recording_file_id VARCHAR(64) PRIMARY KEY,
    reception_id VARCHAR(64) NOT NULL,
    uploaded_by_admin_id VARCHAR(64) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    bucket VARCHAR(63) NOT NULL,
    object_key TEXT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum_sha256 BYTEA NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    recording_received_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_reception_recordings_reception UNIQUE (reception_id),
    CONSTRAINT uq_reception_recordings_object UNIQUE (bucket, object_key),
    CONSTRAINT uq_reception_recordings_source UNIQUE (recording_file_id, reception_id, recording_received_at),
    CONSTRAINT ck_reception_recordings_identifiers CHECK (
        char_length(recording_file_id) > 0 AND char_length(reception_id) > 0
        AND char_length(uploaded_by_admin_id) > 0
    ),
    CONSTRAINT ck_reception_recordings_metadata CHECK (
        char_length(original_filename) > 0 AND char_length(bucket) > 0
        AND octet_length(object_key) BETWEEN 1 AND 1024
        AND char_length(content_type) > 0 AND size_bytes > 0
        AND octet_length(checksum_sha256) = 32
    ),
    CONSTRAINT ck_reception_recordings_state CHECK (
        (status = 'PENDING' AND recording_received_at IS NULL)
        OR (status = 'UPLOADED' AND recording_received_at IS NOT NULL)
    ),
    CONSTRAINT ck_reception_recordings_times CHECK (
        updated_at >= created_at
        AND (recording_received_at IS NULL OR recording_received_at >= created_at)
    )
);

CREATE TABLE reception_transcripts (
    transcript_file_id VARCHAR(64) PRIMARY KEY,
    reception_id VARCHAR(64) NOT NULL,
    recording_file_id VARCHAR(64) NOT NULL,
    produced_by_analysis_request_id VARCHAR(64) NOT NULL,
    bucket VARCHAR(63) NOT NULL,
    object_key TEXT NOT NULL,
    content_type VARCHAR(100) NOT NULL DEFAULT 'text/plain; charset=utf-8',
    size_bytes BIGINT NOT NULL,
    checksum_sha256 BYTEA NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    uploaded_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_reception_transcripts_producer UNIQUE (produced_by_analysis_request_id),
    CONSTRAINT uq_reception_transcripts_object UNIQUE (bucket, object_key),
    CONSTRAINT uq_reception_transcripts_source UNIQUE (transcript_file_id, recording_file_id, reception_id),
    CONSTRAINT ck_reception_transcripts_identifiers CHECK (
        char_length(transcript_file_id) > 0 AND char_length(reception_id) > 0
        AND char_length(recording_file_id) > 0 AND char_length(produced_by_analysis_request_id) > 0
    ),
    CONSTRAINT ck_reception_transcripts_metadata CHECK (
        char_length(bucket) > 0 AND octet_length(object_key) BETWEEN 1 AND 1024
        AND content_type = 'text/plain; charset=utf-8'
        AND size_bytes >= 0 AND octet_length(checksum_sha256) = 32
    ),
    CONSTRAINT ck_reception_transcripts_state CHECK (
        (status = 'PENDING' AND uploaded_at IS NULL)
        OR (status = 'UPLOADED' AND uploaded_at IS NOT NULL)
    ),
    CONSTRAINT ck_reception_transcripts_times CHECK (
        updated_at >= created_at AND (uploaded_at IS NULL OR uploaded_at >= created_at)
    )
);

CREATE INDEX ix_reception_transcripts_recording
    ON reception_transcripts (recording_file_id, created_at);

CREATE TABLE work_photos (
    photo_file_id VARCHAR(64) PRIMARY KEY,
    work_id VARCHAR(64) NOT NULL,
    uploaded_by_driver_id VARCHAR(64) NOT NULL,
    phase VARCHAR(10) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    bucket VARCHAR(63) NOT NULL,
    object_key TEXT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum_sha256 BYTEA NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    uploaded_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_work_photos_object UNIQUE (bucket, object_key),
    CONSTRAINT ck_work_photos_identifiers CHECK (
        char_length(photo_file_id) > 0 AND char_length(work_id) > 0
        AND char_length(uploaded_by_driver_id) > 0
    ),
    CONSTRAINT ck_work_photos_phase CHECK (phase IN ('BEFORE', 'DURING', 'AFTER')),
    CONSTRAINT ck_work_photos_metadata CHECK (
        char_length(original_filename) > 0 AND char_length(bucket) > 0
        AND octet_length(object_key) BETWEEN 1 AND 1024
        AND char_length(content_type) > 0 AND size_bytes > 0
        AND octet_length(checksum_sha256) = 32
    ),
    CONSTRAINT ck_work_photos_state CHECK (
        (status = 'PENDING' AND uploaded_at IS NULL)
        OR (status = 'UPLOADED' AND uploaded_at IS NOT NULL)
    ),
    CONSTRAINT ck_work_photos_times CHECK (
        updated_at >= created_at AND (uploaded_at IS NULL OR uploaded_at >= created_at)
    )
);

CREATE INDEX ix_work_photos_work_phase ON work_photos (work_id, phase, created_at);

CREATE TABLE reception_analysis_requests (
    analysis_request_id VARCHAR(64) PRIMARY KEY,
    reception_id VARCHAR(64) NOT NULL,
    recording_file_id VARCHAR(64) NOT NULL,
    reception_version INTEGER NOT NULL,
    requested_by_admin_id VARCHAR(64) NOT NULL,
    mode VARCHAR(20) NOT NULL,
    is_initial BOOLEAN NOT NULL DEFAULT FALSE,
    source_transcript_file_id VARCHAR(64) NULL,
    transcript_file_id VARCHAR(64) NULL,
    reference_time TIMESTAMPTZ NOT NULL,
    time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Seoul',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    stage VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
    stt_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    transcript_storage_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    analysis_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    result_applied BOOLEAN NOT NULL DEFAULT FALSE,
    ai_original JSONB NULL,
    missing_fields JSONB NULL,
    error_code TEXT NULL,
    error_message TEXT NULL,
    trace_id TEXT NULL,
    claimed_by VARCHAR(128) NULL,
    lease_expires_at TIMESTAMPTZ NULL,
    started_at TIMESTAMPTZ NULL,
    completed_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_reception_analysis_requests_context
        UNIQUE (analysis_request_id, recording_file_id, reception_id),
    CONSTRAINT uq_reception_analysis_requests_reception UNIQUE (analysis_request_id, reception_id),
    CONSTRAINT fk_reception_analysis_requests_recording
        FOREIGN KEY (recording_file_id, reception_id, reference_time)
        REFERENCES reception_recordings (recording_file_id, reception_id, recording_received_at)
        ON DELETE RESTRICT,
    CONSTRAINT fk_reception_analysis_requests_source_transcript
        FOREIGN KEY (source_transcript_file_id, recording_file_id, reception_id)
        REFERENCES reception_transcripts (transcript_file_id, recording_file_id, reception_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_reception_analysis_requests_transcript
        FOREIGN KEY (transcript_file_id, recording_file_id, reception_id)
        REFERENCES reception_transcripts (transcript_file_id, recording_file_id, reception_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reception_analysis_requests_identifiers CHECK (
        char_length(analysis_request_id) > 0 AND char_length(reception_id) > 0
        AND char_length(recording_file_id) > 0 AND char_length(requested_by_admin_id) > 0
    ),
    CONSTRAINT ck_reception_analysis_requests_version CHECK (reception_version >= 1),
    CONSTRAINT ck_reception_analysis_requests_time_zone CHECK (time_zone = 'Asia/Seoul'),
    CONSTRAINT ck_reception_analysis_requests_mode CHECK (
        (mode = 'FULL' AND source_transcript_file_id IS NULL)
        OR (mode = 'STRUCTURE_ONLY' AND source_transcript_file_id IS NOT NULL
            AND transcript_file_id IS NOT NULL
            AND transcript_file_id = source_transcript_file_id
            AND stt_status = 'SUCCEEDED' AND transcript_storage_status = 'SUCCEEDED')
    ),
    CONSTRAINT ck_reception_analysis_requests_initial CHECK (NOT is_initial OR mode = 'FULL'),
    CONSTRAINT ck_reception_analysis_requests_stage CHECK (
        stage IN ('QUEUED', 'RECORDING_DOWNLOAD', 'STT', 'TRANSCRIPT_STORAGE',
                  'STRUCTURE', 'RESULT_APPLICATION', 'COMPLETED')
    ),
    CONSTRAINT ck_reception_analysis_requests_stage_states CHECK (
        stt_status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'NOT_AVAILABLE')
        AND transcript_storage_status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'NOT_AVAILABLE')
        AND analysis_status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'NOT_AVAILABLE')
        AND (transcript_storage_status <> 'SUCCEEDED'
             OR (stt_status = 'SUCCEEDED' AND transcript_file_id IS NOT NULL))
    ),
    CONSTRAINT ck_reception_analysis_requests_result_json CHECK (
        (ai_original IS NULL AND missing_fields IS NULL)
        OR (ai_original IS NOT NULL AND missing_fields IS NOT NULL
            AND jsonb_typeof(ai_original) = 'object' AND jsonb_typeof(missing_fields) = 'array')
    ),
    CONSTRAINT ck_reception_analysis_requests_state CHECK (
        (status = 'PENDING' AND stage = 'QUEUED' AND NOT result_applied
            AND started_at IS NULL AND completed_at IS NULL
            AND error_code IS NULL AND error_message IS NULL AND trace_id IS NULL)
        OR (status = 'PROCESSING' AND NOT result_applied
            AND started_at IS NOT NULL AND completed_at IS NULL
            AND error_code IS NULL AND error_message IS NULL AND trace_id IS NULL)
        OR (status = 'SUCCEEDED' AND stage = 'COMPLETED' AND result_applied
            AND started_at IS NOT NULL AND completed_at IS NOT NULL
            AND stt_status = 'SUCCEEDED' AND transcript_storage_status = 'SUCCEEDED'
            AND analysis_status = 'SUCCEEDED' AND transcript_file_id IS NOT NULL
            AND ai_original IS NOT NULL AND missing_fields IS NOT NULL
            AND error_code IS NULL AND error_message IS NULL AND trace_id IS NULL)
        OR (status IN ('FAILED', 'DISCARDED') AND NOT result_applied AND completed_at IS NOT NULL
            AND error_code IS NOT NULL AND char_length(error_code) > 0
            AND error_message IS NOT NULL AND char_length(error_message) > 0
            AND trace_id IS NOT NULL AND char_length(trace_id) > 0
            AND (status <> 'DISCARDED' OR stage = 'RESULT_APPLICATION'))
    ),
    CONSTRAINT ck_reception_analysis_requests_claim CHECK (
        (status = 'PROCESSING' AND claimed_by IS NOT NULL AND char_length(claimed_by) > 0
            AND lease_expires_at IS NOT NULL AND lease_expires_at > started_at)
        OR (status <> 'PROCESSING' AND claimed_by IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_reception_analysis_requests_times CHECK (
        updated_at >= created_at
        AND (started_at IS NULL OR started_at >= created_at)
        AND (completed_at IS NULL OR completed_at >= COALESCE(started_at, created_at))
    )
);

-- A PENDING request is the durable execution intent. Claim it atomically before external I/O.
-- Expired PROCESSING claims are recorded as failed/unknown, never automatically re-executed.
CREATE UNIQUE INDEX uq_reception_analysis_requests_active
    ON reception_analysis_requests (reception_id) WHERE status IN ('PENDING', 'PROCESSING');

CREATE UNIQUE INDEX uq_reception_analysis_requests_initial
    ON reception_analysis_requests (recording_file_id) WHERE is_initial;

CREATE INDEX ix_reception_analysis_requests_history
    ON reception_analysis_requests (reception_id, created_at);

CREATE INDEX ix_reception_analysis_requests_pending
    ON reception_analysis_requests (created_at, analysis_request_id) WHERE status = 'PENDING';

CREATE INDEX ix_reception_analysis_requests_leases
    ON reception_analysis_requests (lease_expires_at) WHERE status = 'PROCESSING';

CREATE INDEX ix_reception_analysis_requests_source_transcript
    ON reception_analysis_requests (source_transcript_file_id) WHERE source_transcript_file_id IS NOT NULL;

CREATE INDEX ix_reception_analysis_requests_transcript
    ON reception_analysis_requests (transcript_file_id) WHERE transcript_file_id IS NOT NULL;

-- Create the cyclic artifact-producer FK only after both tables exist.
-- Insert the FULL attempt first, then its artifact, then attach the stored artifact to the attempt.
ALTER TABLE reception_transcripts
    ADD CONSTRAINT fk_reception_transcripts_producer
    FOREIGN KEY (produced_by_analysis_request_id, recording_file_id, reception_id)
    REFERENCES reception_analysis_requests (analysis_request_id, recording_file_id, reception_id)
    ON DELETE RESTRICT;

CREATE TABLE admin_push_devices (
    installation_id VARCHAR(64) PRIMARY KEY,
    admin_id VARCHAR(64) NOT NULL,
    platform VARCHAR(10) NOT NULL,
    push_token TEXT NULL,
    push_token_sha256 BYTEA NULL,
    revoked_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_admin_push_devices_identifiers CHECK (
        char_length(installation_id) > 0 AND char_length(admin_id) > 0
    ),
    CONSTRAINT ck_admin_push_devices_platform CHECK (platform IN ('ANDROID', 'IOS')),
    CONSTRAINT ck_admin_push_devices_token CHECK (
        (revoked_at IS NULL AND push_token IS NOT NULL AND char_length(push_token) > 0
            AND push_token_sha256 IS NOT NULL AND octet_length(push_token_sha256) = 32
            AND push_token_sha256 = sha256(convert_to(push_token, 'UTF8')))
        OR (revoked_at IS NOT NULL AND push_token IS NULL AND push_token_sha256 IS NULL)
    ),
    CONSTRAINT ck_admin_push_devices_times CHECK (
        updated_at >= created_at AND (revoked_at IS NULL OR revoked_at >= created_at)
    )
);

-- Keep variable-length provider tokens out of B-tree keys; Backend supplies the verified digest.
CREATE UNIQUE INDEX uq_admin_push_devices_active_token
    ON admin_push_devices (push_token_sha256) WHERE revoked_at IS NULL;

CREATE INDEX ix_admin_push_devices_admin
    ON admin_push_devices (admin_id) WHERE revoked_at IS NULL;

-- EXPANDED means device delivery rows were created, not that FCM or a device received them.
CREATE TABLE reception_analysis_events (
    event_id VARCHAR(64) PRIMARY KEY,
    analysis_request_id VARCHAR(64) NOT NULL,
    reception_id VARCHAR(64) NOT NULL,
    recipient_admin_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    claimed_by VARCHAR(128) NULL,
    lease_expires_at TIMESTAMPTZ NULL,
    completed_at TIMESTAMPTZ NULL,
    last_error TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_reception_analysis_events_attempt UNIQUE (analysis_request_id),
    CONSTRAINT fk_reception_analysis_events_attempt
        FOREIGN KEY (analysis_request_id, reception_id)
        REFERENCES reception_analysis_requests (analysis_request_id, reception_id) ON DELETE RESTRICT,
    CONSTRAINT ck_reception_analysis_events_identifiers CHECK (
        char_length(event_id) > 0 AND char_length(recipient_admin_id) > 0
    ),
    CONSTRAINT ck_reception_analysis_events_type CHECK (
        event_type IN ('RECEPTION_ANALYSIS_SUCCEEDED', 'RECEPTION_ANALYSIS_FAILED')
    ),
    CONSTRAINT ck_reception_analysis_events_state CHECK (
        (status IN ('PENDING', 'PROCESSING') AND completed_at IS NULL AND last_error IS NULL)
        OR (status IN ('EXPANDED', 'SKIPPED') AND completed_at IS NOT NULL AND last_error IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL
            AND last_error IS NOT NULL AND char_length(last_error) > 0)
    ),
    CONSTRAINT ck_reception_analysis_events_claim CHECK (
        (status = 'PROCESSING' AND claimed_by IS NOT NULL AND char_length(claimed_by) > 0
            AND lease_expires_at IS NOT NULL AND lease_expires_at > created_at)
        OR (status <> 'PROCESSING' AND claimed_by IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_reception_analysis_events_times CHECK (
        updated_at >= created_at AND (completed_at IS NULL OR completed_at >= created_at)
    )
);

CREATE INDEX ix_reception_analysis_events_pending
    ON reception_analysis_events (created_at, event_id) WHERE status = 'PENDING';

CREATE INDEX ix_reception_analysis_events_leases
    ON reception_analysis_events (lease_expires_at) WHERE status = 'PROCESSING';

CREATE TABLE reception_analysis_push_deliveries (
    delivery_id VARCHAR(64) PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    installation_id VARCHAR(64) NOT NULL,
    status VARCHAR(25) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    claimed_by VARCHAR(128) NULL,
    lease_expires_at TIMESTAMPTZ NULL,
    provider_message_id TEXT NULL,
    last_error TEXT NULL,
    last_attempt_at TIMESTAMPTZ NULL,
    provider_accepted_at TIMESTAMPTZ NULL,
    completed_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_reception_analysis_push_deliveries_target UNIQUE (event_id, installation_id),
    CONSTRAINT fk_reception_analysis_push_deliveries_event
        FOREIGN KEY (event_id) REFERENCES reception_analysis_events (event_id) ON DELETE RESTRICT,
    CONSTRAINT ck_reception_analysis_push_deliveries_identifiers CHECK (
        char_length(delivery_id) > 0 AND char_length(installation_id) > 0
    ),
    CONSTRAINT ck_reception_analysis_push_deliveries_attempts CHECK (
        attempt_count >= 0 AND ((attempt_count = 0 AND last_attempt_at IS NULL)
            OR (attempt_count > 0 AND last_attempt_at IS NOT NULL))
    ),
    CONSTRAINT ck_reception_analysis_push_deliveries_state CHECK (
        (status IN ('PENDING', 'PROCESSING') AND completed_at IS NULL
            AND provider_accepted_at IS NULL AND provider_message_id IS NULL AND last_error IS NULL)
        OR (status = 'PROVIDER_ACCEPTED' AND completed_at IS NOT NULL AND attempt_count > 0
            AND provider_accepted_at IS NOT NULL AND provider_message_id IS NOT NULL
            AND char_length(provider_message_id) > 0 AND last_error IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND attempt_count > 0
            AND provider_accepted_at IS NULL AND provider_message_id IS NULL
            AND last_error IS NOT NULL AND char_length(last_error) > 0)
        OR (status = 'SKIPPED' AND completed_at IS NOT NULL
            AND provider_accepted_at IS NULL AND provider_message_id IS NULL)
    ),
    CONSTRAINT ck_reception_analysis_push_deliveries_claim CHECK (
        (status = 'PROCESSING' AND claimed_by IS NOT NULL AND char_length(claimed_by) > 0
            AND lease_expires_at IS NOT NULL AND lease_expires_at > created_at)
        OR (status <> 'PROCESSING' AND claimed_by IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_reception_analysis_push_deliveries_times CHECK (
        updated_at >= created_at
        AND (last_attempt_at IS NULL OR last_attempt_at >= created_at)
        AND (provider_accepted_at IS NULL OR provider_accepted_at >= last_attempt_at)
        AND (completed_at IS NULL OR completed_at >= COALESCE(last_attempt_at, created_at))
    )
);

CREATE INDEX ix_reception_analysis_push_deliveries_pending
    ON reception_analysis_push_deliveries (created_at, delivery_id) WHERE status = 'PENDING';

CREATE INDEX ix_reception_analysis_push_deliveries_leases
    ON reception_analysis_push_deliveries (lease_expires_at) WHERE status = 'PROCESSING';

-- installation_id is a historical target identifier, deliberately not a FK to a mutable login
-- binding. Before send, join the current active device with the event recipient's admin ID.
-- Revoking/rebinding a device must not delete delivery history or notify a different account.
