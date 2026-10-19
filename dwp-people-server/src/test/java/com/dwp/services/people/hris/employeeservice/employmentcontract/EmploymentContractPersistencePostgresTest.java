package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.platform.contracts.hris.generated.EmploymentContractTemplateSnapshotV1;
import com.dwp.services.people.hris.contracts.hrm011.Hrm011EventOutbox;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AC-11 PostgreSQL proof for HRM-011 transaction and idempotency semantics. */
@Testcontainers(disabledWithoutDocker = true)
class EmploymentContractPersistencePostgresTest {

    private static final long ROLLBACK_TENANT = 59_111L;
    private static final long REPLAY_TENANT = 59_112L;
    private static final long CONCURRENT_TENANT = 59_113L;
    private static final long OUTBOX_TENANT = 59_114L;
    private static final long USER_ID = 7L;
    private static final long EXPECTED_VERSION = 3L;
    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    private static final LocalDate SOURCE_FROM = LocalDate.parse("2026-01-01");
    private static final LocalDate ISSUE_FROM = LocalDate.parse("2026-10-19");
    private static final String AUTHORIZATION_REVISION = "psr-" + "a".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactionManager;
    private static TransactionTemplate transactions;
    private static ObjectMapper mapper;

    @BeforeAll
    static void migrateOnceThroughV59() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        dataSource = source;

        Flyway flyway = Flyway.configure(
                        EmploymentContractPersistencePostgresTest.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("59");

        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        mapper = new ObjectMapper().findAndRegisterModules();
    }

    @AfterEach
    void clearActor() {
        PeopleRequestContext.clear();
    }

    @Test
    void providerSuccessThenPostOutboxFailureRollsBackAndRetryUsesSameArtifactRequestId() {
        Graph graph = seedGraph(ROLLBACK_TENANT, "rollback");
        JdbcEmploymentContractStore jdbcStore = jdbcStore();
        PostProviderFailureStore failingStore = new PostProviderFailureStore(jdbcStore);
        CapturingArtifactPort artifacts = new CapturingArtifactPort("rollback");
        Fixture fixture = fixture("rollback", graph, failingStore, artifacts);

        assertThatThrownBy(() -> fixture.issue(fixture.request(), fixture.correlationId()))
                .isInstanceOf(ForcedPostProviderFailure.class);

        assertThat(artifacts.artifactRequestIds()).hasSize(1);
        assertDurableCounts(ROLLBACK_TENANT, 0);

        EmploymentContractService.CommandResult retried =
                fixture.issue(fixture.request(), fixture.correlationId());

        assertThat(retried.receipt().status()).isEqualTo("SUCCEEDED");
        assertThat(retried.entityVersion()).isEqualTo(1L);
        assertThat(artifacts.artifactRequestIds()).hasSize(2);
        assertThat(new HashSet<>(artifacts.artifactRequestIds())).hasSize(1);
        assertDurableCounts(ROLLBACK_TENANT, 1);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_command_receipts
                 WHERE tenant_id=? AND command_type='EMPLOYMENT_CONTRACT_ISSUE'
                """, String.class, ROLLBACK_TENANT)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                SELECT artifact_request_id FROM ppl_employment_contracts
                 WHERE tenant_id=?
                """, UUID.class, ROLLBACK_TENANT))
                .isEqualTo(artifacts.artifactRequestIds().get(0));

        assertThat(failingStore.appended()).hasSize(2);
        DomainEventEnvelope event = failingStore.appended().get(1);
        Hrm011EventOutbox.requireEquality(event);
        assertThat(event.type()).isEqualTo(Hrm011EventOutbox.XCON_026);
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.aggregateSequence()).isEqualTo(1L);
        PersistedOutbox persisted = persistedOutbox(event.id());
        assertThat(persisted.eventId()).isEqualTo(event.id());
        assertThat(persisted.tenantId()).isEqualTo(event.tenantId());
        assertThat(persisted.aggregateType()).isEqualTo(event.aggregateType());
        assertThat(persisted.aggregateId()).isEqualTo(event.aggregateId());
        assertThat(persisted.eventType()).isEqualTo(event.type());
        assertThat(persisted.correlationId()).isEqualTo(event.correlationId());
        assertThat(persisted.occurredAt()).isEqualTo(event.time());
        EmploymentContractCanonicalizer canonicalizer =
                new EmploymentContractCanonicalizer(mapper);
        assertThat(canonicalizer.digest(persisted.payload()))
                .isEqualTo(canonicalizer.digest(event.data()));
    }

    @Test
    void completedReceiptReplaysSameHashWithOriginalReceiptAndRejectsDifferentHash() {
        Graph graph = seedGraph(REPLAY_TENANT, "replay");
        CapturingArtifactPort artifacts = new CapturingArtifactPort("replay");
        Fixture fixture = fixture("replay", graph, jdbcStore(), artifacts);

        EmploymentContractService.CommandResult original =
                fixture.issue(fixture.request(), fixture.correlationId());
        UUID replayCorrelation = id("replay:second-correlation");
        EmploymentContractService.CommandResult replayed =
                fixture.issue(fixture.request(), replayCorrelation);

        assertThat(original.receipt().status()).isEqualTo("SUCCEEDED");
        assertThat(replayed.receipt().status()).isEqualTo("SUCCEEDED");
        assertThat(replayed.receipt().receiptId()).isEqualTo(original.receipt().receiptId());
        assertThat(replayed.receipt().correlationId()).isEqualTo(fixture.correlationId());
        assertThat(artifacts.artifactRequestIds()).hasSize(1);
        assertDurableCounts(REPLAY_TENANT, 1);

        EmploymentContractDtos.IssueRequest changed =
                new EmploymentContractDtos.IssueRequest(
                        fixture.request().relationshipPublicId(),
                        fixture.request().templatePublicId(),
                        fixture.request().templateVersion(),
                        fixture.request().locale(),
                        fixture.request().effectiveFrom(),
                        fixture.request().effectiveFrom().plusYears(1),
                        fixture.request().snapshotDigest());

        assertThatThrownBy(() -> fixture.issue(
                changed, id("replay:different-hash-correlation")))
                .isInstanceOfSatisfying(EmploymentContractException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(artifacts.artifactRequestIds()).hasSize(1);
        assertDurableCounts(REPLAY_TENANT, 1);
    }

    @Test
    void concurrentSameKeyReservationsSerializeAndOnlyWinnerInvokesProvider()
            throws Exception {
        Graph graph = seedGraph(CONCURRENT_TENANT, "concurrent");
        BlockingReservationStore blocking =
                new BlockingReservationStore(jdbcStore());
        CapturingArtifactPort artifacts = new CapturingArtifactPort("concurrent");
        Fixture fixture = fixture("concurrent", graph, blocking, artifacts);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<EmploymentContractService.CommandResult> duplicate = null;
        try {
            Future<EmploymentContractService.CommandResult> winner = executor.submit(
                    () -> fixture.issue(fixture.request(), fixture.correlationId()));
            assertThat(blocking.reservationInserted().await(5, TimeUnit.SECONDS)).isTrue();
            duplicate = executor.submit(
                    () -> fixture.issue(fixture.request(), fixture.correlationId()));
            assertThat(blocking.secondReservationAttempted()
                    .await(5, TimeUnit.SECONDS)).isTrue();

            Future<EmploymentContractService.CommandResult> waiting = duplicate;
            assertThatThrownBy(() -> waiting.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            blocking.allowCommit().countDown();

            EmploymentContractService.CommandResult won =
                    winner.get(10, TimeUnit.SECONDS);
            EmploymentContractService.CommandResult replayed =
                    duplicate.get(10, TimeUnit.SECONDS);
            assertThat(won.receipt().status()).isEqualTo("SUCCEEDED");
            assertThat(replayed.receipt().status()).isEqualTo("SUCCEEDED");
            assertThat(replayed.receipt().receiptId()).isEqualTo(won.receipt().receiptId());
        } finally {
            blocking.allowCommit().countDown();
            if (duplicate != null && !duplicate.isDone()) duplicate.cancel(true);
            executor.shutdownNow();
        }

        assertThat(artifacts.artifactRequestIds()).hasSize(1);
        assertDurableCounts(CONCURRENT_TENANT, 1);
    }

    @Test
    void persistentOutboxAcceptsExactDuplicateRejectsConflictAndChecksArgumentDigest() {
        insertTenant(OUTBOX_TENANT, "outbox");
        JdbcEmploymentContractStore store = jdbcStore();
        EmploymentContractCanonicalizer canonicalizer =
                new EmploymentContractCanonicalizer(mapper);
        Hrm011EventOutbox events = new Hrm011EventOutbox(mapper, canonicalizer);
        DomainEventEnvelope original = events.employmentContract(
                contractIssued("outbox:original", OUTBOX_TENANT, 1L));
        String originalDigest = original.data().path("payloadDigest").textValue();

        transactions.executeWithoutResult(ignored ->
                store.appendOutbox(OUTBOX_TENANT, original, originalDigest));
        String originalPayload = payload(original.id());
        transactions.executeWithoutResult(ignored ->
                store.appendOutbox(OUTBOX_TENANT, original, originalDigest));
        assertThat(outboxCount(original.id())).isEqualTo(1);
        assertThat(payload(original.id())).isEqualTo(originalPayload);

        String conflictDigest = "e".repeat(64);
        ObjectNode conflictingData = original.data().deepCopy();
        conflictingData.put("contractType", "AMENDED");
        conflictingData.put("payloadDigest", conflictDigest);
        Map<String, String> conflictingExtensions =
                new HashMap<>(original.extensions());
        conflictingExtensions.put("payloadDigest", conflictDigest);
        DomainEventEnvelope conflicting = copyWith(
                original, conflictingData, conflictingExtensions);

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored ->
                store.appendOutbox(
                        OUTBOX_TENANT, conflicting, conflictDigest)))
                .isInstanceOfSatisfying(EmploymentContractException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("EMPLOYMENT_CONTRACT_OUTBOX_CONFLICT"));
        assertThat(outboxCount(original.id())).isEqualTo(1);
        assertThat(payload(original.id())).isEqualTo(originalPayload);

        DomainEventEnvelope notInserted = events.employmentContract(
                contractIssued("outbox:digest-mismatch", OUTBOX_TENANT, 1L));
        String wrongArgumentDigest = "f".repeat(64);
        assertThat(notInserted.data().path("payloadDigest").textValue())
                .isNotEqualTo(wrongArgumentDigest);
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored ->
                store.appendOutbox(
                        OUTBOX_TENANT, notInserted, wrongArgumentDigest)))
                .isInstanceOfSatisfying(EmploymentContractException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("EMPLOYMENT_CONTRACT_OUTBOX_DIGEST_MISMATCH"));
        assertThat(outboxCount(notInserted.id())).isZero();
    }

    private Fixture fixture(
            String namespace,
            Graph graph,
            EmploymentContractStore store,
            CapturingArtifactPort artifacts) {
        EmploymentContractCanonicalizer canonicalizer =
                new EmploymentContractCanonicalizer(mapper);
        EmploymentContractTemplateSnapshotV1 template = template(
                namespace, graph.tenantId());
        EmploymentContractStore.Aggregate aggregate = aggregate(graph);
        String snapshotDigest = canonicalizer.generationDigest(
                new EmploymentContractDtos.GenerationSnapshot(
                        aggregate.relationship(), aggregate.assignment(),
                        aggregate.compensationBasis(), template, "ko-KR"));
        EmploymentContractDtos.IssueRequest request =
                new EmploymentContractDtos.IssueRequest(
                        graph.relationshipPublicId(), template.templatePublicId(),
                        template.templateVersion(), template.locale(), ISSUE_FROM,
                        null, snapshotDigest);
        UUID actorPublicId = id(namespace + ":actor");
        PeopleRequestContext.Actor actor = new PeopleRequestContext.Actor(
                USER_ID, graph.tenantId(), actorPublicId, Set.of(),
                Set.of(EmploymentContractAuthorization.PERMISSION));
        EmploymentContractAuthorization.Decision decision =
                new EmploymentContractAuthorization.Decision(
                        actor, AUTHORIZATION_REVISION, "scope-" + namespace, null);
        EmploymentContractAuthorization authorization =
                mock(EmploymentContractAuthorization.class);
        when(authorization.authorize(null)).thenReturn(decision);
        when(authorization.requireMutationPopulation(
                any(EmploymentContractAuthorization.Decision.class),
                anyLong(), any(UUID.class))).thenReturn(decision);
        EmploymentContractTemplateQueryPort templates =
                mock(EmploymentContractTemplateQueryPort.class);
        when(templates.requirePublished(
                any(EmploymentContractTemplateQueryPort.TemplateQuery.class)))
                .thenReturn(template);
        HcmHighRiskCommandGuard highRisk = mock(HcmHighRiskCommandGuard.class);
        Hrm011EventOutbox events = new Hrm011EventOutbox(mapper, canonicalizer);
        EmploymentContractService target = new EmploymentContractService(
                new EmploymentContractRolloutGate(Set.of(graph.tenantId())),
                authorization, store, templates, artifacts, canonicalizer,
                highRisk, events, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        EmploymentContractService service = transactionalProxy(
                target, transactionManager);
        return new Fixture(
                graph.tenantId(), actorPublicId,
                "employment-contract-" + namespace + "-001",
                id(namespace + ":correlation"), request, service);
    }

    private EmploymentContractService transactionalProxy(
            EmploymentContractService target,
            PlatformTransactionManager manager) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(
                new AnnotationTransactionAttributeSource(false));
        factory.addAdvice(interceptor);
        EmploymentContractService proxy =
                (EmploymentContractService) factory.getProxy();
        assertThat(AopUtils.isCglibProxy(proxy)).isTrue();
        return proxy;
    }

    private JdbcEmploymentContractStore jdbcStore() {
        return new JdbcEmploymentContractStore(
                new NamedParameterJdbcTemplate(dataSource), mapper);
    }

    private Graph seedGraph(long tenantId, String namespace) {
        insertTenant(tenantId, namespace);
        UUID personPublicId = id(namespace + ":person");
        long personId = jdbc.queryForObject("""
                INSERT INTO ppl_persons(
                    public_id,tenant_id,person_key,display_name,version)
                VALUES(?,?,?, ?,1) RETURNING person_id
                """, Long.class, personPublicId, tenantId,
                "HRM011-" + namespace + "-person", "Person " + namespace);
        UUID workerPublicId = id(namespace + ":worker");
        long workerId = jdbc.queryForObject("""
                INSERT INTO ppl_workers(
                    public_id,tenant_id,person_id,worker_number,worker_type,
                    worker_status,original_hire_date,version)
                VALUES(?,?,?,?,'EMPLOYEE','ACTIVE',DATE '2020-01-01',1)
                RETURNING worker_id
                """, Long.class, workerPublicId, tenantId, personId,
                "HRM011-" + namespace + "-worker");
        UUID employerPublicId = id(namespace + ":employer");
        long employerId = jdbc.queryForObject("""
                INSERT INTO ppl_legal_employers(
                    public_id,tenant_id,employer_key,legal_name,version)
                VALUES(?,?,?,?,1) RETURNING legal_employer_id
                """, Long.class, employerPublicId, tenantId,
                "HRM011-" + namespace + "-employer",
                "Employer " + namespace);
        UUID relationshipPublicId = id(namespace + ":relationship");
        long relationshipId = jdbc.queryForObject("""
                INSERT INTO ppl_work_relationships(
                    public_id,tenant_id,relationship_key,worker_id,
                    legal_employer_id,relationship_type,primary_relationship,
                    start_date,version)
                VALUES(?,?,?,?,?,'EMPLOYEE',TRUE,DATE '2020-01-01',3)
                RETURNING work_relationship_id
                """, Long.class, relationshipPublicId, tenantId,
                "HRM011-" + namespace + "-relationship", workerId, employerId);
        UUID assignmentPublicId = id(namespace + ":assignment");
        long assignmentId = jdbc.queryForObject("""
                INSERT INTO ppl_assignments(
                    public_id,tenant_id,assignment_key,work_relationship_id,
                    effective_start_date,assignment_status,primary_assignment,
                    business_title,version)
                VALUES(?,?,?,?,DATE '2020-01-01','ACTIVE',TRUE,
                    'HRM-011 Specialist',1)
                RETURNING assignment_id
                """, Long.class, assignmentPublicId, tenantId,
                "HRM011-" + namespace + "-assignment", relationshipId);
        UUID basisPublicId = id(namespace + ":basis");
        long basisId = jdbc.queryForObject("""
                INSERT INTO ppl_compensation_basis(
                    public_id,tenant_id,assignment_id,basis_type,amount,
                    currency_code,frequency_code,valid_from,version,
                    created_by,updated_by)
                VALUES(?,?,?,'SALARY',100000.000000,'KRW','ANNUAL',
                    DATE '2026-01-01',1,7,7)
                RETURNING compensation_basis_id
                """, Long.class, basisPublicId, tenantId, assignmentId);
        return new Graph(
                tenantId, personId, personPublicId, workerId, workerPublicId,
                employerId, employerPublicId, relationshipId,
                relationshipPublicId, assignmentId, assignmentPublicId,
                basisId, basisPublicId);
    }

    private void insertTenant(long tenantId, String namespace) {
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,
                    lifecycle_state,data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,?,?,'ACTIVE','test','POOL',7,7)
                """, id(namespace + ":tenant"), tenantId,
                "hrm011-ac11-" + namespace, "HRM-011 AC-11 " + namespace);
    }

    private EmploymentContractStore.Aggregate aggregate(Graph graph) {
        return new EmploymentContractStore.Aggregate(
                graph.relationshipId(), graph.assignmentId(), graph.basisId(),
                graph.workerId(), graph.personPublicId(),
                new EmploymentContractDtos.RelationshipSnapshot(
                        graph.relationshipPublicId(), EXPECTED_VERSION,
                        graph.workerPublicId(), graph.employerPublicId(),
                        LocalDate.parse("2020-01-01"), null),
                new EmploymentContractDtos.AssignmentSnapshot(
                        graph.assignmentPublicId(), 1,
                        LocalDate.parse("2020-01-01"), null),
                new EmploymentContractDtos.CompensationBasisSnapshot(
                        graph.basisPublicId(), 1, "SALARY", "100000",
                        "KRW", "ANNUAL", SOURCE_FROM, null));
    }

    private EmploymentContractTemplateSnapshotV1 template(
            String namespace, long tenantId) {
        return new EmploymentContractTemplateSnapshotV1(
                id(namespace + ":approval-receipt"),
                "employment-contract.v1", "STANDARD", SOURCE_FROM, null,
                "ko-KR", "c".repeat(64), NOW.minusSeconds(60), null,
                "GLOBAL", 1, "PUBLISHED",
                new EmploymentContractTemplateSnapshotV1.TemplateContentReference(
                        id(namespace + ":template-content"), 1, "d".repeat(64)),
                id(namespace + ":template"), "EMPLOYMENT_CONTRACT", 1, tenantId);
    }

    private Hrm011EventOutbox.ContractIssued contractIssued(
            String namespace, long tenantId, long version) {
        return new Hrm011EventOutbox.ContractIssued(
                tenantId, id(namespace + ":contract"), id(namespace + ":worker"),
                "STANDARD", ISSUE_FROM, null,
                new EmploymentContractDtos.DocumentReference(
                        id(namespace + ":document"), 1, "d".repeat(64)),
                version, id(namespace + ":correlation"),
                id(namespace + ":causation"), NOW);
    }

    private DomainEventEnvelope copyWith(
            DomainEventEnvelope source,
            JsonNode data,
            Map<String, String> extensions) {
        return new DomainEventEnvelope(
                source.specVersion(), source.id(), source.source(), source.type(),
                source.schemaVersion(), source.time(), source.subject(),
                source.tenantId(), source.aggregateType(), source.aggregateId(),
                source.aggregateSequence(), source.correlationId(),
                source.causationId(), source.traceParent(), data, extensions);
    }

    private void assertDurableCounts(long tenantId, int expected) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ppl_employment_contracts WHERE tenant_id=?
                """, Integer.class, tenantId)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ppl_command_receipts
                 WHERE tenant_id=? AND command_type='EMPLOYMENT_CONTRACT_ISSUE'
                """, Integer.class, tenantId)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_audit_events
                 WHERE tenant_id=? AND action='employment-contract.issued'
                """, Integer.class, tenantId)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_outbox_events
                 WHERE tenant_id=? AND event_type='EmploymentContractIssued.v1'
                """, Integer.class, tenantId)).isEqualTo(expected);
    }

    private PersistedOutbox persistedOutbox(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT event_id,tenant_id,aggregate_type,aggregate_id,event_type,
                       correlation_id,occurred_at,payload::text AS payload
                  FROM sys_people_outbox_events WHERE event_id=?
                """, (result, ignored) -> new PersistedOutbox(
                result.getObject("event_id", UUID.class),
                result.getLong("tenant_id"),
                result.getString("aggregate_type"),
                result.getString("aggregate_id"),
                result.getString("event_type"),
                result.getString("correlation_id"),
                result.getTimestamp("occurred_at").toInstant(),
                json(result.getString("payload"))), eventId);
    }

    private String payload(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT payload::text FROM sys_people_outbox_events WHERE event_id=?
                """, String.class, eventId);
    }

    private int outboxCount(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_outbox_events WHERE event_id=?
                """, Integer.class, eventId);
    }

    private JsonNode json(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new AssertionError("Persisted outbox JSON is invalid.", exception);
        }
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(
                ("HRM-011-AC-11:" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the PostgreSQL test latch.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test latch.", exception);
        }
    }

    private record Fixture(
            long tenantId,
            UUID actorPublicId,
            String idempotencyKey,
            UUID correlationId,
            EmploymentContractDtos.IssueRequest request,
            EmploymentContractService service) {

        EmploymentContractService.CommandResult issue(
                EmploymentContractDtos.IssueRequest value,
                UUID suppliedCorrelationId) {
            return asActor(() -> service.issue(
                    value, idempotencyKey,
                    '"' + Long.toString(EXPECTED_VERSION) + '"',
                    EXPECTED_VERSION, suppliedCorrelationId, null,
                    new HcmStepUpHeaders(
                            "signed-challenge", idempotencyKey,
                            AUTHORIZATION_REVISION, EXPECTED_VERSION)));
        }

        private <T> T asActor(Supplier<T> operation) {
            PeopleRequestContext.set(
                    USER_ID, tenantId, actorPublicId, Set.of(),
                    Set.of(EmploymentContractAuthorization.PERMISSION));
            try {
                return operation.get();
            } finally {
                PeopleRequestContext.clear();
            }
        }
    }

    private static final class CapturingArtifactPort
            implements EmploymentContractArtifactPort {
        private final String namespace;
        private final List<UUID> artifactRequestIds = new CopyOnWriteArrayList<>();
        private final Map<UUID, EmploymentContractDtos.ArtifactResult> results =
                new ConcurrentHashMap<>();

        private CapturingArtifactPort(String namespace) {
            this.namespace = namespace;
        }

        @Override
        public EmploymentContractDtos.ArtifactResult issue(
                EmploymentContractDtos.ArtifactRequest request) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new AssertionError(
                        "The artifact provider must be called inside the owner transaction.");
            }
            artifactRequestIds.add(request.artifactRequestId());
            return results.computeIfAbsent(
                    request.artifactRequestId(), ignored ->
                            new EmploymentContractDtos.ArtifactResult(
                                    request.artifactRequestId(),
                                    request.contractPublicId(),
                                    request.templateSnapshot().templatePublicId(),
                                    request.templateSnapshot().templateVersion(),
                                    request.generationSnapshotDigest(),
                                    id(namespace + ":provider-receipt"),
                                    new EmploymentContractDtos.DocumentReference(
                                            id(namespace + ":document"),
                                            1, "b".repeat(64)), NOW));
        }

        private List<UUID> artifactRequestIds() {
            return List.copyOf(artifactRequestIds);
        }
    }

    private static class ForwardingStore implements EmploymentContractStore {
        protected final EmploymentContractStore delegate;

        private ForwardingStore(EmploymentContractStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<Receipt> receipt(long tenantId, String idempotencyKey) {
            return delegate.receipt(tenantId, idempotencyKey);
        }

        @Override
        public Optional<Receipt> reserve(Reservation reservation) {
            return delegate.reserve(reservation);
        }

        @Override
        public List<Aggregate> lockAggregate(
                long tenantId, UUID relationshipPublicId, LocalDate effectiveOn) {
            return delegate.lockAggregate(tenantId, relationshipPublicId, effectiveOn);
        }

        @Override
        public IssuedContract insertIssued(Issue issue) {
            return delegate.insertIssued(issue);
        }

        @Override
        public Receipt complete(
                long tenantId, UUID receiptId, UUID contractPublicId,
                long contractVersion, Instant completedAt) {
            return delegate.complete(
                    tenantId, receiptId, contractPublicId,
                    contractVersion, completedAt);
        }

        @Override
        public void appendSuccessAudit(
                long tenantId, long actorUserId, UUID contractPublicId,
                UUID correlationId, Instant occurredAt) {
            delegate.appendSuccessAudit(
                    tenantId, actorUserId, contractPublicId,
                    correlationId, occurredAt);
        }

        @Override
        public void appendOutbox(
                long tenantId,
                DomainEventEnvelope envelope,
                String payloadDigest) {
            delegate.appendOutbox(tenantId, envelope, payloadDigest);
        }
    }

    private static final class PostProviderFailureStore extends ForwardingStore {
        private final AtomicBoolean failOnce = new AtomicBoolean(true);
        private final List<DomainEventEnvelope> appended =
                new CopyOnWriteArrayList<>();

        private PostProviderFailureStore(EmploymentContractStore delegate) {
            super(delegate);
        }

        @Override
        public void appendOutbox(
                long tenantId,
                DomainEventEnvelope envelope,
                String payloadDigest) {
            super.appendOutbox(tenantId, envelope, payloadDigest);
            appended.add(envelope);
            if (failOnce.compareAndSet(true, false)) {
                throw new ForcedPostProviderFailure();
            }
        }

        private List<DomainEventEnvelope> appended() {
            return List.copyOf(appended);
        }
    }

    private static final class BlockingReservationStore extends ForwardingStore {
        private final AtomicInteger reservationCalls = new AtomicInteger();
        private final CountDownLatch reservationInserted = new CountDownLatch(1);
        private final CountDownLatch secondReservationAttempted = new CountDownLatch(1);
        private final CountDownLatch allowCommit = new CountDownLatch(1);

        private BlockingReservationStore(EmploymentContractStore delegate) {
            super(delegate);
        }

        @Override
        public Optional<Receipt> reserve(Reservation reservation) {
            int invocation = reservationCalls.incrementAndGet();
            if (invocation == 2) secondReservationAttempted.countDown();
            Optional<Receipt> result = super.reserve(reservation);
            if (result.isPresent() && invocation == 1) {
                reservationInserted.countDown();
                await(allowCommit);
            }
            return result;
        }

        private CountDownLatch reservationInserted() {
            return reservationInserted;
        }

        private CountDownLatch secondReservationAttempted() {
            return secondReservationAttempted;
        }

        private CountDownLatch allowCommit() {
            return allowCommit;
        }
    }

    private static final class ForcedPostProviderFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ForcedPostProviderFailure() {
            super("forced database failure after provider success and outbox insert");
        }
    }

    private record Graph(
            long tenantId,
            long personId,
            UUID personPublicId,
            long workerId,
            UUID workerPublicId,
            long employerId,
            UUID employerPublicId,
            long relationshipId,
            UUID relationshipPublicId,
            long assignmentId,
            UUID assignmentPublicId,
            long basisId,
            UUID basisPublicId) {
    }

    private record PersistedOutbox(
            UUID eventId,
            long tenantId,
            String aggregateType,
            String aggregateId,
            String eventType,
            String correlationId,
            Instant occurredAt,
            JsonNode payload) {
    }
}
