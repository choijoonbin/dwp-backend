package com.dwp.services.approval.policyimpact;

import static org.assertj.core.api.Assertions.*;
import static com.dwp.services.approval.policyimpact.ApprovalPolicyImpactDtos.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalPolicyImpactRuntimeBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ApprovalPolicyImpactPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner", "apr15-policyimpact");
    JdbcTemplate jdbc;
    NamedParameterJdbcTemplate named;
    TransactionTemplate transactions;
    ApprovalPolicyImpactRepository repo;
    ApprovalPolicyImpactRuntimeBridge runtime;
    UUID policy, workflow, workflowVersion, formVersion;
    ApprovalPolicyImpactAuthority.Window window;
    @BeforeEach void setup() {
        var source = new PGSimpleDataSource(); source.setURL(PG.getJdbcUrl()); source.setUser(PG.getUsername()); source.setPassword(PG.getPassword());
        var migration = Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(false).load();
        new JdbcTemplate(source).execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        migration.clean(); migration.migrate();
        jdbc = new JdbcTemplate(source); named = new NamedParameterJdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.queryForObject("SELECT seed_approval_tenant(42)", Object.class);
        workflow = jdbc.queryForObject("SELECT workflow_id FROM apr_workflow_definitions WHERE tenant_id=42 AND workflow_key='ACCESS_EXCEPTION'", UUID.class);
        workflowVersion = jdbc.queryForObject("SELECT v.workflow_version_id FROM apr_workflow_versions v JOIN apr_workflow_definitions w "
                + "ON w.tenant_id=v.tenant_id AND w.workflow_id=v.workflow_id AND w.current_version=v.version_number WHERE w.workflow_id=?", UUID.class, workflow);
        formVersion = jdbc.queryForObject("SELECT form_version_id FROM apr_form_versions WHERE tenant_id=42 LIMIT 1", UUID.class);
        policy = jdbc.queryForObject("SELECT policy_id FROM apr_policy_rules WHERE tenant_id=42 AND policy_key='REQUIRE_REJECT_REASON'", UUID.class);
        var mapper = new ObjectMapper().findAndRegisterModules();
        repo = new ApprovalPolicyImpactRepository(named, mapper); runtime = new ApprovalPolicyImpactRuntimeBridge(named, mapper);
        window = new ApprovalPolicyImpactAuthority.Window(42, 99, "RS_APPROVALS", "context", "opaque", "psr-" + "a".repeat(64),
                ApprovalPolicyImpactAuthority.ROUTE, "110", Instant.now().plusSeconds(60), "NORMAL", false, false, true,
                ApprovalPolicyImpactAuthorityTest.grants("RS_APPROVALS"));
    }
    ApprovalPolicyImpactFacade facade(ApprovalPolicyImpactAuthority.Port port) {
        return new ApprovalPolicyImpactFacade(repo, new ApprovalPolicyImpactAuthority(port, Clock.systemUTC()), runtime, transactions);
    }
    void pending() {
        jdbc.update("UPDATE apr_policy_rules SET pending_enforcement_mode='BLOCK',pending_severity='HIGH',pending_lifecycle_state='ACTIVE',"
                + "pending_rule_payload='{\"minimumLength\":20}'::jsonb,pending_by=99,pending_at=now(),pending_change_reason='Independent preview',version=version+1 WHERE policy_id=?", policy);
    }
    long version() { return jdbc.queryForObject("SELECT version FROM apr_policy_rules WHERE policy_id=?", Long.class, policy); }
    UUID request() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,requester_user_id,status) "
                + "VALUES(?,42,?,?,?,'Private title must not be projected',99,'DRAFT')", id, "PI-" + id, workflowVersion, formVersion);
        return id;
    }
    Map<String, String> state() {
        var result = new java.util.TreeMap<String, String>();
        for (String table : java.util.List.of("apr_tenants", "apr_policy_rules", "apr_policy_rule_versions", "apr_policy_version_source_records", "apr_requests", "apr_tasks", "apr_steps",
                "apr_quorum_stage_runtime", "apr_quorum_votes", "apr_quorum_sla_timers", "apr_integration_outbox", "sys_audit_outbox"))
            result.put(table, jdbc.queryForObject("SELECT md5(coalesce(string_agg(to_jsonb(x)::text,'' ORDER BY to_jsonb(x)::text),'')) FROM " + table + " x", String.class));
        return result;
    }
    @Test void actualPendingEvaluationHasNoWritesAndContainsNoRequestContent() throws Exception {
        pending(); UUID request = request(); String before = state().toString();
        Result result = facade(() -> window).preview(policy, version());
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.requests().counts().constraintChanged()).isEqualTo(1);
        assertThat(result.requests().items()).extracting(item -> item.id()).contains(request);
        assertThat(result.policy().publishedVersionId()).isNotNull();
        assertThat(result.policy().metadataProvenance()).isEqualTo("LEGACY_CAPTURE_TIME");
        assertThat(result.policy().capturedAt()).isNotNull();
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(result)).doesNotContain("Private title", "requester_user_id", "email", "payload");
        assertThat(state().toString()).isEqualTo(before);
    }
    @Test void thousandAndOneIsExplicitObservedLowerBoundNotSafeZeroOrSilentFullResult() {
        pending();
        jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,requester_user_id,status) "
                + "SELECT gen_random_uuid(),42,'PI-BATCH-'||n,?,?,'Private',99,'DRAFT' FROM generate_series(1,1001) n", workflowVersion, formVersion);
        String before = state().toString(); Result result = facade(() -> window).preview(policy, version());
        assertThat(result.status()).isEqualTo("PARTIAL"); assertThat(result.requests().counts().examined()).isEqualTo(1000);
        assertThat(result.requests().counts().constraintChanged()).isEqualTo(1000);
        assertThat(result.requests().counts().complete()).isFalse(); assertThat(result.requests().counts().countKind()).isEqualTo("OBSERVED_LOWER_BOUND");
        assertThat(result.requests().items()).hasSize(100); assertThat(state().toString()).isEqualTo(before);
    }
    @Test void currentCatalogDraftDoesNotHideRequestPinnedToOlderPublishedVersion() {
        pending(); UUID request = request();
        jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,definition_sha256,lifecycle_state) "
                + "SELECT gen_random_uuid(),tenant_id,workflow_id,2,definition,definition_sha256,'DRAFT' FROM apr_workflow_versions WHERE workflow_version_id=?", workflowVersion);
        jdbc.update("UPDATE apr_workflow_definitions SET current_version=2,lifecycle_state='DRAFT' WHERE workflow_id=?", workflow);
        Result result = facade(() -> window).preview(policy, version());
        assertThat(result.requests().items()).extracting(item -> item.id()).contains(request);
        assertThat(result.workflows().items()).extracting(item -> item.workflowVersionId()).doesNotContain(workflowVersion);
    }
    @Test void absentProposalAndUnknownDefinitionAreNotInventedSuccessOrZero() {
        assertThat(facade(() -> window).preview(policy, version()).status()).isEqualTo("NO_PROPOSAL");
        pending(); request();
        jdbc.update("UPDATE apr_workflow_versions SET definition='{\"schemaContract\":\"UNKNOWN\"}'::jsonb WHERE workflow_version_id=?", workflowVersion);
        Result result = facade(() -> window).preview(policy, version());
        assertThat(result.requests().counts().unknown()).isEqualTo(1); assertThat(result.requests().counts().complete()).isFalse();
        assertThat(result.requests().counts().countKind()).isEqualTo("OBSERVED_LOWER_BOUND");
    }
    @Test void staleCasOtherTenantAndOtherResourceSetCannotHealOrFallback() {
        pending(); long captured = version(); String before = state().toString();
        assertThatThrownBy(() -> facade(() -> window).preview(policy, captured - 1)).isInstanceOf(BaseException.class);
        var other = scope(42, "RS_OTHER");
        assertThatThrownBy(() -> facade(() -> other).preview(policy, captured)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> facade(() -> scope(43, "RS_APPROVALS")).preview(policy, captured))
                .isInstanceOf(BaseException.class).extracting(error -> ((BaseException) error).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(state().toString()).isEqualTo(before);
    }
    @Test void freshReadCommittedDetectsPendingSaveAfterRepeatableReadSnapshot() {
        pending(); long captured = version(); var calls = new AtomicInteger();
        var writer = new TransactionTemplate(transactions.getTransactionManager()); writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var test = facade(() -> {
            if (calls.incrementAndGet() == 2) writer.executeWithoutResult(tx -> jdbc.update("UPDATE apr_policy_rules SET version=version+1 WHERE policy_id=?", policy));
            return window;
        });
        assertThatThrownBy(() -> test.preview(policy, captured)).isInstanceOf(BaseException.class);
        assertThat(version()).isEqualTo(captured + 1);
    }
    @Test void invalidStoredFractionIsUnavailableWithoutAuditOrPolicyMutation() {
        pending(); jdbc.update("UPDATE apr_policy_rules SET pending_rule_payload='{\"minimumLength\":12.5}'::jsonb WHERE policy_id=?", policy);
        String before = state().toString(); assertThatThrownBy(() -> facade(() -> window).preview(policy, version())).isInstanceOf(BaseException.class);
        assertThat(state().toString()).isEqualTo(before);
    }
    @Test void missingOrDivergentHistoryIsUnavailableAndNeverProvisionedOnRead() {
        jdbc.update("DELETE FROM apr_policy_rule_versions WHERE policy_id=?", policy);
        String before = state().toString();
        assertThatThrownBy(() -> facade(() -> window).preview(policy, version()))
                .isInstanceOf(BaseException.class).extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        assertThat(state().toString()).isEqualTo(before);
        jdbc.update("""
                INSERT INTO apr_policy_rule_versions(policy_version_id,tenant_id,policy_id,version_number,
                  enforcement_mode,severity,lifecycle_state,rule_payload,change_reason,published_at,review_comment)
                SELECT gen_random_uuid(),tenant_id,policy_id,1,enforcement_mode,severity,lifecycle_state,
                  '{"minimumLength":20}'::jsonb,'Divergent test history',now(),'Test source corruption'
                  FROM apr_policy_rules WHERE policy_id=?
                """, policy);
        before = state().toString();
        assertThatThrownBy(() -> facade(() -> window).preview(policy, version()))
                .isInstanceOf(BaseException.class).extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        assertThat(state().toString()).isEqualTo(before);
    }
    @Test void taskScopeIsInheritedThroughExactTenantRequestAndStepAndNeverAnotherTenant() {
        pending(); UUID own = request(); UUID ownTask = task(42, own);
        jdbc.queryForObject("SELECT seed_approval_tenant(43)", Object.class);
        UUID foreign = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,requester_user_id,status)
                SELECT ?,43,'FOREIGN-PRIVATE',v.workflow_version_id,f.form_version_id,'Never projected',99,'DRAFT'
                  FROM apr_workflow_versions v JOIN apr_workflow_definitions w ON w.tenant_id=v.tenant_id AND w.workflow_id=v.workflow_id
                  JOIN apr_form_versions f ON f.tenant_id=v.tenant_id
                 WHERE v.tenant_id=43 AND w.workflow_key='ACCESS_EXCEPTION' LIMIT 1
                """, foreign);
        UUID foreignTask = task(43, foreign); String before = state().toString();
        Result result = facade(() -> window).preview(policy, version());
        assertThat(result.tasks().counts().examined()).isEqualTo(1);
        assertThat(result.tasks().items()).extracting(Item::id).containsExactly(ownTask).doesNotContain(foreignTask);
        assertThat(result.requests().items()).extracting(Item::id).containsExactly(own).doesNotContain(foreign);
        assertThat(state().toString()).isEqualTo(before);
    }
    private UUID task(long tenant, UUID request) {
        UUID step = UUID.randomUUID(), task = UUID.randomUUID();
        jdbc.update("INSERT INTO apr_steps(step_id,tenant_id,request_id,step_key,step_name,sequence_number) "
                + "VALUES(?,?,?,'REVIEW','Review',1)", step, tenant, request);
        jdbc.update("INSERT INTO apr_tasks(task_id,tenant_id,request_id,step_id,candidate_role) VALUES(?,?,?,?,'FINANCE_REVIEWER')",
                task, tenant, request, step);
        return task;
    }
    private ApprovalPolicyImpactAuthority.Window scope(long tenant, String resourceSet) {
        return new ApprovalPolicyImpactAuthority.Window(tenant, window.actorId(), resourceSet, window.contextKey(),
                window.contextScopeKey(), window.decisionRevision(), window.routeKey(), window.rolloutState(),
                window.validUntil(), window.accessMode(), false, false, true, ApprovalPolicyImpactAuthorityTest.grants(resourceSet));
    }
}
