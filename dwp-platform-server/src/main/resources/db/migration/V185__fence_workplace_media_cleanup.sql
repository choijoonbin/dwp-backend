ALTER TABLE wp_floor_plan_media_assets
    DROP CONSTRAINT ck_wp_floor_plan_media_asset_status,
    DROP CONSTRAINT ck_wp_floor_plan_media_asset_state;

ALTER TABLE wp_floor_plan_media_assets
    ADD CONSTRAINT ck_wp_floor_plan_media_asset_status CHECK (
        asset_status IN ('STAGED', 'REFERENCED', 'PENDING_DELETE', 'DELETING', 'DELETED')),
    ADD CONSTRAINT ck_wp_floor_plan_media_asset_state CHECK (
        (reference_count > 0 AND asset_status = 'REFERENCED' AND unreferenced_at IS NULL)
        OR (reference_count = 0
            AND asset_status IN ('STAGED', 'PENDING_DELETE', 'DELETING', 'DELETED')));

CREATE OR REPLACE FUNCTION wp_reconcile_floor_plan_media_asset(
    target_tenant_id BIGINT,
    target_storage_key TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    refs INTEGER;
    current_status VARCHAR(20);
BEGIN
    IF target_storage_key IS NULL THEN
        RETURN;
    END IF;

    SELECT COUNT(*) INTO refs
      FROM wp_floor_plan_revisions
     WHERE tenant_id = target_tenant_id
       AND background_asset_key = target_storage_key;

    SELECT asset_status INTO current_status
      FROM wp_floor_plan_media_assets
     WHERE tenant_id = target_tenant_id
       AND storage_key = target_storage_key
     FOR UPDATE;

    IF refs > 0 AND current_status IN ('DELETING', 'DELETED') THEN
        RAISE EXCEPTION 'Floor-plan media asset % is no longer referenceable', target_storage_key
            USING ERRCODE = '55000';
    END IF;

    INSERT INTO wp_floor_plan_media_assets (
        tenant_id, storage_key, reference_count, asset_status,
        first_referenced_at, last_referenced_at, unreferenced_at)
    VALUES (
        target_tenant_id, target_storage_key, refs,
        CASE WHEN refs > 0 THEN 'REFERENCED' ELSE 'PENDING_DELETE' END,
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
        CASE WHEN refs = 0 THEN CURRENT_TIMESTAMP ELSE NULL END)
    ON CONFLICT (tenant_id, storage_key) DO UPDATE
       SET reference_count = EXCLUDED.reference_count,
           asset_status = EXCLUDED.asset_status,
           last_referenced_at = CASE WHEN refs > 0
               THEN CURRENT_TIMESTAMP ELSE wp_floor_plan_media_assets.last_referenced_at END,
           unreferenced_at = EXCLUDED.unreferenced_at;

    IF refs = 0 THEN
        INSERT INTO sys_tenant_media_cleanup_outbox (
            tenant_id, storage_key, cleanup_reason)
        VALUES (target_tenant_id, target_storage_key, 'FLOOR_PLAN_UNREFERENCED')
        ON CONFLICT DO NOTHING;
    ELSE
        UPDATE sys_tenant_media_cleanup_outbox
           SET cleanup_status = 'COMPLETED',
               completed_at = CURRENT_TIMESTAMP,
               lease_owner = NULL,
               lease_expires_at = NULL,
               last_error_code = 'CLEANUP_CANCELLED_BY_REFERENCE',
               updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = target_tenant_id
           AND storage_key = target_storage_key
           AND cleanup_status IN ('PENDING', 'RETRY_WAIT');
    END IF;
END;
$$;

COMMENT ON COLUMN wp_floor_plan_media_assets.asset_status IS
    'DELETING fences new references before external deletion; DELETED prevents dangling revision references.';
