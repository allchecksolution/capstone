CREATE TABLE driver_dispatch_states (
    driver_id BIGINT PRIMARY KEY REFERENCES drivers(driver_id),
    availability VARCHAR(16) NOT NULL CHECK (availability IN ('READY', 'UNAVAILABLE')),
    availability_updated_at TIMESTAMPTZ NOT NULL,
    longitude DOUBLE PRECISION,
    latitude DOUBLE PRECISION,
    accuracy_meters DOUBLE PRECISION,
    location_event_at TIMESTAMPTZ,
    location_issue VARCHAR(20) CHECK (location_issue IN ('PERMISSION_DENIED', 'UNAVAILABLE')),
    CHECK (
        (longitude IS NULL AND latitude IS NULL AND accuracy_meters IS NULL AND
            ((location_issue IS NULL) = (location_event_at IS NULL)))
        OR (longitude IS NOT NULL AND longitude BETWEEN -180 AND 180
            AND latitude IS NOT NULL AND latitude BETWEEN -90 AND 90
            AND accuracy_meters IS NOT NULL AND accuracy_meters BETWEEN 0 AND 100000
            AND location_event_at IS NOT NULL AND location_issue IS NULL)
    )
);
CREATE INDEX ix_driver_dispatch_ready_location ON driver_dispatch_states(location_event_at, driver_id)
    WHERE availability = 'READY' AND longitude IS NOT NULL;
