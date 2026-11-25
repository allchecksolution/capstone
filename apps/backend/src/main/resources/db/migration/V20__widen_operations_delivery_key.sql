-- A full installation identifier plus BIGINT task/actor identities must fit the deduplication key.
ALTER TABLE operations_tasks ALTER COLUMN logical_key TYPE VARCHAR(400);
