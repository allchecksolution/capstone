-- No STT artifacts have been stored, as confirmed before implementation.
-- A NOT NULL column without a default deliberately fails if unexpected legacy
-- transcript rows exist. Never invent original text or delete rows to migrate.
-- Preserve V1-V3 checksums, producer/source FKs, and recording/analysis records.
ALTER TABLE reception_transcripts
    ADD COLUMN transcript_text TEXT NOT NULL,
    ADD COLUMN persistence_version BIGINT NOT NULL DEFAULT 0,
    ALTER COLUMN bucket DROP NOT NULL,
    ALTER COLUMN object_key DROP NOT NULL,
    ALTER COLUMN uploaded_at SET NOT NULL;

ALTER TABLE reception_transcripts
    DROP CONSTRAINT ck_reception_transcripts_metadata,
    ADD CONSTRAINT ck_reception_transcripts_metadata CHECK (
        content_type = 'text/plain; charset=utf-8'
        AND size_bytes >= 0 AND octet_length(checksum_sha256) = 32
        AND bucket IS NULL AND object_key IS NULL
        AND size_bytes = octet_length(transcript_text)
    ),
    DROP CONSTRAINT ck_reception_transcripts_state,
    ADD CONSTRAINT ck_reception_transcripts_state CHECK (status = 'UPLOADED'),
    ADD CONSTRAINT ck_reception_transcripts_content CHECK (
        char_length(transcript_text) BETWEEN 1 AND 100000
        -- Match Kotlin Char.isWhitespace independently of database locale.
        -- This predicate never trims or otherwise changes the stored text.
        AND translate(transcript_text, U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000', '') <> ''
    ),
    ADD CONSTRAINT ck_reception_transcripts_persistence_version CHECK (persistence_version >= 0);

-- Retain V1's status/uploaded_at names. UPLOADED now means the original is stored
-- in the database, not in S3. Unused bucket/object_key columns stay NULL.
