package com.dwp.services.auth.config;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventOutboxRepository;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.services.auth.repository.AppAdminPresetRepository;
import com.dwp.services.auth.service.AppAdminPresetOutboxPublisher;
import com.dwp.services.auth.service.AppAdminPresetRequestService;
import com.dwp.services.auth.service.AppAdminPresetService;
import com.dwp.services.auth.service.AppGovernanceService;
import com.dwp.services.auth.service.IdentityAuditService;
import com.dwp.services.auth.service.ScopedAdminDutyAssignmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Clean local-seed proof for the governed CORE-006 browser fixture lifecycle. */
@Testcontainers(disabledWithoutDocker = true)
class ProductAuthorizationLocalPilotPresetRunnerPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void migrateLocalDatabase() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
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
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void usesOnlyGovernedServicesAndReplaysWithoutDuplicateState() {
        IdentityAuditService audit = mock(IdentityAuditService.class);
        AppAdminPresetRepository repository =
                new AppAdminPresetRepository(jdbc, objectMapper);
        ScopedAdminDutyAssignmentService duties =
                new ScopedAdminDutyAssignmentService(jdbc);
        AppAdminPresetOutboxPublisher events = publisher();
        AppAdminPresetRequestService requests = new AppAdminPresetRequestService(
                jdbc, repository, duties, audit, events);
        AppAdminPresetService presets = new AppAdminPresetService(
                jdbc, repository, duties, audit, events, requests);
        AppGovernanceService governance = new AppGovernanceService(jdbc, audit);
        ProductAuthorizationLocalPilotPresetRunner runner =
                new ProductAuthorizationLocalPilotPresetRunner(
                        true,
                        new MockEnvironment().withProperty("DWP_ENVIRONMENT", "local"),
                        jdbc, governance, repository, requests, presets,
                        Clock.systemUTC());

        transactions.executeWithoutResult(ignored -> runner.run(null));

        UUID resourceSetId = jdbc.queryForObject("""
                SELECT resource_set_id FROM com_admin_resource_sets
                 WHERE tenant_id = 1 AND resource_set_key = 'RS_APPROVALS'
                   AND lifecycle_state = 'ACTIVE'
                """, UUID.class);
        assertThat(jdbc.query("""
                SELECT principal_ref, responsibility_code, created_by, approved_by,
                       lifecycle_state
                  FROM com_admin_role_assignments
                 WHERE tenant_id = 1 AND resource_set_id = ?
                   AND principal_type = 'USER'
                   AND ((principal_ref = '15'
                         AND responsibility_code = 'APP_ACCESS_APPROVER')
                     OR (principal_ref = '14'
                         AND responsibility_code = 'APP_ACCESS_MANAGER'))
                 ORDER BY principal_ref DESC
                """, (result, ignored) -> new ControlAssignment(
                        result.getString("principal_ref"),
                        result.getString("responsibility_code"),
                        result.getLong("created_by"),
                        result.getLong("approved_by"),
                        result.getString("lifecycle_state")), resourceSetId))
                .containsExactly(
                        new ControlAssignment(
                                "15", "APP_ACCESS_APPROVER", 5L, 23L, "ACTIVE"),
                        new ControlAssignment(
                                "14", "APP_ACCESS_MANAGER", 5L, 15L, "ACTIVE"));

        PresetAssignment aggregate = jdbc.queryForObject("""
                SELECT principal_ref, requested_by, approved_by, activated_by,
                       lifecycle_state
                  FROM com_admin_app_preset_assignments
                 WHERE tenant_id = 1 AND preset_code = 'APPROVAL_DESIGNER'
                   AND resource_set_id = ? AND principal_type = 'USER'
                   AND principal_ref = '900018'
                """, (result, ignored) -> new PresetAssignment(
                        result.getString("principal_ref"),
                        result.getLong("requested_by"),
                        result.getLong("approved_by"),
                        result.getLong("activated_by"),
                        result.getString("lifecycle_state")), resourceSetId);
        assertThat(aggregate).isEqualTo(
                new PresetAssignment("900018", 23L, 15L, 14L, "ACTIVE"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_scoped_duty_assignments duty
                JOIN com_admin_app_preset_assignments aggregate
                  ON aggregate.app_preset_assignment_id = duty.app_preset_assignment_id
                 WHERE aggregate.tenant_id = 1
                   AND aggregate.preset_code = 'APPROVAL_DESIGNER'
                   AND aggregate.principal_ref = '900018'
                   AND duty.lifecycle_state = 'ACTIVE'
                """, Integer.class)).isEqualTo(2);
        assertThat(outboxTypes()).containsExactly(
                AppAdminPresetOutboxPublisher.REQUESTED,
                AppAdminPresetOutboxPublisher.DECIDED,
                AppAdminPresetOutboxPublisher.ACTIVATED);

        verify(audit, times(2)).success(
                eq(1L), eq(5L), eq("access.app-responsibility.requested"),
                eq("APP_ADMIN_ASSIGNMENT"), anyString(), anyString(), any(), any());
        verify(audit).success(
                eq(1L), eq(23L), eq("access.app-responsibility.approved"),
                eq("APP_ADMIN_ASSIGNMENT"), anyString(), anyString(), any(), any());
        verify(audit).success(
                eq(1L), eq(15L), eq("access.app-responsibility.approved"),
                eq("APP_ADMIN_ASSIGNMENT"), anyString(), anyString(), any(), any());
        verify(audit).success(
                eq(1L), eq(23L), eq("access.app-admin-preset.requested"),
                eq("APP_ADMIN_PRESET_ASSIGNMENT"), anyString(), anyString(), any(), any());
        verify(audit).success(
                eq(1L), eq(15L), eq("access.app-admin-preset.approved"),
                eq("APP_ADMIN_PRESET_ASSIGNMENT"), anyString(), anyString(), any(), any());
        verify(audit).success(
                eq(1L), eq(14L), eq("access.app-admin-preset.activated"),
                eq("APP_ADMIN_PRESET_ASSIGNMENT"), anyString(), anyString(), any(), any());

        clearInvocations(audit);
        transactions.executeWithoutResult(ignored -> runner.run(null));
        verifyNoInteractions(audit);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_role_assignments
                 WHERE tenant_id = 1 AND resource_set_id = ?
                   AND principal_type = 'USER'
                   AND ((principal_ref = '15'
                         AND responsibility_code = 'APP_ACCESS_APPROVER')
                     OR (principal_ref = '14'
                         AND responsibility_code = 'APP_ACCESS_MANAGER'))
                """, Integer.class, resourceSetId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_app_preset_assignments
                 WHERE tenant_id = 1 AND preset_code = 'APPROVAL_DESIGNER'
                   AND resource_set_id = ? AND principal_ref = '900018'
                """, Integer.class, resourceSetId)).isEqualTo(1);
        assertThat(outboxTypes()).hasSize(3);
    }

    private AppAdminPresetOutboxPublisher publisher() {
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        DomainEventOutboxRepository outbox = new DomainEventOutboxRepository(
                new NamedParameterJdbcTemplate(jdbc.getDataSource()), objectMapper);
        return new AppAdminPresetOutboxPublisher(
                new DomainEventRecorder(outbox, contracts, objectMapper),
                contracts, objectMapper);
    }

    private List<String> outboxTypes() {
        return jdbc.query("""
                SELECT event_type FROM sys_domain_event_outbox
                 WHERE tenant_id = 1
                   AND aggregate_type = 'APP_ADMIN_PRESET_ASSIGNMENT'
                 ORDER BY aggregate_sequence
                """, (result, ignored) -> result.getString(1));
    }

    private record ControlAssignment(
            String principalRef,
            String responsibility,
            long requestedBy,
            long approvedBy,
            String lifecycleState) {
    }

    private record PresetAssignment(
            String principalRef,
            long requestedBy,
            long approvedBy,
            long activatedBy,
            String lifecycleState) {
    }
}
