package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.tenantappadoption.TenantAppAdoptionDtos;
import com.dwp.services.auth.tenantappadoption.TenantAppAdoptionRepository;
import com.dwp.services.auth.tenantappadoption.TenantAppAdoptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class TenantAppAdoptionPostgresIntegrationTest {

    private static final Long TENANT = 1L;
    private static final Long REQUESTER = 1L;
    private static final Long REVIEWER = 2L;
    private static final Long ACTIVATOR = 3L;
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TenantAppAdoptionRepository repository;
    private static TenantAppAdoptionService service;
    private static AppGovernanceAuthorization authorization;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ProductAuthorizationPostgresCatalogFixture.activateV3(source, jdbc, mapper);
        repository = new TenantAppAdoptionRepository(jdbc, mapper);
        authorization = mock(AppGovernanceAuthorization.class);
        when(authorization.appResourceKeys(eq(TENANT), anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    Long actor = invocation.getArgument(1);
                    String responsibility = invocation.getArgument(2);
                    if (REQUESTER.equals(actor) && "APP_OWNER".equals(responsibility)) {
                        return Set.of("APP.APPROVALS", "APP.COMMUNICATIONS", "APP.HCM");
                    }
                    if (REVIEWER.equals(actor)
                            && "APP_ACCESS_APPROVER".equals(responsibility)) {
                        return Set.of("APP.APPROVALS", "APP.COMMUNICATIONS", "APP.HCM");
                    }
                    if (ACTIVATOR.equals(actor)
                            && "APP_ACCESS_MANAGER".equals(responsibility)) {
                        return Set.of("APP.APPROVALS", "APP.COMMUNICATIONS", "APP.HCM");
                    }
                    return Set.of();
                });
        service = new TenantAppAdoptionService(
                repository, mock(IdentityAuditService.class), authorization,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void fullWorkflowSeparatesReservationsFromActiveSeatsAndKeepsExternalExecutionUnavailable()
            throws Exception {
        var installation = service.createInstallation(
                TENANT, REQUESTER, "installation-draft",
                new TenantAppAdoptionDtos.CreateInstallationRequest(
                        "approvals", "APP.APPROVALS", "INTERNAL_AUTH_CONTROLLED", 1,
                        "Adopt the internal approvals application for this tenant."));
        assertThatThrownBy(() -> service.createInstallation(
                TENANT, REQUESTER, "installation-duplicate",
                new TenantAppAdoptionDtos.CreateInstallationRequest(
                        "approvals", "APP.APPROVALS", "INTERNAL_AUTH_CONTROLLED", 1,
                        "Duplicate adoption must be rejected by the unique owner record.")))
                .isInstanceOf(BaseException.class);

        var submitted = service.submitInstallation(
                TENANT, REQUESTER, "installation-submit", installation.installationId(),
                new TenantAppAdoptionDtos.VersionedCommand(installation.version()));
        assertThatThrownBy(() -> service.decideInstallation(
                TENANT, REQUESTER, "installation-self-review", installation.installationId(),
                new TenantAppAdoptionDtos.DecisionCommand(
                        submitted.version(), "APPROVE",
                        "A requester cannot approve the same installation.")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE com_tenant_app_installations
                   SET lifecycle_state = 'APPROVED', approved_by = requested_by,
                       approved_at = CURRENT_TIMESTAMP
                 WHERE installation_id = ?
                """, installation.installationId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        var approved = service.decideInstallation(
                TENANT, REVIEWER, "installation-approve", installation.installationId(),
                new TenantAppAdoptionDtos.DecisionCommand(
                        submitted.version(), "APPROVE",
                        "Independent application owner review approved adoption."));
        assertThatThrownBy(() -> service.activateInstallation(
                TENANT, REVIEWER, "installation-self-activate", installation.installationId(),
                new TenantAppAdoptionDtos.ActivationCommand(
                        approved.version(), "The reviewer cannot activate this installation.")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE com_tenant_app_installations
                   SET lifecycle_state = 'ENABLED', activated_by = approved_by,
                       activated_at = CURRENT_TIMESTAMP,
                       activation_receipt_id = gen_random_uuid()
                 WHERE installation_id = ?
                """, installation.installationId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        var enabled = service.activateInstallation(
                TENANT, ACTIVATOR, "installation-activate", installation.installationId(),
                new TenantAppAdoptionDtos.ActivationCommand(
                        approved.version(), "Activate the independently approved adoption."));
        assertThat(enabled.lifecycleState()).isEqualTo("ENABLED");
        assertThat(enabled.activationReceiptId()).isNotNull();
        assertThat(enabled.externalExecutorState()).isEqualTo("NOT_REQUIRED");

        List<Long> users = jdbc.queryForList("""
                SELECT user_id FROM com_users
                 WHERE tenant_id = ? AND status IN ('ACTIVE', 'INVITED')
                   AND user_id NOT IN (?, ?, ?)
                 ORDER BY user_id LIMIT 2
                """, Long.class, TENANT, REQUESTER, REVIEWER, ACTIVATOR);
        assertThat(users).hasSize(2);
        var pending = service.createAssignment(
                TENANT, REQUESTER, "assignment-request",
                new TenantAppAdoptionDtos.CreateAssignmentRequest(
                        enabled.installationId(), users.get(0), NOW.plusSeconds(7200),
                        "Reserve one governed internal workforce application seat."));
        assertThat(repository.requireInstallation(TENANT, enabled.installationId()))
                .satisfies(value -> {
                    assertThat(value.reservedSeats()).isZero();
                    assertThat(value.activeSeats()).isZero();
                });
        assertThatThrownBy(() -> service.createAssignment(
                TENANT, REQUESTER, "assignment-duplicate",
                new TenantAppAdoptionDtos.CreateAssignmentRequest(
                        enabled.installationId(), users.get(0), NOW.plusSeconds(7200),
                        "A duplicate open user reservation must be rejected.")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.decideAssignment(
                TENANT, REQUESTER, "assignment-self-review", pending.assignmentId(),
                new TenantAppAdoptionDtos.DecisionCommand(
                        pending.version(), "APPROVE",
                        "The requester cannot approve the same seat reservation.")))
                .isInstanceOf(BaseException.class);
        var seatApproved = service.decideAssignment(
                TENANT, REVIEWER, "assignment-approve", pending.assignmentId(),
                new TenantAppAdoptionDtos.DecisionCommand(
                        pending.version(), "APPROVE",
                        "Independent access review approved the seat reservation."));
        assertThat(repository.requireInstallation(TENANT, enabled.installationId()))
                .satisfies(value -> {
                    assertThat(value.reservedSeats()).isEqualTo(1);
                    assertThat(value.activeSeats()).isZero();
                });
        assertThatThrownBy(() -> service.activateAssignment(
                TENANT, REVIEWER, "assignment-self-activate", pending.assignmentId(),
                new TenantAppAdoptionDtos.ActivationCommand(
                        seatApproved.version(), "The reviewer cannot activate the same seat.")))
                .isInstanceOf(BaseException.class);
        var activeSeat = service.activateAssignment(
                TENANT, ACTIVATOR, "assignment-activate", pending.assignmentId(),
                new TenantAppAdoptionDtos.ActivationCommand(
                        seatApproved.version(), "Activate the independently approved seat."));
        assertThat(activeSeat.activationReceiptId()).isNotNull();
        assertThat(repository.requireInstallation(TENANT, enabled.installationId()))
                .satisfies(value -> {
                    assertThat(value.reservedSeats()).isEqualTo(1);
                    assertThat(value.activeSeats()).isEqualTo(1);
                });

        var excess = service.createAssignment(
                TENANT, REQUESTER, "assignment-excess-request",
                new TenantAppAdoptionDtos.CreateAssignmentRequest(
                        enabled.installationId(), users.get(1), NOW.plusSeconds(7200),
                        "Request another seat to verify capacity enforcement."));
        assertThatThrownBy(() -> service.decideAssignment(
                TENANT, users.get(1), "assignment-target-self-review", excess.assignmentId(),
                new TenantAppAdoptionDtos.DecisionCommand(
                        excess.version(), "APPROVE",
                        "The target user cannot approve their own workforce assignment.")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> repository.transitionAssignment(
                TENANT, excess.assignmentId(), excess.version(), "PENDING_APPROVAL", "APPROVED",
                users.get(1), "The SQL guard must reject a target-self transition.", null, NOW))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE com_tenant_app_workforce_assignments
                   SET lifecycle_state = 'APPROVED', approved_by = principal_ref::bigint,
                       approved_at = CURRENT_TIMESTAMP
                 WHERE assignment_id = ?
                """, excess.assignmentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE com_tenant_app_workforce_assignments
                   SET lifecycle_state = 'ACTIVE', approved_by = ?,
                       approved_at = CURRENT_TIMESTAMP, activated_by = principal_ref::bigint,
                       activated_at = CURRENT_TIMESTAMP,
                       activation_receipt_id = gen_random_uuid(),
                       valid_from = CURRENT_TIMESTAMP
                 WHERE assignment_id = ?
                """, REVIEWER, excess.assignmentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> service.decideAssignment(
                TENANT, REVIEWER, "assignment-excess-review", excess.assignmentId(),
                new TenantAppAdoptionDtos.DecisionCommand(
                        excess.version(), "APPROVE",
                        "Review the request against the installation seat capacity.")))
                .isInstanceOf(BaseException.class);

        jdbc.update("""
                UPDATE com_tenant_app_workforce_assignments
                   SET valid_from = CURRENT_TIMESTAMP - INTERVAL '2 hours',
                       valid_to = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                 WHERE assignment_id = ?
                """, activeSeat.assignmentId());
        assertThat(repository.requireInstallation(TENANT, enabled.installationId()))
                .satisfies(value -> {
                    assertThat(value.reservedSeats()).isZero();
                    assertThat(value.activeSeats()).isZero();
                });

        assertThat(repository.requireAssignment(TENANT, activeSeat.assignmentId()))
                .satisfies(value -> {
                    assertThat(value.lifecycleState()).isEqualTo("EXPIRED");
                    assertThat(value.allowedActions()).isEmpty();
                });
        var replacementForActive = transactions.execute(status -> service.createAssignment(
                TENANT, REQUESTER, "assignment-replace-active",
                new TenantAppAdoptionDtos.CreateAssignmentRequest(
                        enabled.installationId(), users.get(0), NOW.plusSeconds(10_800),
                        "Replace a workforce assignment whose active window elapsed.")));
        assertThat(rawAssignmentState(activeSeat.assignmentId())).isEqualTo("EXPIRED");

        var replacementApproved = service.decideAssignment(
                TENANT, REVIEWER, "assignment-replacement-approve",
                replacementForActive.assignmentId(),
                new TenantAppAdoptionDtos.DecisionCommand(
                        replacementForActive.version(), "APPROVE",
                        "Approve the replacement reservation before testing expiry release."));
        jdbc.update("""
                UPDATE com_tenant_app_workforce_assignments
                   SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 hours',
                       valid_to = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                 WHERE assignment_id = ?
                """, replacementApproved.assignmentId());
        var replacementForApproved = transactions.execute(status -> service.createAssignment(
                TENANT, REQUESTER, "assignment-replace-approved",
                new TenantAppAdoptionDtos.CreateAssignmentRequest(
                        enabled.installationId(), users.get(0), NOW.plusSeconds(14_400),
                        "Replace a workforce assignment whose approval window elapsed.")));
        assertThat(rawAssignmentState(replacementApproved.assignmentId())).isEqualTo("EXPIRED");

        jdbc.update("""
                UPDATE com_tenant_app_workforce_assignments
                   SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 hours',
                       valid_to = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                 WHERE assignment_id = ?
                """, replacementForApproved.assignmentId());
        assertThatThrownBy(() -> repository.insertAssignment(
                TENANT, REQUESTER,
                new TenantAppAdoptionDtos.CreateAssignmentRequest(
                        enabled.installationId(), users.get(0), NOW,
                        "The repository must reject an exact-boundary assignment."),
                "NOT_REQUIRED", NOW))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode().name()).isEqualTo("INVALID_STATE"));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<TenantAppAdoptionDtos.Assignment>> attempts =
                    java.util.stream.IntStream.range(0, 2)
                            .mapToObj(index -> executor.submit(() -> {
                                start.await();
                                return transactions.execute(status -> service.createAssignment(
                                        TENANT, REQUESTER, "assignment-concurrent-" + index,
                                        new TenantAppAdoptionDtos.CreateAssignmentRequest(
                                                enabled.installationId(), users.get(0),
                                                NOW.plusSeconds(18_000),
                                                "Race two replacement requests through one installation lock.")));
                            }))
                            .toList();
            start.countDown();
            int successes = 0;
            int conflicts = 0;
            for (Future<TenantAppAdoptionDtos.Assignment> attempt : attempts) {
                try {
                    assertThat(attempt.get()).isNotNull();
                    successes++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(BaseException.class);
                    conflicts++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(rawAssignmentState(replacementForApproved.assignmentId()))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM com_tenant_app_workforce_assignments
                 WHERE tenant_id = ? AND installation_id = ?
                   AND principal_type = 'USER' AND principal_ref = ?
                   AND lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
                """, Integer.class, TENANT, enabled.installationId(),
                String.valueOf(users.get(0)))).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT evidence ->> 'previousState'
                  FROM com_tenant_app_adoption_events
                 WHERE tenant_id = ? AND event_type = 'ASSIGNMENT_EXPIRED'
                """, String.class, TENANT))
                .contains("ACTIVE", "APPROVED", "PENDING_APPROVAL");

        var excessApproved = service.decideAssignment(
                TENANT, REVIEWER, "assignment-excess-review-after-expiry", excess.assignmentId(),
                new TenantAppAdoptionDtos.DecisionCommand(
                        excess.version(), "APPROVE",
                        "Expired active seats must release their reservation capacity."));
        jdbc.update("""
                UPDATE com_tenant_app_workforce_assignments
                   SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 hours',
                       valid_to = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                 WHERE assignment_id = ?
                """, excessApproved.assignmentId());
        assertThat(repository.requireInstallation(TENANT, enabled.installationId()))
                .satisfies(value -> {
                    assertThat(value.reservedSeats()).isZero();
                    assertThat(value.activeSeats()).isZero();
                });
        assertThatThrownBy(() -> service.activateAssignment(
                TENANT, ACTIVATOR, "assignment-expired-activate", excessApproved.assignmentId(),
                new TenantAppAdoptionDtos.ActivationCommand(
                        excessApproved.version(), "Expired approved seats cannot activate.")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> repository.transitionAssignment(
                TENANT, excessApproved.assignmentId(), excessApproved.version(),
                "APPROVED", "ACTIVE", ACTIVATOR,
                "The atomic SQL guard must reject an expired approval.",
                java.util.UUID.randomUUID(), Instant.now()))
                .isInstanceOf(BaseException.class);

        jdbc.update("""
                UPDATE com_tenant_app_workforce_assignments
                   SET valid_from = CURRENT_TIMESTAMP + INTERVAL '1 hour',
                       valid_to = CURRENT_TIMESTAMP + INTERVAL '2 hours'
                 WHERE assignment_id = ?
                """, excessApproved.assignmentId());
        assertThat(repository.requireInstallation(TENANT, enabled.installationId()))
                .satisfies(value -> {
                    assertThat(value.reservedSeats()).isEqualTo(1);
                    assertThat(value.activeSeats()).isZero();
                });
        assertThatThrownBy(() -> service.activateAssignment(
                TENANT, ACTIVATOR, "assignment-future-activate", excessApproved.assignmentId(),
                new TenantAppAdoptionDtos.ActivationCommand(
                        excessApproved.version(), "Future approved seats cannot activate early.")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> repository.transitionAssignment(
                TENANT, excessApproved.assignmentId(), excessApproved.version(),
                "APPROVED", "ACTIVE", ACTIVATOR,
                "The atomic SQL guard must reject a future approval.",
                java.util.UUID.randomUUID(), Instant.now()))
                .isInstanceOf(BaseException.class);
        var revokedFuture = service.revokeAssignment(
                TENANT, ACTIVATOR, "assignment-future-revoke", excessApproved.assignmentId(),
                new TenantAppAdoptionDtos.RevokeCommand(
                        excessApproved.version(),
                        "Future approved reservations remain explicitly revocable."));
        assertThat(revokedFuture.lifecycleState()).isEqualTo("REVOKED");
        assertThat(repository.requireInstallation(TENANT, enabled.installationId()).reservedSeats())
                .isZero();

        var expiredPending = service.createAssignment(
                TENANT, REQUESTER, "assignment-expired-pending-request",
                new TenantAppAdoptionDtos.CreateAssignmentRequest(
                        enabled.installationId(), users.get(1), NOW.plusSeconds(7_200),
                        "Create a pending request whose approval window will expire."));
        jdbc.update("""
                UPDATE com_tenant_app_workforce_assignments
                   SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 hours',
                       valid_to = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                 WHERE assignment_id = ?
                """, expiredPending.assignmentId());
        assertThatThrownBy(() -> service.decideAssignment(
                TENANT, REVIEWER, "assignment-expired-pending-approve",
                expiredPending.assignmentId(),
                new TenantAppAdoptionDtos.DecisionCommand(
                        expiredPending.version(), "APPROVE",
                        "Expired pending assignments must fail closed.")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> repository.transitionAssignment(
                TENANT, expiredPending.assignmentId(), expiredPending.version(),
                "PENDING_APPROVAL", "APPROVED", REVIEWER,
                "The atomic SQL guard must reject an expired pending approval.", null, NOW))
                .isInstanceOf(BaseException.class);
        var expiredDenied = service.decideAssignment(
                TENANT, REVIEWER, "assignment-expired-pending-reject",
                expiredPending.assignmentId(),
                new TenantAppAdoptionDtos.DecisionCommand(
                        expiredPending.version(), "REJECT",
                        "Close the expired request without granting access."));
        assertThat(expiredDenied.lifecycleState()).isEqualTo("DENIED");
        var indefinite = service.createAssignment(
                TENANT, REQUESTER, "assignment-indefinite-request",
                new TenantAppAdoptionDtos.CreateAssignmentRequest(
                        enabled.installationId(), users.get(1), null,
                        "Create an indefinite assignment without a validity end."));
        assertThat(indefinite.validTo()).isNull();

        var external = service.createInstallation(
                TENANT, REQUESTER, "external-draft",
                new TenantAppAdoptionDtos.CreateInstallationRequest(
                        "communications", "APP.COMMUNICATIONS", "EXTERNAL_SERVICE", 10,
                        "Request external communications adoption without inventing execution."));
        var externalSubmitted = service.submitInstallation(
                TENANT, REQUESTER, "external-submit", external.installationId(),
                new TenantAppAdoptionDtos.VersionedCommand(external.version()));
        var externalApproved = service.decideInstallation(
                TENANT, REVIEWER, "external-approve", external.installationId(),
                new TenantAppAdoptionDtos.DecisionCommand(
                        externalSubmitted.version(), "APPROVE",
                        "Independent review approves the external adoption request."));
        assertThatThrownBy(() -> service.activateInstallation(
                TENANT, ACTIVATOR, "external-activate", external.installationId(),
                new TenantAppAdoptionDtos.ActivationCommand(
                        externalApproved.version(),
                        "Do not activate without an external provisioning receipt.")))
                .isInstanceOf(BaseException.class);
        assertThat(repository.requireInstallation(TENANT, external.installationId()))
                .satisfies(value -> {
                    assertThat(value.lifecycleState()).isEqualTo("APPROVED");
                    assertThat(value.externalExecutorState()).isEqualTo("UNAVAILABLE");
                    assertThat(value.activationReceiptId()).isNull();
                });

        when(authorization.requireVisibility(TENANT, ACTIVATOR)).thenReturn(
                new AppGovernanceAuthorization.Visibility(
                        true, Set.of(), Set.of(), Set.of()));
        assertThat(service.projection(TENANT, ACTIVATOR)).satisfies(projection -> {
            assertThat(projection.coverageState()).isEqualTo("COMPLETE_INTERNAL_OWNERS");
            assertThat(projection.exclusions()).contains(
                    "EXTERNAL_SAAS_PROVISIONING", "EXTERNAL_LICENSE_SETTLEMENT");
        });
    }

    @Test
    void concurrentApprovalsSerializeTheInstallationSeatCapacity() throws Exception {
        var installation = service.createInstallation(
                TENANT, REQUESTER, "capacity-installation-draft",
                new TenantAppAdoptionDtos.CreateInstallationRequest(
                        "hcm", "APP.HCM", "INTERNAL_AUTH_CONTROLLED", 1,
                        "Adopt HCM with one governed workforce application seat."));
        var submitted = service.submitInstallation(
                TENANT, REQUESTER, "capacity-installation-submit",
                installation.installationId(),
                new TenantAppAdoptionDtos.VersionedCommand(installation.version()));
        var approved = service.decideInstallation(
                TENANT, REVIEWER, "capacity-installation-approve",
                submitted.installationId(),
                new TenantAppAdoptionDtos.DecisionCommand(
                        submitted.version(), "APPROVE",
                        "Approve the isolated capacity concurrency fixture."));
        var enabled = service.activateInstallation(
                TENANT, ACTIVATOR, "capacity-installation-activate",
                approved.installationId(),
                new TenantAppAdoptionDtos.ActivationCommand(
                        approved.version(),
                        "Activate the isolated capacity concurrency fixture."));

        List<Long> users = jdbc.queryForList("""
                SELECT user_id FROM com_users
                 WHERE tenant_id = ? AND status IN ('ACTIVE', 'INVITED')
                   AND user_id NOT IN (?, ?, ?)
                 ORDER BY user_id LIMIT 2
                """, Long.class, TENANT, REQUESTER, REVIEWER, ACTIVATOR);
        assertThat(users).hasSize(2);
        var first = service.createAssignment(
                TENANT, REQUESTER, "capacity-assignment-first",
                new TenantAppAdoptionDtos.CreateAssignmentRequest(
                        enabled.installationId(), users.get(0), NOW.plusSeconds(7_200),
                        "Request the first seat in the capacity concurrency fixture."));
        var second = service.createAssignment(
                TENANT, REQUESTER, "capacity-assignment-second",
                new TenantAppAdoptionDtos.CreateAssignmentRequest(
                        enabled.installationId(), users.get(1), NOW.plusSeconds(7_200),
                        "Request the second seat in the capacity concurrency fixture."));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<TenantAppAdoptionDtos.Assignment>> attempts = List.of(first, second)
                    .stream()
                    .map(assignment -> executor.submit(() -> {
                        start.await();
                        return transactions.execute(status -> service.decideAssignment(
                                TENANT, REVIEWER,
                                "capacity-approve-" + assignment.assignmentId(),
                                assignment.assignmentId(),
                                new TenantAppAdoptionDtos.DecisionCommand(
                                        assignment.version(), "APPROVE",
                                        "Approve one seat while preserving the installation capacity.")));
                    }))
                    .toList();
            start.countDown();
            int successes = 0;
            int capacityConflicts = 0;
            for (Future<TenantAppAdoptionDtos.Assignment> attempt : attempts) {
                try {
                    assertThat(attempt.get()).isNotNull();
                    successes++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause())
                            .isInstanceOfSatisfying(BaseException.class, failure ->
                                    assertThat(failure.getErrorCode())
                                            .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
                    capacityConflicts++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(capacityConflicts).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM com_tenant_app_workforce_assignments
                 WHERE assignment_id IN (?, ?) AND lifecycle_state = 'APPROVED'
                """, Integer.class, first.assignmentId(), second.assignmentId())).isEqualTo(1);
        assertThat(repository.requireInstallation(TENANT, enabled.installationId()))
                .satisfies(value -> {
                    assertThat(value.seatCapacity()).isEqualTo(1);
                    assertThat(value.reservedSeats()).isEqualTo(1);
                    assertThat(value.reservedSeats()).isLessThanOrEqualTo(value.seatCapacity());
                });
    }

    @Test
    void prioritizesActionableRowsBeforeNewerBoundedTerminalHistory() {
        long tenantId = jdbc.queryForObject("""
                INSERT INTO com_tenants (code, name, status)
                VALUES (?, 'App adoption ordering', 'ACTIVE')
                RETURNING tenant_id
                """, Long.class, "app-order-" + UUID.randomUUID());
        long requesterId = insertUser(tenantId, "requester");
        long reviewerId = insertUser(tenantId, "reviewer");
        long activatorId = insertUser(tenantId, "activator");
        long pendingUserId = insertUser(tenantId, "pending-subject");
        long expiredUserId = insertUser(tenantId, "expired-subject");

        jdbc.update("""
                INSERT INTO com_tenant_app_installations (
                    installation_id, tenant_id, product_key, app_resource_key,
                    installation_kind, lifecycle_state, external_executor_state,
                    justification, requested_by, created_at, updated_at)
                SELECT gen_random_uuid(), ?, 'terminal-installation-' || fixture,
                       'APP.ORDERING', 'INTERNAL_AUTH_CONTROLLED', 'REJECTED',
                       'NOT_REQUIRED', 'Newer terminal installation history fixture.', ?,
                       CURRENT_TIMESTAMP - INTERVAL '1 hour'
                           + make_interval(secs => fixture),
                       CURRENT_TIMESTAMP - INTERVAL '1 hour'
                           + make_interval(secs => fixture)
                  FROM generate_series(1, 101) fixture
                """, tenantId, requesterId);
        UUID actionableInstallationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_tenant_app_installations (
                    installation_id, tenant_id, product_key, app_resource_key,
                    installation_kind, lifecycle_state, external_executor_state,
                    seat_capacity, justification, requested_by,
                    approved_by, approved_at, activated_by, activated_at,
                    activation_receipt_id, created_at, updated_at)
                VALUES (?, ?, 'actionable-installation', 'APP.ORDERING',
                        'INTERNAL_AUTH_CONTROLLED', 'ENABLED', 'NOT_REQUIRED', 10,
                        'Older enabled installation must remain visible.', ?,
                        ?, CURRENT_TIMESTAMP - INTERVAL '2 days',
                        ?, CURRENT_TIMESTAMP - INTERVAL '2 days', gen_random_uuid(),
                        CURRENT_TIMESTAMP - INTERVAL '3 days',
                        CURRENT_TIMESTAMP - INTERVAL '2 days')
                """, actionableInstallationId, tenantId, requesterId, reviewerId, activatorId);

        var installations = repository.installations(tenantId, null, 101);

        assertThat(installations).hasSize(101);
        assertThat(installations.get(0).installationId())
                .isEqualTo(actionableInstallationId);
        assertThat(installations.get(0).lifecycleState()).isEqualTo("ENABLED");

        jdbc.update("""
                INSERT INTO com_tenant_app_workforce_assignments (
                    assignment_id, tenant_id, installation_id, principal_type,
                    principal_ref, lifecycle_state, seat_quantity, source_type,
                    external_settlement_state, justification, requested_by,
                    created_at, updated_at)
                SELECT gen_random_uuid(), ?, ?, 'USER', ?, 'DENIED', 1,
                       'TENANT_DIRECT', 'NOT_REQUIRED',
                       'Newer terminal assignment history fixture.', ?,
                       CURRENT_TIMESTAMP - INTERVAL '1 hour'
                           + make_interval(secs => fixture),
                       CURRENT_TIMESTAMP - INTERVAL '1 hour'
                           + make_interval(secs => fixture)
                  FROM generate_series(1, 101) fixture
                """, tenantId, actionableInstallationId, String.valueOf(pendingUserId),
                requesterId);
        UUID actionableAssignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_tenant_app_workforce_assignments (
                    assignment_id, tenant_id, installation_id, principal_type,
                    principal_ref, lifecycle_state, seat_quantity, source_type,
                    external_settlement_state, valid_to, justification, requested_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'USER', ?, 'PENDING_APPROVAL', 1,
                        'TENANT_DIRECT', 'NOT_REQUIRED',
                        CURRENT_TIMESTAMP + INTERVAL '1 day',
                        'Older pending assignment must remain visible.', ?,
                        CURRENT_TIMESTAMP - INTERVAL '3 days',
                        CURRENT_TIMESTAMP - INTERVAL '2 days')
                """, actionableAssignmentId, tenantId, actionableInstallationId,
                String.valueOf(pendingUserId), requesterId);
        UUID expiredAssignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_tenant_app_workforce_assignments (
                    assignment_id, tenant_id, installation_id, principal_type,
                    principal_ref, lifecycle_state, seat_quantity, source_type,
                    external_settlement_state, valid_from, valid_to,
                    justification, requested_by, approved_by, approved_at,
                    activated_by, activated_at, activation_receipt_id,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'USER', ?, 'ACTIVE', 1,
                        'TENANT_DIRECT', 'NOT_REQUIRED',
                        CURRENT_TIMESTAMP - INTERVAL '3 days',
                        CURRENT_TIMESTAMP - INTERVAL '1 day',
                        'Expired active assignment is terminal in the projection.', ?,
                        ?, CURRENT_TIMESTAMP - INTERVAL '3 days',
                        ?, CURRENT_TIMESTAMP - INTERVAL '3 days', gen_random_uuid(),
                        CURRENT_TIMESTAMP - INTERVAL '4 days',
                        CURRENT_TIMESTAMP - INTERVAL '30 minutes')
                """, expiredAssignmentId, tenantId, actionableInstallationId,
                String.valueOf(expiredUserId), requesterId, reviewerId, activatorId);

        var assignments = repository.assignments(tenantId, null, null, 101);

        assertThat(assignments).hasSize(101);
        assertThat(assignments.get(0).assignmentId()).isEqualTo(actionableAssignmentId);
        assertThat(assignments.get(0).lifecycleState()).isEqualTo("PENDING_APPROVAL");
        assertThat(repository.assignments(tenantId, null, null, 103))
                .filteredOn(value -> value.assignmentId().equals(expiredAssignmentId))
                .singleElement()
                .extracting(TenantAppAdoptionDtos.Assignment::lifecycleState)
                .isEqualTo("EXPIRED");
    }

    private long insertUser(long tenantId, String label) {
        return jdbc.queryForObject("""
                INSERT INTO com_users (tenant_id, display_name, email, status)
                VALUES (?, ?, ?, 'ACTIVE')
                RETURNING user_id
                """, Long.class, tenantId, "Ordering " + label,
                label + "-" + UUID.randomUUID() + "@ordering.test");
    }

    private String rawAssignmentState(java.util.UUID assignmentId) {
        return jdbc.queryForObject("""
                SELECT lifecycle_state
                  FROM com_tenant_app_workforce_assignments
                 WHERE assignment_id = ?
                """, String.class, assignmentId);
    }
}
