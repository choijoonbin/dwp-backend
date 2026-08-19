package com.dwp.services.platform.workplace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Repository
class WorkplaceMediaCleanupRepository {

    private final JdbcTemplate jdbc;

    WorkplaceMediaCleanupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void registerStaged(Long tenantId, String storageKey) {
        jdbc.update("""
                INSERT INTO wp_floor_plan_media_assets (
                    tenant_id, storage_key, reference_count, asset_status,
                    first_referenced_at, last_referenced_at)
                VALUES (?, ?, 0, 'STAGED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, tenantId, storageKey);
    }

    @Transactional
    int reconcile(int batchSize, OffsetDateTime stagedBefore) {
        Integer reconciled = jdbc.queryForObject("""
                WITH candidates AS (
                    SELECT asset.tenant_id, asset.storage_key, asset.asset_status
                      FROM wp_floor_plan_media_assets asset
                     WHERE asset.reference_count = 0
                       AND (asset.asset_status = 'PENDING_DELETE'
                            OR (asset.asset_status = 'STAGED'
                                AND asset.first_referenced_at < ?))
                     ORDER BY COALESCE(asset.unreferenced_at, asset.first_referenced_at),
                              asset.tenant_id, asset.storage_key
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), marked AS (
                    UPDATE wp_floor_plan_media_assets asset
                       SET asset_status = 'PENDING_DELETE',
                           unreferenced_at = COALESCE(asset.unreferenced_at, CURRENT_TIMESTAMP)
                      FROM candidates
                     WHERE asset.tenant_id = candidates.tenant_id
                       AND asset.storage_key = candidates.storage_key
                    RETURNING asset.tenant_id, asset.storage_key, candidates.asset_status
                ), queued AS (
                    INSERT INTO sys_tenant_media_cleanup_outbox (
                        tenant_id, storage_key, cleanup_reason)
                    SELECT tenant_id, storage_key,
                           CASE WHEN asset_status = 'STAGED'
                               THEN 'FLOOR_PLAN_STAGED_ORPHAN'
                               ELSE 'FLOOR_PLAN_UNREFERENCED' END
                      FROM marked
                    ON CONFLICT DO NOTHING
                    RETURNING cleanup_id
                )
                SELECT COUNT(*) FROM marked
                """, Integer.class, stagedBefore, batchSize);
        return reconciled == null ? 0 : reconciled;
    }
}
