package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Testcontainers
class PerformanceSharedRlsReadinessPostgresTest {

    private static final String RUNTIME_ROLE = "prf_shared_rls_runtime";
    private static final String RUNTIME_PASSWORD = "prf-shared-rls-runtime-password";
    private static final String MIGRATION_ROLE = "prf_shared_rls_migration";
    private static final List<String> PROTECTED_TABLES = List.of(
            "prf_cycles",
            "prf_cycle_versions",
            "prf_command_receipts",
            "prf_outbox_events");

    private static final long TENANT_A = 701L;
    private static final long TENANT_B = 702L;
    private static final long TENANT_PROVISIONING = 703L;
    private static final long TENANT_RETIRED = 704L;
    private static final long TENANT_SUSPENDED = 705L;
    private static final UUID SUBJECT =
            UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID CYCLE =
            UUID.fromString("71000000-0000-4000-8000-000000000002");
    private static final UUID APPROVAL_RECEIPT =
            UUID.fromString("71000000-0000-4000-8000-000000000003");
    private static final UUID SNAPSHOT =
            UUID.fromString("71000000-0000-4000-8000-000000000004");
    private static final UUID CORRELATION =
            UUID.fromString("71000000-0000-4000-8000-000000000005");
    private static final UUID WORKER =
            UUID.fromString("71000000-0000-4000-8000-000000000006");
    private static final UUID ASSIGNMENT =
            UUID.fromString("71000000-0000-4000-8000-000000000007");
    private static final UUID PROPOSAL =
            UUID.fromString("71000000-0000-4000-8000-000000000008");
    private static final UUID SNAPSHOT_LINE =
            UUID.fromString("71000000-0000-4000-8000-000000000009");
    private static final LocalDate EFFECTIVE = LocalDate.parse("2027-01-01");
    private static final Instant SNAPSHOT_TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EVENT_TIME = Instant.parse("2026-01-02T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private DataSource adminDataSource;
    private JdbcTemplate adminJdbc;
    private ObjectMapper mapper;
    private String snapshotDigest;
    private SeededEvent tenantAEvent;
    private SeededEvent tenantBEvent;
    private SeededEvent tenantBToctouEvent;
    private SeededEvent provisioningTenantEvent;
    private SeededEvent retiredTenantEvent;
    private SeededEvent suspendedTenantEvent;
    private DataSource runtimeDataSource;
    private JdbcTemplate runtimeJdbc;
    private NamedParameterJdbcTemplate runtimeNamed;
    private TransactionTemplate runtimeTransaction;
    private CompensationPlanningEventHandoffRepository handoffRepository;

    @BeforeEach
    void migrateSeedAndInstallActualForceRls() throws Exception {
        adminDataSource = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
        adminJdbc = new JdbcTemplate(adminDataSource);
        adminJdbc.execute("DROP SCHEMA IF EXISTS hris_performance CASCADE");
        adminJdbc.execute("CREATE SCHEMA hris_performance");
        Flyway.configure(getClass().getClassLoader())
                .dataSource(adminDataSource)
                .locations("classpath:db/performance-migration")
                .defaultSchema("hris_performance")
                .schemas("hris_performance")
                .table("flyway_performance_schema_history")
                .validateOnMigrate(true)
                .load()
                .migrate();
        installRealTenantRegistry();
        seedTenant(TENANT_A, "rls-tenant-a", "RLS Tenant A", "ACTIVE");
        seedTenant(TENANT_B, "rls-tenant-b", "RLS Tenant B", "ACTIVE");
        seedTenant(
                TENANT_PROVISIONING,
                "rls-tenant-provisioning",
                "RLS Tenant Provisioning",
                "PROVISIONING");
        seedTenant(
                TENANT_RETIRED,
                "rls-tenant-retired",
                "RLS Tenant Retired",
                "RETIRED");
        seedTenant(
                TENANT_SUSPENDED,
                "rls-tenant-suspended",
                "RLS Tenant Suspended",
                "SUSPENDED");

        mapper = new ObjectMapper().findAndRegisterModules();
        seedApprovedSnapshot();
        tenantAEvent = seedCreatedEvent(TENANT_A, 10);
        tenantBEvent = seedCreatedEvent(TENANT_B, 20);
        tenantBToctouEvent = seedCreatedEvent(TENANT_B, 21);
        provisioningTenantEvent = seedCreatedEvent(TENANT_PROVISIONING, 22);
        retiredTenantEvent = seedCreatedEvent(TENANT_RETIRED, 23);
        suspendedTenantEvent = seedCreatedEvent(TENANT_SUSPENDED, 24);

        installForceRlsFixture();
        runtimeDataSource = dataSource(RUNTIME_ROLE, RUNTIME_PASSWORD);
        runtimeJdbc = new JdbcTemplate(runtimeDataSource);
        runtimeNamed = new NamedParameterJdbcTemplate(runtimeDataSource);
        runtimeTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtimeDataSource));
        handoffRepository = new CompensationPlanningEventHandoffRepository(
                runtimeNamed, mapper);
    }

    @Test
    void forceRlsReadinessCoversCatalogRefetchDispatcherLifecycleAndToctou()
            throws Exception {
        assertCatalogBoundary();
        assertNoContextCannotReadProtectedTables();
        assertSnapshotRefetchIsTenantScoped();

        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        CompensationPlanningEventHandoffWorker worker =
                new CompensationPlanningEventHandoffWorker(
                        handoffRepository,
                        recorder,
                        mock(AuditOutboxRecorder.class),
                        new DomainEventContractRegistry(),
                        mapper,
                        new DataSourceTransactionManager(runtimeDataSource),
                        true,
                        "rls-readiness-worker",
                        1,
                        Duration.ofSeconds(30),
                        3,
                        ignored -> { });

        assertThat(worker.handoffOnce()).isOne();
        assertThat(deliveryState(tenantAEvent.eventId())).isEqualTo("DELIVERED");
        assertThat(deliveryState(tenantBEvent.eventId())).isEqualTo("PENDING");
        assertThat(adminJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_outbox_events
                 WHERE delivery_state = 'DELIVERED'
                """, Integer.class)).isOne();

        assertThat(worker.handoffOnce()).isOne();
        assertThat(deliveryState(tenantBEvent.eventId())).isEqualTo("DELIVERED");
        assertThat(deliveryState(tenantBToctouEvent.eventId())).isEqualTo("PENDING");
        assertPendingUnclaimed(provisioningTenantEvent);
        assertPendingUnclaimed(retiredTenantEvent);
        assertPendingUnclaimed(suspendedTenantEvent);
        verify(recorder, times(2)).record(any());

        List<Long> discoveredTenants = handoffRepository.activeTenantIds();
        assertThat(discoveredTenants).containsExactly(TENANT_A, TENANT_B);
        assertThat(discoveredTenants)
                .doesNotContain(
                        TENANT_PROVISIONING, TENANT_RETIRED, TENANT_SUSPENDED);
        assertInactiveTenantCannotBeClaimed(
                TENANT_PROVISIONING, provisioningTenantEvent);
        assertInactiveTenantCannotBeClaimed(TENANT_RETIRED, retiredTenantEvent);
        assertInactiveTenantCannotBeClaimed(TENANT_SUSPENDED, suspendedTenantEvent);
        adminJdbc.update("""
                UPDATE public.sys_service_tenants
                   SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ?
                """, TENANT_B);
        List<CompensationPlanningEventHandoffRepository.ClaimedEvent> staleDiscoveryClaim =
                Objects.requireNonNull(runtimeTransaction.execute(ignored ->
                        handoffRepository.claim(
                                TENANT_B,
                                "stale-discovery-worker",
                                10,
                                Duration.ofSeconds(30),
                                Set.of("COMPENSATION_CYCLE"))));
        assertThat(staleDiscoveryClaim).isEmpty();
        assertThat(adminJdbc.queryForMap("""
                SELECT delivery_state, delivery_attempts
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, tenantBToctouEvent.eventId()))
                .containsEntry("delivery_state", "PENDING")
                .containsEntry("delivery_attempts", 0);
        assertThat(worker.handoffOnce()).isZero();
        assertThat(handoffRepository.activeTenantIds()).containsExactly(TENANT_A);
        assertPendingUnclaimed(provisioningTenantEvent);
        assertPendingUnclaimed(retiredTenantEvent);
        assertPendingUnclaimed(suspendedTenantEvent);

        SeededEvent fenced = seedCreatedEvent(TENANT_A, 30);
        CompensationPlanningEventHandoffRepository.ClaimedEvent validClaim =
                Objects.requireNonNull(runtimeTransaction.execute(ignored ->
                        handoffRepository.claim(
                                TENANT_A,
                                "tenant-a-worker",
                                1,
                                Duration.ofSeconds(30),
                                Set.of("COMPENSATION_CYCLE"))))
                        .getFirst();
        assertThat(validClaim.eventId()).isEqualTo(fenced.eventId());
        CompensationPlanningEventHandoffRepository.ClaimedEvent forgedTenant =
                new CompensationPlanningEventHandoffRepository.ClaimedEvent(
                        validClaim.eventId(),
                        TENANT_B,
                        validClaim.commandReceiptId(),
                        validClaim.aggregateType(),
                        validClaim.aggregateId(),
                        validClaim.aggregateVersion(),
                        validClaim.eventName(),
                        validClaim.payload(),
                        validClaim.deliveryAttempt(),
                        validClaim.createdAt(),
                        validClaim.workerId());
        Boolean forgedDelivered = runtimeTransaction.execute(
                ignored -> handoffRepository.markDelivered(forgedTenant));
        assertThat(forgedDelivered).isFalse();
        CompensationPlanningEventHandoffRepository.FailureDisposition forgedFailure =
                runtimeTransaction.execute(
                        ignored -> handoffRepository.markFailed(
                                forgedTenant, 3, true));
        assertThat(forgedFailure)
                .isEqualTo(
                        CompensationPlanningEventHandoffRepository.FailureDisposition.STALE);

        adminJdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET delivery_state = 'QUARANTINED'
                 WHERE tenant_id = ? AND event_id = ?
                """, TENANT_A, fenced.eventId());
        Optional<CompensationPlanningEventHandoffRepository.ReleasedEvent> wrongRelease =
                runtimeTransaction.execute(ignored ->
                        handoffRepository.releaseQuarantined(
                                TENANT_B,
                                fenced.eventId(),
                                CompensationPlanningEventHandoffWorker
                                        .supportedCycleEventNames(),
                                CompensationPlanningEventHandoffWorker
                                        .supportedProposalEventNames()));
        assertThat(wrongRelease).isEmpty();
        Optional<CompensationPlanningEventHandoffRepository.ReleasedEvent> correctRelease =
                runtimeTransaction.execute(ignored ->
                        handoffRepository.releaseQuarantined(
                                TENANT_A,
                                fenced.eventId(),
                                CompensationPlanningEventHandoffWorker
                                        .supportedCycleEventNames(),
                                CompensationPlanningEventHandoffWorker
                                        .supportedProposalEventNames()));
        assertThat(correctRelease)
                .hasValueSatisfying(released -> {
                    assertThat(released.eventId()).isEqualTo(fenced.eventId());
                    assertThat(released.tenantId()).isEqualTo(TENANT_A);
                });
    }

    private void assertSnapshotRefetchIsTenantScoped() {
        CompensationPlanningRepository repository =
                new CompensationPlanningRepository(runtimeNamed, mapper);
        ApprovedCompensationPlanSnapshotProvider provider =
                new ApprovedCompensationPlanSnapshotProvider(
                        repository,
                        new CompensationPlanningCanonicalizer(mapper),
                        mock(AuditOutboxRecorder.class));
        ApprovedCompensationSnapshotRefetchService refetch =
                new ApprovedCompensationSnapshotRefetchService(
                        new ApprovedCompensationSnapshotOwnerAuthority(runtimeNamed),
                        provider);

        ApprovedCompensationSnapshotRequestContext.VerifiedRequest allowed =
                verifiedSnapshotRequest(TENANT_A);
        Xcon020ApprovedCompensationPlanSnapshotV1 result =
                runtimeTransaction.execute(ignored -> refetch.get(allowed));
        assertThat(result).isNotNull();
        assertThat(result.snapshotId()).isEqualTo(SNAPSHOT);
        assertThat(result.snapshotRevision()).isOne();
        assertThat(result.payloadDigest()).isEqualTo(snapshotDigest);

        ApprovedCompensationSnapshotRequestContext.VerifiedRequest wrongTenant =
                verifiedSnapshotRequest(TENANT_B);
        assertThatThrownBy(() -> runtimeTransaction.execute(
                ignored -> refetch.get(wrongTenant)))
                .isInstanceOf(BaseException.class);
    }

    private ApprovedCompensationSnapshotRequestContext.VerifiedRequest
            verifiedSnapshotRequest(long tenantId) {
        return new ApprovedCompensationSnapshotRequestContext.VerifiedRequest(
                ApprovedCompensationSnapshotIngressFilter.PATH_PREFIX + SNAPSHOT,
                SNAPSHOT,
                1L,
                snapshotDigest,
                tenantId,
                4L,
                9L,
                CORRELATION,
                ApprovedCompensationPlanSnapshotProvider.CALLER_WORKLOAD,
                ApprovedCompensationPlanSnapshotProvider.AUDIENCE,
                ApprovedCompensationPlanSnapshotProvider.PURPOSE,
                ApprovedCompensationPlanSnapshotProvider.PROJECTION);
    }

    private void installRealTenantRegistry() throws Exception {
        ClassPathResource registry = new ClassPathResource(
                "db/migration/V3__add_provider_tenant_registry.sql");
        try (Connection connection = adminDataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS public.sys_service_tenants CASCADE");
            statement.execute(registry.getContentAsString(StandardCharsets.UTF_8));
        }
        adminJdbc.update("DELETE FROM public.sys_service_tenants WHERE tenant_id = 1");
    }

    private void seedTenant(
            long tenantId,
            String key,
            String displayName,
            String lifecycleState) {
        adminJdbc.update("""
                INSERT INTO public.sys_service_tenants (
                    provider_tenant_id, tenant_id, tenant_key, display_name,
                    lifecycle_state, data_region, isolation_model, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'local', 'POOL', 1, 1)
                """, UUID.nameUUIDFromBytes(("provider-" + tenantId)
                        .getBytes(StandardCharsets.UTF_8)), tenantId, key, displayName,
                lifecycleState);
    }

    private void seedApprovedSnapshot() {
        UUID guideline = UUID.fromString("71000000-0000-4000-8000-000000000010");
        UUID population = UUID.fromString("71000000-0000-4000-8000-000000000011");
        insertArtifact(guideline, "COMPENSATION_GUIDELINES");
        insertArtifact(population, "COMPENSATION_POPULATION");
        long cycleInternalId = adminJdbc.queryForObject("""
                INSERT INTO hris_performance.prf_cmp_cycles (
                    tenant_id, public_id, aggregate_version, created_at, created_by,
                    updated_at, correlation_id, cycle_code,
                    guideline_artifact_public_id, population_artifact_public_id,
                    effective_from, effective_to, status)
                VALUES (?, ?, 7, ?, ?, ?, ?, 'RLS27', ?, ?, ?, NULL, 'APPROVED')
                RETURNING prf_cmp_cycles_internal_id
                """, Long.class, TENANT_A, CYCLE, timestamp(SNAPSHOT_TIME), SUBJECT,
                timestamp(SNAPSHOT_TIME), CORRELATION, guideline, population,
                Date.valueOf(EFFECTIVE));
        insertReceipt(
                TENANT_A,
                APPROVAL_RECEIPT,
                CYCLE,
                6L,
                7L,
                "snapshot-approval",
                "modern.compplan.plan.approve",
                "APPROVE");

        CompensationPlanningCanonicalizer canonicalizer =
                new CompensationPlanningCanonicalizer(mapper);
        String lineDigest = canonicalizer.lineDigest(
                SNAPSHOT_LINE,
                1,
                WORKER,
                ASSIGNMENT,
                "BASE",
                "USD",
                "99.5",
                EFFECTIVE,
                null);
        CompensationPlanningDtos.SnapshotLine line =
                new CompensationPlanningDtos.SnapshotLine(
                        SNAPSHOT_LINE,
                        1,
                        PROPOSAL,
                        WORKER,
                        ASSIGNMENT,
                        "BASE",
                        "USD",
                        "99.5",
                        EFFECTIVE,
                        null,
                        lineDigest);
        snapshotDigest = canonicalizer.payloadDigest(
                APPROVAL_RECEIPT,
                CYCLE,
                EFFECTIVE,
                null,
                List.of(line),
                SNAPSHOT,
                1L,
                7L);
        CompensationPlanningRepository repository = new CompensationPlanningRepository(
                new NamedParameterJdbcTemplate(adminDataSource), mapper);
        CompensationPlanningRepository.CycleRow cycle =
                new CompensationPlanningRepository.CycleRow(
                        cycleInternalId,
                        CYCLE,
                        7L,
                        SNAPSHOT_TIME,
                        SUBJECT,
                        SNAPSHOT_TIME,
                        CORRELATION,
                        "RLS27",
                        guideline,
                        population,
                        EFFECTIVE,
                        null,
                        "APPROVED");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(adminDataSource));
        transaction.executeWithoutResult(ignored -> {
            repository.bindTenant(TENANT_A);
            repository.insertSnapshot(
                    TENANT_A,
                    SUBJECT,
                    cycle,
                    7L,
                    APPROVAL_RECEIPT,
                    SNAPSHOT,
                    1L,
                    List.of(line),
                    snapshotDigest,
                    CORRELATION,
                    SNAPSHOT_TIME);
        });
    }

    private void insertArtifact(UUID artifactId, String artifactType) {
        adminJdbc.update("""
                INSERT INTO hris_performance.prf_per_artifact_versions (
                    tenant_id, public_id, artifact_type, schema_version,
                    artifact_revision, owner_aggregate_type,
                    owner_aggregate_public_id, canonical_content_digest,
                    encrypted_content, encryption_key_version_public_id,
                    effective_from, effective_to, created_at, created_by)
                VALUES (?, ?, ?, 1, 1, 'COMPENSATION_CYCLE', ?, ?, ?, ?, ?, NULL, ?, ?)
                """, TENANT_A, artifactId, artifactType, CYCLE, "a".repeat(64),
                new byte[29], UUID.randomUUID(), Date.valueOf(EFFECTIVE),
                timestamp(SNAPSHOT_TIME), SUBJECT);
    }

    private SeededEvent seedCreatedEvent(long tenantId, int ordinal) throws Exception {
        UUID receiptId = UUID.nameUUIDFromBytes(
                ("rls-receipt-" + tenantId + "-" + ordinal)
                        .getBytes(StandardCharsets.UTF_8));
        UUID eventId = UUID.nameUUIDFromBytes(
                ("rls-event-" + tenantId + "-" + ordinal)
                        .getBytes(StandardCharsets.UTF_8));
        UUID aggregateId = UUID.nameUUIDFromBytes(
                ("rls-aggregate-" + tenantId + "-" + ordinal)
                        .getBytes(StandardCharsets.UTF_8));
        Instant occurredAt = EVENT_TIME.plusSeconds(ordinal);
        insertReceipt(
                tenantId,
                receiptId,
                aggregateId,
                0L,
                1L,
                "event-" + ordinal,
                "modern.compplan.cycle.create",
                "PROPOSE");
        ObjectNode payload = mapper.createObjectNode()
                .put("aggregateId", aggregateId.toString())
                .put("fromState", "NONE")
                .put("toState", "DRAFT")
                .put("aggregateVersion", 1L)
                .put("occurredAt", occurredAt.toString())
                .put("correlationId", UUID.nameUUIDFromBytes(
                        ("rls-correlation-" + tenantId + "-" + ordinal)
                                .getBytes(StandardCharsets.UTF_8)).toString());
        adminJdbc.update("""
                INSERT INTO hris_performance.prf_outbox_events (
                    event_id, tenant_id, command_receipt_id, aggregate_type,
                    aggregate_id, aggregate_version, event_type, payload,
                    delivery_state, delivery_attempts, available_at, created_at)
                VALUES (?, ?, ?, 'COMPENSATION_CYCLE', ?, 1,
                        'CompensationCycleCreated.v2', CAST(? AS JSONB),
                        'PENDING', 0, ?, ?)
                """, eventId, tenantId, receiptId, aggregateId,
                mapper.writeValueAsString(payload), timestamp(occurredAt),
                timestamp(occurredAt));
        return new SeededEvent(eventId, receiptId, aggregateId);
    }

    private void insertReceipt(
            long tenantId,
            UUID receiptId,
            UUID aggregateId,
            long expectedVersion,
            long appliedVersion,
            String idempotencySuffix,
            String operation,
            String action) {
        adminJdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version,
                    applied_aggregate_version, request_hash,
                    population_scope_digest, field_policy_revision, purpose_code,
                    authorization_revision, receipt_state, result_ref,
                    accepted_at, completed_at)
                VALUES (?, ?, ?, ?, ?, 9, ?, ?, ?, ?, ?, ?, 1,
                        'COMPENSATION_PLANNING', 1, 'SUCCEEDED', ?, ?, ?)
                """, receiptId, tenantId, "rls-" + tenantId + "-" + idempotencySuffix,
                operation, action, SUBJECT, aggregateId, expectedVersion,
                appliedVersion, "b".repeat(64), "c".repeat(64), aggregateId,
                timestamp(SNAPSHOT_TIME), timestamp(SNAPSHOT_TIME));
    }

    private void installForceRlsFixture() {
        dropRoleIfPresent(RUNTIME_ROLE);
        dropRoleIfPresent(MIGRATION_ROLE);
        adminJdbc.execute("CREATE ROLE " + MIGRATION_ROLE
                + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        adminJdbc.execute("CREATE ROLE " + RUNTIME_ROLE + " LOGIN PASSWORD '"
                + RUNTIME_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        adminJdbc.execute("GRANT USAGE, CREATE ON SCHEMA hris_performance TO "
                + MIGRATION_ROLE);
        adminJdbc.execute("GRANT USAGE ON SCHEMA hris_performance TO " + RUNTIME_ROLE);
        adminJdbc.execute("GRANT USAGE ON SCHEMA public TO "
                + MIGRATION_ROLE + ", " + RUNTIME_ROLE);
        adminJdbc.execute("GRANT SELECT, UPDATE ON public.sys_service_tenants TO "
                + MIGRATION_ROLE + ", " + RUNTIME_ROLE);
        adminJdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
                + "hris_performance TO " + MIGRATION_ROLE + ", " + RUNTIME_ROLE);
        adminJdbc.execute("GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA "
                + "hris_performance TO " + MIGRATION_ROLE + ", " + RUNTIME_ROLE);

        for (String table : PROTECTED_TABLES) {
            String qualified = "hris_performance." + table;
            adminJdbc.execute("ALTER TABLE " + qualified + " OWNER TO " + MIGRATION_ROLE);
            adminJdbc.execute("ALTER TABLE " + qualified + " ENABLE ROW LEVEL SECURITY");
            adminJdbc.execute("ALTER TABLE " + qualified + " FORCE ROW LEVEL SECURITY");
            adminJdbc.execute("CREATE POLICY future_tenant_isolation ON " + qualified + " "
                    + "USING (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT) "
                    + "WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT)");
        }

        adminJdbc.execute("ALTER FUNCTION hris_performance."
                + "prf_serialize_population_preview() OWNER TO " + MIGRATION_ROLE);
        adminJdbc.execute("ALTER FUNCTION hris_performance."
                + "prf_capture_review_operation_trigger() OWNER TO " + MIGRATION_ROLE);
        adminJdbc.execute("GRANT EXECUTE ON FUNCTION hris_performance."
                + "prf_capture_review_operation(BIGINT, UUID) TO " + MIGRATION_ROLE);
        adminJdbc.execute("REVOKE ALL ON FUNCTION hris_performance."
                + "prf_serialize_population_preview() FROM PUBLIC, " + RUNTIME_ROLE);
        adminJdbc.execute("REVOKE ALL ON FUNCTION hris_performance."
                + "prf_capture_review_operation_trigger() FROM PUBLIC, " + RUNTIME_ROLE);
    }

    private void dropRoleIfPresent(String role) {
        Boolean exists = adminJdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = ?)",
                Boolean.class,
                role);
        if (Boolean.TRUE.equals(exists)) {
            adminJdbc.execute("DROP OWNED BY " + role);
            adminJdbc.execute("DROP ROLE " + role);
        }
    }

    private void assertCatalogBoundary() {
        assertThat(adminJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = relation.relnamespace
                  JOIN pg_catalog.pg_roles owner_role
                    ON owner_role.oid = relation.relowner
                 WHERE namespace.nspname = 'hris_performance'
                   AND relation.relname IN (
                       'prf_cycles', 'prf_cycle_versions',
                       'prf_command_receipts', 'prf_outbox_events')
                   AND relation.relrowsecurity
                   AND relation.relforcerowsecurity
                   AND owner_role.rolname = ?
                """, Integer.class, MIGRATION_ROLE)).isEqualTo(4);
        assertThat(adminJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_policies
                 WHERE schemaname = 'hris_performance'
                   AND tablename IN (
                       'prf_cycles', 'prf_cycle_versions',
                       'prf_command_receipts', 'prf_outbox_events')
                   AND policyname = 'future_tenant_isolation'
                   AND qual = with_check
                   AND qual LIKE '%current_setting%'
                """, Integer.class)).isEqualTo(4);
        assertThat(adminJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_roles
                 WHERE rolname IN (?, ?)
                   AND NOT rolsuper
                   AND NOT rolbypassrls
                """, Integer.class, RUNTIME_ROLE, MIGRATION_ROLE)).isEqualTo(2);
        assertThat(adminJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_proc routine
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = routine.pronamespace
                  JOIN pg_catalog.pg_roles owner_role
                    ON owner_role.oid = routine.proowner
                 WHERE namespace.nspname = 'hris_performance'
                   AND routine.proname IN (
                       'prf_serialize_population_preview',
                       'prf_capture_review_operation_trigger')
                   AND routine.prosecdef
                   AND owner_role.rolname = ?
                """, Integer.class, MIGRATION_ROLE)).isEqualTo(2);
        assertThat(adminJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_proc routine
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = routine.pronamespace
                 WHERE namespace.nspname = 'hris_performance'
                   AND routine.proname IN (
                       'prf_serialize_population_preview',
                       'prf_capture_review_operation_trigger')
                   AND pg_catalog.has_function_privilege(?, routine.oid, 'EXECUTE')
                """, Integer.class, RUNTIME_ROLE)).isZero();
    }

    private void assertNoContextCannotReadProtectedTables() {
        assertThat(adminJdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_command_receipts
                """, Integer.class)).isGreaterThan(0);
        assertThat(adminJdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_outbox_events
                """, Integer.class)).isEqualTo(6);
        for (String table : PROTECTED_TABLES) {
            assertThat(runtimeJdbc.queryForObject(
                    "SELECT COUNT(*) FROM hris_performance." + table,
                    Integer.class))
                    .as("runtime read without dwp.tenant_id on %s", table)
                    .isZero();
        }

        assertThat(runtimeJdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET delivery_state = 'DELIVERED'
                 WHERE event_id = ?
                """, tenantAEvent.eventId())).isZero();
        assertThat(deliveryState(tenantAEvent.eventId())).isEqualTo("PENDING");

        UUID rejectedCycle =
                UUID.fromString("71000000-0000-4000-8000-000000000099");
        int reviewHistoryBefore = adminJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_review_operation_history
                """, Integer.class);
        assertThatThrownBy(() -> runtimeJdbc.update("""
                INSERT INTO hris_performance.prf_cycles (
                    cycle_id, tenant_id, cycle_key, display_name, lifecycle_state,
                    retention_policy_id, created_by, updated_by)
                VALUES (?, ?, 'NO-CONTEXT-RLS', 'Rejected no-context cycle', 'DRAFT',
                        ?, 99, 99)
                """, rejectedCycle, TENANT_A, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("row-level security");
        assertThat(adminJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_cycles
                 WHERE cycle_id = ?
                """, Integer.class, rejectedCycle)).isZero();
        assertThat(adminJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_review_operation_history
                """, Integer.class)).isEqualTo(reviewHistoryBefore);
    }

    private String deliveryState(UUID eventId) {
        return adminJdbc.queryForObject("""
                SELECT delivery_state
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, String.class, eventId);
    }

    private void assertPendingUnclaimed(SeededEvent event) {
        assertThat(adminJdbc.queryForMap("""
                SELECT delivery_state, delivery_attempts
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, event.eventId()))
                .containsEntry("delivery_state", "PENDING")
                .containsEntry("delivery_attempts", 0);
    }

    private void assertInactiveTenantCannotBeClaimed(
            long tenantId,
            SeededEvent event) {
        List<CompensationPlanningEventHandoffRepository.ClaimedEvent> claimed =
                Objects.requireNonNull(runtimeTransaction.execute(ignored ->
                        handoffRepository.claim(
                                tenantId,
                                "inactive-tenant-worker",
                                10,
                                Duration.ofSeconds(30),
                                Set.of("COMPENSATION_CYCLE"))));
        assertThat(claimed).isEmpty();
        assertPendingUnclaimed(event);
    }

    private DataSource dataSource(String username, String password) {
        PGSimpleDataSource value = new PGSimpleDataSource();
        value.setURL(POSTGRES.getJdbcUrl());
        value.setUser(username);
        value.setPassword(password);
        return value;
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private record SeededEvent(UUID eventId, UUID receiptId, UUID aggregateId) {
    }
}
