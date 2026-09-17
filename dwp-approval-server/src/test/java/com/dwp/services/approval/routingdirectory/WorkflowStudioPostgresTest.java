package com.dwp.services.approval.routingdirectory;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.Mode;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.Rule;
import static com.dwp.services.approval.routingdirectory.WorkflowStudioModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorumDefinition;
import com.dwp.services.approval.security.ApprovalFormManagementScopeTestSupport;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class WorkflowStudioPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private WorkflowStudioService service;
    private UUID workflowId;
    private ApprovalWorkflowQuorumDefinition original;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        Flyway flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc.queryForObject("SELECT seed_approval_tenant(?)", Object.class, 42L);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(source);
        ApprovalDocumentCanonical canonical = new ApprovalDocumentCanonical(mapper);
        service = new WorkflowStudioService(new RoutingDirectoryRepository(named, canonical),
                new WorkflowStudioRepository(named, canonical));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        context("RS_APPROVALS");
        workflowId = UUID.randomUUID();
        original = ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(stage("MANAGER", List.of())));
        jdbc.update("""
                INSERT INTO apr_workflow_definitions(workflow_id,tenant_id,workflow_key,name_ko,name_en,
                    description_ko,description_en,category,data_classification,lifecycle_state,
                    current_version,sla_minutes,allow_self_approval,owner_group_ref,version,
                    created_by,updated_by,management_resource_set_key)
                VALUES(?,42,?,'워크플로','Workflow','스튜디오 회귀','Studio regression','GENERAL',
                    'INTERNAL','DRAFT',1,60,false,'APPROVAL_ADMINS',0,17,17,'RS_APPROVALS')
                """, workflowId, "STUDIO_" + workflowId.toString().replace("-", "").toUpperCase());
        jdbc.update("""
                INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,
                    definition,definition_sha256,lifecycle_state,created_by)
                VALUES(?,42,?,1,?::jsonb,?,'DRAFT',17)
                """, UUID.randomUUID(), workflowId, original.canonicalJson(), original.sha256());
    }

    @AfterEach
    void tearDown() {
        ApprovalRequestContext.clear();
        ApprovalFormManagementScopeTestSupport.clear();
    }

    @Test
    void canvasDryRunDiffAndRetireAreDurableIdempotentAndScopeFenced() {
        ApprovalWorkflowQuorumDefinition candidate = ApprovalWorkflowQuorumDefinition.fromStages(90,
                List.of(stage("MANAGER", List.of()), stage("SECURITY", List.of("MANAGER"))));
        CanvasSave command = new CanvasSave(0L, definition(candidate));
        WorkflowStudio saved = tx(() -> service.save(workflowId, command, "canvas-save"));
        WorkflowStudio replay = tx(() -> service.save(workflowId, command, "canvas-save"));
        assertThat(replay).isEqualTo(saved);
        assertThat(saved.workflowRevision()).isEqualTo(1);
        assertThat(saved.currentVersion()).isEqualTo(2);
        assertThat(saved.current().definitionSha256()).isEqualTo(candidate.sha256());
        assertThat(saved.publishEndpoint()).isEqualTo("/v1/admin/workflows/" + workflowId + "/publish");
        assertThat(saved.runtimeSimulationEndpoint()).endsWith("/simulation");

        DryRunResult dryRun = tx(() -> service.dryRun(workflowId,
                new CanvasDryRun(saved.workflowRevision()), "canvas-dry-run"));
        assertThat(dryRun.topologicalStageKeys()).containsExactly("MANAGER", "SECURITY");
        assertThat(dryRun.structurallyValid()).isTrue();
        assertThat(dryRun.runtimeSimulationRequired()).isTrue();
        assertThat(tx(() -> service.dryRun(workflowId,
                new CanvasDryRun(saved.workflowRevision()), "canvas-dry-run"))).isEqualTo(dryRun);

        assertThat(service.diff(workflowId, 1, 2)).satisfies(diff -> {
            assertThat(diff.addedStages()).containsExactly("SECURITY");
            assertThat(diff.removedStages()).isEmpty();
            assertThat(diff.changedStages()).isEmpty();
        });
        assertThatThrownBy(() -> tx(() -> service.save(workflowId,
                new CanvasSave(0L, definition(candidate)), "canvas-stale")))
                .isInstanceOf(RoutingDirectoryRejected.class);

        Retirement retired = tx(() -> service.retire(workflowId,
                new RetireWorkflow(saved.workflowRevision(), 0L), "canvas-retire"));
        assertThat(retired.lifecycleState()).isEqualTo("RETIRED");
        assertThat(retired.workflowRevision()).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT action FROM apr_workflow_studio_events
                 WHERE workflow_id=? ORDER BY occurred_at,event_id
                """, String.class, workflowId)).containsExactly("SAVE_DRAFT", "DRY_RUN", "RETIRE");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_workflow_studio_events SET action='DRY_RUN' WHERE workflow_id=?
                """, workflowId)).hasRootCauseInstanceOf(java.sql.SQLException.class);

        context("RS_OTHER");
        assertThatThrownBy(() -> service.studio(workflowId))
                .isInstanceOf(RoutingDirectoryRejected.class);
    }

    private ApprovalWorkflowQuorumDefinition.Stage stage(String key, List<String> predecessors) {
        return new ApprovalWorkflowQuorumDefinition.Stage(key, key, "APPROVAL_MANAGER",
                new Rule(Mode.ANY, null), 30, predecessors);
    }

    private Map<String, Object> definition(ApprovalWorkflowQuorumDefinition value) {
        try {
            return mapper.readValue(value.canonicalJson(), new TypeReference<>() { });
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private void context(String scope) {
        ApprovalRequestContext.clear();
        ApprovalFormManagementScopeTestSupport.clear();
        ApprovalRequestContext.set(17L, 42L, UUID.randomUUID(), "Approval Admin",
                Set.of("APPROVAL_ADMIN"), Set.of("ADMIN.APPROVAL_DESIGN:UPDATE"));
        ApprovalFormManagementScopeTestSupport.set("opaque-" + scope.toLowerCase(), scope);
    }

    private <T> T tx(Supplier<T> command) {
        return transactions.execute(ignored -> command.get());
    }
}
