package com.dwp.services.people.hr.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PerformanceCyclePostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private final long tenantId = 501L;
    private NamedParameterJdbcTemplate jdbc;
    private JdbcTemplate plainJdbc;
    private PerformanceCycleQueryRepository queries;
    private PerformanceCycleCommandRepository commands;

    @BeforeEach
    void migrateIsolatedPerformanceSchema() {
        DataSource dataSource = dataSource();
        plainJdbc = new JdbcTemplate(dataSource);
        plainJdbc.execute("DROP SCHEMA IF EXISTS hris_performance CASCADE");
        plainJdbc.execute("CREATE SCHEMA hris_performance");
        Flyway.configure(getClass().getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/performance-migration")
                .defaultSchema("hris_performance")
                .schemas("hris_performance")
                .table("flyway_performance_schema_history")
                .validateOnMigrate(true)
                .load()
                .migrate();
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        queries = new PerformanceCycleQueryRepository(jdbc, objectMapper);
        commands = new PerformanceCycleCommandRepository(jdbc, objectMapper);
    }

    @Test
    void publishesV1ThenAppendsDraftV2WithoutMutatingPublicationEvidence() {
        UUID cycleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID approvalRef = UUID.randomUUID();
        UUID publishReceipt = UUID.randomUUID();
        UUID publisherSubject = UUID.randomUUID();
        var create = createRequest();
        commands.create(tenantId, 10L, cycleId, versionId, create,
                create.stages(), "a".repeat(64));
        commands.validate(tenantId, 10L, cycleId, versionId, 1);
        commands.insertAcceptedReceipt(
                tenantId, 20L, publisherSubject, publishReceipt, "publish-1",
                "PUBLISH", "performance.cycle.publish", cycleId, 2,
                "b".repeat(64), authority(
                        20L, publisherSubject, "APPROVE",
                        "performance.cycle.publish", "c".repeat(64)));
        commands.publish(tenantId, 20L, cycleId, versionId, 2,
                approvalRef, publishReceipt, UUID.randomUUID(), 44, "a".repeat(64),
                create.effectiveFrom(), create.effectiveTo(), List.of("SELF"));
        commands.completeReceipt(tenantId, publishReceipt, cycleId, 3);

        PerformanceCycleDtos.CycleDetail published = queries.cycle(tenantId, cycleId)
                .orElseThrow();
        assertThat(published.lifecycleState()).isEqualTo("PUBLISHED");
        assertThat(published.version().publishedBy()).isEqualTo(20L);
        assertThat(published.version().publishedAt()).isNotNull();
        assertThat(queries.receiptById(tenantId, publishReceipt).orElseThrow().view().state())
                .isEqualTo("SUCCEEDED");
        Map<String, Object> event = plainJdbc.queryForMap("""
                SELECT command_receipt_id, event_type, payload::text AS payload
                  FROM hris_performance.prf_outbox_events
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, tenantId, publishReceipt);
        assertThat(event.get("command_receipt_id")).isEqualTo(publishReceipt);
        assertThat(event.get("event_type")).isEqualTo("PerformanceCyclePublished.v1");
        try {
            var payload = new ObjectMapper().readTree((String) event.get("payload"));
            assertThat(payload.size()).isEqualTo(6);
            assertThat(payload.has("cycleId")).isTrue();
            assertThat(payload.has("cycleVersionId")).isTrue();
            assertThat(payload.has("effectiveFrom")).isTrue();
            assertThat(payload.has("effectiveTo")).isTrue();
            assertThat(payload.has("stageKeys")).isTrue();
            assertThat(payload.has("contentHash")).isTrue();
            assertThat(payload.get("cycleId").asText()).isEqualTo(cycleId.toString());
            assertThat(payload.get("stageKeys").get(0).asText()).isEqualTo("SELF");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(exception);
        }

        var update = updateRequest(3);
        UUID successorId = commands.update(
                tenantId, 30L, cycleId, published, update, update.stages(), "d".repeat(64));

        assertThat(successorId).isNotEqualTo(versionId);
        assertThat(queries.cycle(tenantId, cycleId).orElseThrow().version().versionNo())
                .isEqualTo(2);
        Map<String, Object> v1 = plainJdbc.queryForMap("""
                SELECT version_state, publication_approval_ref, published_by, published_at
                  FROM hris_performance.prf_cycle_versions
                 WHERE tenant_id = ? AND cycle_version_id = ?
                """, tenantId, versionId);
        assertThat(v1.get("version_state")).isEqualTo("PUBLISHED");
        assertThat(v1.get("publication_approval_ref")).isEqualTo(approvalRef);
        assertThat(((Number) v1.get("published_by")).longValue()).isEqualTo(20L);
        assertThat(v1.get("published_at")).isNotNull();

        plainJdbc.update("""
                UPDATE hris_performance.prf_cycles SET lifecycle_state = 'RETIRED'
                 WHERE tenant_id = ? AND cycle_id = ?
                """, tenantId, cycleId);
        Map<String, Object> retained = plainJdbc.queryForMap("""
                SELECT publication_approval_ref, published_by, published_at
                  FROM hris_performance.prf_cycle_versions
                 WHERE tenant_id = ? AND cycle_version_id = ?
                """, tenantId, versionId);
        assertThat(retained).containsEntry("publication_approval_ref", approvalRef);
        assertThat(((Number) retained.get("published_by")).longValue()).isEqualTo(20L);
        assertThat(retained.get("published_at")).isNotNull();
    }

    @Test
    void doesNotReconcileUnknownFromPreexistingAggregateWithoutExactCommandMarker() {
        UUID cycleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        var create = createRequest();
        commands.create(tenantId, 10L, cycleId, versionId, create,
                create.stages(), "a".repeat(64));
        UUID receiptId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        commands.insertAcceptedReceipt(
                tenantId, 10L, subjectId, receiptId, "unknown-1",
                "UPDATE_DRAFT", "performance.cycle.update", cycleId, 0,
                "b".repeat(64), authority(
                        10L, subjectId, "UPDATE",
                        "performance.cycle.update", "c".repeat(64)));
        plainJdbc.update("""
                UPDATE hris_performance.prf_command_receipts
                   SET receipt_state = 'RESULT_UNKNOWN', result_ref = ?,
                       applied_aggregate_version = 1
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, cycleId, tenantId, receiptId);
        PerformanceCycleQueryRepository.ReceiptRecord unknown =
                queries.receiptById(tenantId, receiptId).orElseThrow();

        assertThat(queries.reconcileUnknown(tenantId, unknown).state())
                .isEqualTo("RESULT_UNKNOWN");

        commands.insertAppliedMarker(tenantId, receiptId, cycleId, 1, "UPDATE_DRAFT");
        PerformanceCycleQueryRepository.ReceiptRecord marked =
                queries.receiptById(tenantId, receiptId).orElseThrow();
        assertThat(queries.reconcileUnknown(tenantId, marked).state())
                .isEqualTo("SUCCEEDED");
        assertThat(queries.receiptById(tenantId, receiptId).orElseThrow().view().receiptId())
                .isEqualTo(receiptId);
    }

    @Test
    void expiresReadyPreviewAndPersistsFreshPreviewIdentity() {
        UUID cycleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        var create = createRequest();
        commands.create(tenantId, 10L, cycleId, versionId, create,
                create.stages(), "a".repeat(64));
        UUID snapshotId = UUID.randomUUID();
        var member = new PerformanceCycleDtos.PreviewMember(
                UUID.randomUUID(), UUID.randomUUID(), "ACTIVE", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "INCLUDED");
        var prepared = new PerformanceParticipantPreviewService.PreparedPreview(
                snapshotId, 41L, create.populationRuleVersionId(),
                "d".repeat(64), List.of(member));
        UUID firstPreviewId = UUID.randomUUID();
        UUID secondPreviewId = UUID.randomUUID();

        UUID persistedFirst = commands.createPreview(
                tenantId, 10L, firstPreviewId, versionId, 1L,
                prepared, Instant.now().plusSeconds(3_600));
        plainJdbc.update("""
                UPDATE hris_performance.prf_population_previews
                   SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 hours',
                       expires_at = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                 WHERE tenant_id = ? AND population_preview_id = ?
                """, tenantId, firstPreviewId);

        UUID persistedSecond = commands.createPreview(
                tenantId, 10L, secondPreviewId, versionId, 1L,
                prepared, Instant.now().plusSeconds(3_600));

        assertThat(persistedFirst).isEqualTo(firstPreviewId);
        assertThat(persistedSecond).isEqualTo(secondPreviewId).isNotEqualTo(firstPreviewId);
        assertThat(plainJdbc.queryForObject("""
                SELECT preview_state
                  FROM hris_performance.prf_population_previews
                 WHERE tenant_id = ? AND population_preview_id = ?
                """, String.class, tenantId, firstPreviewId)).isEqualTo("STALE");
        assertThat(plainJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_population_previews
                 WHERE tenant_id = ? AND cycle_version_id = ?
                   AND preview_state = 'READY'
                """, Integer.class, tenantId, versionId)).isEqualTo(1);
        assertThat(queries.preview(tenantId, secondPreviewId).orElseThrow().members())
                .singleElement()
                .extracting(PerformanceCycleDtos.PreviewMember::primaryAssignmentRef)
                .isEqualTo(member.primaryAssignmentRef());
    }

    @Test
    void receiptReservationUsesIdempotencyConstraintAsAtomicArbiter() {
        UUID subjectId = UUID.randomUUID();
        UUID winnerReceiptId = UUID.randomUUID();
        UUID loserReceiptId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();

        boolean winner = commands.insertAcceptedReceipt(
                tenantId, 10L, subjectId, winnerReceiptId, "atomic-1",
                "UPDATE_DRAFT", "performance.cycle.update", cycleId, 3,
                "b".repeat(64), authority(
                        10L, subjectId, "UPDATE",
                        "performance.cycle.update", "c".repeat(64)));
        boolean loser = commands.insertAcceptedReceipt(
                tenantId, 10L, subjectId, loserReceiptId, "atomic-1",
                "UPDATE_DRAFT", "performance.cycle.update", cycleId, 3,
                "b".repeat(64), authority(
                        10L, subjectId, "UPDATE",
                        "performance.cycle.update", "c".repeat(64)));

        assertThat(winner).isTrue();
        assertThat(loser).isFalse();
        assertThat(queries.receiptByIdempotency(
                tenantId, subjectId, "performance.cycle.update", "atomic-1")
                .orElseThrow().view().receiptId()).isEqualTo(winnerReceiptId);
        Map<String, Object> evidence = plainJdbc.queryForMap("""
                SELECT population_scope_digest, field_policy_revision,
                       purpose_code, authorization_revision
                  FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, tenantId, winnerReceiptId);
        assertThat(evidence)
                .containsEntry("population_scope_digest", "c".repeat(64))
                .containsEntry("purpose_code", "HRIS_PERFORMANCE_CYCLE");
        assertThat(((Number) evidence.get("field_policy_revision")).longValue())
                .isEqualTo(11L);
        assertThat(((Number) evidence.get("authorization_revision")).longValue())
                .isEqualTo(23L);
        assertThat(plainJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ? AND subject_principal_public_id = ?
                   AND originating_action = ? AND idempotency_key = ?
                """, Integer.class, tenantId, subjectId,
                "performance.cycle.update", "atomic-1")).isEqualTo(1);
    }

    private PerformanceCycleDtos.CreateCycleRequest createRequest() {
        Instant start = Instant.parse("2027-01-01T00:00:00Z");
        return new PerformanceCycleDtos.CreateCycleRequest(
                UUID.randomUUID(), "FY27-" + UUID.randomUUID(), "FY27 Review",
                UUID.randomUUID(), start, null, "UTC", UUID.randomUUID(),
                UUID.randomUUID(), List.of(stage(start)));
    }

    private PerformanceCycleAuthorityPort.AuthorityEvidence authority(
            long actorId,
            UUID subjectId,
            String action,
            String operation,
            String scopeDigest) {
        return new PerformanceCycleAuthorityPort.AuthorityEvidence(
                tenantId, actorId, subjectId, "APP.HRIS", "DATA.HR_TALENT",
                action, operation, "HRIS_PERFORMANCE_CYCLE", scopeDigest, 11, 23);
    }

    private PerformanceCycleDtos.UpdateCycleRequest updateRequest(long revision) {
        Instant start = Instant.parse("2028-01-01T00:00:00Z");
        return new PerformanceCycleDtos.UpdateCycleRequest(
                UUID.randomUUID(), revision, "FY28 Review", UUID.randomUUID(),
                start, null, "UTC", UUID.randomUUID(), UUID.randomUUID(),
                List.of(stage(start)));
    }

    private PerformanceCycleDtos.StageInput stage(Instant start) {
        return new PerformanceCycleDtos.StageInput(
                "SELF", "SELF_REVIEW", 1, start, start.plusSeconds(86_400),
                true, Map.of("mode", "SELF"));
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
