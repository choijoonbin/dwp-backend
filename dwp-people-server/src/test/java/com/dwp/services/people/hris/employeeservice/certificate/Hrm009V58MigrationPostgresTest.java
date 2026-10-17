package com.dwp.services.people.hris.employeeservice.certificate;

import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class Hrm009V58MigrationPostgresTest {

    private static final long TENANT_ONE = 58_001L;
    private static final long TENANT_TWO = 58_002L;
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private Flyway migrated;

    @AfterEach
    void clean() {
        PeopleRequestContext.clear();
        if (migrated != null) migrated.clean();
    }

    @Test
    void cleanMigrationThroughV58BuildsTheExactSchemaRlsIndexesAndLockedGuards() {
        DataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        migrated = flyway(source, null);
        migrated.clean();

        migrated.migrate();

        assertThat(migrated.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("58"));
        assertThat(jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                 WHERE schemaname='public'
                   AND tablename IN ('ppl_certificate_requests','ppl_issued_certificates')
                 ORDER BY tablename
                """, String.class)).containsExactly(
                "ppl_certificate_requests", "ppl_issued_certificates");
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                 WHERE schemaname='public'
                   AND indexname IN (
                       'idx_ppl_certificate_requests_work_queue',
                       'uidx_ppl_command_receipts_tenant_command_idempotency')
                 ORDER BY indexname
                """, String.class)).containsExactly(
                "idx_ppl_certificate_requests_work_queue",
                "uidx_ppl_command_receipts_tenant_command_idempotency");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_class
                 WHERE relname IN ('ppl_certificate_requests','ppl_issued_certificates')
                   AND relrowsecurity AND relforcerowsecurity
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                 WHERE schemaname='public'
                   AND policyname IN (
                       'ppl_certificate_requests_tenant_policy',
                       'ppl_issued_certificates_tenant_policy')
                   AND qual LIKE '%NULLIF(current_setting(''dwp.tenant_id''::text, true), ''''::text)%'
                   AND with_check LIKE '%NULLIF(current_setting(''dwp.tenant_id''::text, true), ''''::text)%'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_proc
                 WHERE pronamespace='public'::regnamespace
                   AND proname IN (
                       'ppl_guard_certificate_request_state',
                       'ppl_guard_issued_certificate_state')
                   AND prosecdef
                   AND proconfig @> ARRAY['search_path=pg_catalog, public, pg_temp']::text[]
                   AND proacl IS NOT NULL
                   AND NOT EXISTS (
                       SELECT 1
                         FROM aclexplode(proacl) acl
                        WHERE acl.grantee=0 AND acl.privilege_type='EXECUTE'
                   )
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void upgradeFromV57PreservesRowsAndEveryHistoricalChecksum() {
        DataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Flyway throughV57 = flyway(source, "57");
        throughV57.clean();
        throughV57.migrate();
        Map<String, Integer> historical = checksums(throughV57, 57);
        insertReceipt(jdbc, "certificate-upgrade-key-0001",
                "CERTIFICATE_REQUEST", UUID.randomUUID(), "REQUEST_CERTIFICATE",
                UUID.randomUUID(), UUID.randomUUID());
        int before = jdbc.queryForObject(
                "SELECT count(*) FROM ppl_command_receipts", Integer.class);

        migrated = flyway(source, null);
        assertThat(migrated.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(migrated.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("58"));
        assertThat(checksums(migrated, 57)).containsExactlyEntriesOf(historical);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ppl_command_receipts", Integer.class))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                 WHERE indexname='uidx_ppl_command_receipts_tenant_command_idempotency'
                """, Integer.class)).isOne();
    }

    @Test
    void duplicateReceiptPreflightFailsMigrationWithoutDeletionMergeOrRewrite() {
        DataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Flyway throughV57 = flyway(source, "57");
        throughV57.clean();
        throughV57.migrate();
        String key = "certificate-duplicate-key-0001";
        insertReceipt(jdbc, key, "CERTIFICATE_REQUEST", UUID.randomUUID(),
                "REQUEST_CERTIFICATE", UUID.randomUUID(), UUID.randomUUID());
        insertReceipt(jdbc, key, "CERTIFICATE_REQUEST", UUID.randomUUID(),
                "REQUEST_CERTIFICATE_ALT", UUID.randomUUID(), UUID.randomUUID());
        Map<UUID, String> before = receiptHashes(jdbc, key);

        migrated = flyway(source, null);

        assertThatThrownBy(migrated::migrate)
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .rootCause().hasMessageContaining(
                        "duplicate tenant/command/idempotency receipt prevents HRM-009 V58");
        assertThat(receiptHashes(jdbc, key)).containsExactlyInAnyOrderEntriesOf(before);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                 WHERE indexname='uidx_ppl_command_receipts_tenant_command_idempotency'
                """, Integer.class)).isZero();
    }

    @Test
    void strictReceiptIndexRejectsSameTenantCommandAndKeyAcrossLegacySubjects() {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        String key = "certificate-strict-key-000001";
        insertReceipt(jdbc, key, "CERTIFICATE_REQUEST", UUID.randomUUID(),
                "REQUEST_CERTIFICATE", UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> insertReceipt(
                jdbc, key, "CERTIFICATE_REQUEST", UUID.randomUUID(),
                "REQUEST_CERTIFICATE_ALT", UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "uidx_ppl_command_receipts_tenant_command_idempotency");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ppl_command_receipts
                 WHERE tenant_id=1 AND command_type='CERTIFICATE_REQUEST'
                   AND idempotency_key=?
                """, Integer.class, key)).isOne();
    }

    @Test
    void forceRlsHidesAndRejectsCrossTenantRowsForANonOwnerRole() throws Exception {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        long personOne = insertPerson(jdbc, TENANT_ONE, "rls-one");
        insertPerson(jdbc, TENANT_TWO, "rls-two");
        UUID seededRequest = UUID.fromString(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa0");
        jdbc.update(requestInsert(
                TENANT_ONE, personOne, personOne, seededRequest.toString(),
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb0"));
        long seededRequestId = jdbc.queryForObject("""
                SELECT certificate_request_id FROM ppl_certificate_requests
                 WHERE public_id=?
                """, Long.class, seededRequest);
        jdbc.update("""
                UPDATE ppl_certificate_requests
                   SET lifecycle_state='VALIDATING',version=1
                 WHERE certificate_request_id=?
                """, seededRequestId);
        jdbc.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='hrm009_rls_actor') THEN
                        CREATE ROLE hrm009_rls_actor NOLOGIN;
                    END IF;
                END $$
                """);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO hrm009_rls_actor");
        jdbc.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ppl_certificate_requests,ppl_issued_certificates TO hrm009_rls_actor");
        jdbc.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO hrm009_rls_actor");

        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET ROLE hrm009_rls_actor");
            assertThat(count(statement,
                    "SELECT count(*) FROM ppl_certificate_requests")).isZero();
            assertThat(count(statement,
                    "SELECT count(*) FROM ppl_issued_certificates")).isZero();
            assertRlsRejected(connection, statement, requestInsert(
                    TENANT_ONE, personOne, personOne,
                    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa6",
                    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb6"));
            assertRlsRejected(connection, statement, issuedInsert(
                    TENANT_ONE, seededRequestId,
                    "cccccccc-cccc-4ccc-8ccc-ccccccccccc6", null));

            statement.execute("SELECT set_config('dwp.tenant_id','" + TENANT_ONE
                    + "',false)");
            statement.executeUpdate(requestInsert(
                    TENANT_ONE, personOne, personOne,
                    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1",
                    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1"));
            long requestId;
            try (ResultSet result = statement.executeQuery("""
                    SELECT certificate_request_id FROM ppl_certificate_requests
                     WHERE public_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1'
                    """)) {
                result.next();
                requestId = result.getLong(1);
            }
            statement.executeUpdate("""
                    UPDATE ppl_certificate_requests
                       SET lifecycle_state='VALIDATING',version=1
                     WHERE certificate_request_id=%d
                    """.formatted(requestId));
            statement.executeUpdate(issuedInsert(
                    TENANT_ONE, requestId,
                    "cccccccc-cccc-4ccc-8ccc-ccccccccccc1", null));
            assertThat(count(statement,
                    "SELECT count(*) FROM ppl_certificate_requests")).isEqualTo(2);
            assertThat(count(statement,
                    "SELECT count(*) FROM ppl_issued_certificates")).isOne();
            statement.execute("SELECT set_config('dwp.tenant_id','" + TENANT_TWO
                    + "',false)");
            assertThat(count(statement,
                    "SELECT count(*) FROM ppl_certificate_requests")).isZero();
            assertThat(count(statement,
                    "SELECT count(*) FROM ppl_issued_certificates")).isZero();
            assertRlsRejected(connection, statement, requestInsert(
                    TENANT_ONE, personOne, personOne,
                    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2",
                    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2"));
            assertRlsRejected(connection, statement, issuedInsert(
                    TENANT_ONE, seededRequestId,
                    "cccccccc-cccc-4ccc-8ccc-ccccccccccc2", null)
                    .replace(SHA_B, SHA_C));
            connection.rollback();
        }
    }

    @Test
    void stateGuardsEnforceInitialStateTransitionsImmutabilityDeletionAndRevokeTiming() {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        long person = insertPerson(jdbc, TENANT_ONE, "guard-person");

        assertThatThrownBy(() -> jdbc.update(requestInsert(
                TENANT_ONE, person, person,
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb3")
                .replace("'REQUESTED'", "'VALIDATING'")
                .replace(",0,7,7)", ",1,7,7)")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "certificate requests must begin REQUESTED at version zero");

        UUID requestPublicId = UUID.fromString(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa4");
        jdbc.update(requestInsert(
                TENANT_ONE, person, person, requestPublicId.toString(),
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb4"));
        long requestId = jdbc.queryForObject("""
                SELECT certificate_request_id FROM ppl_certificate_requests
                 WHERE public_id=?
                """, Long.class, requestPublicId);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_certificate_requests
                   SET certificate_type='changed',lifecycle_state='VALIDATING',version=1
                 WHERE certificate_request_id=?
                """, requestId)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("originating metadata is immutable");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_certificate_requests
                   SET lifecycle_state='VALIDATING',version=2
                 WHERE certificate_request_id=?
                """, requestId)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("version must advance exactly once");
        jdbc.update("""
                UPDATE ppl_certificate_requests
                   SET lifecycle_state='VALIDATING',version=1
                 WHERE certificate_request_id=?
                """, requestId);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_certificate_requests
                   SET lifecycle_state='ISSUED',version=2,completed_at=NULL
                 WHERE certificate_request_id=?
                """, requestId)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("requires completed_at");

        UUID issuedPublicId = UUID.fromString(
                "cccccccc-cccc-4ccc-8ccc-ccccccccccc4");
        jdbc.update(issuedInsert(
                TENANT_ONE, requestId, issuedPublicId.toString(), null));
        long issuedId = jdbc.queryForObject("""
                SELECT issued_certificate_id FROM ppl_issued_certificates
                 WHERE public_id=?
                """, Long.class, issuedPublicId);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_issued_certificates
                   SET object_version=2,lifecycle_state='EXPIRED'
                 WHERE issued_certificate_id=?
                """, issuedId)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("immutable metadata changed");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_issued_certificates
                   SET lifecycle_state='REVOKED',
                       revoked_at=clock_timestamp()+INTERVAL '2 days',
                       revoke_reason_code='CORRECTION',
                       expires_at=clock_timestamp()+INTERVAL '1 day'
                 WHERE issued_certificate_id=?
                """, issuedId)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("revocation metadata or timing is invalid");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ppl_issued_certificates WHERE issued_certificate_id=?",
                issuedId)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("history cannot be deleted");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ppl_certificate_requests WHERE certificate_request_id=?",
                requestId)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("history cannot be deleted");

        jdbc.update("""
                UPDATE ppl_certificate_requests
                   SET lifecycle_state='ISSUED',version=2,completed_at=clock_timestamp()
                 WHERE certificate_request_id=?
                """, requestId);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_certificate_requests
                   SET lifecycle_state='VALIDATING',version=3
                 WHERE certificate_request_id=?
                """, requestId)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("illegal certificate request state transition");
    }

    @Test
    void retryableConformanceFailureThroughServiceRollsBackEveryDurableEffect() {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,
                    lifecycle_state,data_region,isolation_model)
                VALUES('58001000-0000-4000-8000-000000000001',?,
                    'hrm009-artifact-rollback','HRM-009 Artifact Rollback',
                    'ACTIVE','test','POOL')
                """, TENANT_ONE);
        long personId = insertPerson(jdbc, TENANT_ONE, "artifact-rollback-person");
        Map<String, Object> person = jdbc.queryForMap("""
                SELECT public_id,version FROM ppl_persons WHERE person_id=?
                """, personId);
        UUID personPublicId = (UUID) person.get("public_id");
        long version = ((Number) person.get("version")).longValue();
        UUID correlationId = UUID.fromString(
                "77777777-7777-4777-8777-777777777777");
        String idempotencyKey = "artifact-rollback-key-0001";
        Instant now = Instant.parse("2026-10-18T03:00:00Z");
        PeopleRequestContext.set(
                7L, TENANT_ONE, personPublicId,
                Set.of("HRIS_EMPLOYEE", "HRIS_DUTY_SELF_CERTIFICATE_REQUESTER"),
                Set.of("ACTION.HRIS_CERTIFICATE:CREATE"));
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        CertificateAuthority.CommandDecision decision =
                new CertificateAuthority.CommandDecision(
                        actor, true, CertificateAuthority.SELF_PROFILE,
                        CertificateAuthority.SELF_CAPABILITY,
                        CertificateAuthority.SELF_PURPOSE, null,
                        "self:hcm-scope-" + "b".repeat(40),
                        Set.of("DIRECTORY", "DOCUMENT_CONTENT", "EMPLOYMENT"),
                        "psr-" + "c".repeat(64));
        CertificateAuthority authority = mock(CertificateAuthority.class);
        when(authority.authorizeCommand(
                personPublicId, CertificateAuthority.SELF_PURPOSE, null))
                .thenReturn(decision);
        when(authority.requireMutationScope(decision)).thenReturn(decision);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CertificateRepository repository = new CertificateRepository(
                new NamedParameterJdbcTemplate(source), mapper);
        CertificateService service = new CertificateService(
                new Hrm009RolloutGate(Set.of(TENANT_ONE)), authority, repository,
                mock(HcmPopulationRepository.class), mock(CertificateProjector.class),
                mock(CertificateCursorCodec.class), new CertificateCanonicalizer(mapper),
                new CertificateArtifactPortConformanceTest.ConformanceDouble(true),
                mock(HcmHighRiskCommandGuard.class), mapper,
                Clock.fixed(now, ZoneOffset.UTC));
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        CertificateDtos.RequestCertificate request =
                new CertificateDtos.RequestCertificate(
                        personPublicId, "employment-letter",
                        CertificateAuthority.SELF_PURPOSE, "en-US", now);
        HcmStepUpHeaders stepUp = new HcmStepUpHeaders(
                "signed-challenge", idempotencyKey,
                decision.authorizationRevision(), version);

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                service.requestCertificate(
                        request, idempotencyKey, "\"v:" + version + "\"",
                        correlationId, null, stepUp)))
                .isInstanceOf(CertificateException.class)
                .satisfies(failure ->
                        assertThat(((CertificateException) failure).retryable()).isTrue());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ppl_certificate_requests WHERE tenant_id=?",
                Integer.class, TENANT_ONE)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ppl_issued_certificates WHERE tenant_id=?",
                Integer.class, TENANT_ONE)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ppl_command_receipts
                 WHERE tenant_id=? AND command_type='CERTIFICATE_REQUEST'
                   AND idempotency_key=?
                """, Integer.class, TENANT_ONE, idempotencyKey)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_people_audit_events WHERE tenant_id=?",
                Integer.class, TENANT_ONE)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_outbox_events
                 WHERE tenant_id=? AND event_type='CertificateIssued.v1'
                """, Integer.class, TENANT_ONE)).isZero();
    }

    @Test
    void transactionRollbackLeavesNoRequestIssuedReceiptSuccessAuditOrOutbox() throws Exception {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        long person = insertPerson(jdbc, 1L, "rollback-person");
        UUID requestPublicId = UUID.fromString(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa5");
        UUID certificatePublicId = UUID.fromString(
                "cccccccc-cccc-4ccc-8ccc-ccccccccccc5");
        UUID receiptId = UUID.fromString(
                "dddddddd-dddd-4ddd-8ddd-ddddddddddd5");
        UUID auditId = UUID.fromString(
                "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee5");
        UUID eventId = UUID.fromString(
                "ffffffff-ffff-4fff-8fff-fffffffffff5");

        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate(requestInsert(
                    1L, person, person, requestPublicId.toString(),
                    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb5"));
            long requestId;
            try (ResultSet result = statement.executeQuery("""
                    SELECT certificate_request_id FROM ppl_certificate_requests
                     WHERE public_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa5'
                    """)) {
                result.next();
                requestId = result.getLong(1);
            }
            statement.executeUpdate("UPDATE ppl_certificate_requests SET lifecycle_state='VALIDATING',version=1 WHERE certificate_request_id=" + requestId);
            statement.executeUpdate(issuedInsert(
                    1L, requestId, certificatePublicId.toString(), null));
            statement.executeUpdate("UPDATE ppl_certificate_requests SET lifecycle_state='ISSUED',version=2,completed_at=clock_timestamp() WHERE certificate_request_id=" + requestId);
            statement.executeUpdate("""
                    INSERT INTO ppl_command_receipts(
                        command_receipt_id,tenant_id,command_type,idempotency_key,
                        request_hash,actor_public_id,originating_action,
                        subject_principal_public_id,population_scope_digest,
                        field_policy_revision,purpose_code,authorization_revision,
                        originating_context_digest,target_type,target_public_id,
                        expected_version,lifecycle_state,result_code,result_reference,
                        correlation_id,completed_at)
                    VALUES('dddddddd-dddd-4ddd-8ddd-ddddddddddd5',1,
                        'CERTIFICATE_REQUEST','rollback-key-00000001','%s',
                        '11111111-1111-4111-8111-111111111111','REQUEST_CERTIFICATE',
                        '11111111-1111-4111-8111-111111111111','%s',1,
                        'EMPLOYEE_SELF_SERVICE',1,'%s',
                        'CERTIFICATE_REQUEST','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa5',
                        0,'SUCCEEDED','CERTIFICATE_ISSUED','{"requestVersion":2}',
                        'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb5',clock_timestamp())
                    """.formatted(SHA_A, SHA_B, SHA_C));
            statement.executeUpdate("""
                    INSERT INTO sys_people_audit_events(
                        audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                        target_id,outcome,correlation_id,after_snapshot)
                    VALUES('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee5',1,'USER','7',
                        'certificate.issued','CERTIFICATE_REQUEST',
                        'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa5','SUCCESS',
                        'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb5','{"lifecycleState":"ISSUED"}')
                    """);
            statement.executeUpdate("""
                    INSERT INTO sys_people_outbox_events(
                        event_id,tenant_id,aggregate_type,aggregate_id,event_type,
                        payload,correlation_id)
                    VALUES('ffffffff-ffff-4fff-8fff-fffffffffff5',1,
                        'CERTIFICATE_REQUEST','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa5',
                        'CertificateIssued.v1',
                        '{"requestPublicId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa5",\
                          "certificatePublicId":"cccccccc-cccc-4ccc-8ccc-ccccccccccc5",\
                          "subjectPersonPublicId":"11111111-1111-4111-8111-111111111111",\
                          "certificateType":"employment-letter",\
                          "issuedAt":"2026-10-18T03:00:00Z","expiresAt":null}',
                        'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb5')
                    """);
            connection.rollback();
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ppl_certificate_requests WHERE public_id=?",
                Integer.class, requestPublicId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ppl_issued_certificates WHERE public_id=?",
                Integer.class, certificatePublicId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ppl_command_receipts WHERE command_receipt_id=?",
                Integer.class, receiptId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_people_audit_events WHERE audit_event_id=?",
                Integer.class, auditId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_people_outbox_events WHERE event_id=?",
                Integer.class, eventId)).isZero();
    }

    private DataSource migratedSource() {
        DataSource source = dataSource();
        migrated = flyway(source, null);
        migrated.clean();
        migrated.migrate();
        return source;
    }

    private long insertPerson(JdbcTemplate jdbc, long tenantId, String key) {
        return jdbc.queryForObject("""
                INSERT INTO ppl_persons(tenant_id,person_key,display_name)
                VALUES(?,?,?) RETURNING person_id
                """, Long.class, tenantId, key, "Person " + key);
    }

    private String requestInsert(
            long tenantId,
            long requester,
            long subject,
            String publicId,
            String correlationId) {
        return """
                INSERT INTO ppl_certificate_requests(
                    public_id,tenant_id,requester_person_id,subject_person_id,
                    certificate_type,locale,purpose_code,lifecycle_state,
                    template_public_id,template_version,correlation_id,version,
                    created_by,updated_by)
                VALUES('%s',%d,%d,%d,'employment-letter','en-US',
                    'EMPLOYEE_SELF_SERVICE','REQUESTED',
                    '99999999-9999-4999-8999-999999999999',1,'%s',0,7,7)
                """.formatted(publicId, tenantId, requester, subject, correlationId);
    }

    private String issuedInsert(
            long tenantId,
            long requestId,
            String publicId,
            String expiresSql) {
        String expiry = expiresSql == null ? "NULL" : expiresSql;
        return """
                INSERT INTO ppl_issued_certificates(
                    public_id,tenant_id,certificate_request_id,object_public_id,
                    object_version,content_sha256,verification_token_hash,
                    lifecycle_state,issued_at,expires_at,created_by,updated_by)
                VALUES('%s',%d,%d,'88888888-8888-4888-8888-888888888888',
                    1,'%s','%s','ISSUED',clock_timestamp(),%s,7,7)
                """.formatted(publicId, tenantId, requestId, SHA_A, SHA_B, expiry);
    }

    private void insertReceipt(
            JdbcTemplate jdbc,
            String key,
            String commandType,
            UUID subject,
            String action,
            UUID correlation,
            UUID target) {
        jdbc.update("""
                INSERT INTO ppl_command_receipts(
                    tenant_id,command_type,idempotency_key,request_hash,
                    actor_public_id,originating_action,subject_principal_public_id,
                    population_scope_digest,field_policy_revision,purpose_code,
                    authorization_revision,originating_context_digest,target_type,
                    target_public_id,expected_version,lifecycle_state,correlation_id)
                VALUES(1,?,?,?,?::UUID,?,?::UUID,?,1,'EMPLOYEE_SELF_SERVICE',
                    1,?,'CERTIFICATE_REQUEST',?::UUID,0,'RUNNING',?::UUID)
                """, commandType, key, SHA_A,
                "11111111-1111-4111-8111-111111111111", action,
                subject.toString(), SHA_B, SHA_C, target.toString(),
                correlation.toString());
    }

    private Map<UUID, String> receiptHashes(JdbcTemplate jdbc, String key) {
        Map<UUID, String> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT command_receipt_id,request_hash
                  FROM ppl_command_receipts
                 WHERE tenant_id=1 AND command_type='CERTIFICATE_REQUEST'
                   AND idempotency_key=?
                 ORDER BY command_receipt_id
                """, key)) {
            result.put((UUID) row.get("command_receipt_id"),
                    row.get("request_hash").toString());
        }
        return result;
    }

    private int count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private void assertRlsRejected(
            Connection connection,
            Statement statement,
            String sql) throws Exception {
        java.sql.Savepoint savepoint = connection.setSavepoint();
        assertThatThrownBy(() -> statement.executeUpdate(sql))
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("row-level security policy");
        connection.rollback(savepoint);
    }

    private Map<String, Integer> checksums(Flyway flyway, int maximum) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (MigrationInfo info : flyway.info().applied()) {
            if (info.getVersion() != null
                    && Integer.parseInt(info.getVersion().getVersion()) <= maximum) {
                result.put(info.getVersion().getVersion(), info.getChecksum());
            }
        }
        return result;
    }

    private Flyway flyway(DataSource source, String target) {
        var configuration = Flyway.configure(getClass().getClassLoader())
                .dataSource(source)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}
