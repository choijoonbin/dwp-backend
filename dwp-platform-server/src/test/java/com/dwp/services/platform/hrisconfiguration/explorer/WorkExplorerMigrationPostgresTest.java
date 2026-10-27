package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.AuthoritySnapshot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerRepository.*;

@Testcontainers(disabledWithoutDocker = true)
class WorkExplorerMigrationPostgresTest extends WorkExplorerMigrationPostgresFixture {

    @Test
    void cleanMigrationThroughV327CreatesBoundedPrincipalPrivateState() {
        String schema = "sys012_explorer_clean";
        PGSimpleDataSource source = dataSource();
        Flyway clean = flyway(source, schema, "327").load();
        clean.clean();
        clean.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertV327Schema(jdbc, schema);
        assertTenantAndPrincipalIsolation(jdbc, schema);
        assertBoundedFavoritesRecentsAndReceiptSeal(jdbc, schema);
        assertAllFourTablesEnforceTenantAndPrincipalRls(jdbc, source, schema);
    }

    @Test
    void v326ToV327IsForwardOnlyAndPreservesHistoricalChecksums() {
        String schema = "sys012_explorer_upgrade";
        PGSimpleDataSource source = dataSource();
        Flyway throughV326 = flyway(source, schema, "326").load();
        throughV326.clean();
        throughV326.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(source);
        Map<String, Integer> historicalChecksums = migrationChecksums(jdbc, schema);
        Set<String> tablesBefore = tables(jdbc, schema);
        assertThat(historicalChecksums).containsKey("326");
        assertThat(tablesBefore).doesNotContainAnyElementsOf(TABLES);

        flyway(source, schema, "327").load().migrate();

        Map<String, Integer> upgradedChecksums = migrationChecksums(jdbc, schema);
        assertThat(upgradedChecksums).containsAllEntriesOf(historicalChecksums);
        assertThat(upgradedChecksums).containsKey("327");
        Set<String> addedTables = new LinkedHashSet<>(tables(jdbc, schema));
        addedTables.removeAll(tablesBefore);
        assertThat(addedTables).containsExactlyInAnyOrderElementsOf(TABLES);
        assertV327Schema(jdbc, schema);
    }

    @Test
    void jdbcRepositoryExecutesUuidCasReplayAndActiveReceiptQueryOnPostgres() {
        String schema = "sys012_explorer_repository";
        PGSimpleDataSource source = dataSource();
        Flyway clean = flyway(source, schema, "327").load();
        clean.clean();
        clean.migrate();
        source.setCurrentSchema(schema);

        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcHrisExplorerRepository repository = new JdbcHrisExplorerRepository(jdbc);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        OffsetDateTime now = OffsetDateTime.parse("2026-10-15T04:00:00Z");
        String route = "hris.explorer.team-time";
        CommandContext recent = new CommandContext(
                42, PRINCIPAL_ONE, POPULATION_DIGEST, AUTHORITY_POPULATION_DIGEST,
                AUTHORITY_FIELD_REVISION, AUTHORITY_AUTHORIZATION_REVISION,
                originRoute(JdbcHrisExplorerRepository.RECENT_ACTION),
                ORIGIN_CONTEXT, ORIGIN_SCOPE, ORIGIN_DECISION,
                UUID.fromString("00000000-0000-4000-8000-000000000701"),
                "explorer-recent-0001", JdbcHrisExplorerRepository.RECENT_ACTION);

        var firstRecent = transactions.execute(ignored -> repository.recordRecent(
                recent, route, Set.of(route), REQUEST_DIGEST, now));
        var replayedRecent = transactions.execute(ignored -> repository.recordRecent(
                recent, route, Set.of(route), REQUEST_DIGEST, now.plusMinutes(1)));

        assertThat(firstRecent).isNotNull();
        assertThat(replayedRecent).isEqualTo(firstRecent);
        assertThat(firstRecent.receiptId()).isInstanceOf(UUID.class);
        assertThat(firstRecent.fieldPolicyRevision()).isEqualTo(PUBLIC_FIELD_REVISION);
        assertThat(firstRecent.authorizationRevision())
                .isEqualTo(PUBLIC_AUTHORIZATION_REVISION);
        ReceiptAuthority firstAuthority = transactions.execute(ignored ->
                repository.findReceiptAuthority(
                        42, PRINCIPAL_ONE, firstRecent.receiptId()).orElseThrow());
        assertThat(firstAuthority).isNotNull();
        assertThat(firstAuthority.authorityFieldPolicyRevision())
                .isEqualTo(AUTHORITY_FIELD_REVISION);
        assertThat(firstAuthority.authorityAuthorizationRevision())
                .isEqualTo(AUTHORITY_AUTHORIZATION_REVISION);
        assertThatThrownBy(() -> transactions.execute(ignored -> repository.recordRecent(
                recent, route, Set.of(), "c".repeat(64), now.plusMinutes(2))))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);
        ExplorerState retainedAfterMismatchedReplay = transactions.execute(ignored ->
                repository.readAndPurge(
                        42, PRINCIPAL_ONE, Set.of(route), now.plusMinutes(2)));
        assertThat(retainedAfterMismatchedReplay).isNotNull();
        assertThat(retainedAfterMismatchedReplay.recents())
                .extracting(RecentRow::routeKey).containsExactly(route);

        var replayedAndPurged = transactions.execute(ignored -> repository.recordRecent(
                recent, route, Set.of(), REQUEST_DIGEST, now.plusMinutes(2)));
        assertThat(replayedAndPurged).isEqualTo(firstRecent);
        ExplorerState purgedAfterExactReplay = transactions.execute(ignored ->
                repository.readAndPurge(
                        42, PRINCIPAL_ONE, Set.of(route), now.plusMinutes(2)));
        assertThat(purgedAfterExactReplay).isNotNull();
        assertThat(purgedAfterExactReplay.recents()).isEmpty();

        CommandContext restoredRecent = new CommandContext(
                42, PRINCIPAL_ONE, POPULATION_DIGEST, AUTHORITY_POPULATION_DIGEST,
                AUTHORITY_FIELD_REVISION, AUTHORITY_AUTHORIZATION_REVISION,
                originRoute(JdbcHrisExplorerRepository.RECENT_ACTION),
                ORIGIN_CONTEXT, ORIGIN_SCOPE, ORIGIN_DECISION,
                UUID.fromString("00000000-0000-4000-8000-000000000705"),
                "explorer-recent-0004", JdbcHrisExplorerRepository.RECENT_ACTION);
        transactions.execute(ignored -> repository.recordRecent(
                restoredRecent, route, Set.of(route), "f".repeat(64),
                now.plusMinutes(3)));

        CommandContext preferences = new CommandContext(
                42, PRINCIPAL_ONE, POPULATION_DIGEST, AUTHORITY_POPULATION_DIGEST,
                AUTHORITY_FIELD_REVISION, AUTHORITY_AUTHORIZATION_REVISION,
                originRoute(JdbcHrisExplorerRepository.PREFERENCES_ACTION),
                ORIGIN_CONTEXT, ORIGIN_SCOPE, ORIGIN_DECISION,
                UUID.fromString("00000000-0000-4000-8000-000000000702"),
                "explorer-preferences-0002",
                JdbcHrisExplorerRepository.PREFERENCES_ACTION);
        var preferenceReceipt = transactions.execute(ignored -> repository.replaceFavorites(
                preferences, 1, List.of(route), Set.of(route),
                "d".repeat(64), now.plusMinutes(4)));
        assertThat(preferenceReceipt).isNotNull();
        assertThat(preferenceReceipt.aggregateVersion()).isEqualTo(2);

        ExplorerState state = transactions.execute(ignored -> repository.readAndPurge(
                42, PRINCIPAL_ONE, Set.of(route), now.plusMinutes(5)));
        assertThat(state).isNotNull();
        assertThat(state.preferenceRevision()).isEqualTo(2);
        assertThat(state.favorites()).extracting(FavoriteRow::routeKey).containsExactly(route);
        assertThat(state.recents()).extracting(RecentRow::routeKey).containsExactly(route);

        UUID activeReceiptId = UUID.fromString(
                "00000000-0000-4000-8000-000000000703");
        transactions.executeWithoutResult(ignored -> {
            jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?, true)",
                    String.class, "42");
            jdbc.queryForObject("SELECT set_config('dwp.principal_public_id', ?, true)",
                    String.class, PRINCIPAL_ONE.toString());
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
                        ?, ?, ?, 'explorer-active-0003', ?,
                        'RESULT_UNKNOWN', ?, ?, ?)
                    """, activeReceiptId, activeReceiptId, PRINCIPAL_ONE,
                    POPULATION_DIGEST, AUTHORITY_POPULATION_DIGEST,
                    ORIGIN_CONTEXT, ORIGIN_SCOPE,
                    ORIGIN_DECISION, "e".repeat(64), "route:" + route,
                    UUID.fromString("00000000-0000-4000-8000-000000000704"), now);
        });

        ReceiptActivityProjection active = transactions.execute(ignored ->
                repository.findActiveReceiptActivities(
                        42, PRINCIPAL_ONE, Set.of(route), now.plusMinutes(6)));
        assertThat(active).isNotNull();
        assertThat(active.sourceRevision()).isPositive();
        assertThat(active.generatedAt()).isNotNull();
        assertThat(active.rows()).singleElement().satisfies(row -> {
            assertThat(row.receiptId()).isEqualTo(activeReceiptId);
            assertThat(row.status()).isEqualTo("RESULT_UNKNOWN");
            assertThat(row.resultRef()).isEqualTo("route:" + route);
            assertThat(row.authorityPopulationScopeDigest())
                    .isEqualTo(AUTHORITY_POPULATION_DIGEST);
            assertThat(row.purposeCode()).isEqualTo("HRIS_APP_DISCOVERY");
            assertThat(row.authorityAuthorizationRevision()).isEqualTo(11);
            assertThat(row.authorityFieldPolicyRevision()).isEqualTo(7);
        });
        java.util.Optional<HrisExplorerDtos.HrisExplorerReceipt> stored =
                transactions.execute(ignored -> repository.findReceipt(
                        42, PRINCIPAL_ONE, activeReceiptId));
        assertThat(stored).isPresent();
        java.util.Optional<HrisExplorerDtos.HrisExplorerReceipt> otherPrincipal =
                transactions.execute(ignored -> repository.findReceipt(
                        42, PRINCIPAL_TWO, activeReceiptId));
        java.util.Optional<HrisExplorerDtos.HrisExplorerReceipt> otherTenant =
                transactions.execute(ignored -> repository.findReceipt(
                        43, PRINCIPAL_ONE, activeReceiptId));
        assertThat(otherPrincipal).isEmpty();
        assertThat(otherTenant).isEmpty();
    }

    @Test
    void idempotencyReuseAcrossOriginContextOrDecisionConflictsWithoutSideEffects() {
        String schema = "sys012_explorer_origin_idempotency";
        PGSimpleDataSource source = migratedDataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcHrisExplorerRepository repository = new JdbcHrisExplorerRepository(jdbc);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        OffsetDateTime now = OffsetDateTime.parse("2026-10-15T04:00:00Z");
        String route = "hris.explorer.team-time";
        String idempotencyKey = "explorer-origin-reuse-0001";

        AuthoritySnapshot originalAuthority = authoritySnapshot(
                ORIGIN_CONTEXT, ORIGIN_DECISION);
        String originalDigest = HrisExplorerService.commandDigest(
                JdbcHrisExplorerRepository.RECENT_ACTION,
                originalAuthority, null, List.of(route));
        CommandContext original = originCommand(
                idempotencyKey, 801, ORIGIN_CONTEXT, ORIGIN_DECISION);
        var receipt = transactions.execute(ignored -> repository.recordRecent(
                original, route, Set.of(route), originalDigest, now));
        assertThat(receipt).isNotNull();

        String changedContext = "psc-" + "f".repeat(64);
        AuthoritySnapshot contextAuthority = authoritySnapshot(
                changedContext, ORIGIN_DECISION);
        String contextDigest = HrisExplorerService.commandDigest(
                JdbcHrisExplorerRepository.RECENT_ACTION,
                contextAuthority, null, List.of(route));
        assertThat(contextDigest).isNotEqualTo(originalDigest);
        assertThatThrownBy(() -> transactions.execute(ignored ->
                repository.recordRecent(
                        originCommand(idempotencyKey, 802,
                                changedContext, ORIGIN_DECISION),
                        route, Set.of(route), contextDigest, now.plusMinutes(1))))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);

        String changedDecision = "psr-" + "9".repeat(64);
        AuthoritySnapshot decisionAuthority = authoritySnapshot(
                ORIGIN_CONTEXT, changedDecision);
        String decisionDigest = HrisExplorerService.commandDigest(
                JdbcHrisExplorerRepository.RECENT_ACTION,
                decisionAuthority, null, List.of(route));
        assertThat(decisionDigest).isNotEqualTo(originalDigest);
        assertThatThrownBy(() -> transactions.execute(ignored ->
                repository.recordRecent(
                        originCommand(idempotencyKey, 803,
                                ORIGIN_CONTEXT, changedDecision),
                        route, Set.of(route), decisionDigest, now.plusMinutes(2))))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);

        long collidingFieldRevision = 7L + (1L << 53);
        AuthoritySnapshot collisionAuthority = authoritySnapshot(
                ORIGIN_CONTEXT, ORIGIN_DECISION, collidingFieldRevision, 11);
        String collisionDigest = HrisExplorerService.commandDigest(
                JdbcHrisExplorerRepository.RECENT_ACTION,
                collisionAuthority, null, List.of(route));
        assertThat(HrisExplorerAuthoritySeals.publicRevision(collidingFieldRevision))
                .isEqualTo(HrisExplorerAuthoritySeals.publicRevision(7));
        assertThat(collisionDigest).isNotEqualTo(originalDigest);
        assertThatThrownBy(() -> transactions.execute(ignored ->
                repository.recordRecent(
                        originCommand(idempotencyKey, 804, ORIGIN_CONTEXT,
                                ORIGIN_DECISION, collidingFieldRevision, 11),
                        route, Set.of(route), collisionDigest, now.plusMinutes(3))))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);

        ReceiptAuthority stored = transactions.execute(ignored ->
                repository.findReceiptAuthority(
                        42, PRINCIPAL_ONE, receipt.receiptId()).orElseThrow());
        assertThat(stored).isNotNull();
        assertThat(stored.originContextKey()).isEqualTo(ORIGIN_CONTEXT);
        assertThat(stored.originDecisionRevision()).isEqualTo(ORIGIN_DECISION);
        ExplorerState state = transactions.execute(ignored ->
                repository.readAndPurge(
                        42, PRINCIPAL_ONE, Set.of(route), now.plusMinutes(3)));
        assertThat(state).isNotNull();
        assertThat(state.recents()).singleElement().satisfies(recent -> {
            assertThat(recent.routeKey()).isEqualTo(route);
            assertThat(recent.lastVisitedAt()).isEqualTo(now);
        });
    }

    @Test
    void activeReceiptLimitIsAppliedAfterCurrentRouteAuthorizationFilter() {
        String schema = "sys012_explorer_receipt_authorization_limit";
        PGSimpleDataSource source = migratedDataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcHrisExplorerRepository repository = new JdbcHrisExplorerRepository(jdbc);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        OffsetDateTime now = OffsetDateTime.parse("2026-10-15T04:00:00Z");
        String admittedRoute = "hris.explorer.team-time";
        UUID admittedReceipt = UUID.fromString(
                "00000000-0000-4000-8000-000000000821");

        transactions.executeWithoutResult(ignored -> {
            jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?, true)",
                    String.class, "42");
            jdbc.queryForObject("SELECT set_config('dwp.principal_public_id', ?, true)",
                    String.class, PRINCIPAL_ONE.toString());
            insertActiveReceipt(jdbc, admittedReceipt, 821, admittedRoute,
                    now.minusMinutes(30));
            for (int index = 0; index < 20; index++) {
                insertActiveReceipt(
                        jdbc,
                        UUID.nameUUIDFromBytes(("filtered-receipt-" + index).getBytes(
                                java.nio.charset.StandardCharsets.UTF_8)),
                        900 + index,
                        "hris.explorer.not-currently-authorized-" + index,
                        now.minusMinutes(20).plusSeconds(index));
            }
        });

        ReceiptActivityProjection projection = transactions.execute(ignored ->
                repository.findActiveReceiptActivities(
                        42, PRINCIPAL_ONE, Set.of(admittedRoute), now));

        assertThat(projection).isNotNull();
        assertThat(projection.rows()).singleElement().satisfies(row -> {
            assertThat(row.receiptId()).isEqualTo(admittedReceipt);
            assertThat(row.resultRef()).isEqualTo("route:" + admittedRoute);
        });
        ReceiptActivityProjection genuinelyEmpty = transactions.execute(ignored ->
                repository.findActiveReceiptActivities(
                        42, PRINCIPAL_ONE, Set.of("hris.explorer.no-match"), now));
        assertThat(genuinelyEmpty).isNotNull();
        assertThat(genuinelyEmpty.rows()).isEmpty();
    }

    @Test
    void repositoryEnforcesFavoriteBoundsCasPurgeAndRecentLru() {
        String schema = "sys012_explorer_repository_bounds";
        PGSimpleDataSource source = migratedDataSource(schema);
        JdbcHrisExplorerRepository repository = new JdbcHrisExplorerRepository(
                new JdbcTemplate(source));
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        OffsetDateTime now = OffsetDateTime.parse("2026-10-15T04:00:00Z");
        List<String> routes = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> "hris.explorer.route-" + index)
                .toList();
        Set<String> allowed = Set.copyOf(routes);

        ExplorerState initial = transactions.execute(ignored -> repository.readAndPurge(
                42, PRINCIPAL_ONE, allowed, now));
        assertThat(initial).isNotNull();
        assertThat(initial.preferenceRevision()).isOne();

        assertThatThrownBy(() -> transactions.execute(ignored ->
                repository.replaceFavorites(
                        command(10, JdbcHrisExplorerRepository.PREFERENCES_ACTION),
                        1, List.of(routes.get(0), routes.get(0)), allowed,
                        digest(10), now)))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThatThrownBy(() -> transactions.execute(ignored ->
                repository.replaceFavorites(
                        command(11, JdbcHrisExplorerRepository.PREFERENCES_ACTION),
                        1, routes, allowed, digest(11), now)))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThatThrownBy(() -> transactions.execute(ignored ->
                repository.replaceFavorites(
                        command(12, JdbcHrisExplorerRepository.PREFERENCES_ACTION),
                        99, List.of(routes.get(0)), allowed, digest(12), now)))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);

        var favoritesReceipt = transactions.execute(ignored ->
                repository.replaceFavorites(
                        command(13, JdbcHrisExplorerRepository.PREFERENCES_ACTION),
                        1, routes.subList(0, 20), allowed, digest(13), now));
        assertThat(favoritesReceipt).isNotNull();
        assertThat(favoritesReceipt.aggregateVersion()).isEqualTo(2);

        for (int index = 0; index < routes.size(); index++) {
            int ordinal = 100 + index;
            String route = routes.get(index);
            OffsetDateTime visitedAt = now.plusSeconds(index);
            transactions.execute(ignored -> repository.recordRecent(
                    command(ordinal, JdbcHrisExplorerRepository.RECENT_ACTION),
                    route, allowed, digest(ordinal), visitedAt));
        }
        ExplorerState bounded = transactions.execute(ignored -> repository.readAndPurge(
                42, PRINCIPAL_ONE, allowed, now.plusMinutes(1)));
        assertThat(bounded).isNotNull();
        assertThat(bounded.favorites()).hasSize(20);
        assertThat(bounded.recents()).hasSize(20)
                .extracting(RecentRow::routeKey)
                .doesNotContain(routes.getFirst())
                .contains(routes.getLast());

        String retained = routes.getLast();
        ExplorerState purgedOnRead = transactions.execute(ignored ->
                repository.readAndPurge(
                        42, PRINCIPAL_ONE, Set.of(retained), now.plusMinutes(2)));
        assertThat(purgedOnRead).isNotNull();
        assertThat(purgedOnRead.favorites()).isEmpty();
        assertThat(purgedOnRead.recents())
                .extracting(RecentRow::routeKey).containsExactly(retained);

        transactions.execute(ignored -> repository.replaceFavorites(
                command(200, JdbcHrisExplorerRepository.PREFERENCES_ACTION),
                2, List.of(routes.get(19), retained),
                Set.of(routes.get(19), retained), digest(200), now.plusMinutes(3)));
        transactions.execute(ignored -> repository.recordRecent(
                command(201, JdbcHrisExplorerRepository.RECENT_ACTION),
                retained, Set.of(retained), digest(201), now.plusMinutes(4)));
        ExplorerState purgedOnWrite = transactions.execute(ignored ->
                repository.readAndPurge(
                        42, PRINCIPAL_ONE, Set.of(retained), now.plusMinutes(5)));
        assertThat(purgedOnWrite).isNotNull();
        assertThat(purgedOnWrite.favorites())
                .extracting(FavoriteRow::routeKey).containsExactly(retained);
        assertThat(purgedOnWrite.favorites())
                .extracting(FavoriteRow::position).containsExactly(1);

        ExplorerState expired = transactions.execute(ignored -> repository.readAndPurge(
                42, PRINCIPAL_ONE, Set.of(retained), now.plusDays(31)));
        assertThat(expired).isNotNull();
        assertThat(expired.recents()).isEmpty();
    }

    @Test
    void concurrentDistinctIdempotencyKeysSerializePrincipalRecentTrim() throws Exception {
        String schema = "sys012_explorer_repository_concurrency";
        PGSimpleDataSource source = migratedDataSource(schema);
        JdbcHrisExplorerRepository repository = new JdbcHrisExplorerRepository(
                new JdbcTemplate(source));
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        OffsetDateTime now = OffsetDateTime.parse("2026-10-15T04:00:00Z");
        List<String> routes = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> "hris.explorer.concurrent-" + index)
                .toList();
        Set<String> allowed = Set.copyOf(routes);
        for (int index = 0; index < 19; index++) {
            int ordinal = 300 + index;
            String route = routes.get(index);
            OffsetDateTime visitedAt = now.plusSeconds(index);
            new TransactionTemplate(manager).execute(ignored -> repository.recordRecent(
                    command(ordinal, JdbcHrisExplorerRepository.RECENT_ACTION),
                    route, allowed, digest(ordinal), visitedAt));
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                concurrentRecent(manager, repository, ready, start,
                        319, routes.get(19), allowed, now.plusSeconds(19));
                return null;
            });
            var second = executor.submit(() -> {
                concurrentRecent(manager, repository, ready, start,
                        320, routes.get(20), allowed, now.plusSeconds(20));
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        ExplorerState state = new TransactionTemplate(manager).execute(ignored ->
                repository.readAndPurge(
                        42, PRINCIPAL_ONE, allowed, now.plusMinutes(1)));
        assertThat(state).isNotNull();
        assertThat(state.recents()).hasSize(20)
                .extracting(RecentRow::routeKey)
                .doesNotContain(routes.getFirst())
                .contains(routes.get(19), routes.get(20));
    }
}
