ALTER TABLE mail_delivery_outbox
    ADD COLUMN request_fingerprint CHAR(64);

ALTER TABLE mail_threads
    ADD COLUMN compose_request_fingerprint CHAR(64);

-- Existing rows deliberately remain NULL. Their original actor, version and canonical
-- request fields cannot all be reconstructed safely after projection updates. Runtime
-- replay checks treat this unbound legacy state as a conflict instead of guessing.

ALTER TABLE mail_delivery_outbox
    DROP CONSTRAINT uk_mail_delivery_idempotency;

ALTER TABLE mail_delivery_outbox
    ADD CONSTRAINT uk_mail_delivery_actor_idempotency
        UNIQUE (tenant_id, created_by, idempotency_key);

ALTER TABLE mail_delivery_outbox
    ADD CONSTRAINT ck_mail_delivery_request_fingerprint
        CHECK (request_fingerprint IS NULL
            OR request_fingerprint ~ '^[0-9a-f]{64}$');

ALTER TABLE mail_threads
    ADD CONSTRAINT ck_mail_thread_compose_request_fingerprint
        CHECK (compose_request_fingerprint IS NULL
            OR compose_request_fingerprint ~ '^[0-9a-f]{64}$');

COMMENT ON COLUMN mail_delivery_outbox.request_fingerprint IS
    'Canonical SHA-256 binding for replay-safe compose, reply, and draft-send commands; NULL marks a legacy unbound command whose replay must fail closed.';

COMMENT ON CONSTRAINT uk_mail_delivery_actor_idempotency ON mail_delivery_outbox IS
    'Idempotency keys are tenant-and-actor scoped so unrelated actors cannot block each other.';

COMMENT ON COLUMN mail_threads.compose_request_fingerprint IS
    'Canonical SHA-256 binding for replay-safe legacy compose SEND and DRAFT commands; NULL marks a pre-binding projection whose replay requires a fresh idempotency key.';
