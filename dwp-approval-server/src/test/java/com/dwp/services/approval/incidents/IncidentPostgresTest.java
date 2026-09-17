package com.dwp.services.approval.incidents;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.security.ApprovalFormManagementScopeTestSupport;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.dwp.services.approval.incidents.IncidentModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class IncidentPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-16T02:00:00Z");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-000000000019");
    private static final String ISSUER = "recovery-owner";
    private static final String IDENTITY = "recovery-executor";
    private static final String KEY_ID = "recovery-key-1";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private TransactionTemplate transactions;
    private IncidentService service;
    private IncidentRepository repository;
    private IncidentProjectionRepository projections;
    private IncidentRecoveryAttestationVerifier verifier;
    private PlatformTransactionManager transactionManager;
    private NamedParameterJdbcTemplate named;
    private ApprovalDocumentCanonical canonical;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(source)
                .locations("classpath:db/migration").cleanDisabled(false).load();
        JdbcTemplate bootstrap = new JdbcTemplate(source);
        bootstrap.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        bootstrap.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        flyway.clean();
        flyway.migrate();
        jdbc = bootstrap;
        jdbc.queryForObject("SELECT seed_approval_tenant(?)", Object.class, 42L);
        named = new NamedParameterJdbcTemplate(source);
        mapper = new ObjectMapper().findAndRegisterModules();
        canonical = new ApprovalDocumentCanonical(mapper);
        repository = new IncidentRepository(named, canonical);
        projections = new IncidentProjectionRepository(named, canonical, repository);
        KeyPair keys = keys();
        verifier = new IncidentRecoveryAttestationVerifier(
                mapper, canonical, Clock.fixed(NOW, ZoneOffset.UTC),
                new IncidentRecoveryAttestationVerifier.TrustedExecutor(
                        ISSUER, IDENTITY, KEY_ID, keys.getPublic()));
        IncidentRecoveryExecutor executor = request -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Executor must run outside database transactions.");
            }
            if (request.targetType() == TargetType.CONNECTOR) {
                throw new IllegalStateException("Fixture executor outcome is ambiguous.");
            }
            return proof(verifier, keys, request);
        };
        transactionManager = new DataSourceTransactionManager(source);
        service = new IncidentService(repository, projections,
                verifier, executor, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
        transactions = new TransactionTemplate(transactionManager);
        ApprovalRequestContext.set(17L, 42L, ACTOR,
                Set.of("APPROVAL_ADMIN"), Set.of());
        ApprovalFormManagementScopeTestSupport.set("opaque-approvals", "RS_APPROVALS");
    }

    @AfterEach
    void tearDown() {
        ApprovalRequestContext.clear();
        ApprovalFormManagementScopeTestSupport.clear();
    }

    @Test
    void executesDryRunFirstStagesAndCompletesOnlyAfterEvidenceReconciliation() {
        UUID incidentId = UUID.randomUUID();
        IncidentView opened = tx(() -> service.open("open", incident(incidentId)));
        assertThat(tx(() -> service.open("open", incident(incidentId)))).isEqualTo(opened);

        DiagnosticView diagnostic = tx(() -> service.addDiagnostic("diagnostic", incidentId,
                new DiagnosticCommand(UUID.randomUUID(), "CONNECTOR",
                        Map.of("status", 503, "authorization", "Bearer secret",
                                "message", "provider timeout"),
                        "provider-r4", NOW.minusSeconds(10), opened.version())));
        assertThat(diagnostic.redactedPayload())
                .containsEntry("authorization", "[REDACTED]");

        UUID planId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        PlanView draft = tx(() -> service.createPlan("plan", incidentId,
                new CreatePlan(planId, PlanKind.REPLAY,
                        Map.of("targetCount", 1, "sourceRevision", "outbox-r4"),
                        List.of(new StageDraft(1, ActionKind.REPLAY,
                                TargetType.OUTBOX_EVENT, targetId, 7)), 2)));
        assertThat(draft.state()).isEqualTo(PlanState.DRAFT);

        PlanView validated = tx(() -> service.recordDryRun("dry-run", incidentId, planId,
                new DryRunObservation(draft.version(), true,
                        Map.of("eligible", true, "targetVersion", 7), "a".repeat(64))));
        assertThat(validated.state()).isEqualTo(PlanState.VALIDATED);

        PlanView partial = tx(() -> service.startStage("stage-start", incidentId, planId,
                new StageStart(1, validated.version(), 1, "replay-stage-1")));
        assertThat(partial.state()).isEqualTo(PlanState.PARTIAL);
        assertThat(partial.stages().getFirst().receiptVerificationReference())
                .matches("verified:[a-f0-9]{64}");

        PlanView completed = tx(() -> service.reconcilePlan(
                "reconcile", incidentId, planId, partial.version()));
        assertThat(completed.state()).isEqualTo(PlanState.COMPLETED);
        assertThat(completed.completedAt()).isEqualTo(NOW);
        assertThat(count("apr_incident_timeline")).isGreaterThanOrEqualTo(6);
    }

    @Test
    void unknownOutcomeNeverBecomesSuccessAndPostmortemRequiresResolvedExactVersion() {
        UUID incidentId = UUID.randomUUID();
        IncidentView opened = tx(() -> service.open("open-unknown", incident(incidentId)));
        UUID planId = UUID.randomUUID();
        PlanView draft = tx(() -> service.createPlan("plan-unknown", incidentId,
                new CreatePlan(planId, PlanKind.RECONCILE, Map.of("targetCount", 1),
                        List.of(new StageDraft(1, ActionKind.RECONCILE,
                                TargetType.CONNECTOR, UUID.randomUUID(), 3)), opened.version())));
        PlanView validated = tx(() -> service.recordDryRun("dry-unknown", incidentId, planId,
                new DryRunObservation(draft.version(), true, Map.of("eligible", true),
                        "c".repeat(64))));
        PlanView unknown = tx(() -> service.startStage("start-unknown", incidentId, planId,
                new StageStart(1, validated.version(), 1, "reconcile-stage-1")));
        PlanView reconciled = tx(() -> service.reconcilePlan(
                "reconcile-unknown", incidentId, planId, unknown.version()));
        assertThat(reconciled.state()).isEqualTo(PlanState.UNKNOWN_REMOTE_OUTCOME);
        assertThat(reconciled.completedAt()).isNull();

        assertThatThrownBy(() -> tx(() -> service.recordPostmortem("premature", incidentId,
                postmortem(incidentId, 2))))
                .isInstanceOf(IncidentRejected.class);

        IncidentView investigating = tx(() -> service.changeStatus("investigate", incidentId,
                new StatusCommand(IncidentStatus.INVESTIGATING, 2,
                        "Investigation completed", "e".repeat(64))));
        IncidentView resolved = tx(() -> service.changeStatus("resolve", incidentId,
                new StatusCommand(IncidentStatus.RESOLVED, investigating.version(),
                        "Remote outcome manually reconciled", "f".repeat(64))));
        PostmortemView postmortem = tx(() -> service.recordPostmortem("postmortem", incidentId,
                postmortem(incidentId, resolved.version())));
        assertThat(postmortem.version()).isEqualTo(1);
        assertThat(count("apr_incident_postmortems")).isEqualTo(1);

        ApprovalFormManagementScopeTestSupport.set("opaque-other", "RS_OTHER");
        assertThatThrownBy(() -> tx(() -> service.incident(incidentId)))
                .isInstanceOf(IncidentRejected.class);
        ApprovalFormManagementScopeTestSupport.set("opaque-approvals", "RS_APPROVALS");
        IncidentDetail detail = tx(() -> service.incident(incidentId));
        assertThat(detail.incident().incidentId()).isEqualTo(incidentId);
        assertThat(detail.postmortem()).isNotNull();
    }

    @Test
    void missingExecutorStaysUnknownAndCannotReconcileToCompleted() {
        IncidentService noExecutor = new IncidentService(
                repository, projections, verifier, (IncidentRecoveryExecutor) null,
                transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID incidentId = UUID.randomUUID();
        IncidentView opened = tx(() -> noExecutor.open(
                "open-no-executor", incident(incidentId)));
        UUID planId = UUID.randomUUID();
        PlanView draft = tx(() -> noExecutor.createPlan(
                "plan-no-executor", incidentId,
                new CreatePlan(planId, PlanKind.REPLAY, Map.of("targetCount", 1),
                        List.of(new StageDraft(1, ActionKind.REPLAY,
                                TargetType.OUTBOX_EVENT, UUID.randomUUID(), 9)),
                        opened.version())));
        PlanView validated = tx(() -> noExecutor.recordDryRun(
                "dry-no-executor", incidentId, planId,
                new DryRunObservation(draft.version(), true,
                        Map.of("eligible", true), "9".repeat(64))));

        PlanView unknown = tx(() -> noExecutor.startStage(
                "start-no-executor", incidentId, planId,
                new StageStart(1, validated.version(), 1, "no-executor-stage-1")));
        StageView stage = unknown.stages().getFirst();
        assertThat(stage.state()).isEqualTo(StageState.UNKNOWN_REMOTE_OUTCOME);
        assertThat(stage.receiptVerificationReference()).isNull();

        PlanView reconciled = tx(() -> noExecutor.reconcilePlan(
                "reconcile-no-executor", incidentId, planId, unknown.version()));
        assertThat(reconciled.state()).isEqualTo(PlanState.UNKNOWN_REMOTE_OUTCOME);
        assertThat(reconciled.completedAt()).isNull();
    }

    @Test
    void remoteSuccessThenCompletionFailureBecomesDurableUnknownWithoutReexecution() {
        AtomicInteger executions = new AtomicInteger();
        KeyPair keys = keys();
        IncidentRepository failing = new IncidentRepository(named, canonical) {
            private boolean rejectVerifiedCompletion = true;

            @Override
            PlanView completeStage(
                    Context context,
                    UUID incidentId,
                    UUID planId,
                    VerifiedStageCompletion input,
                    Instant now) {
                if (rejectVerifiedCompletion && input.receiptVerificationReference() != null) {
                    rejectVerifiedCompletion = false;
                    throw new IllegalStateException("Injected completion persistence failure.");
                }
                return super.completeStage(context, incidentId, planId, input, now);
            }
        };
        IncidentProjectionRepository failingProjections =
                new IncidentProjectionRepository(named, canonical, failing);
        IncidentRecoveryExecutor successfulExecutor = request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            executions.incrementAndGet();
            return proof(verifier, keys, request);
        };
        IncidentService resilient = new IncidentService(
                failing, failingProjections, verifier, successfulExecutor,
                transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID incidentId = UUID.randomUUID();
        IncidentView opened = tx(() -> resilient.open(
                "open-persist-failure", incident(incidentId)));
        UUID planId = UUID.randomUUID();
        PlanView draft = tx(() -> resilient.createPlan(
                "plan-persist-failure", incidentId,
                new CreatePlan(planId, PlanKind.REPLAY, Map.of("targetCount", 1),
                        List.of(new StageDraft(1, ActionKind.REPLAY,
                                TargetType.OUTBOX_EVENT, UUID.randomUUID(), 11)),
                        opened.version())));
        PlanView validated = tx(() -> resilient.recordDryRun(
                "dry-persist-failure", incidentId, planId,
                new DryRunObservation(draft.version(), true,
                        Map.of("eligible", true), "8".repeat(64))));
        StageStart start = new StageStart(
                1, validated.version(), 1, "persist-failure-stage-1");

        PlanView unknown = tx(() -> resilient.startStage(
                "start-persist-failure", incidentId, planId, start));
        assertThat(unknown.state()).isEqualTo(PlanState.UNKNOWN_REMOTE_OUTCOME);
        assertThat(unknown.stages().getFirst().state())
                .isEqualTo(StageState.UNKNOWN_REMOTE_OUTCOME);
        assertThat(executions).hasValue(1);

        PlanView retried = tx(() -> resilient.startStage(
                "start-persist-failure", incidentId, planId, start));
        assertThat(retried).isEqualTo(unknown);
        assertThatThrownBy(() -> tx(() -> resilient.startStage(
                "start-persist-failure-new-key", incidentId, planId, start)))
                .isInstanceOf(IncidentRejected.class);
        assertThat(executions).hasValue(1);

        PlanView reconciled = tx(() -> resilient.reconcilePlan(
                "reconcile-persist-failure", incidentId, planId, unknown.version()));
        assertThat(reconciled.state()).isEqualTo(PlanState.UNKNOWN_REMOTE_OUTCOME);
        assertThat(reconciled.completedAt()).isNull();
    }

    private OpenIncident incident(UUID id) {
        return new OpenIncident(id, "INC.CONNECTOR.503", "Connector delivery failure",
                Severity.HIGH, SourceKind.CONNECTOR, "connector:erp-primary");
    }

    private PostmortemCommand postmortem(UUID ignoredIncidentId, long version) {
        return new PostmortemCommand(UUID.randomUUID(), "Provider timeout was reconciled.",
                List.of("Provider dependency unavailable"),
                List.of("Add provider circuit-breaker alarm"), "1".repeat(64), version);
    }

    private KeyPair keys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception unavailable) {
            throw new IllegalStateException("Ed25519 test key generation failed.", unavailable);
        }
    }

    private SignedExecutionReceipt proof(
            IncidentRecoveryAttestationVerifier verifier,
            KeyPair keys,
            ExecutionRequest request) {
        try {
            Map<String, Object> result =
                    Map.of("committedVersion", request.expectedTargetVersion() + 1);
            IncidentRecoveryAttestationVerifier.Claims draft =
                    new IncidentRecoveryAttestationVerifier.Claims(
                            ISSUER, IDENTITY, KEY_ID,
                            IncidentRecoveryAttestationVerifier.AUDIENCE,
                            IncidentRecoveryAttestationVerifier.PURPOSE,
                            request.tenantId(), request.resourceSetKey(), request.incidentId(),
                            request.planId(), request.stageNumber(), request.expectedPlanVersion(),
                            request.expectedStageVersion(), request.actionKind().name(),
                            request.targetType().name(), request.targetId(),
                            request.expectedTargetVersion(), request.executionKey(),
                            StageState.SUCCEEDED.name(), result, "", NOW,
                            NOW.minusSeconds(1), NOW.plusSeconds(120),
                            "recovery-pg-proof-001");
            IncidentRecoveryAttestationVerifier.Claims claims =
                    new IncidentRecoveryAttestationVerifier.Claims(
                            draft.issuer(), draft.executorIdentity(), draft.keyId(), draft.audience(),
                            draft.purpose(), draft.tenantId(), draft.resourceSetKey(),
                            draft.incidentId(), draft.planId(), draft.stageNumber(),
                            draft.expectedPlanVersion(), draft.expectedStageVersion(),
                            draft.actionKind(), draft.targetType(), draft.targetId(),
                            draft.expectedTargetVersion(), draft.executionKey(), draft.outcome(),
                            draft.result(), verifier.expectedEvidence(request, draft),
                            draft.completedAt(), draft.issuedAt(), draft.expiresAt(), draft.nonce());
            byte[] payload = mapper.writeValueAsBytes(claims);
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(keys.getPrivate());
            signature.update(payload);
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return new SignedExecutionReceipt(
                    encoder.encodeToString(payload), encoder.encodeToString(signature.sign()));
        } catch (Exception invalidFixture) {
            throw new IllegalStateException("Signed recovery fixture failed.", invalidFixture);
        }
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private <T> T tx(Supplier<T> supplier) {
        return transactions.execute(ignored -> supplier.get());
    }
}
