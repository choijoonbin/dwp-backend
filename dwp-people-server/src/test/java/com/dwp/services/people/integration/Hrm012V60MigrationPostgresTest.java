package com.dwp.services.people.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class Hrm012V60MigrationPostgresTest {

    private static final long TENANT_ONE = 60_001L;
    private static final long TENANT_TWO = 60_002L;
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);
    private static final String SHA_D = "d".repeat(64);
    private static final String SHA_E = "e".repeat(64);
    private static final String SHA_F = "f".repeat(64);
    private static final List<String> TABLES = List.of(
            "ppl_workforce_ingestion_runs",
            "ppl_workforce_ingestion_items");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private Flyway migrated;

    @AfterEach
    void clean() {
        if (migrated != null) migrated.clean();
    }

    @Test
    void cleanMigrationThroughV60BuildsOnlyTheTwoTablesAndLockedControls() {
        DataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        migrated = flyway(source, null);
        migrated.clean();

        migrated.migrate();

        assertThat(migrated.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("60"));
        assertThat(jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                 WHERE schemaname='public'
                   AND tablename IN (
                       'ppl_workforce_ingestion_runs',
                       'ppl_workforce_ingestion_items')
                 ORDER BY tablename
                """, String.class)).containsExactly(
                "ppl_workforce_ingestion_items",
                "ppl_workforce_ingestion_runs");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_class
                 WHERE relname IN (
                       'ppl_workforce_ingestion_runs',
                       'ppl_workforce_ingestion_items')
                   AND relrowsecurity AND relforcerowsecurity
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                 WHERE schemaname='public'
                   AND policyname IN (
                       'ppl_workforce_ingestion_runs_tenant_policy',
                       'ppl_workforce_ingestion_items_tenant_policy')
                   AND qual LIKE '%current_setting(''dwp.tenant_id''::text, true)%'
                   AND with_check LIKE '%current_setting(''dwp.tenant_id''::text, true)%'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_proc
                 WHERE pronamespace='public'::regnamespace
                   AND proname IN (
                       'ppl_guard_workforce_ingestion_run',
                       'ppl_guard_workforce_ingestion_item',
                       'ppl_guard_workforce_ingestion_terminal_atomic',
                       'ppl_guard_hrm012_reconciled_outbox',
                       'ppl_guard_hrm012_shared_atomic',
                       'ppl_guard_hrm012_receipt_authority')
                   AND prosecdef
                   AND proconfig @> ARRAY[
                       'search_path=pg_catalog, public, pg_temp']::text[]
                   AND proacl IS NOT NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM aclexplode(proacl) acl
                        WHERE acl.grantee=0 AND acl.privilege_type='EXECUTE')
                """, Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger
                 WHERE tgrelid IN (
                       'ppl_workforce_ingestion_runs'::regclass,
                       'ppl_workforce_ingestion_items'::regclass)
                   AND NOT tgisinternal
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger
                 WHERE tgrelid='sys_people_outbox_events'::regclass
                   AND tgname IN (
                       'trg_sys_people_outbox_hrm012_reconciled_guard',
                       'trg_sys_people_outbox_hrm012_atomic')
                   AND NOT tgisinternal
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger
                 WHERE tgname IN (
                       'trg_int_connector_cursors_hrm012_atomic',
                       'trg_ppl_command_receipts_hrm012_atomic',
                       'trg_sys_people_audit_events_hrm012_atomic',
                       'trg_sys_people_outbox_hrm012_atomic')
                   AND tgdeferrable AND tginitdeferred AND NOT tgisinternal
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger
                 WHERE tgrelid='ppl_command_receipts'::regclass
                   AND tgname='trg_ppl_command_receipts_hrm012_authority'
                   AND NOT tgdeferrable AND NOT tgisinternal
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                 WHERE schemaname='public'
                   AND indexname IN (
                       'idx_ppl_workforce_ingestion_runs_work_queue',
                       'idx_ppl_workforce_ingestion_runs_stable',
                       'idx_ppl_workforce_ingestion_runs_connector',
                       'idx_ppl_workforce_ingestion_runs_replay',
                       'idx_ppl_workforce_ingestion_items_stable',
                       'idx_ppl_workforce_ingestion_items_duplicate',
                       'idx_ppl_workforce_ingestion_items_outcome',
                       'uk_sys_people_outbox_hrm012_reconciled')
                """, String.class)).hasSize(8);
    }

    @Test
    void upgradeFromV59PreservesRowsAndEveryHistoricalChecksum() {
        DataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Flyway throughV59 = flyway(source, "59");
        throughV59.clean();
        throughV59.migrate();
        Map<String, Integer> historical = checksums(throughV59, 59);
        ensureTenant(jdbc, TENANT_ONE, "upgrade");
        long sourceId = insertSource(jdbc, TENANT_ONE, "upgrade");

        migrated = flyway(source, null);
        assertThat(migrated.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(migrated.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("60"));
        assertThat(checksums(migrated, 59)).containsExactlyEntriesOf(historical);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM int_source_systems
                 WHERE tenant_id=? AND source_system_id=?
                """, Integer.class, TENANT_ONE, sourceId)).isOne();
        assertThat(jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                 WHERE schemaname='public'
                   AND tablename IN (
                       'ppl_workforce_ingestion_runs',
                       'ppl_workforce_ingestion_items')
                """, String.class)).hasSize(2);
    }

    @Test
    void receiptFrozenAuthorityRequiresClosedShapeAndIsImmutableOnlyAtItsSubtree() {
        JdbcTemplate jdbc = new JdbcTemplate(migratedSource());
        ensureTenant(jdbc, TENANT_ONE, "receipt-authority");
        UUID identity = UUID.randomUUID();

        assertThatThrownBy(() -> insertReceipt(
                jdbc, TENANT_ONE, "missing-frozen-authority",
                "WORKFORCE_INGESTION", "INITIATE_WORKFORCE_INGESTION",
                "HRIS_INTEGRATION", "WORKFORCE_INGESTION_OBJECT",
                identity, identity, ignored -> "{}"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "receipt requires frozen authority object");

        assertThatThrownBy(() -> insertReceipt(
                jdbc, TENANT_ONE, "extra-frozen-authority",
                "WORKFORCE_INGESTION", "INITIATE_WORKFORCE_INGESTION",
                "HRIS_INTEGRATION", "WORKFORCE_INGESTION_OBJECT",
                identity, identity, reference -> reference.replace(
                        "\"canonicalRequestHash\": \"" + SHA_A + "\"",
                        "\"canonicalRequestHash\": \"" + SHA_A
                                + "\", \"unexpected\": true")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "frozen receipt authority shape is not closed");

        assertThatThrownBy(() -> insertReceipt(
                jdbc, TENANT_ONE, "missing-frozen-authority-field",
                "WORKFORCE_INGESTION", "INITIATE_WORKFORCE_INGESTION",
                "HRIS_INTEGRATION", "WORKFORCE_INGESTION_OBJECT",
                identity, identity, reference -> reference.replace(
                        "\"featureDecisionRevision\": \"feature-r1\",", "")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "frozen receipt authority shape is not closed");

        assertThatThrownBy(() -> insertReceipt(
                jdbc, TENANT_ONE, "typed-frozen-authority",
                "WORKFORCE_INGESTION", "INITIATE_WORKFORCE_INGESTION",
                "HRIS_INTEGRATION", "WORKFORCE_INGESTION_OBJECT",
                identity, identity, reference -> reference.replace(
                        "\"populationRevision\": 1",
                        "\"populationRevision\": \"1\"")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "frozen receipt authority revisions are invalid");

        UUID mismatchedIdentity = UUID.randomUUID();
        UUID mismatchedCorrelation = UUID.randomUUID();
        Map<String, UnaryOperator<String>> identityMismatches =
                new LinkedHashMap<>();
        identityMismatches.put("tenant", reference -> reference.replace(
                "\"tenantId\": " + TENANT_ONE,
                "\"tenantId\": " + TENANT_TWO));
        identityMismatches.put("person", reference -> reference.replace(
                "\"personPublicId\": \"" + identity + "\"",
                "\"personPublicId\": \"" + mismatchedIdentity + "\""));
        identityMismatches.put("population", reference -> reference.replace(
                "\"populationScopeDigest\": \"" + SHA_B + "\"",
                "\"populationScopeDigest\": \"" + SHA_F + "\""));
        identityMismatches.put("field-policy", reference -> reference.replace(
                "\"fieldPolicyRevision\": 1",
                "\"fieldPolicyRevision\": 2"));
        identityMismatches.put("authorization", reference -> reference.replace(
                "\"authorizationRevision\": 1",
                "\"authorizationRevision\": 2"));
        identityMismatches.put("correlation", reference -> reference.replaceFirst(
                "\"correlationId\": \"[^\"]+\"",
                "\"correlationId\": \"" + mismatchedCorrelation + "\""));
        identityMismatches.put("request-hash", reference -> reference.replace(
                "\"canonicalRequestHash\": \"" + SHA_A + "\"",
                "\"canonicalRequestHash\": \"" + SHA_F + "\""));
        identityMismatches.forEach((name, mutation) ->
                assertThatThrownBy(() -> insertReceipt(
                        jdbc, TENANT_ONE, "mismatch-" + name,
                        "WORKFORCE_INGESTION",
                        "INITIATE_WORKFORCE_INGESTION",
                        "HRIS_INTEGRATION", "WORKFORCE_INGESTION_OBJECT",
                        identity, identity, mutation))
                        .isInstanceOf(DataAccessException.class)
                        .rootCause().hasMessageContaining(
                                "frozen receipt authority does not match receipt identity"));
        assertThatThrownBy(() -> insertReceipt(
                jdbc, TENANT_ONE, "mismatch-purpose",
                "WORKFORCE_INGESTION", "INITIATE_WORKFORCE_INGESTION",
                "OTHER_PURPOSE", "WORKFORCE_INGESTION_OBJECT",
                identity, identity, null))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "frozen receipt authority does not match receipt identity");
        assertThatThrownBy(() -> insertReceipt(
                jdbc, TENANT_ONE, "mismatch-subject",
                "WORKFORCE_INGESTION", "INITIATE_WORKFORCE_INGESTION",
                "HRIS_INTEGRATION", "WORKFORCE_INGESTION_OBJECT",
                identity, mismatchedIdentity, null))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "frozen receipt authority does not match receipt identity");

        UUID receipt = insertReceipt(
                jdbc, TENANT_ONE, "immutable-frozen-authority");
        String initialAuthority = markerAuthorityReference(
                currentMarkerEvidence(
                        "hrm012-marker-jti", "2999-10-22T00:00:00Z", SHA_C,
                        "UNVERIFIED", "2026-10-22T00:00:00Z", null));
        String frozenBefore = jdbc.queryForObject("""
                SELECT (result_reference->'hrm012FrozenAuthority')::TEXT
                  FROM ppl_command_receipts
                 WHERE tenant_id=? AND command_receipt_id=?
                """, String.class, TENANT_ONE, receipt);
        assertThat(jdbc.update("""
                UPDATE ppl_command_receipts
                   SET result_reference=result_reference
                       || ?::JSONB
                       || jsonb_build_object(
                           'processingResult',jsonb_build_object('state','READY'))
                 WHERE tenant_id=? AND command_receipt_id=?
                """, initialAuthority, TENANT_ONE, receipt)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT (result_reference->'hrm012FrozenAuthority')::TEXT
                  FROM ppl_command_receipts
                 WHERE tenant_id=? AND command_receipt_id=?
                """, String.class, TENANT_ONE, receipt)).isEqualTo(frozenBefore);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_command_receipts
                   SET result_reference=jsonb_set(
                       result_reference,
                       '{hrm012FrozenAuthority,scopeRef}',
                       to_jsonb('connector-set:changed'::TEXT))
                 WHERE tenant_id=? AND command_receipt_id=?
                """, TENANT_ONE, receipt))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "frozen receipt authority is immutable");
    }

    @Test
    void markerAuthorityEvidenceIsBoundedOrderedAndTransitionSafe() {
        JdbcTemplate jdbc = new JdbcTemplate(migratedSource());
        ensureTenant(jdbc, TENANT_ONE, "marker-authority");
        UUID receipt = insertReceipt(jdbc, TENANT_ONE, "marker-authority");
        String firstJti = "hrm012-marker-jti";
        String firstExpiry = "2999-10-22T00:00:00Z";
        String firstBoundAt = "2026-10-22T00:00:00Z";
        String firstCurrent = currentMarkerEvidence(
                firstJti, firstExpiry, SHA_C, "UNVERIFIED",
                firstBoundAt, null);

        String extraAuthorityKey = markerAuthorityReference(firstCurrent)
                .replace("\"authorityEvidence\":",
                        "\"unexpected\":true,\"authorityEvidence\":");
        assertThatThrownBy(() -> updateMarkerAuthority(
                jdbc, receipt, extraAuthorityKey))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "marker authority shape is invalid");
        String oldCurrentNames = markerAuthorityReference(firstCurrent)
                .replace("\"exp\":", "\"expiresAt\":")
                .replace("\"markerSha256\":", "\"markerDigest\":");
        assertThatThrownBy(() -> updateMarkerAuthority(
                jdbc, receipt, oldCurrentNames))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "marker current evidence shape is invalid");

        assertThat(updateMarkerAuthority(
                jdbc, receipt, markerAuthorityReference(firstCurrent))).isOne();
        assertThat(updateMarkerAuthority(
                jdbc, receipt, markerAuthorityReference(firstCurrent))).isOne();
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_command_receipts
                   SET result_reference=result_reference-'hrm012Authority'
                 WHERE tenant_id=? AND command_receipt_id=?
                """, TENANT_ONE, receipt))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "marker authority evidence cannot be removed");
        assertThatThrownBy(() -> updateMarkerAuthority(
                jdbc, receipt, markerAuthorityReference(currentMarkerEvidence(
                        firstJti, "2998-10-22T00:00:00Z", SHA_C,
                        "UNVERIFIED", firstBoundAt, null))))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "same-jti marker identity is immutable");

        String verifiedCurrent = currentMarkerEvidence(
                firstJti, firstExpiry, SHA_C, "VERIFIED_ALLOWED",
                firstBoundAt, "decision-r1");
        assertThatThrownBy(() -> updateMarkerAuthority(
                jdbc, receipt, markerAuthorityReference(currentMarkerEvidence(
                        firstJti, firstExpiry, SHA_C, "VERIFIED_ALLOWED",
                        firstBoundAt, "wrong-decision"))))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "marker current evidence values are invalid");
        assertThat(updateMarkerAuthority(
                jdbc, receipt, markerAuthorityReference(verifiedCurrent))).isOne();
        String forbiddenAfterVerified = markerAuthorityReference(
                currentMarkerEvidence(
                        "fresh-after-verified", "2999-10-22T00:00:01Z", SHA_D,
                        "UNVERIFIED", "2026-10-22T00:00:01Z", null),
                previousMarkerEvidence(
                        firstJti, firstExpiry, "DENIED",
                        "2026-10-22T00:00:01Z"));
        assertThatThrownBy(() -> updateMarkerAuthority(
                jdbc, receipt, forbiddenAfterVerified))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "fresh marker replacement is not safe");

        UUID replaceable = insertReceipt(
                jdbc, TENANT_ONE, "replaceable-marker-authority");
        assertThat(updateMarkerAuthority(
                jdbc, replaceable, markerAuthorityReference(firstCurrent))).isOne();
        String secondJti = "hrm012-marker-jti-2";
        String secondExpiry = "2999-10-22T00:00:02Z";
        String secondBoundAt = "2026-10-22T00:00:02Z";
        String secondCurrent = currentMarkerEvidence(
                secondJti, secondExpiry, SHA_D, "UNVERIFIED",
                secondBoundAt, null);
        String firstPrevious = previousMarkerEvidence(
                firstJti, firstExpiry, "UNVERIFIED", secondBoundAt);
        assertThatThrownBy(() -> updateMarkerAuthority(
                jdbc, replaceable, markerAuthorityReference(
                        secondCurrent, firstPrevious.replace(
                                "\"jtiSha256\":", "\"jti\":"))))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "marker previous evidence shape is invalid");
        assertThat(updateMarkerAuthority(jdbc, replaceable,
                markerAuthorityReference(secondCurrent, firstPrevious))).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT jsonb_array_length(
                           result_reference->'hrm012Authority'->'authorityEvidence')
                  FROM ppl_command_receipts
                 WHERE tenant_id=? AND command_receipt_id=?
                """, Integer.class, TENANT_ONE, replaceable)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT result_reference->'hrm012Authority'
                           ->'authorityEvidence'->0->>'jti'
                  FROM ppl_command_receipts
                 WHERE tenant_id=? AND command_receipt_id=?
                """, String.class, TENANT_ONE, replaceable)).isEqualTo(secondJti);
        assertThat(jdbc.queryForObject("""
                SELECT result_reference->'hrm012Authority'
                           ->'authorityEvidence'->1->>'jtiSha256'
                  FROM ppl_command_receipts
                 WHERE tenant_id=? AND command_receipt_id=?
                """, String.class, TENANT_ONE, replaceable))
                .isEqualTo(digest(firstJti));
        assertThatThrownBy(() -> updateMarkerAuthority(
                jdbc, replaceable, markerAuthorityReference(
                        secondCurrent, firstPrevious, firstPrevious)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "at most one previous entry");

        long sourceId = insertSource(jdbc, TENANT_ONE, "marker-authority");
        UUID connector = insertConnector(
                jdbc, TENANT_ONE, sourceId, "marker-authority");
        UUID mapping = insertMapping(
                jdbc, TENANT_ONE, sourceId, "marker-authority");
        RunGraph run = new RunGraph(
                TENANT_ONE, UUID.randomUUID(), UUID.randomUUID(), replaceable,
                connector, mapping, "obj_marker_authority");
        assertThat(jdbc.update(runInsertSql(run, "VALIDATE_ONLY"))).isOne();
        String thirdBoundAt = "2026-10-22T00:00:03Z";
        String thirdAuthority = markerAuthorityReference(
                currentMarkerEvidence(
                        "hrm012-marker-jti-3", "2999-10-22T00:00:03Z", SHA_E,
                        "UNVERIFIED", thirdBoundAt, null),
                previousMarkerEvidence(
                        secondJti, secondExpiry, "UNVERIFIED", thirdBoundAt));
        assertThatThrownBy(() -> updateMarkerAuthority(
                jdbc, replaceable, thirdAuthority))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "fresh marker replacement is not safe");
    }

    @Test
    void runInsertRequiresExactSameTenantHrm012ReceiptIdentity()
            throws Exception {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        ensureTenant(jdbc, TENANT_ONE, "run-receipt-one");
        ensureTenant(jdbc, TENANT_TWO, "run-receipt-two");
        long sourceId = insertSource(jdbc, TENANT_ONE, "run-receipt");
        UUID connector = insertConnector(
                jdbc, TENANT_ONE, sourceId, "run-receipt");
        UUID mapping = insertMapping(
                jdbc, TENANT_ONE, sourceId, "run-receipt");
        UUID identity = UUID.randomUUID();
        UUID wrongPurpose;
        UUID actorSubjectMismatch;
        // Seed two hostile legacy receipts without V60's receipt trigger so the
        // run guard's own purpose and actor/subject checks are exercised.
        try (Connection connection = source.getConnection()) {
            JdbcTemplate legacy = new JdbcTemplate(
                    new SingleConnectionDataSource(connection, true));
            legacy.execute("SET session_replication_role = 'replica'");
            try {
                wrongPurpose = insertReceipt(
                        legacy, TENANT_ONE, "wrong-purpose",
                        "WORKFORCE_INGESTION",
                        "INITIATE_WORKFORCE_INGESTION",
                        "OTHER_PURPOSE", "WORKFORCE_INGESTION_OBJECT",
                        identity, identity, null);
                actorSubjectMismatch = insertReceipt(
                        legacy, TENANT_ONE, "actor-subject-mismatch",
                        "WORKFORCE_INGESTION",
                        "INITIATE_WORKFORCE_INGESTION",
                        "HRIS_INTEGRATION", "WORKFORCE_INGESTION_OBJECT",
                        identity, UUID.randomUUID(), null);
            } finally {
                legacy.execute("SET session_replication_role = 'origin'");
            }
        }

        List<UUID> invalidReceipts = List.of(
                insertReceipt(jdbc, TENANT_ONE, "wrong-command",
                        "OTHER_COMMAND", "INITIATE_WORKFORCE_INGESTION",
                        "HRIS_INTEGRATION", "WORKFORCE_INGESTION_OBJECT",
                        identity, identity, null),
                insertReceipt(jdbc, TENANT_ONE, "wrong-action",
                        "WORKFORCE_INGESTION", "OTHER_ACTION",
                        "HRIS_INTEGRATION", "WORKFORCE_INGESTION_OBJECT",
                        identity, identity, null),
                insertReceipt(jdbc, TENANT_ONE, "wrong-target",
                        "WORKFORCE_INGESTION", "INITIATE_WORKFORCE_INGESTION",
                        "HRIS_INTEGRATION", "OTHER_TARGET",
                        identity, identity, null),
                wrongPurpose,
                actorSubjectMismatch,
                insertReceipt(jdbc, TENANT_TWO, "cross-tenant-receipt"));

        for (UUID invalidReceipt : invalidReceipts) {
            RunGraph invalid = new RunGraph(
                    TENANT_ONE, UUID.randomUUID(), UUID.randomUUID(),
                    invalidReceipt, connector, mapping,
                    "obj_" + UUID.randomUUID().toString().replace("-", ""));
            assertThatThrownBy(() -> jdbc.update(
                    runInsertSql(invalid, "VALIDATE_ONLY")))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining(
                            "requires exact same-tenant HRM-012 receipt");
        }

        UUID validReceipt = insertReceipt(
                jdbc, TENANT_ONE, "valid-run-receipt");
        RunGraph valid = new RunGraph(
                TENANT_ONE, UUID.randomUUID(), UUID.randomUUID(), validReceipt,
                connector, mapping,
                "obj_" + UUID.randomUUID().toString().replace("-", ""));
        assertThat(jdbc.update(runInsertSql(valid, "VALIDATE_ONLY"))).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ppl_workforce_ingestion_runs
                 WHERE tenant_id=?
                """, Integer.class, TENANT_ONE)).isOne();
    }

    @Test
    void forceRlsHidesAndRejectsBothTablesWithoutMatchingTenantContext()
            throws Exception {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        RunGraph one = insertRunning(jdbc, TENANT_ONE, "rls-one", "VALIDATE_ONLY");
        RunGraph two = insertRunning(jdbc, TENANT_TWO, "rls-two", "VALIDATE_ONLY");
        insertItem(jdbc, one, 1, "VALIDATED");
        insertItem(jdbc, two, 1, "VALIDATED");
        UUID unusedReceipt = insertReceipt(
                jdbc, one.tenantId(), "rls-cross-unused");
        RunGraph cross = one.withRun(
                UUID.randomUUID(), UUID.randomUUID(), unusedReceipt);

        jdbc.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_roles
                         WHERE rolname='hrm012_rls_actor') THEN
                        CREATE ROLE hrm012_rls_actor NOLOGIN;
                    END IF;
                END $$
                """);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO hrm012_rls_actor");
        jdbc.execute("""
                GRANT SELECT,INSERT,UPDATE,DELETE ON
                    ppl_workforce_ingestion_runs,
                    ppl_workforce_ingestion_items
                TO hrm012_rls_actor
                """);

        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET ROLE hrm012_rls_actor");
            for (String table : TABLES) {
                assertThat(count(statement, "SELECT count(*) FROM " + table))
                        .as("missing-context %s", table).isZero();
            }
            assertRlsRejected(
                    connection, statement, runInsertSql(cross, "VALIDATE_ONLY"));
            assertRlsRejected(
                    connection, statement, itemInsertSql(one, 91, "VALIDATED"));

            statement.execute("SELECT set_config('dwp.tenant_id','"
                    + TENANT_ONE + "',false)");
            for (String table : TABLES) {
                assertThat(count(statement, "SELECT count(*) FROM " + table))
                        .as("tenant-one %s", table).isOne();
            }

            statement.execute("SELECT set_config('dwp.tenant_id','"
                    + TENANT_TWO + "',false)");
            for (String table : TABLES) {
                assertThat(count(statement, "SELECT count(*) FROM " + table))
                        .as("tenant-two %s", table).isOne();
            }
            assertRlsRejected(
                    connection, statement, runInsertSql(cross, "VALIDATE_ONLY"));
            assertRlsRejected(
                    connection, statement, itemInsertSql(one, 92, "VALIDATED"));
            connection.rollback();
        }
    }

    @Test
    void modeSpecificOutcomesAccountingEventsAndTerminalImmutabilityFailClosed() {
        JdbcTemplate jdbc = new JdbcTemplate(migratedSource());
        RunGraph validation = insertRunning(
                jdbc, TENANT_ONE, "validate", "VALIDATE_ONLY");

        assertThatThrownBy(() -> insertItem(jdbc, validation, 1, "APPLIED"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "VALIDATE_ONLY item outcome is not allowed");
        insertItem(jdbc, validation, 1, "VALIDATED");
        insertItem(jdbc, validation, 2, "REJECTED");
        insertItem(jdbc, validation, 3, "DUPLICATE");
        assertThatThrownBy(() -> reconcileValidation(
                jdbc, validation, 3, 1, 1, 1))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("VALIDATE_ONLY accounting is not conserved");
        assertThatThrownBy(() -> insertExactOutbox(
                jdbc, validation, 3, 0, 1, 1, 0, 0))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("RUNNING APPLY run");
        reconcileValidation(jdbc, validation, 3, 1, 1, 0);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_workforce_ingestion_items SET outcome_digest=?
                 WHERE workforce_ingestion_run_id=? AND item_sequence=1
                """, SHA_F, validation.runId()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("item evidence is immutable");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_workforce_ingestion_runs SET version=3
                 WHERE workforce_ingestion_run_id=?
                """, validation.runId()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("terminal workforce ingestion run is immutable");

        RunGraph apply = insertRunning(jdbc, TENANT_ONE, "apply", "APPLY");
        assertThatThrownBy(() -> insertItem(jdbc, apply, 1, "VALIDATED"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "APPLY item cannot use VALIDATED outcome");
        insertItem(jdbc, apply, 1, "APPLIED");
        insertItem(jdbc, apply, 2, "QUARANTINED_AFTER_ACCEPTANCE");
        insertItem(jdbc, apply, 3, "REJECTED");
        insertItem(jdbc, apply, 4, "DUPLICATE");
        insertCursor(jdbc, apply, 0L, "cursor-before");

        assertThatThrownBy(() -> reconcileApply(
                jdbc, apply, 4, 3, 1, 1, 1, 1, "cursor-after"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("accounting is not conserved");
        assertThat(jdbc.queryForObject("""
                SELECT version FROM int_connector_cursors
                 WHERE tenant_id=? AND connector_instance_id=?
                """, Long.class, apply.tenantId(), apply.connectorId())).isZero();

        reconcileApply(jdbc, apply, 4, 2, 1, 1, 1, 1, "cursor-after");
        assertThat(jdbc.queryForObject("""
                SELECT cursor_committed_version
                  FROM ppl_workforce_ingestion_runs
                 WHERE workforce_ingestion_run_id=?
                """, Long.class, apply.runId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_command_receipts
                 WHERE tenant_id=? AND command_receipt_id=?
                """, String.class, apply.tenantId(), apply.receiptId()))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_audit_events
                 WHERE tenant_id=? AND target_id=?
                """, Integer.class, apply.tenantId(), apply.runPublicId().toString()))
                .isOne();
        assertThatThrownBy(() -> insertExactOutbox(
                jdbc, apply, 4, 2, 1, 1, 1, 1,
                deterministicId("duplicate", apply, SHA_E)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("RUNNING APPLY run");
    }

    @Test
    void failedTerminalizationRollsBackCursorOutboxAndRunThenExactRetryWins() {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        RunGraph apply = insertRunning(
                jdbc, TENANT_ONE, "rollback", "APPLY");
        insertItem(jdbc, apply, 1, "APPLIED");
        insertCursor(jdbc, apply, 0L, "cursor-before");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(source));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            assertThat(advanceCursor(jdbc, apply, "cursor-after")).isOne();
            insertAudit(jdbc, apply, 2, SHA_E);
            insertExactOutbox(jdbc, apply, 2, 1, 0, 0, 0, 1);
            completeApply(jdbc, apply, 2, 1, 0, 0, 0, 1,
                    digest("cursor-after"));
            completeReceipt(jdbc, apply, SHA_E);
        })).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "run counts must equal immutable item evidence");

        assertThat(jdbc.queryForObject("""
                SELECT version FROM int_connector_cursors
                 WHERE tenant_id=? AND connector_instance_id=?
                """, Long.class, apply.tenantId(), apply.connectorId()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_outbox_events
                 WHERE tenant_id=? AND aggregate_id=?
                """, Integer.class, apply.tenantId(), apply.runPublicId().toString()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_audit_events
                 WHERE tenant_id=? AND target_id=?
                """, Integer.class, apply.tenantId(), apply.runPublicId().toString()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_command_receipts
                 WHERE tenant_id=? AND command_receipt_id=?
                """, String.class, apply.tenantId(), apply.receiptId()))
                .isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_workforce_ingestion_runs
                 WHERE workforce_ingestion_run_id=?
                """, String.class, apply.runId())).isEqualTo("RUNNING");

        reconcileApply(jdbc, apply, 1, 1, 0, 0, 0, 1, "cursor-after");
        assertThat(jdbc.queryForObject("""
                SELECT version FROM int_connector_cursors
                 WHERE tenant_id=? AND connector_instance_id=?
                """, Long.class, apply.tenantId(), apply.connectorId()))
                .isOne();
    }

    @Test
    void terminalApplyRejectsMissingStaleAndDigestMismatchedCursorState() {
        JdbcTemplate jdbc = new JdbcTemplate(migratedSource());

        RunGraph missing = insertRunning(jdbc, TENANT_ONE, "cursor-missing", "APPLY");
        insertItem(jdbc, missing, 1, "APPLIED");
        assertThatThrownBy(() -> finishApplyWithoutCursorMutation(
                jdbc, missing, digest("cursor-after")))
                .isInstanceOfAny(DataAccessException.class, TransactionSystemException.class)
                .rootCause().hasMessageContaining("exact same-transaction cursor CAS");
        assertRunningAndReceiptOpen(jdbc, missing);

        RunGraph stale = insertRunning(jdbc, TENANT_ONE, "cursor-stale", "APPLY");
        insertItem(jdbc, stale, 1, "APPLIED");
        insertCursor(jdbc, stale, 0L, "cursor-before");
        assertThat(advanceCursor(jdbc, stale, "already-advanced")).isOne();
        assertThatThrownBy(() -> finishApplyWithoutCursorMutation(
                jdbc, stale, digest("already-advanced")))
                .isInstanceOfAny(DataAccessException.class, TransactionSystemException.class)
                .rootCause().hasMessageContaining("exact same-transaction cursor CAS");
        assertRunningAndReceiptOpen(jdbc, stale);

        RunGraph missingEvent = insertRunning(
                jdbc, TENANT_ONE, "outbox-missing", "APPLY");
        insertItem(jdbc, missingEvent, 1, "APPLIED");
        insertCursor(jdbc, missingEvent, 0L, "cursor-before");
        assertThatThrownBy(() -> transaction(jdbc).executeWithoutResult(status -> {
            assertThat(advanceCursor(jdbc, missingEvent, "cursor-after")).isOne();
            insertAudit(jdbc, missingEvent, 1, SHA_E);
            completeApply(jdbc, missingEvent, 1, 1, 0, 0, 0, 1,
                    digest("cursor-after"));
            completeReceipt(jdbc, missingEvent, SHA_E);
        })).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("requires one exact reconciled event");
        assertRunningAndReceiptOpen(jdbc, missingEvent);

        RunGraph wrongDigest = insertRunning(
                jdbc, TENANT_ONE, "cursor-wrong-digest", "APPLY");
        insertItem(jdbc, wrongDigest, 1, "APPLIED");
        insertCursor(jdbc, wrongDigest, 0L, "cursor-before");
        assertThatThrownBy(() -> transaction(jdbc).executeWithoutResult(status -> {
            assertThat(advanceCursor(jdbc, wrongDigest, "cursor-after")).isOne();
            insertAudit(jdbc, wrongDigest, 1, SHA_E);
            insertExactOutbox(jdbc, wrongDigest, 1, 1, 0, 0, 0, 1);
            completeApply(jdbc, wrongDigest, 1, 1, 0, 0, 0, 1, SHA_F);
            completeReceipt(jdbc, wrongDigest, SHA_E);
        })).isInstanceOfAny(DataAccessException.class, TransactionSystemException.class)
                .rootCause().hasMessageContaining(
                        "cursor mutation requires same-transaction successful terminal run");
        assertRunningAndReceiptOpen(jdbc, wrongDigest);
        assertThat(jdbc.queryForObject("""
                SELECT version FROM int_connector_cursors
                 WHERE tenant_id=? AND connector_instance_id=?
                """, Long.class, wrongDigest.tenantId(), wrongDigest.connectorId()))
                .isZero();
    }

    @Test
    void unrelatedSharedCursorWriteIgnoresPendingHrmRunFromPriorTransaction() {
        JdbcTemplate jdbc = new JdbcTemplate(migratedSource());
        RunGraph pending = insertRunning(
                jdbc, TENANT_ONE, "shared-cursor-unrelated", "APPLY");
        insertCursor(jdbc, pending, 0L, "cursor-before");

        assertThat(advanceCursor(
                jdbc, pending, "cursor-owned-by-another-workflow")).isOne();

        assertThat(jdbc.queryForObject("""
                SELECT version FROM int_connector_cursors
                 WHERE tenant_id=? AND connector_instance_id=?
                   AND cursor_type='OPAQUE_TOKEN'
                """, Long.class, pending.tenantId(), pending.connectorId()))
                .isOne();
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_workforce_ingestion_runs
                 WHERE tenant_id=? AND workforce_ingestion_run_id=?
                """, String.class, pending.tenantId(), pending.runId()))
                .isEqualTo("RUNNING");
    }

    @Test
    void separatedSuccessEvidenceFailsAndOnlyOneAtomicTransactionCommits() {
        JdbcTemplate jdbc = new JdbcTemplate(migratedSource());
        RunGraph apply = insertRunning(
                jdbc, TENANT_ONE, "separated-evidence", "APPLY");
        insertItem(jdbc, apply, 1, "APPLIED");
        insertCursor(jdbc, apply, 0L, "cursor-before");
        UUID eventId = deterministicId(
                "WorkforceIngestionReconciled.v1", apply, SHA_E);

        assertThatThrownBy(() -> insertTerminalReceipt(
                jdbc, apply.tenantId(), "direct-terminal-insert"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "successful receipt requires same-transaction terminal run");
        assertThatThrownBy(() -> insertExactOutbox(
                jdbc, apply, 1, 1, 0, 0, 0, 1, UUID.randomUUID(), SHA_E))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("event identity is not canonical");
        assertThatThrownBy(() -> insertAudit(jdbc, apply, 1, SHA_E))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "audit event requires same-transaction terminal run");
        assertThatThrownBy(() -> insertExactOutbox(
                jdbc, apply, 1, 1, 0, 0, 0, 1, eventId, SHA_E))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "reconciled event requires same-transaction terminal run");
        assertThatThrownBy(() -> completeReceipt(jdbc, apply, SHA_E))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "successful receipt requires same-transaction terminal run");

        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_workforce_ingestion_runs
                 WHERE workforce_ingestion_run_id=?
                """, String.class, apply.runId())).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_command_receipts
                 WHERE tenant_id=? AND command_receipt_id=?
                """, String.class, apply.tenantId(), apply.receiptId()))
                .isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_outbox_events
                 WHERE tenant_id=? AND aggregate_id=?
                """, Integer.class, apply.tenantId(), apply.runPublicId().toString()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_audit_events
                 WHERE tenant_id=? AND target_id=?
                """, Integer.class, apply.tenantId(), apply.runPublicId().toString()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT version FROM int_connector_cursors
                 WHERE tenant_id=? AND connector_instance_id=?
                """, Long.class, apply.tenantId(), apply.connectorId())).isZero();

        assertThatThrownBy(() -> transaction(jdbc).executeWithoutResult(status -> {
            assertThat(advanceCursor(jdbc, apply, "cursor-after")).isOne();
            insertAudit(jdbc, apply, 1, SHA_E);
            insertExactOutbox(
                    jdbc, apply, 1, 1, 0, 0, 0, 1, eventId, SHA_E);
            insertExactOutbox(
                    jdbc, apply, 1, 1, 0, 0, 0, 1, eventId, SHA_F);
        }))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("duplicate key");

        reconcileApply(jdbc, apply, 1, 1, 0, 0, 0, 1, "cursor-after");
        assertThat(jdbc.queryForObject("""
                SELECT event_id FROM sys_people_outbox_events
                 WHERE tenant_id=? AND aggregate_type='WorkforceIngestionRun'
                   AND aggregate_id=? AND event_type='WorkforceIngestionReconciled.v1'
                """, UUID.class, apply.tenantId(), apply.runPublicId().toString()))
                .isEqualTo(eventId);
    }

    @Test
    void itemInsertLocksParentAgainstTerminalizationAndDeferredRecountIsExact()
            throws Exception {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        RunGraph validation = insertRunning(
                jdbc, TENANT_ONE, "item-terminal-race", "VALIDATE_ONLY");

        try (Connection itemConnection = source.getConnection();
             Connection terminalConnection = source.getConnection()) {
            itemConnection.setAutoCommit(false);
            terminalConnection.setAutoCommit(false);
            JdbcTemplate itemJdbc = new JdbcTemplate(
                    new SingleConnectionDataSource(itemConnection, true));
            JdbcTemplate terminalJdbc = new JdbcTemplate(
                    new SingleConnectionDataSource(terminalConnection, true));
            insertItem(itemJdbc, validation, 1, "VALIDATED");
            terminalJdbc.execute("SET LOCAL lock_timeout='250ms'");

            assertThatThrownBy(() -> completeValidation(
                    terminalJdbc, validation, 0, 0, 0, 0))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("lock timeout");
            terminalConnection.rollback();
            itemConnection.commit();
        }

        reconcileValidation(jdbc, validation, 1, 0, 0, 0);
        assertThat(jdbc.queryForObject("""
                SELECT source_count FROM ppl_workforce_ingestion_runs
                 WHERE workforce_ingestion_run_id=?
                """, Long.class, validation.runId())).isOne();
    }

    @Test
    void lifecycleVersionAndLeaseNegativeTransitionsFailInPostgres() {
        JdbcTemplate jdbc = new JdbcTemplate(migratedSource());
        RunGraph received = insertReceived(
                jdbc, TENANT_ONE, "lifecycle-received", "VALIDATE_ONLY");

        assertThatThrownBy(() -> completeValidation(
                jdbc, received, 0, 0, 0, 0))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("illegal workforce ingestion lifecycle transition");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_workforce_ingestion_runs
                   SET lifecycle_state='RUNNING',attempt_count=1,version=1
                 WHERE workforce_ingestion_run_id=?
                """, received.runId()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("lease");

        RunGraph running = insertRunning(
                jdbc, TENANT_ONE, "lifecycle-version", "VALIDATE_ONLY");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_workforce_ingestion_runs
                   SET lifecycle_state='FAILED_RETRYABLE',lease_owner=NULL,
                       lease_until=NULL,next_attempt_at=clock_timestamp(),
                       failure_code='RETRY',redacted_failure_summary='Redacted',version=1
                 WHERE workforce_ingestion_run_id=?
                """, running.runId()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("version must advance exactly once");
    }

    @Test
    void expiredRunningLeaseRequiresExactNewOwnerTakeover() throws Exception {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        RunGraph live = insertRunning(
                jdbc, TENANT_ONE, "live-lease-takeover", "VALIDATE_ONLY");

        assertThatThrownBy(() -> takeover(jdbc, live, "replacement-worker", 2, 2))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "illegal workforce ingestion lifecycle transition");

        RunGraph expired = insertRunning(
                jdbc, TENANT_ONE, "expired-lease-takeover", "VALIDATE_ONLY");
        expireLeaseWithoutGuards(source, expired);
        assertThatThrownBy(() -> takeover(jdbc, expired, "hrm012-test", 2, 2))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "expired RUNNING lease takeover requires a new owner");
        assertThatThrownBy(() -> takeover(jdbc, expired, "replacement-worker", 1, 2))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "RUNNING transition must increment attempt exactly once");
        assertThatThrownBy(() -> takeover(jdbc, expired, "replacement-worker", 2, 1))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "workforce ingestion version must advance exactly once");
        assertThatThrownBy(() -> takeover(jdbc, expired, "", 2, 2))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "RUNNING transition requires a nonempty owner and future lease");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_workforce_ingestion_runs
                   SET lifecycle_state='RUNNING',lease_owner='replacement-worker',
                       lease_until=clock_timestamp() - interval '1 second',
                       attempt_count=2,version=2
                 WHERE tenant_id=? AND workforce_ingestion_run_id=?
                """, expired.tenantId(), expired.runId()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "RUNNING transition requires a nonempty owner and future lease");

        assertThat(takeover(
                jdbc, expired, "replacement-worker", 2, 2)).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state,lease_owner,attempt_count,version,
                       lease_until > clock_timestamp() AS lease_is_live
                  FROM ppl_workforce_ingestion_runs
                 WHERE tenant_id=? AND workforce_ingestion_run_id=?
                """, expired.tenantId(), expired.runId()))
                .containsEntry("lifecycle_state", "RUNNING")
                .containsEntry("lease_owner", "replacement-worker")
                .containsEntry("attempt_count", 2)
                .containsEntry("version", 2L)
                .containsEntry("lease_is_live", true);
    }

    @Test
    void linkedReplayRequiresTerminalSameTenantExactImmutableLineage() {
        JdbcTemplate jdbc = new JdbcTemplate(migratedSource());
        RunGraph original = insertRunning(
                jdbc, TENANT_ONE, "original", "VALIDATE_ONLY");
        insertItem(jdbc, original, 1, "VALIDATED");
        reconcileValidation(jdbc, original, 1, 0, 0, 0);

        UUID replayReceipt = insertReceipt(jdbc, TENANT_ONE, "replay-ok");
        RunGraph replay = original.withRun(
                UUID.randomUUID(), UUID.randomUUID(), replayReceipt);
        assertThat(jdbc.update(runInsertSql(
                replay, "VALIDATE_ONLY",
                original.receiptId(), original.runId(), original.objectRef())))
                .isOne();

        UUID badReceipt = insertReceipt(jdbc, TENANT_ONE, "replay-bad");
        RunGraph bad = original.withRun(
                UUID.randomUUID(), UUID.randomUUID(), badReceipt);
        assertThatThrownBy(() -> jdbc.update(runInsertSql(
                bad, "VALIDATE_ONLY",
                original.receiptId(), original.runId(),
                "obj_ffffffffffffffffffffffffffffffff")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "linked replay must preserve immutable source lineage");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ppl_workforce_ingestion_runs
                 WHERE workforce_ingestion_run_id=?
                """, Integer.class, bad.runId())).isZero();

        UUID wrongLinkedReceipt = insertReceipt(
                jdbc, TENANT_ONE, "replay-wrong-linked-receipt");
        UUID newReceipt = insertReceipt(jdbc, TENANT_ONE, "replay-new-receipt");
        RunGraph wrongLineage = original.withRun(
                UUID.randomUUID(), UUID.randomUUID(), newReceipt);
        assertThatThrownBy(() -> jdbc.update(runInsertSql(
                wrongLineage, "VALIDATE_ONLY", wrongLinkedReceipt,
                original.runId(), original.objectRef())))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "linked replay lineage does not match original evidence");

        RunGraph nonterminal = insertRunning(
                jdbc, TENANT_ONE, "replay-nonterminal", "VALIDATE_ONLY");
        UUID nonterminalReplayReceipt = insertReceipt(
                jdbc, TENANT_ONE, "replay-nonterminal-new");
        RunGraph nonterminalReplay = nonterminal.withRun(
                UUID.randomUUID(), UUID.randomUUID(), nonterminalReplayReceipt);
        assertThatThrownBy(() -> jdbc.update(runInsertSql(
                nonterminalReplay, "VALIDATE_ONLY", nonterminal.receiptId(),
                nonterminal.runId(), nonterminal.objectRef())))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "linked replay must preserve immutable source lineage");

        ensureTenant(jdbc, TENANT_TWO, "replay-cross-tenant");
        long sourceId = insertSource(jdbc, TENANT_TWO, "replay-cross-tenant");
        UUID connector = insertConnector(
                jdbc, TENANT_TWO, sourceId, "replay-cross-tenant");
        UUID mapping = insertMapping(
                jdbc, TENANT_TWO, sourceId, "replay-cross-tenant");
        RunGraph crossTenant = new RunGraph(
                TENANT_TWO, UUID.randomUUID(), UUID.randomUUID(),
                insertReceipt(jdbc, TENANT_TWO, "replay-cross-tenant"),
                connector, mapping, original.objectRef());
        assertThatThrownBy(() -> jdbc.update(runInsertSql(
                crossTenant, "VALIDATE_ONLY", original.receiptId(),
                original.runId(), original.objectRef())))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "linked replay lineage does not match original evidence");
    }

    private DataSource migratedSource() {
        DataSource source = dataSource();
        migrated = flyway(source, null);
        migrated.clean();
        migrated.migrate();
        return source;
    }

    private RunGraph insertRunning(
            JdbcTemplate jdbc,
            long tenant,
            String suffix,
            String mode) {
        RunGraph run = insertReceived(jdbc, tenant, suffix, mode);
        assertThat(jdbc.update("""
                UPDATE ppl_workforce_ingestion_runs
                   SET lifecycle_state='RUNNING',lease_owner='hrm012-test',
                       lease_until=clock_timestamp() + interval '5 minutes',
                       attempt_count=1,version=1
                 WHERE workforce_ingestion_run_id=?
                """, run.runId())).isOne();
        return run;
    }

    private RunGraph insertReceived(
            JdbcTemplate jdbc,
            long tenant,
            String suffix,
            String mode) {
        ensureTenant(jdbc, tenant, suffix);
        long sourceId = insertSource(jdbc, tenant, suffix);
        UUID connector = insertConnector(jdbc, tenant, sourceId, suffix);
        UUID mapping = insertMapping(jdbc, tenant, sourceId, suffix);
        UUID receipt = insertReceipt(jdbc, tenant, suffix);
        RunGraph run = new RunGraph(
                tenant, UUID.randomUUID(), UUID.randomUUID(), receipt,
                connector, mapping,
                "obj_" + UUID.randomUUID().toString().replace("-", ""));
        assertThat(jdbc.update(runInsertSql(run, mode))).isOne();
        return run;
    }

    private void ensureTenant(JdbcTemplate jdbc, long tenant, String suffix) {
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,
                    lifecycle_state,data_region,isolation_model,created_by,updated_by)
                VALUES(?::UUID,?,?,?,'ACTIVE','local','POOL',7,7)
                ON CONFLICT (tenant_id) DO NOTHING
                """, UUID.randomUUID().toString(), tenant,
                "hrm012-" + suffix + "-" + tenant,
                "HRM-012 " + suffix);
    }

    private long insertSource(JdbcTemplate jdbc, long tenant, String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO int_source_systems(
                    tenant_id,source_key,system_type,name,created_by,updated_by)
                VALUES(?,?,'CUSTOM',?,7,7)
                RETURNING source_system_id
                """, Long.class, tenant, "source-" + suffix,
                "HRM-012 " + suffix);
    }

    private UUID insertConnector(
            JdbcTemplate jdbc,
            long tenant,
            long sourceId,
            String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO int_connector_instances(
                    tenant_id,source_system_id,connector_key,connector_type,
                    auth_mode,lifecycle_state,version,created_by,updated_by)
                VALUES(?,?,?,'FILE_IMPORT','NONE','ACTIVE',1,7,7)
                RETURNING connector_instance_id
                """, UUID.class, tenant, sourceId, "connector-" + suffix);
    }

    private UUID insertMapping(
            JdbcTemplate jdbc,
            long tenant,
            long sourceId,
            String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO int_mapping_profiles(
                    tenant_id,source_system_id,profile_key,adapter_type,
                    source_schema_version,target_schema_version,
                    mapping_definition,lifecycle_state,version,
                    mapping_sha256,activated_at,activated_by,created_by,updated_by)
                VALUES(?,?,?,'CANONICAL_JSON','workforce.v1','people.v1',
                       '{}'::JSONB,'ACTIVE',1,?,clock_timestamp(),7,7,7)
                RETURNING mapping_profile_id
                """, UUID.class, tenant, sourceId, "mapping-" + suffix, SHA_A);
    }

    private UUID insertReceipt(JdbcTemplate jdbc, long tenant, String suffix) {
        UUID identity = UUID.randomUUID();
        return insertReceipt(jdbc, tenant, suffix,
                "WORKFORCE_INGESTION", "INITIATE_WORKFORCE_INGESTION",
                "HRIS_INTEGRATION", "WORKFORCE_INGESTION_OBJECT",
                identity, identity, null);
    }

    private UUID insertReceipt(
            JdbcTemplate jdbc,
            long tenant,
            String suffix,
            String commandType,
            String originatingAction,
            String purposeCode,
            String targetType,
            UUID actor,
            UUID subject,
            UnaryOperator<String> referenceMutation) {
        UUID correlation = UUID.randomUUID();
        String reference = frozenAuthorityReference(tenant, actor, correlation);
        if (referenceMutation != null) {
            reference = referenceMutation.apply(reference);
        }
        return jdbc.queryForObject("""
                INSERT INTO ppl_command_receipts(
                    tenant_id,command_type,idempotency_key,request_hash,
                    actor_public_id,originating_action,
                    subject_principal_public_id,population_scope_digest,
                    field_policy_revision,purpose_code,authorization_revision,
                    originating_context_digest,target_type,target_public_id,
                    expected_version,result_reference,correlation_id)
                VALUES(?,?,?,?,?::UUID,?,?::UUID,?,1,?,1,?,?,NULL,0,
                       ?::JSONB,?::UUID)
                RETURNING command_receipt_id
                """, UUID.class, tenant, commandType,
                "hrm012-" + suffix + "-" + UUID.randomUUID(), SHA_A,
                actor.toString(), originatingAction, subject.toString(),
                SHA_B, purposeCode, SHA_C, targetType, reference,
                correlation.toString());
    }

    private String frozenAuthorityReference(
            long tenant, UUID person, UUID correlation) {
        return """
                {
                  "hrm012FrozenAuthority": {
                    "tenantId": %d,
                    "numericUserId": 7001,
                    "personPublicId": "%s",
                    "markerJti": "hrm012-marker-jti",
                    "markerSha256": "%s",
                    "contextKey": "hrm012-context",
                    "scopeRef": "connector-set:test",
                    "populationScopeDigest": "%s",
                    "authorizationRevision": 1,
                    "populationRevision": 1,
                    "fieldPolicyRevision": 1,
                    "postReceiptDecisionRevision": "decision-r1",
                    "dutyEvidenceRevision": "duty-r1",
                    "featureDecisionRevision": "feature-r1",
                    "purpose": "HRIS_INTEGRATION",
                    "correlationId": "%s",
                    "idempotencyKeySha256": "%s",
                    "canonicalRequestHash": "%s"
                  }
                }
                """.formatted(
                tenant, person, SHA_C, SHA_B, correlation, SHA_D, SHA_A);
    }

    private int insertTerminalReceipt(
            JdbcTemplate jdbc, long tenant, String suffix) {
        UUID identity = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        String reference = frozenAuthorityReference(tenant, identity, correlation);
        return jdbc.update("""
                INSERT INTO ppl_command_receipts(
                    tenant_id,command_type,idempotency_key,request_hash,
                    actor_public_id,originating_action,
                    subject_principal_public_id,population_scope_digest,
                    field_policy_revision,purpose_code,authorization_revision,
                    originating_context_digest,target_type,target_public_id,
                    expected_version,lifecycle_state,result_code,result_reference,
                    correlation_id,completed_at)
                VALUES(?, 'WORKFORCE_INGESTION', ?, ?, ?::UUID,
                       'INITIATE_WORKFORCE_INGESTION', ?::UUID, ?, 1,
                       'HRIS_INTEGRATION', 1, ?, 'WORKFORCE_INGESTION_OBJECT',
                       NULL, 0, 'SUCCEEDED', 'WORKFORCE_INGESTION_SUCCEEDED',
                       ?::JSONB || jsonb_build_object(
                           'runPublicId',?,'resultDigest',?),
                       ?::UUID,clock_timestamp())
                """, tenant, "hrm012-" + suffix + '-' + UUID.randomUUID(), SHA_A,
                identity.toString(), identity.toString(), SHA_B, SHA_C,
                reference, UUID.randomUUID().toString(), SHA_E,
                correlation.toString());
    }

    private void expireLeaseWithoutGuards(DataSource source, RunGraph run)
            throws Exception {
        try (Connection connection = source.getConnection()) {
            JdbcTemplate fixture = new JdbcTemplate(
                    new SingleConnectionDataSource(connection, true));
            fixture.execute("SET session_replication_role = 'replica'");
            try {
                assertThat(fixture.update("""
                        UPDATE ppl_workforce_ingestion_runs
                           SET lease_until=clock_timestamp() - interval '1 minute'
                         WHERE tenant_id=? AND workforce_ingestion_run_id=?
                        """, run.tenantId(), run.runId())).isOne();
            } finally {
                fixture.execute("SET session_replication_role = 'origin'");
            }
        }
    }

    private int takeover(
            JdbcTemplate jdbc,
            RunGraph run,
            String owner,
            int attempt,
            long version) {
        return jdbc.update("""
                UPDATE ppl_workforce_ingestion_runs
                   SET lifecycle_state='RUNNING',lease_owner=?,
                       lease_until=clock_timestamp() + interval '5 minutes',
                       attempt_count=?,version=?
                 WHERE tenant_id=? AND workforce_ingestion_run_id=?
                """, owner, attempt, version, run.tenantId(), run.runId());
    }

    private int updateMarkerAuthority(
            JdbcTemplate jdbc, UUID receipt, String authorityReference) {
        return jdbc.update("""
                UPDATE ppl_command_receipts
                   SET result_reference=result_reference || ?::JSONB
                 WHERE tenant_id=? AND command_receipt_id=?
                """, authorityReference, TENANT_ONE, receipt);
    }

    private String markerAuthorityReference(String... evidence) {
        return """
                {
                  "hrm012Authority": {
                    "authorityEvidence": [%s]
                  }
                }
                """.formatted(String.join(",", evidence));
    }

    private String currentMarkerEvidence(
            String jti,
            String expiresAt,
            String markerSha256,
            String state,
            String boundAt,
            String authDecisionRevision) {
        String revision = authDecisionRevision == null
                ? "null" : "\"" + authDecisionRevision + "\"";
        return """
                {
                  "jti": "%s",
                  "exp": "%s",
                  "markerSha256": "%s",
                  "state": "%s",
                  "boundAt": "%s",
                  "authDecisionRevision": %s
                }
                """.formatted(
                jti, expiresAt, markerSha256, state, boundAt, revision);
    }

    private String previousMarkerEvidence(
            String jti, String expiresAt, String state, String replacedAt) {
        return """
                {
                  "jtiSha256": "%s",
                  "exp": "%s",
                  "state": "%s",
                  "replacedAt": "%s"
                }
                """.formatted(digest(jti), expiresAt, state, replacedAt);
    }

    private String runInsertSql(RunGraph run, String mode) {
        return runInsertSql(run, mode, null, null, run.objectRef());
    }

    private String runInsertSql(
            RunGraph run,
            String mode,
            UUID replayReceipt,
            UUID replayRun,
            String objectRef) {
        String replayReceiptValue = replayReceipt == null
                ? "NULL" : "'" + replayReceipt + "'::UUID";
        String replayRunValue = replayRun == null
                ? "NULL" : "'" + replayRun + "'::UUID";
        return """
                INSERT INTO ppl_workforce_ingestion_runs(
                    workforce_ingestion_run_id,run_public_id,tenant_id,
                    command_receipt_id,linked_replay_receipt_id,
                    linked_replay_run_id,connector_instance_id,
                    connector_public_id,connector_version,mapping_profile_id,
                    mapping_public_id,mapping_version,mapping_sha256,
                    legal_entity_public_ids,population_scope_digest,
                    object_ref,object_digest,schema_key,schema_version,mode,
                    effective_date,cursor_before_digest,cursor_expected_version)
                VALUES('%s'::UUID,'%s'::UUID,%d,'%s'::UUID,%s,%s,
                       '%s'::UUID,'%s'::UUID,1,'%s'::UUID,'%s'::UUID,1,
                       '%s',ARRAY[]::UUID[],'%s','%s','%s','workforce.v1',1,
                       '%s',DATE '2026-10-22','%s',0)
                """.formatted(
                run.runId(), run.runPublicId(), run.tenantId(), run.receiptId(),
                replayReceiptValue, replayRunValue,
                run.connectorId(), run.connectorId(),
                run.mappingId(), run.mappingId(), SHA_A, SHA_B,
                objectRef, SHA_C, mode, SHA_D);
    }

    private void insertItem(
            JdbcTemplate jdbc,
            RunGraph run,
            long sequence,
            String outcome) {
        String finding = switch (outcome) {
            case "REJECTED", "QUARANTINED_AFTER_ACCEPTANCE" -> "'INVALID_VALUE'";
            default -> "NULL";
        };
        String summary = switch (outcome) {
            case "REJECTED", "QUARANTINED_AFTER_ACCEPTANCE" -> "'Redacted finding'";
            default -> "NULL";
        };
        String applied = "APPLIED".equals(outcome) ? "'" + SHA_E + "'" : "NULL";
        String ciphertext = "QUARANTINED_AFTER_ACCEPTANCE".equals(outcome)
                ? "decode('deadbeef','hex')" : "NULL";
        String key = "QUARANTINED_AFTER_ACCEPTANCE".equals(outcome)
                ? "'kms://hrm012/test'" : "NULL";
        String envelope = "QUARANTINED_AFTER_ACCEPTANCE".equals(outcome)
                ? "'" + SHA_F + "'" : "NULL";
        jdbc.update("""
                INSERT INTO ppl_workforce_ingestion_items(
                    workforce_ingestion_item_id,item_public_id,tenant_id,
                    workforce_ingestion_run_id,connector_instance_id,
                    item_sequence,source_record_key_digest,
                    source_version_digest,input_digest,outcome,outcome_digest,
                    finding_code,redacted_summary,applied_effect_digest,
                    quarantine_ciphertext,quarantine_key_reference,
                    quarantine_envelope_digest)
                VALUES(?::UUID,?::UUID,?,?::UUID,?::UUID,?,?,?,?,?,?,
                       %s,%s,%s,%s,%s,%s)
                """.formatted(
                finding, summary, applied, ciphertext, key, envelope),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                run.tenantId(), run.runId().toString(),
                run.connectorId().toString(), sequence,
                SHA_A, SHA_B, SHA_C, outcome, SHA_D);
    }

    private String itemInsertSql(
            RunGraph run, long sequence, String outcome) {
        if (!"VALIDATED".equals(outcome)) {
            throw new IllegalArgumentException("RLS fixture supports VALIDATED only.");
        }
        return """
                INSERT INTO ppl_workforce_ingestion_items(
                    workforce_ingestion_item_id,item_public_id,tenant_id,
                    workforce_ingestion_run_id,connector_instance_id,
                    item_sequence,source_record_key_digest,
                    source_version_digest,input_digest,outcome,outcome_digest)
                VALUES('%s'::UUID,'%s'::UUID,%d,'%s'::UUID,'%s'::UUID,
                       %d,'%s','%s','%s','%s','%s')
                """.formatted(
                UUID.randomUUID(), UUID.randomUUID(), run.tenantId(),
                run.runId(), run.connectorId(), sequence,
                SHA_A, SHA_B, SHA_C, outcome, SHA_D);
    }

    private int completeValidation(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            long rejected,
            long duplicate,
            long accepted) {
        return jdbc.update("""
                UPDATE ppl_workforce_ingestion_runs
                   SET lifecycle_state='SUCCEEDED',lease_owner=NULL,
                       lease_until=NULL,source_count=?,accepted_count=?,
                       rejected_count=?,duplicate_count=?,quarantined_count=0,
                       applied_count=0,result_digest=?,
                       completed_at=clock_timestamp(),version=2
                 WHERE workforce_ingestion_run_id=?
                """, source, accepted, rejected, duplicate, SHA_E, run.runId());
    }

    private int completeApply(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            long accepted,
            long rejected,
            long duplicate,
            long quarantined,
            long applied,
            String cursorAfterDigest) {
        return jdbc.update("""
                UPDATE ppl_workforce_ingestion_runs
                   SET lifecycle_state='SUCCEEDED',lease_owner=NULL,
                       lease_until=NULL,source_count=?,accepted_count=?,
                       rejected_count=?,duplicate_count=?,quarantined_count=?,
                       applied_count=?,result_digest=?,cursor_after_digest=?,
                       cursor_committed_version=1,
                       completed_at=clock_timestamp(),version=2
                 WHERE workforce_ingestion_run_id=?
                """, source, accepted, rejected, duplicate, quarantined,
                applied, SHA_E, cursorAfterDigest, run.runId());
    }

    private void insertExactOutbox(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            long accepted,
            long rejected,
            long duplicate,
            long quarantined,
            long applied) {
        insertExactOutbox(jdbc, run, source, accepted, rejected, duplicate,
                quarantined, applied, deterministicId(
                        "WorkforceIngestionReconciled.v1", run, SHA_E), SHA_E);
    }

    private void insertExactOutbox(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            long accepted,
            long rejected,
            long duplicate,
            long quarantined,
            long applied,
            UUID eventId) {
        insertExactOutbox(jdbc, run, source, accepted, rejected, duplicate,
                quarantined, applied, eventId, SHA_E);
    }

    private void insertExactOutbox(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            long accepted,
            long rejected,
            long duplicate,
            long quarantined,
            long applied,
            UUID eventId,
            String resultDigest) {
        jdbc.update("""
                INSERT INTO sys_people_outbox_events(
                    event_id,tenant_id,aggregate_type,aggregate_id,
                    event_type,payload,correlation_id)
                SELECT ?::UUID,?,'WorkforceIngestionRun',?,
                       'WorkforceIngestionReconciled.v1',
                       jsonb_build_object(
                           'runPublicId',?,
                           'connectorPublicId',?,
                           'mappingVersion',1,
                           'sourceCount',?,
                           'acceptedCount',?,
                           'rejectedCount',?,
                           'duplicateCount',?,
                           'quarantinedCount',?,
                           'appliedCount',?,
                           'resultDigest',?),receipt.correlation_id::TEXT
                  FROM ppl_command_receipts receipt
                 WHERE receipt.tenant_id=? AND receipt.command_receipt_id=?::UUID
                """, eventId.toString(), run.tenantId(),
                run.runPublicId().toString(), run.runPublicId().toString(),
                run.connectorId().toString(), source, accepted, rejected,
                duplicate, quarantined, applied, resultDigest,
                run.tenantId(), run.receiptId().toString());
    }

    private void insertAudit(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            String resultDigest) {
        jdbc.update("""
                INSERT INTO sys_people_audit_events(
                    audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                    target_id,outcome,correlation_id,after_snapshot,occurred_at)
                SELECT ?::UUID,receipt.tenant_id,'USER',receipt.actor_public_id::TEXT,
                       'workforce-ingestion.reconciled','WORKFORCE_INGESTION_RUN',
                       ?,'SUCCESS',receipt.correlation_id::TEXT,
                       jsonb_build_object('mode',run.mode,'sourceCount',?,
                                          'resultDigest',?),clock_timestamp()
                  FROM ppl_command_receipts receipt
                  JOIN ppl_workforce_ingestion_runs run
                    ON run.tenant_id=receipt.tenant_id
                   AND run.command_receipt_id=receipt.command_receipt_id
                 WHERE receipt.tenant_id=? AND receipt.command_receipt_id=?::UUID
                """, deterministicId("audit", run, resultDigest).toString(),
                run.runPublicId().toString(), source, resultDigest,
                run.tenantId(), run.receiptId().toString());
    }

    private int completeReceipt(
            JdbcTemplate jdbc, RunGraph run, String resultDigest) {
        return jdbc.update("""
                UPDATE ppl_command_receipts
                   SET lifecycle_state='SUCCEEDED',
                       result_code='WORKFORCE_INGESTION_SUCCEEDED',
                       result_reference=COALESCE(result_reference,'{}'::jsonb)
                           || jsonb_build_object('runPublicId',?,'resultDigest',?),
                       completed_at=clock_timestamp(),updated_at=clock_timestamp(),
                       lease_owner=NULL,lease_until=NULL,next_attempt_at=NULL,
                       last_error_code=NULL
                 WHERE tenant_id=? AND command_receipt_id=?::UUID
                """, run.runPublicId().toString(), resultDigest,
                run.tenantId(), run.receiptId().toString());
    }

    private int advanceCursor(
            JdbcTemplate jdbc, RunGraph run, String cursor) {
        return jdbc.update("""
                INSERT INTO int_connector_cursors(
                    tenant_id,connector_instance_id,cursor_type,
                    committed_cursor,committed_at,version,updated_at)
                VALUES(?,?::UUID,'OPAQUE_TOKEN',?,clock_timestamp(),1,
                       clock_timestamp())
                ON CONFLICT (tenant_id,connector_instance_id,cursor_type) DO UPDATE SET
                    committed_cursor=EXCLUDED.committed_cursor,
                    committed_at=EXCLUDED.committed_at,
                    version=int_connector_cursors.version+1,
                    updated_at=EXCLUDED.updated_at
                 WHERE int_connector_cursors.version=0
                """, run.tenantId(), run.connectorId().toString(), cursor);
    }

    private void reconcileValidation(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            long rejected,
            long duplicate,
            long accepted) {
        transaction(jdbc).executeWithoutResult(status -> {
            insertAudit(jdbc, run, source, SHA_E);
            assertThat(completeValidation(
                    jdbc, run, source, rejected, duplicate, accepted)).isOne();
            assertThat(completeReceipt(jdbc, run, SHA_E)).isOne();
        });
    }

    private void reconcileApply(
            JdbcTemplate jdbc,
            RunGraph run,
            long source,
            long accepted,
            long rejected,
            long duplicate,
            long quarantined,
            long applied,
            String cursor) {
        transaction(jdbc).executeWithoutResult(status -> {
            assertThat(advanceCursor(jdbc, run, cursor)).isOne();
            insertAudit(jdbc, run, source, SHA_E);
            insertExactOutbox(jdbc, run, source, accepted, rejected, duplicate,
                    quarantined, applied);
            assertThat(completeApply(jdbc, run, source, accepted, rejected,
                    duplicate, quarantined, applied, digest(cursor))).isOne();
            assertThat(completeReceipt(jdbc, run, SHA_E)).isOne();
        });
    }

    private void finishApplyWithoutCursorMutation(
            JdbcTemplate jdbc, RunGraph run, String cursorDigest) {
        transaction(jdbc).executeWithoutResult(status -> {
            insertAudit(jdbc, run, 1, SHA_E);
            insertExactOutbox(jdbc, run, 1, 1, 0, 0, 0, 1);
            assertThat(completeApply(
                    jdbc, run, 1, 1, 0, 0, 0, 1, cursorDigest)).isOne();
            assertThat(completeReceipt(jdbc, run, SHA_E)).isOne();
        });
    }

    private TransactionTemplate transaction(JdbcTemplate jdbc) {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Test datasource is unavailable.");
        return new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    private void assertRunningAndReceiptOpen(JdbcTemplate jdbc, RunGraph run) {
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_workforce_ingestion_runs
                 WHERE workforce_ingestion_run_id=?
                """, String.class, run.runId())).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_command_receipts
                 WHERE tenant_id=? AND command_receipt_id=?
                """, String.class, run.tenantId(), run.receiptId()))
                .isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_outbox_events
                 WHERE tenant_id=? AND aggregate_id=?
                """, Integer.class, run.tenantId(), run.runPublicId().toString()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_audit_events
                 WHERE tenant_id=? AND target_id=?
                """, Integer.class, run.tenantId(), run.runPublicId().toString()))
                .isZero();
    }

    private String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID deterministicId(
            String domain, RunGraph run, String resultDigest) {
        try {
            byte[] source = ("dwp.hris.hrm012\n" + domain + "\n"
                    + run.tenantId() + "\n" + run.runPublicId() + "\n"
                    + resultDigest).getBytes(StandardCharsets.UTF_8);
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(source);
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(hash);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void insertCursor(
            JdbcTemplate jdbc,
            RunGraph run,
            long version,
            String cursor) {
        jdbc.update("""
                INSERT INTO int_connector_cursors(
                    tenant_id,connector_instance_id,cursor_type,
                    committed_cursor,committed_at,version)
                VALUES(?,?,'OPAQUE_TOKEN',?,clock_timestamp(),?)
                """, run.tenantId(), run.connectorId(), cursor, version);
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

    private record RunGraph(
            long tenantId,
            UUID runId,
            UUID runPublicId,
            UUID receiptId,
            UUID connectorId,
            UUID mappingId,
            String objectRef) {

        private RunGraph withRun(
                UUID newRunId,
                UUID newRunPublicId,
                UUID newReceiptId) {
            return new RunGraph(
                    tenantId, newRunId, newRunPublicId, newReceiptId,
                    connectorId, mappingId, objectRef);
        }
    }
}
