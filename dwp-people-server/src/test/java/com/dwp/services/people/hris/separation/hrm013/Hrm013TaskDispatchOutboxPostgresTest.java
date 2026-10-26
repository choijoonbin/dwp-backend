package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
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

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class Hrm013TaskDispatchOutboxPostgresTest extends Hrm013PostgresTestSupport {
    @Test
    void staleClaimCannotOverwriteAReclaimedDispatch() {
        Graph graph = graph("outbox-cas", Hrm013TaskDispatchV1.TIME);
        Hrm013TaskDispatchOutboxStore store = dispatchStore();
        TaskDispatchFence fence = dispatchFence(graph, Hrm013TaskDispatchV1.TIME);
        CallbackAuthorityRecord callback = callbackFence(graph, fence,
                Hrm013TaskDispatchV1.TIME);
        transactions.executeWithoutResult(status -> store.insert(fence, callback));

        TaskDispatchClaim first = store.claimNext(TENANT, NOW.plusSeconds(1)).orElseThrow();
        assertThat(first.attemptCount()).isOne();
        assertThatThrownBy(() -> store.fail(new TaskDispatchFailure(
                TENANT, fence.dispatchEventId(), first.attemptCount(),
                first.claimUntil().plusMillis(1), true, false,
                NOW.plusSeconds(90), "TRANSIENT")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim lost");

        assertThat(store.fail(new TaskDispatchFailure(
                TENANT, fence.dispatchEventId(), first.attemptCount(),
                first.claimUntil(), true, false,
                NOW.plusSeconds(90), "TRANSIENT")))
                .isEqualTo(TaskDispatchDisposition.RETRY);
    }

    @Test
    void finalExpiredClaimBecomesResultUnknownAndIsAudited() {
        Graph graph = graph("outbox-final", Hrm013TaskDispatchV1.TIME);
        Hrm013TaskDispatchOutboxStore store = dispatchStore();
        TaskDispatchFence fence = dispatchFence(graph, Hrm013TaskDispatchV1.TIME);
        transactions.executeWithoutResult(status -> store.insert(
                fence, callbackFence(graph, fence, Hrm013TaskDispatchV1.TIME)));
        jdbc.update("""
                UPDATE sys_people_outbox_events
                   SET attempt_count=10,next_attempt_at=?
                 WHERE tenant_id=? AND event_id=?
                """, java.sql.Timestamp.from(NOW.minusSeconds(1)), TENANT,
                fence.dispatchEventId());

        assertThat(store.claimNext(TENANT, NOW)).isEmpty();
        assertThat(jdbc.queryForMap("""
                SELECT last_error,dead_lettered_at FROM sys_people_outbox_events
                 WHERE tenant_id=? AND event_id=?
                """, TENANT, fence.dispatchEventId()))
                .containsEntry("last_error", "RESULT_UNKNOWN");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_audit_events
                 WHERE tenant_id=? AND target_id=?
                   AND action='HRM013_TASK_DISPATCH_TERMINATED'
                """, Integer.class, TENANT, fence.dispatchEventId().toString())).isOne();
    }
}

abstract class Hrm013PostgresTestSupport {
    static final long TENANT = 61_301L;
    static final String SHA_A = "a".repeat(64);
    static final String SHA_B = "b".repeat(64);
    static final String SHA_C = "c".repeat(64);
    static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    Flyway flyway;
    JdbcTemplate jdbc;
    NamedParameterJdbcTemplate named;
    DataSourceTransactionManager manager;
    TransactionTemplate transactions;

    @BeforeEach
    void migrateRuntimeSchema() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        flyway = Flyway.configure(getClass().getClassLoader())
                .dataSource(source).locations("classpath:db/migration")
                .schemas("public").defaultSchema("public")
                .cleanDisabled(false).target("61").load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        named = new NamedParameterJdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
    }

    @AfterEach
    void cleanRuntimeSchema() {
        if (flyway != null) flyway.clean();
    }

    Hrm013TaskDispatchOutboxStore dispatchStore() {
        return new Hrm013TaskDispatchOutboxStore(
                named, manager, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                new JdbcHrm013CallbackAuthorityStore(named, manager));
    }

    Graph graph(String key, Hrm013TaskDispatchV1.Profile profile) {
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,
                    data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,?,?,'ACTIVE','local','POOL',1,1)
                """, UUID.randomUUID(), TENANT, "hrm013-runtime-" + key,
                "Runtime " + key);
        long person = jdbc.queryForObject("""
                INSERT INTO ppl_persons(tenant_id,person_key,display_name,created_by,updated_by)
                VALUES(?,?,?,1,1) RETURNING person_id
                """, Long.class, TENANT, "PERSON-" + key, "Person " + key);
        long employer = jdbc.queryForObject("""
                INSERT INTO ppl_legal_employers(
                    tenant_id,employer_key,legal_name,created_by,updated_by)
                VALUES(?,?,?,1,1) RETURNING legal_employer_id
                """, Long.class, TENANT, "EMP-" + key, "Employer " + key);
        long worker = jdbc.queryForObject("""
                INSERT INTO ppl_workers(
                    tenant_id,person_id,worker_number,worker_type,worker_status,
                    created_by,updated_by)
                VALUES(?,?,?,'EMPLOYEE','ACTIVE',1,1) RETURNING worker_id
                """, Long.class, TENANT, person, "WORKER-" + key);
        long relationship = jdbc.queryForObject("""
                INSERT INTO ppl_work_relationships(
                    tenant_id,relationship_key,worker_id,legal_employer_id,
                    relationship_type,primary_relationship,start_date,created_by,updated_by)
                VALUES(?,?,?,?,'EMPLOYEE',TRUE,DATE '2020-01-01',1,1)
                RETURNING work_relationship_id
                """, Long.class, TENANT, "REL-" + key, worker, employer);
        UUID casePublic = UUID.randomUUID();
        UUID maker = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        long caseId = jdbc.queryForObject("""
                INSERT INTO ppl_separation_cases(
                    public_id,tenant_id,worker_id,work_relationship_id,separation_type,
                    reason_code,effective_date,last_working_date,lifecycle_state,
                    input_snapshot_hash,task_template_public_id,task_template_revision,
                    task_template_hash,maker_principal_public_id,correlation_id,
                    created_at,created_by,updated_at,updated_by)
                VALUES(?,?,?,?,'RESIGNATION','VOLUNTARY',DATE '2026-11-01',
                    DATE '2026-10-31','TASKS_ACTIVE',?,?,1,?,?,?, ?,1,?,1)
                RETURNING separation_case_id
                """, Long.class, casePublic, TENANT, worker, relationship, SHA_A,
                UUID.randomUUID(), SHA_B, maker, correlation,
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        UUID taskPublic = UUID.randomUUID();
        long taskId = jdbc.queryForObject("""
                INSERT INTO ppl_separation_tasks(
                    public_id,tenant_id,separation_case_id,task_type,
                    owner_workload_id,mandatory,irreversible,lifecycle_state,version,
                    created_at,created_by,updated_at,updated_by)
                VALUES(?,?,?,?,?,TRUE,TRUE,'PENDING',0,?,1,?,1)
                RETURNING separation_task_id
                """, Long.class, taskPublic, TENANT, caseId, profile.taskType(),
                profile.ownerWorkloadId(), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW));
        return new Graph(caseId, casePublic, taskId, taskPublic, maker, correlation);
    }

    TaskDispatchFence dispatchFence(Graph graph, Hrm013TaskDispatchV1.Profile profile) {
        UUID event = Hrm013TaskDispatchV1.dispatchEventId(
                TENANT, graph.taskPublicId(), 1, profile.bindingId());
        Hrm013TaskDispatchV1.DispatchBody raw;
        if (profile.equals(Hrm013TaskDispatchV1.IDENTITY)) {
            raw = new Hrm013TaskDispatchV1.IdentityTaskDispatch(
                    Hrm013TaskDispatchV1.SCHEMA_NAME, Hrm013TaskDispatchV1.SCHEMA_VERSION,
                    event, graph.casePublicId(), graph.taskPublicId(), profile.taskType(),
                    1, 1, NOW, SHA_A, graph.correlationId(), UUID.randomUUID(),
                    "RESIGNATION", LocalDate.parse("2026-11-01"));
        } else {
            raw = new Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch(
                    Hrm013TaskDispatchV1.SCHEMA_NAME, Hrm013TaskDispatchV1.SCHEMA_VERSION,
                    event, graph.casePublicId(), graph.taskPublicId(), profile.taskType(),
                    1, 1, NOW, SHA_A, graph.correlationId(), UUID.randomUUID(),
                    UUID.randomUUID(), "RESIGNATION", LocalDate.parse("2026-11-01"),
                    LocalDate.parse("2026-10-31"));
        }
        Hrm013TaskDispatchV1.DispatchBody body = Hrm013TaskDispatchV1.seal(raw);
        byte[] exact = Hrm013TaskDispatchV1.encode(profile, TENANT, body);
        return new TaskDispatchFence(event, TENANT, graph.casePublicId(),
                graph.taskPublicId(), profile.bindingId(), profile.ownerWorkloadId(),
                profile.purpose(), profile.taskType(), profile.eventType(), 1, 1,
                exact, Hrm013TaskDispatchV1.requestBodySha256(exact),
                body.payloadSha256(), graph.correlationId(), 7, 11, 13, 17, NOW);
    }

    CallbackAuthorityRecord callbackFence(
            Graph graph, TaskDispatchFence fence, Hrm013TaskDispatchV1.Profile profile) {
        return new CallbackAuthorityRecord(UUID.randomUUID(), new CallbackAuthorityKey(
                TENANT, profile.callbackBindingId(), profile.ownerWorkloadId(),
                graph.casePublicId(), graph.taskPublicId(), profile.callbackPurpose(), null),
                graph.caseId(), graph.taskId(), "TASK_RECEIPT", "dwp-people-server",
                profile.taskType(), null, fence.callbackTenantGrantRevision(),
                fence.callbackAuthorizationRevision(), 1, fence.correlationId(),
                fence.payloadSha256(), 1, "ADMIT", null, SHA_C, NOW, null);
    }

    record Graph(long caseId, UUID casePublicId, long taskId, UUID taskPublicId,
                 UUID makerId, UUID correlationId) { }
}
