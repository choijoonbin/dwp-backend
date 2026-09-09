package com.dwp.services.platform.activity;

import com.dwp.services.platform.workspace.WorkspaceDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PersonalWorkActivityBackfillPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void committedV228ReceiptBackfillsExactlyOnceAndIsImmediatelyReadable() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration").target("228").load();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        long tenant = 48001L;
        long user = 61L;
        UUID task = UUID.randomUUID();
        UUID audit = UUID.randomUUID();
        UUID timeline = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO personal_work_tasks(task_id,tenant_id,owner_user_id,title,status,priority)
                VALUES (?,?,?,'Committed before Activity projection','OPEN','NORMAL')
                """, task, tenant, user);
        jdbc.update("""
                INSERT INTO sys_platform_audit_events(audit_event_id,tenant_id,actor_type,actor_id,
                  action,target_type,target_id,outcome,correlation_id)
                VALUES (?,?,'USER',?,'personal-work.task.created','PERSONAL_WORK_TASK',
                  ?,'SUCCESS','backfill-correlation')
                """, audit, tenant, user, task.toString());
        jdbc.update("""
                INSERT INTO personal_work_timeline(event_id,tenant_id,owner_user_id,task_id,
                  action,status,version,audit_record_id)
                VALUES (?,?,?,?,'CREATED','OPEN',0,?)
                """, timeline, tenant, user, task, audit);
        jdbc.update("""
                INSERT INTO personal_work_command_receipts(tenant_id,owner_user_id,command_id,
                  operation,target_key,request_fingerprint,response_payload)
                VALUES (?,?,?,'CREATE','tasks',?,?::jsonb)
                """, tenant, user, command, "a".repeat(64), """
                {"taskId":"%s","title":"Committed before Activity projection","description":null,
                 "status":"OPEN","priority":"NORMAL","dueAt":null,"sourceReference":null,
                 "version":0,"createdAt":"2026-09-08T00:00:00Z",
                 "updatedAt":"2026-09-08T00:00:00Z","completedAt":null,
                 "checklist":[],"sourceReferences":[],"deletedAt":null}
                """.formatted(task));

        UUID lifecycleTask = UUID.randomUUID();
        UUID lifecycleCreate = UUID.randomUUID();
        UUID lifecycleUpdate = UUID.randomUUID();
        UUID lifecycleStatus = UUID.randomUUID();
        UUID lifecycleDelete = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO personal_work_tasks(task_id,tenant_id,owner_user_id,title,status,priority,
                  version,deleted_at)
                VALUES (?,?,?,'Lifecycle updated before projection','WAITING','HIGH',3,
                  '2026-09-08T00:04:00Z')
                """, lifecycleTask, tenant, user);
        insertHistoricalCommand(jdbc, tenant, user, lifecycleTask, 0, "CREATED", "OPEN",
                "CREATE", "tasks", lifecycleCreate, "Lifecycle created before projection", false);
        insertHistoricalCommand(jdbc, tenant, user, lifecycleTask, 1, "UPDATED", "OPEN",
                "UPDATE", lifecycleTask.toString(), lifecycleUpdate,
                "Lifecycle updated before projection", false);
        insertHistoricalCommand(jdbc, tenant, user, lifecycleTask, 2, "WAITING", "WAITING",
                "STATUS", lifecycleTask.toString(), lifecycleStatus,
                "Lifecycle updated before projection", false);
        insertHistoricalCommand(jdbc, tenant, user, lifecycleTask, 3, "DELETED", "WAITING",
                "DELETE", lifecycleTask.toString(), lifecycleDelete,
                "Lifecycle updated before projection", true);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM wrk_activity_events WHERE source_system='PERSONAL_TASK'",
                Integer.class)).isZero();

        Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration")
                .load().migrate();
        Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration")
                .load().migrate();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM wrk_personal_work_activity_bindings
                 WHERE tenant_id=? AND actor_user_id=? AND resource_id=?
                   AND resource_version=0 AND idempotency_key=? AND result_state='OPEN'
                   AND audit_record_id=?
                """, Integer.class, tenant, user, task, command, audit)).isOne();
        assertThat(jdbc.queryForList("""
                SELECT result_state FROM wrk_personal_work_activity_bindings
                 WHERE tenant_id=? AND actor_user_id=? AND resource_id=?
                 ORDER BY resource_version
                """, String.class, tenant, user, lifecycleTask))
                .containsExactly("OPEN", "OPEN", "WAITING", "DELETED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM wrk_activity_events event
                JOIN wrk_personal_work_activity_bindings binding
                  ON binding.activity_event_id=event.activity_event_id
                 WHERE binding.tenant_id=? AND binding.actor_user_id=?
                   AND binding.resource_id=? AND event.data_provenance='LIVE'
                """, Integer.class, tenant, user, lifecycleTask)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM wrk_activity_events
                 WHERE tenant_id=? AND visible_to_user_id=? AND source_system='PERSONAL_TASK'
                   AND source_event_id=? AND object_id=? AND event_state='COMPLETED'
                   AND event_kind='CHANGE' AND data_provenance='LIVE'
                """, Integer.class, tenant, user, "personal-work-command:" + user + ":" + command,
                task.toString())).isOne();

        var activity = new ActivityService(
                new ActivityRepository(new NamedParameterJdbcTemplate(jdbc)),
                new ActivityCursor(new ObjectMapper().findAndRegisterModules()));
        WorkspaceDtos.ActivityEvent event = activity.list(tenant, user,
                "APP.ACTIVITY:VIEW,APP.WORK:VIEW", "en", ActivityQuery.defaults())
                .events().stream().filter(candidate -> command.equals(candidate.idempotencyKey()))
                .findFirst().orElseThrow();
        assertThat(event.idempotencyKey()).isEqualTo(command);
        assertThat(event.resourceVersion()).isZero();
        assertThat(event.resultState()).isEqualTo("OPEN");
        assertThat(event.correlationId()).isEqualTo("backfill-correlation");
        assertThat(event.sourceRoute()).isEqualTo(
                "/work/queue?work=PERSONAL_TASK%3A" + task + "%3A");
    }

    private void insertHistoricalCommand(
            JdbcTemplate jdbc,
            long tenant,
            long user,
            UUID task,
            long version,
            String timelineAction,
            String status,
            String operation,
            String target,
            UUID command,
            String title,
            boolean deleted) {
        UUID audit = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_platform_audit_events(audit_event_id,tenant_id,actor_type,actor_id,
                  action,target_type,target_id,outcome,correlation_id)
                VALUES (?,?,'USER',?,?,'PERSONAL_WORK_TASK',?,'SUCCESS',?)
                """, audit, tenant, user, "personal-work.task." + timelineAction.toLowerCase(),
                task.toString(), "backfill-" + operation.toLowerCase());
        jdbc.update("""
                INSERT INTO personal_work_timeline(event_id,tenant_id,owner_user_id,task_id,
                  action,status,version,audit_record_id)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), tenant, user, task, timelineAction, status, version, audit);
        String deletedAt = deleted ? "\"2026-09-08T00:04:00Z\"" : "null";
        String response = """
                {"taskId":"%s","title":"%s","status":"%s","version":%d,"deletedAt":%s}
                """.formatted(task, title, status, version, deletedAt);
        jdbc.update("""
                INSERT INTO personal_work_command_receipts(tenant_id,owner_user_id,command_id,
                  operation,target_key,request_fingerprint,response_payload)
                VALUES (?,?,?,?,?,? ,?::jsonb)
                """, tenant, user, command, operation, target, "b".repeat(64), response);
    }
}
