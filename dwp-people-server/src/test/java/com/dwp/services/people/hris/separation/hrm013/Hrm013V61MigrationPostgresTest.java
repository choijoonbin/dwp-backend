package com.dwp.services.people.hris.separation.hrm013;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class Hrm013V61MigrationPostgresTest {
    private static final long TENANT_ONE = 61_001L;
    private static final long TENANT_TWO = 61_002L;
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);
    private static final String SHA_D = "d".repeat(64);
    private static final List<String> TABLES = List.of(
            "ppl_hrm013_callback_proof_replays",
            "ppl_separation_callback_authorities",
            "ppl_separation_callbacks",
            "ppl_separation_cases",
            "ppl_separation_tasks",
            "ppl_separation_waiver_request_outbox");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private Flyway migrated;
    private JdbcTemplate owner;

    @AfterEach
    void clean() {
        dropRuntimeRole();
        if (migrated != null) migrated.clean();
        dropRuntimeRole();
    }

    @Test
    void cleanV61AndV60UpgradeCreateExactlyTheSixTableBoundary() {
        DataSource source = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(source);
        migrated = flyway(source, "61");
        migrated.clean();
        migrated.migrate();

        assertThat(migrated.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("61"));
        assertThat(owner.queryForList("""
                SELECT tablename FROM pg_tables
                 WHERE schemaname='public' AND tablename IN (
                    'ppl_hrm013_callback_proof_replays',
                    'ppl_separation_callback_authorities',
                    'ppl_separation_callbacks','ppl_separation_cases',
                    'ppl_separation_tasks','ppl_separation_waiver_request_outbox')
                 ORDER BY tablename
                """, String.class))
                .containsExactlyElementsOf(TABLES);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_class
                 WHERE relname IN (
                    'ppl_hrm013_callback_proof_replays',
                    'ppl_separation_callback_authorities',
                    'ppl_separation_callbacks','ppl_separation_cases',
                    'ppl_separation_tasks','ppl_separation_waiver_request_outbox')
                   AND relrowsecurity AND relforcerowsecurity
                """, Integer.class)).isEqualTo(6);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_policies
                 WHERE schemaname='public' AND tablename IN (
                    'ppl_hrm013_callback_proof_replays',
                    'ppl_separation_callback_authorities',
                    'ppl_separation_callbacks','ppl_separation_cases',
                    'ppl_separation_tasks','ppl_separation_waiver_request_outbox')
                   AND qual LIKE '%current_setting(''dwp.tenant_id''::text, true)%'
                   AND with_check LIKE '%current_setting(''dwp.tenant_id''::text, true)%'
                """, Integer.class)).isEqualTo(6);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema='public' AND table_name='ppl_assignment_events'
                   AND column_name IN (
                       'separation_case_id','supersedes_assignment_event_id',
                       'reverses_assignment_event_id')
                """, Integer.class)).isEqualTo(3);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_constraint
                 WHERE conname='fk_ppl_separation_callback_authorities_supersedes'
                   AND confkey IS NOT NULL
                """, Integer.class)).isOne();

        migrated.clean();
        Flyway throughV60 = flyway(source, "60");
        throughV60.migrate();
        ensureTenant(owner, TENANT_ONE, "upgrade");
        long person = insertPerson(owner, TENANT_ONE, "upgrade");
        UUID event = UUID.randomUUID();
        owner.update("""
                INSERT INTO ppl_assignment_events(
                    public_id,tenant_id,subject_person_id,event_type,effective_date,
                    command_context,event_payload,input_snapshot_hash,
                    expected_owner_version,correlation_id,created_by,updated_by)
                VALUES(?, ?, ?, 'HIRE', DATE '2026-10-26', '{}'::jsonb,
                    '{}'::jsonb, ?, 0, ?, 1, 1)
                """, event, TENANT_ONE, person, SHA_A, UUID.randomUUID());

        migrated = flyway(source, "61");
        assertThat(migrated.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(migrated.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("61"));
        assertThat(owner.queryForMap("""
                SELECT public_id,separation_case_id,supersedes_assignment_event_id,
                       reverses_assignment_event_id
                  FROM ppl_assignment_events WHERE public_id=?
                """, event))
                .containsEntry("public_id", event)
                .containsEntry("separation_case_id", null)
                .containsEntry("supersedes_assignment_event_id", null)
                .containsEntry("reverses_assignment_event_id", null);
    }

    @Test
    void schemaEnforcesReplayFenceCallbackAndForwardLineageInvariants() throws Exception {
        owner = new JdbcTemplate(migratedSource());
        Graph graph = insertGraph(owner, TENANT_ONE, "one");

        Instant reserved = Instant.now().minusSeconds(1);
        Instant expires = reserved.plusSeconds(30);
        owner.update("""
                INSERT INTO ppl_hrm013_callback_proof_replays(
                    tenant_id,binding_id,caller_workload_id,token_kind,
                    token_digest,expires_at,reserved_at)
                VALUES(?, 'SVC-PEP-HRM-008', 'dwp-time-server', 'JTI', ?, ?, ?),
                      (?, 'SVC-PEP-HRM-008', 'dwp-time-server', 'REQUEST_NONCE', ?, ?, ?)
                """, TENANT_ONE, SHA_A, java.sql.Timestamp.from(expires),
                java.sql.Timestamp.from(reserved), TENANT_ONE, SHA_B,
                java.sql.Timestamp.from(expires), java.sql.Timestamp.from(reserved));
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM ppl_hrm013_callback_proof_replays",
                Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> owner.update("""
                INSERT INTO ppl_hrm013_callback_proof_replays(
                    tenant_id,binding_id,caller_workload_id,token_kind,
                    token_digest,expires_at,reserved_at)
                VALUES(?, 'SVC-PEP-HRM-009', 'dwp-payroll-server', 'JTI', ?, ?, ?)
                """, TENANT_ONE, SHA_A, java.sql.Timestamp.from(expires),
                java.sql.Timestamp.from(reserved)))
                .isInstanceOf(DataAccessException.class);

        byte[] request = "{\"approvalRequestId\":\"bounded\"}"
                .getBytes(StandardCharsets.UTF_8);
        UUID approvalRequest = UUID.randomUUID();
        UUID outboxPublic = UUID.randomUUID();
        owner.update("""
                INSERT INTO ppl_separation_waiver_request_outbox(
                    public_id,tenant_id,separation_case_id,separation_task_id,
                    approval_request_id,case_public_id,task_public_id,
                    request_revision,case_maker_public_id,
                    requested_by_actor_public_id,reason_code,task_owner_revision,
                    task_version,exact_request_body,request_body_sha256,
                    approval_request_payload_sha256,command_digest,correlation_id,
                    available_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,1,?,?,'POLICY_EXCEPTION',1,0,?,?,?,?,?,now(),now(),now())
                """, outboxPublic, TENANT_ONE, graph.caseId(), graph.taskId(),
                approvalRequest, graph.casePublicId(), graph.taskPublicId(),
                UUID.randomUUID(), UUID.randomUUID(), request, sha256(request),
                SHA_B, SHA_C, UUID.randomUUID());
        assertThatThrownBy(() -> owner.update("""
                UPDATE ppl_separation_waiver_request_outbox
                   SET command_digest=?,updated_at=now()
                 WHERE tenant_id=? AND approval_request_id=?
                """, SHA_D, TENANT_ONE, approvalRequest))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("fence and exact bytes are immutable");

        UUID authority = UUID.randomUUID();
        owner.update("""
                INSERT INTO ppl_separation_callback_authorities(
                    public_id,tenant_id,case_public_id,task_public_id,
                    separation_case_id,separation_task_id,callback_type,
                    caller_workload_id,binding_id,audience,purpose_code,task_type,
                    tenant_grant_revision,authorization_revision,
                    expected_next_owner_revision,correlation_id,
                    originating_payload_sha256,authority_revision,decision,
                    authority_digest,created_at)
                VALUES(?,?,?,?,?,?,'TASK_RECEIPT','dwp-time-server',
                    'SVC-PEP-HRM-008','dwp-people-server',
                    'HRIS_SEPARATION_TIME_TASK_RECEIPT','TIME_FINALIZATION',
                    4,6,1,?,?,1,'ADMIT',?,now())
                """, authority, TENANT_ONE, graph.casePublicId(), graph.taskPublicId(),
                graph.caseId(), graph.taskId(), UUID.randomUUID(), SHA_B, SHA_C);

        byte[] response = "{\"status\":\"ACCEPTED\"}".getBytes(StandardCharsets.UTF_8);
        UUID callback = UUID.randomUUID();
        owner.update("""
                INSERT INTO ppl_separation_callbacks(
                    tenant_id,separation_case_id,separation_task_id,callback_type,
                    callback_value,caller_workload_id,callback_event_id,
                    external_receipt_public_id,owner_revision,tenant_grant_revision,
                    authorization_revision,payload_hash,originating_payload_sha256,
                    command_digest,request_body_sha256,audience,purpose_code,
                    correlation_id,receipt_public_id,exact_response_body,
                    response_body_sha256,outcome,occurred_at,received_at)
                VALUES(?,?,?,'TASK_RECEIPT','COMPLETED','dwp-time-server',?,?,
                    1,4,6,?,?,?,?,'dwp-people-server',
                    'HRIS_SEPARATION_TIME_TASK_RECEIPT',?,?,?,?,'ACCEPTED',now(),now())
                """, TENANT_ONE, graph.caseId(), graph.taskId(), callback,
                UUID.randomUUID(), SHA_A, SHA_B, SHA_C, SHA_D, UUID.randomUUID(),
                UUID.randomUUID(), response, sha256(response));
        assertThatThrownBy(() -> owner.update("""
                UPDATE ppl_separation_callbacks SET outcome='QUARANTINED'
                 WHERE tenant_id=? AND callback_event_id=?
                """, TENANT_ONE, callback))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("receipt evidence are immutable");

        long termination = insertAssignmentEvent(owner, graph, "TERMINATION", null, null);
        insertAssignmentEvent(owner, graph, "REHIRE", null, termination);
        insertAssignmentEvent(owner, graph, "CORRECTION", termination, null);
        assertThatThrownBy(() -> insertAssignmentEvent(owner, graph, "LEAVE", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertAssignmentEvent(owner, graph, "REHIRE", null, null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void nullableGovernanceEvidenceCannotBypassTerminalChecks() throws Exception {
        owner = new JdbcTemplate(migratedSource());
        Graph graph = insertGraph(owner, TENANT_ONE, "nullable-evidence");
        UUID maker = UUID.randomUUID();
        UUID approvalActor = UUID.randomUUID();

        assertThatThrownBy(() -> owner.update("""
                INSERT INTO ppl_separation_cases(
                    public_id,tenant_id,worker_id,work_relationship_id,separation_type,
                    reason_code,effective_date,last_working_date,lifecycle_state,
                    input_snapshot_hash,task_template_public_id,task_template_revision,
                    task_template_hash,approval_request_id,approval_decision_event_id,
                    approval_decision_revision,approval_payload_hash,
                    maker_principal_public_id,approval_actor_public_id,correlation_id,
                    version,irreversible_at,published_at,created_at,created_by,
                    updated_at,updated_by)
                VALUES(?,?,?,?,'RESIGNATION','VOLUNTARY',DATE '2026-11-01',
                    DATE '2026-10-31','PUBLISHED',?,?,1,?,?,?,NULL,?,?,?,?,0,
                    now(),now(),now(),1,now(),1)
                """, UUID.randomUUID(), TENANT_ONE, graph.workerId(),
                graph.relationshipId(), SHA_A, UUID.randomUUID(), SHA_B,
                UUID.randomUUID(), UUID.randomUUID(), SHA_C, maker,
                approvalActor, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> insertWaivedTaskWithNullableEvidence(
                owner, graph, null, 1L))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertWaivedTaskWithNullableEvidence(
                owner, graph, "APPROVED", null))
                .isInstanceOf(DataAccessException.class);

        WaiverSeed waiver = insertWaiverRequest(owner, graph, "nullable-evidence");
        assertThatThrownBy(() -> insertApprovalCallbackWithNullableEvidence(
                owner, graph, waiver, null, 1L))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertApprovalCallbackWithNullableEvidence(
                owner, graph, waiver, "APPROVED", null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void allSixTablesDenyCrossTenantReadsUnderForcedRls() throws Exception {
        DataSource source = migratedSource();
        owner = new JdbcTemplate(source);
        Graph first = insertGraph(owner, TENANT_ONE, "rls-one");
        Graph second = insertGraph(owner, TENANT_TWO, "rls-two");
        BoundaryEvidence firstEvidence = insertBoundaryEvidence(owner, first, "rls-one");
        BoundaryEvidence secondEvidence = insertBoundaryEvidence(owner, second, "rls-two");

        assertThatThrownBy(() -> insertTaskAuthority(
                owner, second, UUID.randomUUID(), 2, 2,
                firstEvidence.authorityPublicId()))
                .isInstanceOf(DataAccessException.class);
        insertTaskAuthority(owner, second, UUID.randomUUID(), 2, 2,
                secondEvidence.authorityPublicId());
        owner.execute("CREATE ROLE hrm013_runtime LOGIN PASSWORD 'hrm013_runtime_password' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO hrm013_runtime");
        for (String table : TABLES) {
            owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON " + table + " TO hrm013_runtime");
        }

        PGSimpleDataSource runtimeSource = dataSource(
                "hrm013_runtime", "hrm013_runtime_password");
        try (var connection = runtimeSource.getConnection();
             var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT set_config('dwp.tenant_id','" + TENANT_ONE + "',true)");
            for (String table : TABLES) {
                try (var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).as(table).isOne();
                }
            }
            connection.rollback();
        }
    }

    private int insertWaivedTaskWithNullableEvidence(
            JdbcTemplate jdbc, Graph graph, String decision, Long decisionRevision) {
        String taskType = decision == null
                ? "NULL_WAIVER_DECISION_TEST" : "NULL_WAIVER_REVISION_TEST";
        return jdbc.update("""
                INSERT INTO ppl_separation_tasks(
                    public_id,tenant_id,separation_case_id,task_type,
                    owner_workload_id,mandatory,irreversible,lifecycle_state,
                    owner_revision,waiver_decision_event_id,waiver_actor_public_id,
                    waiver_reason_code,waiver_approval_request_id,waiver_decision,
                    waiver_decision_revision,waiver_step_up_evidence_id,payload_hash,
                    version,completed_at,created_at,created_by,updated_at,updated_by)
                VALUES(?,?,?,?,'dwp-time-server',TRUE,FALSE,'WAIVED',1,?,?,
                    'POLICY_EXCEPTION',?,?,?,?,?,0,now(),now(),1,now(),1)
                """, UUID.randomUUID(), graph.tenantId(), graph.caseId(), taskType,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), decision,
                decisionRevision, UUID.randomUUID(), SHA_A);
    }

    private WaiverSeed insertWaiverRequest(
            JdbcTemplate jdbc, Graph graph, String key) throws Exception {
        byte[] request = ("{\"approvalRequestId\":\"" + key + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        WaiverSeed seed = new WaiverSeed(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), SHA_B);
        jdbc.update("""
                INSERT INTO ppl_separation_waiver_request_outbox(
                    public_id,tenant_id,separation_case_id,separation_task_id,
                    approval_request_id,case_public_id,task_public_id,
                    request_revision,case_maker_public_id,
                    requested_by_actor_public_id,reason_code,task_owner_revision,
                    task_version,exact_request_body,request_body_sha256,
                    approval_request_payload_sha256,command_digest,correlation_id,
                    available_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,1,?,?,'POLICY_EXCEPTION',1,0,?,?,?,?,?,
                    now(),now(),now())
                """, UUID.randomUUID(), graph.tenantId(), graph.caseId(), graph.taskId(),
                seed.approvalRequestId(), graph.casePublicId(), graph.taskPublicId(),
                seed.makerPublicId(), seed.requesterPublicId(), request, sha256(request),
                seed.payloadSha256(), SHA_C, seed.correlationId());
        return seed;
    }

    private int insertApprovalCallbackWithNullableEvidence(
            JdbcTemplate jdbc,
            Graph graph,
            WaiverSeed waiver,
            String waiverDecision,
            Long decisionRevision) throws Exception {
        boolean approved = "APPROVED".equals(waiverDecision);
        Instant now = Instant.now();
        byte[] response = "{\"binding\":\"HRM-010\"}"
                .getBytes(StandardCharsets.UTF_8);
        var parameters = new MapSqlParameterSource()
                .addValue("tenant", graph.tenantId())
                .addValue("caseId", graph.caseId())
                .addValue("taskId", graph.taskId())
                .addValue("callbackEvent", UUID.randomUUID())
                .addValue("payloadHash", SHA_A)
                .addValue("originDigest", waiver.payloadSha256())
                .addValue("commandDigest", SHA_C)
                .addValue("requestDigest", SHA_D)
                .addValue("correlation", waiver.correlationId())
                .addValue("approvalRequest", waiver.approvalRequestId())
                .addValue("waiverDecision", waiverDecision)
                .addValue("decisionRevision", decisionRevision)
                .addValue("decisionActor", UUID.randomUUID())
                .addValue("caseMaker", waiver.makerPublicId())
                .addValue("requester", waiver.requesterPublicId())
                .addValue("stepUpId", approved ? UUID.randomUUID() : null)
                .addValue("stepUpAudience", approved ? "dwp-people-server" : null)
                .addValue("stepUpPurpose", approved
                        ? "HRIS_SEPARATION_TASK_WAIVER_DECISION" : null)
                .addValue("stepUpNonceDigest", approved ? SHA_A : null)
                .addValue("stepUpDigest", approved ? SHA_B : null)
                .addValue("stepUpVerified", approved
                        ? java.sql.Timestamp.from(now.minusSeconds(2)) : null)
                .addValue("stepUpConsumed", approved
                        ? java.sql.Timestamp.from(now.minusSeconds(1)) : null)
                .addValue("receiptPublicId", UUID.randomUUID())
                .addValue("response", response)
                .addValue("responseDigest", sha256(response));
        return new NamedParameterJdbcTemplate(jdbc.getDataSource()).update("""
                INSERT INTO ppl_separation_callbacks(
                    tenant_id,separation_case_id,separation_task_id,callback_type,
                    callback_value,caller_workload_id,callback_event_id,
                    owner_revision,tenant_grant_revision,authorization_revision,
                    payload_hash,originating_payload_sha256,command_digest,
                    request_body_sha256,audience,purpose_code,correlation_id,
                    approval_request_id,waiver_decision,decision_revision,
                    decision_actor_public_id,case_maker_public_id,
                    requested_by_actor_public_id,reason_code,step_up_evidence_id,
                    step_up_evidence_audience,step_up_evidence_purpose,
                    step_up_evidence_nonce_sha256,step_up_evidence_sha256,
                    step_up_verified_at,step_up_consumed_at,receipt_public_id,
                    exact_response_body,response_body_sha256,outcome,
                    occurred_at,received_at)
                VALUES(:tenant,:caseId,:taskId,'APPROVAL_DECISION','APPROVED',
                    'dwp-approval-server',:callbackEvent,1,3,5,:payloadHash,
                    :originDigest,:commandDigest,:requestDigest,'dwp-people-server',
                    'HRIS_SEPARATION_TASK_WAIVER_DECISION',:correlation,
                    :approvalRequest,:waiverDecision,:decisionRevision,:decisionActor,
                    :caseMaker,:requester,'POLICY_EXCEPTION',:stepUpId,
                    :stepUpAudience,:stepUpPurpose,:stepUpNonceDigest,:stepUpDigest,
                    :stepUpVerified,:stepUpConsumed,:receiptPublicId,:response,
                    :responseDigest,'ACCEPTED',now(),now())
                """, parameters);
    }

    private BoundaryEvidence insertBoundaryEvidence(
            JdbcTemplate jdbc, Graph graph, String key) throws Exception {
        String tokenDigest = sha256(("proof-" + key).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO ppl_hrm013_callback_proof_replays(
                    tenant_id,binding_id,caller_workload_id,token_kind,
                    token_digest,expires_at,reserved_at)
                VALUES(?,'SVC-PEP-HRM-008','dwp-time-server','JTI',?,
                    now() + INTERVAL '30 seconds',now())
                """, graph.tenantId(), tokenDigest);
        insertWaiverRequest(jdbc, graph, key);
        UUID authorityPublicId = UUID.randomUUID();
        insertTaskAuthority(jdbc, graph, authorityPublicId, 1, 1, null);

        byte[] response = ("{\"binding\":\"" + key + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        jdbc.update("""
                INSERT INTO ppl_separation_callbacks(
                    tenant_id,separation_case_id,separation_task_id,callback_type,
                    callback_value,caller_workload_id,callback_event_id,
                    external_receipt_public_id,owner_revision,tenant_grant_revision,
                    authorization_revision,payload_hash,originating_payload_sha256,
                    command_digest,request_body_sha256,audience,purpose_code,
                    correlation_id,receipt_public_id,exact_response_body,
                    response_body_sha256,outcome,occurred_at,received_at)
                VALUES(?,?,?,'TASK_RECEIPT','COMPLETED','dwp-time-server',?,?,
                    1,4,6,?,?,?,?,'dwp-people-server',
                    'HRIS_SEPARATION_TIME_TASK_RECEIPT',?,?,?,?,'ACCEPTED',now(),now())
                """, graph.tenantId(), graph.caseId(), graph.taskId(),
                UUID.randomUUID(), UUID.randomUUID(), SHA_A, SHA_B, SHA_C, SHA_D,
                UUID.randomUUID(), UUID.randomUUID(), response, sha256(response));
        return new BoundaryEvidence(authorityPublicId);
    }

    private int insertTaskAuthority(
            JdbcTemplate jdbc,
            Graph graph,
            UUID publicId,
            long authorityRevision,
            long expectedOwnerRevision,
            UUID supersedesPublicId) {
        return jdbc.update("""
                INSERT INTO ppl_separation_callback_authorities(
                    public_id,tenant_id,case_public_id,task_public_id,
                    separation_case_id,separation_task_id,callback_type,
                    caller_workload_id,binding_id,audience,purpose_code,task_type,
                    tenant_grant_revision,authorization_revision,
                    expected_next_owner_revision,correlation_id,
                    originating_payload_sha256,authority_revision,decision,
                    supersedes_authority_public_id,authority_digest,created_at)
                VALUES(?,?,?,?,?,?,'TASK_RECEIPT','dwp-time-server',
                    'SVC-PEP-HRM-008','dwp-people-server',
                    'HRIS_SEPARATION_TIME_TASK_RECEIPT','TIME_FINALIZATION',4,6,
                    ?,?,?,?,'ADMIT',?,?,now())
                """, publicId, graph.tenantId(), graph.casePublicId(),
                graph.taskPublicId(), graph.caseId(), graph.taskId(),
                expectedOwnerRevision, UUID.randomUUID(), SHA_B, authorityRevision,
                supersedesPublicId, SHA_C);
    }

    private DataSource migratedSource() {
        DataSource source = dataSource(POSTGRES.getUsername(), POSTGRES.getPassword());
        migrated = flyway(source, "61");
        migrated.clean();
        migrated.migrate();
        return source;
    }

    private Graph insertGraph(JdbcTemplate jdbc, long tenantId, String key) {
        ensureTenant(jdbc, tenantId, key);
        long person = insertPerson(jdbc, tenantId, key);
        long employer = jdbc.queryForObject("""
                INSERT INTO ppl_legal_employers(
                    tenant_id,employer_key,legal_name,created_by,updated_by)
                VALUES(?,?,?,1,1) RETURNING legal_employer_id
                """, Long.class, tenantId, "EMP-" + key, "Employer " + key);
        long worker = jdbc.queryForObject("""
                INSERT INTO ppl_workers(
                    tenant_id,person_id,worker_number,worker_type,worker_status,
                    original_hire_date,created_by,updated_by)
                VALUES(?,?,?,'EMPLOYEE','ACTIVE',DATE '2020-01-01',1,1)
                RETURNING worker_id
                """, Long.class, tenantId, person, "WORKER-" + key);
        long relationship = jdbc.queryForObject("""
                INSERT INTO ppl_work_relationships(
                    tenant_id,relationship_key,worker_id,legal_employer_id,
                    relationship_type,primary_relationship,start_date,created_by,updated_by)
                VALUES(?,?,?,?,'EMPLOYEE',TRUE,DATE '2020-01-01',1,1)
                RETURNING work_relationship_id
                """, Long.class, tenantId, "REL-" + key, worker, employer);
        UUID casePublic = UUID.randomUUID();
        long caseId = jdbc.queryForObject("""
                INSERT INTO ppl_separation_cases(
                    public_id,tenant_id,worker_id,work_relationship_id,separation_type,
                    reason_code,effective_date,last_working_date,input_snapshot_hash,
                    task_template_public_id,task_template_revision,task_template_hash,
                    maker_principal_public_id,correlation_id,created_at,created_by,
                    updated_at,updated_by)
                VALUES(?,?,?,?,'RESIGNATION','VOLUNTARY',DATE '2026-11-01',
                    DATE '2026-10-31',?,?,1,?,?,?,now(),1,now(),1)
                RETURNING separation_case_id
                """, Long.class, casePublic, tenantId, worker, relationship, SHA_A,
                UUID.randomUUID(), SHA_B, UUID.randomUUID(), UUID.randomUUID());
        UUID taskPublic = UUID.randomUUID();
        long taskId = jdbc.queryForObject("""
                INSERT INTO ppl_separation_tasks(
                    public_id,tenant_id,separation_case_id,task_type,
                    owner_workload_id,mandatory,irreversible,owner_revision,
                    created_at,created_by,updated_at,updated_by)
                VALUES(?,?,?,'TIME_FINALIZATION','dwp-time-server',TRUE,TRUE,1,
                    now(),1,now(),1) RETURNING separation_task_id
                """, Long.class, taskPublic, tenantId, caseId);
        return new Graph(
                tenantId, person, worker, relationship,
                caseId, casePublic, taskId, taskPublic);
    }

    private long insertAssignmentEvent(
            JdbcTemplate jdbc, Graph graph, String type, Long supersedes, Long reverses) {
        return jdbc.queryForObject("""
                INSERT INTO ppl_assignment_events(
                    public_id,tenant_id,subject_person_id,event_type,effective_date,
                    command_context,event_payload,input_snapshot_hash,
                    expected_owner_version,correlation_id,created_by,updated_by,
                    separation_case_id,supersedes_assignment_event_id,
                    reverses_assignment_event_id)
                VALUES(?,?,?, ?,DATE '2026-11-01','{}'::jsonb,'{}'::jsonb,?,0,?,1,1,
                    ?,?,?) RETURNING assignment_event_id
                """, Long.class, UUID.randomUUID(), graph.tenantId(), graph.personId(),
                type, SHA_A, UUID.randomUUID(), graph.caseId(), supersedes, reverses);
    }

    private static void ensureTenant(JdbcTemplate jdbc, long tenantId, String key) {
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,
                    data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,?,?,'ACTIVE','local','POOL',1,1)
                """, UUID.randomUUID(), tenantId, "hrm013-" + key, "HRM-013 " + key);
    }

    private static long insertPerson(JdbcTemplate jdbc, long tenantId, String key) {
        return jdbc.queryForObject("""
                INSERT INTO ppl_persons(
                    tenant_id,person_key,display_name,created_by,updated_by)
                VALUES(?,?,?,1,1) RETURNING person_id
                """, Long.class, tenantId, "PERSON-" + key, "Person " + key);
    }

    private Flyway flyway(DataSource source, String target) {
        return Flyway.configure(getClass().getClassLoader())
                .dataSource(source)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(false)
                .target(target)
                .load();
    }

    private PGSimpleDataSource dataSource(String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(username);
        source.setPassword(password);
        return source;
    }

    private void dropRuntimeRole() {
        if (owner == null) return;
        Integer exists = owner.queryForObject(
                "SELECT count(*) FROM pg_roles WHERE rolname='hrm013_runtime'",
                Integer.class);
        if (exists != null && exists == 1) {
            owner.execute("DROP OWNED BY hrm013_runtime");
            owner.execute("DROP ROLE hrm013_runtime");
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record Graph(
            long tenantId,
            long personId,
            long workerId,
            long relationshipId,
            long caseId,
            UUID casePublicId,
            long taskId,
            UUID taskPublicId) {
    }

    private record WaiverSeed(
            UUID approvalRequestId,
            UUID makerPublicId,
            UUID requesterPublicId,
            UUID correlationId,
            String payloadSha256) {
    }

    private record BoundaryEvidence(UUID authorityPublicId) {
    }
}
