package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class CalendarP0GovernancePostgresIntegrationTest {

    private static final long TENANT_A = 9_120_001L;
    private static final long TENANT_B = 9_120_002L;
    private static final long OWNER_USER_ID = 9_121_001L;
    private static final long VIEWER_USER_ID = 9_121_002L;
    private static final long ATTENDEE_USER_ID = 9_121_003L;
    private static final long OUTSIDER_USER_ID = 9_121_004L;

    private static final UUID OWNER_PERSON_ID =
            UUID.fromString("91200000-0000-4000-8000-000000000001");
    private static final UUID VIEWER_PERSON_ID =
            UUID.fromString("91200000-0000-4000-8000-000000000002");
    private static final UUID ATTENDEE_PERSON_ID =
            UUID.fromString("91200000-0000-4000-8000-000000000003");
    private static final UUID OUTSIDER_PERSON_ID =
            UUID.fromString("91200000-0000-4000-8000-000000000004");

    private static final OffsetDateTime QUERY_FROM =
            OffsetDateTime.parse("2026-08-31T00:00:00Z");
    private static final OffsetDateTime QUERY_TO =
            OffsetDateTime.parse("2026-09-02T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static int resourceTenantConstraintCountBeforeV192;

    @BeforeAll
    static void migrateV192OverTheExistingV147Constraint() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        Flyway throughV191 = flyway("191");
        throughV191.clean();
        throughV191.migrate();
        jdbc = new JdbcTemplate(dataSource);
        resourceTenantConstraintCountBeforeV192 = constraintCount(
                "cal_resources", "uk_cal_resources_tenant_resource");

        flyway("192").migrate();
    }

    @BeforeEach
    void clearOwnedFixtures() {
        for (String table : List.of(
                "cal_event_occurrence_overrides",
                "cal_event_user_preferences",
                "cal_calendar_subscriptions",
                "cal_calendar_access_grants",
                "cal_resource_bookings",
                "cal_event_attendees",
                "cal_events",
                "cal_resources",
                "cal_calendars",
                "cal_event_tombstones",
                "cal_identity_links")) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
        }
    }

    @Test
    void v192MigratesCleanlyWithThePreexistingCompositeResourceConstraint() {
        assertThat(resourceTenantConstraintCountBeforeV192).isOne();
        assertThat(constraintCount("cal_resources", "uk_cal_resources_tenant_resource"))
                .isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM flyway_schema_history
                 WHERE version = '192' AND success
                """, Integer.class)).isOne();
    }

    @Test
    void v192CreatesGovernedCalendarSchemasAndValidatedCompositeTenantForeignKeys() {
        Map<String, List<String>> requiredColumns = Map.of(
                "cal_calendar_access_grants", List.of(
                        "grant_id", "tenant_id", "calendar_id", "principal_type",
                        "principal_person_public_id", "principal_group_ref", "access_level",
                        "can_view_private", "valid_until", "lifecycle_state", "version"),
                "cal_calendar_subscriptions", List.of(
                        "tenant_id", "person_public_id", "calendar_id", "selected", "favorite",
                        "display_order", "color_override", "version"),
                "cal_event_user_preferences", List.of(
                        "tenant_id", "person_public_id", "event_id", "starred", "hidden",
                        "version"),
                "cal_event_tombstones", List.of(
                        "tombstone_id", "tenant_id", "source_type", "source_ref",
                        "recurrence_id", "sequence", "deleted_at", "purge_after", "legal_hold"),
                "cal_event_occurrence_overrides", List.of(
                        "override_id", "tenant_id", "event_id", "original_starts_at",
                        "override_kind", "starts_at", "ends_at", "importance", "version"),
                "cal_events", List.of(
                        "importance", "deleted_at", "deleted_by", "deletion_reason",
                        "purge_after", "legal_hold"),
                "cal_calendars", List.of("owner_display_name", "subscription_policy"));

        requiredColumns.forEach((table, required) ->
                assertThat(columns(table)).as("columns on %s", table).containsAll(required));

        assertCompositeForeignKey(
                "cal_events", "fk_cal_events_tenant_calendar",
                "FOREIGN KEY (tenant_id, calendar_id)",
                "REFERENCES cal_calendars(tenant_id, calendar_id)");
        assertCompositeForeignKey(
                "cal_event_attendees", "fk_cal_attendees_tenant_event",
                "FOREIGN KEY (tenant_id, event_id)",
                "REFERENCES cal_events(tenant_id, event_id)");
        assertCompositeForeignKey(
                "cal_resource_bookings", "fk_cal_bookings_tenant_event",
                "FOREIGN KEY (tenant_id, event_id)",
                "REFERENCES cal_events(tenant_id, event_id)");
        assertCompositeForeignKey(
                "cal_resource_bookings", "fk_cal_bookings_tenant_resource",
                "FOREIGN KEY (tenant_id, resource_id)",
                "REFERENCES cal_resources(tenant_id, resource_id)");
        assertCompositeForeignKey(
                "cal_calendar_access_grants", "fk_cal_grants_tenant_calendar",
                "FOREIGN KEY (tenant_id, calendar_id)",
                "REFERENCES cal_calendars(tenant_id, calendar_id)");
        assertCompositeForeignKey(
                "cal_calendar_access_grants", "fk_cal_grants_tenant_person",
                "FOREIGN KEY (tenant_id, principal_person_public_id)",
                "REFERENCES cal_identity_links(tenant_id, person_public_id)");
        assertCompositeForeignKey(
                "cal_calendar_subscriptions", "fk_cal_subscriptions_tenant_person",
                "FOREIGN KEY (tenant_id, person_public_id)",
                "REFERENCES cal_identity_links(tenant_id, person_public_id)");
        assertCompositeForeignKey(
                "cal_calendar_subscriptions", "fk_cal_subscriptions_tenant_calendar",
                "FOREIGN KEY (tenant_id, calendar_id)",
                "REFERENCES cal_calendars(tenant_id, calendar_id)");
        assertCompositeForeignKey(
                "cal_event_user_preferences", "fk_cal_event_preferences_tenant_person",
                "FOREIGN KEY (tenant_id, person_public_id)",
                "REFERENCES cal_identity_links(tenant_id, person_public_id)");
        assertCompositeForeignKey(
                "cal_event_user_preferences", "fk_cal_event_preferences_tenant_event",
                "FOREIGN KEY (tenant_id, event_id)",
                "REFERENCES cal_events(tenant_id, event_id)");
        assertCompositeForeignKey(
                "cal_event_occurrence_overrides", "fk_cal_occurrence_overrides_tenant_event",
                "FOREIGN KEY (tenant_id, event_id)",
                "REFERENCES cal_events(tenant_id, event_id)");
    }

    @Test
    void compositeTenantForeignKeysRejectCrossTenantCalendarReferences() {
        UUID tenantACalendar = UUID.randomUUID();
        UUID tenantBCalendar = UUID.randomUUID();
        UUID tenantAEvent = UUID.randomUUID();
        UUID tenantBEvent = UUID.randomUUID();
        UUID tenantAResource = UUID.randomUUID();
        UUID tenantBResource = UUID.randomUUID();

        insertIdentity(TENANT_A, OWNER_USER_ID, OWNER_PERSON_ID);
        insertIdentity(TENANT_B, OWNER_USER_ID, VIEWER_PERSON_ID);
        insertCalendar(TENANT_A, tenantACalendar, OWNER_USER_ID, OWNER_PERSON_ID);
        insertCalendar(TENANT_B, tenantBCalendar, OWNER_USER_ID, VIEWER_PERSON_ID);
        insertEvent(TENANT_A, tenantAEvent, tenantACalendar, OWNER_USER_ID, OWNER_PERSON_ID,
                "Tenant A event", "DEFAULT");
        insertEvent(TENANT_B, tenantBEvent, tenantBCalendar, OWNER_USER_ID, VIEWER_PERSON_ID,
                "Tenant B event", "DEFAULT");
        insertResource(TENANT_A, tenantAResource, "RESOURCE-A");
        insertResource(TENANT_B, tenantBResource, "RESOURCE-B");

        assertForeignKeyViolation("fk_cal_events_tenant_calendar", () -> insertEvent(
                TENANT_A, UUID.randomUUID(), tenantBCalendar, OWNER_USER_ID, OWNER_PERSON_ID,
                "Cross-tenant event", "DEFAULT"));
        assertForeignKeyViolation("fk_cal_attendees_tenant_event", () -> jdbc.update("""
                INSERT INTO cal_event_attendees (
                    tenant_id, event_id, attendee_person_public_id,
                    attendee_email, attendee_name)
                VALUES (?, ?, ?, 'cross-tenant@example.com', 'Cross tenant')
                """, TENANT_A, tenantBEvent, OWNER_PERSON_ID));
        assertForeignKeyViolation("fk_cal_bookings_tenant_event", () -> insertBooking(
                TENANT_A, tenantBEvent, tenantAResource));
        assertForeignKeyViolation("fk_cal_bookings_tenant_resource", () -> insertBooking(
                TENANT_A, tenantAEvent, tenantBResource));
        assertForeignKeyViolation("fk_cal_grants_tenant_calendar", () -> jdbc.update("""
                INSERT INTO cal_calendar_access_grants (
                    tenant_id, calendar_id, principal_type,
                    access_level, created_by, updated_by)
                VALUES (?, ?, 'TENANT', 'VIEW_DETAILS', ?, ?)
                """, TENANT_A, tenantBCalendar, OWNER_USER_ID, OWNER_USER_ID));
        assertForeignKeyViolation("fk_cal_grants_tenant_person", () -> jdbc.update("""
                INSERT INTO cal_calendar_access_grants (
                    tenant_id, calendar_id, principal_type,
                    principal_person_public_id, access_level, created_by, updated_by)
                VALUES (?, ?, 'PERSON', ?, 'VIEW_DETAILS', ?, ?)
                """, TENANT_A, tenantACalendar, VIEWER_PERSON_ID,
                OWNER_USER_ID, OWNER_USER_ID));
        assertForeignKeyViolation("fk_cal_subscriptions_tenant_calendar", () -> jdbc.update("""
                INSERT INTO cal_calendar_subscriptions (
                    tenant_id, person_public_id, calendar_id)
                VALUES (?, ?, ?)
                """, TENANT_A, OWNER_PERSON_ID, tenantBCalendar));
        assertForeignKeyViolation("fk_cal_event_preferences_tenant_event", () -> jdbc.update("""
                INSERT INTO cal_event_user_preferences (
                    tenant_id, person_public_id, event_id, starred)
                VALUES (?, ?, ?, TRUE)
                """, TENANT_A, OWNER_PERSON_ID, tenantBEvent));
        assertForeignKeyViolation("fk_cal_occurrence_overrides_tenant_event", () -> jdbc.update("""
                INSERT INTO cal_event_occurrence_overrides (
                    tenant_id, event_id, original_starts_at,
                    override_kind, created_by, updated_by)
                VALUES (?, ?, ?, 'CANCELLED', ?, ?)
                """, TENANT_A, tenantBEvent, QUERY_FROM.plusHours(10),
                OWNER_USER_ID, OWNER_USER_ID));
    }

    @Test
    void visibleEventsExposeOnlyOwnerExplicitGrantOrEventAttendee() {
        Fixture fixture = fixture("DEFAULT");

        assertThat(visibleEvents(OWNER_USER_ID, OWNER_PERSON_ID))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.eventId()).isEqualTo(fixture.eventId());
                    assertThat(row.accessLevel()).isEqualTo("OWNER");
                    assertThat(row.detailLevel()).isEqualTo("FULL");
                });
        assertThat(visibleCalendarIds(OWNER_USER_ID, OWNER_PERSON_ID))
                .containsExactly(fixture.calendarId());
        assertThat(visibleEvents(OUTSIDER_USER_ID, OUTSIDER_PERSON_ID)).isEmpty();
        assertThat(visibleCalendarIds(OUTSIDER_USER_ID, OUTSIDER_PERSON_ID)).isEmpty();

        insertGrant(
                fixture.calendarId(), VIEWER_PERSON_ID, "VIEW_DETAILS", false,
                OffsetDateTime.now().plusDays(1));
        assertThat(visibleEvents(VIEWER_USER_ID, VIEWER_PERSON_ID))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.eventId()).isEqualTo(fixture.eventId());
                    assertThat(row.accessLevel()).isEqualTo("VIEW_DETAILS");
                    assertThat(row.detailLevel()).isEqualTo("FULL");
                });
        assertThat(visibleCalendarIds(VIEWER_USER_ID, VIEWER_PERSON_ID))
                .containsExactly(fixture.calendarId());

        jdbc.update("""
                INSERT INTO cal_event_attendees (
                    tenant_id, event_id, attendee_user_id,
                    attendee_person_public_id, attendee_email, attendee_name)
                VALUES (?, ?, ?, ?, 'attendee@example.com', 'Explicit attendee')
                """, TENANT_A, fixture.eventId(), ATTENDEE_USER_ID, ATTENDEE_PERSON_ID);
        assertThat(visibleEvents(ATTENDEE_USER_ID, ATTENDEE_PERSON_ID))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.eventId()).isEqualTo(fixture.eventId());
                    assertThat(row.accessLevel()).isEqualTo("EVENT_ATTENDEE");
                    assertThat(row.detailLevel()).isEqualTo("FULL");
                });
        assertThat(visibleCalendarIds(ATTENDEE_USER_ID, ATTENDEE_PERSON_ID)).isEmpty();
    }

    @Test
    void roomWritePreflightPreservesGroupGrantsAndFailsClosedAfterRevocation() {
        Fixture fixture = fixture("DEFAULT");
        UUID groupRef = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        insertResource(TENANT_A, resourceId, "GROUP-ROOM");
        insertBooking(TENANT_A, fixture.eventId(), resourceId);
        insertGroupGrant(fixture.calendarId(), groupRef, "EDIT");

        CalendarRepository repository = new CalendarRepository(jdbc, new ObjectMapper());
        CalendarService calendarService = mock(CalendarService.class);
        RoomService service = new RoomService(
                calendarService, repository, new RoomRepository(jdbc));
        CalendarDtos.UpdateEventRequest update = roomUpdate(resourceId);
        String groupHeader = groupRef.toString();

        assertThatThrownBy(() -> service.updateRoomBooking(
                TENANT_A, VIEWER_USER_ID, VIEWER_PERSON_ID, fixture.eventId(),
                "en-US", "corr-no-group", null, update))
                .isInstanceOf(BaseException.class);
        verify(calendarService, never()).update(
                TENANT_A, VIEWER_USER_ID, VIEWER_PERSON_ID, fixture.eventId(),
                "en-US", "corr-no-group", null, update);

        service.updateRoomBooking(
                TENANT_A, VIEWER_USER_ID, VIEWER_PERSON_ID, fixture.eventId(),
                "en-US", "corr-group-edit", groupHeader, update);
        verify(calendarService).update(
                TENANT_A, VIEWER_USER_ID, VIEWER_PERSON_ID, fixture.eventId(),
                "en-US", "corr-group-edit", groupHeader, update);

        jdbc.update("""
                UPDATE cal_calendar_access_grants
                   SET access_level = 'MANAGE', updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND calendar_id = ?
                   AND principal_type = 'GROUP' AND principal_group_ref = ?
                """, TENANT_A, fixture.calendarId(), groupRef);
        CalendarDtos.VersionRequest cancel = new CalendarDtos.VersionRequest(0L);
        service.cancelRoomBooking(
                TENANT_A, VIEWER_USER_ID, VIEWER_PERSON_ID, fixture.eventId(),
                "en-US", "corr-group-manage", groupHeader, cancel);
        verify(calendarService).cancel(
                TENANT_A, VIEWER_USER_ID, VIEWER_PERSON_ID, fixture.eventId(),
                "en-US", "corr-group-manage", groupHeader, cancel);

        jdbc.update("""
                UPDATE cal_calendar_access_grants
                   SET lifecycle_state = 'REVOKED', updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND calendar_id = ?
                   AND principal_type = 'GROUP' AND principal_group_ref = ?
                """, TENANT_A, fixture.calendarId(), groupRef);
        clearInvocations(calendarService);

        assertThatThrownBy(() -> service.updateRoomBooking(
                TENANT_A, VIEWER_USER_ID, VIEWER_PERSON_ID, fixture.eventId(),
                "en-US", "corr-revoked", groupHeader, update))
                .isInstanceOf(BaseException.class);
        verify(calendarService, never()).update(
                TENANT_A, VIEWER_USER_ID, VIEWER_PERSON_ID, fixture.eventId(),
                "en-US", "corr-revoked", groupHeader, update);
    }

    @Test
    void accessDecisionDoesNotRevealAnInaccessibleCalendar() {
        Fixture fixture = fixture("DEFAULT");

        assertThat(accessDecision(
                fixture.calendarId(), OWNER_USER_ID, OWNER_PERSON_ID))
                .containsExactly("OWNER");
        assertThat(accessDecision(
                fixture.calendarId(), OUTSIDER_USER_ID, OUTSIDER_PERSON_ID))
                .isEmpty();

        insertGrant(
                fixture.calendarId(), VIEWER_PERSON_ID, "VIEW_DETAILS", false,
                OffsetDateTime.now().plusDays(1));
        assertThat(accessDecision(
                fixture.calendarId(), VIEWER_USER_ID, VIEWER_PERSON_ID))
                .containsExactly("VIEW_DETAILS");
    }

    @Test
    void freeBusyDecisionRedactsPrivateDetailsUnlessTheGrantAllowsThem() {
        Fixture fixture = fixture("PRIVATE");
        insertGrant(
                fixture.calendarId(), VIEWER_PERSON_ID, "VIEW_DETAILS", false,
                OffsetDateTime.now().plusDays(1));

        assertThat(visibleEvents(VIEWER_USER_ID, VIEWER_PERSON_ID))
                .singleElement()
                .extracting(EventAccessRow::detailLevel)
                .isEqualTo("FREE_BUSY");

        jdbc.update("""
                UPDATE cal_calendar_access_grants
                   SET can_view_private = TRUE, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND calendar_id = ?
                   AND principal_person_public_id = ? AND lifecycle_state = 'ACTIVE'
                """, TENANT_A, fixture.calendarId(), VIEWER_PERSON_ID);
        assertThat(visibleEvents(VIEWER_USER_ID, VIEWER_PERSON_ID))
                .singleElement()
                .extracting(EventAccessRow::detailLevel)
                .isEqualTo("FULL");

        jdbc.update("""
                UPDATE cal_calendar_access_grants
                   SET access_level = 'VIEW_FREE_BUSY', updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND calendar_id = ?
                   AND principal_person_public_id = ? AND lifecycle_state = 'ACTIVE'
                """, TENANT_A, fixture.calendarId(), VIEWER_PERSON_ID);
        assertThat(visibleEvents(VIEWER_USER_ID, VIEWER_PERSON_ID))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.accessLevel()).isEqualTo("VIEW_FREE_BUSY");
                    assertThat(row.detailLevel()).isEqualTo("FREE_BUSY");
                });
    }

    @Test
    void deletedHiddenAndExpiredGrantRowsAreExcluded() {
        Fixture fixture = fixture("DEFAULT");
        insertGrant(
                fixture.calendarId(), VIEWER_PERSON_ID, "VIEW_DETAILS", false,
                OffsetDateTime.now().minusDays(1));

        assertThat(visibleEvents(VIEWER_USER_ID, VIEWER_PERSON_ID)).isEmpty();
        assertThat(visibleCalendarIds(VIEWER_USER_ID, VIEWER_PERSON_ID)).isEmpty();

        jdbc.update("""
                UPDATE cal_calendar_access_grants
                   SET valid_until = CURRENT_TIMESTAMP + INTERVAL '1 day'
                 WHERE tenant_id = ? AND calendar_id = ?
                   AND principal_person_public_id = ?
                """, TENANT_A, fixture.calendarId(), VIEWER_PERSON_ID);
        jdbc.update("""
                INSERT INTO cal_event_user_preferences (
                    tenant_id, person_public_id, event_id, hidden)
                VALUES (?, ?, ?, TRUE)
                """, TENANT_A, VIEWER_PERSON_ID, fixture.eventId());

        assertThat(visibleEvents(VIEWER_USER_ID, VIEWER_PERSON_ID)).isEmpty();
        assertThat(visibleEvents(OWNER_USER_ID, OWNER_PERSON_ID))
                .extracting(EventAccessRow::eventId)
                .containsExactly(fixture.eventId());

        jdbc.update("""
                UPDATE cal_events
                   SET deleted_at = CURRENT_TIMESTAMP,
                       deleted_by = ?, deletion_reason = 'P0 test deletion',
                       purge_after = CURRENT_TIMESTAMP + INTERVAL '30 days'
                 WHERE tenant_id = ? AND event_id = ?
                """, OWNER_USER_ID, TENANT_A, fixture.eventId());

        assertThat(visibleEvents(OWNER_USER_ID, OWNER_PERSON_ID)).isEmpty();
    }

    @Test
    void systemCalendarOrganizerDoesNotRetainGeneralMutationAuthority() {
        insertIdentity(TENANT_A, OWNER_USER_ID, OWNER_PERSON_ID);
        UUID calendarId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertSystemCalendar(TENANT_A, calendarId);
        insertEvent(
                TENANT_A, eventId, calendarId, OWNER_USER_ID, OWNER_PERSON_ID,
                "Company town hall", "DEFAULT");
        insertTenantGrant(calendarId, "VIEW_DETAILS");

        assertThat(visibleEvents(OWNER_USER_ID, OWNER_PERSON_ID))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.eventId()).isEqualTo(eventId);
                    assertThat(row.accessLevel()).isEqualTo("VIEW_DETAILS");
                    assertThat(row.detailLevel()).isEqualTo("FULL");
                });

        CalendarCollaborationRepository.EventDecision decision =
                new CalendarCollaborationRepository(jdbc)
                        .eventDecision(
                                TENANT_A,
                                OWNER_USER_ID,
                                OWNER_PERSON_ID,
                                new UUID[0],
                                eventId)
                        .orElseThrow();
        assertThat(decision.organizer()).isFalse();
        assertThat(decision.accessLevel()).isEqualTo("VIEW_DETAILS");
        assertThat(decision.canManage()).isFalse();
        assertThat(decision.canEdit()).isFalse();
        assertThat(decision.canViewDetails()).isTrue();
    }

    @Test
    void deletedEventsDoNotContinueBlockingFreeBusy() {
        Fixture fixture = fixture("DEFAULT");
        CalendarRepository repository = new CalendarRepository(jdbc, new ObjectMapper());

        assertThat(repository.busySlots(
                TENANT_A, List.of(OWNER_PERSON_ID), QUERY_FROM, QUERY_TO)).hasSize(1);

        jdbc.update("""
                UPDATE cal_events
                   SET deleted_at = CURRENT_TIMESTAMP,
                       deleted_by = ?,
                       purge_after = CURRENT_TIMESTAMP + INTERVAL '30 days'
                 WHERE tenant_id = ? AND event_id = ?
                """, OWNER_USER_ID, TENANT_A, fixture.eventId());

        assertThat(repository.busySlots(
                TENANT_A, List.of(OWNER_PERSON_ID), QUERY_FROM, QUERY_TO)).isEmpty();
    }

    @Test
    void firstPersonShareStartsAtVersionOneAndRejectsARepeatedCreateVersion() {
        Fixture fixture = fixture("DEFAULT");
        CalendarCollaborationRepository repository = new CalendarCollaborationRepository(jdbc);

        CalendarCollaborationRepository.ShareRow created = repository.upsertPersonShare(
                TENANT_A,
                OWNER_USER_ID,
                fixture.calendarId(),
                VIEWER_PERSON_ID,
                "Explicit viewer",
                "VIEW_DETAILS",
                false,
                null,
                0L).orElseThrow();
        assertThat(created.version()).isEqualTo(1L);

        assertThat(repository.upsertPersonShare(
                TENANT_A,
                OWNER_USER_ID,
                fixture.calendarId(),
                VIEWER_PERSON_ID,
                "Repeated viewer",
                "EDIT",
                false,
                null,
                0L)).isEmpty();

        assertThat(repository.upsertPersonShare(
                TENANT_A,
                OWNER_USER_ID,
                fixture.calendarId(),
                VIEWER_PERSON_ID,
                "Updated viewer",
                "EDIT",
                false,
                null,
                1L)).get().extracting(CalendarCollaborationRepository.ShareRow::version)
                .isEqualTo(2L);
    }

    @Test
    void companyCalendarCreationKeepsBilingualNamesAndEnforcesTenantReadOnlyAccess() {
        insertIdentity(TENANT_A, OWNER_USER_ID, OWNER_PERSON_ID);
        insertIdentity(TENANT_A, VIEWER_USER_ID, VIEWER_PERSON_ID);
        CompanyCalendarAdminRepository repository = new CompanyCalendarAdminRepository(jdbc);

        UUID calendarId = repository.insertCalendar(
                TENANT_A,
                OWNER_USER_ID,
                new CalendarDtos.CompanyCalendarRequest(
                        "company-milestones",
                        "회사 주요 일정",
                        "Company milestones",
                        "#2563EB",
                        0L));

        assertThat(repository.calendars(TENANT_A, true)).singleElement().satisfies(calendar -> {
            assertThat(calendar.calendarId()).isEqualTo(calendarId);
            assertThat(calendar.name()).isEqualTo("회사 주요 일정");
            assertThat(calendar.nameKo()).isEqualTo("회사 주요 일정");
            assertThat(calendar.nameEn()).isEqualTo("Company milestones");
        });
        assertThat(accessDecision(calendarId, VIEWER_USER_ID, VIEWER_PERSON_ID))
                .containsExactly("VIEW_DETAILS");

        jdbc.update("""
                UPDATE cal_calendar_access_grants
                   SET access_level = 'MANAGE', can_view_private = TRUE
                 WHERE tenant_id = ? AND calendar_id = ? AND principal_type = 'TENANT'
                """, TENANT_A, calendarId);
        assertThat(repository.updateCalendar(
                TENANT_A,
                OWNER_USER_ID,
                calendarId,
                new CalendarDtos.CompanyCalendarRequest(
                        "company-milestones",
                        "회사 공통 일정",
                        "Company-wide calendar",
                        "#0F766E",
                        0L))).isEqualTo(1);

        Map<String, Object> grant = jdbc.queryForMap("""
                SELECT access_level, can_view_private
                  FROM cal_calendar_access_grants
                 WHERE tenant_id = ? AND calendar_id = ? AND principal_type = 'TENANT'
                """, TENANT_A, calendarId);
        assertThat(grant).containsEntry("access_level", "VIEW_DETAILS")
                .containsEntry("can_view_private", false);
        assertThat(repository.calendars(TENANT_A, false)).singleElement().satisfies(calendar -> {
            assertThat(calendar.name()).isEqualTo("Company-wide calendar");
            assertThat(calendar.nameKo()).isEqualTo("회사 공통 일정");
            assertThat(calendar.nameEn()).isEqualTo("Company-wide calendar");
            assertThat(calendar.version()).isEqualTo(1L);
        });
    }

    @Test
    void trashRestorePurgeAndLegalHoldPreserveTheRetentionContract() {
        Fixture fixture = fixture("DEFAULT");
        CalendarCollaborationRepository collaboration =
                new CalendarCollaborationRepository(jdbc);
        CalendarRetentionRepository retention = new CalendarRetentionRepository(jdbc);

        CalendarCollaborationRepository.EventMutation trashed = collaboration.trashEvent(
                TENANT_A, OWNER_USER_ID, fixture.eventId(), "Retention integration", 0L)
                .orElseThrow();
        retention.recordTombstone(TENANT_A, fixture.eventId());
        assertThat(trashed.version()).isEqualTo(1L);
        assertThat(trashed.deletedAt()).isNotNull();
        assertThat(trashed.purgeAfter()).isNotNull();
        assertThat(tombstoneCount(fixture.eventId())).isEqualTo(1);

        CalendarCollaborationRepository.EventMutation restored = collaboration.restoreEvent(
                TENANT_A, OWNER_USER_ID, fixture.eventId(), trashed.version()).orElseThrow();
        retention.removeTombstone(TENANT_A, fixture.eventId());
        assertThat(restored.version()).isEqualTo(2L);
        assertThat(restored.deletedAt()).isNull();
        assertThat(tombstoneCount(fixture.eventId())).isZero();

        CalendarCollaborationRepository.EventMutation expired = collaboration.trashEvent(
                TENANT_A, OWNER_USER_ID, fixture.eventId(), "Expired retention", restored.version())
                .orElseThrow();
        jdbc.update("""
                UPDATE cal_events
                   SET purge_after = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                 WHERE tenant_id = ? AND event_id = ?
                """, TENANT_A, fixture.eventId());
        retention.recordTombstone(TENANT_A, fixture.eventId());
        assertThat(expired.version()).isEqualTo(3L);
        assertThat(retention.purgeExpiredEvents()).isEqualTo(1);
        assertThat(eventCount(fixture.eventId())).isZero();
        assertThat(tombstoneCount(fixture.eventId())).isEqualTo(1);

        UUID heldCalendarId = UUID.randomUUID();
        UUID heldEventId = UUID.randomUUID();
        insertCalendar(TENANT_A, heldCalendarId, VIEWER_USER_ID, VIEWER_PERSON_ID);
        insertEvent(
                TENANT_A, heldEventId, heldCalendarId, VIEWER_USER_ID, VIEWER_PERSON_ID,
                "Legal hold event", "DEFAULT");
        jdbc.update("""
                UPDATE cal_events SET legal_hold = TRUE
                 WHERE tenant_id = ? AND event_id = ?
                """, TENANT_A, heldEventId);
        CalendarCollaborationRepository.EventMutation held = collaboration.trashEvent(
                TENANT_A, VIEWER_USER_ID, heldEventId, "Legal hold", 0L).orElseThrow();
        retention.recordTombstone(TENANT_A, heldEventId);

        assertThat(held.legalHold()).isTrue();
        assertThat(held.purgeAfter()).isNull();
        assertThat(retention.purgeExpiredEvents()).isZero();
        assertThat(eventCount(heldEventId)).isEqualTo(1);
        assertThat(tombstoneCount(heldEventId)).isEqualTo(1);
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    private static int constraintCount(String table, String constraint) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_constraint constraint_row
                  JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
                  JOIN pg_namespace namespace_row ON namespace_row.oid = table_row.relnamespace
                 WHERE namespace_row.nspname = CURRENT_SCHEMA()
                   AND table_row.relname = ? AND constraint_row.conname = ?
                """, Integer.class, table, constraint);
    }

    private int eventCount(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM cal_events WHERE tenant_id = ? AND event_id = ?",
                Integer.class,
                TENANT_A,
                eventId);
    }

    private int tombstoneCount(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM cal_event_tombstones
                 WHERE tenant_id = ? AND source_ref = ?
                """, Integer.class, TENANT_A, eventId.toString());
    }

    private Set<String> columns(String table) {
        return Set.copyOf(jdbc.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = CURRENT_SCHEMA() AND table_name = ?
                """, String.class, table));
    }

    private void assertCompositeForeignKey(
            String table,
            String constraint,
            String sourceColumns,
            String targetColumns) {
        Map<String, Object> metadata = jdbc.queryForMap("""
                SELECT pg_get_constraintdef(constraint_row.oid) AS definition,
                       constraint_row.convalidated AS validated
                  FROM pg_constraint constraint_row
                  JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
                  JOIN pg_namespace namespace_row ON namespace_row.oid = table_row.relnamespace
                 WHERE namespace_row.nspname = CURRENT_SCHEMA()
                   AND table_row.relname = ? AND constraint_row.conname = ?
                """, table, constraint);
        assertThat(metadata.get("definition").toString())
                .contains(sourceColumns)
                .contains(targetColumns);
        assertThat(metadata.get("validated")).isEqualTo(true);
    }

    private void assertForeignKeyViolation(String constraint, Runnable command) {
        assertThatThrownBy(command::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining(constraint);
    }

    private Fixture fixture(String visibility) {
        insertIdentity(TENANT_A, OWNER_USER_ID, OWNER_PERSON_ID);
        insertIdentity(TENANT_A, VIEWER_USER_ID, VIEWER_PERSON_ID);
        insertIdentity(TENANT_A, ATTENDEE_USER_ID, ATTENDEE_PERSON_ID);
        insertIdentity(TENANT_A, OUTSIDER_USER_ID, OUTSIDER_PERSON_ID);
        UUID calendarId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertCalendar(TENANT_A, calendarId, OWNER_USER_ID, OWNER_PERSON_ID);
        insertEvent(
                TENANT_A, eventId, calendarId, OWNER_USER_ID, OWNER_PERSON_ID,
                "Confidential strategy", visibility);
        return new Fixture(calendarId, eventId);
    }

    private void insertIdentity(long tenantId, long userId, UUID personId) {
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id)
                VALUES (?, ?, ?)
                """, tenantId, userId, personId);
    }

    private void insertCalendar(
            long tenantId,
            UUID calendarId,
            long ownerUserId,
            UUID ownerPersonId) {
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, owner_user_id,
                    owner_person_public_id, owner_display_name, name_ko, name_en,
                    calendar_type, visibility, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Calendar owner', '개인 캘린더', 'Personal calendar',
                        'PERSONAL', 'DETAILS', 'ACTIVE', ?, ?)
                """, calendarId, tenantId, "personal-" + calendarId,
                ownerUserId, ownerPersonId, ownerUserId, ownerUserId);
    }

    private void insertSystemCalendar(long tenantId, UUID calendarId) {
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, name_ko, name_en,
                    calendar_type, visibility, subscription_policy,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, '회사 캘린더', 'Company calendar',
                        'SYSTEM', 'DETAILS', 'REQUIRED', 'ACTIVE', ?, ?)
                """, calendarId, tenantId, "company-" + calendarId,
                OWNER_USER_ID, OWNER_USER_ID);
    }

    private void insertEvent(
            long tenantId,
            UUID eventId,
            UUID calendarId,
            long organizerUserId,
            UUID organizerPersonId,
            String title,
            String visibility) {
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, title, event_type,
                    starts_at, ends_at, time_zone, status, visibility,
                    recurrence_pattern, recurrence_interval, response_required,
                    source_type, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Calendar owner', ?, 'MEETING',
                        ?, ?, 'UTC', 'CONFIRMED', ?, 'NONE', 1, FALSE,
                        'NATIVE', ?, ?)
                """, eventId, tenantId, calendarId, organizerUserId, organizerPersonId,
                title, QUERY_FROM.plusHours(10), QUERY_FROM.plusHours(11), visibility,
                organizerUserId, organizerUserId);
    }

    private void insertResource(long tenantId, UUID resourceId, String code) {
        jdbc.update("""
                INSERT INTO cal_resources (
                    resource_id, tenant_id, resource_code, name_ko, name_en,
                    resource_type, site_name, capacity, time_zone,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, '테스트 회의실', 'Test room', 'ROOM',
                        'Test site', 8, 'UTC', 'AVAILABLE', ?, ?)
                """, resourceId, tenantId, code, OWNER_USER_ID, OWNER_USER_ID);
    }

    private void insertBooking(long tenantId, UUID eventId, UUID resourceId) {
        jdbc.update("""
                INSERT INTO cal_resource_bookings (
                    tenant_id, event_id, resource_id, starts_at, ends_at,
                    booking_status, requested_by, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)
                """, tenantId, eventId, resourceId,
                QUERY_FROM.plusHours(10), QUERY_FROM.plusHours(11),
                OWNER_USER_ID, OWNER_USER_ID, OWNER_USER_ID);
    }

    private void insertGrant(
            UUID calendarId,
            UUID personId,
            String accessLevel,
            boolean canViewPrivate,
            OffsetDateTime validUntil) {
        jdbc.update("""
                INSERT INTO cal_calendar_access_grants (
                    tenant_id, calendar_id, principal_type,
                    principal_person_public_id, principal_display_name,
                    access_level, can_view_private, valid_until,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, 'PERSON', ?, 'Explicit viewer', ?, ?, ?, 'ACTIVE', ?, ?)
                """, TENANT_A, calendarId, personId, accessLevel,
                canViewPrivate, validUntil, OWNER_USER_ID, OWNER_USER_ID);
    }

    private void insertTenantGrant(UUID calendarId, String accessLevel) {
        jdbc.update("""
                INSERT INTO cal_calendar_access_grants (
                    tenant_id, calendar_id, principal_type,
                    access_level, can_view_private, lifecycle_state,
                    created_by, updated_by)
                VALUES (?, ?, 'TENANT', ?, FALSE, 'ACTIVE', ?, ?)
                """, TENANT_A, calendarId, accessLevel, OWNER_USER_ID, OWNER_USER_ID);
    }

    private void insertGroupGrant(UUID calendarId, UUID groupRef, String accessLevel) {
        jdbc.update("""
                INSERT INTO cal_calendar_access_grants (
                    tenant_id, calendar_id, principal_type, principal_group_ref,
                    principal_display_name, access_level, can_view_private,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, 'GROUP', ?, 'Calendar editors', ?, FALSE, 'ACTIVE', ?, ?)
                """, TENANT_A, calendarId, groupRef, accessLevel,
                OWNER_USER_ID, OWNER_USER_ID);
    }

    private CalendarDtos.UpdateEventRequest roomUpdate(UUID resourceId) {
        return new CalendarDtos.UpdateEventRequest(
                "Group room review",
                "Decision agenda",
                CalendarTypes.EventType.MEETING,
                QUERY_FROM.plusHours(10),
                QUERY_FROM.plusHours(11),
                "UTC",
                false,
                "Test room",
                null,
                CalendarTypes.EventVisibility.DEFAULT,
                CalendarTypes.RecurrencePattern.NONE,
                1,
                (LocalDate) null,
                false,
                List.of(),
                resourceId,
                0L);
    }

    private List<UUID> visibleCalendarIds(long viewerUserId, UUID viewerPersonId) {
        return jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(CalendarAccessSql.CALENDARS);
            statement.setLong(1, viewerUserId);
            statement.setObject(2, viewerPersonId);
            statement.setArray(3, connection.createArrayOf("uuid", new UUID[0]));
            statement.setBoolean(4, false);
            statement.setLong(5, TENANT_A);
            return statement;
        }, (result, ignored) -> result.getObject("calendar_id", UUID.class));
    }

    private List<String> accessDecision(
            UUID calendarId, long viewerUserId, UUID viewerPersonId) {
        return jdbc.query(connection -> {
            PreparedStatement statement =
                    connection.prepareStatement(CalendarAccessSql.ACCESS_DECISION);
            statement.setLong(1, viewerUserId);
            statement.setObject(2, viewerPersonId);
            statement.setArray(3, connection.createArrayOf("uuid", new UUID[0]));
            statement.setLong(4, TENANT_A);
            statement.setObject(5, calendarId);
            return statement;
        }, (result, ignored) -> result.getString("access_level"));
    }

    private List<EventAccessRow> visibleEvents(long viewerUserId, UUID viewerPersonId) {
        return jdbc.query(connection -> {
            PreparedStatement statement =
                    connection.prepareStatement(CalendarAccessSql.VISIBLE_EVENTS);
            statement.setLong(1, viewerUserId);
            statement.setObject(2, viewerPersonId);
            statement.setArray(3, connection.createArrayOf("uuid", new UUID[0]));
            statement.setBoolean(4, false);
            statement.setBoolean(5, false);
            statement.setLong(6, TENANT_A);
            statement.setObject(7, QUERY_TO);
            statement.setObject(8, QUERY_FROM);
            statement.setObject(9, QUERY_TO);
            statement.setObject(10, QUERY_FROM);
            return statement;
        }, (result, ignored) -> new EventAccessRow(
                result.getObject("event_id", UUID.class),
                result.getString("access_level"),
                result.getString("detail_level")));
    }

    private record Fixture(UUID calendarId, UUID eventId) {
    }

    private record EventAccessRow(UUID eventId, String accessLevel, String detailLevel) {
    }
}
