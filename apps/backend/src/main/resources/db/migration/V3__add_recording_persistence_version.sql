-- Keep V1 metadata and first completion timestamps unchanged for existing recordings.
ALTER TABLE reception_recordings
    ADD COLUMN persistence_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_reception_recordings_persistence_version CHECK (persistence_version >= 0);
