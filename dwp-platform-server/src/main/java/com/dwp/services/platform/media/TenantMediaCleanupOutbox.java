package com.dwp.services.platform.media;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class TenantMediaCleanupOutbox {

    private final JdbcTemplate jdbc;

    public TenantMediaCleanupOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(Long tenantId, String storageKey, String reason) {
        if (storageKey == null || storageKey.isBlank()) return;
        jdbc.update("""
                INSERT INTO sys_tenant_media_cleanup_outbox (
                    tenant_id, storage_key, cleanup_reason)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, tenantId, storageKey, normalizeReason(reason));
    }

    @Transactional
    List<CleanupJob> claim(String workerId, int batchSize, int leaseSeconds) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT cleanup_id
                      FROM sys_tenant_media_cleanup_outbox
                     WHERE cleanup_status IN ('PENDING', 'RETRY_WAIT')
                       AND next_attempt_at <= CURRENT_TIMESTAMP
                     ORDER BY next_attempt_at, created_at, cleanup_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), leased AS (
                    UPDATE sys_tenant_media_cleanup_outbox cleanup
                       SET cleanup_status = 'LEASED',
                           attempt_count = attempt_count + 1,
                           lease_owner = ?,
                           lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                           updated_at = CURRENT_TIMESTAMP
                      FROM candidates
                     WHERE cleanup.cleanup_id = candidates.cleanup_id
                    RETURNING cleanup.*
                )
                SELECT cleanup_id, tenant_id, storage_key, attempt_count
                  FROM leased
                 ORDER BY created_at, cleanup_id
                """, (result, ignored) -> new CleanupJob(
                result.getObject("cleanup_id", UUID.class),
                result.getLong("tenant_id"),
                result.getString("storage_key"),
                result.getInt("attempt_count")), batchSize, workerId, leaseSeconds);
    }

    @Transactional
    boolean beginDelete(CleanupJob job, String workerId) {
        List<MediaAssetState> assets = jdbc.query("""
                SELECT reference_count, asset_status
                  FROM wp_floor_plan_media_assets
                 WHERE tenant_id = ? AND storage_key = ?
                 FOR UPDATE
                """, (result, ignored) -> new MediaAssetState(
                result.getInt("reference_count"), result.getString("asset_status")),
                job.tenantId(), job.storageKey());
        if (!assets.isEmpty()) {
            MediaAssetState asset = assets.getFirst();
            if (asset.referenceCount() > 0
                    || !("STAGED".equals(asset.status())
                    || "PENDING_DELETE".equals(asset.status()))) {
                return false;
            }
        }
        Integer leased = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_tenant_media_cleanup_outbox
                 WHERE cleanup_id = ?
                   AND cleanup_status = 'LEASED'
                   AND lease_owner = ?
                """, Integer.class, job.cleanupId(), workerId);
        if (leased == null || leased != 1) return false;
        if (!assets.isEmpty()) {
            return jdbc.update("""
                    UPDATE wp_floor_plan_media_assets
                       SET asset_status = 'DELETING'
                     WHERE tenant_id = ? AND storage_key = ?
                       AND reference_count = 0
                       AND asset_status IN ('STAGED', 'PENDING_DELETE')
                    """, job.tenantId(), job.storageKey()) == 1;
        }
        return true;
    }

    int complete(CleanupJob job, String workerId) {
        return jdbc.update("""
                UPDATE sys_tenant_media_cleanup_outbox
                   SET cleanup_status = 'COMPLETED',
                       completed_at = CURRENT_TIMESTAMP,
                       lease_owner = NULL,
                       lease_expires_at = NULL,
                       last_error_code = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE cleanup_id = ?
                   AND cleanup_status = 'LEASED'
                   AND lease_owner = ?
                """, job.cleanupId(), workerId);
    }

    @Transactional
    int completeDelete(CleanupJob job, String workerId) {
        jdbc.update("""
                UPDATE wp_floor_plan_media_assets
                   SET asset_status = 'DELETED'
                 WHERE tenant_id = ? AND storage_key = ?
                   AND reference_count = 0
                   AND asset_status = 'DELETING'
                """, job.tenantId(), job.storageKey());
        return complete(job, workerId);
    }

    @Transactional
    int fail(
            CleanupJob job,
            String workerId,
            int maximumAttempts,
            String errorCode,
            OffsetDateTime nextAttemptAt) {
        boolean exhausted = job.attemptCount() >= maximumAttempts;
        jdbc.update("""
                UPDATE wp_floor_plan_media_assets
                   SET asset_status = 'PENDING_DELETE'
                 WHERE tenant_id = ? AND storage_key = ?
                   AND reference_count = 0
                   AND asset_status = 'DELETING'
                """, job.tenantId(), job.storageKey());
        return jdbc.update("""
                UPDATE sys_tenant_media_cleanup_outbox
                   SET cleanup_status = ?,
                       next_attempt_at = CASE WHEN ? THEN next_attempt_at ELSE ? END,
                       lease_owner = NULL,
                       lease_expires_at = NULL,
                       last_error_code = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE cleanup_id = ?
                   AND cleanup_status = 'LEASED'
                   AND lease_owner = ?
                """, exhausted ? "DEAD" : "RETRY_WAIT", exhausted, nextAttemptAt,
                errorCode, job.cleanupId(), workerId);
    }

    @Transactional
    void releaseExpiredLeases() {
        jdbc.update("""
                UPDATE wp_floor_plan_media_assets asset
                   SET asset_status = 'PENDING_DELETE'
                  FROM sys_tenant_media_cleanup_outbox cleanup
                 WHERE cleanup.tenant_id = asset.tenant_id
                   AND cleanup.storage_key = asset.storage_key
                   AND cleanup.cleanup_status = 'LEASED'
                   AND cleanup.lease_expires_at < CURRENT_TIMESTAMP
                   AND asset.reference_count = 0
                   AND asset.asset_status = 'DELETING'
                """);
        jdbc.update("""
                UPDATE sys_tenant_media_cleanup_outbox
                   SET cleanup_status = 'RETRY_WAIT',
                       next_attempt_at = CURRENT_TIMESTAMP,
                       lease_owner = NULL,
                       lease_expires_at = NULL,
                       last_error_code = COALESCE(last_error_code, 'CLEANUP_LEASE_EXPIRED'),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE cleanup_status = 'LEASED'
                   AND lease_expires_at < CURRENT_TIMESTAMP
                """);
    }

    private String normalizeReason(String reason) {
        String normalized = reason == null ? "ASSET_REPLACED"
                : reason.trim().toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        if (normalized.length() < 3 || normalized.length() > 80) {
            throw new IllegalArgumentException("Invalid tenant media cleanup reason.");
        }
        return normalized;
    }

    private record MediaAssetState(int referenceCount, String status) {}

    record CleanupJob(UUID cleanupId, Long tenantId, String storageKey, int attemptCount) {}
}
