-- Extend the V2 confirmation constraint while preserving existing values and all
-- other required-field checks. Do not rewrite previously applied migrations.
ALTER TABLE receptions
    DROP CONSTRAINT ck_receptions_confirmed_data,
    ADD CONSTRAINT ck_receptions_confirmed_data CHECK (
        review_status <> 'CONFIRMED'
        OR COALESCE(
            final_data ->> 'workType' IN ('EXTERIOR_LEAK', 'INTERIOR_LEAK', 'BLOCKAGE', 'BOILER', 'WATERPROOFING')
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
    );
