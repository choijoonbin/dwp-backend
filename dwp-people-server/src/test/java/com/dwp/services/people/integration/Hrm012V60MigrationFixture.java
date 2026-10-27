package com.dwp.services.people.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HRM-012 frozen-authority, cursor, receipt, and outbox database fixtures. */
final class Hrm012V60MigrationFixture {

    static final long TENANT_ONE = 60_001L;
    static final long TENANT_TWO = 60_002L;
    static final String SHA_A = "a".repeat(64);
    static final String SHA_B = "b".repeat(64);
    static final String SHA_C = "c".repeat(64);
    static final String SHA_D = "d".repeat(64);
    static final String SHA_E = "e".repeat(64);
    static final String SHA_F = "f".repeat(64);
    static final List<String> TABLES = List.of(
            "ppl_workforce_ingestion_runs",
            "ppl_workforce_ingestion_items");

    static RunGraph insertRunning(
            JdbcTemplate jdbc,
            long tenant,
            String suffix,
            String mode) {
        RunGraph run = insertReceived(jdbc, tenant, suffix, mode);
        assertThat(jdbc.update("""
                UPDATE ppl_workforce_ingestion_runs
                   SET lifecycle_state='RUNNING',lease_owner='hrm012-test',
                       lease_until=clock_timestamp() + interval '5 minutes',
                       attempt_count=1,version=1
                 WHERE workforce_ingestion_run_id=?
                """, run.runId())).isOne();
        return run;
    }

    static RunGraph insertReceived(
            JdbcTemplate jdbc,
            long tenant,
            String suffix,
            String mode) {
        ensureTenant(jdbc, tenant, suffix);
        long sourceId = insertSource(jdbc, tenant, suffix);
        UUID connector = insertConnector(jdbc, tenant, sourceId, suffix);
        UUID mapping = insertMapping(jdbc, tenant, sourceId, suffix);
        UUID receipt = insertReceipt(jdbc, tenant, suffix);
        RunGraph run = new RunGraph(
                tenant, UUID.randomUUID(), UUID.randomUUID(), receipt,
                connector, mapping,
                "obj_" + UUID.randomUUID().toString().replace("-", ""));
        assertThat(jdbc.update(runInsertSql(run, mode))).isOne();
        return run;
    }

    static void ensureTenant(JdbcTemplate jdbc, long tenant, String suffix) {
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,
                    lifecycle_state,data_region,isolation_model,created_by,updated_by)
                VALUES(?::UUID,?,?,?,'ACTIVE','local','POOL',7,7)
                ON CONFLICT (tenant_id) DO NOTHING
                """, UUID.randomUUID().toString(), tenant,
                "hrm012-" + suffix + "-" + tenant,
                "HRM-012 " + suffix);
    }

    static long insertSource(JdbcTemplate jdbc, long tenant, String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO int_source_systems(
                    tenant_id,source_key,system_type,name,created_by,updated_by)
                VALUES(?,?,'CUSTOM',?,7,7)
                RETURNING source_system_id
                """, Long.class, tenant, "source-" + suffix,
                "HRM-012 " + suffix);
    }

    static UUID insertConnector(
            JdbcTemplate jdbc,
            long tenant,
            long sourceId,
            String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO int_connector_instances(
                    tenant_id,source_system_id,connector_key,connector_type,
                    auth_mode,lifecycle_state,version,created_by,updated_by)
                VALUES(?,?,?,'FILE_IMPORT','NONE','ACTIVE',1,7,7)
                RETURNING connector_instance_id
                """, UUID.class, tenant, sourceId, "connector-" + suffix);
    }

    static UUID insertMapping(
            JdbcTemplate jdbc,
            long tenant,
            long sourceId,
            String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO int_mapping_profiles(
                    tenant_id,source_system_id,profile_key,adapter_type,
                    source_schema_version,target_schema_version,
                    mapping_definition,lifecycle_state,version,
                    mapping_sha256,activated_at,activated_by,created_by,updated_by)
                VALUES(?,?,?,'CANONICAL_JSON','workforce.v1','people.v1',
                       '{}'::JSONB,'ACTIVE',1,?,clock_timestamp(),7,7,7)
                RETURNING mapping_profile_id
                """, UUID.class, tenant, sourceId, "mapping-" + suffix, SHA_A);
    }

    static UUID insertReceipt(JdbcTemplate jdbc, long tenant, String suffix) {
        UUID identity = UUID.randomUUID();
        return insertReceipt(jdbc, tenant, suffix,
                "WORKFORCE_INGESTION", "INITIATE_WORKFORCE_INGESTION",
                "HRIS_INTEGRATION", "WORKFORCE_INGESTION_OBJECT",
                identity, identity, null);
    }

    static UUID insertReceipt(
            JdbcTemplate jdbc,
            long tenant,
            String suffix,
            String commandType,
            String originatingAction,
            String purposeCode,
            String targetType,
            UUID actor,
            UUID subject,
            UnaryOperator<String> referenceMutation) {
        UUID correlation = UUID.randomUUID();
        String reference = frozenAuthorityReference(tenant, actor, correlation);
        if (referenceMutation != null) {
            reference = referenceMutation.apply(reference);
        }
        return jdbc.queryForObject("""
                INSERT INTO ppl_command_receipts(
                    tenant_id,command_type,idempotency_key,request_hash,
                    actor_public_id,originating_action,
                    subject_principal_public_id,population_scope_digest,
                    field_policy_revision,purpose_code,authorization_revision,
                    originating_context_digest,target_type,target_public_id,
                    expected_version,result_reference,correlation_id)
                VALUES(?,?,?,?,?::UUID,?,?::UUID,?,1,?,1,?,?,NULL,0,
                       ?::JSONB,?::UUID)
                RETURNING command_receipt_id
                """, UUID.class, tenant, commandType,
                "hrm012-" + suffix + "-" + UUID.randomUUID(), SHA_A,
                actor.toString(), originatingAction, subject.toString(),
                SHA_B, purposeCode, SHA_C, targetType, reference,
                correlation.toString());
    }

    static String frozenAuthorityReference(
            long tenant, UUID person, UUID correlation) {
        return """
                {
                  "hrm012FrozenAuthority": {
                    "tenantId": %d,
                    "numericUserId": 7001,
                    "personPublicId": "%s",
                    "markerJti": "hrm012-marker-jti",
                    "markerSha256": "%s",
                    "contextKey": "hrm012-context",
                    "scopeRef": "connector-set:test",
                    "populationScopeDigest": "%s",
                    "authorizationRevision": 1,
                    "populationRevision": 1,
                    "fieldPolicyRevision": 1,
                    "postReceiptDecisionRevision": "decision-r1",
                    "dutyEvidenceRevision": "duty-r1",
                    "featureDecisionRevision": "feature-r1",
                    "purpose": "HRIS_INTEGRATION",
                    "correlationId": "%s",
                    "idempotencyKeySha256": "%s",
                    "canonicalRequestHash": "%s"
                  }
                }
                """.formatted(
                tenant, person, SHA_C, SHA_B, correlation, SHA_D, SHA_A);
    }

    static int insertTerminalReceipt(
            JdbcTemplate jdbc, long tenant, String suffix) {
        UUID identity = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        String reference = frozenAuthorityReference(tenant, identity, correlation);
        return jdbc.update("""
                INSERT INTO ppl_command_receipts(
                    tenant_id,command_type,idempotency_key,request_hash,
                    actor_public_id,originating_action,
                    subject_principal_public_id,population_scope_digest,
                    field_policy_revision,purpose_code,authorization_revision,
                    originating_context_digest,target_type,target_public_id,
                    expected_version,lifecycle_state,result_code,result_reference,
                    correlation_id,completed_at)
                VALUES(?, 'WORKFORCE_INGESTION', ?, ?, ?::UUID,
                       'INITIATE_WORKFORCE_INGESTION', ?::UUID, ?, 1,
                       'HRIS_INTEGRATION', 1, ?, 'WORKFORCE_INGESTION_OBJECT',
                       NULL, 0, 'SUCCEEDED', 'WORKFORCE_INGESTION_SUCCEEDED',
                       ?::JSONB || jsonb_build_object(
                           'runPublicId',?,'resultDigest',?),
                       ?::UUID,clock_timestamp())
                """, tenant, "hrm012-" + suffix + '-' + UUID.randomUUID(), SHA_A,
                identity.toString(), identity.toString(), SHA_B, SHA_C,
                reference, UUID.randomUUID().toString(), SHA_E,
                correlation.toString());
    }

    static void expireLeaseWithoutGuards(DataSource source, RunGraph run)
            throws Exception {
        try (Connection connection = source.getConnection()) {
            JdbcTemplate fixture = new JdbcTemplate(
                    new SingleConnectionDataSource(connection, true));
            fixture.execute("SET session_replication_role = 'replica'");
            try {
                assertThat(fixture.update("""
                        UPDATE ppl_workforce_ingestion_runs
                           SET lease_until=clock_timestamp() - interval '1 minute'
                         WHERE tenant_id=? AND workforce_ingestion_run_id=?
                        """, run.tenantId(), run.runId())).isOne();
            } finally {
                fixture.execute("SET session_replication_role = 'origin'");
            }
        }
    }

    static int takeover(
            JdbcTemplate jdbc,
            RunGraph run,
            String owner,
            int attempt,
            long version) {
        return jdbc.update("""
                UPDATE ppl_workforce_ingestion_runs
                   SET lifecycle_state='RUNNING',lease_owner=?,
                       lease_until=clock_timestamp() + interval '5 minutes',
                       attempt_count=?,version=?
                 WHERE tenant_id=? AND workforce_ingestion_run_id=?
                """, owner, attempt, version, run.tenantId(), run.runId());
    }

    static int updateMarkerAuthority(
            JdbcTemplate jdbc, UUID receipt, String authorityReference) {
        return jdbc.update("""
                UPDATE ppl_command_receipts
                   SET result_reference=result_reference || ?::JSONB
                 WHERE tenant_id=? AND command_receipt_id=?
                """, authorityReference, TENANT_ONE, receipt);
    }

    static String markerAuthorityReference(String... evidence) {
        return """
                {
                  "hrm012Authority": {
                    "authorityEvidence": [%s]
                  }
                }
                """.formatted(String.join(",", evidence));
    }

    static String currentMarkerEvidence(
            String jti,
            String expiresAt,
            String markerSha256,
            String state,
            String boundAt,
            String authDecisionRevision) {
        String revision = authDecisionRevision == null
                ? "null" : "\"" + authDecisionRevision + "\"";
        return """
                {
                  "jti": "%s",
                  "exp": "%s",
                  "markerSha256": "%s",
                  "state": "%s",
                  "boundAt": "%s",
                  "authDecisionRevision": %s
                }
                """.formatted(
                jti, expiresAt, markerSha256, state, boundAt, revision);
    }

    static String previousMarkerEvidence(
            String jti, String expiresAt, String state, String replacedAt) {
        return """
                {
                  "jtiSha256": "%s",
                  "exp": "%s",
                  "state": "%s",
                  "replacedAt": "%s"
                }
                """.formatted(digest(jti), expiresAt, state, replacedAt);
    }

    static String runInsertSql(RunGraph run, String mode) {
        return runInsertSql(run, mode, null, null, run.objectRef());
    }

    static String runInsertSql(
            RunGraph run,
            String mode,
            UUID replayReceipt,
            UUID replayRun,
            String objectRef) {
        String replayReceiptValue = replayReceipt == null
                ? "NULL" : "'" + replayReceipt + "'::UUID";
        String replayRunValue = replayRun == null
                ? "NULL" : "'" + replayRun + "'::UUID";
        return """
                INSERT INTO ppl_workforce_ingestion_runs(
                    workforce_ingestion_run_id,run_public_id,tenant_id,
                    command_receipt_id,linked_replay_receipt_id,
                    linked_replay_run_id,connector_instance_id,
                    connector_public_id,connector_version,mapping_profile_id,
                    mapping_public_id,mapping_version,mapping_sha256,
                    legal_entity_public_ids,population_scope_digest,
                    object_ref,object_digest,schema_key,schema_version,mode,
                    effective_date,cursor_before_digest,cursor_expected_version)
                VALUES('%s'::UUID,'%s'::UUID,%d,'%s'::UUID,%s,%s,
                       '%s'::UUID,'%s'::UUID,1,'%s'::UUID,'%s'::UUID,1,
                       '%s',ARRAY[]::UUID[],'%s','%s','%s','workforce.v1',1,
                       '%s',DATE '2026-10-22','%s',0)
                """.formatted(
                run.runId(), run.runPublicId(), run.tenantId(), run.receiptId(),
                replayReceiptValue, replayRunValue,
                run.connectorId(), run.connectorId(),
                run.mappingId(), run.mappingId(), SHA_A, SHA_B,
                objectRef, SHA_C, mode, SHA_D);
    }

    static void insertItem(
            JdbcTemplate jdbc,
            RunGraph run,
            long sequence,
            String outcome) {
        String finding = switch (outcome) {
            case "REJECTED", "QUARANTINED_AFTER_ACCEPTANCE" -> "'INVALID_VALUE'";
            default -> "NULL";
        };
        String summary = switch (outcome) {
            case "REJECTED", "QUARANTINED_AFTER_ACCEPTANCE" -> "'Redacted finding'";
            default -> "NULL";
        };
        String applied = "APPLIED".equals(outcome) ? "'" + SHA_E + "'" : "NULL";
        String ciphertext = "QUARANTINED_AFTER_ACCEPTANCE".equals(outcome)
                ? "decode('deadbeef','hex')" : "NULL";
        String key = "QUARANTINED_AFTER_ACCEPTANCE".equals(outcome)
                ? "'kms://hrm012/test'" : "NULL";
        String envelope = "QUARANTINED_AFTER_ACCEPTANCE".equals(outcome)
                ? "'" + SHA_F + "'" : "NULL";
        jdbc.update("""
                INSERT INTO ppl_workforce_ingestion_items(
                    workforce_ingestion_item_id,item_public_id,tenant_id,
                    workforce_ingestion_run_id,connector_instance_id,
                    item_sequence,source_record_key_digest,
                    source_version_digest,input_digest,outcome,outcome_digest,
                    finding_code,redacted_summary,applied_effect_digest,
                    quarantine_ciphertext,quarantine_key_reference,
                    quarantine_envelope_digest)
                VALUES(?::UUID,?::UUID,?,?::UUID,?::UUID,?,?,?,?,?,?,
                       %s,%s,%s,%s,%s,%s)
                """.formatted(
                finding, summary, applied, ciphertext, key, envelope),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                run.tenantId(), run.runId().toString(),
                run.connectorId().toString(), sequence,
                SHA_A, SHA_B, SHA_C, outcome, SHA_D);
    }

    static String itemInsertSql(
            RunGraph run, long sequence, String outcome) {
        if (!"VALIDATED".equals(outcome)) {
            throw new IllegalArgumentException("RLS fixture supports VALIDATED only.");
        }
        return """
                INSERT INTO ppl_workforce_ingestion_items(
                    workforce_ingestion_item_id,item_public_id,tenant_id,
                    workforce_ingestion_run_id,connector_instance_id,
                    item_sequence,source_record_key_digest,
                    source_version_digest,input_digest,outcome,outcome_digest)
                VALUES('%s'::UUID,'%s'::UUID,%d,'%s'::UUID,'%s'::UUID,
                       %d,'%s','%s','%s','%s','%s')
                """.formatted(
                UUID.randomUUID(), UUID.randomUUID(), run.tenantId(),
                run.runId(), run.connectorId(), sequence,
                SHA_A, SHA_B, SHA_C, outcome, SHA_D);
    }

    static int completeValidation(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            long rejected,
            long duplicate,
            long accepted) {
        return jdbc.update("""
                UPDATE ppl_workforce_ingestion_runs
                   SET lifecycle_state='SUCCEEDED',lease_owner=NULL,
                       lease_until=NULL,source_count=?,accepted_count=?,
                       rejected_count=?,duplicate_count=?,quarantined_count=0,
                       applied_count=0,result_digest=?,
                       completed_at=clock_timestamp(),version=2
                 WHERE workforce_ingestion_run_id=?
                """, source, accepted, rejected, duplicate, SHA_E, run.runId());
    }

    static int completeApply(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            long accepted,
            long rejected,
            long duplicate,
            long quarantined,
            long applied,
            String cursorAfterDigest) {
        return jdbc.update("""
                UPDATE ppl_workforce_ingestion_runs
                   SET lifecycle_state='SUCCEEDED',lease_owner=NULL,
                       lease_until=NULL,source_count=?,accepted_count=?,
                       rejected_count=?,duplicate_count=?,quarantined_count=?,
                       applied_count=?,result_digest=?,cursor_after_digest=?,
                       cursor_committed_version=1,
                       completed_at=clock_timestamp(),version=2
                 WHERE workforce_ingestion_run_id=?
                """, source, accepted, rejected, duplicate, quarantined,
                applied, SHA_E, cursorAfterDigest, run.runId());
    }

    static void insertExactOutbox(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            long accepted,
            long rejected,
            long duplicate,
            long quarantined,
            long applied) {
        insertExactOutbox(jdbc, run, source, accepted, rejected, duplicate,
                quarantined, applied, deterministicId(
                        "WorkforceIngestionReconciled.v1", run, SHA_E), SHA_E);
    }

    static void insertExactOutbox(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            long accepted,
            long rejected,
            long duplicate,
            long quarantined,
            long applied,
            UUID eventId) {
        insertExactOutbox(jdbc, run, source, accepted, rejected, duplicate,
                quarantined, applied, eventId, SHA_E);
    }

    static void insertExactOutbox(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            long accepted,
            long rejected,
            long duplicate,
            long quarantined,
            long applied,
            UUID eventId,
            String resultDigest) {
        jdbc.update("""
                INSERT INTO sys_people_outbox_events(
                    event_id,tenant_id,aggregate_type,aggregate_id,
                    event_type,payload,correlation_id)
                SELECT ?::UUID,?,'WorkforceIngestionRun',?,
                       'WorkforceIngestionReconciled.v1',
                       jsonb_build_object(
                           'runPublicId',?,
                           'connectorPublicId',?,
                           'mappingVersion',1,
                           'sourceCount',?,
                           'acceptedCount',?,
                           'rejectedCount',?,
                           'duplicateCount',?,
                           'quarantinedCount',?,
                           'appliedCount',?,
                           'resultDigest',?),receipt.correlation_id::TEXT
                  FROM ppl_command_receipts receipt
                 WHERE receipt.tenant_id=? AND receipt.command_receipt_id=?::UUID
                """, eventId.toString(), run.tenantId(),
                run.runPublicId().toString(), run.runPublicId().toString(),
                run.connectorId().toString(), source, accepted, rejected,
                duplicate, quarantined, applied, resultDigest,
                run.tenantId(), run.receiptId().toString());
    }

    static void insertAudit(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            String resultDigest) {
        jdbc.update("""
                INSERT INTO sys_people_audit_events(
                    audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                    target_id,outcome,correlation_id,after_snapshot,occurred_at)
                SELECT ?::UUID,receipt.tenant_id,'USER',receipt.actor_public_id::TEXT,
                       'workforce-ingestion.reconciled','WORKFORCE_INGESTION_RUN',
                       ?,'SUCCESS',receipt.correlation_id::TEXT,
                       jsonb_build_object('mode',run.mode,'sourceCount',?,
                                          'resultDigest',?),clock_timestamp()
                  FROM ppl_command_receipts receipt
                  JOIN ppl_workforce_ingestion_runs run
                    ON run.tenant_id=receipt.tenant_id
                   AND run.command_receipt_id=receipt.command_receipt_id
                 WHERE receipt.tenant_id=? AND receipt.command_receipt_id=?::UUID
                """, deterministicId("audit", run, resultDigest).toString(),
                run.runPublicId().toString(), source, resultDigest,
                run.tenantId(), run.receiptId().toString());
    }

    static int completeReceipt(
            JdbcTemplate jdbc, RunGraph run, String resultDigest) {
        return jdbc.update("""
                UPDATE ppl_command_receipts
                   SET lifecycle_state='SUCCEEDED',
                       result_code='WORKFORCE_INGESTION_SUCCEEDED',
                       result_reference=COALESCE(result_reference,'{}'::jsonb)
                           || jsonb_build_object('runPublicId',?,'resultDigest',?),
                       completed_at=clock_timestamp(),updated_at=clock_timestamp(),
                       lease_owner=NULL,lease_until=NULL,next_attempt_at=NULL,
                       last_error_code=NULL
                 WHERE tenant_id=? AND command_receipt_id=?::UUID
                """, run.runPublicId().toString(), resultDigest,
                run.tenantId(), run.receiptId().toString());
    }

    static int advanceCursor(
            JdbcTemplate jdbc, RunGraph run, String cursor) {
        return jdbc.update("""
                INSERT INTO int_connector_cursors(
                    tenant_id,connector_instance_id,cursor_type,
                    committed_cursor,committed_at,version,updated_at)
                VALUES(?,?::UUID,'OPAQUE_TOKEN',?,clock_timestamp(),1,
                       clock_timestamp())
                ON CONFLICT (tenant_id,connector_instance_id,cursor_type) DO UPDATE SET
                    committed_cursor=EXCLUDED.committed_cursor,
                    committed_at=EXCLUDED.committed_at,
                    version=int_connector_cursors.version+1,
                    updated_at=EXCLUDED.updated_at
                 WHERE int_connector_cursors.version=0
                """, run.tenantId(), run.connectorId().toString(), cursor);
    }

    static void reconcileValidation(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            long rejected,
            long duplicate,
            long accepted) {
        transaction(jdbc).executeWithoutResult(status -> {
            insertAudit(jdbc, run, source, SHA_E);
            assertThat(completeValidation(
                    jdbc, run, source, rejected, duplicate, accepted)).isOne();
            assertThat(completeReceipt(jdbc, run, SHA_E)).isOne();
        });
    }

    static void reconcileApply(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            long accepted,
            long rejected,
            long duplicate,
            long quarantined,
            long applied,
            String cursor) {
        transaction(jdbc).executeWithoutResult(status -> {
            assertThat(advanceCursor(jdbc, run, cursor)).isOne();
            insertAudit(jdbc, run, source, SHA_E);
            insertExactOutbox(jdbc, run, source, accepted, rejected, duplicate,
                    quarantined, applied);
            assertThat(completeApply(jdbc, run, source, accepted, rejected,
                    duplicate, quarantined, applied, digest(cursor))).isOne();
            assertThat(completeReceipt(jdbc, run, SHA_E)).isOne();
        });
    }

    static void finishApplyWithoutCursorMutation(
            JdbcTemplate jdbc, RunGraph run, String cursorDigest) {
        transaction(jdbc).executeWithoutResult(status -> {
            insertAudit(jdbc, run, 1, SHA_E);
            insertExactOutbox(jdbc, run, 1, 1, 0, 0, 0, 1);
            assertThat(completeApply(
                    jdbc, run, 1, 1, 0, 0, 0, 1, cursorDigest)).isOne();
            assertThat(completeReceipt(jdbc, run, SHA_E)).isOne();
        });
    }

    static TransactionTemplate transaction(JdbcTemplate jdbc) {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Test datasource is unavailable.");
        return new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    static void assertRunningAndReceiptOpen(JdbcTemplate jdbc, RunGraph run) {
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_workforce_ingestion_runs
                 WHERE workforce_ingestion_run_id=?
                """, String.class, run.runId())).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_command_receipts
                 WHERE tenant_id=? AND command_receipt_id=?
                """, String.class, run.tenantId(), run.receiptId()))
                .isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_outbox_events
                 WHERE tenant_id=? AND aggregate_id=?
                """, Integer.class, run.tenantId(), run.runPublicId().toString()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_audit_events
                 WHERE tenant_id=? AND target_id=?
                """, Integer.class, run.tenantId(), run.runPublicId().toString()))
                .isZero();
    }

    static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static UUID deterministicId(
            String domain, RunGraph run, String resultDigest) {
        try {
            byte[] source = ("dwp.hris.hrm012\n" + domain + "\n"
                    + run.tenantId() + "\n" + run.runPublicId() + "\n"
                    + resultDigest).getBytes(StandardCharsets.UTF_8);
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(source);
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(hash);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static void insertCursor(
            JdbcTemplate jdbc,
            RunGraph run,
            long version,
            String cursor) {
        jdbc.update("""
                INSERT INTO int_connector_cursors(
                    tenant_id,connector_instance_id,cursor_type,
                    committed_cursor,committed_at,version)
                VALUES(?,?,'OPAQUE_TOKEN',?,clock_timestamp(),?)
                """, run.tenantId(), run.connectorId(), cursor, version);
    }

    static int count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    static void assertRlsRejected(
            Connection connection,
            Statement statement,
            String sql) throws Exception {
        java.sql.Savepoint savepoint = connection.setSavepoint();
        assertThatThrownBy(() -> statement.executeUpdate(sql))
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("row-level security policy");
        connection.rollback(savepoint);
    }

    static Map<String, Integer> checksums(Flyway flyway, int maximum) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (MigrationInfo info : flyway.info().applied()) {
            if (info.getVersion() != null
                    && Integer.parseInt(info.getVersion().getVersion()) <= maximum) {
                result.put(info.getVersion().getVersion(), info.getChecksum());
            }
        }
        return result;
    }

    static Flyway flyway(DataSource source, String target) {
        var configuration = Flyway.configure(Hrm012V60MigrationFixture.class.getClassLoader())
                .dataSource(source)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    record RunGraph(
            long tenantId,
            UUID runId,
            UUID runPublicId,
            UUID receiptId,
            UUID connectorId,
            UUID mappingId,
            String objectRef) {

        RunGraph withRun(
                UUID newRunId,
                UUID newRunPublicId,
                UUID newReceiptId) {
            return new RunGraph(
                    tenantId, newRunId, newRunPublicId, newReceiptId,
                    connectorId, mappingId, objectRef);
        }
    }
}
