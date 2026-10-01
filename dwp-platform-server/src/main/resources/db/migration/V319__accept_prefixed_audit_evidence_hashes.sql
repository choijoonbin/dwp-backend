-- Gateway denial evidence uses the explicit hmac-sha256:<64 lowercase hex> wire form.
-- Preserve legacy raw SHA-256 rows while widening only the two pseudonymous hash columns.
ALTER TABLE sys_audit_events
    DROP CONSTRAINT ck_sys_audit_event_session,
    DROP CONSTRAINT ck_sys_audit_event_client;

ALTER TABLE sys_audit_events
    ALTER COLUMN session_id_hash TYPE VARCHAR(76)
        USING RTRIM(session_id_hash),
    ALTER COLUMN client_address_hash TYPE VARCHAR(76)
        USING RTRIM(client_address_hash);

ALTER TABLE sys_audit_events
    ADD CONSTRAINT ck_sys_audit_event_session
        CHECK (session_id_hash IS NULL OR session_id_hash ~
            '^([0-9a-f]{64}|hmac-sha256:[0-9a-f]{64})$'),
    ADD CONSTRAINT ck_sys_audit_event_client
        CHECK (client_address_hash IS NULL OR client_address_hash ~
            '^([0-9a-f]{64}|hmac-sha256:[0-9a-f]{64})$');

COMMENT ON COLUMN sys_audit_events.session_id_hash IS
    'Pseudonymous session hash: legacy raw SHA-256 or hmac-sha256:<64 lowercase hex>.';
COMMENT ON COLUMN sys_audit_events.client_address_hash IS
    'Pseudonymous client address hash: legacy raw SHA-256 or hmac-sha256:<64 lowercase hex>.';
