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

import java.time.Clock;
import java.time.LocalDate;
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
                "wp_resources",
                "wp_sections",
                "wp_zones",
                "wp_floors",
                "wp_sites",
                "wp_campuses",
                "wp_tenant_policies",
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
        jdbc.update("""
                INSERT INTO sys_service_tenants (
                    provider_tenant_id, tenant_id, tenant_key, display_name,
                    lifecycle_state, data_region, isolation_model, created_by, updated_by)
                VALUES ('92930000-0000-4000-8000-000000000099', ?,
                        'calendar-restore-policy-test', 'Calendar restore policy test',
                        'ACTIVE', 'local', 'POOL', ?, ?)
                ON CONFLICT (tenant_id) DO NOTHING
                """, TENANT_ID, OWNER_USER_ID, OWNER_USER_ID);
        CalendarCollaborationRepository collaboration =
                new CalendarCollaborationRepository(jdbc);
        CalendarRestoreRepository restoreRepository =
                new CalendarRestoreRepository(jdbc);
        CalendarRepository calendar = new CalendarRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        CalendarRetentionRepository retention = new CalendarRetentionRepository(jdbc);
        RoomBookingPolicyService roomBookingPolicy = new RoomBookingPolicyService(
                calendar,
                new CalendarSchedulingHorizon(Clock.systemUTC()),
                new WorkplaceRoomRecurrencePolicy(jdbc));
        CalendarResourceRestoreService restore = new CalendarResourceRestoreService(
                collaboration,
                restoreRepository,
                calendar,
                retention,
                mock(WorkplaceRoomAccessPort.class),
                roomBookingPolicy);
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
    void restoredSeriesCannotBeExtendedBeforeRebookingWhenRecurringIsDisabled() {
        Fixture fixture = recurringFixture("recurrence-policy-bypass");
        manageAsWorkplaceRoom(fixture, false);

        collaborationService.trashEvent(
                TENANT_ID,
                OWNER_USER_ID,
                OWNER_PERSON_ID,
                null,
                fixture.eventId(),
                "corr-trash-recurrence",
                new CalendarDtos.TrashEventRequest(0L, "Recurrence policy test"));
        CalendarRecoveryDtos.RestoreEventResponse restored = collaborationService.restoreEvent(
                TENANT_ID,
                OWNER_USER_ID,
                OWNER_PERSON_ID,
                null,
                fixture.eventId(),
                "corr-restore-recurrence",
                new CalendarDtos.VersionRequest(1L));
        assertThat(restored.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.EXPLICIT_REBOOK_REQUIRED);
        assertThat(restored.resources()).singleElement().satisfies(resource ->
                assertThat(resource.canRebook()).isTrue());

        jdbc.update("""
                UPDATE cal_events
                   SET recurrence_until = recurrence_until + 7,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE tenant_id = ? AND event_id = ?
                """, OWNER_USER_ID, TENANT_ID, fixture.eventId());
        long currentEventVersion = jdbc.queryForObject("""
                SELECT version FROM cal_events WHERE tenant_id = ? AND event_id = ?
                """, Long.class, TENANT_ID, fixture.eventId());
        UUID idempotencyKey = UUID.randomUUID();

        CalendarRecoveryDtos.RestoreEventResponse rejected =
                collaborationService.rebookRestoredResource(
                        TENANT_ID,
                        OWNER_USER_ID,
                        OWNER_PERSON_ID,
                        null,
                        fixture.eventId(),
                        "corr-rebook-recurrence",
                        new CalendarRecoveryDtos.RestoreResourceBookingRequest(
                                currentEventVersion,
                                fixture.resourceId(),
                                restored.resources().getFirst().bookingVersion(),
                                idempotencyKey));

        assertThat(rejected.outcome())
                .isEqualTo(CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_UNAVAILABLE);
        assertThat(rejected.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.RESOURCE_POLICY_BLOCKED);
        assertThat(booking(fixture.eventId()))
                .containsEntry("booking_status", "CANCELLED")
                .containsEntry("cancelled_for_event_trash", true)
                .containsEntry("version", 1L);
        assertThat(commandCount(idempotencyKey)).isOne();
    }

    @Test
    void legacyTrashRowWithoutSeriesSnapshotFailsClosedWhenRecurringIsDisabled() {
        Fixture fixture = recurringFixture("legacy-null-snapshot");
        manageAsWorkplaceRoom(fixture, false);
        jdbc.update("""
                UPDATE cal_resource_bookings
                   SET booking_status = 'CANCELLED',
                       cancelled_for_event_trash = TRUE,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE tenant_id = ? AND event_id = ?
                """, OWNER_USER_ID, TENANT_ID, fixture.eventId());
        assertThat(jdbc.queryForObject("""
                SELECT trash_recurrence_pattern IS NULL
                  FROM cal_resource_bookings
                 WHERE tenant_id = ? AND event_id = ?
                """, Boolean.class, TENANT_ID, fixture.eventId())).isTrue();
        UUID idempotencyKey = UUID.randomUUID();

        CalendarRecoveryDtos.RestoreEventResponse rejected =
                collaborationService.rebookRestoredResource(
                        TENANT_ID,
                        OWNER_USER_ID,
                        OWNER_PERSON_ID,
                        null,
                        fixture.eventId(),
                        "corr-rebook-legacy-null",
                        new CalendarRecoveryDtos.RestoreResourceBookingRequest(
                                0L, fixture.resourceId(), 1L, idempotencyKey));

        assertThat(rejected.reason())
                .isEqualTo(CalendarRecoveryDtos.RestoreReason.RESOURCE_POLICY_BLOCKED);
        assertThat(booking(fixture.eventId()))
                .containsEntry("booking_status", "CANCELLED")
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

    @Test
    void recurrencePolicyAllowsUnmanagedRoomsAndFailsClosedWithoutTenantPolicy() {
        Fixture fixture = fixture("policy-resolution");
        WorkplaceRoomRecurrencePolicy policy = new WorkplaceRoomRecurrencePolicy(jdbc);

        assertThat(policy.allowsRecurring(TENANT_ID, fixture.resourceId())).isTrue();

        manageAsWorkplaceRoom(fixture, false);
        jdbc.update("DELETE FROM wp_tenant_policies WHERE tenant_id = ?", TENANT_ID);

        assertThat(policy.allowsRecurring(TENANT_ID, fixture.resourceId())).isFalse();
    }

    private Fixture fixture(String suffix) {
        return fixture(suffix, CalendarTypes.RecurrencePattern.NONE, null);
    }

    private Fixture recurringFixture(String suffix) {
        return fixture(
                suffix,
                CalendarTypes.RecurrencePattern.WEEKLY,
                null);
    }

    private Fixture fixture(
            String suffix,
            CalendarTypes.RecurrencePattern recurrence,
            LocalDate recurrenceUntil) {
        UUID calendarId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime startsAt = nextStart();
        if (recurrence != CalendarTypes.RecurrencePattern.NONE && recurrenceUntil == null) {
            recurrenceUntil = startsAt.toLocalDate().plusWeeks(2);
        }
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
                    recurrence_pattern, recurrence_interval, recurrence_until,
                    response_required, source_type, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Restore owner', 'Restore contract', 'Agenda',
                        'MEETING', ?, ?, 'UTC', 'CONFIRMED', 'DEFAULT',
                        ?, 1, ?, FALSE, 'NATIVE', ?, ?)
                """, eventId, TENANT_ID, calendarId, OWNER_USER_ID, OWNER_PERSON_ID,
                startsAt, startsAt.plusHours(1), recurrence.name(), recurrenceUntil,
                OWNER_USER_ID, OWNER_USER_ID);
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

    private OffsetDateTime nextStart() {
        return OffsetDateTime.now(ZoneOffset.UTC)
                .plusDays(2)
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
    }

    private void manageAsWorkplaceRoom(Fixture fixture, boolean allowRecurring) {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_sites (
                    site_id, tenant_id, site_code, name_ko, name_en, time_zone,
                    created_by, updated_by)
                VALUES (?, ?, ?, '복원 사이트', 'Restore site', 'UTC', ?, ?)
                """, siteId, TENANT_ID, "RESTORE-SITE-" + fixture.resourceId(),
                OWNER_USER_ID, OWNER_USER_ID);
        jdbc.update("""
                INSERT INTO wp_floors (
                    floor_id, tenant_id, site_id, floor_number, name_ko, name_en,
                    created_by, updated_by)
                VALUES (?, ?, ?, 1, '복원 층', 'Restore floor', ?, ?)
                """, floorId, TENANT_ID, siteId, OWNER_USER_ID, OWNER_USER_ID);
        jdbc.update("""
                INSERT INTO wp_resources (
                    tenant_id, floor_id, calendar_resource_id, resource_code,
                    name_ko, name_en, resource_type, booking_mode, lifecycle_state,
                    capacity, created_by, updated_by)
                VALUES (?, ?, ?, ?, '복원 회의실', 'Restore room', 'ROOM',
                        'RESERVABLE', 'AVAILABLE', 8, ?, ?)
                """, TENANT_ID, floorId, fixture.resourceId(),
                "RESTORE-WP-" + fixture.resourceId(), OWNER_USER_ID, OWNER_USER_ID);
        jdbc.update("""
                INSERT INTO wp_tenant_policies (tenant_id, allow_recurring, created_by, updated_by)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE
                    SET allow_recurring = EXCLUDED.allow_recurring,
                        updated_at = CURRENT_TIMESTAMP,
                        updated_by = EXCLUDED.updated_by
                """, TENANT_ID, allowRecurring, OWNER_USER_ID, OWNER_USER_ID);
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
