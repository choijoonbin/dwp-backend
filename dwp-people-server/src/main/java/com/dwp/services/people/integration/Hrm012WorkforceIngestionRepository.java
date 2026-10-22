package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012CanonicalJson;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** V60 run/item ledger and atomic reconciliation owner. */
@Repository
@ConditionalOnProperty(prefix = "dwp.hris.data-quality-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public class Hrm012WorkforceIngestionRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    Hrm012WorkforceIngestionRepository(
            NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Run createRun(CreateRun value) {
        establishTenant(value.tenantId());
        UUID runId = UUID.randomUUID();
        UUID publicId = UUID.randomUUID();
        List<Run> inserted = jdbc.query("""
                INSERT INTO ppl_workforce_ingestion_runs(
                    workforce_ingestion_run_id,run_public_id,tenant_id,
                    command_receipt_id,linked_replay_receipt_id,linked_replay_run_id,
                    connector_instance_id,connector_public_id,connector_version,
                    mapping_profile_id,mapping_public_id,mapping_version,mapping_sha256,
                    legal_entity_public_ids,population_scope_digest,
                    object_ref,object_digest,schema_key,schema_version,mode,effective_date,
                    lifecycle_state,cursor_before_digest,cursor_expected_version)
                VALUES(:run,:publicId,:tenant,:receipt,:replayReceipt,:replayRun,
                    :connector,:connectorPublic,:connectorVersion,
                    :mapping,:mappingPublic,:mappingVersion,:mappingDigest,
                    :legalEntities,:population,:objectRef,:objectDigest,:schemaKey,
                    :schemaVersion,:mode,:effectiveDate,'RECEIVED',:cursorBefore,
                    :cursorVersion)
                ON CONFLICT (tenant_id,command_receipt_id) DO NOTHING
                RETURNING *
                """, runParameters(value).addValue("run", runId)
                .addValue("publicId", publicId), this::run);
        if (!inserted.isEmpty()) return inserted.getFirst();
        Run existing = findByReceipt(value.tenantId(), value.receiptId())
                .orElseThrow(() -> Hrm012Problem.conflict(
                        "HRM012_RUN_RESERVATION_CONFLICT",
                        "The receipt run reservation conflicted."));
        requireSame(existing, value);
        return existing;
    }

    @Transactional
    public Optional<Run> claim(
            long tenantId, UUID runPublicId, String worker, Instant now,
            Duration lease) {
        establishTenant(tenantId);
        if (!Hrm012WorkforceIngestionContract.canonical(worker, 160)
                || lease == null || lease.isNegative() || lease.isZero()
                || lease.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("Invalid HRM-012 worker lease.");
        }
        return jdbc.query("""
                UPDATE ppl_workforce_ingestion_runs
                   SET lifecycle_state='RUNNING',lease_owner=:worker,
                       lease_until=:leaseUntil,attempt_count=attempt_count+1,
                       next_attempt_at=NULL,version=version+1
                 WHERE tenant_id=:tenant AND run_public_id=:run
                   AND (lifecycle_state='RECEIVED'
                        OR (lifecycle_state='FAILED_RETRYABLE'
                            AND next_attempt_at<=:now)
                        OR (lifecycle_state='RUNNING'
                            AND lease_until<=:now))
                RETURNING *
                """, params(tenantId).addValue("run", runPublicId)
                .addValue("worker", worker)
                .addValue("now", Timestamp.from(now))
                .addValue("leaseUntil", Timestamp.from(now.plus(lease))), this::run)
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<Run> findByReceipt(long tenantId, UUID receiptId) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT run.* FROM ppl_workforce_ingestion_runs run
                 WHERE run.tenant_id=:tenant AND run.command_receipt_id=:receipt
                """, params(tenantId).addValue("receipt", receiptId), this::run)
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public boolean duplicate(
            long tenantId, UUID connectorId, UUID currentRunId,
            String recordKeyDigest, String sourceVersionDigest, String inputDigest) {
        establishTenant(tenantId);
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM ppl_workforce_ingestion_items item
                  JOIN ppl_workforce_ingestion_runs run
                    ON run.tenant_id=item.tenant_id
                   AND run.workforce_ingestion_run_id=item.workforce_ingestion_run_id
                 WHERE item.tenant_id=:tenant
                   AND item.connector_instance_id=:connector
                   AND item.workforce_ingestion_run_id<>:run
                   AND item.source_record_key_digest=:recordKey
                   AND item.source_version_digest=:sourceVersion
                   AND item.input_digest=:input
                   AND item.outcome IN (
                       'VALIDATED','APPLIED','DUPLICATE',
                       'QUARANTINED_AFTER_ACCEPTANCE')
                   AND run.lifecycle_state='SUCCEEDED'
                """, params(tenantId).addValue("connector", connectorId)
                .addValue("run", currentRunId).addValue("recordKey", recordKeyDigest)
                .addValue("sourceVersion", sourceVersionDigest)
                .addValue("input", inputDigest), Integer.class);
        return count != null && count > 0;
    }

    @Transactional
    public void reconcileValidation(
            Run run, List<Hrm012WorkforceIngestionContract.ItemEvidence> items,
            Hrm012WorkforceIngestionContract.Counts counts,
            String resultDigest, Instant completedAt) {
        requireRunning(run, Hrm012WorkforceIngestionContract.Mode.VALIDATE_ONLY);
        establishTenant(run.tenantId());
        insertItems(run, items);
        appendAudit(run, counts, resultDigest, completedAt);
        completeReceipt(run, resultDigest, completedAt);
        completeRun(run, counts, resultDigest, null, null, completedAt);
    }

    @Transactional
    public void reconcileApply(
            Run run, List<Hrm012WorkforceIngestionContract.ItemEvidence> items,
            Hrm012WorkforceIngestionContract.Counts counts,
            String resultDigest, String nextCursor, String nextCursorDigest,
            Instant completedAt) {
        requireRunning(run, Hrm012WorkforceIngestionContract.Mode.APPLY);
        establishTenant(run.tenantId());
        insertItems(run, items);
        advanceCursor(run, nextCursor, completedAt);
        appendAudit(run, counts, resultDigest, completedAt);
        appendOutbox(run, counts, resultDigest, completedAt);
        completeReceipt(run, resultDigest, completedAt);
        completeRun(run, counts, resultDigest, nextCursorDigest,
                run.cursorExpectedVersion() + 1, completedAt);
    }

    @Transactional
    public void retryable(
            Run run, String code, String redactedSummary,
            Instant retryAt) {
        requireRunning(run, run.mode());
        establishTenant(run.tenantId());
        int updated = jdbc.update("""
                UPDATE ppl_workforce_ingestion_runs
                   SET lifecycle_state='FAILED_RETRYABLE',lease_owner=NULL,lease_until=NULL,
                       next_attempt_at=:retryAt,failure_code=:code,
                       redacted_failure_summary=:summary,version=version+1
                 WHERE tenant_id=:tenant AND workforce_ingestion_run_id=:run
                   AND lifecycle_state='RUNNING' AND version=:version
                """, params(run.tenantId()).addValue("run", run.runId())
                .addValue("version", run.version()).addValue("retryAt", Timestamp.from(retryAt))
                .addValue("code", code).addValue("summary", redactedSummary));
        if (updated != 1) throw Hrm012Problem.conflict(
                "HRM012_RUN_LEASE_CONFLICT", "The ingestion lease was lost.");
        jdbc.update("""
                UPDATE ppl_command_receipts
                   SET lifecycle_state='FAILED_RETRYABLE',next_attempt_at=:retryAt,
                       last_error_code=:code,lease_owner=NULL,lease_until=NULL
                 WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                   AND lifecycle_state NOT IN ('SUCCEEDED','REJECTED','DEAD','CANCELLED')
                """, params(run.tenantId()).addValue("receipt", run.receiptId())
                .addValue("retryAt", Timestamp.from(retryAt)).addValue("code", code));
    }

    private void insertItems(
            Run run, List<Hrm012WorkforceIngestionContract.ItemEvidence> items) {
        for (Hrm012WorkforceIngestionContract.ItemEvidence item : items) {
            int count = jdbc.update("""
                    INSERT INTO ppl_workforce_ingestion_items(
                        item_public_id,tenant_id,workforce_ingestion_run_id,
                        connector_instance_id,item_sequence,source_record_key_digest,
                        source_version_digest,input_digest,outcome,outcome_digest,
                        finding_code,redacted_summary,applied_effect_digest,
                        quarantine_ciphertext,quarantine_key_reference,
                        quarantine_envelope_digest)
                    VALUES(:publicId,:tenant,:run,:connector,:sequence,:recordKey,
                        :sourceVersion,:input,:outcome,:outcomeDigest,:finding,
                        :summary,:effect,:ciphertext,:keyReference,:envelopeDigest)
                    ON CONFLICT (tenant_id,workforce_ingestion_run_id,item_sequence)
                    DO NOTHING
                    """, params(run.tenantId()).addValue("publicId", UUID.randomUUID())
                    .addValue("run", run.runId()).addValue("connector", run.connectorId())
                    .addValue("sequence", item.sequence())
                    .addValue("recordKey", item.sourceRecordKeyDigest())
                    .addValue("sourceVersion", item.sourceVersionDigest())
                    .addValue("input", item.inputDigest())
                    .addValue("outcome", item.outcome().name())
                    .addValue("outcomeDigest", item.outcomeDigest())
                    .addValue("finding", item.findingCode())
                    .addValue("summary", item.redactedSummary())
                    .addValue("effect", item.appliedEffectDigest())
                    .addValue("ciphertext", item.quarantineCiphertext())
                    .addValue("keyReference", item.quarantineKeyReference())
                    .addValue("envelopeDigest", item.quarantineEnvelopeDigest()));
            if (count == 0 && !exactItem(run, item)) {
                throw Hrm012Problem.conflict("HRM012_ITEM_EVIDENCE_CONFLICT",
                        "Immutable item evidence conflicts with recovery.");
            }
        }
    }

    private boolean exactItem(
            Run run, Hrm012WorkforceIngestionContract.ItemEvidence item) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM ppl_workforce_ingestion_items
                 WHERE tenant_id=:tenant AND workforce_ingestion_run_id=:run
                   AND item_sequence=:sequence
                   AND source_record_key_digest=:recordKey
                   AND source_version_digest=:sourceVersion AND input_digest=:input
                   AND outcome=:outcome AND outcome_digest=:outcomeDigest
                   AND finding_code IS NOT DISTINCT FROM :finding
                   AND redacted_summary IS NOT DISTINCT FROM :summary
                   AND applied_effect_digest IS NOT DISTINCT FROM :effect
                   AND quarantine_ciphertext IS NOT DISTINCT FROM :ciphertext
                   AND quarantine_key_reference IS NOT DISTINCT FROM :keyReference
                   AND quarantine_envelope_digest IS NOT DISTINCT FROM :envelopeDigest
                """, params(run.tenantId()).addValue("run", run.runId())
                .addValue("sequence", item.sequence())
                .addValue("recordKey", item.sourceRecordKeyDigest())
                .addValue("sourceVersion", item.sourceVersionDigest())
                .addValue("input", item.inputDigest()).addValue("outcome", item.outcome().name())
                .addValue("outcomeDigest", item.outcomeDigest())
                .addValue("finding", item.findingCode()).addValue("summary", item.redactedSummary())
                .addValue("effect", item.appliedEffectDigest())
                .addValue("ciphertext", item.quarantineCiphertext())
                .addValue("keyReference", item.quarantineKeyReference())
                .addValue("envelopeDigest", item.quarantineEnvelopeDigest()), Integer.class);
        return count != null && count == 1;
    }

    private void advanceCursor(Run run, String nextCursor, Instant now) {
        int count = jdbc.update("""
                INSERT INTO int_connector_cursors(
                    tenant_id,connector_instance_id,cursor_type,committed_cursor,
                    committed_at,version,created_at,updated_at)
                VALUES(:tenant,:connector,'OPAQUE_TOKEN',:cursor,:now,1,:now,:now)
                ON CONFLICT (tenant_id,connector_instance_id,cursor_type) DO UPDATE SET
                    committed_cursor=EXCLUDED.committed_cursor,
                    committed_at=EXCLUDED.committed_at,
                    version=int_connector_cursors.version+1,
                    updated_at=EXCLUDED.updated_at
                WHERE int_connector_cursors.version=:expectedVersion
                """, params(run.tenantId()).addValue("connector", run.connectorId())
                .addValue("cursor", nextCursor).addValue("now", Timestamp.from(now))
                .addValue("expectedVersion", run.cursorExpectedVersion()));
        if (count != 1) throw Hrm012Problem.conflict(
                "HRM012_CURSOR_CAS_CONFLICT", "The connector cursor changed.");
    }

    private void appendAudit(
            Run run, Hrm012WorkforceIngestionContract.Counts counts,
            String resultDigest, Instant now) {
        int count = jdbc.update("""
                INSERT INTO sys_people_audit_events(
                    audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                    target_id,outcome,correlation_id,after_snapshot,occurred_at)
                VALUES(:event,:tenant,'USER',:actor,'workforce-ingestion.reconciled',
                    'WORKFORCE_INGESTION_RUN',:target,'SUCCESS',:correlation,
                    CAST(:snapshot AS jsonb),:now)
                """, params(run.tenantId()).addValue("event", deterministicId(
                        "audit", run.tenantId(), run.runPublicId(), resultDigest))
                .addValue("actor", run.actorPublicId().toString())
                .addValue("target", run.runPublicId().toString())
                .addValue("correlation", run.correlationId().toString())
                .addValue("snapshot", json(Map.of(
                        "mode", run.mode().name(), "sourceCount", counts.sourceCount(),
                        "resultDigest", resultDigest)))
                .addValue("now", Timestamp.from(now)));
        if (count != 1) throw new IllegalStateException("HRM-012 audit insert failed.");
    }

    private void appendOutbox(
            Run run, Hrm012WorkforceIngestionContract.Counts counts,
            String resultDigest, Instant now) {
        Hrm012WorkforceIngestionContract.ReconciledEvent event =
                new Hrm012WorkforceIngestionContract.ReconciledEvent(
                        run.runPublicId(), run.connectorPublicId(), run.mappingVersion(),
                        counts.sourceCount(), counts.acceptedCount(), counts.rejectedCount(),
                        counts.duplicateCount(), counts.quarantinedCount(),
                        counts.appliedCount(), resultDigest);
        UUID eventId = deterministicId(
                Hrm012WorkforceIngestionContract.EVENT_TYPE,
                run.tenantId(), run.runPublicId(), resultDigest);
        String payload = json(event);
        int count = jdbc.update("""
                INSERT INTO sys_people_outbox_events(
                    event_id,tenant_id,aggregate_type,aggregate_id,event_type,
                    payload,correlation_id,occurred_at)
                VALUES(:event,:tenant,'WorkforceIngestionRun',:aggregate,:type,
                    CAST(:payload AS jsonb),:correlation,:now)
                ON CONFLICT (event_id) DO NOTHING
                """, params(run.tenantId()).addValue("event", eventId)
                .addValue("aggregate", run.runPublicId().toString())
                .addValue("type", Hrm012WorkforceIngestionContract.EVENT_TYPE)
                .addValue("payload", payload)
                .addValue("correlation", run.correlationId().toString())
                .addValue("now", Timestamp.from(now)));
        if (count == 1) return;
        Integer exact = jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_outbox_events
                 WHERE event_id=:event AND tenant_id=:tenant
                   AND aggregate_type='WorkforceIngestionRun'
                   AND aggregate_id=:aggregate AND event_type=:type
                   AND payload=CAST(:payload AS jsonb)
                   AND correlation_id=:correlation AND occurred_at=:now
                """, params(run.tenantId()).addValue("event", eventId)
                .addValue("aggregate", run.runPublicId().toString())
                .addValue("type", Hrm012WorkforceIngestionContract.EVENT_TYPE)
                .addValue("payload", payload)
                .addValue("correlation", run.correlationId().toString())
                .addValue("now", Timestamp.from(now)), Integer.class);
        if (exact == null || exact != 1) throw Hrm012Problem.conflict(
                "HRM012_OUTBOX_CONFLICT", "The reconciled event conflicts.");
    }

    private void completeRun(
            Run run, Hrm012WorkforceIngestionContract.Counts counts,
            String resultDigest, String cursorAfterDigest,
            Long cursorCommittedVersion, Instant now) {
        int count = jdbc.update("""
                UPDATE ppl_workforce_ingestion_runs
                   SET lifecycle_state='SUCCEEDED',lease_owner=NULL,lease_until=NULL,
                       source_count=:source,accepted_count=:accepted,
                       rejected_count=:rejected,duplicate_count=:duplicate,
                       quarantined_count=:quarantined,applied_count=:applied,
                       result_digest=:result,cursor_after_digest=:cursorAfter,
                       cursor_committed_version=:cursorVersion,completed_at=:now,
                       version=version+1
                 WHERE tenant_id=:tenant AND workforce_ingestion_run_id=:run
                   AND lifecycle_state='RUNNING' AND version=:version
                """, params(run.tenantId()).addValue("run", run.runId())
                .addValue("version", run.version()).addValue("source", counts.sourceCount())
                .addValue("accepted", counts.acceptedCount())
                .addValue("rejected", counts.rejectedCount())
                .addValue("duplicate", counts.duplicateCount())
                .addValue("quarantined", counts.quarantinedCount())
                .addValue("applied", counts.appliedCount()).addValue("result", resultDigest)
                .addValue("cursorAfter", cursorAfterDigest)
                .addValue("cursorVersion", cursorCommittedVersion)
                .addValue("now", Timestamp.from(now)));
        if (count != 1) throw Hrm012Problem.conflict(
                "HRM012_RUN_LEASE_CONFLICT", "The ingestion lease was lost.");
    }

    private void completeReceipt(Run run, String resultDigest, Instant now) {
        int count = jdbc.update("""
                UPDATE ppl_command_receipts
                   SET lifecycle_state='SUCCEEDED',result_code='WORKFORCE_INGESTION_SUCCEEDED',
                       result_reference=COALESCE(result_reference,'{}'::jsonb)
                           || CAST(:result AS jsonb),completed_at=:now,updated_at=:now,
                       lease_owner=NULL,lease_until=NULL,next_attempt_at=NULL,
                       last_error_code=NULL
                 WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                   AND lifecycle_state NOT IN ('SUCCEEDED','REJECTED','DEAD','CANCELLED')
                """, params(run.tenantId()).addValue("receipt", run.receiptId())
                .addValue("result", json(Map.of(
                        "runPublicId", run.runPublicId().toString(),
                        "resultDigest", resultDigest)))
                .addValue("now", Timestamp.from(now)));
        if (count != 1) throw Hrm012Problem.conflict(
                "HRM012_RECEIPT_COMPLETION_CONFLICT", "The receipt changed.");
    }

    private MapSqlParameterSource runParameters(CreateRun value) {
        Hrm012WorkforceIngestionContract.OwnerMetadata metadata = value.metadata();
        Hrm012WorkforceIngestionContract.Command command = value.command();
        return params(value.tenantId()).addValue("receipt", value.receiptId())
                .addValue("replayReceipt", value.linkedReplayReceiptId())
                .addValue("replayRun", value.linkedReplayRunId())
                .addValue("connector", metadata.connectorInstanceId())
                .addValue("connectorPublic", metadata.connectorPublicId())
                .addValue("connectorVersion", metadata.connectorVersion())
                .addValue("mapping", metadata.mappingProfileId())
                .addValue("mappingPublic", metadata.mappingPublicId())
                .addValue("mappingVersion", metadata.mappingVersion())
                .addValue("mappingDigest", metadata.mappingSha256())
                .addValue("legalEntities", metadata.legalEntityPublicIds().toArray(UUID[]::new))
                .addValue("population", value.populationScopeDigest())
                .addValue("objectRef", command.objectRef())
                .addValue("objectDigest", command.objectDigest())
                .addValue("schemaKey", command.schemaKey())
                .addValue("schemaVersion", command.schemaVersion())
                .addValue("mode", command.mode().name())
                .addValue("effectiveDate", command.effectiveDate())
                .addValue("cursorBefore", metadata.cursorBeforeDigest())
                .addValue("cursorVersion", metadata.cursorExpectedVersion());
    }

    private Run run(ResultSet result, int ignored) throws SQLException {
        long tenant = result.getLong("tenant_id");
        UUID receipt = result.getObject("command_receipt_id", UUID.class);
        ReceiptContext context = receiptContext(tenant, receipt);
        return new Run(
                result.getObject("workforce_ingestion_run_id", UUID.class),
                result.getObject("run_public_id", UUID.class), tenant, receipt,
                result.getObject("linked_replay_receipt_id", UUID.class),
                result.getObject("linked_replay_run_id", UUID.class),
                result.getObject("connector_instance_id", UUID.class),
                result.getObject("connector_public_id", UUID.class),
                result.getLong("connector_version"),
                result.getObject("mapping_profile_id", UUID.class),
                result.getObject("mapping_public_id", UUID.class),
                result.getLong("mapping_version"), result.getString("mapping_sha256"),
                legalEntityIds(result), result.getString("population_scope_digest"),
                result.getString("object_ref"), result.getString("object_digest"),
                result.getString("schema_key"), result.getInt("schema_version"),
                Hrm012WorkforceIngestionContract.Mode.valueOf(result.getString("mode")),
                result.getObject("effective_date", java.time.LocalDate.class),
                result.getString("lifecycle_state"), result.getString("cursor_before_digest"),
                result.getLong("cursor_expected_version"), result.getLong("version"),
                context.actorPublicId(), context.correlationId(),
                context.expectedVersion(),
                result.getTimestamp("created_at").toInstant());
    }

    private ReceiptContext receiptContext(long tenantId, UUID receiptId) {
        return jdbc.query("""
                SELECT actor_public_id,correlation_id,expected_version
                  FROM ppl_command_receipts
                 WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                """, params(tenantId).addValue("receipt", receiptId),
                (result, ignored) -> new ReceiptContext(
                        result.getObject("actor_public_id", UUID.class),
                        result.getObject("correlation_id", UUID.class),
                        result.getLong("expected_version")))
                .stream().findFirst().orElseThrow();
    }

    void requireSame(Run existing, CreateRun requested) {
        Hrm012WorkforceIngestionContract.OwnerMetadata metadata = requested.metadata();
        Hrm012WorkforceIngestionContract.Command command = requested.command();
        if (!existing.connectorId().equals(metadata.connectorInstanceId())
                || !existing.connectorPublicId().equals(metadata.connectorPublicId())
                || existing.connectorVersion() != metadata.connectorVersion()
                || !existing.mappingId().equals(metadata.mappingProfileId())
                || !existing.mappingPublicId().equals(metadata.mappingPublicId())
                || existing.mappingVersion() != metadata.mappingVersion()
                || !existing.mappingSha256().equals(metadata.mappingSha256())
                || !existing.legalEntityPublicIds().equals(
                metadata.legalEntityPublicIds())
                || !existing.populationScopeDigest().equals(
                requested.populationScopeDigest())
                || !existing.objectRef().equals(command.objectRef())
                || !existing.objectDigest().equals(command.objectDigest())
                || !existing.schemaKey().equals(command.schemaKey())
                || existing.schemaVersion() != command.schemaVersion()
                || existing.mode() != command.mode()
                || !existing.effectiveDate().equals(command.effectiveDate())
                || !existing.cursorBeforeDigest().equals(metadata.cursorBeforeDigest())
                || existing.cursorExpectedVersion() != metadata.cursorExpectedVersion()
                || existing.expectedObjectVersion() != metadata.objectVersion()
                || !java.util.Objects.equals(
                existing.linkedReplayReceiptId(), requested.linkedReplayReceiptId())
                || !java.util.Objects.equals(
                existing.linkedReplayRunId(), requested.linkedReplayRunId())) {
            throw Hrm012Problem.conflict("HRM012_RUN_SNAPSHOT_CONFLICT",
                    "The immutable run snapshot conflicts with this receipt.");
        }
    }

    private List<UUID> legalEntityIds(ResultSet result) throws SQLException {
        java.sql.Array value = result.getArray("legal_entity_public_ids");
        if (value == null) return List.of();
        Object raw = value.getArray();
        if (!(raw instanceof UUID[] ids)) {
            throw new SQLException("Invalid HRM-012 legal entity snapshot.");
        }
        return Arrays.stream(ids).distinct().sorted().toList();
    }

    private void requireRunning(Run run, Hrm012WorkforceIngestionContract.Mode mode) {
        if (run == null || run.mode() != mode || !"RUNNING".equals(run.state())) {
            throw Hrm012Problem.conflict(
                    "HRM012_RUN_NOT_CLAIMED", "A current RUNNING lease is required.");
        }
    }

    private UUID deterministicId(String domain, long tenant, UUID run, String digest) {
        try {
            byte[] source = ("dwp.hris.hrm012\n" + domain + "\n" + tenant + "\n"
                    + run + "\n" + digest).getBytes(StandardCharsets.UTF_8);
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(source);
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(hash);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (Exception exception) {
            throw new IllegalStateException("Deterministic event identity is unavailable.", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("HRM-012 evidence JSON cannot be written.", exception);
        }
    }

    private void establishTenant(long tenantId) {
        String value = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id',CAST(:tenant AS VARCHAR),TRUE)",
                params(tenantId), String.class);
        if (!Long.toString(tenantId).equals(value)) {
            throw new IllegalStateException("HRM-012 tenant scope was not established.");
        }
    }

    private MapSqlParameterSource params(long tenantId) {
        return new MapSqlParameterSource("tenant", tenantId);
    }

    public record CreateRun(
            long tenantId,
            UUID receiptId,
            UUID linkedReplayReceiptId,
            UUID linkedReplayRunId,
            Hrm012WorkforceIngestionContract.Command command,
            Hrm012WorkforceIngestionContract.OwnerMetadata metadata,
            String populationScopeDigest) {
    }

    public record Run(
            UUID runId,
            UUID runPublicId,
            long tenantId,
            UUID receiptId,
            UUID linkedReplayReceiptId,
            UUID linkedReplayRunId,
            UUID connectorId,
            UUID connectorPublicId,
            long connectorVersion,
            UUID mappingId,
            UUID mappingPublicId,
            long mappingVersion,
            String mappingSha256,
            List<UUID> legalEntityPublicIds,
            String populationScopeDigest,
            String objectRef,
            String objectDigest,
            String schemaKey,
            int schemaVersion,
            Hrm012WorkforceIngestionContract.Mode mode,
            java.time.LocalDate effectiveDate,
            String state,
            String cursorBeforeDigest,
            long cursorExpectedVersion,
            long version,
            UUID actorPublicId,
            UUID correlationId,
            long expectedObjectVersion,
            Instant createdAt) {
    }

    private record ReceiptContext(
            UUID actorPublicId, UUID correlationId, long expectedVersion) {
    }
}
