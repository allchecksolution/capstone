-- Work owns workload. No independent driver workload counter is introduced.
CREATE TABLE work_orders (
    work_id VARCHAR(64) PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL CHECK (char_length(btrim(request_id)) > 0),
    driver_id VARCHAR(64) NOT NULL REFERENCES drivers(driver_id),
    status VARCHAR(20) NOT NULL CHECK (status IN ('NOT_STARTED', 'EN_ROUTE', 'ARRIVED', 'IN_PROGRESS', 'REVISIT_REQUIRED', 'WORK_DONE', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE UNIQUE INDEX uq_work_orders_active_driver ON work_orders(driver_id) WHERE status NOT IN ('WORK_DONE', 'CANCELLED');
CREATE UNIQUE INDEX uq_work_orders_active_request ON work_orders(request_id) WHERE status NOT IN ('WORK_DONE', 'CANCELLED');
CREATE INDEX ix_work_orders_driver ON work_orders(driver_id);
