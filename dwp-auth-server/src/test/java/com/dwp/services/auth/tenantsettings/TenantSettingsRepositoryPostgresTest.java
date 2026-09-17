package com.dwp.services.auth.tenantsettings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class TenantSettingsRepositoryPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TenantSettingsRepository repository;
    private static ObjectMapper mapper;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper().findAndRegisterModules();
        repository = new TenantSettingsRepository(
                jdbc, new NamedParameterJdbcTemplate(dataSource), mapper);
    }

    @Test
    void persistsTheIndependentWorkflowAndPublishesTheRuntimePolicy() {
        Instant now = Instant.parse("2026-09-17T08:00:00Z");
        TenantSettingsRepository.PolicyState before = repository.currentPolicy(1L);
        TenantSettingsRepository.PolicyState proposed = new TenantSettingsRepository.PolicyState(
                before.defaultLoginType(), before.allowedLoginTypes(),
                before.localLoginEnabled(), before.ssoLoginEnabled(), before.ssoProviderKey(),
                !before.requireMfa(), before.tokenTtlSec());
        TenantSettingsDtos.ChangeSet draft = repository.insertChangeSet(
                1L, mapper.valueToTree(before), mapper.valueToTree(proposed),
                "a".repeat(64), "b".repeat(64),
                new TenantSettingsDtos.Impact(
                        "ESTIMATED", repository.activeIdentityCount(1L),
                        "INTERNAL_AUTH_DIRECTORY_ACTIVE_IDENTITIES", now,
                        List.of("EXTERNAL_IDP_POPULATION_NOT_PROBED")),
                "Require an independent review before changing MFA policy.", 1L);

        TenantSettingsDtos.ChangeSet submitted = repository.submit(
                1L, draft.changeSetId(), draft.version(), 1L, now);
        TenantSettingsDtos.ChangeSet approved = repository.decide(
                1L, draft.changeSetId(), submitted.version(), "APPROVED",
                "A second administrator reviewed the proposed policy.", 2L, now.plusSeconds(1));
        TenantSettingsDtos.ChangeSet published = repository.publish(
                1L, draft.changeSetId(), approved.version(), proposed,
                3L, now.plusSeconds(2), UUID.randomUUID());

        assertThat(published.lifecycleState()).isEqualTo("PUBLISHED");
        assertThat(published.decidedBy()).isEqualTo(2L);
        assertThat(published.publishReceiptId()).isNotNull();
        assertThat(repository.currentPolicy(1L).requireMfa()).isEqualTo(proposed.requireMfa());
    }

    @Test
    void readsInternalRoleAndAppPresetCoverageWithoutExternalClaims() {
        List<TenantSettingsRepository.UserRow> users = repository.users(1L, null, 0, 20);
        assertThat(users).isNotEmpty();
        List<Long> userIds = users.stream().map(TenantSettingsRepository.UserRow::userId).toList();

        assertThat(repository.grants(1L, userIds)).containsKeys(userIds.toArray(Long[]::new));
        assertThat(repository.freshestProjectionSource(1L)).isNotNull();
    }

    @Test
    void storesRecoveryVerificationAsAnAttestationRatherThanExternalLoginProof() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_emergency_access_principals (
                    emergency_access_principal_id, tenant_id, user_id,
                    justification, review_due_at, lifecycle_state)
                VALUES (?, 1, 1, ?, CURRENT_TIMESTAMP + INTERVAL '90 days', 'ACTIVE')
                """, id, "Recovery owner registered for the tenant drill.");
        jdbc.update("""
                UPDATE com_emergency_access_principals
                   SET verification_status = 'VERIFIED',
                       verification_method = 'RECOVERY_DRILL_COMPLETED',
                       verification_reference = 'Internal drill ticket REC-2026-0917',
                       last_verified_at = CURRENT_TIMESTAMP,
                       last_verified_by = 2,
                       verification_due_at = CURRENT_TIMESTAMP + INTERVAL '90 days'
                 WHERE emergency_access_principal_id = ?
                """, id);

        assertThat(jdbc.queryForObject("""
                SELECT verification_method
                  FROM com_emergency_access_principals
                 WHERE emergency_access_principal_id = ?
                """, String.class, id)).isEqualTo("RECOVERY_DRILL_COMPLETED");
    }
}
