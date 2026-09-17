package com.dwp.services.platform.calendar;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class CalendarSettingsPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicLong TENANTS = new AtomicLong(9_940_000);
    private static JdbcTemplate jdbc;

    private long tenant;
    private UUID owner;
    private UUID delegate;
    private CalendarSettingsAccess.Actor actor;
    private CalendarSettingsRepository repository;

    @BeforeAll
    static void migrateThroughCalendarSettingsContract() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .target("307").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void setUp() {
        tenant = TENANTS.incrementAndGet();
        owner = UUID.randomUUID();
        delegate = UUID.randomUUID();
        identity(tenant, 101, owner);
        identity(tenant, 102, delegate);
        actor = new CalendarSettingsAccess.Actor(tenant, 101, owner);
        repository = new CalendarSettingsRepository(jdbc);
    }

    @Test
    void persistsSettingsWithVersionedCompareAndSwapAndTenantIsolation() {
        CalendarSettingsRepository.SettingsWrite first = settingsWrite("USER", 31);
        CalendarSettingsRepository.SettingsRow created = repository
                .saveSettings(actor, first, 0).orElseThrow();
        assertThat(created.version()).isEqualTo(1);
        assertThat(repository.saveSettings(actor, settingsWrite("USER", 7), 0)).isEmpty();

        CalendarSettingsRepository.SettingsRow updated = repository
                .saveSettings(actor, settingsWrite("USER", 7), 1).orElseThrow();
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.workingDaysMask()).isEqualTo(7);

        long otherTenant = tenant + 100_000;
        UUID otherPerson = UUID.randomUUID();
        identity(otherTenant, 101, otherPerson);
        CalendarSettingsAccess.Actor other =
                new CalendarSettingsAccess.Actor(otherTenant, 101, otherPerson);
        assertThat(repository.settings(other)).isEmpty();
        assertThat(repository.settings(
                new CalendarSettingsAccess.Actor(otherTenant, 101, owner))).isEmpty();
    }

    @Test
    void serializesOverlappingDelegationCreationPerOwnerAndScopesEveryRead() {
        CalendarSettingsRepository.SettingsWrite baseline = settingsWrite("TENANT_POLICY", 31);
        repository.lockSettings(actor, baseline);
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T00:00:00Z");
        CalendarSettingsRepository.DelegationRow created = repository.createDelegation(
                actor, UUID.randomUUID(), delegate, true, false, true,
                now, now.plusDays(7));

        assertThat(created.canRespond()).isTrue();
        assertThat(created.canCreate()).isTrue();
        assertThat(repository.hasOverlappingDelegation(
                actor, delegate, now.plusDays(1), now.plusDays(2), now)).isTrue();
        assertThat(repository.hasOverlappingDelegation(
                actor, delegate, now.plusDays(8), now.plusDays(9), now)).isFalse();

        CalendarSettingsAccess.Actor wrongOwner =
                new CalendarSettingsAccess.Actor(tenant, 102, delegate);
        assertThat(repository.delegations(wrongOwner)).isEmpty();
        assertThat(repository.delegation(wrongOwner, created.delegationId())).isEmpty();
    }

    @Test
    void revocationRequiresOwnerVersionAndUnexpiredAuthority() {
        repository.lockSettings(actor, settingsWrite("TENANT_POLICY", 31));
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T00:00:00Z");
        CalendarSettingsRepository.DelegationRow created = repository.createDelegation(
                actor, UUID.randomUUID(), delegate, true, false, false,
                now.minusHours(1), now.plusHours(1));

        assertThat(repository.revokeDelegation(
                actor, created.delegationId(), 4, now)).isFalse();
        assertThat(repository.revokeDelegation(
                actor, created.delegationId(), 0, now)).isTrue();
        Optional<CalendarSettingsRepository.DelegationRow> revoked =
                repository.delegation(actor, created.delegationId());
        assertThat(revoked).get().satisfies(value -> {
            assertThat(value.status()).isEqualTo("REVOKED");
            assertThat(value.version()).isEqualTo(1);
        });
    }

    private CalendarSettingsRepository.SettingsWrite settingsWrite(String origin, int days) {
        return new CalendarSettingsRepository.SettingsWrite(
                days, LocalTime.of(9, 0), LocalTime.of(18, 0), "Asia/Seoul", 1,
                30, "FIVE_TEN", 5, "FREE_BUSY", 10, origin);
    }

    private void identity(long tenantId, long userId, UUID personPublicId) {
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id)
                VALUES (?, ?, ?)
                """, tenantId, userId, personPublicId);
    }
}
