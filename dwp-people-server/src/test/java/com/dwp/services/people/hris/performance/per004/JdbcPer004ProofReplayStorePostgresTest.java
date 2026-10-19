package com.dwp.services.people.hris.performance.per004;

import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class JdbcPer004ProofReplayStorePostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    @Test void twoDurableIdentifiersReserveAtomicallyAndRejectDuplicateOrPartialCollision() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE sys_domain_event_inbox (
                    consumer_name TEXT NOT NULL, event_id UUID NOT NULL,
                    event_source TEXT NOT NULL, event_type TEXT NOT NULL,
                    schema_version INTEGER NOT NULL, tenant_id BIGINT NOT NULL,
                    aggregate_type TEXT NOT NULL, aggregate_id TEXT NOT NULL,
                    aggregate_sequence BIGINT NOT NULL, correlation_id TEXT NOT NULL,
                    payload JSONB NOT NULL, payload_sha256 CHAR(64) NOT NULL,
                    status TEXT NOT NULL, attempt_count INTEGER NOT NULL,
                    available_at TIMESTAMPTZ NOT NULL, processed_at TIMESTAMPTZ NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (consumer_name, event_id))
                """);
        JdbcPer004ProofReplayStore store = new JdbcPer004ProofReplayStore(jdbc,
                new DataSourceTransactionManager(source));
        UUID jti = UUID.randomUUID(), nonce = UUID.randomUUID();
        assertThat(store.reserve(Endpoint.PREPARE, claims(jti, nonce, 42))).isTrue();
        assertThat(store.reserve(Endpoint.PREPARE, claims(jti, nonce, 42))).isFalse();
        assertThat(store.reserve(Endpoint.PREPARE, claims(UUID.randomUUID(), nonce, 42))).isFalse();
        assertThat(store.reserve(Endpoint.PREPARE, claims(jti, UUID.randomUUID(), 43))).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_domain_event_inbox", Integer.class))
                .isEqualTo(2);
        assertThat(store.reserve(Endpoint.FINALIZE, claims(UUID.randomUUID(), UUID.randomUUID(), 42)))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_domain_event_inbox", Integer.class))
                .isEqualTo(4);
        assertThat(store.reserve(UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(30)))
                .isFalse();
    }

    private static HrisReferenceConfigurationWorkloadV1.ProofClaims claims(
            UUID jti, UUID nonce, long tenant) {
        Instant now = Instant.now();
        return new HrisReferenceConfigurationWorkloadV1.ProofClaims(
                "dwp-platform-server", "dwp-people-server", "dwp-platform-server",
                now, now, now.plusSeconds(30), jti, nonce, Endpoint.PREPARE.bindingId(),
                Endpoint.PREPARE.purpose(), "POST", Endpoint.PREPARE.path(null), tenant,
                "a".repeat(64), WorkloadAuthentication.CLIENT_CREDENTIAL_JWT, "per004-test");
    }
}
