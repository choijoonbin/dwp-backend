package com.dwp.services.payroll.statutory.pay003;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dwp.services.payroll.statutory.pay003.Pay003TestFixtures.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class Pay003PostgresContractTest {

    private static final String RUNTIME = "pay003_runtime";
    private static final String RUNTIME_PASSWORD = "pay003-runtime-password";
    private static final String PUBLISHER = "pay003_projection_publisher";
    private static final String PUBLISHER_PASSWORD = "pay003-publisher-password";
    private static final String UPGRADE_DATABASE = "pay003_v52_upgrade";
    private static final List<String> TABLES = List.of(
            "pay_country_pack_version_snapshots",
            "pay_country_pack_activation_versions",
            "pay_country_pack_command_receipts",
            "pay_country_pack_audit_outbox_events",
            "pay_social_insurance_result_facts");
    private static final UUID LEGAL_ENTITY =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PAY_GROUP =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR =
            UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID INSTALLER =
            UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID APPROVER =
            UUID.fromString("10000000-0000-0000-0000-000000000005");
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final Instant RECORDED =
            Instant.parse("2026-10-18T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource adminDataSource;
    private static DataSource runtimeDataSource;
    private static DataSource publisherDataSource;
    private static DataSource upgradeDataSource;
    private static JdbcTemplate admin;
    private static JdbcTemplate upgrade;

    @BeforeAll
    static void migrateFreshAndV52Upgrade() throws Exception {
        adminDataSource = dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME
                    + " LOGIN NOINHERIT PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("CREATE ROLE " + PUBLISHER
                    + " LOGIN NOINHERIT PASSWORD '" + PUBLISHER_PASSWORD + "'");
            statement.execute("CREATE DATABASE " + UPGRADE_DATABASE);
        }

        migrate(adminDataSource, null);
        runtimeDataSource = dataSource(
                POSTGRES.getJdbcUrl(), RUNTIME, RUNTIME_PASSWORD);
        publisherDataSource = dataSource(
                POSTGRES.getJdbcUrl(), PUBLISHER, PUBLISHER_PASSWORD);
        admin = new JdbcTemplate(adminDataSource);

        upgradeDataSource = dataSource(
                jdbcUrl(UPGRADE_DATABASE), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        migrate(upgradeDataSource, MigrationVersion.fromVersion("52"));
        upgrade = new JdbcTemplate(upgradeDataSource);
        upgrade.update("""
                INSERT INTO pay_population_source_revisions (
                    tenant_id, source_id, source_revision, payload_digest,
                    legal_entity_id, payroll_group_id, population_kind,
                    named_population_id, item_count, effective_at, recorded_at)
                VALUES (77, ?, 1, ?, ?, ?, 'LEGAL_ENTITY', NULL, 0,
                    TIMESTAMPTZ '2026-10-18 00:00:00Z',
                    TIMESTAMPTZ '2026-10-18 00:00:00Z')
                """, UUID.fromString(
                "10000000-0000-0000-0000-000000000077"), A,
                LEGAL_ENTITY, PAY_GROUP);
        migrate(upgradeDataSource, null);
    }

    @AfterAll
    static void releaseReferences() {
        upgrade = null;
        admin = null;
        runtimeDataSource = null;
        publisherDataSource = null;
        upgradeDataSource = null;
        adminDataSource = null;
    }

    @BeforeEach
    void resetPay003Facts() {
        admin.execute("TRUNCATE TABLE " + String.join(", ", TABLES)
                + " RESTART IDENTITY CASCADE");
    }

    @Test
    void freshV53AndV52ToV53UpgradeCreateExactlyFiveHardenedTables() {
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version = '53' AND success
                """, Long.class)).isEqualTo(1L);
        assertThat(upgrade.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version IN ('52', '53') AND success
                """, Long.class)).isEqualTo(2L);
        assertThat(upgrade.queryForObject("""
                SELECT COUNT(*) FROM pay_population_source_revisions
                 WHERE tenant_id = 77 AND source_revision = 1
                """, Long.class)).isEqualTo(1L);
        assertThat(admin.queryForList("""
                SELECT relname FROM pg_class
                 WHERE relkind = 'r' AND relname IN (%s)
                 ORDER BY relname
                """.formatted(sqlNames(TABLES)), String.class))
                .containsExactlyElementsOf(TABLES.stream().sorted().toList());
        assertThat(admin.queryForObject("""
                SELECT bool_and(relrowsecurity AND relforcerowsecurity)
                  FROM pg_class WHERE relname IN (%s)
                """.formatted(sqlNames(TABLES)), Boolean.class)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pg_policies
                 WHERE schemaname = current_schema()
                   AND tablename IN (%s)
                   AND qual LIKE '%%dwp.payroll_tenant_id%%'
                   AND with_check LIKE '%%dwp.payroll_tenant_id%%'
                """.formatted(sqlNames(TABLES)), Long.class)).isEqualTo(5L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pg_trigger
                 WHERE NOT tgisinternal
                   AND tgname LIKE 'pay_%%_immutable'
                   AND tgrelid::regclass::text IN (%s)
                """.formatted(sqlNames(TABLES)), Long.class)).isEqualTo(5L);
    }

    @Test
    void grantsSplitSourcePublicationFromRuntimeCommandsAndRls() {
        assertThat(privilege(RUNTIME,
                "pay_country_pack_version_snapshots", "INSERT")).isFalse();
        assertThat(privilege(RUNTIME,
                "pay_social_insurance_result_facts", "INSERT")).isFalse();
        assertThat(privilege(RUNTIME,
                "pay_country_pack_activation_versions", "INSERT")).isTrue();
        assertThat(privilege(RUNTIME,
                "pay_country_pack_command_receipts", "INSERT")).isTrue();
        assertThat(privilege(PUBLISHER,
                "pay_country_pack_version_snapshots", "INSERT")).isTrue();
        assertThat(privilege(PUBLISHER,
                "pay_social_insurance_result_facts", "INSERT")).isTrue();
        assertThat(privilege(PUBLISHER,
                "pay_country_pack_command_receipts", "INSERT")).isFalse();
        for (String table : TABLES) {
            assertThat(privilege(RUNTIME, table, "UPDATE")).isFalse();
            assertThat(privilege(PUBLISHER, table, "DELETE")).isFalse();
        }

        assertThatThrownBy(() -> inTransaction(runtimeDataSource, jdbc -> {
            bind(jdbc, 1);
            snapshot(jdbc, UUID.randomUUID(), "GB.NI", "forged", 1,
                    LocalDate.parse("2026-10-01"), RECORDED);
        })).hasRootCauseInstanceOf(PSQLException.class);

        UUID tenantOne = UUID.randomUUID();
        UUID tenantTwo = UUID.randomUUID();
        snapshot(admin, tenantOne, "GB.NI", "one", 1,
                LocalDate.parse("2026-10-01"), RECORDED);
        snapshot(admin, tenantTwo, "GB.NI", "two", 2,
                LocalDate.parse("2026-10-01"), RECORDED);
        inTransaction(runtimeDataSource, jdbc -> {
            bind(jdbc, 1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pay_country_pack_version_snapshots",
                    Long.class)).isEqualTo(1L);
        });
    }

    @Test
    void fullLifecycleCasAndDeferredClosureRejectIllegalAndCrossWiredRows() {
        UUID pack = UUID.randomUUID();
        snapshot(admin, pack, "GB.NI", "v1", 1,
                LocalDate.parse("2026-10-01"), RECORDED);
        lifecycle(admin, pack, 0, 1, "INSTALLED",
                LocalDate.parse("2026-10-01"), null, RECORDED.plusSeconds(1));
        lifecycle(admin, pack, 1, 2, "VALIDATED",
                LocalDate.parse("2026-10-01"), null, RECORDED.plusSeconds(2));
        lifecycle(admin, pack, 2, 3, "APPROVED",
                LocalDate.parse("2026-10-01"), null, RECORDED.plusSeconds(3));
        activate(adminDataSource, pack, 3, 4,
                LocalDate.parse("2026-10-01"), null, "one");
        assertThat(admin.queryForObject("""
                SELECT lifecycle_state FROM pay_country_pack_activation_versions
                 WHERE tenant_id = 1 AND pack_id = ?
                 ORDER BY activation_revision DESC LIMIT 1
                """, String.class, pack)).isEqualTo("ACTIVE");
        JdbcPay003Store store = new JdbcPay003Store(
                new NamedParameterJdbcTemplate(adminDataSource), MAPPER,
                new Pay003Canonical(MAPPER, Pay003TestFixtures.CURSOR_SECRET));
        Pay003Models.StoredReceipt replay = store.receipt(
                1, ACTOR, "pay003-idem-one-00000001").orElseThrow();
        assertThat(replay.receipt().status()).isEqualTo("SUCCEEDED");
        assertThat(replay.receipt().populationScopeDigest()).isEqualTo(A);
        assertThat(replay.receipt().aggregateVersion()).isEqualTo(4);

        UUID illegal = UUID.randomUUID();
        snapshot(admin, illegal, "GB.NI.ILLEGAL", "v1", 1,
                LocalDate.parse("2026-10-01"), RECORDED);
        assertThatThrownBy(() -> lifecycle(
                admin, illegal, 0, 1, "APPROVED",
                LocalDate.parse("2026-10-01"), null,
                RECORDED.plusSeconds(1)))
                .hasRootCauseInstanceOf(PSQLException.class);

        UUID packA = UUID.randomUUID();
        UUID packB = UUID.randomUUID();
        approved(packA, "GB.CROSS.A", "v1",
                LocalDate.parse("2026-10-01"));
        approved(packB, "GB.CROSS.B", "v1",
                LocalDate.parse("2026-10-01"));
        assertThatThrownBy(() -> crossWire(packA, packB))
                .hasRootCauseInstanceOf(PSQLException.class)
                .rootCause()
                .hasMessageContaining("Activation receipt and audit outbox closure");
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_country_pack_activation_versions
                 WHERE tenant_id = 1 AND pack_id IN (?, ?)
                   AND lifecycle_state = 'ACTIVE'
                """, Long.class, packA, packB)).isZero();
    }

    @Test
    void halfOpenOverlapIsSerializedAndRetiredHeadClosesTheRange() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        approved(first, "GB.OVERLAP", "v1",
                LocalDate.parse("2026-10-01"));
        approved(second, "GB.OVERLAP", "v2",
                LocalDate.parse("2026-11-01"));
        approved(third, "GB.OVERLAP", "v3",
                LocalDate.parse("2026-11-01"));
        activate(adminDataSource, first, 3, 4,
                LocalDate.parse("2026-10-01"), null, "first");
        assertThatThrownBy(() -> activate(adminDataSource, second, 3, 4,
                LocalDate.parse("2026-11-01"), null, "blocked"))
                .hasRootCauseInstanceOf(PSQLException.class)
                .rootCause()
                .hasMessageContaining("effective period overlaps");

        lifecycle(admin, first, 4, 5, "RETIRED",
                LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-11-01"), RECORDED.plusSeconds(20));

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (Map.Entry<UUID, String> contender : Map.of(
                    second, "second", third, "third").entrySet()) {
                pool.submit(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        activate(adminDataSource, contender.getKey(), 3, 4,
                                LocalDate.parse("2026-11-01"), null,
                                contender.getValue());
                        succeeded.incrementAndGet();
                    } catch (Exception failure) {
                        conflicted.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(1);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_country_pack_activation_versions
                 WHERE tenant_id = 1 AND pack_id IN (?, ?)
                   AND lifecycle_state = 'ACTIVE'
                """, Long.class, second, third)).isEqualTo(1L);
    }

    @Test
    void publisherTransactionPersistsOnlyExactTypedV50Lineage() {
        UUID pack = UUID.randomUUID();
        approved(pack, "GB.SI", "policy-pack",
                LocalDate.parse("2026-10-01"));
        activate(adminDataSource, pack, 3, 4,
                LocalDate.parse("2026-10-01"), null, "social");
        V50Facts facts = v50Facts();
        seedV50Facts(facts);

        Pay003Canonical canonical = new Pay003Canonical(
                MAPPER, Pay003TestFixtures.CURSOR_SECRET);
        Pay003SocialInsuranceOutcomePublisher publisher =
                new Pay003SocialInsuranceOutcomePublisher(
                        new NamedParameterJdbcTemplate(publisherDataSource),
                        canonical);
        Pay003Models.SocialInsuranceProviderOutcome outcome =
                providerOutcome(pack, facts, List.of(facts.highLine(), facts.lowLine()));
        Pay003Models.PreparedSocialInsuranceFact prepared = publisher.prepare(outcome);
        assertThat(prepared.sourceResultLineIds())
                .containsExactly(facts.lowLine(), facts.highLine());
        publisher.publish(outcome);

        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_social_insurance_result_facts
                 WHERE tenant_id = 1 AND social_insurance_fact_id = ?
                   AND provider_result_type = 'SOCIAL_INSURANCE'
                   AND provider_source_semantics = 'TYPED_COUNTRY_RULE_OUTPUT'
                   AND source_line_set_digest = ?
                   AND provider_outcome_digest = ?
                   AND result_digest = ?
                """, Long.class, prepared.socialInsuranceFactId(),
                prepared.sourceLineSetDigest(), prepared.providerOutcomeDigest(),
                prepared.resultDigest())).isEqualTo(1L);

        assertThatThrownBy(() -> insertFact(
                UUID.randomUUID(), facts, pack, 4, "policy-v1",
                new BigDecimal("9.9999"), "GBP",
                List.of(facts.lowLine(), facts.highLine()),
                "TYPED_COUNTRY_RULE_OUTPUT"))
                .hasRootCauseInstanceOf(PSQLException.class);
        assertThatThrownBy(() -> insertFact(
                UUID.randomUUID(), facts, pack, 4, "policy-v1",
                new BigDecimal("10.0000"), "GBP",
                List.of(facts.lowLine(), facts.highLine()), "DEDUCTION"))
                .hasRootCauseInstanceOf(PSQLException.class);
        assertThatThrownBy(() -> insertFact(
                UUID.randomUUID(), facts, pack, 4, "wrong-policy",
                new BigDecimal("10.0000"), "GBP",
                List.of(facts.lowLine(), facts.highLine()),
                "TYPED_COUNTRY_RULE_OUTPUT"))
                .hasRootCauseInstanceOf(PSQLException.class);
    }

    @Test
    void jdbcReadsHonorAsOfAndSerializedPolicyKeyFilters() {
        UUID pack = UUID.randomUUID();
        snapshot(admin, pack, "GB.NI", "historical", 1,
                LocalDate.parse("2026-10-01"), RECORDED);
        lifecycle(admin, pack, 0, 1, "INSTALLED",
                LocalDate.parse("2026-10-01"), null, RECORDED.plusSeconds(10));
        lifecycle(admin, pack, 1, 2, "VALIDATED",
                LocalDate.parse("2026-10-01"), null, RECORDED.plusSeconds(20));
        lifecycle(admin, pack, 2, 3, "APPROVED",
                LocalDate.parse("2026-10-01"), null, RECORDED.plusSeconds(30));
        Pay003Canonical canonical = new Pay003Canonical(
                MAPPER, Pay003TestFixtures.CURSOR_SECRET);
        JdbcPay003Store store = new JdbcPay003Store(
                new NamedParameterJdbcTemplate(adminDataSource), MAPPER, canonical);
        Pay003Authority.Actor fixture =
                Pay003TestFixtures.actor(Pay003Route.STATUTORY);
        Pay003Authority.Actor actor = new Pay003Authority.Actor(
                fixture.subject(), new Pay003Authority.Scope(
                LEGAL_ENTITY, PAY_GROUP, "scope-11", A));
        Pay003Models.QueryRequest beforeApproval = new Pay003Models.QueryRequest(
                "STATUTORY", RECORDED.plusSeconds(5), null,
                Map.of("filter[statutoryRule][policyKeys]", List.of("gb.ni")),
                25, "en-GB", List.of("itemPublicId:ASC"), "Europe/London",
                UUID.randomUUID());
        Pay003Models.SourcePage<Pay003Models.PackSnapshot> before =
                store.statutory(actor, beforeApproval, RECORDED.plusSeconds(5));
        assertThat(before.availability())
                .isEqualTo(Pay003Models.Availability.AVAILABLE);
        assertThat(before.rows()).singleElement().satisfies(row -> {
            assertThat(row.lifecycleState())
                    .isEqualTo(Pay003Models.LifecycleState.AVAILABLE);
            assertThat(row.currentRevision()).isZero();
        });

        Pay003Models.QueryRequest afterApproval = new Pay003Models.QueryRequest(
                "STATUTORY", RECORDED.plusSeconds(40), null,
                Map.of("filter[statutoryRule][policyKeys]", List.of("gb.ni")),
                25, "en-GB", List.of("itemPublicId:ASC"), "Europe/London",
                UUID.randomUUID());
        assertThat(store.statutory(
                actor, afterApproval, RECORDED.plusSeconds(40)).rows())
                .singleElement().satisfies(row -> {
                    assertThat(row.lifecycleState())
                            .isEqualTo(Pay003Models.LifecycleState.APPROVED);
                    assertThat(row.currentRevision()).isEqualTo(3);
                });
        Pay003Models.QueryRequest excluded = new Pay003Models.QueryRequest(
                "STATUTORY", RECORDED.plusSeconds(40), null,
                Map.of("filter[statutoryRule][policyKeys]", List.of("other")),
                25, "en-GB", List.of("itemPublicId:ASC"), "Europe/London",
                UUID.randomUUID());
        assertThat(store.statutory(actor, excluded,
                RECORDED.plusSeconds(40)).rows()).isEmpty();
    }

    @Test
    void runtimeCommandRollbackReplayAndAllFiveTenantPolicies() {
        UUID pack = UUID.randomUUID();
        approved(pack, "GB.RUNTIME", "v1", LocalDate.parse("2026-10-01"));
        Pay003Canonical canonical = new Pay003Canonical(
                MAPPER, Pay003TestFixtures.CURSOR_SECRET);
        JdbcPay003Store store = new JdbcPay003Store(
                new NamedParameterJdbcTemplate(runtimeDataSource), MAPPER, canonical);
        Pay003Authority.Actor fixture = Pay003TestFixtures.actor(Pay003Route.ACTIVATE);
        Pay003Authority.Actor actor = new Pay003Authority.Actor(
                fixture.subject(), new Pay003Authority.Scope(
                LEGAL_ENTITY, PAY_GROUP, "scope-runtime", A));
        Pay003Models.ActivationCommand command = new Pay003Models.ActivationCommand(
                "UK-REF-2026", LocalDate.parse("2026-10-01"), A,
                admin.queryForObject("""
                        SELECT statutory_approval_receipt_id
                          FROM pay_country_pack_version_snapshots WHERE pack_id = ?
                        """, UUID.class, pack));
        Pay003Models.ActivationHeaders headers = new Pay003Models.ActivationHeaders(
                "pay003-runtime-command-0001", 3, "signed-challenge",
                "decision-41", UUID.randomUUID());
        Pay003Service service = new Pay003Service(
                store, canonical, snapshot -> Pay003Ports.Verification.VERIFIED,
                (current, snapshot, receipt) -> new Pay003Ports.ApprovalEvidence(
                        Pay003Ports.Verification.VERIFIED, receipt, APPROVER,
                        1, LEGAL_ENTITY, PAY_GROUP, "UK-REF-2026", A),
                (snapshot, input) -> Pay003Ports.Verification.VERIFIED,
                (current, variant) -> Map.of(),
                (current, snapshot, preliminary) -> true,
                (current, snapshot, input, transport) -> new Pay003Ports.StepUpEvidence(
                        "challenge-runtime", "nonce-runtime", "test-issuer",
                        Pay003TestFixtures.NOW.plusSeconds(300), B, "decision-41"),
                java.time.Clock.fixed(Pay003TestFixtures.NOW, java.time.ZoneOffset.UTC));
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtimeDataSource));
        assertThatThrownBy(() -> transaction.execute(status -> {
            service.activate(actor, pack, command, headers);
            throw new IllegalStateException("lost before commit");
        })).isInstanceOf(IllegalStateException.class);
        for (String table : TABLES.subList(2, 4)) {
            assertThat(admin.queryForObject("SELECT COUNT(*) FROM " + table,
                    Long.class)).isZero();
        }
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_country_pack_activation_versions
                 WHERE lifecycle_state = 'ACTIVE'
                """, Long.class)).isZero();
        Pay003Models.PayrollCommandReceipt committed = transaction.execute(status ->
                service.activate(actor, pack, command, headers));
        Pay003Models.PayrollCommandReceipt replayed = transaction.execute(status ->
                service.activate(actor, pack, command, new Pay003Models.ActivationHeaders(
                        headers.idempotencyKey(), 3, "signed-challenge",
                        "decision-41", UUID.randomUUID())));
        assertThat(replayed).isEqualTo(committed);
        assertThatThrownBy(() -> transaction.execute(status ->
                service.activate(actor, pack, new Pay003Models.ActivationCommand(
                        "OTHER-REFERENCE", command.effectiveDate(), A,
                        command.statutoryApprovalReceiptId()), headers)))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);
        for (String table : TABLES.subList(2, 4)) {
            assertThat(admin.queryForObject("SELECT COUNT(*) FROM " + table,
                    Long.class)).isEqualTo(1L);
        }
        String auditPayload = admin.queryForObject(
                "SELECT event_payload::TEXT FROM pay_country_pack_audit_outbox_events",
                String.class);
        assertThat(admin.queryForObject(
                "SELECT event_payload::TEXT FROM pay_country_pack_audit_outbox_events",
                String.class)).isEqualTo(auditPayload);
        V50Facts facts = v50Facts();
        seedV50Facts(facts);
        new Pay003SocialInsuranceOutcomePublisher(
                new NamedParameterJdbcTemplate(publisherDataSource), canonical)
                .publish(providerOutcome(pack, facts,
                        List.of(facts.lowLine(), facts.highLine())));
        snapshot(admin, UUID.randomUUID(), "GB.OTHER", "v1", 2,
                LocalDate.parse("2026-10-01"), RECORDED);
        inTransaction(runtimeDataSource, jdbc -> {
            jdbc.queryForObject("SELECT set_config('dwp.payroll_tenant_id', '', true)",
                    String.class);
            for (String table : TABLES) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table,
                        Long.class)).isZero();
            }
            bind(jdbc, 2);
            for (String table : TABLES) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                        + " WHERE tenant_id = 1", Long.class)).isZero();
            }
            bind(jdbc, 1);
            for (String table : TABLES) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table,
                        Long.class)).isPositive();
            }
        });
        for (String table : TABLES) {
            assertThatThrownBy(() -> admin.update("UPDATE " + table
                    + " SET tenant_id = tenant_id WHERE tenant_id = 1"))
                    .hasRootCauseInstanceOf(PSQLException.class);
            assertThatThrownBy(() -> admin.update("DELETE FROM " + table
                    + " WHERE tenant_id = 1"))
                    .hasRootCauseInstanceOf(PSQLException.class);
        }
    }

    @Test
    void activationReceiptActorCrossWireIsRejectedAtCommit() {
        UUID pack = UUID.randomUUID();
        approved(pack, "GB.ACTOR.CROSS", "v1", LocalDate.parse("2026-10-01"));
        assertThatThrownBy(() -> inTransaction(adminDataSource, jdbc -> {
            UUID receipt = UUID.randomUUID();
            UUID event = UUID.randomUUID();
            UUID correlation = UUID.randomUUID();
            activeRow(jdbc, pack, receipt, event, correlation);
            receipt(jdbc, receipt, pack, 3, 4, correlation,
                    "pay003-actor-cross-00000001", UUID.randomUUID());
            outbox(jdbc, event, receipt, pack, 4, correlation);
        })).hasRootCauseInstanceOf(PSQLException.class).rootCause()
                .hasMessageContaining("Activation receipt and audit outbox closure");
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_country_pack_activation_versions
                 WHERE pack_id = ? AND lifecycle_state = 'ACTIVE'
                """, Long.class, pack)).isZero();
        for (String table : TABLES.subList(2, 4)) {
            assertThat(admin.queryForObject("SELECT COUNT(*) FROM " + table,
                    Long.class)).isZero();
        }
    }

    @Test
    void identicalInstallerAndStatutoryApproverIsRejectedByPostgres() {
        UUID pack = UUID.randomUUID();
        assertThatThrownBy(() -> snapshot(admin, pack, "GB.SOD", "v1", 1,
                LocalDate.parse("2026-10-01"), RECORDED, INSTALLER))
                .hasRootCauseInstanceOf(PSQLException.class).rootCause()
                .hasMessageContaining("pay_country_pack_snapshot_sod");
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_country_pack_version_snapshots
                 WHERE pack_id = ?
                """, Long.class, pack)).isZero();
    }

    private static void migrate(DataSource source, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "payrollRuntimeRole", RUNTIME,
                        "payrollProjectionPublisherRole", PUBLISHER));
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    private boolean privilege(String role, String table, String permission) {
        return Boolean.TRUE.equals(admin.queryForObject(
                "SELECT has_table_privilege(?, ?, ?)",
                Boolean.class, role, table, permission));
    }

    private void approved(
            UUID pack, String packKey, String version, LocalDate effectiveFrom) {
        snapshot(admin, pack, packKey, version, 1, effectiveFrom, RECORDED);
        lifecycle(admin, pack, 0, 1, "INSTALLED", effectiveFrom, null,
                RECORDED.plusSeconds(1));
        lifecycle(admin, pack, 1, 2, "VALIDATED", effectiveFrom, null,
                RECORDED.plusSeconds(2));
        lifecycle(admin, pack, 2, 3, "APPROVED", effectiveFrom, null,
                RECORDED.plusSeconds(3));
    }

    private static void snapshot(
            JdbcTemplate jdbc,
            UUID pack,
            String packKey,
            String version,
            long tenant,
            LocalDate effectiveFrom,
            Instant recordedAt) {
        snapshot(jdbc, pack, packKey, version, tenant, effectiveFrom,
                recordedAt, APPROVER);
    }

    private static void snapshot(
            JdbcTemplate jdbc,
            UUID pack,
            String packKey,
            String version,
            long tenant,
            LocalDate effectiveFrom,
            Instant recordedAt,
            UUID approver) {
        jdbc.update("""
                INSERT INTO pay_country_pack_version_snapshots (
                    tenant_id, pack_id, pack_key, pack_version,
                    snapshot_revision, legal_entity_id, payroll_group_id,
                    jurisdiction_code, lifecycle_state, payload_digest,
                    artifact_digest, signature_digest, signature_algorithm,
                    signing_key_id, compatibility_version,
                    policy_schema_version, official_reference_set,
                    golden_pack_digest, statutory_approval_receipt_id,
                    installer_subject_public_id,
                    statutory_approver_subject_public_id,
                    effective_from, effective_to, recorded_at)
                VALUES (?, ?, ?, ?, 0, ?, ?, 'GB', 'AVAILABLE', ?, ?, ?,
                    'ED25519', 'key-1', '1', 'policy-v1', 'UK-REF-2026', ?,
                    ?, ?, ?, ?, NULL, ?)
                """, tenant, pack, packKey, version, LEGAL_ENTITY, PAY_GROUP,
                A, B, C, A, UUID.randomUUID(), INSTALLER, approver,
                Date.valueOf(effectiveFrom), Timestamp.from(recordedAt));
    }

    private static void lifecycle(
            JdbcTemplate jdbc,
            UUID pack,
            long previous,
            long revision,
            String state,
            LocalDate from,
            LocalDate to,
            Instant occurredAt) {
        jdbc.update("""
                INSERT INTO pay_country_pack_activation_versions (
                    tenant_id, pack_id, previous_revision,
                    activation_revision, lifecycle_state,
                    effective_from, effective_to,
                    activated_by_subject_public_id, receipt_id,
                    audit_event_id, correlation_id, occurred_at)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?)
                """, pack, previous, revision, state, Date.valueOf(from),
                to == null ? null : Date.valueOf(to), ACTOR,
                Timestamp.from(occurredAt));
    }

    private static void activate(
            DataSource source,
            UUID pack,
            long previous,
            long revision,
            LocalDate from,
            LocalDate to,
            String keySuffix) {
        inTransaction(source, jdbc -> {
            UUID receipt = UUID.randomUUID();
            UUID event = UUID.randomUUID();
            UUID correlation = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO pay_country_pack_activation_versions (
                        tenant_id, pack_id, previous_revision,
                        activation_revision, lifecycle_state,
                        effective_from, effective_to,
                        activated_by_subject_public_id, receipt_id,
                        audit_event_id, correlation_id, occurred_at)
                    VALUES (1, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?)
                    """, pack, previous, revision, Date.valueOf(from),
                    to == null ? null : Date.valueOf(to), ACTOR, receipt, event,
                    correlation, Timestamp.from(RECORDED.plusSeconds(10 + revision)));
            receipt(jdbc, receipt, pack, previous, revision, correlation,
                    "pay003-idem-" + keySuffix + "-00000001");
            outbox(jdbc, event, receipt, pack, revision, correlation);
        });
    }

    private static void receipt(
            JdbcTemplate jdbc,
            UUID receipt,
            UUID pack,
            long expected,
            long revision,
            UUID correlation,
            String idempotency) {
        receipt(jdbc, receipt, pack, expected, revision, correlation,
                idempotency, ACTOR);
    }

    private static void receipt(
            JdbcTemplate jdbc,
            UUID receipt,
            UUID pack,
            long expected,
            long revision,
            UUID correlation,
            String idempotency,
            UUID subject) {
        jdbc.update("""
                INSERT INTO pay_country_pack_command_receipts (
                    tenant_id, receipt_id, subject_principal_public_id,
                    originating_action, idempotency_key, request_digest,
                    pack_id, expected_revision, activation_revision,
                    legal_entity_id, payroll_group_id,
                    population_scope_digest, purpose_code,
                    field_policy_revision, authorization_revision,
                    receipt_status, correlation_id, result_ref,
                    step_up_challenge_id, step_up_nonce, step_up_issuer,
                    step_up_expires_at, step_up_payload_sha256,
                    step_up_decision_revision, audit_payload, created_at)
                VALUES (1, ?, ?, 'PAY_COUNTRY_PACK_ACTIVATE', ?, ?, ?, ?, ?,
                    ?, ?, ?, 'STATUTORY_CONFIGURATION', 17, 23, 'SUCCEEDED', ?,
                    'country-pack:' || ?::TEXT, 'challenge-1', 'nonce-1',
                    'issuer-1', ?, ?, 'decision-41', '{}'::JSONB, ?)
                """, receipt, subject, idempotency, A, pack, expected, revision,
                LEGAL_ENTITY, PAY_GROUP, A, correlation, pack,
                Timestamp.from(RECORDED.plusSeconds(3600)), B,
                Timestamp.from(RECORDED.plusSeconds(10 + revision)));
    }

    private static void outbox(
            JdbcTemplate jdbc,
            UUID event,
            UUID receipt,
            UUID pack,
            long revision,
            UUID correlation) {
        String payload = """
                {"specVersion":"1.0","id":"%s","source":"test",
                 "type":"CountryPackActivated","schemaVersion":1,
                 "time":"2026-10-18T00:00:14Z","tenantId":1,
                 "aggregateType":"COUNTRY_PACK","aggregateId":"%s",
                 "aggregateSequence":%d,"correlationId":"%s","data":{}}
                """.formatted(event, pack, revision, correlation);
        jdbc.update("""
                INSERT INTO pay_country_pack_audit_outbox_events (
                    tenant_id, event_id, receipt_id, pack_id,
                    activation_revision, event_type, schema_version,
                    aggregate_sequence, correlation_id,
                    event_payload, occurred_at)
                VALUES (1, ?, ?, ?, ?, 'CountryPackActivated', 1, ?, ?,
                    CAST(? AS JSONB), ?)
                """, event, receipt, pack, revision, revision, correlation,
                payload, Timestamp.from(RECORDED.plusSeconds(10 + revision)));
    }

    private void crossWire(UUID packA, UUID packB) {
        inTransaction(adminDataSource, jdbc -> {
            UUID receiptA = UUID.randomUUID();
            UUID receiptB = UUID.randomUUID();
            UUID eventA = UUID.randomUUID();
            UUID eventB = UUID.randomUUID();
            UUID correlationA = UUID.randomUUID();
            UUID correlationB = UUID.randomUUID();
            activeRow(jdbc, packA, receiptA, eventA, correlationA);
            activeRow(jdbc, packB, receiptB, eventB, correlationB);
            receipt(jdbc, receiptA, packB, 3, 4, correlationB,
                    "pay003-cross-a-00000001");
            receipt(jdbc, receiptB, packA, 3, 4, correlationA,
                    "pay003-cross-b-00000001");
            outbox(jdbc, eventA, receiptA, packB, 4, correlationB);
            outbox(jdbc, eventB, receiptB, packA, 4, correlationA);
        });
    }

    private void activeRow(
            JdbcTemplate jdbc,
            UUID pack,
            UUID receipt,
            UUID event,
            UUID correlation) {
        jdbc.update("""
                INSERT INTO pay_country_pack_activation_versions (
                    tenant_id, pack_id, previous_revision,
                    activation_revision, lifecycle_state,
                    effective_from, effective_to,
                    activated_by_subject_public_id, receipt_id,
                    audit_event_id, correlation_id, occurred_at)
                VALUES (1, ?, 3, 4, 'ACTIVE', DATE '2026-10-01', NULL,
                    ?, ?, ?, ?, TIMESTAMPTZ '2026-10-18 00:00:14Z')
                """, pack, ACTOR, receipt, event, correlation);
    }

    private V50Facts v50Facts() {
        // Fresh public IDs retain opposite sign bits to exercise canonical UUID ordering.
        UUID source = UUID.randomUUID();
        UUID low = new UUID(source.getMostSignificantBits() & Long.MAX_VALUE,
                source.getLeastSignificantBits());
        UUID high = new UUID(source.getMostSignificantBits() | Long.MIN_VALUE,
                source.getLeastSignificantBits());
        return new V50Facts(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                low, high);
    }

    private void seedV50Facts(V50Facts facts) {
        inTransaction(adminDataSource, jdbc -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    INSERT INTO pay_worker_results (
                        tenant_id, run_id, attempt_no, worker_result_id,
                        worker_id, currency, gross_amount, deduction_amount,
                        net_amount, employer_cost_amount, line_count,
                        worker_result_digest, calculation_result_digest,
                        recorded_at)
                    VALUES (1, ?, 1, ?, ?, 'GBP', 100.0000, 10.0000,
                        90.0000, 20.0000, 2, ?, ?, ?)
                    """, facts.run(), facts.workerResult(), facts.worker(),
                    A, B, Timestamp.from(RECORDED));
            resultLine(jdbc, facts, facts.lowLine(), 101, new BigDecimal("4.2500"));
            resultLine(jdbc, facts, facts.highLine(), 102, new BigDecimal("5.7500"));
        });
    }

    private void resultLine(
            JdbcTemplate jdbc,
            V50Facts facts,
            UUID line,
            long source,
            BigDecimal amount) {
        jdbc.update("""
                INSERT INTO pay_result_lines (
                    tenant_id, run_id, attempt_no, input_snapshot_internal_id,
                    result_line_id, compensation_plan_input_id, worker_id,
                    worker_result_id, component_code, source_payload_digest,
                    semantic_binding_digest, element_id, element_version,
                    formula_id, formula_version, formula_ast_digest,
                    line_category, formula_input_digest, formula_output_digest,
                    currency, amount, terminal_trace_id, trace_digest,
                    result_line_digest, recorded_at)
                VALUES (1, ?, 1, 999, ?, ?, ?, ?, 'SOCIAL_INSURANCE_SOURCE',
                    ?, ?, ?, 1, ?, 1, ?, 'DEDUCTION', ?, ?, 'GBP', ?, ?, ?, ?, ?)
                """, facts.run(), line, source, facts.worker(),
                facts.workerResult(), A, B, UUID.randomUUID(), UUID.randomUUID(),
                C, A, B, amount, UUID.randomUUID(), C, A,
                Timestamp.from(RECORDED));
    }

    private Pay003Models.SocialInsuranceProviderOutcome providerOutcome(
            UUID pack, V50Facts facts, List<UUID> orderedLines) {
        Map<UUID, BigDecimal> amounts = Map.of(
                facts.lowLine(), new BigDecimal("4.2500"),
                facts.highLine(), new BigDecimal("5.7500"));
        return new Pay003Models.SocialInsuranceProviderOutcome(
                1, facts.run(), 1, facts.workerResult(), pack, 4, "policy-v1",
                new Pay003Models.PostedMoney(
                        new BigDecimal("10.0000"), "GBP"),
                orderedLines.stream().map(line ->
                        new Pay003Models.ProviderSourceLine(
                                line, new Pay003Models.PostedMoney(
                                amounts.get(line), "GBP"),
                                "TYPED_COUNTRY_RULE_OUTPUT")).toList(),
                C, RECORDED.plusSeconds(100));
    }

    private void insertFact(
            UUID fact,
            V50Facts facts,
            UUID pack,
            long activationRevision,
            String policy,
            BigDecimal amount,
            String currency,
            List<UUID> lines,
            String semantics) {
        String array = lines.stream().map(value -> "'" + value + "'::UUID")
                .reduce((left, right) -> left + "," + right).orElseThrow();
        admin.update("""
                INSERT INTO pay_social_insurance_result_facts (
                    tenant_id, social_insurance_fact_id, run_id, attempt_no,
                    worker_result_id, pack_id, activation_revision,
                    policy_version, provider_result_type,
                    provider_source_semantics, provider_outcome_digest,
                    amount, currency, source_result_line_ids,
                    source_line_set_digest, result_digest, recorded_at)
                VALUES (1, ?, ?, 1, ?, ?, ?, ?, 'SOCIAL_INSURANCE', ?, ?,
                    ?, ?, ARRAY[%s], ?, ?, ?)
                """.formatted(array), fact, facts.run(), facts.workerResult(),
                pack, activationRevision, policy, semantics, A, amount, currency,
                B, C, Timestamp.from(RECORDED.plusSeconds(100)));
    }

    private static void bind(JdbcTemplate jdbc, long tenant) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', ?, true)",
                String.class, Long.toString(tenant));
    }

    private static void inTransaction(
            DataSource source, java.util.function.Consumer<JdbcTemplate> work) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(ignored ->
                work.accept(new JdbcTemplate(source)));
    }

    private static String sqlNames(List<String> names) {
        return names.stream().map(name -> "'" + name + "'")
                .reduce((left, right) -> left + "," + right).orElseThrow();
    }

    private static DataSource dataSource(
            String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url, username, password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    private static String jdbcUrl(String database) {
        String url = POSTGRES.getJdbcUrl();
        int query = url.indexOf('?');
        String suffix = query < 0 ? "" : url.substring(query);
        String base = query < 0 ? url : url.substring(0, query);
        return base.substring(0, base.lastIndexOf('/') + 1) + database + suffix;
    }

    private record V50Facts(
            UUID run,
            UUID workerResult,
            UUID worker,
            UUID lowLine,
            UUID highLine) {
    }
}
