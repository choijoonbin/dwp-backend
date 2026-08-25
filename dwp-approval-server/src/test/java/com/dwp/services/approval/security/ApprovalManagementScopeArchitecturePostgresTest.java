package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalCommandRepository;
import com.dwp.services.approval.domain.ApprovalDelegationCommandSupport;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalQueryRepository;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalManagementScopeArchitecturePostgresTest {

    private static final UUID WORKFLOW_A = UUID.fromString(
            "20000000-0000-0000-0000-00000000000a");
    private static final UUID WORKFLOW_B = UUID.fromString(
            "20000000-0000-0000-0000-00000000000b");
    private static final UUID FORM_A = UUID.fromString(
            "30000000-0000-0000-0000-00000000000a");
    private static final UUID FORM_B = UUID.fromString(
            "30000000-0000-0000-0000-00000000000b");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private PGSimpleDataSource dataSource;
    private ApprovalQueryRepository queries;
    private ApprovalCommandRepository commands;

    @BeforeEach
    void setUp() {
        dataSource = new PGSimpleDataSource();
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
        named = new NamedParameterJdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        queries = new ApprovalQueryRepository(named, mapper);
        commands = new ApprovalCommandRepository(named, mapper);
    }

    @AfterEach
    void clearContexts() {
        ApprovalManagementScopeContext.clear();
        ApprovalDecisionRevisionContext.clear();
    }

    @Test
    void firstNonRootHitAtomicallySeedsAndClonesTheRequiredBaseline() {
        ApprovalManagementScopeProvisioner provisioner =
                new ApprovalManagementScopeProvisioner(named, true);

        provisioner.ensure(84, "RS_TEAM_A");
        jdbc.update("""
                INSERT INTO apr_form_categories (
                    category_id, tenant_id, category_key, name_ko, name_en,
                    management_resource_set_key, created_by, updated_by)
                VALUES (?, 84, 'GENERAL', '팀 공통', 'Team general',
                        'RS_TEAM_A', 99, 99)
                """, UUID.randomUUID());
        UUID nonRootForm = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_forms (
                    form_id, tenant_id, form_key, name_ko, name_en,
                    management_resource_set_key, created_by, updated_by)
                VALUES (?, 84, 'TEAM_FORM', '팀 양식', 'Team form',
                        'RS_TEAM_A', 99, 99)
                """, nonRootForm);
        provisioner.ensure(84, "RS_TEAM_A");

        assertThat(count("apr_policy_rules", 84, "RS_TEAM_A")).isEqualTo(4);
        assertThat(count("apr_signature_providers", 84, "RS_TEAM_A")).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM apr_policy_rule_versions version
                  JOIN apr_policy_rules policy ON policy.policy_id = version.policy_id
                 WHERE policy.tenant_id = 84
                   AND policy.management_resource_set_key = 'RS_TEAM_A'
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT non_root_writes_activated_at IS NOT NULL
                  FROM apr_management_scope_schema_fence
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT category.management_resource_set_key
                  FROM apr_forms form
                  JOIN apr_form_categories category
                    ON category.tenant_id = form.tenant_id
                   AND category.category_id = form.category_id
                 WHERE form.form_id = ?
                """, String.class, nonRootForm)).isEqualTo("RS_TEAM_A");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) = COUNT(*) FILTER (
                           WHERE category.management_resource_set_key = 'RS_APPROVALS')
                  FROM apr_forms form
                  JOIN apr_form_categories category
                    ON category.tenant_id = form.tenant_id
                   AND category.category_id = form.category_id
                 WHERE form.tenant_id = 84
                   AND form.management_resource_set_key = 'RS_APPROVALS'
                """, Boolean.class)).isTrue();

        ApprovalManagementScopeContext.set("opaque-a", "RS_TEAM_A");
        assertThat(queries.policies(84)).hasSize(4);
        assertThat(queries.signatureProviders(84)).hasSize(3);
        assertThat(queries.isBlockingPolicyActive(
                84, "BLOCK_SELF_APPROVAL", "RS_TEAM_A")).isTrue();
    }

    @Test
    void inactiveTenantCannotBeReactivatedOrProvisioned() {
        jdbc.update("INSERT INTO apr_tenants (tenant_id, lifecycle_state, updated_at) "
                + "VALUES (85, 'SUSPENDED', TIMESTAMPTZ '2025-01-01 00:00:00Z')");
        ApprovalManagementScopeProvisioner provisioner =
                new ApprovalManagementScopeProvisioner(named, true);

        assertThatThrownBy(() -> provisioner.ensure(85, "RS_TEAM_A"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject(
                "SELECT lifecycle_state FROM apr_tenants WHERE tenant_id = 85",
                String.class)).isEqualTo("SUSPENDED");
        assertThat(count("apr_policy_rules", 85, "RS_TEAM_A")).isZero();

        assertThatThrownBy(() -> jdbc.queryForObject(
                "SELECT seed_approval_tenant(85)", Object.class))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state = 'SUSPENDED'
                       AND updated_at = TIMESTAMPTZ '2025-01-01 00:00:00Z'
                  FROM apr_tenants WHERE tenant_id = 85
                """, Boolean.class)).isTrue();
        assertThat(count("apr_policy_rules", 85, "RS_APPROVALS")).isZero();
    }

    @Test
    void failedProvisionRollsBackEveryCloneAndLeavesTheFenceUnactivated() {
        jdbc.execute("""
                CREATE FUNCTION test_reject_scoped_signature()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.management_resource_set_key <> 'RS_APPROVALS' THEN
                        RAISE EXCEPTION 'forced scoped signature failure';
                    END IF;
                    RETURN NEW;
                END
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_test_reject_scoped_signature
                BEFORE INSERT ON apr_signature_providers
                FOR EACH ROW EXECUTE FUNCTION test_reject_scoped_signature()
                """);
        ApprovalManagementScopeProvisioner provisioner =
                new ApprovalManagementScopeProvisioner(named, true);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                ignored -> provisioner.ensure(87, "RS_TEAM_A")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("forced scoped signature failure");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM apr_tenants WHERE tenant_id = 87",
                Integer.class)).isZero();
        assertThat(count("apr_policy_rules", 87, "RS_TEAM_A")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT non_root_writes_activated_at IS NULL
                  FROM apr_management_scope_schema_fence
                """, Boolean.class)).isTrue();
    }

    @Test
    void legacyReadsAreRootOnlyAndExactMissingScopeFailsClosed() {
        seedTenantAndWorkflows();
        jdbc.queryForObject("SELECT seed_approval_tenant(42)", Object.class);

        assertThat(queries.workflows(42, false))
                .extracting(ApprovalDtos.WorkflowSummary::workflowKey)
                .doesNotContain("FLOW_A", "FLOW_B");

        setDecision("work-scope", "route.approvals.admin.workflows.page");
        assertThatThrownBy(() -> queries.workflows(42, false))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        assertThatThrownBy(() -> commands.createFormCategory(
                actor(17), new ApprovalDtos.CreateFormCategoryRequest(
                        "TEAM_CATEGORY", null, "팀", "Team", "", "",
                        "folder", 10)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));

        ApprovalDecisionRevisionContext.clear();
        ApprovalManagementScopeContext.set("opaque-a", "RS_TEAM_A");
        assertThat(queries.workflows(42, false))
                .extracting(ApprovalDtos.WorkflowSummary::workflowKey)
                .containsExactly("FLOW_A");
    }

    @Test
    void workCatalogIsTenantWideByImmutableIdWhileAdminRemainsExactScope() {
        seedTenantAndWorkflows();
        publishBundle(WORKFLOW_A, FORM_A, "RS_TEAM_A", "A");
        publishBundle(WORKFLOW_B, FORM_B, "RS_TEAM_B", "B");

        setDecision("work-scope", "route.approvals.work.catalog.page");
        assertThat(queries.publishedWorkflowsForWork(42))
                .extracting(ApprovalDtos.WorkflowSummary::workflowId)
                .containsExactlyInAnyOrder(WORKFLOW_A, WORKFLOW_B);
        assertThat(queries.publishedForms(42))
                .extracting(ApprovalDtos.FormSummary::formId)
                .containsExactlyInAnyOrder(FORM_A, FORM_B);
        assertThat(queries.publishedTemplate(42, WORKFLOW_A).form().form().formId())
                .isEqualTo(FORM_A);
        assertThat(queries.publishedTemplate(42, WORKFLOW_B).form().form().formId())
                .isEqualTo(FORM_B);

        ApprovalDecisionRevisionContext.clear();
        ApprovalManagementScopeContext.set("opaque-a", "RS_TEAM_A");
        assertThat(queries.workflows(42, false))
                .extracting(ApprovalDtos.WorkflowSummary::workflowId)
                .containsExactly(WORKFLOW_A);
    }

    @Test
    void workCatalogNeverProjectsDraftFutureOrExpiredRoutes() {
        seedTenantAndWorkflows();
        publishBundle(WORKFLOW_A, FORM_A, "RS_TEAM_A", "A");
        publishBundle(WORKFLOW_B, FORM_B, "RS_TEAM_B", "B");
        setDecision("work-scope", "route.approvals.work.catalog.page");

        jdbc.update("UPDATE apr_workflow_definitions SET lifecycle_state = 'DRAFT' "
                + "WHERE workflow_id = ?", WORKFLOW_B);
        assertThat(queries.publishedWorkflowsForWork(42))
                .extracting(ApprovalDtos.WorkflowSummary::workflowId)
                .contains(WORKFLOW_A)
                .doesNotContain(WORKFLOW_B);
        assertWorkCatalogExcludesBundleB();

        ApprovalDecisionRevisionContext.clear();
        ApprovalManagementScopeContext.set("opaque-b", "RS_TEAM_B");
        assertThat(queries.form(42, FORM_B).routes())
                .extracting(ApprovalDtos.FormRouteSummary::workflowId)
                .containsExactly(WORKFLOW_B);

        ApprovalManagementScopeContext.clear();
        setDecision("work-scope", "route.approvals.work.catalog.page");
        jdbc.update("UPDATE apr_workflow_definitions SET lifecycle_state = 'PUBLISHED' "
                + "WHERE workflow_id = ?", WORKFLOW_B);
        jdbc.update("UPDATE apr_form_workflow_bindings "
                + "SET effective_from = CURRENT_TIMESTAMP + INTERVAL '1 day' "
                + "WHERE form_id = ?", FORM_B);
        assertWorkCatalogExcludesBundleB();

        jdbc.update("UPDATE apr_form_workflow_bindings "
                + "SET effective_from = CURRENT_TIMESTAMP - INTERVAL '2 days', "
                + "effective_to = CURRENT_TIMESTAMP - INTERVAL '1 day' "
                + "WHERE form_id = ?", FORM_B);
        assertWorkCatalogExcludesBundleB();
    }

    @Test
    void taskAndOutboxChildrenCannotCrossRequestScopeBoundaries() {
        seedTenantAndWorkflows();
        publishBundle(WORKFLOW_A, FORM_A, "RS_TEAM_A", "A");
        publishBundle(WORKFLOW_B, FORM_B, "RS_TEAM_B", "B");
        UUID requestA = seedRequest(WORKFLOW_A, FORM_A, "RS_TEAM_A", "A");
        UUID requestB = seedRequest(WORKFLOW_B, FORM_B, "RS_TEAM_B", "B");
        UUID stepA = seedStep(requestA, "A");
        UUID stepB = seedStep(requestB, "B");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO apr_tasks (
                    task_id, tenant_id, request_id, step_id,
                    assignee_user_id, status)
                VALUES (?, 42, ?, ?, 17, 'PENDING')
                """, UUID.randomUUID(), requestA, stepB))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.update("""
                INSERT INTO apr_tasks (
                    task_id, tenant_id, request_id, step_id,
                    assignee_user_id, status)
                VALUES (?, 42, ?, ?, 17, 'PENDING')
                """, UUID.randomUUID(), requestA, stepA)).isEqualTo(1);

        assertThatThrownBy(() -> insertOutbox(requestA, "RS_TEAM_B"))
                .isInstanceOf(DataAccessException.class);
        insertOutbox(requestA, "RS_TEAM_A");
    }

    @Test
    void categoryOnlyNonRootDataAlsoTripsTheForwardCompatibilityFence() {
        jdbc.update("INSERT INTO apr_tenants (tenant_id) VALUES (86)");
        jdbc.update("""
                INSERT INTO apr_form_categories (
                    category_id, tenant_id, category_key, name_ko, name_en,
                    management_resource_set_key, created_by, updated_by)
                VALUES (?, 86, 'TEAM', '팀', 'Team', 'RS_TEAM_A', 99, 99)
                """, UUID.randomUUID());
        ApprovalManagementScopeCompatibilityReadiness readiness =
                new ApprovalManagementScopeCompatibilityReadiness(
                        jdbc, "production", true, false, true);

        assertThatThrownBy(() -> readiness.run(
                new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rollback is unsafe");
    }

    @Test
    void workflowDelegationBindsToUuidAndCannotAuthorizeSameKeyInAnotherScope() {
        seedTenantAndWorkflows();
        jdbc.update("UPDATE apr_workflow_definitions SET workflow_key = 'SHARED' "
                + "WHERE workflow_id IN (?, ?)", WORKFLOW_A, WORKFLOW_B);
        publishBundle(WORKFLOW_A, FORM_A, "RS_TEAM_A", "A");
        publishBundle(WORKFLOW_B, FORM_B, "RS_TEAM_B", "B");
        UUID requestA = seedRequest(WORKFLOW_A, FORM_A, "RS_TEAM_A", "A");
        UUID requestB = seedRequest(WORKFLOW_B, FORM_B, "RS_TEAM_B", "B");
        UUID taskA = seedTask(requestA, seedStep(requestA, "A"), "A");
        seedTask(requestB, seedStep(requestB, "B"), "B");
        ApprovalIdentityDirectory.Subject delegate = new ApprovalIdentityDirectory.Subject(
                42L, 23L, UUID.randomUUID(), UUID.randomUUID(),
                "Delegate", "delegate@example.test", "Reviewer", "ACTIVE", List.of());

        ApprovalDelegationCommandSupport.Created delegation = commands.createDelegation(actor(17),
                new ApprovalDtos.CreateDelegationRequest(
                        23L, "WORKFLOW", "SHARED", WORKFLOW_A,
                        Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
                        "Delegate only the selected workflow"), delegate);
        assertThat(jdbc.queryForObject(
                "SELECT workflow_id FROM apr_delegations WHERE delegation_id = ?",
                UUID.class, delegation.delegationId())).isEqualTo(WORKFLOW_A);
        assertThat(queries.tasks(actor(23), "DELEGATED", 20))
                .extracting(ApprovalDtos.TaskSummary::taskId)
                .containsExactly(taskA);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_delegations
                   SET workflow_id = ?, workflow_key = 'SHARED'
                 WHERE delegation_id = ?
                """, WORKFLOW_B, delegation.delegationId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_delegations SET workflow_key = 'OTHER'
                 WHERE delegation_id = ?
                """, delegation.delegationId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_delegations SET scope_type = 'ALL'
                 WHERE delegation_id = ?
                """, delegation.delegationId()))
                .isInstanceOf(DataAccessException.class);
        commands.revokeDelegation(actor(17), delegation.delegationId(), 0);
        assertThat(jdbc.queryForObject(
                "SELECT lifecycle_state FROM apr_delegations WHERE delegation_id = ?",
                String.class, delegation.delegationId())).isEqualTo("REVOKED");
    }

    @Test
    void localRepeatableSeedIsUuidBoundAndIdempotentAfterFreshV14Migration()
            throws Exception {
        String seed = new ClassPathResource(
                "db/local-seed/R__seed_skax_approval_data.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        jdbc.execute(seed);
        Integer firstCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM apr_delegations
                 WHERE tenant_id = 1
                   AND reason = '휴가 및 프로젝트 일정에 따른 결재 대행 설정'
                """, Integer.class);
        jdbc.execute(seed);

        assertThat(firstCount).isNotNull().isPositive();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM apr_delegations
                 WHERE tenant_id = 1
                   AND reason = '휴가 및 프로젝트 일정에 따른 결재 대행 설정'
                """, Integer.class)).isEqualTo(firstCount);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM apr_delegations
                 WHERE tenant_id = 1 AND scope_type = 'WORKFLOW'
                   AND (workflow_id IS NULL OR workflow_key <> 'CAPEX_PURCHASE')
                """, Integer.class)).isZero();
    }

    private void seedTenantAndWorkflows() {
        jdbc.update("INSERT INTO apr_tenants (tenant_id) VALUES (42)");
        seedWorkflow(WORKFLOW_A, "FLOW_A", "RS_TEAM_A");
        seedWorkflow(WORKFLOW_B, "FLOW_B", "RS_TEAM_B");
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
                VALUES (?, 42, ?, 1, '{"steps":[]}'::jsonb, ?, 'DRAFT', 99)
                """, UUID.randomUUID(), id, "a".repeat(64));
    }

    private void publishBundle(UUID workflowId, UUID formId, String scope, String suffix) {
        UUID categoryId = UUID.nameUUIDFromBytes(("category-" + suffix).getBytes());
        jdbc.update("UPDATE apr_workflow_definitions SET lifecycle_state = 'PUBLISHED' "
                + "WHERE workflow_id = ?", workflowId);
        jdbc.update("UPDATE apr_workflow_versions SET lifecycle_state = 'PUBLISHED' "
                + "WHERE workflow_id = ?", workflowId);
        jdbc.update("""
                INSERT INTO apr_form_categories (
                    category_id, tenant_id, category_key, name_ko, name_en,
                    management_resource_set_key, created_by, updated_by)
                VALUES (?, 42, ?, ?, ?, ?, 99, 99)
                """, categoryId, "CAT_" + suffix, suffix, suffix, scope);
        jdbc.update("""
                INSERT INTO apr_forms (
                    form_id, tenant_id, form_key, name_ko, name_en,
                    lifecycle_state, category_id, management_resource_set_key,
                    created_by, updated_by)
                VALUES (?, 42, ?, ?, ?, 'PUBLISHED', ?, ?, 99, 99)
                """, formId, "FORM_" + suffix, suffix, suffix, categoryId, scope);
        jdbc.update("""
                INSERT INTO apr_form_versions (
                    form_version_id, tenant_id, form_id, version_number,
                    schema_payload, schema_sha256, lifecycle_state,
                    published_at, published_by, created_by)
                VALUES (?, 42, ?, 1, '{"fields":[]}'::jsonb, ?, 'PUBLISHED',
                        CURRENT_TIMESTAMP, 99, 99)
                """, UUID.randomUUID(), formId, "b".repeat(64));
        jdbc.update("""
                INSERT INTO apr_form_workflow_bindings (
                    binding_id, tenant_id, form_id, workflow_id,
                    binding_type, lifecycle_state, effective_from,
                    created_by, updated_by)
                VALUES (?, 42, ?, ?, 'DEFAULT', 'ACTIVE',
                        CURRENT_TIMESTAMP - INTERVAL '1 minute', 99, 99)
                """, UUID.randomUUID(), formId, workflowId);
    }

    private UUID seedRequest(UUID workflowId, UUID formId, String scope, String suffix) {
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_requests (
                    request_id, tenant_id, request_number,
                    workflow_version_id, form_version_id,
                    title, requester_user_id, status,
                    management_resource_set_key, created_by, updated_by)
                SELECT ?, 42, ?, workflow_version.workflow_version_id,
                       form_version.form_version_id, ?, 99, 'IN_REVIEW', ?, 99, 99
                  FROM apr_workflow_versions workflow_version
                  JOIN apr_form_versions form_version
                    ON form_version.tenant_id = workflow_version.tenant_id
                   AND form_version.form_id = ?
                   AND form_version.version_number = 1
                 WHERE workflow_version.tenant_id = 42
                   AND workflow_version.workflow_id = ?
                   AND workflow_version.version_number = 1
                """, requestId, "REQ-" + suffix + '-' + requestId,
                "Request " + suffix, scope, formId, workflowId);
        return requestId;
    }

    private UUID seedStep(UUID requestId, String suffix) {
        UUID stepId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_steps (
                    step_id, tenant_id, request_id, step_key, step_name,
                    sequence_number, status)
                VALUES (?, 42, ?, ?, ?, 1, 'IN_PROGRESS')
                """, stepId, requestId, "STEP_" + suffix, suffix);
        return stepId;
    }

    private UUID seedTask(UUID requestId, UUID stepId, String suffix) {
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_tasks (
                    task_id, tenant_id, request_id, step_id,
                    assignee_user_id, status)
                VALUES (?, 42, ?, ?, 17, 'PENDING')
                """, taskId, requestId, stepId);
        return taskId;
    }

    private void insertOutbox(UUID requestId, String scope) {
        jdbc.update("""
                INSERT INTO apr_integration_outbox (
                    outbox_id, event_id, tenant_id, request_id,
                    event_type, payload, payload_sha256, status,
                    recovery_auditor_assignment_state,
                    management_resource_set_key)
                VALUES (?, ?, 42, ?, 'approval.request.submitted', '{}'::jsonb,
                        ?, 'PENDING', 'PENDING', ?)
                """, UUID.randomUUID(), UUID.randomUUID(), requestId,
                "c".repeat(64), scope);
    }

    private int count(String table, long tenantId, String scope) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table
                        + " WHERE tenant_id = ? AND management_resource_set_key = ?",
                Integer.class, tenantId, scope);
    }

    private ApprovalRequestContext.Actor actor(long userId) {
        return new ApprovalRequestContext.Actor(
                userId, 42L, null, "User", Set.of(), Set.of());
    }

    private void setDecision(String scopeKey, String route) {
        ApprovalDecisionRevisionContext.set(
                "decision-revision", OffsetDateTime.now().plusMinutes(5),
                "approvals.work", scopeKey, route, "110");
    }

    private void assertWorkCatalogExcludesBundleB() {
        assertThat(queries.publishedForms(42))
                .extracting(ApprovalDtos.FormSummary::formId)
                .contains(FORM_A)
                .doesNotContain(FORM_B);
        assertThatThrownBy(() -> queries.publishedTemplate(42, WORKFLOW_B))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
