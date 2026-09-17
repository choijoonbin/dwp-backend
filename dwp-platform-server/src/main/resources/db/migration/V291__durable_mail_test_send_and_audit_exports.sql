ALTER TABLE mail_delivery_audit_exports
    ADD COLUMN snapshot_payload TEXT,
    ADD COLUMN payload_sha256 CHAR(64),
    ADD COLUMN item_count INTEGER,
    ADD COLUMN truncated BOOLEAN,
    ADD COLUMN snapshot_cutoff TIMESTAMPTZ;

UPDATE mail_delivery_audit_exports
   SET export_state = 'FAILED'
 WHERE export_state = 'READY'
   AND snapshot_payload IS NULL;

ALTER TABLE mail_delivery_audit_exports
    ADD CONSTRAINT ck_mail_delivery_export_snapshot_hash
        CHECK (payload_sha256 IS NULL OR payload_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_mail_delivery_export_item_count
        CHECK (item_count IS NULL OR item_count >= 0),
    ADD CONSTRAINT ck_mail_delivery_export_ready_snapshot
        CHECK (export_state <> 'READY' OR (
            snapshot_payload IS NOT NULL
            AND payload_sha256 IS NOT NULL
            AND item_count IS NOT NULL
            AND truncated IS NOT NULL
            AND snapshot_cutoff IS NOT NULL));

CREATE FUNCTION guard_mail_delivery_export_snapshot_immutable()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.snapshot_payload IS NOT NULL AND (
        OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
        OR OLD.actor_user_id IS DISTINCT FROM NEW.actor_user_id
        OR OLD.filters IS DISTINCT FROM NEW.filters
        OR OLD.purpose IS DISTINCT FROM NEW.purpose
        OR OLD.watermark IS DISTINCT FROM NEW.watermark
        OR OLD.idempotency_key IS DISTINCT FROM NEW.idempotency_key
        OR OLD.expires_at IS DISTINCT FROM NEW.expires_at
        OR OLD.snapshot_payload IS DISTINCT FROM NEW.snapshot_payload
        OR OLD.payload_sha256 IS DISTINCT FROM NEW.payload_sha256
        OR OLD.item_count IS DISTINCT FROM NEW.item_count
        OR OLD.truncated IS DISTINCT FROM NEW.truncated
        OR OLD.snapshot_cutoff IS DISTINCT FROM NEW.snapshot_cutoff) THEN
        RAISE EXCEPTION 'Mail delivery audit export snapshot is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mail_delivery_export_snapshot_immutable
    BEFORE UPDATE ON mail_delivery_audit_exports
    FOR EACH ROW EXECUTE FUNCTION guard_mail_delivery_export_snapshot_immutable();

COMMENT ON TABLE mail_connection_operations IS
    'Actor-scoped operations. TEST_SEND claims are committed as UNKNOWN before provider I/O so replay never sends twice after an ambiguous result.';

COMMENT ON COLUMN mail_delivery_audit_exports.snapshot_payload IS
    'Immutable UTF-8 JSON snapshot returned byte-for-byte for every download of this export.';

COMMENT ON COLUMN mail_delivery_audit_exports.payload_sha256 IS
    'SHA-256 of the exact UTF-8 snapshot_payload bytes.';
