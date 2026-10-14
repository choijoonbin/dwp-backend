package com.dwp.services.platform.auditcontrol;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.hriscontracts.HrisAuditOutboxEventAdapter;
import com.dwp.platform.contracts.hris.generated.HrisAuditOutboxEventV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class HcmAuditEvidenceRepositoryPostgresTest {

    private static final long TENANT_A = 9_301L;
    private static final long TENANT_B = 9_302L;
    private static final Instant SNAPSHOT = Instant.parse("2026-10-14T06:00:00Z");
    private static final UUID FIRST = UUID.fromString(
            "10000000-0000-4000-8000-000000000003");
    private static final UUID SECOND = UUID.fromString(
            "10000000-0000-4000-8000-000000000002");
    private static final UUID THIRD = UUID.fromString(
            "10000000-0000-4000-8000-000000000001");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static HcmAuditEvidenceRepository repository;
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().findAndRegisterModules();

    @BeforeAll
    static void migrate() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new HcmAuditEvidenceRepository(
                new NamedParameterJdbcTemplate(dataSource));
    }

    @Test
    void isolatesTenantFingerprintAndFrozenKeysetAgainstTheRealSchema() throws Exception {
        insertCanonical(TENANT_A, FIRST, SNAPSHOT.minusSeconds(10),
                SNAPSHOT.minusSeconds(5), metadata());
        insertCanonical(TENANT_A, SECOND, SNAPSHOT.minusSeconds(20),
                SNAPSHOT.minusSeconds(5), metadata());
        insertCanonical(TENANT_A, THIRD, SNAPSHOT.minusSeconds(20),
                SNAPSHOT.minusSeconds(5), metadata());
        UUID foreign = UUID.fromString("20000000-0000-4000-8000-000000000001");
        insertCanonical(TENANT_B, foreign, SNAPSHOT.minusSeconds(15),
                SNAPSHOT.minusSeconds(5), metadata());

        UUID extraMetadata = UUID.fromString(
                "30000000-0000-4000-8000-000000000001");
        Map<String, Object> widened = metadata();
        widened.put("actorDisplayName", "must-not-be-selected");
        insertCanonical(TENANT_A, extraMetadata, SNAPSHOT.minusSeconds(12),
                SNAPSHOT.minusSeconds(5), widened);
        UUID rawState = UUID.fromString("30000000-0000-4000-8000-000000000002");
        insert(TENANT_A, rawState, SNAPSHOT.minusSeconds(13),
                SNAPSHOT.minusSeconds(5), metadata(), "{\"salary\":1}", "hris.hrm");
        UUID sourceModuleOnly = UUID.fromString(
                "30000000-0000-4000-8000-000000000003");
        insert(TENANT_A, sourceModuleOnly, SNAPSHOT.minusSeconds(14),
                SNAPSHOT.minusSeconds(5), Map.of(), "{}", "hris.hrm");

        HcmAuditEvidenceCriteria criteria = criteria();
        List<HcmAuditEvidenceDtos.Evidence> firstPage =
                repository.list(criteria, null, 2);
        assertThat(firstPage)
                .extracting(HcmAuditEvidenceDtos.Evidence::eventId)
                .containsExactly(FIRST, SECOND);
        assertThat(firstPage.getFirst().sourceModule()).isEqualTo("hris.hrm");
        assertThat(firstPage.getFirst().effectiveCapability())
                .isEqualTo("HRIS.WORKER.UPDATE");

        insertCanonical(TENANT_A,
                UUID.fromString("40000000-0000-4000-8000-000000000001"),
                SNAPSHOT.minusSeconds(5), SNAPSHOT.plusSeconds(1), metadata());
        HcmAuditEvidenceCursorCodec.CursorPosition position =
                new HcmAuditEvidenceCursorCodec.CursorPosition(
                        SNAPSHOT,
                        firstPage.getLast().occurredAt(),
                        firstPage.getLast().eventId());
        List<HcmAuditEvidenceDtos.Evidence> secondPage =
                repository.list(criteria, position, 2);
        assertThat(secondPage)
                .extracting(HcmAuditEvidenceDtos.Evidence::eventId)
                .containsExactly(THIRD)
                .doesNotContain(FIRST, SECOND, foreign, extraMetadata, rawState,
                        sourceModuleOnly);

        assertThat(repository.findById(TENANT_A, FIRST, SNAPSHOT)).isPresent();
        assertThat(repository.findById(TENANT_B, FIRST, SNAPSHOT)).isEmpty();
        assertThat(repository.findById(TENANT_A, foreign, SNAPSHOT)).isEmpty();
        assertThat(repository.findById(TENANT_A, extraMetadata, SNAPSHOT)).isEmpty();
        assertThat(repository.findById(TENANT_A, rawState, SNAPSHOT)).isEmpty();
        assertThat(repository.findById(TENANT_A, sourceModuleOnly, SNAPSHOT)).isEmpty();
    }

    @Test
    void canonicalAdapterRiskEnrichmentAndDatabaseIngestRemainProjectable() {
        long tenantId = 9_303L;
        Instant now = Instant.now();
        UUID eventId = UUID.fromString("70000000-0000-4000-8000-000000000001");
        HrisAuditOutboxEventV1 source = new HrisAuditOutboxEventV1(
                eventId,
                tenantId,
                now.minusSeconds(10),
                "dwp.people",
                "hris.sys",
                "AUDIT_POLICY",
                "policy:1",
                1L,
                "POLICY_DENIED",
                "DENIED",
                "USER",
                "actor:1",
                "HRIS.AUDIT.VIEW",
                "HRIS_AUDIT_EVIDENCE",
                "decision:1",
                1L,
                1L,
                null,
                null,
                null,
                null,
                "policy:1",
                "SOD_CONFLICT",
                "ACCESS_DENIED",
                null,
                null,
                "c".repeat(64),
                "RESTRICTED",
                "EXTENDED",
                UUID.fromString("70000000-0000-4000-8000-000000000002"),
                UUID.fromString("70000000-0000-4000-8000-000000000003"));

        AuditEvent adapted = HrisAuditOutboxEventAdapter.toAuditEvent(source);
        AuditEvent enriched = new AuditRiskEngine().enrich(adapted);
        assertThat(enriched.policyDecision()).isEqualTo("DENY");
        assertThat(enriched.severity()).isEqualTo("CRITICAL");
        AuditControlEventRepository eventRepository = new AuditControlEventRepository(
                new NamedParameterJdbcTemplate(dataSource), OBJECT_MAPPER);
        assertThat(eventRepository.ingest(enriched, List.of(), "d".repeat(64)))
                .isEqualTo(1);

        Instant snapshot = Instant.now().plusSeconds(5);
        HcmAuditEvidenceCriteria criteria = HcmAuditEvidenceCriteria.of(
                tenantId,
                AuditWindow.H24,
                "POLICY_DENIED",
                "CRITICAL",
                "DENIED",
                "dwp.people",
                null,
                10,
                snapshot,
                Set.of("dwp.people"));
        assertThat(repository.list(criteria, null, 10))
                .singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.eventId()).isEqualTo(eventId);
                    assertThat(evidence.severity()).isEqualTo("CRITICAL");
                    assertThat(evidence.reasonCode()).isEqualTo("ACCESS_DENIED");
                    assertThat(evidence.effectiveCapability())
                            .isEqualTo("HRIS.AUDIT.VIEW");
                });
    }

    @Test
    void rejectsEveryFingerprintMutationAndAcceptsVersionSevenUuidEvidence()
            throws Exception {
        long tenantId = 9_304L;
        UUID canonical = UUID.fromString(
                "018f47a2-7b3c-7def-8abc-0123456789ab");
        String v7Correlation = "018f47a2-7b3c-7def-8abc-0123456789ac";
        Map<String, Object> v7Metadata = metadata();
        v7Metadata.put("causationId",
                "018f47a2-7b3c-7def-8abc-0123456789ad");
        insert(tenantId, canonical, SNAPSHOT.minusSeconds(10),
                SNAPSHOT.minusSeconds(5), v7Metadata, "{}", "hris.hrm",
                "ALLOW", v7Correlation);

        Map<String, Object> missingKey = metadata();
        missingKey.remove("purposeCode");
        UUID missingKeyId = UUID.fromString(
                "018f47a2-7b3c-7def-8abc-0123456789b1");
        insert(tenantId, missingKeyId, SNAPSHOT.minusSeconds(11),
                SNAPSHOT.minusSeconds(5), missingKey, "{}", "hris.hrm");

        Map<String, Object> wrongType = metadata();
        wrongType.put("aggregateRevision", Map.of("value", 3));
        UUID wrongTypeId = UUID.fromString(
                "018f47a2-7b3c-7def-8abc-0123456789b2");
        insert(tenantId, wrongTypeId, SNAPSHOT.minusSeconds(12),
                SNAPSHOT.minusSeconds(5), wrongType, "{}", "hris.hrm");

        Map<String, Object> badDigest = metadata();
        badDigest.put("evidenceDigest", "not-a-sha256");
        UUID badDigestId = UUID.fromString(
                "018f47a2-7b3c-7def-8abc-0123456789b3");
        insert(tenantId, badDigestId, SNAPSHOT.minusSeconds(13),
                SNAPSHOT.minusSeconds(5), badDigest, "{}", "hris.hrm");

        Map<String, Object> badCausation = metadata();
        badCausation.put("causationId", "not-a-uuid");
        UUID badCausationId = UUID.fromString(
                "018f47a2-7b3c-7def-8abc-0123456789b4");
        insert(tenantId, badCausationId, SNAPSHOT.minusSeconds(14),
                SNAPSHOT.minusSeconds(5), badCausation, "{}", "hris.hrm");

        UUID badCorrelationId = UUID.fromString(
                "018f47a2-7b3c-7def-8abc-0123456789b5");
        insert(tenantId, badCorrelationId, SNAPSHOT.minusSeconds(15),
                SNAPSHOT.minusSeconds(5), metadata(), "{}", "hris.hrm",
                "ALLOW", "not-a-uuid");

        UUID nullDecisionId = UUID.fromString(
                "018f47a2-7b3c-7def-8abc-0123456789b6");
        insert(tenantId, nullDecisionId, SNAPSHOT.minusSeconds(16),
                SNAPSHOT.minusSeconds(5), metadata(), "{}", "hris.hrm",
                null, v7Correlation);

        UUID mismatchedDecisionId = UUID.fromString(
                "018f47a2-7b3c-7def-8abc-0123456789b7");
        insert(tenantId, mismatchedDecisionId, SNAPSHOT.minusSeconds(17),
                SNAPSHOT.minusSeconds(5), metadata(), "{}", "hris.hrm",
                "DENY", v7Correlation);

        HcmAuditEvidenceCriteria criteria = HcmAuditEvidenceCriteria.of(
                tenantId, AuditWindow.H24, "ALL", "ALL", "ALL", null,
                null, 20, SNAPSHOT, Set.of("dwp.people"));

        assertThat(repository.list(criteria, null, 20))
                .extracting(HcmAuditEvidenceDtos.Evidence::eventId)
                .containsExactly(canonical)
                .doesNotContain(missingKeyId, wrongTypeId, badDigestId,
                        badCausationId, badCorrelationId, nullDecisionId,
                        mismatchedDecisionId);
        assertThat(repository.findById(tenantId, canonical, SNAPSHOT))
                .get()
                .satisfies(evidence -> {
                    assertThat(evidence.causationId().toString())
                            .isEqualTo(v7Metadata.get("causationId"));
                    assertThat(evidence.correlationId().toString())
                            .isEqualTo(v7Correlation);
                });
    }

    private HcmAuditEvidenceCriteria criteria() {
        return HcmAuditEvidenceCriteria.of(
                TENANT_A,
                AuditWindow.H24,
                "ALL",
                "ALL",
                "ALL",
                null,
                null,
                10,
                SNAPSHOT,
                Set.of("dwp.people"));
    }

    private void insertCanonical(
            long tenantId,
            UUID eventId,
            Instant occurredAt,
            Instant ingestedAt,
            Map<String, Object> metadata) throws Exception {
        insert(tenantId, eventId, occurredAt, ingestedAt, metadata, "{}", "hris.hrm");
    }

    private void insert(
            long tenantId,
            UUID eventId,
            Instant occurredAt,
            Instant ingestedAt,
            Map<String, Object> metadata,
            String beforeState,
            String sourceModule) throws Exception {
        insert(tenantId, eventId, occurredAt, ingestedAt, metadata,
                beforeState, sourceModule, "ALLOW",
                "50000000-0000-4000-8000-000000000001");
    }

    private void insert(
            long tenantId,
            UUID eventId,
            Instant occurredAt,
            Instant ingestedAt,
            Map<String, Object> metadata,
            String beforeState,
            String sourceModule,
            String policyDecision,
            String correlationId) throws Exception {
        jdbc.update("""
                INSERT INTO sys_audit_events (
                    event_id, occurred_at, ingested_at, event_version, tenant_id,
                    category, action, outcome, severity, risk_score,
                    actor_type, actor_id, source_service, source_module, environment,
                    target_type, target_id, correlation_id, policy_id, policy_decision,
                    before_state, after_state, changed_fields, metadata,
                    retention_class, record_hash)
                VALUES (?, ?, ?, '1.0', ?,
                    'ADMIN_CHANGE', 'WORKER_UPDATED', 'SUCCESS', 'INFO', 0,
                    'USER', 'actor:1', 'dwp.people', ?, 'test',
                    'WORKER', 'worker:11', ?, 'decision:1', ?,
                    CAST(? AS jsonb), '{}'::jsonb, ARRAY[]::text[], CAST(? AS jsonb),
                    'EXTENDED', ?)
                """,
                eventId,
                Timestamp.from(occurredAt),
                Timestamp.from(ingestedAt),
                tenantId,
                sourceModule,
                correlationId,
                policyDecision,
                beforeState,
                OBJECT_MAPPER.writeValueAsString(metadata),
                "f".repeat(64));
    }

    private Map<String, Object> metadata() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("aggregateRef", "worker:11");
        values.put("aggregateRevision", 3);
        values.put("effectiveCapability", "HRIS.WORKER.UPDATE");
        values.put("purposeCode", "EMPLOYMENT_ADMINISTRATION");
        values.put("scopeRevision", 5);
        values.put("fieldPolicyRevision", 7);
        values.put("beforeDigest", "a".repeat(64));
        values.put("afterDigest", "b".repeat(64));
        values.put("evidenceDigest", "c".repeat(64));
        values.put("classification", "RESTRICTED");
        values.put("causationId",
                UUID.fromString("60000000-0000-4000-8000-000000000001").toString());
        return values;
    }
}
