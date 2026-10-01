package com.dwp.services.auth.service;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

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
    private static final Instant NOW = Instant.parse("2026-09-29T09:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TenantAppAdoptionRepository repository;
    private static TenantAppAdoptionService service;
    private static AppGovernanceAuthorization authorization;

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
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ProductAuthorizationPostgresCatalogFixture.activateV3(source, jdbc, mapper);
        repository = new TenantAppAdoptionRepository(jdbc, mapper);
        authorization = mock(AppGovernanceAuthorization.class);
        when(authorization.appResourceKeys(eq(TENANT), anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    Long actor = invocation.getArgument(1);
                    String responsibility = invocation.getArgument(2);
                    if (REQUESTER.equals(actor) && "APP_OWNER".equals(responsibility)) {
                        return Set.of("APP.APPROVALS", "APP.COMMUNICATIONS");
                    }
                    if (REVIEWER.equals(actor)
                            && "APP_ACCESS_APPROVER".equals(responsibility)) {
                        return Set.of("APP.APPROVALS", "APP.COMMUNICATIONS");
                    }
                    if (ACTIVATOR.equals(actor)
                            && "APP_ACCESS_MANAGER".equals(responsibility)) {
                        return Set.of("APP.APPROVALS", "APP.COMMUNICATIONS");
                    }
                    return Set.of();
                });
        service = new TenantAppAdoptionService(
                repository, mock(IdentityAuditService.class), authorization,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void fullWorkflowSeparatesReservationsFromActiveSeatsAndKeepsExternalExecutionUnavailable() {
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
                 ORDER BY user_id LIMIT 2
                """, Long.class, TENANT);
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
}
