package com.dwp.services.platform.activity;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
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
import java.time.OffsetDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ActivityEvidencePostgresTest {
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicLong TENANTS = new AtomicLong(28000);
    private static JdbcTemplate jdbc;
    private ActivityEvidenceRepository repository;
    private long tenant;
    private UUID work;
    private UUID audit;
    private UUID event;

    @BeforeAll
    static void migrate() {
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl()); source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration").target("222").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void setup() {
        tenant = TENANTS.incrementAndGet(); work = UUID.randomUUID(); audit = UUID.randomUUID(); event = UUID.randomUUID();
        repository = new ActivityEvidenceRepository(new NamedParameterJdbcTemplate(jdbc));
        jdbc.update("""
                INSERT INTO wrk_items(work_item_id,tenant_id,work_key,title_ko,title_en,work_type,
                  priority,lifecycle_state,owner_name,assignee_user_id,source_system)
                VALUES (?,?,'WK-EVIDENCE','업무','Work','TASK','HIGH','WAITING','Owner',7,'DWP_WORKSPACE')
                """, work, tenant);
        jdbc.update("""
                INSERT INTO sys_platform_audit_events(audit_event_id,tenant_id,actor_type,actor_id,
                  action,target_type,target_id,outcome) VALUES (?,?,'USER',7,'work.changed','WORK_ITEM',?,'SUCCESS')
                """, audit, tenant, work.toString());
        jdbc.update("""
                INSERT INTO wrk_activity_events(activity_event_id,tenant_id,visible_to_user_id,actor_kind,
                  actor_name,event_state,title_ko,title_en,summary_ko,summary_en,object_type,
                  object_label_ko,object_label_en,source_system,event_kind,source_event_id,object_id,
                  work_status,audit_record_id,data_provenance,audit_reference)
                VALUES (?,?,7,'PERSON','User','COMPLETED','변경','Changed','요약','Summary','WORK_ITEM',
                  '업무','Work','DWP_WORKSPACE','CHANGE',?,?,'WAITING',?,'LIVE','LOCAL-TEST')
                """, event, tenant, event.toString(), work.toString(), audit);
    }

    @Test
    void nativeEvidenceRechecksTenantViewerPermissionAndCurrentSourceOwnership() {
        var access = Set.of("APP.ACTIVITY:VIEW", "APP.WORK:VIEW");
        var row = repository.evidence(tenant, 7L, access, event).orElseThrow();
        assertThat(row.auditId()).isEqualTo(audit);
        assertThat(row.recordHash()).isNull();
        assertThat(row.checkpointStatus()).isNull();
        assertThat(repository.evidence(tenant + 1, 7L, access, event)).isEmpty();
        assertThat(repository.evidence(tenant, 8L, access, event)).isEmpty();
        assertThat(repository.evidence(tenant, 7L, Set.of("APP.ACTIVITY:VIEW"), event)).isEmpty();
        jdbc.update("UPDATE wrk_items SET assignee_user_id=8 WHERE work_item_id=?", work);
        assertThat(repository.evidence(tenant, 7L, access, event)).isEmpty();
    }

    @Test
    void centralAgentReceiptRequiresExactActorTenantSourceAndRunTarget() {
        UUID run = UUID.randomUUID();
        centralAudit(audit, run, "dwp-agent-runtime", "AGENT_RUN", "7");
        var receipt = repository.agentEvidence(tenant, 7L, audit).orElseThrow();
        assertThat(receipt.eventId()).isEqualTo(run);
        assertThat(receipt.checkpointStatus()).isNull();
        assertThat(repository.agentEvidence(tenant, 8L, audit)).isEmpty();
        assertThat(repository.agentEvidence(tenant + 1, 7L, audit)).isEmpty();
        UUID foreign = UUID.randomUUID();
        centralAudit(foreign, run, "other-service", "AGENT_RUN", "7");
        assertThat(repository.agentEvidence(tenant, 7L, foreign)).isEmpty();
        UUID plan = UUID.randomUUID();
        centralAudit(plan, run, "dwp-agent-runtime", "AGENT_PLAN", "7");
        assertThat(repository.agentEvidence(tenant, 7L, plan)).isEmpty();
    }

    @Test
    void centralAgentReceiptRejectsNonUserActorsWithTheSameActorIdAndScope() {
        UUID run = UUID.randomUUID();
        UUID service = UUID.randomUUID();
        UUID system = UUID.randomUUID();
        centralAudit(service, run, "dwp-agent-runtime", "AGENT_RUN", "7", "SERVICE");
        centralAudit(system, run, "dwp-agent-runtime", "AGENT_RUN", "7", "SYSTEM");

        assertThat(repository.agentEvidence(tenant, 7L, service)).isEmpty();
        assertThat(repository.agentEvidence(tenant, 7L, system)).isEmpty();
    }

    @Test
    void nativeReceiptMatchesTheExactCentralAuditTimeNotJustAnUnqualifiedId() {
        var access = Set.of("APP.ACTIVITY:VIEW", "APP.WORK:VIEW");
        centralAudit(audit, UUID.randomUUID(), "dwp-agent-runtime", "AGENT_RUN", "7");
        assertThat(repository.evidence(tenant, 7L, access, event).orElseThrow().recordHash()).isNull();
        jdbc.update("""
                INSERT INTO sys_audit_events(event_id,occurred_at,event_version,tenant_id,category,action,
                  outcome,severity,actor_type,actor_id,source_service,source_module,environment,
                  target_type,target_id,record_hash)
                SELECT audit_event_id,occurred_at,'1.0',tenant_id,'ADMIN_CHANGE',action,
                  outcome,'INFO',actor_type,actor_id::text,'dwp-platform-server','test','test',
                  target_type,target_id,? FROM sys_platform_audit_events WHERE tenant_id=? AND audit_event_id=?
                """, "d".repeat(64), tenant, audit);
        var receipt = repository.evidence(tenant, 7L, access, event).orElseThrow();
        assertThat(receipt.recordHash()).isEqualTo("d".repeat(64));
        assertThat(receipt.checkpointStatus()).isNull();
    }

    @Test
    void checkpointIsReportedOnlyForRecordsPresentAtItsCreation() {
        centralAudit(audit, UUID.randomUUID(), "dwp-agent-runtime", "AGENT_RUN", "7");
        jdbc.update("""
                INSERT INTO sys_audit_integrity_checkpoints(tenant_id,checkpoint_date,record_count,
                  first_event_at,last_event_at,root_hash,checkpoint_hash,signature,verification_status,
                  created_at,verified_at)
                SELECT ?, (occurred_at AT TIME ZONE 'UTC')::date,1,occurred_at,occurred_at,?,?,
                  'test-checkpoint','VERIFIED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                  FROM sys_audit_events WHERE tenant_id=? AND event_id=?
                """, tenant, "b".repeat(64), "c".repeat(64), tenant, audit);
        assertThat(repository.agentEvidence(tenant, 7L, audit).orElseThrow().checkpointStatus()).isEqualTo("VERIFIED");
        jdbc.update("UPDATE sys_audit_integrity_checkpoints SET created_at=created_at-INTERVAL '1 day' WHERE tenant_id=?", tenant);
        assertThat(repository.agentEvidence(tenant, 7L, audit).orElseThrow().checkpointStatus()).isNull();
    }

    @Test
    void connectorStatusIsPersonalSourcePermissionScopedAndHasNoSensitiveFields() {
        UUID connector = UUID.randomUUID(), subject = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO int_productivity_connectors(productivity_connector_id,tenant_id,connector_key,
                  display_name,provider_type,auth_mode,lifecycle_state,health_state,policy_state)
                VALUES (?,?,'personal-test','Personal connector','MICROSOFT_GRAPH','DELEGATED',
                  'ACTIVE','HEALTHY','APPROVED')
                """, connector, tenant);
        jdbc.update("""
                INSERT INTO int_productivity_subjects(productivity_subject_id,tenant_id,productivity_connector_id,user_id,consent_state)
                VALUES (?,?,?,7,'CONNECTED')
                """, subject, tenant, connector);
        jdbc.update("""
                INSERT INTO int_productivity_sync_streams(productivity_sync_stream_id,tenant_id,
                  productivity_subject_id,resource_kind,stream_state,last_attempt_at)
                VALUES (?,?,?,'MAIL','STALE',CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), tenant, subject);
        var rows = repository.sources(tenant, 7L, Set.of("APP.MAIL:VIEW"), OffsetDateTime.now());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().status()).isEqualTo("STALE");
        assertThat(rows.getFirst().lastSuccessAt()).isNull();
        assertThat(repository.sources(tenant, 8L, Set.of("APP.MAIL:VIEW"), OffsetDateTime.now())).isEmpty();
        assertThat(repository.sources(tenant, 7L, Set.of("APP.CALENDAR:VIEW"), OffsetDateTime.now())).isEmpty();
        assertThat(repository.sources(tenant + 1, 7L, Set.of("APP.MAIL:VIEW"), OffsetDateTime.now())).isEmpty();

        jdbc.update("UPDATE int_productivity_connectors SET policy_state='BLOCKED' WHERE productivity_connector_id=?",
                connector);
        assertThat(repository.sources(tenant, 7L, Set.of("APP.MAIL:VIEW"), OffsetDateTime.now()))
                .singleElement().extracting(ActivityEvidenceDtos.SourceStatus::status)
                .isEqualTo("BLOCKED");
        jdbc.update("UPDATE int_productivity_connectors SET policy_state='REVIEW_REQUIRED' WHERE productivity_connector_id=?",
                connector);
        assertThat(repository.sources(tenant, 7L, Set.of("APP.MAIL:VIEW"), OffsetDateTime.now()))
                .singleElement().extracting(ActivityEvidenceDtos.SourceStatus::status)
                .isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void localSeedRequiresOptInAndIsIdempotentWithoutOverwritingUserChanges() throws Exception {
        String seed = Files.readString(Path.of("scripts/seed-local-activity-demo.sql"));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        assertThatThrownBy(() -> transaction.execute(status -> { jdbc.execute(seed); return null; }))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        Long localTenant = jdbc.queryForObject("SELECT tenant_id FROM sys_service_tenants WHERE tenant_key='default'", Long.class);
        cleanupLocalFixtures(localTenant);
        jdbc.update("""
                INSERT INTO cal_identity_links(tenant_id,user_id,person_public_id)
                VALUES (?,900018,'8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b') ON CONFLICT DO NOTHING
                """, localTenant);
        for (int attempt = 0; attempt < 2; attempt++) {
            transaction.execute(status -> {
                jdbc.execute("SET LOCAL dwp.activity.seed_profile = 'local-joonbin'");
                jdbc.execute(seed); return null;
            });
            if (attempt == 0) {
                jdbc.update("UPDATE wrk_items SET lifecycle_state='WAITING' WHERE tenant_id=? AND work_key='ACTIVITY-LOCAL-01'", localTenant);
                jdbc.update("""
                        UPDATE int_productivity_sync_streams SET stream_state='SUSPENDED',last_attempt_at='2026-01-01T00:00:00Z'
                         WHERE tenant_id=? AND resource_kind='CALENDAR' AND productivity_subject_id IN
                           (SELECT productivity_subject_id FROM int_productivity_subjects WHERE tenant_id=? AND user_id=900018)
                        """, localTenant, localTenant);
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wrk_activity_events WHERE tenant_id=? AND correlation_id='activity-local-joonbin'", Integer.class, localTenant)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_platform_audit_events WHERE tenant_id=? AND correlation_id='activity-local-joonbin'", Integer.class, localTenant)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM wrk_items WHERE tenant_id=? AND work_key='ACTIVITY-LOCAL-01'", String.class, localTenant)).isEqualTo("WAITING");
        assertThat(repository.sources(localTenant, 900018L,
                Set.of("APP.MAIL:VIEW", "APP.CALENDAR:VIEW"), OffsetDateTime.now())).isEmpty();
        var sources = repository.sources(localTenant, 900018L,
                Set.of("APP.MAIL:VIEW", "APP.CALENDAR:VIEW"), OffsetDateTime.now(), true);
        assertThat(sources).hasSize(3)
                .allMatch(row -> row.semantics().equals("LOCAL_FIXTURE"));
        assertThat(sources).extracting(ActivityEvidenceDtos.SourceStatus::status)
                .containsExactly("BLOCKED", "SUSPENDED", "READY");
        var calendar = sources.stream().filter(row -> row.resourceKind().equals("CALENDAR")).findFirst().orElseThrow();
        assertThat(calendar.lastAttemptAt().toInstant()).isEqualTo(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(jdbc.queryForObject("""
                SELECT stream_state FROM int_productivity_sync_streams
                 WHERE tenant_id=? AND resource_kind='CALENDAR' AND productivity_subject_id IN
                   (SELECT productivity_subject_id FROM int_productivity_subjects
                     WHERE tenant_id=? AND user_id=900018)
                """, String.class, localTenant, localTenant)).isEqualTo("SUSPENDED");
        UUID localEvent = jdbc.queryForObject("""
                SELECT activity_event_id FROM wrk_activity_events
                 WHERE tenant_id=? AND correlation_id='activity-local-joonbin'
                 ORDER BY activity_event_id LIMIT 1
                """, UUID.class, localTenant);
        assertThat(repository.evidence(localTenant, 900018L,
                Set.of("APP.ACTIVITY:VIEW", "APP.WORK:VIEW"), localEvent)).isEmpty();
        assertThat(repository.evidence(localTenant, 900018L,
                Set.of("APP.ACTIVITY:VIEW", "APP.WORK:VIEW"), localEvent, true)).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM int_productivity_connectors WHERE tenant_id=? AND connector_key LIKE 'activity-local-joonbin-%' AND (credential_reference IS NOT NULL OR client_id IS NOT NULL)", Integer.class, localTenant)).isZero();
    }

    @Test
    void localSeedFailsClosedAndRollsBackWhenItsStableWorkIdWasAlreadyReused() throws Exception {
        String seed = Files.readString(Path.of("scripts/seed-local-activity-demo.sql"));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        Long localTenant = jdbc.queryForObject(
                "SELECT tenant_id FROM sys_service_tenants WHERE tenant_key='default'", Long.class);
        cleanupLocalFixtures(localTenant);
        jdbc.update("""
                INSERT INTO cal_identity_links(tenant_id,user_id,person_public_id)
                VALUES (?,900018,'8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b') ON CONFLICT DO NOTHING
                """, localTenant);
        UUID collisionId = jdbc.queryForObject(
                "SELECT md5('activity-local-work:' || ? || ':ACTIVITY-LOCAL-01')::uuid",
                UUID.class, localTenant);
        jdbc.update("""
                INSERT INTO wrk_items(work_item_id,tenant_id,work_key,title_ko,title_en,work_type,
                  priority,lifecycle_state,owner_name,assignee_user_id,source_system)
                VALUES (?,?,'UNRELATED-COLLISION','기존 업무','Existing work','TASK','MEDIUM',
                  'WAITING','Existing',900018,'DWP_WORKSPACE')
                """, collisionId, localTenant);
        try {
            assertThatThrownBy(() -> transaction.execute(status -> {
                jdbc.execute("SET LOCAL dwp.activity.seed_profile = 'local-joonbin'");
                jdbc.execute(seed);
                return null;
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM wrk_activity_events
                     WHERE tenant_id=? AND correlation_id='activity-local-joonbin'
                    """, Integer.class, localTenant)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM sys_platform_audit_events
                     WHERE tenant_id=? AND correlation_id='activity-local-joonbin'
                    """, Integer.class, localTenant)).isZero();
        } finally {
            jdbc.update("DELETE FROM wrk_items WHERE work_item_id=?", collisionId);
        }
    }

    @Test
    void localSeedRejectsAReservedConnectorThatContainsExternalCredentials() throws Exception {
        String seed = Files.readString(Path.of("scripts/seed-local-activity-demo.sql"));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        Long localTenant = jdbc.queryForObject(
                "SELECT tenant_id FROM sys_service_tenants WHERE tenant_key='default'", Long.class);
        cleanupLocalFixtures(localTenant);
        jdbc.update("""
                INSERT INTO cal_identity_links(tenant_id,user_id,person_public_id)
                VALUES (?,900018,'8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b') ON CONFLICT DO NOTHING
                """, localTenant);
        UUID connectorId = jdbc.queryForObject(
                "SELECT md5('activity-local-connector:' || ? || ':primary')::uuid",
                UUID.class, localTenant);
        jdbc.update("""
                INSERT INTO int_productivity_connectors(productivity_connector_id,tenant_id,
                  connector_key,display_name,provider_type,auth_mode,credential_reference,
                  lifecycle_state,health_state,policy_state)
                VALUES (?,?,'activity-local-joonbin-primary','[개발 검증] 개인 연동 primary',
                  'MICROSOFT_GRAPH','DELEGATED','vault://unexpected','ACTIVE','HEALTHY','APPROVED')
                """, connectorId, localTenant);
        try {
            assertThatThrownBy(() -> transaction.execute(status -> {
                jdbc.execute("SET LOCAL dwp.activity.seed_profile = 'local-joonbin'");
                jdbc.execute(seed);
                return null;
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM wrk_activity_events
                     WHERE tenant_id=? AND correlation_id='activity-local-joonbin'
                    """, Integer.class, localTenant)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM sys_platform_audit_events
                     WHERE tenant_id=? AND correlation_id='activity-local-joonbin'
                    """, Integer.class, localTenant)).isZero();
        } finally {
            cleanupLocalFixtures(localTenant);
        }
    }

    private void centralAudit(UUID id, UUID run, String source, String targetType, String actor) {
        centralAudit(id, run, source, targetType, actor, "USER");
    }

    private void centralAudit(
            UUID id, UUID run, String source, String targetType, String actor, String actorType) {
        jdbc.update("""
                INSERT INTO sys_audit_events(event_id,occurred_at,event_version,tenant_id,category,action,
                  outcome,severity,actor_type,actor_id,source_service,source_module,environment,
                  target_type,target_id,record_hash)
                VALUES (?,CURRENT_TIMESTAMP,'1.0',?,'AI_ACTION','agent.ask.evaluated','SUCCESS','INFO',
                  ?,?,?,'test','test',?,?,?)
                """, id, tenant, actorType, actor, source, targetType, run.toString(), "a".repeat(64));
    }

    private void cleanupLocalFixtures(Long localTenant) {
        jdbc.update("DELETE FROM wrk_activity_events WHERE tenant_id=? AND correlation_id='activity-local-joonbin'",
                localTenant);
        jdbc.update("DELETE FROM sys_platform_audit_events WHERE tenant_id=? AND correlation_id='activity-local-joonbin'",
                localTenant);
        jdbc.update("DELETE FROM wrk_items WHERE tenant_id=? AND work_key LIKE 'ACTIVITY-LOCAL-%'",
                localTenant);
        jdbc.update("""
                DELETE FROM int_productivity_sync_streams WHERE tenant_id=?
                 AND productivity_subject_id IN (SELECT productivity_subject_id
                   FROM int_productivity_subjects WHERE tenant_id=? AND user_id=900018)
                """, localTenant, localTenant);
        jdbc.update("""
                DELETE FROM int_productivity_subjects WHERE tenant_id=? AND user_id=900018
                 AND productivity_connector_id IN (SELECT productivity_connector_id
                   FROM int_productivity_connectors WHERE tenant_id=?
                     AND connector_key LIKE 'activity-local-joonbin-%')
                """, localTenant, localTenant);
        jdbc.update("""
                DELETE FROM int_productivity_connectors WHERE tenant_id=?
                 AND connector_key LIKE 'activity-local-joonbin-%'
                """, localTenant);
    }
}
