package com.dwp.services.platform.workplace.connectorops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.Capability;
import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.ConnectorKind;
import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.ProviderReportedState;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class BundledVerificationPresencePostgresTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void seedsAndServesOnlyTheExactBundledVerificationTenantWithoutTakingOwnership() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        jdbc.update("""
                INSERT INTO sys_service_tenants (tenant_id, tenant_key) VALUES
                    (1, 'default'), (2, 'customer')
                """);
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id) VALUES
                    (1, 900018, '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'),
                    (2, 900018, '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b')
                """);
        jdbc.update("INSERT INTO wp_tenant_policies (tenant_id) VALUES (1), (2)");

        var migration = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V340__seed_skax_verification_presence_connector.sql"));
        migration.execute(dataSource);
        migration.execute(dataSource);

        assertThat(jdbc.queryForList("""
                SELECT tenant_id, connector_kind, provider, enabled,
                       configuration_reference, version, updated_by
                  FROM wp_experience_connector_configurations
                """))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("tenant_id")).isEqualTo(1L);
                    assertThat(row.get("connector_kind")).isEqualTo("ACTUAL_PRESENCE");
                    assertThat(row.get("provider")).isEqualTo(
                            BundledVerificationPresenceAdapter.PROVIDER);
                    assertThat(row.get("enabled")).isEqualTo(true);
                    assertThat(row.get("configuration_reference")).isEqualTo(
                            BundledVerificationPresenceAdapter.CONFIGURATION_REFERENCE);
                    assertThat(row.get("version")).isEqualTo(0L);
                    assertThat(row.get("updated_by")).isEqualTo(900018L);
                });

        var adapter = new BundledVerificationPresenceAdapter(
                jdbc, Clock.fixed(Instant.parse("2026-10-27T07:20:30Z"), ZoneOffset.UTC));
        var allowed = new WorkplaceConnectorReplayAdapter.ProviderContext(
                1, ConnectorKind.ACTUAL_PRESENCE,
                BundledVerificationPresenceAdapter.PROVIDER,
                BundledVerificationPresenceAdapter.CONFIGURATION_REFERENCE);
        assertThat(adapter.ready(allowed)).isTrue();
        assertThat(adapter.ready(new WorkplaceConnectorReplayAdapter.ProviderContext(
                2, ConnectorKind.ACTUAL_PRESENCE,
                BundledVerificationPresenceAdapter.PROVIDER,
                BundledVerificationPresenceAdapter.CONFIGURATION_REFERENCE))).isFalse();
        assertThat(adapter.ready(new WorkplaceConnectorReplayAdapter.ProviderContext(
                1, ConnectorKind.ACTUAL_PRESENCE,
                BundledVerificationPresenceAdapter.PROVIDER,
                "local://another/presence"))).isFalse();
        assertThat(adapter.observe(allowed)).satisfies(observation -> {
            assertThat(observation.state()).isEqualTo(ProviderReportedState.HEALTHY);
            assertThat(observation.capabilities())
                    .containsExactly(Capability.HEALTH, Capability.CHECKPOINT);
            assertThat(observation.sourceObservedAt())
                    .isEqualTo(OffsetDateTime.parse("2026-10-27T07:20:30Z"))
                    .isEqualTo(observation.lastSuccessAt());
            assertThat(observation.lagSeconds()).isZero();
            assertThat(observation.checkpointReference())
                    .isEqualTo("local-verification-presence:29884760");
            assertThat(observation.payloadFingerprint())
                    .isEqualTo("skax_presence_29884760");
        });

        var repository = new WorkplaceConnectorOpsRepository(jdbc, new ObjectMapper());
        UUID verificationPersonId = UUID.fromString(
                BundledVerificationPresenceAdapter.VERIFICATION_PERSON_ID);
        assertThat(repository.observableConfigurationForIdentity(
                "default",
                BundledVerificationPresenceAdapter.VERIFICATION_USER_ID,
                verificationPersonId,
                ConnectorKind.ACTUAL_PRESENCE,
                BundledVerificationPresenceAdapter.PROVIDER,
                BundledVerificationPresenceAdapter.CONFIGURATION_REFERENCE))
                .get()
                .satisfies(row -> {
                    assertThat(row.tenantId()).isEqualTo(1L);
                    assertThat(row.configuredProvider())
                            .isEqualTo(BundledVerificationPresenceAdapter.PROVIDER);
                    assertThat(row.configurationReference())
                            .isEqualTo(BundledVerificationPresenceAdapter.CONFIGURATION_REFERENCE);
                });
        assertThat(repository.observableConfigurationForIdentity(
                "default", 1L, verificationPersonId,
                ConnectorKind.ACTUAL_PRESENCE,
                BundledVerificationPresenceAdapter.PROVIDER,
                BundledVerificationPresenceAdapter.CONFIGURATION_REFERENCE)).isEmpty();
        assertThat(repository.observableConfigurationForIdentity(
                "default",
                BundledVerificationPresenceAdapter.VERIFICATION_USER_ID,
                verificationPersonId,
                ConnectorKind.ACTUAL_PRESENCE,
                "WRONG_PROVIDER",
                BundledVerificationPresenceAdapter.CONFIGURATION_REFERENCE)).isEmpty();
        assertThat(repository.observableConfigurationForIdentity(
                "default",
                BundledVerificationPresenceAdapter.VERIFICATION_USER_ID,
                verificationPersonId,
                ConnectorKind.ACTUAL_PRESENCE,
                BundledVerificationPresenceAdapter.PROVIDER,
                "local://wrong/reference")).isEmpty();
        jdbc.update("""
                UPDATE wp_experience_connector_configurations
                   SET updated_by=77
                 WHERE tenant_id=1 AND connector_kind='ACTUAL_PRESENCE'
                """);
        assertThat(repository.observableConfigurationForIdentity(
                "default",
                BundledVerificationPresenceAdapter.VERIFICATION_USER_ID,
                verificationPersonId,
                ConnectorKind.ACTUAL_PRESENCE,
                BundledVerificationPresenceAdapter.PROVIDER,
                BundledVerificationPresenceAdapter.CONFIGURATION_REFERENCE)).isEmpty();

        jdbc.update("""
                UPDATE wp_experience_connector_configurations
                   SET provider='CUSTOM_OWNER', configuration_reference='vault://customer/presence',
                       version=7, updated_by=77
                 WHERE tenant_id=1 AND connector_kind='ACTUAL_PRESENCE'
                """);
        migration.execute(dataSource);
        assertThat(jdbc.queryForMap("""
                SELECT provider, configuration_reference, version, updated_by
                  FROM wp_experience_connector_configurations
                 WHERE tenant_id=1 AND connector_kind='ACTUAL_PRESENCE'
                """))
                .containsEntry("provider", "CUSTOM_OWNER")
                .containsEntry("configuration_reference", "vault://customer/presence")
                .containsEntry("version", 7L)
                .containsEntry("updated_by", 77L);
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE sys_service_tenants (
                    tenant_id bigint PRIMARY KEY,
                    tenant_key text NOT NULL UNIQUE)
                """);
        jdbc.execute("""
                CREATE TABLE cal_identity_links (
                    tenant_id bigint NOT NULL,
                    user_id bigint NOT NULL,
                    person_public_id uuid NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE wp_tenant_policies (
                    tenant_id bigint PRIMARY KEY)
                """);
        jdbc.execute("""
                CREATE TABLE wp_experience_connector_configurations (
                    tenant_id bigint NOT NULL REFERENCES wp_tenant_policies(tenant_id),
                    connector_kind varchar(24) NOT NULL,
                    provider varchar(80),
                    enabled boolean NOT NULL DEFAULT false,
                    configuration_reference varchar(160),
                    version bigint NOT NULL DEFAULT 0,
                    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_by bigint,
                    PRIMARY KEY (tenant_id, connector_kind))
                """);
        jdbc.execute("""
                CREATE TABLE wp_connector_runtime_truth (
                    tenant_id bigint NOT NULL,
                    connector_kind varchar(24) NOT NULL,
                    observation_id uuid,
                    provider varchar(80),
                    configuration_version bigint,
                    reported_state varchar(32),
                    capabilities jsonb,
                    source_observed_at timestamptz,
                    received_at timestamptz,
                    last_success_at timestamptz,
                    lag_seconds bigint,
                    checkpoint_reference varchar(200),
                    retry_queue_depth bigint,
                    dead_letter_queue_depth bigint,
                    error_code varchar(80),
                    observation_sequence bigint,
                    version bigint,
                    PRIMARY KEY (tenant_id, connector_kind))
                """);
    }
}
