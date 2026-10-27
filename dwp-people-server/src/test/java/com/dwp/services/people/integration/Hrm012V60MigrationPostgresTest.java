package com.dwp.services.people.integration;

import static com.dwp.services.people.integration.Hrm012V60MigrationFixture.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
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
        migrated = flyway(source, "60");
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

        migrated = flyway(source, "60");
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

    private DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

}
