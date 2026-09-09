package com.dwp.services.platform.activity;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditEventRepository;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.workhub.personal.PersonalWorkAccess;
import com.dwp.services.platform.workhub.personal.PersonalWorkRepository;
import com.dwp.services.platform.workhub.personal.PersonalWorkService;
import com.dwp.services.platform.workspace.WorkspaceDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.dwp.services.platform.workhub.personal.PersonalWorkDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PersonalWorkActivityPostgresIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicLong TENANTS = new AtomicLong(38000);
    private static final String WORK_ACCESS = "APP.WORK:VIEW,APP.WORK:UPDATE";
    private static final String ACTIVITY_ACCESS = "APP.ACTIVITY:VIEW,APP.WORK:VIEW";
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static LocalContainerEntityManagerFactoryBean factory;
    private static PlatformAuditService audit;
    private PersonalWorkService personal;
    private ActivityService activity;
    private ActivityEvidenceRepository evidence;
    private long tenant;

    @BeforeAll
    static void migrateAndCreateRealAuditRuntime() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration")
                .load().migrate();
        jdbc = new JdbcTemplate(source);

        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPackagesToScan("com.dwp.services.platform.audit");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.afterPropertiesSet();
        var entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        var auditRepository = new JpaRepositoryFactory(entityManager)
                .getRepository(PlatformAuditEventRepository.class);
        audit = new PlatformAuditService(auditRepository, new ObjectMapper().findAndRegisterModules());
        var transactionManager = new JpaTransactionManager(factory.getObject());
        transactionManager.setDataSource(source);
        transactions = new TransactionTemplate(transactionManager);
    }

    @AfterAll
    static void closeJpa() {
        if (factory != null) factory.destroy();
    }

    @BeforeEach
    void setUp() {
        tenant = TENANTS.incrementAndGet();
        var mapper = new ObjectMapper().findAndRegisterModules();
        personal = new PersonalWorkService(new PersonalWorkRepository(jdbc), new PersonalWorkAccess(),
                List.of(), audit, mapper);
        var repository = new ActivityRepository(new NamedParameterJdbcTemplate(jdbc));
        activity = new ActivityService(repository, new ActivityCursor(mapper));
        evidence = new ActivityEvidenceRepository(new NamedParameterJdbcTemplate(jdbc));
    }

    @Test
    void individualCommandIsExactlyBoundAndIdempotentReplayCreatesNoDuplicate() {
        AccessContext owner = owner(11L);
        UUID createKey = UUID.randomUUID();
        Task created = tx(() -> personal.create(owner, createKey, "create-correlation",
                new CreateTaskRequest("Exact personal task", null, Priority.HIGH, null, null)));
        UUID statusKey = UUID.randomUUID();
        StatusRequest command = new StatusRequest(Status.IN_PROGRESS, created.version());
        Task running = tx(() -> personal.transition(owner, created.taskId(), statusKey,
                "individual-correlation", command));

        WorkspaceDtos.ActivityEvent event = event(11L, statusKey);
        assertThat(event.actor()).isEqualTo("PERSON");
        assertThat(event.actorName()).isEqualTo("User 11");
        assertThat(event.objectId()).isEqualTo(created.taskId().toString());
        assertThat(event.source()).isEqualTo("PERSONAL_TASK");
        assertThat(event.sourceReference()).isEqualTo(created.taskId().toString());
        assertThat(event.resourceVersion()).isEqualTo(running.version());
        assertThat(event.idempotencyKey()).isEqualTo(statusKey);
        assertThat(event.resultState()).isEqualTo("IN_PROGRESS");
        assertThat(event.workStatus()).isEqualTo("IN_PROGRESS");
        assertThat(event.state()).isEqualTo("COMPLETED");
        assertThat(event.eventKind()).isEqualTo("CHANGE");
        assertThat(event.dataProvenance()).isEqualTo("LIVE");
        assertThat(event.correlationId()).isEqualTo("individual-correlation");
        assertThat(event.sourceEventId()).isEqualTo("personal-work-command:11:" + statusKey);
        assertThat(event.sourceRoute()).isEqualTo("/work/queue?work=PERSONAL_TASK%3A"
                + created.taskId() + "%3A");
        assertThat(event.auditRecordId()).isNotNull();
        assertExactDatabaseBinding(11L, statusKey);

        long[] before = commandCounts(11L, statusKey);
        Task replay = tx(() -> personal.transition(owner, created.taskId(), statusKey,
                "ignored-replay-correlation", command));
        assertThat(replay).isEqualTo(running);
        assertThat(commandCounts(11L, statusKey)).containsExactly(before);
        assertThat(event(11L, statusKey).id()).isEqualTo(event.id());
    }

    @Test
    void batchEquivalentCommandsProjectOneFactPerSucceededItemAndNoneForConflict() {
        AccessContext owner = owner(21L);
        Task first = create(owner, "Batch first");
        Task second = create(owner, "Batch second");
        UUID firstKey = UUID.randomUUID();
        UUID secondKey = UUID.randomUUID();
        tx(() -> personal.transition(owner, first.taskId(), firstKey, "batch-correlation",
                new StatusRequest(Status.COMPLETED, first.version())));
        tx(() -> personal.transition(owner, second.taskId(), secondKey, "batch-correlation",
                new StatusRequest(Status.COMPLETED, second.version())));

        assertThat(event(21L, firstKey).resultState()).isEqualTo("COMPLETED");
        assertThat(event(21L, secondKey).resultState()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM wrk_personal_work_activity_bindings
                 WHERE tenant_id=? AND actor_user_id=? AND idempotency_key IN (?,?)
                """, Integer.class, tenant, 21L, firstKey, secondKey)).isEqualTo(2);

        UUID conflictKey = UUID.randomUUID();
        assertThatThrownBy(() -> tx(() -> personal.transition(owner, first.taskId(), conflictKey,
                "batch-correlation", new StatusRequest(Status.WAITING, first.version()))))
                .isInstanceOf(BaseException.class);
        assertThat(commandCounts(21L, conflictKey)).containsExactly(0, 0, 0, 0);

        tx(() -> personal.transition(owner, first.taskId(), firstKey, "batch-replay",
                new StatusRequest(Status.COMPLETED, first.version())));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM wrk_personal_work_activity_bindings
                 WHERE tenant_id=? AND actor_user_id=? AND idempotency_key IN (?,?)
                """, Integer.class, tenant, 21L, firstKey, secondKey)).isEqualTo(2);
    }

    @Test
    void sameTenantOwnersMayReuseACommandUuidWithoutIdentityCollisionOrDisclosure() {
        UUID sharedKey = UUID.randomUUID();
        Task first = tx(() -> personal.create(owner(31L), sharedKey, "owner-31",
                new CreateTaskRequest("First owner", null, Priority.NORMAL, null, null)));
        Task second = tx(() -> personal.create(owner(32L), sharedKey, "owner-32",
                new CreateTaskRequest("Second owner", null, Priority.NORMAL, null, null)));

        WorkspaceDtos.ActivityEvent firstEvent = event(31L, sharedKey);
        WorkspaceDtos.ActivityEvent secondEvent = event(32L, sharedKey);
        assertThat(firstEvent.id()).isNotEqualTo(secondEvent.id());
        assertThat(firstEvent.objectId()).isEqualTo(first.taskId().toString());
        assertThat(secondEvent.objectId()).isEqualTo(second.taskId().toString());
        assertThat(firstEvent.sourceEventId()).isEqualTo("personal-work-command:31:" + sharedKey);
        assertThat(secondEvent.sourceEventId()).isEqualTo("personal-work-command:32:" + sharedKey);
        assertThat(activity.list(tenant, 31L, ACTIVITY_ACCESS, "en", ActivityQuery.defaults()).events())
                .extracting(WorkspaceDtos.ActivityEvent::id).contains(firstEvent.id()).doesNotContain(secondEvent.id());
        assertThatThrownBy(() -> activity.detail(tenant, 31L, ACTIVITY_ACCESS, "en", secondEvent.id()))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void simultaneousReplayCommitsOneReceiptTimelineAuditAndActivityFact() throws Exception {
        AccessContext owner = owner(33L);
        UUID key = UUID.randomUUID();
        CreateTaskRequest request = new CreateTaskRequest(
                "Concurrent personal task", null, Priority.NORMAL, null, null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return tx(() -> personal.create(owner, key, "concurrent-correlation", request));
            });
            var second = executor.submit(() -> {
                start.await();
                return tx(() -> personal.create(owner, key, "concurrent-correlation", request));
            });
            start.countDown();
            Task firstResult = first.get(20, TimeUnit.SECONDS);
            Task secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResult).isEqualTo(secondResult);
        }

        assertThat(commandCounts(33L, key)).containsExactly(1, 1, 1, 1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM wrk_activity_events event
                JOIN wrk_personal_work_activity_bindings binding
                  ON binding.activity_event_id=event.activity_event_id
                WHERE binding.tenant_id=? AND binding.actor_user_id=? AND binding.idempotency_key=?
                """, Integer.class, tenant, 33L, key)).isOne();
    }

    @Test
    void deletionHistoryRemainsOwnedButSourceAndRevokedPermissionsFailClosed() {
        AccessContext owner = owner(41L);
        Task created = create(owner, "Delete and retain evidence");
        UUID deleteKey = UUID.randomUUID();
        tx(() -> personal.delete(owner, created.taskId(), deleteKey, "delete-correlation",
                new VersionRequest(created.version())));
        WorkspaceDtos.ActivityEvent deleted = event(41L, deleteKey);
        assertThat(deleted.resultState()).isEqualTo("DELETED");
        assertThat(deleted.sourceAccess()).isEqualTo("DELETED");
        assertThat(deleted.sourceRoute()).isNull();
        assertThat(evidence.evidence(tenant, 41L,
                Set.of("APP.ACTIVITY:VIEW", "APP.WORK:VIEW"), deleted.id())).isPresent();

        assertThat(activity.list(tenant, 41L, "APP.ACTIVITY:VIEW", "en",
                ActivityQuery.defaults()).events()).isEmpty();
        assertThatThrownBy(() -> activity.detail(tenant, 41L, "APP.ACTIVITY:VIEW", "en", deleted.id()))
                .isInstanceOf(BaseException.class);
        assertThat(evidence.evidence(tenant, 41L, Set.of("APP.ACTIVITY:VIEW"), deleted.id())).isEmpty();
        assertThatThrownBy(() -> activity.detail(tenant, 42L, ACTIVITY_ACCESS, "en", deleted.id()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> activity.detail(tenant + 1, 41L, ACTIVITY_ACCESS, "en", deleted.id()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> activity.list(tenant, 41L, "APP.WORK:VIEW", "en",
                ActivityQuery.defaults())).isInstanceOf(BaseException.class);
    }

    @Test
    void projectedReceiptAuditTimelineActivityAndBindingEvidenceCannotBeRewritten() {
        AccessContext owner = owner(45L);
        Task created = create(owner, "Immutable command evidence");
        UUID commandKey = idempotencyKeyForLatestTaskCommand(45L, created.taskId());
        WorkspaceDtos.ActivityEvent event = event(45L, commandKey);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE personal_work_command_receipts
                   SET response_payload=jsonb_set(response_payload,'{title}','\"tampered\"'::jsonb)
                 WHERE tenant_id=? AND owner_user_id=? AND command_id=?
                """, tenant, 45L, commandKey)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE sys_platform_audit_events SET outcome='FAILED'
                 WHERE tenant_id=? AND audit_event_id=?
                """, tenant, event.auditRecordId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE personal_work_timeline SET status='COMPLETED'
                 WHERE tenant_id=? AND owner_user_id=? AND task_id=? AND version=?
                """, tenant, 45L, created.taskId(), created.version())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE wrk_activity_events SET summary_en='tampered'
                 WHERE tenant_id=? AND activity_event_id=?
                """, tenant, event.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE wrk_personal_work_activity_bindings SET result_state='COMPLETED'
                 WHERE tenant_id=? AND activity_event_id=?
                """, tenant, event.id())).isInstanceOf(DataAccessException.class);

        assertExactDatabaseBinding(45L, commandKey);
    }

    @Test
    void dayPlanIsNotAWorkItemFactAndCommandBindingsCannotBeRewritten() {
        AccessContext owner = owner(51L);
        Task created = create(owner, "Plan selection");
        UUID planKey = UUID.randomUUID();
        tx(() -> personal.replaceDayPlan(owner, LocalDate.of(2026, 9, 8), planKey,
                "plan-correlation", new ReplaceDayPlanRequest(
                        List.of(new SourceReference("PERSONAL_TASK", created.taskId().toString(), null)), 0L)));
        assertThat(commandCounts(51L, planKey)).containsExactly(1, 0, 0, 0);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_platform_audit_events
                 WHERE tenant_id=? AND actor_id=? AND correlation_id='plan-correlation'
                """, Integer.class, tenant, 51L)).isOne();

        UUID activityId = event(51L, idempotencyKeyForLatestTaskCommand(51L, created.taskId())).id();
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE wrk_personal_work_activity_bindings SET result_state='COMPLETED'
                 WHERE activity_event_id=?
                """, activityId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM wrk_personal_work_activity_bindings WHERE activity_event_id=?
                """, activityId)).isInstanceOf(DataAccessException.class);
    }

    private Task create(AccessContext owner, String title) {
        return tx(() -> personal.create(owner, UUID.randomUUID(), "create-" + owner.userId(),
                new CreateTaskRequest(title, null, Priority.NORMAL, null, null)));
    }

    private AccessContext owner(long user) {
        return new AccessContext(tenant, user, WORK_ACCESS, null, null, "en");
    }

    private WorkspaceDtos.ActivityEvent event(long user, UUID idempotencyKey) {
        return activity.list(tenant, user, ACTIVITY_ACCESS, "en", ActivityQuery.defaults()).events().stream()
                .filter(row -> idempotencyKey.equals(row.idempotencyKey())).findFirst().orElseThrow();
    }

    private UUID idempotencyKeyForLatestTaskCommand(long user, UUID taskId) {
        return jdbc.queryForObject("""
                SELECT idempotency_key FROM wrk_personal_work_activity_bindings
                 WHERE tenant_id=? AND actor_user_id=? AND resource_id=?
                 ORDER BY resource_version DESC LIMIT 1
                """, UUID.class, tenant, user, taskId);
    }

    private long[] commandCounts(long user, UUID key) {
        return new long[] {
                count("personal_work_command_receipts", user, key, "command_id"),
                count("personal_work_timeline", user, key, null),
                count("sys_platform_audit_events", user, key, null),
                count("wrk_personal_work_activity_bindings", user, key, "idempotency_key")
        };
    }

    private long count(String table, long user, UUID key, String directKeyColumn) {
        if (directKeyColumn != null) {
            return jdbc.queryForObject("SELECT count(*) FROM " + table
                    + " WHERE tenant_id=? AND "
                    + (table.equals("wrk_personal_work_activity_bindings") ? "actor_user_id" : "owner_user_id")
                    + "=? AND " + directKeyColumn + "=?", Long.class, tenant, user, key);
        }
        if (table.equals("personal_work_timeline")) {
            return jdbc.queryForObject("""
                    SELECT count(*) FROM personal_work_timeline timeline
                    JOIN wrk_personal_work_activity_bindings binding
                      ON binding.tenant_id=timeline.tenant_id
                     AND binding.actor_user_id=timeline.owner_user_id
                     AND binding.resource_id=timeline.task_id
                     AND binding.resource_version=timeline.version
                    WHERE binding.tenant_id=? AND binding.actor_user_id=? AND binding.idempotency_key=?
                    """, Long.class, tenant, user, key);
        }
        return jdbc.queryForObject("""
                SELECT count(*) FROM sys_platform_audit_events audit
                JOIN wrk_personal_work_activity_bindings binding
                  ON binding.tenant_id=audit.tenant_id AND binding.audit_record_id=audit.audit_event_id
                WHERE binding.tenant_id=? AND binding.actor_user_id=? AND binding.idempotency_key=?
                """, Long.class, tenant, user, key);
    }

    private void assertExactDatabaseBinding(long user, UUID key) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM wrk_personal_work_activity_bindings binding
                  JOIN personal_work_command_receipts receipt
                    ON receipt.tenant_id=binding.tenant_id
                   AND receipt.owner_user_id=binding.actor_user_id
                   AND receipt.command_id=binding.idempotency_key
                  JOIN personal_work_timeline timeline
                    ON timeline.tenant_id=binding.tenant_id
                   AND timeline.owner_user_id=binding.actor_user_id
                   AND timeline.task_id=binding.resource_id
                   AND timeline.version=binding.resource_version
                  JOIN sys_platform_audit_events audit
                    ON audit.tenant_id=binding.tenant_id
                   AND audit.audit_event_id=binding.audit_record_id
                  JOIN wrk_activity_events event
                    ON event.activity_event_id=binding.activity_event_id
                   AND event.tenant_id=binding.tenant_id
                   AND event.visible_to_user_id=binding.actor_user_id
                   AND event.object_id=binding.resource_id::text
                   AND event.source_system=binding.source_system
                   AND event.audit_record_id=binding.audit_record_id
                 WHERE binding.tenant_id=? AND binding.actor_user_id=?
                   AND binding.idempotency_key=?
                   AND receipt.response_payload->>'taskId'=binding.resource_id::text
                   AND (receipt.response_payload->>'version')::bigint=binding.resource_version
                   AND receipt.response_payload->>'status'=timeline.status
                   AND audit.actor_type='USER' AND audit.actor_id=binding.actor_user_id
                   AND audit.outcome='SUCCESS' AND event.data_provenance='LIVE'
                """, Integer.class, tenant, user, key)).isOne();
    }

    private <T> T tx(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }
}
