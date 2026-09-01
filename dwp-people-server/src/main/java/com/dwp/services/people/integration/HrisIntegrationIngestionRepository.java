package com.dwp.services.people.integration;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.UUID;

class HrisIntegrationIngestionRepository extends HrisIntegrationConnectorRepository {
    HrisIntegrationIngestionRepository(NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    public HrisIntegrationRepository.Receipt acquireReceipt(
            Long tenantId,
            long sourceSystemId,
            String idempotencyKey,
            String payloadSha256) {
        String insert = """
                INSERT INTO int_ingestion_receipts (
                    tenant_id, source_system_id, idempotency_key, payload_sha256)
                VALUES (:tenantId, :sourceSystemId, :idempotencyKey, :payloadSha256)
                ON CONFLICT (tenant_id, source_system_id, idempotency_key) DO NOTHING
                RETURNING ingestion_receipt_id, sync_run_id, lifecycle_state, payload_sha256
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource("tenantId", tenantId)
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("payloadSha256", payloadSha256);
        List<HrisIntegrationRepository.Receipt> inserted = jdbc.query(insert, parameters, (rs, rowNum) -> new HrisIntegrationRepository.Receipt(
                rs.getLong("ingestion_receipt_id"),
                (UUID) rs.getObject("sync_run_id"),
                rs.getString("lifecycle_state"),
                rs.getString("payload_sha256"),
                true));
        if (!inserted.isEmpty()) return inserted.get(0);
        String select = """
                SELECT ingestion_receipt_id, sync_run_id, lifecycle_state, payload_sha256
                  FROM int_ingestion_receipts
                 WHERE tenant_id = :tenantId
                   AND source_system_id = :sourceSystemId
                   AND idempotency_key = :idempotencyKey
                """;
        return jdbc.query(select, parameters, (rs, rowNum) -> new HrisIntegrationRepository.Receipt(
                        rs.getLong("ingestion_receipt_id"),
                        (UUID) rs.getObject("sync_run_id"),
                        rs.getString("lifecycle_state"),
                        rs.getString("payload_sha256"),
                        false))
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Ingestion receipt disappeared."));
    }

    public void startRun(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            long receiptId,
            UUID syncRunId,
            String correlationId,
            String syncMode,
            String watermark,
            UUID connectorInstanceId,
            UUID mappingProfileId,
            UUID retryOfSyncRunId,
            int pageCount) {
        String runSql = """
                INSERT INTO int_sync_runs (
                    sync_run_id, tenant_id, source_system_id, correlation_id,
                    sync_mode, lifecycle_state, requested_watermark, started_at,
                    connector_instance_id, mapping_profile_id, retry_of_sync_run_id, page_count,
                    created_by, updated_by)
                VALUES (
                    :syncRunId, :tenantId, :sourceSystemId, :correlationId,
                    :syncMode, 'RUNNING', :watermark, CURRENT_TIMESTAMP,
                    :connectorInstanceId, :mappingProfileId, :retryOfSyncRunId, :pageCount,
                    :actorId, :actorId)
                """;
        MapSqlParameterSource parameters = params(tenantId, actorId)
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("syncRunId", syncRunId)
                .addValue("correlationId", correlationId)
                .addValue("syncMode", syncMode)
                .addValue("watermark", watermark)
                .addValue("connectorInstanceId", connectorInstanceId)
                .addValue("mappingProfileId", mappingProfileId)
                .addValue("retryOfSyncRunId", retryOfSyncRunId)
                .addValue("pageCount", pageCount);
        jdbc.update(runSql, parameters);
        jdbc.update("""
                UPDATE int_ingestion_receipts
                   SET sync_run_id = :syncRunId
                 WHERE tenant_id = :tenantId AND ingestion_receipt_id = :receiptId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("receiptId", receiptId)
                .addValue("syncRunId", syncRunId));
    }


    public void emitProjectionChanged(
            Long tenantId,
            UUID personPublicId,
            UUID syncRunId,
            String correlationId,
            HrisModels.WorkerRecord worker,
            String jobTitle) {
        jdbc.update("""
                INSERT INTO sys_people_outbox_events (
                    tenant_id, aggregate_type, aggregate_id, event_type,
                    payload, correlation_id)
                VALUES (
                    :tenantId, 'PERSON', :personPublicId,
                    'people.worker-projection.changed',
                    jsonb_build_object(
                        'contractVersion', 1,
                        'personPublicId', :personPublicId,
                        'externalId', :externalId,
                        'displayName', :displayName,
                        'givenName', :givenName,
                        'familyName', :familyName,
                        'workEmail', :workEmail,
                        'jobTitle', :jobTitle,
                        'preferredLocale', :preferredLocale,
                        'workerStatus', :workerStatus,
                        'sourceVersion', :sourceVersion,
                        'syncRunId', :syncRunId
                    ),
                    :correlationId)
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("personPublicId", personPublicId.toString())
                .addValue("externalId", worker.externalId())
                .addValue("displayName", worker.displayName())
                .addValue("givenName", worker.givenName())
                .addValue("familyName", worker.familyName())
                .addValue("workEmail", worker.workEmail())
                .addValue("jobTitle", jobTitle)
                .addValue("preferredLocale", worker.preferredLocale())
                .addValue("workerStatus", worker.workerStatus())
                .addValue("sourceVersion", worker.sourceVersion())
                .addValue("syncRunId", syncRunId.toString())
                .addValue("correlationId", correlationId));
    }

    public void completeRun(
            Long tenantId,
            long sourceSystemId,
            long receiptId,
            UUID syncRunId,
            UUID connectorInstanceId,
            String watermark,
            long read,
            long created,
            long updated,
            long rejected) {
        jdbc.update("""
                UPDATE int_sync_runs
                   SET lifecycle_state = CASE WHEN :rejected = 0 THEN 'SUCCEEDED' ELSE 'PARTIAL' END,
                       committed_watermark = :watermark,
                       read_count = :read,
                       created_count = :created,
                       updated_count = :updated,
                       rejected_count = :rejected,
                       version = version + 1,
                       completed_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND sync_run_id = :syncRunId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("syncRunId", syncRunId)
                .addValue("watermark", watermark)
                .addValue("read", read)
                .addValue("created", created)
                .addValue("updated", updated)
                .addValue("rejected", rejected));
        jdbc.update("""
                UPDATE int_ingestion_receipts
                   SET lifecycle_state = 'SUCCEEDED', completed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND ingestion_receipt_id = :receiptId
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("receiptId", receiptId));
        String connectorPredicate = connectorInstanceId == null
                ? "source_system_id = :sourceSystemId"
                : "connector_instance_id = :connectorInstanceId";
        MapSqlParameterSource connectorParameters = new MapSqlParameterSource("tenantId", tenantId)
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("connectorInstanceId", connectorInstanceId);
        jdbc.update("""
                UPDATE int_connector_instances
                   SET last_successful_sync_at = CURRENT_TIMESTAMP,
                       last_attempted_sync_at = CURRENT_TIMESTAMP,
                       last_health_checked_at = CURRENT_TIMESTAMP,
                       health_state = 'HEALTHY',
                       last_error_code = NULL,
                       consecutive_failure_count = 0,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND %s
                """.formatted(connectorPredicate), connectorParameters);
        if (connectorInstanceId != null) {
            jdbc.update("""
                    INSERT INTO int_connector_cursors (
                        tenant_id, connector_instance_id, cursor_type,
                        committed_cursor, committed_at, sync_run_id)
                    VALUES (
                        :tenantId, :connectorInstanceId, 'WATERMARK',
                        :watermark, CURRENT_TIMESTAMP, :syncRunId)
                    ON CONFLICT (tenant_id, connector_instance_id, cursor_type) DO UPDATE SET
                        committed_cursor = EXCLUDED.committed_cursor,
                        committed_at = EXCLUDED.committed_at,
                        sync_run_id = EXCLUDED.sync_run_id,
                        version = int_connector_cursors.version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    """, new MapSqlParameterSource("tenantId", tenantId)
                    .addValue("connectorInstanceId", connectorInstanceId)
                    .addValue("watermark", watermark)
                    .addValue("syncRunId", syncRunId));
        }
    }

    public void auditImport(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            UUID syncRunId,
            String correlationId,
            long read,
            long created,
            long updated) {
        jdbc.update("""
                INSERT INTO sys_people_audit_events (
                    tenant_id, actor_type, actor_id, action, target_type, target_id,
                    outcome, correlation_id, source_system_id, after_snapshot)
                VALUES (
                    :tenantId, 'USER', :actorId, 'people.hris-import.completed',
                    'SYNC_RUN', :syncRunId, 'SUCCESS', :correlationId,
                    :sourceSystemId,
                    jsonb_build_object('readCount', :read, 'createdCount', :created, 'updatedCount', :updated))
                """, params(tenantId, actorId)
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("syncRunId", syncRunId.toString())
                .addValue("correlationId", correlationId)
                .addValue("read", read)
                .addValue("created", created)
                .addValue("updated", updated));
    }

}
