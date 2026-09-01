package com.dwp.services.platform.auditcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class AuditControlExportIntegrityRepository extends AuditControlPolicyRepository {
    AuditControlExportIntegrityRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }

    public UUID createExport(Long tenantId, String actorId, String criteria, String format) {
        return jdbc.queryForObject("""
                INSERT INTO sys_audit_export_jobs (tenant_id, requested_by, criteria, format, status)
                VALUES (:tenantId, :actor, CAST(:criteria AS jsonb), :format, 'RUNNING')
                RETURNING export_job_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("actor", actorId)
                .addValue("criteria", criteria).addValue("format", format), UUID.class);
    }

    public void completeExport(UUID exportId, byte[] content, int rows, String sha256) {
        jdbc.update("""
                UPDATE sys_audit_export_jobs SET status = 'COMPLETED', content = :content,
                       row_count = :rows, content_sha256 = :sha256,
                       completed_at = CURRENT_TIMESTAMP, expires_at = CURRENT_TIMESTAMP + INTERVAL '24 hours'
                 WHERE export_job_id = :exportId
                """, new MapSqlParameterSource("exportId", exportId)
                .addValue("content", content).addValue("rows", rows).addValue("sha256", sha256));
    }

    public Optional<byte[]> exportContent(Long tenantId, UUID exportId) {
        List<byte[]> rows = jdbc.query("""
                SELECT content FROM sys_audit_export_jobs
                 WHERE tenant_id = :tenantId AND export_job_id = :exportId
                   AND status = 'COMPLETED' AND expires_at > CURRENT_TIMESTAMP
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("exportId", exportId),
                (rs, row) -> rs.getBytes("content"));
        return rows.stream().findFirst();
    }

    public Optional<AuditControlDtos.ExportJob> exportJob(Long tenantId, UUID exportId) {
        List<AuditControlDtos.ExportJob> rows = jdbc.query("""
                SELECT export_job_id, format, status, row_count, content_sha256, error_message,
                       requested_at, completed_at, expires_at
                  FROM sys_audit_export_jobs
                 WHERE tenant_id = :tenantId AND export_job_id = :exportId
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("exportId", exportId),
                (rs, row) -> new AuditControlDtos.ExportJob(
                        rs.getObject("export_job_id", UUID.class), rs.getString("format"),
                        rs.getString("status"), (Integer) rs.getObject("row_count"),
                        rs.getString("content_sha256"), rs.getString("error_message"),
                        instant(rs, "requested_at"), instant(rs, "completed_at"), instant(rs, "expires_at")));
        return rows.stream().findFirst();
    }

    public List<AuditControlDtos.IntegrityCheckpoint> integrity(Long tenantId) {
        return jdbc.query("""
                SELECT checkpoint_id, checkpoint_date, record_count, first_event_at, last_event_at,
                       root_hash, checkpoint_hash, signature_algorithm, verification_status,
                       created_at, verified_at
                  FROM sys_audit_integrity_checkpoints
                 WHERE tenant_id = :tenantId ORDER BY checkpoint_date DESC LIMIT 90
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, row) ->
                new AuditControlDtos.IntegrityCheckpoint(
                        rs.getObject("checkpoint_id", UUID.class), rs.getObject("checkpoint_date", LocalDate.class),
                        rs.getLong("record_count"), instant(rs, "first_event_at"), instant(rs, "last_event_at"),
                        rs.getString("root_hash"), rs.getString("checkpoint_hash"),
                        rs.getString("signature_algorithm"), rs.getString("verification_status"),
                        instant(rs, "created_at"), instant(rs, "verified_at")));
    }

    public Map<String, Object> integritySource(Long tenantId, LocalDate date) {
        return jdbc.queryForMap("""
                SELECT COUNT(*) record_count, MIN(occurred_at) first_event_at,
                       MAX(occurred_at) last_event_at,
                       COALESCE(string_agg(record_hash, '' ORDER BY occurred_at, event_id), '') hashes
                  FROM sys_audit_events
                 WHERE tenant_id = :tenantId
                   AND occurred_at >= :from AND occurred_at < :to
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("from", Timestamp.from(date.atStartOfDay().toInstant(ZoneOffset.UTC)))
                .addValue("to", Timestamp.from(date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))));
    }

    public String previousCheckpointHash(Long tenantId, LocalDate date) {
        List<String> rows = jdbc.query("""
                SELECT checkpoint_hash FROM sys_audit_integrity_checkpoints
                 WHERE tenant_id = :tenantId AND checkpoint_date < :date
                 ORDER BY checkpoint_date DESC LIMIT 1
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("date", date),
                (rs, row) -> rs.getString(1));
        return rows.stream().findFirst().orElse(null);
    }

    public void saveCheckpoint(
            Long tenantId, LocalDate date, long count, Instant first, Instant last,
            String rootHash, String previous, String checkpointHash, String signature,
            String verificationStatus) {
        jdbc.update("""
                INSERT INTO sys_audit_integrity_checkpoints (
                    tenant_id, checkpoint_date, record_count, first_event_at, last_event_at,
                    root_hash, previous_checkpoint_hash, checkpoint_hash, signature,
                    verification_status, verified_at)
                VALUES (:tenantId, :date, :count, :first, :last, :root, :previous,
                        :checkpoint, :signature, :status, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, checkpoint_date) DO UPDATE SET
                    verification_status = EXCLUDED.verification_status,
                    verified_at = CURRENT_TIMESTAMP
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("date", date)
                .addValue("count", count).addValue("first", timestamp(first)).addValue("last", timestamp(last))
                .addValue("root", rootHash).addValue("previous", previous)
                .addValue("checkpoint", checkpointHash).addValue("signature", signature)
                .addValue("status", verificationStatus));
    }

    public List<Long> activeTenants() {
        return jdbc.query("SELECT DISTINCT tenant_id FROM sys_audit_events",
                (rs, row) -> rs.getLong(1));
    }

    public int applyRetention() {
        jdbc.getJdbcTemplate().execute("SET LOCAL dwp.audit_retention_bypass = 'on'");
        return jdbc.getJdbcTemplate().update("""
                DELETE FROM sys_audit_events event
                 USING sys_audit_retention_policies policy
                 WHERE event.tenant_id = policy.tenant_id
                   AND event.retention_class <> 'LEGAL_HOLD'
                   AND event.occurred_at < CURRENT_TIMESTAMP -
                       make_interval(days => CASE
                           WHEN event.retention_class = 'EXTENDED'
                               THEN policy.extended_retention_days
                           ELSE policy.standard_retention_days END)
                """);
    }

}
