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
class CalendarP0GovernancePostgresIntegrationTest
        extends CalendarP0GovernancePostgresTestSupport {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");


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
        // The class keeps its explicit V192 migration assertions, while the behavioral
        // repository checks below must run against the schema required by the current
        // Calendar access and recurrence queries.
        flyway("377").migrate();
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

        CalendarRepository repository = CalendarP0GovernanceRepositoryFixture.roomRepository(jdbc, TENANT_A, resourceId);
        CalendarService calendarService = mock(CalendarService.class);
        RoomService service = new RoomService(
                calendarService,
                repository,
                new RoomRepository(jdbc),
                mock(RoomBookingPolicyService.class));
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
    void roomAvailabilityExcludesOnlyTheVerifiedCurrentEvent() {
        Fixture fixture = fixture("DEFAULT");
        UUID competingEventId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        insertEvent(
                TENANT_A,
                competingEventId,
                fixture.calendarId(),
                OWNER_USER_ID,
                OWNER_PERSON_ID,
                "Competing room booking",
                "DEFAULT",
                QUERY_FROM.plusHours(11),
                QUERY_FROM.plusHours(12));
        insertResource(TENANT_A, resourceId, "ELIGIBILITY-ROOM");
        insertBooking(TENANT_A, fixture.eventId(), resourceId);
        insertBooking(
                TENANT_A,
                competingEventId,
                resourceId,
                QUERY_FROM.plusHours(11),
                QUERY_FROM.plusHours(12));
        RoomRepository repository = new RoomRepository(jdbc);

        assertThat(repository.resourceOccupancy(
                TENANT_A,
                QUERY_FROM.plusHours(9),
                QUERY_FROM.plusHours(12),
                fixture.eventId())).singleElement()
                .extracting(RoomRepository.ResourceOccupancyRow::resourceId)
                .isEqualTo(resourceId);
        assertThat(repository.resourceOccupancy(
                TENANT_A,
                QUERY_FROM.plusHours(9),
                QUERY_FROM.plusHours(12),
                null)).hasSize(2);
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

}
