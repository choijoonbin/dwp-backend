package com.dwp.services.people.integration;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hr.HrDomainFoundationService;
import com.dwp.services.people.provisioning.PeopleTenantProvisioningDtos;
import com.dwp.services.people.provisioning.PeopleTenantProvisioningService;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.workforce.WorkforceAccessDeniedAuditRecorder;
import com.dwp.services.people.workforce.WorkforceAccessDtos;
import com.dwp.services.people.workforce.WorkforceAccessPolicyRepository;
import com.dwp.services.people.workforce.WorkforceAccessPolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class LocalSyntheticPeopleWorkforceBootstrapPostgresTest {

    private static final String RUN_ID = "w1-20261002t010203z-89abcdef";
    private static final long ADMINISTRATOR_ID = 1001L;
    private static final List<String> TENANT_A_PLANNED_IDENTITY_ROLES =
            List.of("HR_ADMIN", "PAYROLL_ADMIN", "PEOPLE_ADMIN");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:18.4-alpine"));

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static PeopleTenantProvisioningService provisioning;
    private static HrisIntegrationRepository integrationRepository;
    private static WorkforceAccessPolicyService policies;
    private static HcmPopulationScopeService populations;
    private static HrisImportService imports;

    @BeforeAll
    static void migrateAndWireProductionOwners() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .validateOnMigrate(true)
                .load()
                .migrate();

        jdbc = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        HrDomainFoundationService foundation = new HrDomainFoundationService(jdbc);
        provisioning = new PeopleTenantProvisioningService(jdbc, foundation, objectMapper);
        integrationRepository = new HrisIntegrationRepository(named);
        imports = new HrisImportService(
                integrationRepository,
                new WorkdayReferenceMapper(objectMapper),
                objectMapper,
                foundation,
                true);
        AuditOutboxRecorder audit = new AuditOutboxRecorder(
                named, objectMapper, "dwp-people-server", "postgres-test", "test");
        policies = new WorkforceAccessPolicyService(
                new WorkforceAccessPolicyRepository(named),
                audit,
                new WorkforceAccessDeniedAuditRecorder(audit));
        populations = new HcmPopulationScopeService(
                new HcmPopulationRepository(named), policies);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void clearRequestContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void realOwnersCommitExactFixtureEvidenceAndRejectAReplayWithoutMutation() {
        UUID providerTenantId = UUID.fromString("10000000-0000-0000-0000-000000000101");
        long tenantId = 9_181_001L;
        activateTenant(providerTenantId, tenantId, "bootstrap-success");
        LocalSyntheticPeopleWorkforceBootstrapService service = bootstrapService();
        LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapRequest request =
                request(providerTenantId, tenantId);

        LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapResponse response =
                transactions.execute(ignored -> service.bootstrap(request));

        assertThat(response).isNotNull();
        assertThat(response.importReplayed()).isFalse();
        assertThat(response.importedWorkerCount()).isEqualTo(3);
        assertThat(response.targetPopulationCount()).isEqualTo(2);
        assertThat(response.plannedIdentityRoleCodes())
                .isEqualTo(TENANT_A_PLANNED_IDENTITY_ROLES);
        assertThat(response.targetPopulationRevision()).matches(
                "[0-9a-f]{32}:true\\|\\[\\]\\|\\[DIRECTORY, EMPLOYMENT, JOB_GRADE, "
                        + "WORKER_IDENTIFIERS\\]\\|READ");
        assertThat(response.actorPersonPublicId()).isNotEqualTo(response.targetPersonPublicId());
        assertThat(response.receiptSha256()).matches("[0-9a-f]{64}");
        assertThat(integrationRepository.findWorkforceIdentity(tenantId, "E100001"))
                .isPresent();
        assertThat(integrationRepository.findWorkforceIdentity(tenantId, "E100002"))
                .isPresent();
        assertThat(syncRunState(tenantId)).isEqualTo("SUCCEEDED:3:3:0:0");
        assertThat(receiptState(tenantId)).isEqualTo("SUCCEEDED");
        assertThat(count(tenantId, "ppl_persons")).isEqualTo(3);
        assertThat(count(tenantId, "sys_people_outbox_events")).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ppl_workforce_access_policies
                 WHERE tenant_id=? AND subject_type='ROLE'
                   AND subject_ref='TENANT_ADMIN' AND lifecycle_state='ACTIVE'
                   AND field_groups @> ARRAY['DIRECTORY','EMPLOYMENT','WORKER_IDENTIFIERS']::varchar[]
                   AND cardinality(field_groups)=3
                """, Long.class, tenantId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_people_audit_events
                 WHERE tenant_id=? AND action='people.hris-import.completed'
                """, Long.class, tenantId)).isEqualTo(1);
        assertThat(count(tenantId, "sys_audit_outbox")).isGreaterThanOrEqualTo(2);

        EvidenceCounts committed = evidenceCounts(tenantId);
        assertThatThrownBy(() -> transactions.execute(
                ignored -> service.bootstrap(request)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("one-shot runtime boundary");
        assertThat(evidenceCounts(tenantId)).isEqualTo(committed);
    }

    @Test
    void foundationOnlyTenantBPlanCommitsPopulationWithoutJobGrade() {
        UUID providerTenantId = UUID.fromString("10000000-0000-0000-0000-000000000103");
        long tenantId = 9_181_003L;
        activateTenant(providerTenantId, tenantId, "bootstrap-foundation-only");
        LocalSyntheticPeopleWorkforceBootstrapService service = bootstrapService();

        LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapResponse response =
                transactions.execute(ignored -> service.bootstrap(
                        request(providerTenantId, tenantId, List.of())));

        assertThat(response).isNotNull();
        assertThat(response.plannedIdentityRoleCodes()).isEmpty();
        assertThat(response.targetPopulationCount()).isEqualTo(2);
        assertThat(response.targetPopulationRevision()).matches(
                "[0-9a-f]{32}:true\\|\\[\\]\\|\\[DIRECTORY, EMPLOYMENT, "
                        + "WORKER_IDENTIFIERS\\]\\|READ");
        assertThat(response.receiptSha256()).matches("[0-9a-f]{64}");
        assertThat(syncRunState(tenantId)).isEqualTo("SUCCEEDED:3:3:0:0");
    }

    @Test
    void conflictingPolicyFailsClearlyAndRollsBackTheWholeImport() {
        UUID providerTenantId = UUID.fromString("10000000-0000-0000-0000-000000000102");
        long tenantId = 9_181_002L;
        activateTenant(providerTenantId, tenantId, "bootstrap-conflict");
        transactions.executeWithoutResult(ignored -> {
            PeopleRequestContext.set(ADMINISTRATOR_ID, tenantId, Set.of("ADMIN"));
            try {
                policies.create(new WorkforceAccessDtos.CreatePolicyRequest(
                        "ROLE", "TENANT_ADMIN", "TENANT", null,
                        List.of("DIRECTORY", "EMPLOYMENT", "WORKER_IDENTIFIERS"),
                        List.of("READ", "EXPORT"),
                        null, null, "Deliberate near-match for rollback verification."),
                        "bootstrap-conflict-precondition");
            } finally {
                PeopleRequestContext.clear();
            }
        });
        LocalSyntheticPeopleWorkforceBootstrapService service = bootstrapService();

        assertThatThrownBy(() -> transactions.execute(ignored -> service.bootstrap(
                request(providerTenantId, tenantId))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts with the active tenant-admin policy");

        assertThat(count(tenantId, "int_sync_runs")).isZero();
        assertThat(count(tenantId, "int_ingestion_receipts")).isZero();
        assertThat(count(tenantId, "ppl_persons")).isZero();
        assertThat(count(tenantId, "sys_people_outbox_events")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_people_audit_events
                 WHERE tenant_id=? AND action='people.hris-import.completed'
                """, Long.class, tenantId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ppl_workforce_access_policies
                 WHERE tenant_id=? AND subject_ref='TENANT_ADMIN'
                """, Long.class, tenantId)).isEqualTo(1);
    }

    private static LocalSyntheticPeopleWorkforceBootstrapService bootstrapService() {
        return new LocalSyntheticPeopleWorkforceBootstrapService(
                imports, integrationRepository, policies, populations, RUN_ID);
    }

    private static void activateTenant(UUID providerTenantId, long tenantId, String tenantKey) {
        transactions.executeWithoutResult(ignored -> {
            provisioning.provision(new PeopleTenantProvisioningDtos.ProvisionTenantRequest(
                    providerTenantId, tenantId, tenantKey,
                    "Synthetic " + tenantKey, "kr-central", "POOL"));
            provisioning.lifecycle(
                    providerTenantId,
                    new PeopleTenantProvisioningDtos.UpdateLifecycleRequest("ACTIVE"));
        });
    }

    private static LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapRequest request(
            UUID providerTenantId,
            long tenantId) {
        return request(providerTenantId, tenantId, TENANT_A_PLANNED_IDENTITY_ROLES);
    }

    private static LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapRequest request(
            UUID providerTenantId,
            long tenantId,
            List<String> plannedIdentityRoleCodes) {
        return new LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapRequest(
                RUN_ID, providerTenantId, tenantId, ADMINISTRATOR_ID,
                plannedIdentityRoleCodes);
    }

    private static String syncRunState(long tenantId) {
        return jdbc.queryForObject("""
                SELECT lifecycle_state || ':' || read_count || ':' || created_count || ':'
                       || updated_count || ':' || rejected_count
                  FROM int_sync_runs WHERE tenant_id=?
                """, String.class, tenantId);
    }

    private static String receiptState(long tenantId) {
        return jdbc.queryForObject("""
                SELECT lifecycle_state FROM int_ingestion_receipts WHERE tenant_id=?
                """, String.class, tenantId);
    }

    private static long count(long tenantId, String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?",
                Long.class,
                tenantId);
    }

    private static EvidenceCounts evidenceCounts(long tenantId) {
        return new EvidenceCounts(
                count(tenantId, "int_sync_runs"),
                count(tenantId, "int_ingestion_receipts"),
                count(tenantId, "ppl_persons"),
                count(tenantId, "sys_people_outbox_events"),
                count(tenantId, "sys_people_audit_events"),
                count(tenantId, "sys_audit_outbox"),
                count(tenantId, "ppl_workforce_access_policies"));
    }

    private record EvidenceCounts(
            long syncRuns,
            long receipts,
            long people,
            long projectionEvents,
            long auditEvents,
            long auditOutboxEvents,
            long policies) {
    }
}
