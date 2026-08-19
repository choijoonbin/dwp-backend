ALTER TABLE cal_events
    ADD COLUMN request_fingerprint VARCHAR(64);

ALTER TABLE cal_events
    ADD CONSTRAINT ck_cal_events_request_fingerprint
        CHECK (request_fingerprint IS NULL
            OR request_fingerprint ~ '^[0-9a-f]{64}$');

COMMENT ON COLUMN cal_events.request_fingerprint IS
    'SHA-256 of the canonical create-event payload used to reject idempotency-key misuse.';
