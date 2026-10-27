package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.AuthoritySnapshot;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerRepository.*;

/** Shared setup and evidence builders for WorkExplorerMigrationPostgresTest. */
abstract class WorkExplorerMigrationPostgresFixture {
    protected static final Set<String> TABLES = Set.of(
            "sys_hris_explorer_preferences",
            "sys_hris_explorer_favorites",
            "sys_hris_explorer_recents",
            "sys_hris_command_receipts");
    protected static final UUID PRINCIPAL_ONE =
            UUID.fromString("00000000-0000-4000-8000-000000000101");
    protected static final UUID PRINCIPAL_TWO =
            UUID.fromString("00000000-0000-4000-8000-000000000202");
    protected static final String POPULATION_DIGEST = "a".repeat(64);
    protected static final String REQUEST_DIGEST = "b".repeat(64);
    protected static final String ORIGIN_CONTEXT = "psc-" + "c".repeat(64);
    protected static final String ORIGIN_SCOPE = "hcm-scope-" + "d".repeat(40);
    protected static final String AUTHORITY_POPULATION_DIGEST =
            HrisExplorerAuthoritySeals.populationScopeDigest(ORIGIN_SCOPE);
    protected static final String ORIGIN_DECISION = "psr-" + "e".repeat(64);
    protected static final long AUTHORITY_FIELD_REVISION = 4_663_670_355_146_917_040L;
    protected static final long PUBLIC_FIELD_REVISION = 6_948_340_445_824_176L;
    protected static final long AUTHORITY_AUTHORIZATION_REVISION = 1_991_387_878_219_104_048L;
    protected static final long PUBLIC_AUTHORIZATION_REVISION = 796_842_921_344_816L;

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    protected void concurrentRecent(
            DataSourceTransactionManager manager,
            JdbcHrisExplorerRepository repository,
            CountDownLatch ready,
            CountDownLatch start,
            int ordinal,
            String route,
            Set<String> allowed,
            OffsetDateTime visitedAt) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent recent start barrier timed out");
        }
        new TransactionTemplate(manager).execute(ignored -> repository.recordRecent(
                command(ordinal, JdbcHrisExplorerRepository.RECENT_ACTION),
                route, allowed, digest(ordinal), visitedAt));
    }

    protected CommandContext command(int ordinal, String action) {
        return new CommandContext(
                42, PRINCIPAL_ONE, POPULATION_DIGEST, AUTHORITY_POPULATION_DIGEST, 7, 11,
                originRoute(action), ORIGIN_CONTEXT, ORIGIN_SCOPE, ORIGIN_DECISION,
                UUID.nameUUIDFromBytes(("correlation-" + ordinal).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)),
                "explorer-idempotency-" + ordinal, action);
    }

    protected CommandContext originCommand(
            String idempotencyKey, int ordinal,
            String contextKey, String decisionRevision) {
        return originCommand(idempotencyKey, ordinal, contextKey,
                decisionRevision, 7, 11);
    }

    protected CommandContext originCommand(
            String idempotencyKey, int ordinal,
            String contextKey, String decisionRevision,
            long fieldPolicyRevision, long authorizationRevision) {
        return new CommandContext(
                42, PRINCIPAL_ONE, POPULATION_DIGEST, AUTHORITY_POPULATION_DIGEST,
                fieldPolicyRevision, authorizationRevision,
                originRoute(JdbcHrisExplorerRepository.RECENT_ACTION),
                contextKey, ORIGIN_SCOPE, decisionRevision,
                UUID.nameUUIDFromBytes(("origin-correlation-" + ordinal).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)),
                idempotencyKey, JdbcHrisExplorerRepository.RECENT_ACTION);
    }

    protected AuthoritySnapshot authoritySnapshot(
            String contextKey, String decisionRevision) {
        return authoritySnapshot(contextKey, decisionRevision, 7, 11);
    }

    protected AuthoritySnapshot authoritySnapshot(
            String contextKey, String decisionRevision,
            long fieldPolicyRevision, long authorizationRevision) {
        AuthoritySnapshot value = HrisExplorerTestFixtures.snapshot();
        return new AuthoritySnapshot(
                value.tenantId(), value.subjectPrincipalPublicId(),
                decisionRevision, contextKey, ORIGIN_SCOPE,
                value.purposeCode(), POPULATION_DIGEST,
                fieldPolicyRevision, authorizationRevision,
                value.entitlementRevision(), value.projectionRevision(),
                value.freshUntil(), value.allowedRouteDecisions());
    }

    protected void insertActiveReceipt(
            JdbcTemplate jdbc, UUID receiptId, int ordinal,
            String routeKey, OffsetDateTime createdAt) {
        jdbc.update("""
                INSERT INTO sys_hris_command_receipts (
                    command_receipt_id, public_id, tenant_id, command_type,
                    originating_action, subject_principal_public_id,
                    population_scope_digest, authority_population_scope_digest,
                    field_policy_revision, authority_field_policy_revision,
                    purpose_code, authorization_revision,
                    authority_authorization_revision, origin_route_contract_key,
                    origin_context_key, origin_context_scope_key,
                    origin_decision_revision, idempotency_key, request_digest,
                    status, result_ref, correlation_id, created_at)
                VALUES (?, ?, 42, 'EXPLORER_RECENT_RECORD',
                    'EXPLORER_RECENT_RECORD', ?, ?, ?, 7, 7,
                    'HRIS_APP_DISCOVERY', 11, 11,
                    'route.hcm.utility.explorer-recent-create.action',
                    ?, ?, ?, ?, ?, 'RESULT_UNKNOWN', ?, ?, ?)
                """, receiptId, receiptId, PRINCIPAL_ONE, POPULATION_DIGEST,
                AUTHORITY_POPULATION_DIGEST,
                ORIGIN_CONTEXT, ORIGIN_SCOPE, ORIGIN_DECISION,
                "explorer-filter-%04d".formatted(ordinal),
                "%064x".formatted(ordinal), "route:" + routeKey,
                UUID.nameUUIDFromBytes(("filter-correlation-" + ordinal).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)), createdAt);
    }

    protected String originRoute(String action) {
        return JdbcHrisExplorerRepository.PREFERENCES_ACTION.equals(action)
                ? "route.hcm.utility.explorer-preferences-update.action"
                : "route.hcm.utility.explorer-recent-create.action";
    }

    protected String digest(int ordinal) {
        return "%064x".formatted(ordinal);
    }

    protected PGSimpleDataSource migratedDataSource(String schema) {
        PGSimpleDataSource source = dataSource();
        Flyway migration = flyway(source, schema, "327").load();
        migration.clean();
        migration.migrate();
        source.setCurrentSchema(schema);
        return source;
    }

    protected void assertV327Schema(JdbcTemplate jdbc, String schema) {
        assertThat(tables(jdbc, schema)).containsAll(TABLES);
        assertThat(jdbc.queryForList("""
                SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = ?
                   AND c.relname IN (
                       'sys_hris_explorer_preferences',
                       'sys_hris_explorer_favorites',
                       'sys_hris_explorer_recents',
                       'sys_hris_command_receipts')
                   AND c.relkind = 'r'
                 ORDER BY c.relname
                """, schema))
                .hasSize(4)
                .allSatisfy(row -> {
                    assertThat(row.get("relrowsecurity")).isEqualTo(true);
                    assertThat(row.get("relforcerowsecurity")).isEqualTo(true);
                });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_constraint constraint_row
                  JOIN pg_class relation_row
                    ON relation_row.oid = constraint_row.conrelid
                  JOIN pg_namespace namespace_row
                    ON namespace_row.oid = relation_row.relnamespace
                 WHERE namespace_row.nspname = ?
                   AND relation_row.relname IN (
                       'sys_hris_explorer_preferences',
                       'sys_hris_explorer_favorites',
                       'sys_hris_explorer_recents',
                       'sys_hris_command_receipts')
                   AND constraint_row.contype = 'f'
                """, Integer.class, schema))
                .isOne();
        assertThat(jdbc.queryForList("""
                SELECT table_name, data_type
                  FROM information_schema.columns
                 WHERE table_schema = ?
                   AND (table_name, column_name) IN (
                       ('sys_hris_explorer_preferences', 'explorer_preference_id'),
                       ('sys_hris_explorer_favorites', 'favorite_id'),
                       ('sys_hris_explorer_recents', 'recent_id'),
                       ('sys_hris_command_receipts', 'command_receipt_id'))
                 ORDER BY table_name
                """, schema))
                .hasSize(4)
                .allSatisfy(row -> assertThat(row.get("data_type")).isEqualTo("uuid"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.columns
                 WHERE table_schema = ?
                   AND table_name = 'sys_hris_command_receipts'
                   AND column_name = 'authority_population_scope_digest'
                   AND data_type = 'character'
                   AND character_maximum_length = 64
                   AND is_nullable = 'NO'
                """, Integer.class, schema)).isOne();
        assertThat(jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable
                  FROM information_schema.columns
                 WHERE table_schema = ?
                   AND table_name = 'sys_hris_command_receipts'
                   AND column_name IN (
                       'authority_field_policy_revision',
                       'authority_authorization_revision')
                 ORDER BY column_name
                """, schema))
                .hasSize(2)
                .allSatisfy(row -> {
                    assertThat(row.get("data_type")).isEqualTo("bigint");
                    assertThat(row.get("is_nullable")).isEqualTo("NO");
                });
        assertThat(jdbc.queryForList("""
                SELECT tablename, qual, with_check
                  FROM pg_policies
                 WHERE schemaname = ?
                   AND tablename IN (
                       'sys_hris_explorer_preferences',
                       'sys_hris_explorer_favorites',
                       'sys_hris_explorer_recents',
                       'sys_hris_command_receipts')
                 ORDER BY tablename
                """, schema))
                .hasSize(4)
                .allSatisfy(row -> {
                    assertThat(row.get("qual").toString())
                            .contains("dwp.tenant_id", "dwp.principal_public_id");
                    assertThat(row.get("with_check").toString())
                            .contains("dwp.tenant_id", "dwp.principal_public_id");
                });
    }

    protected void assertTenantAndPrincipalIsolation(JdbcTemplate jdbc, String schema) {
        insertPreference(jdbc, schema, 42, PRINCIPAL_ONE);
        insertPreference(jdbc, schema, 42, PRINCIPAL_TWO);
        insertPreference(jdbc, schema, 43, PRINCIPAL_ONE);

        assertThatThrownBy(() -> insertPreference(jdbc, schema, 42, PRINCIPAL_ONE))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM %s.sys_hris_explorer_preferences
                 WHERE tenant_id = 42 AND principal_public_id = ?
                """.formatted(schema), Integer.class, PRINCIPAL_ONE)).isOne();

    }

    protected void assertAllFourTablesEnforceTenantAndPrincipalRls(
            JdbcTemplate jdbc,
            PGSimpleDataSource source,
            String schema) {
        String role = "sys012_explorer_runtime_clean";
        jdbc.execute("DROP ROLE IF EXISTS " + role);
        jdbc.execute("CREATE ROLE " + role + " NOLOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
        for (String table : TABLES) {
            jdbc.execute("GRANT SELECT ON " + schema + "." + table + " TO " + role);
        }

        assertThat(visibleCounts(
                jdbc, source, schema, role,
                42, PRINCIPAL_ONE, 42, PRINCIPAL_ONE))
                .containsEntry("sys_hris_explorer_preferences", 1)
                .containsEntry("sys_hris_explorer_favorites", 20)
                .containsEntry("sys_hris_explorer_recents", 1)
                .containsEntry("sys_hris_command_receipts", 1);
        assertThat(visibleCounts(
                jdbc, source, schema, role,
                42, PRINCIPAL_TWO, 42, PRINCIPAL_ONE))
                .containsEntry("sys_hris_explorer_preferences", 0)
                .containsEntry("sys_hris_explorer_favorites", 0)
                .containsEntry("sys_hris_explorer_recents", 0)
                .containsEntry("sys_hris_command_receipts", 0);
        assertThat(visibleCounts(
                jdbc, source, schema, role,
                43, PRINCIPAL_ONE, 42, PRINCIPAL_ONE))
                .containsEntry("sys_hris_explorer_preferences", 0)
                .containsEntry("sys_hris_explorer_favorites", 0)
                .containsEntry("sys_hris_explorer_recents", 0)
                .containsEntry("sys_hris_command_receipts", 0);
        assertThat(visibleCounts(
                jdbc, source, schema, role,
                99, PRINCIPAL_ONE, 42, PRINCIPAL_ONE))
                .allSatisfy((table, count) -> assertThat(count).as(table).isZero());
    }

    protected Map<String, Integer> visibleCounts(
            JdbcTemplate jdbc,
            PGSimpleDataSource source,
            String schema,
            String role,
            long viewerTenantId,
            UUID viewerPrincipal,
            long rowTenantId,
            UUID rowPrincipal) {
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        return transactions.execute(ignored -> {
            jdbc.execute("SET LOCAL ROLE " + role);
            jdbc.queryForObject(
                    "SELECT set_config('dwp.tenant_id', ?, true)",
                    String.class,
                    Long.toString(viewerTenantId));
            jdbc.queryForObject(
                    "SELECT set_config('dwp.principal_public_id', ?, true)",
                    String.class,
                    viewerPrincipal.toString());
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("sys_hris_explorer_preferences", jdbc.queryForObject("""
                    SELECT COUNT(*) FROM %s.sys_hris_explorer_preferences
                     WHERE tenant_id = ? AND principal_public_id = ?
                    """.formatted(schema), Integer.class, rowTenantId, rowPrincipal));
            counts.put("sys_hris_explorer_favorites", jdbc.queryForObject("""
                    SELECT COUNT(*) FROM %s.sys_hris_explorer_favorites
                     WHERE tenant_id = ? AND principal_public_id = ?
                    """.formatted(schema), Integer.class, rowTenantId, rowPrincipal));
            counts.put("sys_hris_explorer_recents", jdbc.queryForObject("""
                    SELECT COUNT(*) FROM %s.sys_hris_explorer_recents
                     WHERE tenant_id = ? AND principal_public_id = ?
                    """.formatted(schema), Integer.class, rowTenantId, rowPrincipal));
            counts.put("sys_hris_command_receipts", jdbc.queryForObject("""
                    SELECT COUNT(*) FROM %s.sys_hris_command_receipts
                     WHERE tenant_id = ? AND subject_principal_public_id = ?
                    """.formatted(schema), Integer.class, rowTenantId, rowPrincipal));
            return counts;
        });
    }

    protected void assertBoundedFavoritesRecentsAndReceiptSeal(
            JdbcTemplate jdbc,
            String schema) {
        UUID preferenceId = jdbc.queryForObject("""
                SELECT explorer_preference_id
                  FROM %s.sys_hris_explorer_preferences
                 WHERE tenant_id = 42 AND principal_public_id = ?
                """.formatted(schema), UUID.class, PRINCIPAL_ONE);
        for (int position = 1; position <= 20; position++) {
            jdbc.update("""
                    INSERT INTO %s.sys_hris_explorer_favorites (
                        tenant_id, principal_public_id, explorer_preference_id,
                        route_key, position)
                    VALUES (42, ?, ?, ?, ?)
                    """.formatted(schema), PRINCIPAL_ONE, preferenceId,
                    "hris.explorer.route-" + position, position);
        }
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO %s.sys_hris_explorer_favorites (
                    tenant_id, principal_public_id, explorer_preference_id,
                    route_key, position)
                VALUES (42, ?, ?, 'hris.explorer.route-21', 21)
                """.formatted(schema), PRINCIPAL_ONE, preferenceId))
                .isInstanceOf(DataIntegrityViolationException.class);

        OffsetDateTime visitedAt = OffsetDateTime.parse("2026-10-15T00:00:00Z");
        jdbc.update("""
                INSERT INTO %s.sys_hris_explorer_recents (
                    tenant_id, principal_public_id, route_key,
                    last_visited_at, expires_at,
                    route_catalog_revision, entitlement_revision)
                VALUES (42, ?, 'hris.explorer.route-1', ?, ?, 1, 1)
                """.formatted(schema), PRINCIPAL_ONE, visitedAt, visitedAt.plusDays(30));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO %s.sys_hris_explorer_recents (
                    tenant_id, principal_public_id, route_key,
                    last_visited_at, expires_at,
                    route_catalog_revision, entitlement_revision)
                VALUES (42, ?, 'hris.explorer.route-2', ?, ?, 1, 1)
                """.formatted(schema), PRINCIPAL_ONE, visitedAt, visitedAt.plusDays(29)))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID receiptId = UUID.fromString("00000000-0000-4000-8000-000000000327");
        insertReceipt(jdbc, schema, 42, PRINCIPAL_ONE, receiptId);
        assertThat(jdbc.queryForMap("""
                SELECT field_policy_revision, authority_field_policy_revision,
                       authorization_revision, authority_authorization_revision
                  FROM %s.sys_hris_command_receipts
                 WHERE tenant_id = 42 AND public_id = ?
                """.formatted(schema), receiptId))
                .containsEntry("field_policy_revision", PUBLIC_FIELD_REVISION)
                .containsEntry("authority_field_policy_revision", AUTHORITY_FIELD_REVISION)
                .containsEntry("authorization_revision", PUBLIC_AUTHORIZATION_REVISION)
                .containsEntry("authority_authorization_revision",
                        AUTHORITY_AUTHORIZATION_REVISION);
        assertThatThrownBy(() -> insertReceiptWithRevisions(
                jdbc, schema, 42, PRINCIPAL_ONE,
                UUID.fromString("00000000-0000-4000-8000-000000000330"),
                "explorer-wrong-projection-0001",
                PUBLIC_FIELD_REVISION + 1, AUTHORITY_FIELD_REVISION,
                PUBLIC_AUTHORIZATION_REVISION,
                AUTHORITY_AUTHORIZATION_REVISION))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE %s.sys_hris_command_receipts
                   SET field_policy_revision = ?
                 WHERE tenant_id = 42 AND public_id = ?
                """.formatted(schema), PUBLIC_FIELD_REVISION + 1, receiptId))
                .isInstanceOf(DataIntegrityViolationException.class);
        long authorityWithZeroLow53Bits = 1L << 53;
        UUID zeroProjectionReceipt = UUID.fromString(
                "00000000-0000-4000-8000-000000000331");
        insertReceiptWithRevisions(
                jdbc, schema, 42, PRINCIPAL_TWO, zeroProjectionReceipt,
                "explorer-zero-projection-0001",
                1, authorityWithZeroLow53Bits,
                1, authorityWithZeroLow53Bits);
        assertThat(jdbc.queryForMap("""
                SELECT field_policy_revision, authority_field_policy_revision,
                       authorization_revision, authority_authorization_revision
                  FROM %s.sys_hris_command_receipts
                 WHERE tenant_id = 42 AND public_id = ?
                """.formatted(schema), zeroProjectionReceipt))
                .containsEntry("field_policy_revision", 1L)
                .containsEntry("authority_field_policy_revision",
                        authorityWithZeroLow53Bits)
                .containsEntry("authorization_revision", 1L)
                .containsEntry("authority_authorization_revision",
                        authorityWithZeroLow53Bits);
        assertThatThrownBy(() -> insertReceipt(
                jdbc,
                schema,
                42,
                PRINCIPAL_ONE,
                UUID.fromString("00000000-0000-4000-8000-000000000328")))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertReceipt(
                jdbc,
                schema,
                42,
                PRINCIPAL_TWO,
                UUID.fromString("00000000-0000-4000-8000-000000000329"));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE %s.sys_hris_command_receipts
                   SET request_digest = ?
                 WHERE tenant_id = 42 AND public_id = ?
                """.formatted(schema), "c".repeat(64), receiptId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE %s.sys_hris_command_receipts
                   SET authority_authorization_revision =
                       authority_authorization_revision + 1
                 WHERE tenant_id = 42 AND public_id = ?
                """.formatted(schema), receiptId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE %s.sys_hris_command_receipts
                   SET result_ref = ''
                 WHERE tenant_id = 42 AND public_id = ?
                """.formatted(schema), receiptId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE %s.sys_hris_command_receipts
                   SET completed_at = created_at - INTERVAL '1 second'
                 WHERE tenant_id = 42 AND public_id = ?
                """.formatted(schema), receiptId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    protected void insertPreference(
            JdbcTemplate jdbc,
            String schema,
            long tenantId,
            UUID principal) {
        jdbc.update("""
                INSERT INTO %s.sys_hris_explorer_preferences (
                    tenant_id, principal_public_id, preference_revision,
                    route_catalog_revision, entitlement_revision, updated_by)
                VALUES (?, ?, 1, 1, 1, ?)
                """.formatted(schema), tenantId, principal, principal);
    }

    protected void insertReceipt(
            JdbcTemplate jdbc,
            String schema,
            long tenantId,
            UUID principal,
            UUID publicId) {
        insertReceiptWithRevisions(
                jdbc, schema, tenantId, principal, publicId,
                "explorer-preferences-0001",
                PUBLIC_FIELD_REVISION, AUTHORITY_FIELD_REVISION,
                PUBLIC_AUTHORIZATION_REVISION,
                AUTHORITY_AUTHORIZATION_REVISION);
    }

    protected void insertReceiptWithRevisions(
            JdbcTemplate jdbc,
            String schema,
            long tenantId,
            UUID principal,
            UUID publicId,
            String idempotencyKey,
            long publicFieldRevision,
            long authorityFieldRevision,
            long publicAuthorizationRevision,
            long authorityAuthorizationRevision) {
        jdbc.update("""
                INSERT INTO %s.sys_hris_command_receipts (
                    public_id, tenant_id, command_type, originating_action,
                    subject_principal_public_id, population_scope_digest,
                    authority_population_scope_digest,
                    field_policy_revision, authority_field_policy_revision,
                    purpose_code, authorization_revision,
                    authority_authorization_revision,
                    origin_route_contract_key, origin_context_key,
                    origin_context_scope_key, origin_decision_revision,
                    idempotency_key, request_digest, status, correlation_id)
                VALUES (?, ?, 'EXPLORER_PREFERENCES_REPLACE',
                    'EXPLORER_PREFERENCES_REPLACE', ?, ?, ?, ?, ?,
                    'HRIS_APP_DISCOVERY', ?, ?,
                    'route.hcm.utility.explorer-preferences-update.action',
                    ?, ?, ?, ?, ?, 'ACCEPTED', ?)
                """.formatted(schema), publicId, tenantId, principal,
                POPULATION_DIGEST, AUTHORITY_POPULATION_DIGEST,
                publicFieldRevision, authorityFieldRevision,
                publicAuthorizationRevision, authorityAuthorizationRevision,
                ORIGIN_CONTEXT, ORIGIN_SCOPE,
                ORIGIN_DECISION, idempotencyKey, REQUEST_DIGEST,
                UUID.fromString("00000000-0000-4000-8000-000000000777"));
    }

    protected Map<String, Integer> migrationChecksums(JdbcTemplate jdbc, String schema) {
        return jdbc.query("""
                SELECT version, checksum
                  FROM %s.flyway_schema_history
                 WHERE version IS NOT NULL
                 ORDER BY installed_rank
                """.formatted(schema), result -> {
            Map<String, Integer> checksums = new LinkedHashMap<>();
            while (result.next()) {
                checksums.put(result.getString("version"),
                        (Integer) result.getObject("checksum"));
            }
            return checksums;
        });
    }

    protected Set<String> tables(JdbcTemplate jdbc, String schema) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = ?
                   AND table_type = 'BASE TABLE'
                 ORDER BY table_name
                """, String.class, schema));
    }

    protected FluentConfiguration flyway(
            PGSimpleDataSource source,
            String schema,
            String target) {
        // Extensions are database-scoped. Keep pgcrypto outside the isolated
        // migration schemas so every full-history migration can resolve digest().
        new JdbcTemplate(source).execute(
                "CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public");
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration;
    }

    protected PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
