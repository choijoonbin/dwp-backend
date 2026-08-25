package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalCommandRepository;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalQueryRepository;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Owner-store truth table for selected Approval resource-set isolation. */
@Testcontainers(disabledWithoutDocker = true)
class ApprovalManagementScopePostgresTest {

    private static final UUID WORKFLOW_A = UUID.fromString(
            "10000000-0000-0000-0000-00000000000a");
    private static final UUID WORKFLOW_B = UUID.fromString(
            "10000000-0000-0000-0000-00000000000b");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private ApprovalQueryRepository queries;
    private ApprovalCommandRepository commands;
    private ApprovalOwnerPredicateEvaluator owners;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        jdbc = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        queries = new ApprovalQueryRepository(named, mapper);
        commands = new ApprovalCommandRepository(named, mapper);
        owners = new ApprovalOwnerPredicateEvaluator(
                named, mock(ApprovalIdentityDirectory.class));
        jdbc.update("INSERT INTO apr_tenants (tenant_id) VALUES (42)");
        seedWorkflow(WORKFLOW_A, "FLOW_A", "RS_TEAM_A");
        seedWorkflow(WORKFLOW_B, "FLOW_B", "RS_TEAM_B");
    }

    @AfterEach
    void clearScope() {
        ApprovalManagementScopeContext.clear();
        ApprovalDecisionRevisionContext.clear();
    }

    @Test
    void scopeACannotReadChangeOrHighRiskLockScopeBAndCreatesInheritScopeA() {
        ApprovalManagementScopeContext.set("scope-team-a", "RS_TEAM_A");

        assertThat(queries.workflows(42, false))
                .extracting(ApprovalDtos.WorkflowSummary::workflowKey)
                .containsExactly("FLOW_A");
        assertError(ErrorCode.NOT_FOUND, () -> queries.workflow(42, WORKFLOW_B));
        assertError(ErrorCode.RESOURCE_CONFLICT,
                () -> commands.publishWorkflow(actor(), WORKFLOW_B, 0, "corr-a"));
        assertError(ErrorCode.RESOURCE_NOT_AVAILABLE,
                () -> owners.lockAndValidate(actor(), "WORKFLOW", WORKFLOW_B, 0));

        UUID category = commands.createFormCategory(actor(),
                new ApprovalDtos.CreateFormCategoryRequest(
                        "TEAM_A", null, "팀 A", "Team A", "A", "A", "folder", 10));
        assertThat(jdbc.queryForObject(
                "SELECT management_resource_set_key FROM apr_form_categories "
                        + "WHERE category_id = ?",
                String.class, category)).isEqualTo("RS_TEAM_A");
    }

    @Test
    void switchingTabsDoesNotLeakOrReuseThePriorRequestScope() {
        ApprovalManagementScopeContext.set("scope-team-a", "RS_TEAM_A");
        assertThat(queries.workflows(42, false))
                .extracting(ApprovalDtos.WorkflowSummary::workflowKey)
                .containsExactly("FLOW_A");
        ApprovalManagementScopeContext.clear();

        ApprovalManagementScopeContext.set("scope-team-b", "RS_TEAM_B");
        assertThat(queries.workflows(42, false))
                .extracting(ApprovalDtos.WorkflowSummary::workflowKey)
                .containsExactly("FLOW_B");
        commands.publishWorkflow(actor(), WORKFLOW_B, 0, "corr-b");

        assertThat(jdbc.queryForObject(
                "SELECT lifecycle_state FROM apr_workflow_definitions "
                        + "WHERE workflow_id = ?",
                String.class, WORKFLOW_B)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject(
                "SELECT lifecycle_state FROM apr_workflow_definitions "
                        + "WHERE workflow_id = ?",
                String.class, WORKFLOW_A)).isEqualTo("DRAFT");
    }

    private void seedWorkflow(UUID id, String key, String scope) {
        jdbc.update("""
                INSERT INTO apr_workflow_definitions (
                    workflow_id, tenant_id, workflow_key, name_ko, name_en,
                    description_ko, description_en, category,
                    management_resource_set_key, created_by, updated_by)
                VALUES (?, 42, ?, ?, ?, 'description', 'description', 'GENERAL', ?, 99, 99)
                """, id, key, key, key, scope);
        jdbc.update("""
                INSERT INTO apr_workflow_versions (
                    workflow_version_id, tenant_id, workflow_id, version_number,
                    definition, definition_sha256, lifecycle_state, created_by)
                VALUES (?, 42, ?, 1, '{}'::jsonb, ?, 'DRAFT', 99)
                """, UUID.randomUUID(), id, "a".repeat(64));
    }

    private ApprovalRequestContext.Actor actor() {
        return new ApprovalRequestContext.Actor(
                17L, 42L, null, "Manager", Set.of("APPROVAL_ADMIN"),
                Set.of("ADMIN.APPROVAL_DESIGN:PUBLISH"));
    }

    private void assertError(
            ErrorCode expected,
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }
}
