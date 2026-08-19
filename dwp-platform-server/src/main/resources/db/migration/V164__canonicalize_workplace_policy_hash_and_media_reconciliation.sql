CREATE EXTENSION IF NOT EXISTS pgcrypto;

UPDATE wp_bookings
   SET policy_snapshot_hash = encode(
           digest(policy_snapshot::TEXT, 'sha256'), 'hex');

ALTER TABLE wp_bookings
    ADD CONSTRAINT ck_wp_bookings_policy_snapshot_hash_matches CHECK (
        policy_snapshot_hash = encode(
            digest(policy_snapshot::TEXT, 'sha256'), 'hex'));

CREATE INDEX idx_wp_floor_plan_media_reconciliation
    ON wp_floor_plan_media_assets (asset_status, unreferenced_at, tenant_id, storage_key)
    WHERE reference_count = 0
      AND asset_status IN ('STAGED', 'PENDING_DELETE');

COMMENT ON CONSTRAINT ck_wp_bookings_policy_snapshot_hash_matches ON wp_bookings IS
    'Prevents the effective policy evidence from being changed without its canonical SHA-256 digest.';
COMMENT ON INDEX idx_wp_floor_plan_media_reconciliation IS
    'Supports bounded recovery of staged or unreferenced floor-plan media after process interruption.';
