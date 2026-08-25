package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventOutboxRepository;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.repository.AppAdminPresetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Clean PostgreSQL proof for the atomic preset, self-service, and outbox contract. */
@Testcontainers(disabledWithoutDocker = true)
class AppAdminPresetPostgresIntegrationTest {

    private static final OffsetDateTime VALID_TO =
            OffsetDateTime.parse("2030-12-01T00:00:00Z");
    private static final OffsetDateTime REVIEW_DUE =
            OffsetDateTime.parse("2030-06-01T00:00:00Z");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactionManager;
    private static TransactionTemplate transactions;
    private static ObjectMapper objectMapper;

    private IdentityAuditService audit;
    private AppAdminPresetService service;
    private AppAdminPresetRequestService requestService;
    private Fixture fixture;

    @BeforeAll
    static void migrateCleanDatabase() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @BeforeEach
    void setUp() {
        audit = mock(IdentityAuditService.class);
        doAnswer(invocation -> {
            String attempted = objectMapper.writeValueAsString(invocation.getArgument(7));
            TransactionTemplate independent = new TransactionTemplate(transactionManager);
            independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.executeWithoutResult(ignored -> jdbc.update("""
                    INSERT INTO sys_identity_audit_events (
                        audit_event_id, tenant_id, actor_id, actor_type, action,
                        target_type, target_id, correlation_id, outcome, reason,
                        after_snapshot)
                    VALUES (?, ?, ?, 'USER', ?, ?, ?, ?, 'DENIED', ?, ?)
                    """, UUID.randomUUID(), invocation.getArgument(0),
                    invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4),
                    invocation.getArgument(5), invocation.getArgument(6), attempted));
            return null;
        }).when(audit).denied(
                anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
        AppAdminPresetRepository repository =
                new AppAdminPresetRepository(jdbc, objectMapper);
        ScopedAdminDutyAssignmentService duties =
                new ScopedAdminDutyAssignmentService(jdbc);
        AppAdminPresetOutboxPublisher events = publisher();
        requestService = new AppAdminPresetRequestService(
                jdbc, repository, duties, audit, events);
        service = new AppAdminPresetService(
                jdbc, repository, duties, audit, events, requestService,
                java.time.Clock.systemUTC());
        fixture = fixture();
    }

    @Test
    void approvesActivatesAndRevokesOneResponsibilityAndExactDutyPackageAtomically() {
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.requester(), "preset-request-1",
                        governedRequest(fixture.subject(), "APPROVAL_DESIGNER")));

        assertThat(pending.lifecycleState()).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.requestChannel()).isEqualTo("GOVERNANCE");
        assertThat(pending.duties())
                .extracting(AppGovernanceDtos.AppAdminPresetDutyAssignment::dutyCode)
                .containsExactly("APPROVAL_DESIGN_DRAFT", "APPROVAL_POLICY_DRAFT");
        assertBundleStates(pending.presetAssignmentId(), "PENDING_APPROVAL", 2);

        assertThatThrownBy(() -> inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.requester(), "self-approve",
                pending.presetAssignmentId(), decision("APPROVED", pending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));
        assertBundleStates(pending.presetAssignmentId(), "PENDING_APPROVAL", 2);

        AppGovernanceDtos.AppAdminPresetAssignment approved = inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.approver(), "preset-approve-1",
                pending.presetAssignmentId(), decision("APPROVED", pending.version())));
        assertThat(approved.lifecycleState()).isEqualTo("APPROVED");
        assertBundleStates(approved.presetAssignmentId(), "APPROVED", 2);
        assertThat(effectiveDuties(fixture.subject())).isEmpty();
        assertThat(accessRevision(fixture.subject())).isZero();
        assertThatThrownBy(() -> inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.approver(), "approver-activate-1",
                approved.presetAssignmentId(), activation(approved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.requester(), "requester-activate-1",
                approved.presetAssignmentId(), activation(approved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertOutbox(approved.presetAssignmentId(),
                List.of(AppAdminPresetOutboxPublisher.REQUESTED,
                        AppAdminPresetOutboxPublisher.DECIDED), List.of(1L, 2L));

        AppGovernanceDtos.AppAdminPresetAssignment active = inTransaction(() ->
                service.activate(
                        fixture.tenantId(), fixture.fulfiller(), "preset-activate-1",
                        approved.presetAssignmentId(), activation(approved.version())));
        assertThat(active.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(active.activatedBy()).isEqualTo(fixture.fulfiller());
        assertBundleStates(active.presetAssignmentId(), "ACTIVE", 2);
        assertThat(effectiveDuties(fixture.subject()))
                .containsExactlyInAnyOrder(
                        "APPROVAL_DESIGN_DRAFT", "APPROVAL_POLICY_DRAFT");
        assertThat(accessRevision(fixture.subject())).isEqualTo(1);
        assertOutbox(active.presetAssignmentId(),
                List.of(AppAdminPresetOutboxPublisher.REQUESTED,
                        AppAdminPresetOutboxPublisher.DECIDED,
                        AppAdminPresetOutboxPublisher.ACTIVATED), List.of(1L, 2L, 3L));

        AppGovernanceDtos.AppAdminPresetAssignment revoked = inTransaction(() -> service.revoke(
                fixture.tenantId(), fixture.fulfiller(), "preset-revoke-1",
                active.presetAssignmentId(), new AppGovernanceDtos.RevokeAppAdminPresetRequest(
                        "The design responsibility has ended.", active.version())));
        assertThat(revoked.lifecycleState()).isEqualTo("REVOKED");
        assertBundleStates(revoked.presetAssignmentId(), "REVOKED", 2);
        assertThat(effectiveDuties(fixture.subject())).isEmpty();
        assertThat(accessRevision(fixture.subject())).isEqualTo(2);
        assertOutbox(revoked.presetAssignmentId(),
                List.of(AppAdminPresetOutboxPublisher.REQUESTED,
                        AppAdminPresetOutboxPublisher.DECIDED,
                        AppAdminPresetOutboxPublisher.ACTIVATED,
                        AppAdminPresetOutboxPublisher.REVOKED), List.of(1L, 2L, 3L, 4L));
        verify(audit).success(anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.eq("access.app-admin-preset.approved"),
                anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void selfServiceForcesActorPendingAndReplaysOnlyAnIdenticalRequest() {
        List<AppGovernanceDtos.AppAdminPresetSelfServiceOption> options =
                requestService.selfServiceOptions(
                        fixture.tenantId(), fixture.member(), "APP.APPROVALS");
        assertThat(options).hasSize(4);
        assertThat(options)
                .allMatch(option -> option.preset().requestable()
                        && option.preset().appResourceKey().equals("APP.APPROVALS")
                        && option.resourceSets().stream().anyMatch(set ->
                                set.resourceSetId().equals(fixture.resourceSetId())));
        assertThat(requestService.selfServiceOptions(
                fixture.tenantId(), fixture.member(), "APP.HCM")).isEmpty();

        AppGovernanceDtos.CreateSelfServicePresetRequest request =
                new AppGovernanceDtos.CreateSelfServicePresetRequest(
                        "APPROVAL_OPERATOR", fixture.resourceSetId(), VALID_TO,
                        REVIEW_DUE, "Request time-bound approval operations responsibility.");
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestSelfService(
                        fixture.tenantId(), fixture.member(), "self-request-1",
                        "self-service-key-0001", request));
        AppGovernanceDtos.AppAdminPresetAssignment replay = inTransaction(() ->
                requestService.requestSelfService(
                        fixture.tenantId(), fixture.member(), "self-replay-1",
                        "self-service-key-0001", request));

        assertThat(replay.presetAssignmentId()).isEqualTo(pending.presetAssignmentId());
        assertThat(pending.principalType()).isEqualTo("USER");
        assertThat(pending.principalRef()).isEqualTo(fixture.member().toString());
        assertThat(pending.requestChannel()).isEqualTo("SELF_SERVICE");
        assertThat(pending.lifecycleState()).isEqualTo("PENDING_APPROVAL");
        assertThat(aggregateCount(fixture.member(), "APPROVAL_OPERATOR")).isEqualTo(1);
        assertOutbox(pending.presetAssignmentId(),
                List.of(AppAdminPresetOutboxPublisher.REQUESTED), List.of(1L));
        assertThat(service.assignment(
                fixture.tenantId(), fixture.member(), pending.presetAssignmentId()))
                .isEqualTo(pending);
        assertThatThrownBy(() -> service.assignments(fixture.tenantId(), fixture.member()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> inTransaction(() -> requestService.requestSelfService(
                fixture.tenantId(), fixture.member(), "self-duplicate-1",
                "self-service-key-0002", request)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(aggregateCount(fixture.member(), "APPROVAL_OPERATOR")).isEqualTo(1);

        AppGovernanceDtos.CreateSelfServicePresetRequest changed =
                new AppGovernanceDtos.CreateSelfServicePresetRequest(
                        "APPROVAL_OPERATOR", fixture.resourceSetId(), VALID_TO,
                        REVIEW_DUE, "A materially different justification for this key.");
        assertThatThrownBy(() -> inTransaction(() -> requestService.requestSelfService(
                fixture.tenantId(), fixture.member(), "self-conflict-1",
                "self-service-key-0001", changed)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.member(), "self-decision-1",
                pending.presetAssignmentId(), decision("APPROVED", pending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertBundleStates(pending.presetAssignmentId(), "PENDING_APPROVAL", 2);

        AppGovernanceDtos.AppAdminPresetAssignment approved = inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.approver(), "self-approved-1",
                pending.presetAssignmentId(), decision("APPROVED", pending.version())));
        assertThat(approved.lifecycleState()).isEqualTo("APPROVED");
        assertThat(effectiveDuties(fixture.member())).isEmpty();
        assertThatThrownBy(() -> inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.approver(), "self-approver-activate-1",
                approved.presetAssignmentId(), activation(approved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        AppGovernanceDtos.AppAdminPresetAssignment active = inTransaction(() ->
                service.activate(
                        fixture.tenantId(), fixture.fulfiller(), "self-activated-1",
                        approved.presetAssignmentId(), activation(approved.version())));
        assertThat(active.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(effectiveDuties(fixture.member()))
                .containsExactlyInAnyOrder(
                        "APPROVAL_OPERATIONS_EXECUTE", "APPROVAL_SIGNATURE_READ");
    }

    @Test
    void catalogAdminCanReadAndRequestButCannotDecideRevokeOrResolveReviews() {
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.catalogAdmin(), "catalog-request-1",
                        governedRequest(fixture.subject(), "APPROVAL_PUBLISHER")));

        assertThat(service.dashboard(fixture.tenantId(), fixture.catalogAdmin()).catalog())
                .extracting(AppGovernanceDtos.AppAdminPreset::presetCode)
                .contains("APPROVAL_DESIGNER", "APPROVAL_PUBLISHER");
        assertThat(new AppGovernanceService(jdbc, audit)
                .dashboard(fixture.tenantId(), fixture.catalogAdmin()).resourceSets())
                .extracting(AppGovernanceDtos.ResourceSet::resourceSetId)
                .contains(fixture.resourceSetId());
        assertThat(service.assignments(fixture.tenantId(), fixture.catalogAdmin()))
                .extracting(AppGovernanceDtos.AppAdminPresetAssignment::presetAssignmentId)
                .contains(pending.presetAssignmentId());
        assertThatThrownBy(() -> inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.catalogAdmin(), "catalog-decision-1",
                pending.presetAssignmentId(), decision("APPROVED", pending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertBundleStates(pending.presetAssignmentId(), "PENDING_APPROVAL", 2);
        assertThatThrownBy(() -> inTransaction(() -> service.decideReview(
                fixture.tenantId(), fixture.catalogAdmin(), "catalog-review-1",
                UUID.randomUUID(), new AppGovernanceDtos.AppAdminPresetReviewDecisionRequest(
                        "DISMISSED", "The legacy review has documented compensating evidence.", 0L))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> inTransaction(() -> {
            jdbc.update("""
                    UPDATE sys_admin_app_preset_catalog SET version = version + 1
                     WHERE preset_code = 'APPROVAL_PUBLISHER'
                    """);
            return service.decide(
                    fixture.tenantId(), fixture.approver(), "stale-catalog-approve-1",
                    pending.presetAssignmentId(), decision("APPROVED", pending.version()));
        })).isInstanceOfSatisfying(BaseException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        assertBundleStates(pending.presetAssignmentId(), "PENDING_APPROVAL", 2);

        AppGovernanceDtos.AppAdminPresetAssignment approved = inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.approver(), "catalog-approved-1",
                pending.presetAssignmentId(), decision("APPROVED", pending.version())));
        assertThatThrownBy(() -> inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.catalogAdmin(), "catalog-activate-1",
                approved.presetAssignmentId(), activation(approved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> inTransaction(() -> service.revoke(
                fixture.tenantId(), fixture.catalogAdmin(), "catalog-revoke-1",
                approved.presetAssignmentId(), new AppGovernanceDtos.RevokeAppAdminPresetRequest(
                        "Catalog administrators cannot fulfil or revoke this package.",
                        approved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        AppGovernanceDtos.AppAdminPresetAssignment active = inTransaction(() ->
                service.activate(
                        fixture.tenantId(), fixture.fulfiller(), "catalog-activated-1",
                        approved.presetAssignmentId(), activation(approved.version())));
        assertBundleStates(active.presetAssignmentId(), "ACTIVE", 2);
    }

    @Test
    void broadTenantRolesCannotOpenOrMutateAppGovernanceAndGenericWritesStayControlPlaneOnly() {
        Long admin = user(fixture.tenantId(), "broad-admin");
        Long platformAdmin = user(fixture.tenantId(), "broad-platform-admin");
        grantRole(fixture.tenantId(), admin, "ADMIN");
        grantRole(fixture.tenantId(), platformAdmin, "PLATFORM_ADMIN");
        List<Long> broadActors = List.of(fixture.tenantAdmin(), admin, platformAdmin);
        AppGovernanceService generic = new AppGovernanceService(jdbc, audit);

        for (Long broadActor : broadActors) {
            assertThatThrownBy(() -> service.dashboard(fixture.tenantId(), broadActor))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThatThrownBy(() -> generic.dashboard(fixture.tenantId(), broadActor))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThatThrownBy(() -> inTransaction(() -> requestService.requestGoverned(
                    fixture.tenantId(), broadActor, "broad-request-" + broadActor,
                    governedRequest(fixture.subject(), "APPROVAL_DESIGNER"))))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThatThrownBy(() -> inTransaction(() -> generic.createResourceSet(
                    fixture.tenantId(), broadActor, "broad-resource-" + broadActor,
                    new AppGovernanceDtos.CreateResourceSetRequest(
                            "RS_BROAD_" + broadActor, "Forbidden broad set", null,
                            List.of("APP.APPROVALS")))))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }

        assertThatThrownBy(() -> inTransaction(() -> generic.requestAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "generic-specialist-create",
                genericRequest(fixture.subject(), "APP_CONFIG_ADMIN"))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN))
                .hasMessageContaining("preset workflow");
        UUID legacySpecialist = pendingResponsibility(
                fixture.subject(), "APP_CONFIG_ADMIN", fixture.catalogAdmin());
        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), fixture.approver(), "generic-specialist-decision",
                legacySpecialist, genericDecision("APPROVED", 0L))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN))
                .hasMessageContaining("preset workflow");

        Long controlSubject = user(fixture.tenantId(), "control-reviewer");
        AppGovernanceDtos.Assignment pending = inTransaction(() -> generic.requestAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "generic-control-request",
                genericRequest(controlSubject, "APP_ACCESS_REVIEWER")));
        for (Long forbiddenActor : List.of(
                fixture.catalogAdmin(), fixture.tenantAdmin(), admin,
                platformAdmin, fixture.fulfiller())) {
            assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                    fixture.tenantId(), forbiddenActor,
                    "generic-control-decision-" + forbiddenActor,
                    pending.assignmentId(), genericDecision("APPROVED", pending.version()))))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
        AppGovernanceDtos.Assignment active = inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), fixture.approver(), "generic-control-approved",
                pending.assignmentId(), genericDecision("APPROVED", pending.version())));
        assertThat(active.responsibilityCode()).isEqualTo("APP_ACCESS_REVIEWER");
        assertThat(effectiveDuties(controlSubject)).isEmpty();
        assertThat(generic.resourceRoles(fixture.tenantId(), controlSubject))
                .extracting(AppGovernanceDtos.ResourceRole::responsibilityCode)
                .containsOnly("APP_ACCESS_REVIEWER")
                .doesNotContain("APP_CONFIG_ADMIN");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_app_preset_assignments
                 WHERE tenant_id = ? AND principal_type = 'USER' AND principal_ref = ?
                """, Integer.class, fixture.tenantId(), controlSubject.toString())).isZero();
        for (Long forbiddenActor : List.of(
                fixture.catalogAdmin(), fixture.tenantAdmin(), admin,
                platformAdmin, fixture.approver())) {
            assertThatThrownBy(() -> inTransaction(() -> generic.revokeAssignment(
                    fixture.tenantId(), forbiddenActor,
                    "generic-control-revoke-" + forbiddenActor,
                    active.assignmentId(), genericRevoke(active.version()))))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
        AppGovernanceDtos.Assignment revoked = inTransaction(() -> generic.revokeAssignment(
                fixture.tenantId(), fixture.fulfiller(), "generic-control-revoked",
                active.assignmentId(), genericRevoke(active.version())));
        assertThat(revoked.lifecycleState()).isEqualTo("REVOKED");
    }

    @Test
    void specialistResponsibilityDoesNotExposeTheCompanyGovernanceHub() {
        Long specialist = user(fixture.tenantId(), "product-config-specialist");
        grantResponsibility(
                fixture.tenantId(), specialist, "APP_CONFIG_ADMIN",
                fixture.resourceSetId(), fixture.catalogAdmin());

        assertThatThrownBy(() -> service.dashboard(fixture.tenantId(), specialist))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> new AppGovernanceService(jdbc, audit)
                .dashboard(fixture.tenantId(), specialist))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void sodDenialAuditAndOutboxCommitIndependentlyWithCorrelationEvidence() {
        String correlationId = "preset-sod-denied-audit-1";

        assertThatThrownBy(() -> inTransaction(() -> requestService.requestGoverned(
                fixture.tenantId(), fixture.requester(), correlationId,
                governedRequest(fixture.requester(), "APPROVAL_DESIGNER"))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));

        DenialEvent denial = jdbc.queryForObject("""
                SELECT audit_event_id, action, outcome, correlation_id, reason
                  FROM sys_identity_audit_events
                 WHERE tenant_id = ? AND correlation_id = ?
                """, (result, ignored) -> new DenialEvent(
                result.getObject("audit_event_id", UUID.class),
                result.getString("action"), result.getString("outcome"),
                result.getString("correlation_id"), result.getString("reason"), null),
                fixture.tenantId(), correlationId);
        assertThat(denial)
                .extracting(DenialEvent::action, DenialEvent::outcome,
                        DenialEvent::correlationId)
                .containsExactly(
                        "access.app-admin-preset.request-denied",
                        "DENIED", correlationId);
        assertThat(denial.reason()).contains("Self-fulfilment");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_audit_outbox
                 WHERE event_id = ? AND payload ->> 'correlationId' = ?
                """, Integer.class, denial.auditEventId(), correlationId)).isEqualTo(1);
        verify(audit).denied(
                eq(fixture.tenantId()), eq(fixture.requester()),
                eq("access.app-admin-preset.request-denied"),
                eq("APP_ADMIN_PRESET_ASSIGNMENT"),
                eq(fixture.resourceSetId().toString()),
                eq(correlationId),
                eq("Self-fulfilment of an app administrator preset is forbidden."),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void authorizationDenialPersistsMachineReasonCorrelationAndOperation() throws Exception {
        String correlationId = "preset-authority-denied-audit-1";

        assertThatThrownBy(() -> inTransaction(() -> requestService.requestGoverned(
                fixture.tenantId(), fixture.member(), correlationId,
                governedRequest(fixture.subject(), "APPROVAL_DESIGNER"))))
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(exception.getMessage())
                            .isEqualTo("CATALOG_ADMIN_OR_APP_OWNER_REQUIRED");
                });

        DenialEvent denial = jdbc.queryForObject("""
                SELECT audit_event_id, action, outcome, correlation_id, reason,
                       after_snapshot::text AS after_snapshot
                  FROM sys_identity_audit_events
                 WHERE tenant_id = ? AND correlation_id = ?
                """, (result, ignored) -> new DenialEvent(
                result.getObject("audit_event_id", UUID.class),
                result.getString("action"), result.getString("outcome"),
                result.getString("correlation_id"), result.getString("reason"),
                result.getString("after_snapshot")),
                fixture.tenantId(), correlationId);
        assertThat(denial)
                .extracting(DenialEvent::action, DenialEvent::outcome,
                        DenialEvent::correlationId, DenialEvent::reason)
                .containsExactly(
                        "access.app-admin-preset.request-denied",
                        "DENIED", correlationId,
                        "CATALOG_ADMIN_OR_APP_OWNER_REQUIRED");
        assertThat(objectMapper.readTree(denial.afterSnapshot()).path("errorCode").asText())
                .isEqualTo("FORBIDDEN");
        assertThat(objectMapper.readTree(denial.afterSnapshot()).path("operation").asText())
                .isEqualTo("access.app-admin-preset.request-denied");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_audit_outbox
                 WHERE event_id = ? AND payload ->> 'correlationId' = ?
                """, Integer.class, denial.auditEventId(), correlationId)).isEqualTo(1);
    }

    @Test
    void catalogAuthorityAloneDesignatesAndRevokesAppOwners() {
        AppGovernanceService generic = new AppGovernanceService(jdbc, audit);
        Long secondCatalogAdmin = user(fixture.tenantId(), "second-catalog-admin");
        grantRole(fixture.tenantId(), secondCatalogAdmin, "APP_CATALOG_ADMIN");
        Long ownerCandidate = user(fixture.tenantId(), "owner-candidate");

        AppGovernanceDtos.Assignment pending = inTransaction(() -> generic.requestAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "owner-catalog-request",
                genericRequest(ownerCandidate, "APP_OWNER")));
        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), fixture.approver(), "owner-approver-forbidden",
                pending.assignmentId(), genericDecision("APPROVED", pending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "owner-requester-self-approval",
                pending.assignmentId(), genericDecision("APPROVED", pending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), secondCatalogAdmin, "owner-stale-version",
                pending.assignmentId(), genericDecision("APPROVED", pending.version() + 1))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        AppGovernanceDtos.Assignment active = inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), secondCatalogAdmin, "owner-catalog-approved",
                pending.assignmentId(), genericDecision("APPROVED", pending.version())));
        assertThat(active.lifecycleState()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> inTransaction(() -> generic.revokeAssignment(
                fixture.tenantId(), fixture.fulfiller(), "owner-manager-forbidden",
                active.assignmentId(), genericRevoke(active.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        AppGovernanceDtos.Assignment revoked = inTransaction(() -> generic.revokeAssignment(
                fixture.tenantId(), fixture.catalogAdmin(), "owner-catalog-revoked",
                active.assignmentId(), genericRevoke(active.version())));
        assertThat(revoked.lifecycleState()).isEqualTo("REVOKED");
    }

    @Test
    void firstExactScopeApproverBootstrapIsOwnerAnchoredOneTimeAndIndependent() {
        AppGovernanceService generic = new AppGovernanceService(jdbc, audit);
        Long secondCatalogAdmin = user(fixture.tenantId(), "bootstrap-catalog-admin");
        grantRole(fixture.tenantId(), secondCatalogAdmin, "APP_CATALOG_ADMIN");

        UUID bootstrapSet = additionalApprovalsResourceSet(fixture.tenantId());
        Long owner = user(fixture.tenantId(), "bootstrap-owner");
        grantResponsibility(
                fixture.tenantId(), owner, "APP_OWNER", bootstrapSet,
                fixture.catalogAdmin());
        Long firstApprover = user(fixture.tenantId(), "bootstrap-first-approver");
        AppGovernanceDtos.Assignment firstPending = inTransaction(() ->
                generic.requestAssignment(
                        fixture.tenantId(), owner, "first-approver-request",
                        genericRequest(
                                firstApprover, "APP_ACCESS_APPROVER", bootstrapSet)));
        AppGovernanceDtos.Assignment firstActive = inTransaction(() ->
                generic.decideAssignment(
                        fixture.tenantId(), fixture.catalogAdmin(),
                        "first-approver-catalog-decision", firstPending.assignmentId(),
                        genericDecision("APPROVED", firstPending.version())));
        assertThat(firstActive.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(firstActive.approvedBy()).isEqualTo(fixture.catalogAdmin());

        Long secondApprover = user(fixture.tenantId(), "bootstrap-second-approver");
        AppGovernanceDtos.Assignment secondPending = inTransaction(() ->
                generic.requestAssignment(
                        fixture.tenantId(), owner, "second-approver-request",
                        genericRequest(
                                secondApprover, "APP_ACCESS_APPROVER", bootstrapSet)));
        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), secondCatalogAdmin,
                "second-approver-catalog-forbidden", secondPending.assignmentId(),
                genericDecision("APPROVED", secondPending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        AppGovernanceDtos.Assignment secondActive = inTransaction(() ->
                generic.decideAssignment(
                        fixture.tenantId(), firstApprover,
                        "second-approver-scoped-decision", secondPending.assignmentId(),
                        genericDecision("APPROVED", secondPending.version())));
        assertThat(secondActive.lifecycleState()).isEqualTo("ACTIVE");

        UUID ownerlessSet = additionalApprovalsResourceSet(fixture.tenantId());
        Long inactiveOwner = user(fixture.tenantId(), "inactive-bootstrap-owner");
        grantResponsibility(
                fixture.tenantId(), inactiveOwner, "APP_OWNER", ownerlessSet,
                fixture.catalogAdmin());
        jdbc.update("""
                UPDATE com_users SET status = 'SUSPENDED'
                 WHERE tenant_id = ? AND user_id = ?
                """, fixture.tenantId(), inactiveOwner);
        Long ownerlessCandidate = user(fixture.tenantId(), "ownerless-approver");
        AppGovernanceDtos.Assignment ownerlessPending = inTransaction(() ->
                generic.requestAssignment(
                        fixture.tenantId(), fixture.catalogAdmin(),
                        "ownerless-approver-request",
                        genericRequest(
                                ownerlessCandidate, "APP_ACCESS_APPROVER", ownerlessSet)));
        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), secondCatalogAdmin,
                "ownerless-approver-catalog-forbidden", ownerlessPending.assignmentId(),
                genericDecision("APPROVED", ownerlessPending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        UUID selfApprovalSet = additionalApprovalsResourceSet(fixture.tenantId());
        Long selfApprovalOwner = user(fixture.tenantId(), "self-approval-owner");
        grantResponsibility(
                fixture.tenantId(), selfApprovalOwner, "APP_OWNER", selfApprovalSet,
                fixture.catalogAdmin());
        Long selfApprovalTarget = user(fixture.tenantId(), "self-approval-target");
        AppGovernanceDtos.Assignment selfPending = inTransaction(() ->
                generic.requestAssignment(
                        fixture.tenantId(), secondCatalogAdmin,
                        "first-approver-self-request",
                        genericRequest(
                                selfApprovalTarget, "APP_ACCESS_APPROVER", selfApprovalSet)));
        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), secondCatalogAdmin,
                "first-approver-self-decision", selfPending.assignmentId(),
                genericDecision("APPROVED", selfPending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void concurrentFirstApproverDecisionsActivateExactlyOneAssignment() throws Exception {
        AppGovernanceService generic = new AppGovernanceService(jdbc, audit);
        Long secondCatalogAdmin = user(fixture.tenantId(), "concurrent-catalog-admin");
        grantRole(fixture.tenantId(), secondCatalogAdmin, "APP_CATALOG_ADMIN");
        UUID resourceSetId = additionalApprovalsResourceSet(fixture.tenantId());
        Long owner = user(fixture.tenantId(), "concurrent-bootstrap-owner");
        grantResponsibility(
                fixture.tenantId(), owner, "APP_OWNER", resourceSetId,
                fixture.catalogAdmin());
        Long firstSubject = user(fixture.tenantId(), "concurrent-first-approver");
        Long secondSubject = user(fixture.tenantId(), "concurrent-second-approver");
        AppGovernanceDtos.Assignment firstPending = inTransaction(() ->
                generic.requestAssignment(
                        fixture.tenantId(), owner, "concurrent-first-request",
                        genericRequest(
                                firstSubject, "APP_ACCESS_APPROVER", resourceSetId)));
        AppGovernanceDtos.Assignment secondPending = inTransaction(() ->
                generic.requestAssignment(
                        fixture.tenantId(), owner, "concurrent-second-request",
                        genericRequest(
                                secondSubject, "APP_ACCESS_APPROVER", resourceSetId)));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection boundaryBlocker = dataSource.getConnection()) {
            boundaryBlocker.setAutoCommit(false);
            lockResourceSetBoundary(boundaryBlocker, fixture.tenantId(), resourceSetId);

            Future<DecisionAttempt> first = executor.submit(() -> decideConcurrently(
                    generic, fixture.catalogAdmin(), firstPending, ready, start));
            Future<DecisionAttempt> second = executor.submit(() -> decideConcurrently(
                    generic, secondCatalogAdmin, secondPending, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(awaitBlockedFirstApproverDecisions()).isEqualTo(2);
            boundaryBlocker.commit();

            List<DecisionAttempt> results = List.of(
                    first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().filter(DecisionAttempt::active)).hasSize(1);
            assertThat(results.stream().filter(
                    result -> result.errorCode() == ErrorCode.FORBIDDEN)).hasSize(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_role_assignments
                 WHERE tenant_id = ? AND resource_set_id = ?
                   AND responsibility_code = 'APP_ACCESS_APPROVER'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class, fixture.tenantId(), resourceSetId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_role_assignments
                 WHERE tenant_id = ? AND resource_set_id = ?
                   AND responsibility_code = 'APP_ACCESS_APPROVER'
                   AND lifecycle_state = 'PENDING_APPROVAL'
                """, Integer.class, fixture.tenantId(), resourceSetId)).isEqualTo(1);
    }

    @Test
    void scopedApproverCannotApproveAControlResponsibilityForTheirOwnGroup() {
        AppGovernanceService generic = new AppGovernanceService(jdbc, audit);
        Long groupId = jdbc.queryForObject("""
                INSERT INTO com_groups (
                    tenant_id, group_key, display_name, source_type, status)
                VALUES (?, ?, 'Approver target group', 'LOCAL', 'ACTIVE')
                RETURNING group_id
                """, Long.class, fixture.tenantId(),
                "SELF_APPROVAL_GROUP_" + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO com_group_members (
                    tenant_id, group_id, user_id, source_type)
                VALUES (?, ?, ?, 'LOCAL')
                """, fixture.tenantId(), groupId, fixture.approver());
        AppGovernanceDtos.Assignment pending = inTransaction(() ->
                generic.requestAssignment(
                        fixture.tenantId(), fixture.catalogAdmin(),
                        "group-control-request",
                        new AppGovernanceDtos.CreateAssignmentRequest(
                                "GROUP", groupId.toString(), "APP_ACCESS_REVIEWER",
                                fixture.resourceSetId(), VALID_TO,
                                "Create a governed reviewer responsibility for the group.")));

        assertThatThrownBy(() -> inTransaction(() -> generic.decideAssignment(
                fixture.tenantId(), fixture.approver(), "group-self-approval",
                pending.assignmentId(), genericDecision("APPROVED", pending.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM com_admin_role_assignments
                 WHERE tenant_id = ? AND admin_role_assignment_id = ?
                """, String.class, fixture.tenantId(), pending.assignmentId()))
                .isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void dashboardsProjectOnlyExactResponsibilityAndReviewerResourceSets() {
        Long scopedViewer = user(fixture.tenantId(), "scoped-viewer");
        grantResponsibility(
                fixture.tenantId(), scopedViewer, "APP_OWNER",
                fixture.resourceSetId(), fixture.catalogAdmin());
        grantResponsibility(
                fixture.tenantId(), scopedViewer, "APP_ACCESS_REVIEWER",
                fixture.resourceSetId(), fixture.catalogAdmin());
        UUID hcmSet = productResourceSet(
                fixture.tenantId(), "APP.HCM", "RS_HCM_SECONDARY", "HCM secondary");
        Long assignedSubject = user(fixture.tenantId(), "scoped-assignment-subject");
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), scopedViewer, "scoped-dashboard-request",
                        governedRequest(assignedSubject, "APPROVAL_AUDITOR")));
        UUID visibleReview = review(
                assignedSubject, "APPROVAL_OPERATIONS_AUDIT",
                "VISIBLE_EXACT_SCOPE", fixture.resourceSetId());
        review(assignedSubject, "APPROVAL_OPERATIONS_AUDIT",
                "HIDDEN_OTHER_SCOPE", hcmSet);

        AppAdminPresetService.DashboardProjection projection =
                service.dashboard(fixture.tenantId(), scopedViewer);
        assertThat(projection.catalog())
                .hasSize(4)
                .allMatch(value -> "APP.APPROVALS".equals(value.appResourceKey()));
        assertThat(projection.assignments())
                .extracting(AppGovernanceDtos.AppAdminPresetAssignment::presetAssignmentId)
                .containsExactly(pending.presetAssignmentId());
        assertThat(projection.reviews())
                .extracting(AppGovernanceDtos.AppAdminPresetReview::reviewId)
                .containsExactly(visibleReview);
        AppGovernanceDtos.AppAdminPresetReview review = projection.reviews().getFirst();
        assertThat(review.resourceSetId()).isEqualTo(fixture.resourceSetId());
        assertThat(review.resourceSetName()).isEqualTo("Approvals");

        AppGovernanceDtos.Dashboard base =
                new AppGovernanceService(jdbc, audit)
                        .dashboard(fixture.tenantId(), scopedViewer);
        assertThat(base.resourceSets())
                .extracting(AppGovernanceDtos.ResourceSet::resourceSetId)
                .containsExactly(fixture.resourceSetId());
        assertThat(base.assignments())
                .allMatch(value -> value.resourceSetId().equals(fixture.resourceSetId()));
        assertThat(base.responsibilities())
                .extracting(AppGovernanceDtos.Responsibility::code)
                .containsExactlyInAnyOrder(
                        "APP_OWNER", "APP_ACCESS_APPROVER",
                        "APP_ACCESS_MANAGER", "APP_ACCESS_REVIEWER")
                .doesNotContain("APP_CONFIG_ADMIN");

        AppGovernanceDtos.Dashboard reviewerDashboard =
                new AppGovernanceService(jdbc, audit)
                        .dashboard(fixture.tenantId(), fixture.reviewer());
        assertThat(reviewerDashboard.responsibilities()).isEmpty();
        assertThat(reviewerDashboard.principals()).isEmpty();
    }

    @Test
    void denialAndOutboxFailureNeverLeavePartialResponsibilityOrDutyState() {
        Long deniedSubject = user(fixture.tenantId(), "denied-subject");
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.requester(), "deny-request-1",
                        governedRequest(deniedSubject, "APPROVAL_AUDITOR")));
        AppGovernanceDtos.AppAdminPresetAssignment denied = inTransaction(() -> {
            jdbc.update("""
                    UPDATE sys_admin_app_preset_catalog SET version = version + 1
                     WHERE preset_code = 'APPROVAL_AUDITOR'
                    """);
            AppGovernanceDtos.AppAdminPresetAssignment result = service.decide(
                    fixture.tenantId(), fixture.approver(), "deny-decision-1",
                    pending.presetAssignmentId(), decision("DENIED", pending.version()));
            jdbc.update("""
                    UPDATE sys_admin_app_preset_catalog SET version = version - 1
                     WHERE preset_code = 'APPROVAL_AUDITOR'
                    """);
            return result;
        });
        assertThat(denied.lifecycleState()).isEqualTo("DENIED");
        assertBundleStates(denied.presetAssignmentId(), "DENIED", 1);
        assertThat(effectiveDuties(deniedSubject)).isEmpty();

        Long rollbackSubject = user(fixture.tenantId(), "rollback-subject");
        AppAdminPresetOutboxPublisher failingEvents = mock(AppAdminPresetOutboxPublisher.class);
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(failingEvents).assignment(anyString(), anyLong(), any(),
                        org.mockito.ArgumentMatchers.anyLong(), anyString());
        AppAdminPresetRepository repository = new AppAdminPresetRepository(jdbc, objectMapper);
        AppAdminPresetRequestService failing = new AppAdminPresetRequestService(
                jdbc, repository, new ScopedAdminDutyAssignmentService(jdbc),
                audit, failingEvents);
        assertThatThrownBy(() -> inTransaction(() -> failing.requestGoverned(
                fixture.tenantId(), fixture.requester(), "rollback-request-1",
                governedRequest(rollbackSubject, "APPROVAL_PUBLISHER"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox unavailable");
        assertThat(aggregateCount(rollbackSubject, "APPROVAL_PUBLISHER")).isZero();
        assertThat(openResponsibilityCount(rollbackSubject)).isZero();
        assertThat(openDutyCount(rollbackSubject)).isZero();
    }

    @Test
    void approvedCancellationIsAtomicAndNeverInvalidatesAnIneffectivePrincipal() {
        Long subject = user(fixture.tenantId(), "approved-cancel-subject");
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.requester(), "cancel-request-1",
                        governedRequest(subject, "APPROVAL_OPERATOR")));
        AppGovernanceDtos.AppAdminPresetAssignment approved = inTransaction(() ->
                service.decide(
                        fixture.tenantId(), fixture.approver(), "cancel-approved-1",
                        pending.presetAssignmentId(),
                        decision("APPROVED", pending.version())));

        assertThat(accessRevision(subject)).isZero();
        assertThatThrownBy(() -> inTransaction(() -> service.revoke(
                fixture.tenantId(), fixture.approver(), "cancel-approver-revoke",
                approved.presetAssignmentId(),
                new AppGovernanceDtos.RevokeAppAdminPresetRequest(
                        "The approver cannot cancel through the manager API.",
                        approved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        AppGovernanceDtos.AppAdminPresetAssignment cancelled = inTransaction(() ->
                service.revoke(
                        fixture.tenantId(), fixture.fulfiller(), "cancel-manager-revoke",
                        approved.presetAssignmentId(),
                        new AppGovernanceDtos.RevokeAppAdminPresetRequest(
                                "Cancel the approved package before any access is activated.",
                                approved.version())));

        assertThat(cancelled.lifecycleState()).isEqualTo("REVOKED");
        assertBundleStates(cancelled.presetAssignmentId(), "REVOKED", 2);
        assertThat(effectiveDuties(subject)).isEmpty();
        assertThat(accessRevision(subject)).isZero();
        assertOutbox(cancelled.presetAssignmentId(),
                List.of(AppAdminPresetOutboxPublisher.REQUESTED,
                        AppAdminPresetOutboxPublisher.DECIDED,
                        AppAdminPresetOutboxPublisher.REVOKED), List.of(1L, 2L, 3L));
    }

    @Test
    void activatorMustBeIndependentFromRequesterAndTargetEvenWithExactManagerScope() {
        Long ownerManager = user(fixture.tenantId(), "owner-manager");
        grantResponsibility(
                fixture.tenantId(), ownerManager, "APP_OWNER",
                fixture.resourceSetId(), fixture.catalogAdmin());
        grantResponsibility(
                fixture.tenantId(), ownerManager, "APP_ACCESS_MANAGER",
                fixture.resourceSetId(), fixture.catalogAdmin());
        Long firstSubject = user(fixture.tenantId(), "requester-independence-subject");
        AppGovernanceDtos.AppAdminPresetAssignment requesterPending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), ownerManager, "independent-requester-request",
                        governedRequest(firstSubject, "APPROVAL_DESIGNER")));
        AppGovernanceDtos.AppAdminPresetAssignment requesterApproved = inTransaction(() ->
                service.decide(
                        fixture.tenantId(), fixture.approver(),
                        "independent-requester-approved",
                        requesterPending.presetAssignmentId(),
                        decision("APPROVED", requesterPending.version())));
        assertThatThrownBy(() -> inTransaction(() -> service.activate(
                fixture.tenantId(), ownerManager, "requester-self-fulfil",
                requesterApproved.presetAssignmentId(),
                activation(requesterApproved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));

        AppGovernanceDtos.AppAdminPresetAssignment targetPending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.requester(), "target-request",
                        governedRequest(fixture.fulfiller(), "APPROVAL_OPERATOR")));
        AppGovernanceDtos.AppAdminPresetAssignment targetApproved = inTransaction(() ->
                service.decide(
                        fixture.tenantId(), fixture.approver(), "target-approved",
                        targetPending.presetAssignmentId(),
                        decision("APPROVED", targetPending.version())));
        assertThatThrownBy(() -> inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.fulfiller(), "target-self-fulfil",
                targetApproved.presetAssignmentId(), activation(targetApproved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));

        Long approverSubject = user(fixture.tenantId(), "approver-independence-subject");
        AppGovernanceDtos.AppAdminPresetAssignment approverPending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.requester(), "approver-request",
                        governedRequest(approverSubject, "APPROVAL_AUDITOR")));
        AppGovernanceDtos.AppAdminPresetAssignment approverApproved = inTransaction(() ->
                service.decide(
                        fixture.tenantId(), fixture.approver(), "approver-approved",
                        approverPending.presetAssignmentId(),
                        decision("APPROVED", approverPending.version())));
        jdbc.update("""
                UPDATE com_admin_role_assignments
                   SET responsibility_code = 'APP_ACCESS_MANAGER'
                 WHERE tenant_id = ? AND principal_type = 'USER' AND principal_ref = ?
                   AND responsibility_code = 'APP_ACCESS_APPROVER'
                """, fixture.tenantId(), fixture.approver().toString());
        assertThatThrownBy(() -> inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.approver(), "approver-self-fulfil",
                approverApproved.presetAssignmentId(),
                activation(approverApproved.version()))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));
    }

    @Test
    void expiryClosesTheEntireBundleInvalidatesSessionsAndPublishesOneRevision() {
        Long expiringSubject = user(fixture.tenantId(), "expiring-subject");
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.requester(), "expiry-request-1",
                        governedRequest(expiringSubject, "APPROVAL_AUDITOR")));
        AppGovernanceDtos.AppAdminPresetAssignment approved = inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.approver(), "expiry-approve-1",
                pending.presetAssignmentId(), decision("APPROVED", pending.version())));
        AppGovernanceDtos.AppAdminPresetAssignment active = inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.fulfiller(), "expiry-activate-1",
                approved.presetAssignmentId(), activation(approved.version())));

        inTransaction(() -> {
            jdbc.update("""
                    UPDATE com_admin_role_assignments
                       SET valid_from = CURRENT_TIMESTAMP - INTERVAL '20 days',
                           review_due_at = CURRENT_TIMESTAMP - INTERVAL '2 days',
                           valid_to = CURRENT_TIMESTAMP - INTERVAL '1 day'
                     WHERE admin_role_assignment_id = ?
                    """, active.responsibilityAssignmentId());
            jdbc.update("""
                    UPDATE com_admin_scoped_duty_assignments
                       SET valid_from = CURRENT_TIMESTAMP - INTERVAL '20 days',
                           review_due_at = CURRENT_TIMESTAMP - INTERVAL '2 days',
                           valid_to = CURRENT_TIMESTAMP - INTERVAL '1 day'
                     WHERE app_preset_assignment_id = ?
                    """, active.presetAssignmentId());
            jdbc.update("""
                    UPDATE com_admin_app_preset_assignments
                       SET created_at = CURRENT_TIMESTAMP - INTERVAL '30 days',
                           valid_from = CURRENT_TIMESTAMP - INTERVAL '20 days',
                           review_due_at = CURRENT_TIMESTAMP - INTERVAL '2 days',
                           valid_to = CURRENT_TIMESTAMP - INTERVAL '1 day'
                     WHERE app_preset_assignment_id = ?
                    """, active.presetAssignmentId());
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            return null;
        });

        assertThat(inTransaction(() -> service.expireDueAssignments(10))).isEqualTo(1);
        assertBundleStates(active.presetAssignmentId(), "EXPIRED", 1);
        assertThat(effectiveDuties(expiringSubject)).isEmpty();
        assertThat(accessRevision(expiringSubject)).isEqualTo(2);
        assertOutbox(active.presetAssignmentId(),
                List.of(AppAdminPresetOutboxPublisher.REQUESTED,
                        AppAdminPresetOutboxPublisher.DECIDED,
                        AppAdminPresetOutboxPublisher.ACTIVATED,
                        AppAdminPresetOutboxPublisher.EXPIRED), List.of(1L, 2L, 3L, 4L));
    }

    @Test
    void reviewsRequireIndependentGovernorAndExactGovernedDutyScope() {
        AppGovernanceDtos.AppAdminPresetAssignment pending = inTransaction(() ->
                requestService.requestGoverned(
                        fixture.tenantId(), fixture.requester(), "review-request-1",
                        governedRequest(fixture.subject(), "APPROVAL_AUDITOR")));
        AppGovernanceDtos.AppAdminPresetAssignment approved = inTransaction(() -> service.decide(
                fixture.tenantId(), fixture.approver(), "review-approve-1",
                pending.presetAssignmentId(), decision("APPROVED", pending.version())));
        inTransaction(() -> service.activate(
                fixture.tenantId(), fixture.fulfiller(), "review-activate-1",
                approved.presetAssignmentId(), activation(approved.version())));

        UUID otherResourceSet = additionalApprovalsResourceSet(fixture.tenantId());
        grantResponsibility(
                fixture.tenantId(), fixture.reviewer(), "APP_ACCESS_REVIEWER",
                otherResourceSet, fixture.catalogAdmin());
        UUID mismatched = review(
                fixture.subject(), "APPROVAL_OPERATIONS_AUDIT",
                "MISMATCHED_PRESET_SCOPE", otherResourceSet);
        assertThatThrownBy(() -> inTransaction(() -> service.decideReview(
                fixture.tenantId(), fixture.reviewer(), "review-mismatch-1", mismatched,
                new AppGovernanceDtos.AppAdminPresetReviewDecisionRequest(
                        "RESOLVED", "A different resource set cannot resolve this review.", 0L))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));
        AppGovernanceDtos.AppAdminPresetReview dismissed = inTransaction(() ->
                service.decideReview(
                        fixture.tenantId(), fixture.reviewer(), "review-dismiss-1", mismatched,
                        new AppGovernanceDtos.AppAdminPresetReviewDecisionRequest(
                                "DISMISSED",
                                "The mismatched migration evidence was independently dismissed.",
                                0L)));
        assertThat(dismissed.lifecycleState()).isEqualTo("DISMISSED");

        UUID exact = review(
                fixture.subject(), "APPROVAL_OPERATIONS_AUDIT",
                "PRESET_WORKFLOW_REVIEW_REQUIRED", fixture.resourceSetId());
        assertThatThrownBy(() -> inTransaction(() -> service.decideReview(
                fixture.tenantId(), fixture.subject(), "review-self-1", exact,
                new AppGovernanceDtos.AppAdminPresetReviewDecisionRequest(
                        "RESOLVED", "A reviewed user cannot resolve their own evidence.", 0L))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));
        AppGovernanceDtos.AppAdminPresetReview resolved = inTransaction(() ->
                service.decideReview(
                        fixture.tenantId(), fixture.reviewer(), "review-resolve-1", exact,
                        new AppGovernanceDtos.AppAdminPresetReviewDecisionRequest(
                                "RESOLVED",
                                "The exact governed preset now supplies reviewed duty evidence.",
                                0L)));
        assertThat(resolved.lifecycleState()).isEqualTo("RESOLVED");
        assertThat(resolved.version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_domain_event_outbox
                 WHERE tenant_id = ? AND aggregate_type = 'APP_ADMIN_PRESET_REVIEW'
                   AND aggregate_id = ? AND event_type = ?
                """, Integer.class, fixture.tenantId(), exact.toString(),
                AppAdminPresetOutboxPublisher.REVIEW_DECIDED)).isEqualTo(1);
    }

    @Test
    void catalogIsDataDrivenSeparatedAndNonApprovalProductsFailClosedAsDraft() {
        AppAdminPresetRepository repository = new AppAdminPresetRepository(jdbc, objectMapper);
        List<AppGovernanceDtos.AppAdminPreset> catalog = repository.catalog();
        assertThat(catalog).hasSize(14);
        assertThat(catalog.stream().filter(AppGovernanceDtos.AppAdminPreset::requestable))
                .extracting(AppGovernanceDtos.AppAdminPreset::presetCode)
                .containsExactlyInAnyOrder(
                        "APPROVAL_DESIGNER", "APPROVAL_PUBLISHER",
                        "APPROVAL_OPERATOR", "APPROVAL_AUDITOR");
        assertThat(catalog.stream().filter(value -> !value.requestable()).toList())
                .hasSize(10)
                .allMatch(value -> "PRESET_DRAFT".equals(value.unavailableReason())
                        && value.appResourceKey() != null && value.duties().isEmpty());
        assertThat(repository.requirePreset("SERVICES_PRESET_CATALOG_PENDING")
                .appResourceKey()).isEqualTo("APP.SERVICES");
        assertThat(catalog).noneMatch(value -> value.productKey().equals("rooms"));
        assertThat(catalog)
                .noneMatch(value -> value.presetCode().contains("ALL_ADMIN"));
        assertThat(catalog.stream().filter(AppGovernanceDtos.AppAdminPreset::requestable))
                .allMatch(value -> value.duties().size() <= 2);

        transactions.execute(status -> {
            jdbc.update("""
                    UPDATE sys_admin_scoped_duty_catalog
                       SET product_resource_key = 'APP.HCM'
                     WHERE duty_code = 'APPROVAL_SIGNATURE_READ'
                    """);
            AppGovernanceDtos.AppAdminPreset mismatched =
                    repository.requirePreset("APPROVAL_OPERATOR");
            assertThat(mismatched.requestable()).isFalse();
            assertThat(mismatched.unavailableReason()).isEqualTo("PRODUCT_RESOURCE_MISMATCH");
            status.setRollbackOnly();
            return null;
        });
    }

    private AppAdminPresetOutboxPublisher publisher() {
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        DomainEventOutboxRepository outbox = new DomainEventOutboxRepository(
                new NamedParameterJdbcTemplate(dataSource), objectMapper);
        return new AppAdminPresetOutboxPublisher(
                new DomainEventRecorder(outbox, contracts, objectMapper),
                contracts, objectMapper);
    }

    private Fixture fixture() {
        int number = SEQUENCE.incrementAndGet();
        Long tenantId = jdbc.queryForObject("""
                INSERT INTO com_tenants (code, name, status)
                VALUES (?, ?, 'ACTIVE') RETURNING tenant_id
                """, Long.class, "preset-test-" + number, "Preset test " + number);
        Long requester = user(tenantId, "requester");
        Long approver = user(tenantId, "approver");
        Long fulfiller = user(tenantId, "fulfiller");
        Long reviewer = user(tenantId, "reviewer");
        Long catalogAdmin = user(tenantId, "catalog-admin");
        Long tenantAdmin = user(tenantId, "tenant-admin");
        Long member = user(tenantId, "member");
        Long subject = user(tenantId, "subject");
        grantRole(tenantId, catalogAdmin, "APP_CATALOG_ADMIN");
        grantTenantAdmin(tenantId, tenantAdmin);
        UUID resourceSet = approvalsResourceSet(tenantId);
        grantResponsibility(
                tenantId, requester, "APP_OWNER", resourceSet, catalogAdmin);
        grantResponsibility(
                tenantId, requester, "APP_ACCESS_APPROVER", resourceSet, catalogAdmin);
        grantResponsibility(
                tenantId, approver, "APP_ACCESS_APPROVER", resourceSet, catalogAdmin);
        grantResponsibility(
                tenantId, fulfiller, "APP_ACCESS_MANAGER", resourceSet, catalogAdmin);
        grantResponsibility(
                tenantId, reviewer, "APP_ACCESS_REVIEWER", resourceSet, catalogAdmin);
        return new Fixture(
                tenantId, requester, approver, fulfiller, reviewer, catalogAdmin,
                tenantAdmin, member, subject, resourceSet);
    }

    private Long user(Long tenantId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO com_users (tenant_id, display_name, email, status)
                VALUES (?, ?, ?, 'ACTIVE') RETURNING user_id
                """, Long.class, tenantId, name,
                name + '-' + tenantId + "@preset.test");
    }

    private void grantTenantAdmin(Long tenantId, Long userId) {
        grantRole(tenantId, userId, "TENANT_ADMIN");
    }

    private void grantRole(Long tenantId, Long userId, String roleCode) {
        Long roleId = jdbc.queryForObject("""
                INSERT INTO com_roles (
                    tenant_id, code, name, description, status, role_type,
                    privileged, assignable_to_groups, builtin_role_code)
                SELECT ?, role_code, display_name, description, 'ACTIVE', 'SYSTEM',
                       privileged, assignable_to_groups, role_code
                  FROM sys_builtin_role_catalog WHERE role_code = ?
                ON CONFLICT (tenant_id, code) DO UPDATE SET status = 'ACTIVE'
                RETURNING role_id
                """, Long.class, tenantId, roleCode);
        jdbc.update("""
                INSERT INTO com_role_members (tenant_id, role_id, user_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, tenantId, roleId, userId);
    }

    private UUID approvalsResourceSet(Long tenantId) {
        jdbc.update("""
                INSERT INTO com_resources (tenant_id, type, key, name, enabled)
                VALUES (?, 'APP', 'APP.APPROVALS', 'Approvals', TRUE)
                """, tenantId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_resource_sets (
                    resource_set_id, tenant_id, resource_set_key, name,
                    resource_type, lifecycle_state)
                VALUES (?, ?, 'RS_APPROVALS', 'Approvals', 'APP', 'ACTIVE')
                """, id, tenantId);
        jdbc.update("""
                INSERT INTO com_admin_resource_set_members (
                    resource_set_member_id, tenant_id, resource_set_id,
                    resource_type, resource_key, lifecycle_state)
                VALUES (?, ?, ?, 'APP', 'APP.APPROVALS', 'ACTIVE')
                """, UUID.randomUUID(), tenantId, id);
        return id;
    }

    private UUID additionalApprovalsResourceSet(Long tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_resource_sets (
                    resource_set_id, tenant_id, resource_set_key, name,
                    resource_type, lifecycle_state)
                VALUES (?, ?, ?, 'Approvals secondary', 'APP', 'ACTIVE')
                """, id, tenantId,
                "RS_APPROVALS_" + id.toString().substring(0, 8).toUpperCase());
        jdbc.update("""
                INSERT INTO com_admin_resource_set_members (
                    resource_set_member_id, tenant_id, resource_set_id,
                    resource_type, resource_key, lifecycle_state)
                VALUES (?, ?, ?, 'APP', 'APP.APPROVALS', 'ACTIVE')
                """, UUID.randomUUID(), tenantId, id);
        return id;
    }

    private UUID productResourceSet(
            Long tenantId, String resourceKey, String setKey, String name) {
        jdbc.update("""
                INSERT INTO com_resources (tenant_id, type, key, name, enabled)
                VALUES (?, 'APP', ?, ?, TRUE)
                ON CONFLICT (tenant_id, type, key) DO NOTHING
                """, tenantId, resourceKey, name);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_resource_sets (
                    resource_set_id, tenant_id, resource_set_key, name,
                    resource_type, lifecycle_state)
                VALUES (?, ?, ?, ?, 'APP', 'ACTIVE')
                """, id, tenantId, setKey, name);
        jdbc.update("""
                INSERT INTO com_admin_resource_set_members (
                    resource_set_member_id, tenant_id, resource_set_id,
                    resource_type, resource_key, lifecycle_state)
                VALUES (?, ?, ?, 'APP', ?, 'ACTIVE')
                """, UUID.randomUUID(), tenantId, id, resourceKey);
        return id;
    }

    private void grantResponsibility(
            Long tenantId,
            Long userId,
            String responsibility,
            UUID resourceSetId,
            Long approvedBy) {
        jdbc.update("""
                INSERT INTO com_admin_role_assignments (
                    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
                    responsibility_code, resource_set_id, assignment_source,
                    lifecycle_state, valid_from, valid_to, review_due_at,
                    justification, approved_by, approved_at,
                    decision_reason, created_by, updated_by)
                VALUES (?, ?, 'USER', ?, ?, ?, 'PROVISIONING', 'ACTIVE',
                        CURRENT_TIMESTAMP, ?, ?, ?, ?, CURRENT_TIMESTAMP,
                        'Controlled test bootstrap responsibility.', ?, ?)
                """, UUID.randomUUID(), tenantId, userId.toString(), responsibility,
                resourceSetId, VALID_TO, REVIEW_DUE,
                "Controlled test bootstrap for exact scoped governance responsibility.",
                approvedBy, approvedBy, approvedBy);
    }

    private AppGovernanceDtos.CreateAppAdminPresetAssignmentRequest governedRequest(
            Long subjectId, String presetCode) {
        return new AppGovernanceDtos.CreateAppAdminPresetAssignmentRequest(
                "USER", subjectId.toString(), presetCode, fixture.resourceSetId(),
                VALID_TO, REVIEW_DUE,
                "Assign the minimum product-specific duties for a time-bound need.");
    }

    private AppGovernanceDtos.AppAdminPresetDecisionRequest decision(
            String decision, long version) {
        return new AppGovernanceDtos.AppAdminPresetDecisionRequest(
                decision, "Independent governed preset decision with reviewed evidence.", version);
    }

    private AppGovernanceDtos.CreateAssignmentRequest genericRequest(
            Long subjectId, String responsibilityCode) {
        return genericRequest(subjectId, responsibilityCode, fixture.resourceSetId());
    }

    private AppGovernanceDtos.CreateAssignmentRequest genericRequest(
            Long subjectId, String responsibilityCode, UUID resourceSetId) {
        return new AppGovernanceDtos.CreateAssignmentRequest(
                "USER", subjectId.toString(), responsibilityCode,
                resourceSetId, VALID_TO,
                "Create an independently governed control-plane responsibility.");
    }

    private AppGovernanceDtos.AssignmentDecisionRequest genericDecision(
            String decision, long version) {
        return new AppGovernanceDtos.AssignmentDecisionRequest(
                decision, "Independent exact-scope control-plane decision evidence.", version);
    }

    private AppGovernanceDtos.RevokeAssignmentRequest genericRevoke(long version) {
        return new AppGovernanceDtos.RevokeAssignmentRequest(
                "Exact-scope access manager revoked the legacy responsibility.", version);
    }

    private UUID pendingResponsibility(Long subjectId, String responsibility, Long requester) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_role_assignments (
                    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
                    responsibility_code, resource_set_id, assignment_source,
                    lifecycle_state, valid_to, review_due_at, justification,
                    created_by, updated_by)
                VALUES (?, ?, 'USER', ?, ?, ?, 'MANUAL', 'PENDING_APPROVAL',
                        ?, ?, ?, ?, ?)
                """, id, fixture.tenantId(), subjectId.toString(), responsibility,
                fixture.resourceSetId(), VALID_TO, REVIEW_DUE,
                "Legacy specialist row retained only to prove decision bypass closure.",
                requester, requester);
        return id;
    }

    private AppGovernanceDtos.ActivateAppAdminPresetRequest activation(long version) {
        return new AppGovernanceDtos.ActivateAppAdminPresetRequest(
                "Independent access manager activated the exact approved package.", version);
    }

    private void assertBundleStates(UUID aggregateId, String state, int dutyCount) {
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM com_admin_app_preset_assignments
                 WHERE app_preset_assignment_id = ?
                """, String.class, aggregateId)).isEqualTo(state);
        assertThat(jdbc.queryForObject("""
                SELECT responsibility.lifecycle_state
                  FROM com_admin_app_preset_assignments aggregate
                  JOIN com_admin_role_assignments responsibility
                    ON responsibility.admin_role_assignment_id =
                       aggregate.responsibility_assignment_id
                 WHERE aggregate.app_preset_assignment_id = ?
                """, String.class, aggregateId)).isEqualTo(state);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_scoped_duty_assignments
                 WHERE app_preset_assignment_id = ? AND lifecycle_state = ?
                """, Integer.class, aggregateId, state)).isEqualTo(dutyCount);
    }

    private List<String> effectiveDuties(Long userId) {
        return jdbc.query("""
                SELECT DISTINCT duty_code FROM auth_effective_scoped_duties
                 WHERE tenant_id = ? AND user_id = ? ORDER BY duty_code
                """, (result, ignored) -> result.getString(1), fixture.tenantId(), userId);
    }

    private long accessRevision(Long userId) {
        Long value = jdbc.queryForObject("""
                SELECT access_revision FROM com_users
                 WHERE tenant_id = ? AND user_id = ?
                """, Long.class, fixture.tenantId(), userId);
        return value == null ? -1 : value;
    }

    private void assertOutbox(UUID aggregateId, List<String> types, List<Long> sequences) {
        assertThat(jdbc.query("""
                SELECT event_type, aggregate_sequence
                  FROM sys_domain_event_outbox
                 WHERE tenant_id = ? AND aggregate_type = 'APP_ADMIN_PRESET_ASSIGNMENT'
                   AND aggregate_id = ? ORDER BY aggregate_sequence
                """, (result, ignored) -> new Event(
                        result.getString("event_type"),
                        result.getLong("aggregate_sequence")),
                fixture.tenantId(), aggregateId.toString()))
                .extracting(Event::type, Event::sequence)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, types.size())
                        .mapToObj(index -> org.assertj.core.groups.Tuple.tuple(
                                types.get(index), sequences.get(index))).toList());
    }

    private int aggregateCount(Long subjectId, String presetCode) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_app_preset_assignments
                 WHERE tenant_id = ? AND principal_type = 'USER'
                   AND principal_ref = ? AND preset_code = ?
                """, Integer.class, fixture.tenantId(), subjectId.toString(), presetCode);
    }

    private int openResponsibilityCount(Long subjectId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_role_assignments
                 WHERE tenant_id = ? AND principal_type = 'USER' AND principal_ref = ?
                   AND lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
                """, Integer.class, fixture.tenantId(), subjectId.toString());
    }

    private int openDutyCount(Long subjectId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_scoped_duty_assignments
                 WHERE tenant_id = ? AND principal_type = 'USER' AND principal_ref = ?
                   AND lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
                """, Integer.class, fixture.tenantId(), subjectId.toString());
    }

    private UUID review(
            Long userId, String dutyCode, String reasonCode, UUID resourceSetId) {
        return jdbc.queryForObject("""
                INSERT INTO com_admin_scoped_duty_reviews (
                    tenant_id, user_id, source_role_code, duty_code,
                    reason_code, evidence)
                VALUES (?, ?, 'AUDITOR', ?, ?,
                        jsonb_build_object('resourceSetId', CAST(? AS text)))
                RETURNING scoped_duty_review_id
                """, UUID.class, fixture.tenantId(), userId, dutyCode,
                reasonCode, resourceSetId);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transactions.execute(ignored -> work.get());
    }

    private DecisionAttempt decideConcurrently(
            AppGovernanceService generic,
            Long actorId,
            AppGovernanceDtos.Assignment pending,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent decision gate did not open.");
            }
            AppGovernanceDtos.Assignment active = inTransaction(() -> {
                jdbc.queryForObject(
                        "SELECT set_config('application_name', ?, true)", String.class,
                        "core006-first-approver-" + actorId);
                return generic.decideAssignment(
                            fixture.tenantId(), actorId,
                            "concurrent-first-approver-" + actorId,
                            pending.assignmentId(),
                            genericDecision("APPROVED", pending.version()));
            });
            return new DecisionAttempt(active, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent decision was interrupted.", exception);
        } catch (BaseException exception) {
            return new DecisionAttempt(null, exception.getErrorCode());
        }
    }

    private void lockResourceSetBoundary(
            Connection connection, Long tenantId, UUID resourceSetId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT resource_set_id FROM com_admin_resource_sets
                 WHERE tenant_id = ? AND resource_set_id = ?
                 FOR UPDATE
                """)) {
            statement.setLong(1, tenantId);
            statement.setObject(2, resourceSetId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
            }
        }
    }

    private int awaitBlockedFirstApproverDecisions() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int blocked = 0;
        do {
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE application_name LIKE 'core006-first-approver-%'
                       AND state = 'active'
                       AND wait_event_type = 'Lock'
                       AND query ILIKE '%com_admin_resource_sets%'
                       AND query ILIKE '%FOR UPDATE%'
                    """, Integer.class);
            blocked = count == null ? 0 : count;
            if (blocked == 2) return blocked;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return blocked;
    }

    private record Fixture(
            Long tenantId,
            Long requester,
            Long approver,
            Long fulfiller,
            Long reviewer,
            Long catalogAdmin,
            Long tenantAdmin,
            Long member,
            Long subject,
            UUID resourceSetId) {
    }

    private record Event(String type, long sequence) {
    }

    private record DenialEvent(
            UUID auditEventId,
            String action,
            String outcome,
            String correlationId,
            String reason,
            String afterSnapshot) {
    }

    private record DecisionAttempt(
            AppGovernanceDtos.Assignment assignment,
            ErrorCode errorCode) {

        boolean active() {
            return assignment != null && "ACTIVE".equals(assignment.lifecycleState());
        }
    }
}
