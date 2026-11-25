CREATE TABLE request_limit_buckets (
    bucket_id VARCHAR(128) PRIMARY KEY,
    request_count INTEGER NOT NULL CHECK (request_count > 0),
    window_ends_at TIMESTAMPTZ NOT NULL
);
