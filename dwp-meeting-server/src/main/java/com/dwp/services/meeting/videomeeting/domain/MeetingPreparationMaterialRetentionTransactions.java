package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
class MeetingPreparationMaterialRetentionTransactions {
    private static final String WORKER = "PREPARATION_MATERIALS";
    private static final String LOCK = "dwp-meeting-preparation-material-retention-v1";
    private final JdbcTemplate jdbc;

    MeetingPreparationMaterialRetentionTransactions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    int purge(OffsetDateTime now, int batchSize) {
        Boolean claimed = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                Boolean.class, LOCK);
        if (!Boolean.TRUE.equals(claimed)) return -1;
        jdbc.update("""
                UPDATE vm_meeting_material_retention_state
                   SET last_attempt_at = ?, version = version + 1
                 WHERE worker_key = ?
                """, now, WORKER);
        int deleted = jdbc.update("""
                WITH expired AS (
                    SELECT material_id
                      FROM vm_meeting_preparation_materials
                     WHERE retention_until <= ?
                     ORDER BY retention_until, material_id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                DELETE FROM vm_meeting_preparation_materials material
                 USING expired
                 WHERE material.material_id = expired.material_id
                """, now, batchSize);
        jdbc.update("""
                UPDATE vm_meeting_material_retention_state
                   SET last_success_at = ?, last_failure_at = NULL, last_error_code = NULL,
                       version = version + 1
                 WHERE worker_key = ?
                """, now, WORKER);
        jdbc.update("""
                INSERT INTO vm_meeting_material_retention_evidence (
                    execution_id, outcome, deleted_count)
                VALUES (?, 'SUCCEEDED', ?)
                """, UUID.randomUUID(), deleted);
        return deleted;
    }

    @Transactional
    void recordFailure(OffsetDateTime now) {
        jdbc.update("""
                UPDATE vm_meeting_material_retention_state
                   SET last_attempt_at = ?, last_failure_at = ?,
                       last_error_code = 'PURGE_FAILED', version = version + 1
                 WHERE worker_key = ?
                """, now, now, WORKER);
        jdbc.update("""
                INSERT INTO vm_meeting_material_retention_evidence (
                    execution_id, outcome, deleted_count, error_code)
                VALUES (?, 'FAILED', 0, 'PURGE_FAILED')
                """, UUID.randomUUID());
    }

    boolean ready(OffsetDateTime threshold, OffsetDateTime now) {
        Boolean ready = jdbc.queryForObject("""
                SELECT last_success_at >= ?
                       AND (last_failure_at IS NULL OR last_success_at >= last_failure_at)
                       AND NOT EXISTS (
                           SELECT 1
                             FROM vm_meeting_preparation_materials
                            WHERE retention_until <= ?
                       )
                  FROM vm_meeting_material_retention_state
                 WHERE worker_key = ?
                """, Boolean.class, threshold, now, WORKER);
        return Boolean.TRUE.equals(ready);
    }
}
