package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dwp.core.exception.BaseException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AdminMailDurabilityPostgresTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void ownerHandoffMigrationBackfillsLegacyExecutedResultEvidence() {
        String schema = "mail_legacy_executed_handoff";
        Flyway throughV288 = flyway(schema, "288");
        throughV288.clean();
        throughV288.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        UUID proposalId = jdbc.queryForObject("""
                SELECT proposal_id
                  FROM mail_action_proposals
                 WHERE proposal_status = 'PROPOSED'
                 ORDER BY proposal_id
                 LIMIT 1
                """, UUID.class);
        jdbc.update("""
                UPDATE mail_action_proposals
                   SET proposal_status = 'EXECUTED', decided_at = CURRENT_TIMESTAMP,
                       decided_by = COALESCE(created_by, 1), version = version + 1
                 WHERE proposal_id = ?
                """, proposalId);

        flyway(schema, "289").migrate();

        Map<String, Object> migrated = jdbc.queryForMap("""
                SELECT owner_command_id, owner_state, result_ref, owner_updated_at
                  FROM mail_action_proposals
                 WHERE proposal_id = ?
                """, proposalId);
        assertThat(migrated.get("owner_command_id")).isNotNull();
        assertThat(migrated.get("owner_state")).isEqualTo("EXECUTED");
        assertThat(migrated.get("result_ref"))
                .isEqualTo("legacy-executed:" + proposalId);
        assertThat(migrated.get("owner_updated_at")).isNotNull();
    }

    @Test
    void legacyAdminMutationReceiptIsActorScopedCanonicalAndFailClosed() {
        String schema = "mail_legacy_admin_receipt";
        migrate(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        MailAdminMutationReceipts receipts = new MailAdminMutationReceipts(
                jdbc, new ObjectMapper().findAndRegisterModules());
        long tenantId = connectionFixture(jdbc).tenantId();
        long actorId = 8_301L;
        UUID key = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Map<String, Object> firstOrder = new LinkedHashMap<>();
        firstOrder.put("displayName", " People Mail ");
        firstOrder.put("state", "ACTIVE");
        Map<String, Object> reverseOrder = new LinkedHashMap<>();
        reverseOrder.put("state", "ACTIVE");
        reverseOrder.put("displayName", "People Mail");
        String fingerprint = receipts.fingerprint(
                "CONNECTION_UPDATE", aggregateId, firstOrder);

        assertThat(receipts.fingerprint(
                "CONNECTION_UPDATE", aggregateId, reverseOrder))
                .isEqualTo(fingerprint);
        assertThat(receipts.claimOrReplay(
                tenantId, actorId, "CONNECTION_UPDATE", key,
                fingerprint, "first")).isNull();
        receipts.complete(
                tenantId, actorId, key, "MAIL_PROVIDER_CONNECTION", aggregateId);

        MailAdminMutationReceipts.Receipt replay = receipts.claimOrReplay(
                tenantId, actorId, "CONNECTION_UPDATE", key,
                fingerprint, "replay");
        assertThat(replay.aggregateType()).isEqualTo("MAIL_PROVIDER_CONNECTION");
        assertThat(replay.aggregateId()).isEqualTo(aggregateId);
        assertThat(replay.completedAt()).isNotNull();

        assertThatThrownBy(() -> receipts.claimOrReplay(
                tenantId, actorId, "CONNECTION_UPDATE", key,
                receipts.fingerprint("CONNECTION_UPDATE", aggregateId, Map.of(
                        "displayName", "Changed", "state", "ACTIVE")), "drift"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("another Mail administrator command");

        assertThat(receipts.claimOrReplay(
                tenantId, actorId + 1, "CONNECTION_UPDATE", key,
                fingerprint, "other-actor")).isNull();

        UUID inProgressKey = UUID.randomUUID();
        assertThat(receipts.claimOrReplay(
                tenantId, actorId, "SHARED_INBOX_UPDATE", inProgressKey,
                "c".repeat(64), "in-progress")).isNull();
        assertThatThrownBy(() -> receipts.claimOrReplay(
                tenantId, actorId, "SHARED_INBOX_UPDATE", inProgressKey,
                "c".repeat(64), "retry"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("still in progress");
    }

    @Test
    void testSendClaimCommitsBeforeTheOuterProviderTransactionAndReplaysByActor() {
        String schema = "mail_test_send_durable_claim";
        migrate(schema);
        PGSimpleDataSource source = dataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        AdminMailCompletionRepository repository = repository(jdbc);
        AdminMailOperationDurability durability = new AdminMailOperationDurability(
                repository, new DataSourceTransactionManager(source));
        ConnectionFixture fixture = connectionFixture(jdbc);
        UUID idempotencyKey = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        AtomicReference<AdminMailOperationDurability.Claim> committedClaim =
                new AtomicReference<>();

        assertThatThrownBy(() -> new TransactionTemplate(
                new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
                    committedClaim.set(durability.claimTestSend(
                            fixture.tenantId(), 8101, fixture.connectionId(), "TENANT",
                            Map.of("recipient", "durability@example.test"),
                            idempotencyKey, fingerprint, "durable-claim"));
                    throw new IllegalStateException("simulate crash before provider result");
                })).hasMessageContaining("simulate crash");

        AdminMailCompletionRepository.ConnectionOperationRow persisted = repository
                .connectionOperation(fixture.tenantId(), 8101, idempotencyKey)
                .orElseThrow();
        assertThat(persisted.id()).isEqualTo(committedClaim.get().operationId());
        assertThat(persisted.state()).isEqualTo("UNKNOWN");
        assertThat(persisted.errorCode()).isEqualTo("TEST_SEND_RESULT_UNKNOWN");
        assertThat(persisted.completedAt()).isNull();

        AdminMailOperationDurability.Claim replay = durability.claimTestSend(
                fixture.tenantId(), 8101, fixture.connectionId(), "TENANT",
                Map.of("recipient", "durability@example.test"),
                idempotencyKey, fingerprint, "replay");
        assertThat(replay.created()).isFalse();
        assertThat(replay.operationId()).isEqualTo(persisted.id());
        assertThat(replay.existing().state()).isEqualTo("UNKNOWN");

        AdminMailOperationDurability.Claim otherActor = durability.claimTestSend(
                fixture.tenantId(), 8102, fixture.connectionId(), "TENANT",
                Map.of("recipient", "durability@example.test"),
                idempotencyKey, fingerprint, "other-actor");
        assertThat(otherActor.created()).isTrue();
        assertThat(otherActor.operationId()).isNotEqualTo(persisted.id());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM mail_connection_operations
                 WHERE tenant_id = ? AND idempotency_key = ?
                """, Integer.class, fixture.tenantId(), idempotencyKey)).isEqualTo(2);
    }

    @Test
    void deliveryExportKeepsExactSnapshotBytesAfterSourceChanges() throws Exception {
        String schema = "mail_delivery_export_snapshot";
        migrate(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AdminMailCompletionRepository repository = repository(jdbc);
        AdminMailCompletionService service = new AdminMailCompletionService(
                repository,
                new MailConnectorRegistry(List.of(new DwpSandboxMailConnector())),
                objectMapper);
        DeliveryFixture fixture = deliveryFixture(jdbc);
        long actorId = 8201;
        UUID idempotencyKey = UUID.randomUUID();
        MailWorkspaceDtos.DeliveryExportRequest request =
                new MailWorkspaceDtos.DeliveryExportRequest(
                        Map.of(), "Immutable incident evidence", idempotencyKey);

        MailWorkspaceDtos.DeliveryExport created = service.createDeliveryExport(
                fixture.tenantId(), actorId, request);
        assertThat(created.state()).isEqualTo("PENDING_APPROVAL");
        MailWorkspaceDtos.DeliveryExport approved = service.approveDeliveryExport(
                fixture.tenantId(), actorId + 1, created.exportId(),
                new MailWorkspaceDtos.EvidenceExportApprovalRequest(
                        "APPROVE", UUID.randomUUID()));
        assertThat(approved.state()).isEqualTo("READY");
        assertThat(approved.distinctApproverCount()).isOne();
        String firstDownload = service.deliveryExportJson(
                fixture.tenantId(), actorId, created.exportId());

        jdbc.update("""
                UPDATE mail_delivery_outbox
                   SET delivery_status = 'FAILED', last_error_code = 'SOURCE_CHANGED',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND delivery_id = ?
                """, fixture.tenantId(), fixture.deliveryId());

        MailWorkspaceDtos.DeliveryExport replay = service.createDeliveryExport(
                fixture.tenantId(), actorId, request);
        String secondDownload = service.deliveryExportJson(
                fixture.tenantId(), actorId, created.exportId());
        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT snapshot_payload, payload_sha256, item_count, truncated,
                       snapshot_cutoff
                  FROM mail_delivery_audit_exports
                 WHERE tenant_id = ? AND export_id = ?
                """, fixture.tenantId(), created.exportId());

        assertThat(replay.exportId()).isEqualTo(created.exportId());
        assertThat(secondDownload.getBytes(StandardCharsets.UTF_8))
                .containsExactly(firstDownload.getBytes(StandardCharsets.UTF_8));
        assertThat(stored.get("snapshot_payload")).isEqualTo(firstDownload);
        assertThat(stored.get("payload_sha256")).isEqualTo(sha256(firstDownload));
        assertThat(stored.get("item_count")).isEqualTo(1);
        assertThat(stored.get("truncated")).isEqualTo(false);
        assertThat(stored.get("snapshot_cutoff")).isNotNull();
        assertThat(firstDownload).contains("\"itemCount\":1")
                .contains("\"truncated\":false")
                .contains("\"itemLimit\":10000")
                .contains("\"state\":\"QUEUED\"");
        assertThat(created.payloadSha256()).isEqualTo(sha256(firstDownload));
        assertThat(created.itemCount()).isOne();
        assertThat(created.truncated()).isFalse();
        assertThat(created.snapshotCutoff()).isNotNull();

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE mail_delivery_audit_exports
                   SET snapshot_payload = '{\"tampered\":true}'
                 WHERE tenant_id = ? AND export_id = ?
                """, fixture.tenantId(), created.exportId()))
                .hasStackTraceContaining("Mail delivery audit export snapshot is immutable");
        assertThat(jdbc.queryForObject("""
                SELECT snapshot_payload FROM mail_delivery_audit_exports
                 WHERE tenant_id = ? AND export_id = ?
                """, String.class, fixture.tenantId(), created.exportId()))
                .isEqualTo(firstDownload);
    }

    @Test
    void mailEventIdentifierMigrationPreservesRowsAndAcceptsCanonicalHyphenatedSegments() {
        String schema = "mail_event_identifier_contract";
        Flyway throughV312 = flyway(schema, "312");
        throughV312.clean();
        throughV312.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        long tenantId = connectionFixture(jdbc).tenantId();
        UUID aggregateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_audit_events (
                    tenant_id, actor_user_id, action, target_type, target_id)
                VALUES (?, 8301, 'mail.audit.before.migration', 'MAIL_TEST', ?)
                """, tenantId, aggregateId.toString());
        jdbc.update("""
                INSERT INTO mail_domain_events (
                    tenant_id, aggregate_type, aggregate_id, event_type)
                VALUES (?, 'MAIL_TEST', ?, 'mail.domain.before.migration')
                """, tenantId, aggregateId);

        flyway(schema, "313").migrate();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM mail_audit_events
                 WHERE tenant_id = ? AND action = 'mail.audit.before.migration'
                """, Integer.class, tenantId)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM mail_domain_events
                 WHERE tenant_id = ? AND event_type = 'mail.domain.before.migration'
                """, Integer.class, tenantId)).isOne();
        assertThat(jdbc.update("""
                INSERT INTO mail_audit_events (
                    tenant_id, actor_user_id, action, target_type, target_id)
                VALUES (?, 8302, 'mail.evidence-export.approved', 'MAIL_TEST', ?)
                """, tenantId, UUID.randomUUID().toString())).isOne();
        assertThat(jdbc.update("""
                INSERT INTO mail_domain_events (
                    tenant_id, aggregate_type, aggregate_id, event_type)
                VALUES (?, 'MAIL_TEST', ?, 'mail.action.owner-not-executed')
                """, tenantId, UUID.randomUUID())).isOne();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO mail_audit_events (
                    tenant_id, actor_user_id, action, target_type, target_id)
                VALUES (?, 8303, 'mail.-invalid.event', 'MAIL_TEST', ?)
                """, tenantId, UUID.randomUUID().toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO mail_domain_events (
                    tenant_id, aggregate_type, aggregate_id, event_type)
                VALUES (?, 'MAIL_TEST', ?, 'mail.too-short')
                """, tenantId, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private AdminMailCompletionRepository repository(JdbcTemplate jdbc) {
        return new AdminMailCompletionRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
    }

    private ConnectionFixture connectionFixture(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT tenant_id, connection_id
                  FROM mail_provider_connections
                 WHERE provider_type = 'DWP_SANDBOX'
                 ORDER BY tenant_id LIMIT 1
                """, result -> {
            if (!result.next()) throw new IllegalStateException("Sandbox connection fixture missing");
            return new ConnectionFixture(
                    result.getLong("tenant_id"),
                    result.getObject("connection_id", UUID.class));
        });
    }

    private DeliveryFixture deliveryFixture(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT message.tenant_id, message.thread_id, message.message_id
                  FROM mail_messages message
                  LEFT JOIN mail_delivery_outbox delivery
                    ON delivery.message_id = message.message_id
                 WHERE delivery.delivery_id IS NULL
                 ORDER BY message.tenant_id, message.message_id LIMIT 1
                """, result -> {
            if (!result.next()) throw new IllegalStateException("Mail message fixture missing");
            long tenantId = result.getLong("tenant_id");
            UUID threadId = result.getObject("thread_id", UUID.class);
            UUID messageId = result.getObject("message_id", UUID.class);
            UUID deliveryId = jdbc.queryForObject("""
                    INSERT INTO mail_delivery_outbox (
                        tenant_id, thread_id, message_id, idempotency_key,
                        delivery_status, correlation_id, created_by,
                        request_fingerprint)
                    VALUES (?, ?, ?, ?, 'QUEUED', 'snapshot-source', 8201, ?)
                    RETURNING delivery_id
                    """, UUID.class, tenantId, threadId, messageId,
                    UUID.randomUUID(), "b".repeat(64));
            return new DeliveryFixture(tenantId, deliveryId);
        });
    }

    private String sha256(String payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private void migrate(String schema) {
        Flyway flyway = flyway(schema, "313");
        flyway.clean();
        flyway.migrate();
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(dataSource())
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }

    private PGSimpleDataSource dataSource(String schema) {
        PGSimpleDataSource source = dataSource();
        source.setCurrentSchema(schema);
        return source;
    }

    private record ConnectionFixture(long tenantId, UUID connectionId) { }
    private record DeliveryFixture(long tenantId, UUID deliveryId) { }
}
