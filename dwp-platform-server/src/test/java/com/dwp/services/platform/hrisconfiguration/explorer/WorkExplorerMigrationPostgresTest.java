package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.AuthoritySnapshot;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
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
class WorkExplorerMigrationPostgresTest {

    private static final Set<String> TABLES = Set.of(
            "sys_hris_explorer_preferences",
            "sys_hris_explorer_favorites",
            "sys_hris_explorer_recents",
            "sys_hris_command_receipts");
    private static final UUID PRINCIPAL_ONE =
            UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID PRINCIPAL_TWO =
            UUID.fromString("00000000-0000-4000-8000-000000000202");
    private static final String POPULATION_DIGEST = "a".repeat(64);
    private static final String REQUEST_DIGEST = "b".repeat(64);
    private static final String ORIGIN_CONTEXT = "psc-" + "c".repeat(64);
    private static final String ORIGIN_SCOPE = "hcm-scope-" + "d".repeat(40);
    private static final String AUTHORITY_POPULATION_DIGEST =
            HrisExplorerAuthoritySeals.populationScopeDigest(ORIGIN_SCOPE);
    private static final String ORIGIN_DECISION = "psr-" + "e".repeat(64);
    private static final long AUTHORITY_FIELD_REVISION = 4_663_670_355_146_917_040L;
    private static final long PUBLIC_FIELD_REVISION = 6_948_340_445_824_176L;
    private static final long AUTHORITY_AUTHORIZATION_REVISION = 1_991_387_878_219_104_048L;
    private static final long PUBLIC_AUTHORIZATION_REVISION = 796_842_921_344_816L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

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

    private void concurrentRecent(
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

    private CommandContext command(int ordinal, String action) {
        return new CommandContext(
                42, PRINCIPAL_ONE, POPULATION_DIGEST, AUTHORITY_POPULATION_DIGEST, 7, 11,
                originRoute(action), ORIGIN_CONTEXT, ORIGIN_SCOPE, ORIGIN_DECISION,
                UUID.nameUUIDFromBytes(("correlation-" + ordinal).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)),
                "explorer-idempotency-" + ordinal, action);
    }

    private CommandContext originCommand(
            String idempotencyKey, int ordinal,
            String contextKey, String decisionRevision) {
        return originCommand(idempotencyKey, ordinal, contextKey,
                decisionRevision, 7, 11);
    }

    private CommandContext originCommand(
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

    private AuthoritySnapshot authoritySnapshot(
            String contextKey, String decisionRevision) {
        return authoritySnapshot(contextKey, decisionRevision, 7, 11);
    }

    private AuthoritySnapshot authoritySnapshot(
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

    private void insertActiveReceipt(
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

    private String originRoute(String action) {
        return JdbcHrisExplorerRepository.PREFERENCES_ACTION.equals(action)
                ? "route.hcm.utility.explorer-preferences-update.action"
                : "route.hcm.utility.explorer-recent-create.action";
    }

    private String digest(int ordinal) {
        return "%064x".formatted(ordinal);
    }

    private PGSimpleDataSource migratedDataSource(String schema) {
        PGSimpleDataSource source = dataSource();
        Flyway migration = flyway(source, schema, "327").load();
        migration.clean();
        migration.migrate();
        source.setCurrentSchema(schema);
        return source;
    }

    private void assertV327Schema(JdbcTemplate jdbc, String schema) {
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

    private void assertTenantAndPrincipalIsolation(JdbcTemplate jdbc, String schema) {
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

    private void assertAllFourTablesEnforceTenantAndPrincipalRls(
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

    private Map<String, Integer> visibleCounts(
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

    private void assertBoundedFavoritesRecentsAndReceiptSeal(
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

    private void insertPreference(
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

    private void insertReceipt(
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

    private void insertReceiptWithRevisions(
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

    private Map<String, Integer> migrationChecksums(JdbcTemplate jdbc, String schema) {
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

    private Set<String> tables(JdbcTemplate jdbc, String schema) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = ?
                   AND table_type = 'BASE TABLE'
                 ORDER BY table_name
                """, String.class, schema));
    }

    private FluentConfiguration flyway(
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

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
