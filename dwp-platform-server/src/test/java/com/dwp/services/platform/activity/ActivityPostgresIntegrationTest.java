package com.dwp.services.platform.activity;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditEventRepository;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.workspace.WorkspaceDtos;
import com.dwp.services.platform.workspace.WorkspaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ActivityPostgresIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicLong TENANTS = new AtomicLong(8000);
    private static final String ACCESS = "APP.ACTIVITY:VIEW,APP.WORK:VIEW,APP.APPS:VIEW";
    private static JdbcTemplate jdbc;
    private static PGSimpleDataSource source;
    private static final UUID LEGACY_WORK = UUID.fromString("eeeeeeee-0000-0000-0000-000000000002");
    private static final UUID LEGACY_EVENT = UUID.fromString("eeeeeeee-0000-0000-0000-000000000003");
    private static final UUID FOREIGN_LEGACY_EVENT = UUID.fromString("eeeeeeee-0000-0000-0000-000000000004");
    private ActivityService service;
    private long tenant;
    private UUID work;
    private UUID audit;

    @BeforeAll
    static void migrateWithoutDestroyingLegacyHistory() {
        source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl()); source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration")
                .target("221").load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO wrk_activity_events(activity_event_id,tenant_id,visible_to_user_id,actor_kind,
                  actor_name,event_state,title_ko,title_en,summary_ko,summary_en,object_type,
                  object_label_ko,object_label_en,source_system,audit_reference,source_route)
                SELECT 'eeeeeeee-0000-0000-0000-000000000001',app.tenant_id,7,'PERSON','Test','COMPLETED',
                  '앱 실행','App launched','legacy','legacy','WORKSPACE_APP',app.name_ko,app.name_en,
                  'DWP Apps','AUD-APP-11111111-1111-1111-1111-111111111111','/apps?app='||app.app_key
                FROM adm_workspace_apps app ORDER BY app.workspace_app_id LIMIT 1
                """);
        jdbc.update("""
                INSERT INTO wrk_items(work_item_id,tenant_id,work_key,title_ko,title_en,work_type,
                  priority,lifecycle_state,owner_name,assignee_user_id,source_system)
                VALUES (?,7000,'WK-MIGRATION','업무','Work','TASK','HIGH','WAITING','Owner',7,'DWP_WORKSPACE')
                """, LEGACY_WORK);
        var legacyRow = new WorkspaceRepository(jdbc).workItem(7000L, 7L, LEGACY_WORK, false).orElseThrow();
        jdbc.update("""
                INSERT INTO wrk_activity_events(activity_event_id,tenant_id,visible_to_user_id,actor_kind,
                  actor_name,event_state,title_ko,title_en,summary_ko,summary_en,object_type,
                  object_label_ko,object_label_en,source_system,audit_reference,source_route)
                VALUES (?,7000,7,'PERSON','User','RUNNING','업무 상태 변경','Work status changed',?,?,'WORK_ITEM',
                  '업무','Work',?,'AUD-WRK-11111111-1111-1111-1111-111111111111','/untrusted-old-route')
                """, LEGACY_EVENT, legacyRow.id() + " 상태가 대기 상태로 변경되었습니다.",
                legacyRow.id() + " was moved to waiting.", legacyRow.sourceSystem());
        jdbc.update("""
                INSERT INTO wrk_items(work_item_id,tenant_id,work_key,title_ko,title_en,work_type,
                  priority,lifecycle_state,owner_name,assignee_user_id,source_system)
                VALUES (?,7000,'WK-EXTERNAL','외부업무','External task','TASK','HIGH','WAITING','Owner',7,'IT Service')
                """, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO wrk_activity_events(activity_event_id,tenant_id,visible_to_user_id,actor_kind,
                  actor_name,event_state,title_ko,title_en,summary_ko,summary_en,object_type,
                  object_label_ko,object_label_en,source_system,audit_reference)
                VALUES (?,7000,7,'PERSON','User','RUNNING','업무 상태 변경','Work status changed',
                  'WK-EXTERNAL 상태가 대기 상태로 변경되었습니다.','WK-EXTERNAL was moved to waiting.',
                  'WORK_ITEM','외부업무','External task','IT Service','AUD-WRK-22222222-2222-2222-2222-222222222222')
                """, FOREIGN_LEGACY_EVENT);
        Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration")
                .target("222").load().migrate();
    }

    @BeforeEach
    void setUp() {
        tenant = TENANTS.incrementAndGet(); work = UUID.randomUUID(); audit = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wrk_items(work_item_id,tenant_id,work_key,title_ko,title_en,work_type,
                  priority,lifecycle_state,owner_name,assignee_user_id,source_system)
                VALUES (?,?,'WK-TEST','업무','Work','TASK','HIGH','WAITING','Owner',7,'DWP_WORKSPACE')
                """, work, tenant);
        saveAudit(tenant, audit);
        service = new ActivityService(new ActivityRepository(new NamedParameterJdbcTemplate(jdbc)),
                new ActivityCursor(new ObjectMapper().findAndRegisterModules()));
    }

    @Test
    void migrationPreservesKnownRuntimeHistoryAndQuarantinesSamplesWithoutDeleting() {
        assertThat(jdbc.queryForObject("SELECT data_provenance FROM wrk_activity_events WHERE activity_event_id="
                + "'eeeeeeee-0000-0000-0000-000000000001'", String.class)).isEqualTo("LEGACY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wrk_activity_events WHERE data_provenance='SAMPLE'",
                Integer.class)).isGreaterThanOrEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT audit_record_id FROM wrk_activity_events WHERE activity_event_id="
                + "'eeeeeeee-0000-0000-0000-000000000001'", UUID.class)).isNull();
        var preserved = service.detail(7000L, 7L, ACCESS, "en", LEGACY_EVENT);
        assertThat(preserved.objectId()).isEqualTo(LEGACY_WORK.toString());
        assertThat(preserved.sourceRoute()).isEqualTo("/work?item=WK-MIGRATION");
        assertThat(preserved.dataProvenance()).isEqualTo("LEGACY");
        assertThat(preserved.auditStatus()).isEqualTo("LEGACY_UNLINKED");
        assertThat(preserved.auditId()).isNull();
        assertThat(jdbc.queryForObject("SELECT data_provenance FROM wrk_activity_events WHERE activity_event_id=?",
                String.class, FOREIGN_LEGACY_EVENT)).isEqualTo("QUARANTINED");
        assertThatThrownBy(() -> service.detail(7000L, 7L, ACCESS, "en", FOREIGN_LEGACY_EVENT)).isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForList("SELECT code FROM sys_code_values WHERE code_set_key="
                + "'PLATFORM.WORKSPACE_ACTIVITY.EVENT_KIND' AND lifecycle_state='ACTIVE' ORDER BY code", String.class))
                .containsExactly("CHANGE", "EXECUTION", "USAGE");
        assertThat(jdbc.queryForList("SELECT code FROM sys_code_values WHERE code_set_key="
                + "'PLATFORM.WORKSPACE_ACTIVITY.DATA_PROVENANCE' AND lifecycle_state='ACTIVE' ORDER BY code", String.class))
                .containsExactly("LEGACY", "LIVE", "QUARANTINED", "SAMPLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_code_bindings WHERE source_reference IN "
                + "('wrk_activity_events.event_kind','wrk_activity_events.data_provenance','wrk_activity_events.work_status')"
                + " AND usage_type='DATABASE_COLUMN' AND enforcement_type='CHECK'", Integer.class)).isEqualTo(3);
    }

    @Test
    void workChangesRecordCompletedFactsWithSeparateWorkStatusAndRealAuditBinding() {
        WorkspaceRepository repository = new WorkspaceRepository(jdbc);
        var row = repository.workItem(tenant, 7L, work, false).orElseThrow();
        repository.addWorkActivity(tenant, 7L, row, "WAITING", "변경", "Changed", "요약", "Summary", audit, "corr");
        WorkspaceDtos.ActivityEvent event = service.list(tenant, 7L, ACCESS, "en", ActivityQuery.defaults()).events().getFirst();
        assertThat(event.state()).isEqualTo("COMPLETED");
        assertThat(event.workStatus()).isEqualTo("WAITING");
        assertThat(event.eventKind()).isEqualTo("CHANGE");
        assertThat(event.auditRecordId()).isEqualTo(audit);
        assertThat(event.auditId()).isEqualTo(audit.toString());
        assertThat(event.correlationId()).isEqualTo("corr");
        assertThat(event.sourceRoute()).isEqualTo("/work?item=WK-TEST");
        assertThat(service.summary(tenant, 7L, ACCESS).total()).isZero();
        assertThatThrownBy(() -> repository.addWorkActivity(tenant, 7L, row, "WAITING",
                "변경", "Changed", "요약", "Summary", audit, "corr")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE wrk_activity_events SET event_state='RUNNING' WHERE activity_event_id=?",
                event.id())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void readsEveryMatchingEventBeyond200WithStableKeysetAndIndependentOldDetail() {
        OffsetDateTime time = OffsetDateTime.parse("2025-01-01T00:00:00.123456Z");
        List<UUID> inserted = new ArrayList<>();
        for (int i = 0; i < 225; i++) inserted.add(event("CHANGE", "COMPLETED", null, null, null, time, "result-" + i));
        ActivityQuery query = query(null, null, null, null, 31, false);
        var first = service.list(tenant, 7L, ACCESS, "en", query);
        event("CHANGE", "COMPLETED", null, null, null, time.minusDays(1), "late backfill");
        List<UUID> found = new ArrayList<>();
        var page = first;
        do {
            page.events().forEach(e -> found.add(e.id()));
            if (!page.hasMore()) break;
            page = service.list(tenant, 7L, ACCESS, "en", query(null, null, null, page.nextCursor(), 31, false));
            assertThat(page.snapshotAt()).isEqualTo(first.snapshotAt());
        } while (true);
        assertThat(found).hasSize(225); assertThat(new HashSet<>(found)).hasSize(225);
        assertThat(found).containsExactlyInAnyOrderElementsOf(inserted);
        assertThat(service.detail(tenant, 7L, ACCESS, "en", inserted.getFirst()).id()).isEqualTo(inserted.getFirst());
        assertThat(service.list(tenant, 7L, ACCESS, "en", query("result-224", null, null, null, 50, false)).events()).hasSize(1);
        assertThat(service.list(tenant, 7L, ACCESS, "en", query(null, time, time.plusSeconds(1), null, 100, false)).events()).hasSize(100);
        assertThat(service.list(tenant, 7L, ACCESS, "en", query(null, time.minusSeconds(1), time, null, 50, false)).events()).isEmpty();
    }

    @Test
    void summariesChooseLatestExecutionVersionNotNewestArrivalOrHistoricalRunning() {
        OffsetDateTime now = OffsetDateTime.now().minusHours(1);
        event("EXECUTION", "COMPLETED", "run-one", 2L, 1, now, "completed");
        event("EXECUTION", "RUNNING", "run-one", 1L, 1, now.plusMinutes(1), "late old state");
        event("EXECUTION", "RUNNING", "run-two", 1L, 1, now, "retry one");
        event("EXECUTION", "FAILED", "run-two", 2L, 1, now, "failed one");
        event("EXECUTION", "NEEDS_INPUT", "run-two", 0L, 2, now, "retry two");
        var summary = service.summary(tenant, 7L, ACCESS);
        assertThat(summary.total()).isEqualTo(2); assertThat(summary.completed()).isEqualTo(1);
        assertThat(summary.needsInput()).isEqualTo(1); assertThat(summary.running()).isZero();
        assertThat(summary.failed()).isZero();
        var filtered = new ActivityQuery("PERSON", "COMPLETED", "completed", "DWP_WORKSPACE",
                "WORK_ITEM", work.toString(), "run-one", now.minusSeconds(1), now.plusSeconds(1), null, 50, false);
        assertThat(service.list(tenant, 7L, ACCESS, "en", filtered).events()).hasSize(1);
    }

    @Test
    void audienceIsNotPermissionAndRevocationDeletionAndUnknownTypesFailClosed() {
        UUID id = event("CHANGE", "COMPLETED", null, null, null, OffsetDateTime.now(), "visible");
        assertThat(service.detail(tenant, 7L, ACCESS, "en", id).sourceAccess()).isEqualTo("AVAILABLE");
        var activityOnly = service.list(tenant, 7L, "APP.ACTIVITY:VIEW", "en", ActivityQuery.defaults());
        assertThat(activityOnly.events()).isEmpty();
        assertThat(activityOnly.coverage().supportedObjectTypes()).isEmpty();
        var activityOnlySummary = service.summary(tenant, 7L, "APP.ACTIVITY:VIEW");
        assertThat(activityOnlySummary.total()).isZero();
        assertThat(activityOnlySummary.coverage().supportedObjectTypes()).isEmpty();
        for (long wrongTenant : new long[] {tenant + 1}) {
            assertThatThrownBy(() -> service.detail(wrongTenant, 7L, ACCESS, "en", id)).isInstanceOf(BaseException.class);
        }
        assertThatThrownBy(() -> service.detail(tenant, 8L, ACCESS, "en", id)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.detail(tenant, 7L, "APP.ACTIVITY:VIEW", "en", id)).isInstanceOf(BaseException.class);
        jdbc.update("UPDATE wrk_items SET assignee_user_id=8 WHERE work_item_id=?", work);
        assertThat(service.list(tenant, 7L, ACCESS, "en", ActivityQuery.defaults()).events()).isEmpty();
        assertThatThrownBy(() -> service.detail(tenant, 7L, ACCESS, "en", id)).isInstanceOf(BaseException.class);
        jdbc.update("DELETE FROM wrk_items WHERE work_item_id=?", work);
        assertThatThrownBy(() -> service.detail(tenant, 7L, ACCESS, "en", id)).isInstanceOf(BaseException.class);
    }

    @Test
    void foreignSourceOrNonTaskProjectionCannotAuthorizeHistoryDetailOrExecutionCounts() {
        UUID id = event("EXECUTION", "RUNNING", "native-run", 0L, 1, OffsetDateTime.now(), "native task");
        assertThat(service.summary(tenant, 7L, ACCESS).total()).isEqualTo(1);
        jdbc.update("UPDATE wrk_items SET source_system='IT Service' WHERE work_item_id=?", work);
        assertThat(service.list(tenant, 7L, ACCESS, "en", ActivityQuery.defaults()).events()).isEmpty();
        assertThatThrownBy(() -> service.detail(tenant, 7L, ACCESS, "en", id)).isInstanceOf(BaseException.class);
        assertThat(service.summary(tenant, 7L, ACCESS).total()).isZero();
        jdbc.update("UPDATE wrk_items SET source_system='DWP_WORKSPACE',work_type='SERVICE' WHERE work_item_id=?", work);
        assertThat(service.list(tenant, 7L, ACCESS, "en", ActivityQuery.defaults()).events()).isEmpty();
        assertThatThrownBy(() -> service.detail(tenant, 7L, ACCESS, "en", id)).isInstanceOf(BaseException.class);
        assertThat(service.summary(tenant, 7L, ACCESS).total()).isZero();
    }

    @Test
    void databaseRejectsCrossTenantAuditReferencesAndRollsBackBothFacts() {
        UUID otherAudit = UUID.randomUUID(); saveAudit(tenant + 50, otherAudit);
        UUID originalAudit = audit; audit = otherAudit;
        assertThatThrownBy(() -> event("CHANGE", "COMPLETED", null, null, null, OffsetDateTime.now(), "invalid"))
                .isInstanceOf(DataAccessException.class);
        audit = originalAudit;
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        UUID rollbackAudit = UUID.randomUUID();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            saveAudit(tenant, rollbackAudit); audit = rollbackAudit;
            event("CHANGE", "COMPLETED", null, null, null, OffsetDateTime.now(), "rollback");
            throw new IllegalStateException("abort command");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_platform_audit_events WHERE audit_event_id=?",
                Integer.class, rollbackAudit)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wrk_activity_events WHERE audit_record_id=?",
                Integer.class, rollbackAudit)).isZero();
    }

    @Test
    void usageRequiresAnExplicitFilterAndCurrentActiveApplicationGrant() {
        jdbc.update("""
                INSERT INTO adm_workspace_apps(tenant_id,app_key,name_ko,name_en,description_ko,description_en,
                  owner_name,category,launch_mode,launch_target,icon_key,resource_key,health_state)
                VALUES (?,'test-app','앱','App','설명','Description','DWP','PRODUCTIVITY','NATIVE',
                  '/calendar','calendar','APP.CALENDAR','HEALTHY')
                """, tenant);
        WorkspaceRepository repository = new WorkspaceRepository(jdbc);
        repository.addAppActivity(tenant, 7L, repository.app(tenant, 7L, "test-app", false).orElseThrow(),
                "실행", "Launched", "요약", "Summary", audit, "app-correlation");
        String grants = ACCESS + ",APP.CALENDAR:VIEW";
        assertThat(service.list(tenant, 7L, grants, "en", ActivityQuery.defaults()).events()).isEmpty();
        var withUsage = query(null, null, null, null, 50, true);
        var event = service.list(tenant, 7L, grants, "en", withUsage).events().getFirst();
        assertThat(event.eventKind()).isEqualTo("USAGE");
        assertThat(event.sourceRoute()).isEqualTo("/apps?app=test-app");
        assertThat(service.list(tenant, 7L, ACCESS, "en", withUsage).events()).isEmpty();
        assertThatThrownBy(() -> service.detail(tenant, 7L, ACCESS, "en", event.id())).isInstanceOf(BaseException.class);
        jdbc.update("UPDATE adm_workspace_apps SET lifecycle_state='RETIRED' WHERE tenant_id=?", tenant);
        assertThat(service.list(tenant, 7L, grants, "en", withUsage).events()).isEmpty();
    }

    @Test
    void missingObjectReferenceAndAudienceCannotBecomeTrustedLiveRows() {
        assertThatThrownBy(() -> event("EXECUTION", "RUNNING", "run", null, 1, OffsetDateTime.now(), "invalid"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> event("EXECUTION", "RUNNING", "run", 0L, null, OffsetDateTime.now(), "invalid"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> event("EXECUTION", "RUNNING", " ", 0L, 1, OffsetDateTime.now(), "invalid"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wrk_activity_events(activity_event_id,tenant_id,actor_kind,actor_name,event_state,
                  title_ko,title_en,object_type,object_label_ko,object_label_en,source_system,audit_reference,data_provenance)
                VALUES (?,?,'SYSTEM','Unknown','COMPLETED','숨김','Hidden','UNKNOWN','숨김','Hidden','Unknown','ref','LIVE')
                """, UUID.randomUUID(), tenant)).isInstanceOf(DataAccessException.class);
        jdbc.update("""
                INSERT INTO wrk_activity_events(activity_event_id,tenant_id,visible_to_user_id,actor_kind,actor_name,event_state,
                  title_ko,title_en,object_type,object_label_ko,object_label_en,source_system,audit_reference,
                  object_id,source_event_id,audit_record_id,data_provenance)
                VALUES (?,?,7,'SYSTEM','Unknown','COMPLETED','숨김','Hidden','UNKNOWN','숨김','Hidden','Unknown','ref',
                  ?,?,?, 'LIVE')
                """, UUID.randomUUID(), tenant, work.toString(), UUID.randomUUID().toString(), audit);
        assertThat(service.list(tenant, 7L, ACCESS, "en", ActivityQuery.defaults()).events()).isEmpty();
        assertThat(service.summary(tenant, 7L, ACCESS).total()).isZero();
    }

    @Test
    void realJpaAuditAndJdbcHistoryCommitAtomicallyWithTheDeferredEvidenceReference() {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source); factory.setPackagesToScan("com.dwp.services.platform.audit");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter()); factory.afterPropertiesSet();
        try {
            var entityManagerFactory = factory.getObject();
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
            var auditRepository = new JpaRepositoryFactory(entityManager).getRepository(PlatformAuditEventRepository.class);
            var auditService = new PlatformAuditService(auditRepository, new ObjectMapper().findAndRegisterModules());
            var transactionManager = new JpaTransactionManager(entityManagerFactory);
            transactionManager.setDataSource(source);
            var transaction = new TransactionTemplate(transactionManager);
            var repository = new WorkspaceRepository(jdbc);
            var row = repository.workItem(tenant, 7L, work, false).orElseThrow();
            UUID evidence = transaction.execute(status -> {
                assertThat(repository.updateWorkStatus(tenant, 7L, work, "IN_PROGRESS", row.version(), "진행", "Started"))
                        .isTrue();
                var after = repository.workItem(tenant, 7L, work, false).orElseThrow();
                UUID ref = auditService.successWithId(tenant, 7L, "workspace.work-status.updated",
                        "WORK_ITEM", "WK-TEST", "jpa-jdbc-correlation", row, after);
                repository.addWorkActivity(tenant, 7L, after, "IN_PROGRESS", "변경", "Changed", "요약", "Summary", ref, "jpa-jdbc-correlation");
                return ref;
            });
            var current = repository.workItem(tenant, 7L, work, false).orElseThrow();
            assertThat(current.status()).isEqualTo("IN_PROGRESS");
            assertThat(service.list(tenant, 7L, ACCESS, "en", ActivityQuery.defaults()).events().getFirst().auditRecordId())
                    .isEqualTo(evidence);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_platform_audit_events WHERE audit_event_id=?",
                    Integer.class, evidence)).isEqualTo(1);
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                assertThat(repository.updateWorkStatus(tenant, 7L, work, "COMPLETED", current.version(), "완료", "Completed"))
                        .isTrue();
                UUID ref = auditService.successWithId(tenant, 7L, "workspace.work-status.updated",
                        "WORK_ITEM", "WK-TEST", "must-rollback", current, repository.workItem(tenant, 7L, work, false).orElseThrow());
                // Duplicate stable source event: an audit insert must not survive a failed event write.
                repository.addWorkActivity(tenant, 7L, current, "COMPLETED", "변경", "Changed", "요약", "Summary", ref, "must-rollback");
            })).isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_platform_audit_events WHERE tenant_id=? AND correlation_id='must-rollback'",
                    Integer.class, tenant)).isZero();
            var unchanged = repository.workItem(tenant, 7L, work, false).orElseThrow();
            assertThat(unchanged.version()).isEqualTo(current.version());
            assertThat(unchanged.status()).isEqualTo("IN_PROGRESS");
        } finally { factory.destroy(); }
    }

    private UUID event(String kind, String state, String execution, Long version, Integer attempt,
                       OffsetDateTime time, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wrk_activity_events(activity_event_id,tenant_id,visible_to_user_id,actor_kind,actor_name,
                  event_state,title_ko,title_en,object_type,object_label_ko,object_label_en,source_system,audit_reference,
                  event_kind,source_event_id,object_id,execution_id,execution_version,attempt,audit_record_id,
                  data_provenance,occurred_at,source_route)
                VALUES (?,?,7,'PERSON','User',?,?,?,'WORK_ITEM','업무','Work','DWP_WORKSPACE',?,?,?,?,?,?,?,?,
                  'LIVE',?,'https://untrusted.example/never-render')
                """, id, tenant, state, title, title, audit.toString(), kind, id.toString(), work.toString(),
                execution, version, attempt, audit, time);
        return id;
    }

    private static void saveAudit(long tenant, UUID id) {
        jdbc.update("""
                INSERT INTO sys_platform_audit_events(audit_event_id,tenant_id,actor_type,actor_id,action,
                  target_type,target_id,outcome) VALUES (?,?,'USER',7,'activity.test','WORK_ITEM','test','SUCCESS')
                """, id, tenant);
    }

    private ActivityQuery query(String text, OffsetDateTime from, OffsetDateTime to, String cursor, int limit, boolean usage) {
        return new ActivityQuery(null, null, text, null, null, null, null, from, to, cursor, limit, usage);
    }
}
