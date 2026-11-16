-- Separate JPA write-conflict detection from the immutable captured reception_version.
-- Existing pending/processing/terminal attempts and their source links remain unchanged.
ALTER TABLE reception_analysis_requests
    ADD COLUMN persistence_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_reception_analysis_requests_persistence_version CHECK (persistence_version >= 0);
