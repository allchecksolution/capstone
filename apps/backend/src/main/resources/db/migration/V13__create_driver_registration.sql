CREATE TABLE driver_profiles (
    profile_id VARCHAR(64) PRIMARY KEY,
    worker_name VARCHAR(100) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    email VARCHAR(254) NOT NULL,
    residence VARCHAR(200) NOT NULL,
    vehicle_number VARCHAR(20) NOT NULL,
    bank_name VARCHAR(50) NOT NULL,
    account_number VARCHAR(30) NOT NULL,
    holder_name VARCHAR(50) NOT NULL,
    tax_invoice_email VARCHAR(254) NOT NULL
);

CREATE TABLE driver_profile_specialties (
    profile_id VARCHAR(64) NOT NULL REFERENCES driver_profiles(profile_id),
    position INTEGER NOT NULL CHECK (position >= 0),
    specialty VARCHAR(20) NOT NULL CHECK (specialty IN ('BLOCKAGE', 'INTERIOR_LEAK', 'EXTERIOR_LEAK', 'BOILER', 'WATERPROOFING')),
    PRIMARY KEY (profile_id, position),
    UNIQUE (profile_id, specialty)
);
CREATE TABLE driver_profile_regions (
    profile_id VARCHAR(64) NOT NULL REFERENCES driver_profiles(profile_id),
    position INTEGER NOT NULL CHECK (position >= 0),
    region VARCHAR(100) NOT NULL CHECK (char_length(btrim(region)) > 0),
    PRIMARY KEY (profile_id, position),
    UNIQUE (profile_id, region)
);
CREATE TABLE driver_profile_equipment (
    profile_id VARCHAR(64) NOT NULL REFERENCES driver_profiles(profile_id),
    position INTEGER NOT NULL CHECK (position >= 0),
    equipment VARCHAR(100) NOT NULL CHECK (char_length(btrim(equipment)) > 0),
    PRIMARY KEY (profile_id, position),
    UNIQUE (profile_id, equipment)
);

CREATE TABLE driver_businesses (
    business_id VARCHAR(64) PRIMARY KEY,
    business_name VARCHAR(100) NOT NULL,
    registration_number VARCHAR(10) NOT NULL CHECK (registration_number ~ '^[0-9]{10}$'),
    opened_on DATE NOT NULL,
    business_type VARCHAR(100) NOT NULL,
    business_item VARCHAR(100) NOT NULL,
    representative_name VARCHAR(100) NOT NULL,
    company_phone VARCHAR(20) NOT NULL,
    company_address VARCHAR(200) NOT NULL,
    operating_status VARCHAR(16) NOT NULL CHECK (operating_status IN ('CONTINUING', 'SUSPENDED', 'CLOSED')),
    verified_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE drivers (
    driver_id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL UNIQUE REFERENCES identity_accounts(account_id),
    profile_id VARCHAR(64) NOT NULL UNIQUE REFERENCES driver_profiles(profile_id),
    business_id VARCHAR(64) NOT NULL UNIQUE REFERENCES driver_businesses(business_id),
    account_status VARCHAR(20) NOT NULL CHECK (account_status IN ('PENDING_APPROVAL', 'ACTIVATED', 'REJECTED', 'WITHDRAWN')),
    submitted_at TIMESTAMPTZ NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    rejection_reason VARCHAR(1000),
    rejected_at TIMESTAMPTZ,
    withdrawn_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ck_drivers_rejection CHECK (
        (account_status = 'REJECTED' AND rejection_reason IS NOT NULL AND char_length(btrim(rejection_reason)) > 0 AND rejected_at IS NOT NULL)
        OR (account_status <> 'REJECTED' AND rejection_reason IS NULL AND rejected_at IS NULL)
    ),
    CONSTRAINT ck_drivers_withdrawal CHECK ((account_status = 'WITHDRAWN') = (withdrawn_at IS NOT NULL))
);
CREATE INDEX ix_drivers_status_submitted ON drivers(account_status, submitted_at, driver_id);

CREATE TABLE driver_registration_history (
    history_id VARCHAR(64) PRIMARY KEY,
    driver_id VARCHAR(64) NOT NULL REFERENCES drivers(driver_id),
    revision BIGINT NOT NULL CHECK (revision >= 1),
    event_type VARCHAR(16) NOT NULL CHECK (event_type IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    actor_account_id VARCHAR(64) NOT NULL REFERENCES identity_accounts(account_id),
    occurred_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(1000),
    request_snapshot TEXT,
    UNIQUE(driver_id, revision, event_type),
    CHECK ((event_type = 'REJECTED' AND reason IS NOT NULL AND char_length(btrim(reason)) > 0) OR (event_type <> 'REJECTED' AND reason IS NULL)),
    CHECK ((event_type = 'SUBMITTED') = (request_snapshot IS NOT NULL))
);
CREATE INDEX ix_driver_history_driver ON driver_registration_history(driver_id, occurred_at);

CREATE TABLE driver_idempotency_records (
    record_id VARCHAR(64) PRIMARY KEY,
    actor_id VARCHAR(64) NOT NULL REFERENCES identity_accounts(account_id),
    operation_scope VARCHAR(200) NOT NULL,
    key_hash VARCHAR(64) NOT NULL CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    request_fingerprint VARCHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    response_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_driver_idempotency_scope UNIQUE(actor_id, operation_scope, key_hash)
);

CREATE TABLE driver_notification_events (
    event_id VARCHAR(64) PRIMARY KEY,
    driver_id VARCHAR(64) NOT NULL REFERENCES drivers(driver_id),
    revision BIGINT NOT NULL CHECK (revision >= 1),
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('REGISTRATION_SUBMITTED', 'DRIVER_APPROVED', 'DRIVER_REJECTED')),
    recipient_account_id VARCHAR(64) REFERENCES identity_accounts(account_id),
    created_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'EXPANDED', 'SKIPPED')),
    UNIQUE(driver_id, revision, event_type),
    CHECK ((event_type = 'REGISTRATION_SUBMITTED') = (recipient_account_id IS NULL))
);
CREATE INDEX ix_driver_notifications_pending ON driver_notification_events(created_at, event_id) WHERE status = 'PENDING';
