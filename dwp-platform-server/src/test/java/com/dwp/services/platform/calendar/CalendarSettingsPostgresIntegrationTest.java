package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
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
                .target("377").load().migrate();
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

    @Test
    void activeCreateAndEditDelegationsFlowThroughCommandAccessAndFailClosedAfterExpiryOrRevocation() {
        CalendarRepository calendar = new CalendarRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        UUID calendarId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(3).withNano(0);
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, owner_user_id,
                    owner_person_public_id, owner_display_name, name_ko, name_en,
                    calendar_type, visibility, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Owner', '위임 캘린더', 'Delegated calendar',
                        'PERSONAL', 'PRIVATE', 'ACTIVE', ?, ?)
                """, calendarId, tenant, "delegated-" + calendarId,
                actor.userId(), owner, actor.userId(), actor.userId());
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, title, event_type,
                    starts_at, ends_at, time_zone, status, visibility,
                    recurrence_pattern, recurrence_interval, response_required,
                    source_type, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Owner', 'Delegated edit target', 'MEETING',
                        ?, ?, 'UTC', 'CONFIRMED', 'DEFAULT', 'NONE', 1, FALSE,
                        'NATIVE', ?, ?)
                """, eventId, tenant, calendarId, actor.userId(), owner,
                startsAt, startsAt.plusHours(1), actor.userId(), actor.userId());

        CalendarSettingsAccess.Actor delegateActor =
                new CalendarSettingsAccess.Actor(tenant, 102, delegate);
        repository.lockSettings(actor, settingsWrite("TENANT_POLICY", 31));
        OffsetDateTime now = OffsetDateTime.now();
        CalendarSettingsRepository.DelegationRow created = repository.createDelegation(
                actor, UUID.randomUUID(), delegate, false, false, true,
                now.minusMinutes(1), now.plusHours(1));

        CalendarRepository.CalendarRow createCalendar = calendar.calendars(
                        tenant, delegateActor.userId(), delegateActor.personPublicId(), null, false)
                .stream().filter(value -> value.calendarId().equals(calendarId))
                .findFirst().orElseThrow();
        assertThat(createCalendar.accessLevel())
                .isEqualTo(CalendarTypes.CalendarAccessLevel.EVENT_ATTENDEE);
        assertThat(CalendarAccessPolicy.calendarCapabilities(createCalendar).canCreateEvents())
                .isTrue();
        assertThat(CalendarAccessPolicy.writableCalendar(
                calendar, tenant, delegateActor.userId(), delegateActor.personPublicId(),
                null, calendarId)).isEqualTo(calendarId);
        CalendarRepository.EventRow createOnlyEvent = CalendarRepositoryRouting.event(
                        calendar, tenant, delegateActor.userId(), delegateActor.personPublicId(),
                        null, eventId, false)
                .orElseThrow();
        assertThat(createOnlyEvent.detailLevel())
                .isEqualTo(CalendarTypes.EventDetailLevel.FREE_BUSY);
        CalendarDtos.EventSummary createOnlySummary = new CalendarOccurrenceProjector(calendar)
                .summary(
                        tenant, delegateActor.userId(), delegateActor.personPublicId(),
                        createOnlyEvent, false, "en");
        assertThat(createOnlySummary.attendees()).isEmpty();

        jdbc.update("""
                UPDATE cal_calendar_delegations
                   SET can_create = FALSE, can_edit_schedule = TRUE,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND delegation_id = ?
                """, tenant, created.delegationId());
        CalendarRepository.EventRow editable = CalendarRepositoryRouting.event(
                        calendar, tenant, delegateActor.userId(), delegateActor.personPublicId(),
                        null, eventId, false)
                .orElseThrow();
        assertThat(editable.accessLevel()).isEqualTo(CalendarTypes.CalendarAccessLevel.EDIT);
        assertThat(CalendarAccessPolicy.canEdit(editable)).isTrue();
        CalendarDtos.UpdateEventRequest delegatedUpdate = new CalendarDtos.UpdateEventRequest(
                "Delegated schedule update", null, CalendarTypes.EventType.MEETING,
                startsAt.plusHours(2), startsAt.plusHours(3), "UTC", false,
                null, null, CalendarTypes.EventVisibility.DEFAULT,
                CalendarTypes.RecurrencePattern.NONE, 1, null, false,
                List.of(), null, 0L, CalendarTypes.EventImportance.NORMAL);
        assertThat(calendar.updateEvent(
                tenant, delegateActor.userId(), delegateActor.personPublicId(),
                null, eventId, delegatedUpdate)).isOne();
        CalendarDtos.UpdateEventRequest currentVersionUpdate = new CalendarDtos.UpdateEventRequest(
                delegatedUpdate.title(), delegatedUpdate.description(), delegatedUpdate.type(),
                delegatedUpdate.startsAt(), delegatedUpdate.endsAt(), delegatedUpdate.timeZone(),
                delegatedUpdate.allDay(), delegatedUpdate.location(),
                delegatedUpdate.conferenceUrl(), delegatedUpdate.visibility(),
                delegatedUpdate.recurrence(), delegatedUpdate.recurrenceInterval(),
                delegatedUpdate.recurrenceUntil(), delegatedUpdate.responseRequired(),
                delegatedUpdate.attendees(), delegatedUpdate.resourceId(), 1L,
                delegatedUpdate.importance());

        jdbc.update("""
                UPDATE cal_calendar_delegations
                   SET valid_until = CURRENT_TIMESTAMP - INTERVAL '1 second',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND delegation_id = ?
                """, tenant, created.delegationId());
        assertThat(calendar.calendarAccess(
                tenant, delegateActor.userId(), delegateActor.personPublicId(), null, calendarId))
                .isEmpty();
        assertThat(CalendarRepositoryRouting.event(
                calendar, tenant, delegateActor.userId(), delegateActor.personPublicId(),
                null, eventId, false)).isEmpty();
        assertThat(calendar.updateEvent(
                tenant, delegateActor.userId(), delegateActor.personPublicId(),
                null, eventId, currentVersionUpdate)).isZero();

        jdbc.update("""
                UPDATE cal_calendar_delegations
                   SET valid_until = CURRENT_TIMESTAMP + INTERVAL '1 hour',
                       status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP,
                       revoked_by = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND delegation_id = ?
                """, actor.userId(), tenant, created.delegationId());
        assertThat(calendar.calendarAccess(
                tenant, delegateActor.userId(), delegateActor.personPublicId(), null, calendarId))
                .isEmpty();
        assertThat(calendar.updateEvent(
                tenant, delegateActor.userId(), delegateActor.personPublicId(),
                null, eventId, currentVersionUpdate)).isZero();

        long otherTenant = tenant + 200_000;
        identity(otherTenant, actor.userId(), owner);
        identity(otherTenant, delegateActor.userId(), delegate);
        CalendarSettingsAccess.Actor otherOwner =
                new CalendarSettingsAccess.Actor(otherTenant, actor.userId(), owner);
        CalendarSettingsRepository otherSettings = new CalendarSettingsRepository(jdbc);
        otherSettings.lockSettings(otherOwner, settingsWrite("TENANT_POLICY", 31));
        otherSettings.createDelegation(
                otherOwner, UUID.randomUUID(), delegate, false, true, false,
                now.minusMinutes(1), now.plusHours(1));
        assertThat(calendar.calendarAccess(
                tenant, delegateActor.userId(), delegateActor.personPublicId(), null, calendarId))
                .isEmpty();
        assertThat(calendar.updateEvent(
                tenant, delegateActor.userId(), delegateActor.personPublicId(),
                null, eventId, currentVersionUpdate)).isZero();
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
