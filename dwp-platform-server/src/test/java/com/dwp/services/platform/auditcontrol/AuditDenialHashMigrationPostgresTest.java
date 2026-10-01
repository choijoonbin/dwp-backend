package com.dwp.services.platform.auditcontrol;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AuditDenialHashMigrationPostgresTest {

    private static final String LEGACY_HASH = "a".repeat(64);
    private static final String PREFIXED_HASH = "hmac-sha256:" + "b".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void upgradesCurrentAuditHistoryWithoutRewritingLegacyHashes() {
        PGSimpleDataSource source = dataSource();
        Flyway current = flyway(source).target("318").load();
        current.clean();
        current.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        UUID legacyEvent = insert(jdbc, LEGACY_HASH, LEGACY_HASH);

        flyway(source).load().migrate();

        assertHashColumns(jdbc);
        Map<String, Object> preserved = jdbc.queryForMap("""
                SELECT session_id_hash, client_address_hash
                  FROM sys_audit_events
                 WHERE event_id = ?
                """, legacyEvent);
        assertThat(preserved.get("session_id_hash")).isEqualTo(LEGACY_HASH);
        assertThat(preserved.get("client_address_hash")).isEqualTo(LEGACY_HASH);
        insert(jdbc, PREFIXED_HASH, PREFIXED_HASH);
    }

    @Test
    void cleanMigrationAcceptsOnlyLegacyOrPrefixedSha256Evidence() {
        PGSimpleDataSource source = dataSource();
        Flyway clean = flyway(source).load();
        clean.clean();
        clean.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);

        assertHashColumns(jdbc);
        insert(jdbc, LEGACY_HASH, PREFIXED_HASH);
        LocalDate futureMonth = LocalDate.now(ZoneOffset.UTC)
                .plusMonths(2)
                .withDayOfMonth(1);
        jdbc.query(
                "SELECT sys_ensure_audit_event_partition(?)",
                statement -> statement.setObject(1, futureMonth),
                result -> null);
        insertAt(
                jdbc,
                PREFIXED_HASH,
                PREFIXED_HASH,
                futureMonth.atStartOfDay().atOffset(ZoneOffset.UTC));
        assertThatThrownBy(() -> insert(jdbc, "hmac-sha256:" + "z".repeat(64), PREFIXED_HASH))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, "c".repeat(65), PREFIXED_HASH))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private FluentConfiguration flyway(PGSimpleDataSource source) {
        return Flyway.configure()
                .dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false);
    }

    private void assertHashColumns(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("""
                SELECT column_name, data_type, character_maximum_length
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'sys_audit_events'
                   AND column_name IN ('session_id_hash', 'client_address_hash')
                 ORDER BY column_name
                """))
                .hasSize(2)
                .allSatisfy(column -> {
                    assertThat(column.get("data_type")).isEqualTo("character varying");
                    assertThat(column.get("character_maximum_length")).isEqualTo(76);
                });
    }

    private UUID insert(JdbcTemplate jdbc, String sessionHash, String addressHash) {
        return insertAt(jdbc, sessionHash, addressHash, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private UUID insertAt(
            JdbcTemplate jdbc,
            String sessionHash,
            String addressHash,
            OffsetDateTime occurredAt) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_audit_events (
                    event_id, occurred_at, event_version, tenant_id,
                    category, action, outcome, severity, actor_type,
                    source_service, source_module, environment,
                    target_type, target_id, session_id_hash,
                    client_address_hash, record_hash)
                VALUES (?, ?, '1', 1,
                    'POLICY_DENIED', 'GATEWAY_DENIAL', 'DENIED', 'MEDIUM', 'USER',
                    'dwp-gateway', 'authorization', 'local',
                    'PRODUCT_ROUTE', 'route.test', ?, ?, ?)
                """, eventId, occurredAt, sessionHash, addressHash, "f".repeat(64));
        return eventId;
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}
