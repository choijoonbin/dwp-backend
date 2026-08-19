ALTER TABLE wp_resource_release_windows
    ADD COLUMN idempotency_key VARCHAR(160),
    ADD COLUMN request_fingerprint CHAR(64),
    ADD CONSTRAINT ck_wp_release_windows_idempotency_pair CHECK (
        (idempotency_key IS NULL AND request_fingerprint IS NULL)
        OR (idempotency_key IS NOT NULL
            AND idempotency_key ~ '^[!-~]{1,160}$'
            AND request_fingerprint ~ '^[0-9a-f]{64}$'));

CREATE UNIQUE INDEX uk_wp_release_windows_idempotency
    ON wp_resource_release_windows (
        tenant_id, released_by_user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN wp_resource_release_windows.idempotency_key IS
    'Client supplied opaque key scoped to tenant and verified release owner.';
COMMENT ON COLUMN wp_resource_release_windows.request_fingerprint IS
    'SHA-256 fingerprint of the normalized create-release-window command.';
