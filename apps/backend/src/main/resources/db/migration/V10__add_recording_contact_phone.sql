-- Filename-derived review input is separate from caller metadata and immutable AI extraction.
-- Do not guess customer numbers for existing records or overwrite reviewed values.
ALTER TABLE receptions ADD COLUMN recording_contact_phone VARCHAR(20);
ALTER TABLE receptions ADD CONSTRAINT ck_receptions_recording_contact_phone
    CHECK (recording_contact_phone IS NULL OR recording_contact_phone ~ '^\+?[0-9]{9,15}$');
