package com.dwp.services.platform.hrisconfiguration.operations;

import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.operations.HrisOperationsModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class HrisOperationalExceptionMigrationPostgresTest {

    private static final Set<String> COLUMNS = Set.of(
            "tenant_id", "exception_public_id", "event_source",
            "owner_service", "owner_module", "source_ref",
            "source_revision", "exception_type", "severity",
            "internal_state", "code", "summary_key", "owner_route_key",
            "evidence_refs", "correlation_id", "event_digest",
            "first_observed_at", "last_observed_at", "occurrence_count",
            "version");
    private static final UUID EXCEPTION_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000020");
    private static final UUID CORRELATION_ID =
            UUID.fromString("30000000-0000-4000-8000-000000000030");
    private static final String SOURCE =
            "urn:dwp:people:hrm:operational-exception";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void cleanMigrationThroughV331CreatesExactProjectionRlsAndRuntimeAcl() {
        String schema = "sys002_v331_clean";
        PGSimpleDataSource source = dataSource();
        Flyway flyway = flyway(source, schema, "331").load();
        flyway.clean();
        flyway.migrate();
        source.setCurrentSchema(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);

        assertV331Schema(jdbc, schema);
        assertThat(tables(jdbc, schema)).contains(
                "sys_domain_event_inbox", "sys_domain_event_offsets",
                "sys_domain_event_outbox",
                "hris_operational_exception_projection");
        String qualified = schema + ".hris_operational_exception_projection";
        for (String privilege : List.of("SELECT", "INSERT", "UPDATE")) {
            assertThat(jdbc.queryForObject(
                    "SELECT has_table_privilege(current_user, ?, ?)",
                    Boolean.class, qualified, privilege)).isTrue();
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  CROSS JOIN LATERAL aclexplode(
                      COALESCE(c.relacl, acldefault('r', c.relowner))) acl
                 WHERE n.nspname = ?
                   AND c.relname = 'hris_operational_exception_projection'
                   AND acl.grantee = 0
                   AND acl.privilege_type IN
                       ('SELECT', 'INSERT', 'UPDATE', 'DELETE')
                """, Integer.class, schema)).isZero();

        assertDatabaseGuardsAndRls(jdbc, source, schema);
    }

    @Test
    void v330ToV331IsForwardOnlyAndAddsExactlyTheProjectionTable() {
        String schema = "sys002_v330_upgrade";
        PGSimpleDataSource source = dataSource();
        Flyway before = flyway(source, schema, "330").load();
        before.clean();
        before.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Map<String, Integer> checksumsBefore = migrationChecksums(jdbc, schema);
        Set<String> tablesBefore = tables(jdbc, schema);

        assertThat(checksumsBefore).containsKey("330");
        assertThat(tablesBefore)
                .doesNotContain("hris_operational_exception_projection");

        flyway(source, schema, "331").load().migrate();

        Map<String, Integer> checksumsAfter = migrationChecksums(jdbc, schema);
        assertThat(checksumsAfter).containsAllEntriesOf(checksumsBefore)
                .containsKey("331");
        Set<String> added = new LinkedHashSet<>(tables(jdbc, schema));
        added.removeAll(tablesBefore);
        assertThat(added).containsExactly(
                "hris_operational_exception_projection");
        assertV331Schema(jdbc, schema);
    }

    @Test
    void postgresAdapterIsIdempotentContiguousAndGapBounded() {
        String schema = "sys002_v331_repository";
        PGSimpleDataSource source = dataSource();
        Flyway flyway = flyway(source, schema, "331").load();
        flyway.clean();
        flyway.migrate();
        source.setCurrentSchema(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcHrisOperationalExceptionRepository repository =
                new JdbcHrisOperationalExceptionRepository(
                        new NamedParameterJdbcTemplate(source),
                        new DataSourceTransactionManager(source));
        HrisOperationalExceptionEventContract contract =
                new HrisOperationalExceptionEventContract(
                        new ObjectMapper().findAndRegisterModules(),
                        (sourceName, code, summary) ->
                                "HRM_ASSIGNMENT_STALLED".equals(code)
                                        && ("hris.operations.exception.hrm."
                                        + "assignment-stalled").equals(summary));

        ValidatedEvent first = event(contract, 1,
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                InternalState.OPEN, "2026-10-17T07:00:00Z");
        ValidatedEvent second = event(contract, 2,
                UUID.fromString("10000000-0000-4000-8000-000000000002"),
                InternalState.RECOVERING, "2026-10-17T07:01:00Z");

        assertThat(repository.apply(first).state()).isEqualTo(IngressState.APPLIED);
        assertThat(repository.apply(first).state())
                .isEqualTo(IngressState.DUPLICATE);
        assertThat(repository.apply(second).state()).isEqualTo(IngressState.APPLIED);

        ValidatedEvent conflictingSecond = event(contract, 2,
                UUID.fromString("10000000-0000-4000-8000-000000000012"),
                InternalState.RESOLVED, "2026-10-17T07:02:00Z");
        assertThat(repository.apply(conflictingSecond).state())
                .isEqualTo(IngressState.QUARANTINED);

        ValidatedEvent gap = event(contract, 4,
                UUID.fromString("10000000-0000-4000-8000-000000000004"),
                InternalState.RESOLVED, "2026-10-17T07:04:00Z");
        for (int attempt = 1; attempt <= 4; attempt++) {
            IngressResult result = repository.apply(gap);
            assertThat(result.state()).isEqualTo(IngressState.DEFERRED);
            assertThat(result.attempt()).isEqualTo(attempt);
        }
        IngressResult exhausted = repository.apply(gap);
        assertThat(exhausted.state())
                .isEqualTo(IngressState.DEAD_LETTER_REQUIRED);
        assertThat(exhausted.attempt()).isEqualTo(5);

        assertThat(jdbc.queryForMap("""
                SELECT source_revision, internal_state, occurrence_count, version
                  FROM hris_operational_exception_projection
                """)).containsEntry("source_revision", 2L)
                .containsEntry("internal_state", "RECOVERING")
                .containsEntry("occurrence_count", 2L)
                .containsEntry("version", 2L);
        assertThat(jdbc.queryForObject("""
                SELECT last_sequence FROM sys_domain_event_offsets
                 WHERE consumer_name = ? AND aggregate_id = ?
                """, Long.class, HrisOperationalExceptionEventContract.CONSUMER,
                EXCEPTION_ID.toString())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM sys_domain_event_inbox
                 WHERE consumer_name = ? AND event_id = ?
                """, Integer.class,
                HrisOperationalExceptionEventContract.CONSUMER,
                gap.envelope().id())).isEqualTo(5);

        OffsetDateTime asOf = OffsetDateTime.parse("2026-10-17T08:00:00Z");
        TransactionTemplate reads = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        ProjectionPage page = reads.execute(ignored -> repository.list(
                42, Set.of(OwnerModule.HRM), null, null, PublicState.OPEN,
                asOf, null, 100));
        assertThat(page).isNotNull();
        assertThat(page.items()).singleElement().satisfies(value -> {
            assertThat(value.sourceRevision()).isEqualTo(2);
            assertThat(value.internalState()).isEqualTo(InternalState.RECOVERING);
        });
        ProjectionPage foreign = reads.execute(ignored -> repository.list(
                43, Set.of(OwnerModule.HRM), null, null, null,
                asOf, null, 100));
        assertThat(foreign).isNotNull();
        assertThat(foreign.items()).isEmpty();
    }

    private void assertDatabaseGuardsAndRls(
            JdbcTemplate jdbc, PGSimpleDataSource source, String schema) {
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        transactions.executeWithoutResult(ignored -> {
            tenant(jdbc, 42);
            assertThat(jdbc.update(insertSql(),
                    EXCEPTION_ID,
                    "urn:dwp:hris:hrm:assignment:" + EXCEPTION_ID,
                    CORRELATION_ID)).isEqualTo(1);
        });
        transactions.executeWithoutResult(ignored -> {
            tenant(jdbc, 42);
            assertThat(jdbc.update("""
                    UPDATE hris_operational_exception_projection
                       SET source_revision = 2,
                           internal_state = 'ACKNOWLEDGED',
                           event_digest = repeat('b', 64),
                           last_observed_at = last_observed_at + INTERVAL '1 second',
                           occurrence_count = 2,
                           version = 2
                     WHERE tenant_id = 42 AND exception_public_id = ?
                    """, EXCEPTION_ID)).isEqualTo(1);
        });
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            tenant(jdbc, 42);
            jdbc.update("""
                    UPDATE hris_operational_exception_projection
                       SET source_revision = 4, occurrence_count = 3, version = 3
                     WHERE tenant_id = 42 AND exception_public_id = ?
                    """, EXCEPTION_ID);
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            tenant(jdbc, 42);
            jdbc.update("""
                    INSERT INTO hris_operational_exception_projection (
                        tenant_id, exception_public_id, event_source, owner_service,
                        owner_module, source_ref, source_revision, exception_type,
                        severity, internal_state, code, summary_key, owner_route_key,
                        evidence_refs, correlation_id, event_digest,
                        first_observed_at, last_observed_at, occurrence_count, version)
                    VALUES (42, ?, 'urn:dwp:people:hrm:operational-exception',
                        'dwp-people-server', 'HRM', ?, 1, 'ASSIGNMENT_PROCESSING',
                        'HIGH', 'OPEN', 'HRM_ASSIGNMENT_STALLED',
                        'hris.operations.exception.hrm.assignment-stalled',
                        'route.hcm.operations.employment-assignments.page',
                        ARRAY[NULL]::text[], ?, repeat('c', 64),
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 1)
                    """, UUID.fromString(
                    "20000000-0000-4000-8000-000000000021"),
                    "urn:dwp:hris:hrm:assignment:"
                            + "20000000-0000-4000-8000-000000000021",
                    CORRELATION_ID);
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            tenant(jdbc, 42);
            jdbc.update("""
                    DELETE FROM hris_operational_exception_projection
                     WHERE tenant_id = 42 AND exception_public_id = ?
                    """, EXCEPTION_ID);
        })).isInstanceOf(DataIntegrityViolationException.class);

        String role = "sys002_reader_"
                + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
        jdbc.execute("GRANT SELECT, INSERT, UPDATE ON "
                + "hris_operational_exception_projection TO " + role);
        String qualified = schema + ".hris_operational_exception_projection";
        for (String privilege : List.of("SELECT", "INSERT", "UPDATE")) {
            assertThat(jdbc.queryForObject(
                    "SELECT has_table_privilege(?, ?, ?)",
                    Boolean.class, role, qualified, privilege)).isTrue();
        }
        Integer hidden = transactions.execute(ignored -> {
            jdbc.execute("SET LOCAL ROLE " + role);
            tenant(jdbc, 43);
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM hris_operational_exception_projection",
                    Integer.class);
        });
        Integer visible = transactions.execute(ignored -> {
            jdbc.execute("SET LOCAL ROLE " + role);
            tenant(jdbc, 42);
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM hris_operational_exception_projection",
                    Integer.class);
        });
        assertThat(hidden).isZero();
        assertThat(visible).isEqualTo(1);

        Integer crossTenantUpdated = transactions.execute(ignored -> {
            jdbc.execute("SET LOCAL ROLE " + role);
            tenant(jdbc, 43);
            return jdbc.update("""
                    UPDATE hris_operational_exception_projection
                       SET severity = 'CRITICAL'
                     WHERE tenant_id = 42 AND exception_public_id = ?
                    """, EXCEPTION_ID);
        });
        assertThat(crossTenantUpdated).isZero();

        UUID crossTenantInsert = UUID.fromString(
                "20000000-0000-4000-8000-000000000022");
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            jdbc.execute("SET LOCAL ROLE " + role);
            tenant(jdbc, 43);
            jdbc.update(insertSql(), crossTenantInsert,
                    "urn:dwp:hris:hrm:assignment:" + crossTenantInsert,
                    CORRELATION_ID);
        })).isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .hasRootCauseMessage("ERROR: new row violates row-level security "
                        + "policy for table \"hris_operational_exception_projection\"");

        transactions.executeWithoutResult(ignored -> {
            tenant(jdbc, 42);
            assertThat(jdbc.queryForMap("""
                    SELECT source_revision, severity, internal_state
                      FROM hris_operational_exception_projection
                     WHERE tenant_id = 42 AND exception_public_id = ?
                    """, EXCEPTION_ID))
                    .containsEntry("source_revision", 2L)
                    .containsEntry("severity", "HIGH")
                    .containsEntry("internal_state", "ACKNOWLEDGED");
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM hris_operational_exception_projection
                     WHERE tenant_id = 42 AND exception_public_id = ?
                    """, Integer.class, crossTenantInsert)).isZero();
        });
    }

    private void assertV331Schema(JdbcTemplate jdbc, String schema) {
        assertThat(jdbc.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = ?
                   AND table_name = 'hris_operational_exception_projection'
                """, String.class, schema)).containsExactlyInAnyOrderElementsOf(COLUMNS);
        assertThat(jdbc.queryForMap("""
                SELECT c.relrowsecurity AS rls, c.relforcerowsecurity AS force_rls
                  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = ?
                   AND c.relname = 'hris_operational_exception_projection'
                """, schema)).containsEntry("rls", true)
                .containsEntry("force_rls", true);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_policies
                 WHERE schemaname = ?
                   AND tablename = 'hris_operational_exception_projection'
                """, Integer.class, schema)).isEqualTo(1);
    }

    private ValidatedEvent event(
            HrisOperationalExceptionEventContract contract,
            long revision,
            UUID eventId,
            InternalState state,
            String occurredAt) {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        ObjectNode data = json.createObjectNode();
        data.put("exceptionPublicId", EXCEPTION_ID.toString());
        data.put("ownerService", "dwp-people-server");
        data.put("ownerModule", "HRM");
        data.put("sourceRef", "urn:dwp:hris:hrm:assignment:" + EXCEPTION_ID);
        data.put("sourceRevision", revision);
        data.put("type", "ASSIGNMENT_PROCESSING");
        data.put("severity", "HIGH");
        data.put("state", state.name());
        data.put("code", "HRM_ASSIGNMENT_STALLED");
        data.put("summaryKey",
                "hris.operations.exception.hrm.assignment-stalled");
        data.put("ownerRouteKey",
                "route.hcm.operations.employment-assignments.page");
        data.putArray("evidenceRefs").add(
                "urn:dwp:hris:evidence:domain-event:"
                        + "40000000-0000-4000-8000-000000000040");
        data.put("correlationId", CORRELATION_ID.toString());
        data.put("occurredAt", occurredAt);
        return contract.validate(new DomainEventEnvelope(
                "1.0", eventId, SOURCE,
                HrisOperationalExceptionEventContract.TYPE, 1,
                Instant.parse(occurredAt),
                HrisOperationalExceptionEventContract.AGGREGATE + "/"
                        + EXCEPTION_ID,
                42L, HrisOperationalExceptionEventContract.AGGREGATE,
                EXCEPTION_ID.toString(), revision, CORRELATION_ID.toString(),
                null, null, data, Map.of()));
    }

    private String insertSql() {
        return """
                INSERT INTO hris_operational_exception_projection (
                    tenant_id, exception_public_id, event_source, owner_service,
                    owner_module, source_ref, source_revision, exception_type,
                    severity, internal_state, code, summary_key, owner_route_key,
                    evidence_refs, correlation_id, event_digest,
                    first_observed_at, last_observed_at, occurrence_count, version)
                VALUES (42, ?, 'urn:dwp:people:hrm:operational-exception',
                    'dwp-people-server', 'HRM', ?, 1, 'ASSIGNMENT_PROCESSING',
                    'HIGH', 'OPEN', 'HRM_ASSIGNMENT_STALLED',
                    'hris.operations.exception.hrm.assignment-stalled',
                    'route.hcm.operations.employment-assignments.page',
                    ARRAY['urn:dwp:hris:evidence:domain-event:' ||
                        '40000000-0000-4000-8000-000000000040'],
                    ?, repeat('a', 64), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 1)
                """;
    }

    private void tenant(JdbcTemplate jdbc, long tenantId) {
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?, true)",
                String.class, Long.toString(tenantId));
    }

    private Map<String, Integer> migrationChecksums(
            JdbcTemplate jdbc, String schema) {
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
                 WHERE table_schema = ? AND table_type = 'BASE TABLE'
                 ORDER BY table_name
                """, String.class, schema));
    }

    private FluentConfiguration flyway(
            PGSimpleDataSource source, String schema, String target) {
        new JdbcTemplate(source).execute(
                "CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public");
        return Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target(target)
                .cleanDisabled(false);
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}
