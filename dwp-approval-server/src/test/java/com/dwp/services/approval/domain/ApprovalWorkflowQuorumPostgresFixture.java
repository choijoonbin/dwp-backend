package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class ApprovalWorkflowQuorumPostgresFixture implements ApprovalWorkflowQuorumAuthority {
    static final long TENANT = 42;
    static final long REQUESTER = 99;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    ApprovalWorkflowQuorumRuntime runtime;
    ApprovalWorkflowQuorumSlaRuntime sla;
    ApprovalWorkflowQuorumReadOnlySimulation simulation;
    UUID request;
    UUID workflow;
    UUID workflowVersion;
    UUID delegation;
    Pins pins;
    final Set<Long> revoked = ConcurrentHashMap.newKeySet();
    final Set<Long> withdrawnRoles = ConcurrentHashMap.newKeySet();
    List<Long> pool = List.of(101L, 102L, 103L);
    boolean complete = true;
    boolean truncated;
    boolean unknown;
    java.util.function.Consumer<Snapshot> onVoter = snapshot -> { };

    void initialize(PostgreSQLContainer<?> postgres) {
        var source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        // Reset only the disposable test database, never any live DWP database.
        var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(false).load();
        new JdbcTemplate(source).execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        new JdbcTemplate(source).execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var named = new NamedParameterJdbcTemplate(source);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var audit = new AuditOutboxRecorder(named, mapper, "dwp-approval-server", "test", "test");
        runtime = new ApprovalWorkflowQuorumRuntime(named, mapper, tx, this, audit);
        sla = new ApprovalWorkflowQuorumSlaRuntime(named, mapper, tx, this, audit);
        simulation = new ApprovalWorkflowQuorumReadOnlySimulation(named, mapper, tx, this);
        jdbc.queryForObject("SELECT seed_approval_tenant(?)", Object.class, TENANT);
        workflow = jdbc.queryForObject("SELECT workflow_id FROM apr_workflow_definitions WHERE tenant_id=42 "
                + "AND workflow_key='ACCESS_EXCEPTION'", UUID.class);
    }

    void prepare(ApprovalWorkflowQuorumDefinition definition) {
        request = UUID.randomUUID();
        workflowVersion = UUID.randomUUID();
        jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,"
                + "definition_sha256,lifecycle_state,published_at,published_by) VALUES(?,42,?,90,?::jsonb,?,'PUBLISHED',now(),99)",
                workflowVersion, workflow, definition.canonicalJson(), definition.sha256());
        UUID form = jdbc.queryForObject("SELECT form_version_id FROM apr_form_versions WHERE tenant_id=42 "
                + "ORDER BY version_number DESC,form_version_id LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,"
                + "requester_user_id,requester_person_public_id,status,submitted_at,due_at) "
                + "VALUES(?,42,?,?,?,'Quorum',99,?,'IN_REVIEW',now(),now()+interval '60 minutes')",
                request, "QUORUM-" + request, workflowVersion, form, person(REQUESTER));
        jdbc.update("INSERT INTO apr_request_payloads(tenant_id,request_id,payload,payload_sha256,schema_version) "
                + "VALUES(42,?,'{}'::jsonb,?,1)", request, "a".repeat(64));
        pins = runtime.canonicalPins(TENANT, request, definition);
    }

    void start(ApprovalWorkflowQuorumDefinition definition) { prepare(definition); runtime.start(TENANT, request, pins, definition); }

    void prepareDraft(ApprovalWorkflowQuorumDefinition definition) {
        prepare(definition);
        UUID form = jdbc.queryForObject("""
                SELECT version.form_version_id FROM apr_form_workflow_bindings binding
                  JOIN apr_forms form ON form.tenant_id=binding.tenant_id AND form.form_id=binding.form_id
                  JOIN apr_form_versions version ON version.tenant_id=form.tenant_id AND version.form_id=form.form_id
                   AND version.version_number=form.current_version
                 WHERE binding.tenant_id=42 AND binding.workflow_id=? AND binding.lifecycle_state='ACTIVE' LIMIT 1
                """, UUID.class, workflow);
        jdbc.update("UPDATE apr_workflow_definitions SET current_version=90,sla_minutes=? WHERE workflow_id=?",
                definition.slaMinutes(), workflow);
        jdbc.update("UPDATE apr_requests SET status='DRAFT',submitted_at=NULL,due_at=NULL,form_version_id=? WHERE request_id=?", form, request);
        jdbc.update("UPDATE apr_request_payloads SET payload='{\"summary\":\"Runtime submission\",\"amount\":20}'::jsonb WHERE request_id=?", request);
        var named = new NamedParameterJdbcTemplate(jdbc);
        var store = new ApprovalWorkflowQuorumRuntimeStore(named, new ObjectMapper().findAndRegisterModules());
        pins = store.context(TENANT, request, definition, store.policy(TENANT, request, false), false).pins();
    }

    void bindTypedForm(ApprovalWorkflowQuorumDefinition definition) {
        var schema = new ApprovalFormSchemaV2Compiler().compile(ApprovalFormSchemaV2CompilerTest.schema(
                ApprovalFormSchemaV2CompilerTest.field("summary", "TEXT"),
                ApprovalFormSchemaV2CompilerTest.field("amount", "NUMBER")));
        UUID version = UUID.randomUUID();
        UUID form = jdbc.queryForObject("SELECT form_id FROM apr_form_versions WHERE tenant_id=42 AND form_version_id="
                + "(SELECT form_version_id FROM apr_requests WHERE request_id=?)", UUID.class, request);
        jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,schema_payload,schema_sha256,"
                + "lifecycle_state,published_at,published_by) VALUES(?,42,?,99,?::jsonb,?,'PUBLISHED',now(),99)",
                version, form, schema.canonicalJson(), schema.sha256());
        jdbc.update("UPDATE apr_requests SET form_version_id=? WHERE request_id=?", version, request);
        jdbc.update("UPDATE apr_request_payloads SET payload='{\"summary\":\"Runtime submission\",\"amount\":\"20\"}'::jsonb WHERE request_id=?", request);
        var store = new ApprovalWorkflowQuorumRuntimeStore(new NamedParameterJdbcTemplate(jdbc), new ObjectMapper().findAndRegisterModules());
        pins = store.context(TENANT, request, definition, store.policy(TENANT, request, false), false).pins();
    }

    ApprovalWorkflowQuorumRuntime.VoteCommand command(String stage, long actor, long principal, Decision decision) {
        var row = jdbc.queryForMap("SELECT runtime.step_id,runtime.generation,runtime.version,candidate.task_id,task.version task_version "
                + "FROM apr_quorum_stage_runtime runtime JOIN apr_quorum_candidates candidate "
                + "ON candidate.tenant_id=runtime.tenant_id AND candidate.request_id=runtime.request_id AND candidate.step_id=runtime.step_id "
                + "JOIN apr_tasks task ON task.task_id=candidate.task_id WHERE runtime.tenant_id=42 AND runtime.request_id=? "
                + "AND runtime.stage_key=? AND candidate.principal_user_id=?", request, stage, principal);
        return new ApprovalWorkflowQuorumRuntime.VoteCommand(TENANT, request, (UUID) row.get("step_id"), 1,
                (UUID) row.get("task_id"), ((Number) row.get("task_version")).longValue(), ((Number) row.get("version")).longValue(),
                pins, actor, principal, decision, decision == Decision.REJECT ? "Policy risk confirmed" : "");
    }

    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    String status(String stage) { return jdbc.queryForObject("SELECT status FROM apr_quorum_stage_runtime WHERE tenant_id=42 "
            + "AND request_id=? AND stage_key=?", String.class, request, stage); }

    UUID delegate(long actor, long principal) {
        delegation = UUID.randomUUID();
        jdbc.update("INSERT INTO apr_delegations(delegation_id,tenant_id,delegator_user_id,delegate_user_id,scope_type,"
                + "starts_at,ends_at,reason,delegate_person_public_id,delegate_display_name,delegated_role_codes,workflow_id,workflow_key) "
                + "VALUES(?,42,?,?,'WORKFLOW',now()-interval '1 hour',now()+interval '1 hour','Test delegation',?,'Delegate',"
                + "'[\"FINANCE_REVIEWER\"]'::jsonb,?,'ACCESS_EXCEPTION')", delegation, principal, actor, person(actor), workflow);
        return delegation;
    }

    void dueTimers() { jdbc.update("UPDATE apr_quorum_sla_timers SET due_at=now()-interval '1 minute' WHERE request_id=?", request); }

    static UUID person(long user) { return UUID.nameUUIDFromBytes(("quorum-person:" + user).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    Subject subject(long user) { return new Subject(TENANT, user, person(user), IdentityPlane.TENANT,
            !revoked.contains(user), withdrawnRoles.contains(user) ? Set.of("OTHER_REVIEWER") : Set.of("FINANCE_REVIEWER"),
            !revoked.contains(user)); }

    @Override public CandidatePool candidates(Pins pins, UUID requestId, ApprovalWorkflowQuorumDefinition.Stage stage, Instant now) {
        if (unknown) return null;
        return new CandidatePool(TENANT, pins.workflowVersionId(), stage.candidateRole(), pool.stream().map(this::subject).toList(),
                "test-authority-v1", complete, truncated, now, now.plusSeconds(60));
    }

    @Override public CurrentAuthority voter(Snapshot snapshot, long actor, long principal, Instant now) {
        onVoter.accept(snapshot);
        if (unknown) return null;
        Delegation grant = actor == principal ? null : new Delegation(delegation, TENANT, workflowVersion, principal, actor,
                "FINANCE_REVIEWER", true, now.minusSeconds(3600), now.plusSeconds(3600));
        return new CurrentAuthority(AccessMode.NORMAL, "test-authority-v2", now, now.plusSeconds(60),
                subject(actor), subject(principal), grant);
    }

    static ApprovalWorkflowQuorumDefinition one(Mode mode, Integer value) {
        return ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(stage("FINANCE", mode, value, List.of())));
    }
    static ApprovalWorkflowQuorumDefinition.Stage stage(String key, Mode mode, Integer value, List<String> predecessors) {
        return new ApprovalWorkflowQuorumDefinition.Stage(key, key, "FINANCE_REVIEWER", new Rule(mode, value), 15, predecessors);
    }
}
