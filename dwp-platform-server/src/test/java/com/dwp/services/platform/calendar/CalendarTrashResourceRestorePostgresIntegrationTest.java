package com.dwp.services.platform.calendar;

import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class CalendarTrashResourceRestorePostgresIntegrationTest {

    private static final long TENANT_ID = 9_293_001L;
    private static final long OWNER_USER_ID = 9_293_101L;
    private static final UUID OWNER_PERSON_ID =
            UUID.fromString("92930000-0000-4000-8000-000000000001");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;

    private CalendarCollaborationService collaborationService;

    @BeforeAll
    static void migrateLatest() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        for (String table : new String[] {
                "cal_resource_restore_commands",
                "cal_event_tombstones",
                "cal_resource_bookings",
                "cal_event_attendees",
                "cal_events",
                "cal_resources",
                "cal_calendars",
                "cal_identity_links"
        }) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", TENANT_ID);
        }
        CalendarCollaborationRepository collaboration =
                new CalendarCollaborationRepository(jdbc);
        CalendarRestoreRepository restoreRepository =
                new CalendarRestoreRepository(jdbc);
        CalendarRepository calendar = new CalendarRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        CalendarRetentionRepository retention = new CalendarRetentionRepository(jdbc);
        CalendarResourceRestoreService restore = new CalendarResourceRestoreService(
                collaboration,
                restoreRepository,
                calendar,
                retention,
                mock(WorkplaceRoomAccessPort.class));
        collaborationService = new CalendarCollaborationService(
                collaboration, calendar, retention, restore);
    }

    @Test
    void trashRestoreAndExplicitRebookReturnExactDurableOutcomes() {
        Fixture fixture = fixture("primary");

        CalendarDtos.EventCapabilities trashed = collaborationService.trashEvent(
                TENANT_ID,
                OWNER_USER_ID,
                OWNER_PERSON_ID,
                null,
                fixture.eventId(),
                "corr-trash",
                new CalendarDtos.TrashEventRequest(0L, "User removed event"));
        assertThat(trashed.canRestore()).isTrue();
        assertThat(booking(fixture.eventId())).containsEntry("booking_status", "CANCELLED")
                .containsEntry("cancelled_for_event_trash", true)
                .containsEntry("version", 1L);

        CalendarRecoveryDtos.RestoreEventResponse restored = collaborationService.restoreEvent(
                TENANT_ID,
                OWNER_USER_ID,
                OWNER_PERSON_ID,
                null,
                fixture.eventId(),
                "corr-restore",
                new CalendarDtos.VersionRequest(1L));
        assertThat(restored.outcome())
                .isEqualTo(CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_REBOOK_REQUIRED);
        assertThat(restored.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.EXPLICIT_REBOOK_REQUIRED);
        assertThat(restored.resources()).singleElement().satisfies(resource -> {
            assertThat(resource.resourceId()).isEqualTo(fixture.resourceId());
            assertThat(resource.bookingVersion()).isEqualTo(1L);
            assertThat(resource.canRebook()).isTrue();
        });

        UUID idempotencyKey = UUID.randomUUID();
        CalendarRecoveryDtos.RestoreResourceBookingRequest request =
                new CalendarRecoveryDtos.RestoreResourceBookingRequest(
                        restored.eventVersion(),
                        fixture.resourceId(),
                        restored.resources().getFirst().bookingVersion(),
                        idempotencyKey);
        CalendarRecoveryDtos.RestoreEventResponse rebooked =
                collaborationService.rebookRestoredResource(
                        TENANT_ID,
                        OWNER_USER_ID,
                        OWNER_PERSON_ID,
                        null,
                        fixture.eventId(),
                        "corr-rebook",
                        request);
        CalendarRecoveryDtos.RestoreEventResponse replayed =
                collaborationService.rebookRestoredResource(
                        TENANT_ID,
                        OWNER_USER_ID,
                        OWNER_PERSON_ID,
                        null,
                        fixture.eventId(),
                        "corr-rebook-retry",
                        request);

        assertThat(rebooked.outcome())
                .isEqualTo(CalendarRecoveryDtos.RestoreOutcome.EVENT_AND_RESOURCES_RESTORED);
        assertThat(rebooked.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.RESOURCE_REBOOKED);
        assertThat(replayed).isEqualTo(rebooked);
        assertThat(booking(fixture.eventId())).containsEntry("booking_status", "CONFIRMED")
                .containsEntry("cancelled_for_event_trash", false)
                .containsEntry("version", 2L);
        assertThat(commandCount(idempotencyKey)).isOne();
    }

    @Test
    void competingBookingProducesAStableConflictReceiptAndNeverReactivatesTheRoom() {
        Fixture fixture = fixture("conflict-target");
        collaborationService.trashEvent(
                TENANT_ID,
                OWNER_USER_ID,
                OWNER_PERSON_ID,
                null,
                fixture.eventId(),
                "corr-trash-conflict",
                new CalendarDtos.TrashEventRequest(0L, "Conflict test"));
        CalendarRecoveryDtos.RestoreEventResponse restored = collaborationService.restoreEvent(
                TENANT_ID,
                OWNER_USER_ID,
                OWNER_PERSON_ID,
                null,
                fixture.eventId(),
                "corr-restore-conflict",
                new CalendarDtos.VersionRequest(1L));
        insertCompetingBooking(fixture);

        UUID idempotencyKey = UUID.randomUUID();
        CalendarRecoveryDtos.RestoreResourceBookingRequest request =
                new CalendarRecoveryDtos.RestoreResourceBookingRequest(
                        restored.eventVersion(), fixture.resourceId(), 1L, idempotencyKey);
        CalendarRecoveryDtos.RestoreEventResponse rejected =
                collaborationService.rebookRestoredResource(
                        TENANT_ID,
                        OWNER_USER_ID,
                        OWNER_PERSON_ID,
                        null,
                        fixture.eventId(),
                        "corr-rebook-conflict",
                        request);
        CalendarRecoveryDtos.RestoreEventResponse replayed =
                collaborationService.rebookRestoredResource(
                        TENANT_ID,
                        OWNER_USER_ID,
                        OWNER_PERSON_ID,
                        null,
                        fixture.eventId(),
                        "corr-rebook-conflict-retry",
                        request);

        assertThat(rejected.outcome())
                .isEqualTo(CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_CONFLICT);
        assertThat(rejected.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.RESOURCE_TIME_CONFLICT);
        assertThat(replayed).isEqualTo(rejected);
        assertThat(booking(fixture.eventId())).containsEntry("booking_status", "CANCELLED")
                .containsEntry("cancelled_for_event_trash", true)
                .containsEntry("version", 1L);
        assertThat(commandCount(idempotencyKey)).isOne();
    }

    @Test
    void aPreviouslyManualCancelledBookingIsNotPresentedAsTrashRestorable() {
        Fixture fixture = fixture("manual-cancel");
        jdbc.update("""
                UPDATE cal_resource_bookings
                   SET booking_status = 'CANCELLED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND event_id = ?
                """, OWNER_USER_ID, TENANT_ID, fixture.eventId());

        collaborationService.trashEvent(
                TENANT_ID,
                OWNER_USER_ID,
                OWNER_PERSON_ID,
                null,
                fixture.eventId(),
                "corr-trash-manual-cancel",
                new CalendarDtos.TrashEventRequest(0L, "No room should be restored"));
        CalendarRecoveryDtos.RestoreEventResponse restored = collaborationService.restoreEvent(
                TENANT_ID,
                OWNER_USER_ID,
                OWNER_PERSON_ID,
                null,
                fixture.eventId(),
                "corr-restore-manual-cancel",
                new CalendarDtos.VersionRequest(1L));

        assertThat(restored.outcome())
                .isEqualTo(CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_NO_PRIOR_RESOURCE);
        assertThat(restored.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.NO_PRIOR_RESOURCE);
        assertThat(restored.resources()).isEmpty();
        assertThat(booking(fixture.eventId()))
                .containsEntry("booking_status", "CANCELLED")
                .containsEntry("cancelled_for_event_trash", false)
                .containsEntry("version", 1L);
    }

    private Fixture fixture(String suffix) {
        UUID calendarId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now(ZoneOffset.UTC)
                .plusDays(2)
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id)
                VALUES (?, ?, ?)
                """, TENANT_ID, OWNER_USER_ID, OWNER_PERSON_ID);
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, owner_user_id,
                    owner_person_public_id, owner_display_name, name_ko, name_en,
                    calendar_type, visibility, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Restore owner', '복원 캘린더', 'Restore calendar',
                        'PERSONAL', 'DETAILS', 'ACTIVE', ?, ?)
                """, calendarId, TENANT_ID, "restore-" + suffix,
                OWNER_USER_ID, OWNER_PERSON_ID, OWNER_USER_ID, OWNER_USER_ID);
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, title, description,
                    event_type, starts_at, ends_at, time_zone, status, visibility,
                    recurrence_pattern, recurrence_interval, response_required,
                    source_type, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Restore owner', 'Restore contract', 'Agenda',
                        'MEETING', ?, ?, 'UTC', 'CONFIRMED', 'DEFAULT',
                        'NONE', 1, FALSE, 'NATIVE', ?, ?)
                """, eventId, TENANT_ID, calendarId, OWNER_USER_ID, OWNER_PERSON_ID,
                startsAt, startsAt.plusHours(1), OWNER_USER_ID, OWNER_USER_ID);
        jdbc.update("""
                INSERT INTO cal_resources (
                    resource_id, tenant_id, resource_code, name_ko, name_en,
                    resource_type, site_name, capacity, time_zone,
                    approval_required, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, '복원 회의실', 'Restore room', 'ROOM',
                        'Restore site', 8, 'UTC', FALSE, 'AVAILABLE', ?, ?)
                """, resourceId, TENANT_ID, "RESTORE-" + suffix,
                OWNER_USER_ID, OWNER_USER_ID);
        jdbc.update("""
                INSERT INTO cal_resource_bookings (
                    tenant_id, event_id, resource_id, starts_at, ends_at,
                    booking_status, requested_by, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)
                """, TENANT_ID, eventId, resourceId, startsAt, startsAt.plusHours(1),
                OWNER_USER_ID, OWNER_USER_ID, OWNER_USER_ID);
        return new Fixture(eventId, resourceId, startsAt);
    }

    private void insertCompetingBooking(Fixture target) {
        UUID eventId = UUID.randomUUID();
        UUID calendarId = jdbc.queryForObject(
                "SELECT calendar_id FROM cal_events WHERE event_id = ?",
                UUID.class,
                target.eventId());
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, title, description,
                    event_type, starts_at, ends_at, time_zone, status, visibility,
                    recurrence_pattern, recurrence_interval, response_required,
                    source_type, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Restore owner', 'Competing room use', 'Agenda',
                        'MEETING', ?, ?, 'UTC', 'CONFIRMED', 'DEFAULT',
                        'NONE', 1, FALSE, 'NATIVE', ?, ?)
                """, eventId, TENANT_ID, calendarId, OWNER_USER_ID, OWNER_PERSON_ID,
                target.startsAt(), target.startsAt().plusHours(1),
                OWNER_USER_ID, OWNER_USER_ID);
        jdbc.update("""
                INSERT INTO cal_resource_bookings (
                    tenant_id, event_id, resource_id, starts_at, ends_at,
                    booking_status, requested_by, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)
                """, TENANT_ID, eventId, target.resourceId(),
                target.startsAt(), target.startsAt().plusHours(1),
                OWNER_USER_ID, OWNER_USER_ID, OWNER_USER_ID);
    }

    private java.util.Map<String, Object> booking(UUID eventId) {
        return jdbc.queryForMap("""
                SELECT booking_status, cancelled_for_event_trash, version
                  FROM cal_resource_bookings
                 WHERE tenant_id = ? AND event_id = ?
                """, TENANT_ID, eventId);
    }

    private int commandCount(UUID idempotencyKey) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM cal_resource_restore_commands
                 WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """, Integer.class, TENANT_ID, OWNER_USER_ID, idempotencyKey);
    }

    private record Fixture(
            UUID eventId,
            UUID resourceId,
            OffsetDateTime startsAt) {
    }
}
