package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.dwp.services.platform.calendar.CalendarTeamAvailabilityDtos.*;
import static org.assertj.core.api.Assertions.*;

/** Runs against the actual existing Calendar schema; no application migration is added. */
@Testcontainers(disabledWithoutDocker = true)
class CalendarTeamAvailabilityPostgresIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicLong TENANTS = new AtomicLong(9_410_000);
    private static final UUID VIEWER = UUID.fromString("94100000-0000-4000-8000-000000000001");
    private static final UUID MEMBER = UUID.fromString("94100000-0000-4000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("94100000-0000-4000-8000-000000000003");
    private static final UUID GROUP = UUID.fromString("94100000-0000-4000-8000-000000000004");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-04T00:40:00Z");
    private static JdbcTemplate jdbc;
    private long tenant;
    private UUID calendar;
    private CalendarTeamAvailabilityAccess.Actor actor;
    private CalendarTeamAvailabilityRepository repository;
    private CalendarTeamAvailabilityService service;

    @BeforeAll
    static void migrateExistingSchema() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("196").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void fixture() {
        // New tenant per test, all inside an isolated disposable Testcontainers database.
        tenant = TENANTS.incrementAndGet();
        identity(tenant, 101, VIEWER);
        identity(tenant, 102, MEMBER);
        identity(tenant, 103, OTHER);
        calendar = calendar(tenant, 102, MEMBER);
        actor = new CalendarTeamAvailabilityAccess.Actor(tenant, 101, VIEWER, Set.of());
        repository = new CalendarTeamAvailabilityRepository(jdbc);
        service = new CalendarTeamAvailabilityService(repository, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    @Test
    void directoryIdentityOrTenantGrantAloneNeverAuthorizesAListOrBusyRead() {
        event(tenant, calendar, 102, MEMBER, "FOCUS", "PRIVATE", NOW.minusMinutes(40), NOW.plusMinutes(20));
        assertThat(snapshot().members()).isEmpty();
        assertThat(repository.schedules(actor, NOW, List.of(MEMBER), NOW.minusHours(1), NOW.plusHours(2), 4001)).isEmpty();
        UUID grant = grant(tenant, calendar, "TENANT", null, "VIEW_DETAILS", true, null);
        assertThat(snapshot().members()).isEmpty();
        jdbc.update("UPDATE cal_calendar_access_grants SET lifecycle_state = 'REVOKED' WHERE grant_id = ?", grant);
        grant = grant(tenant, calendar, "PERSON", VIEWER, "VIEW_DETAILS", true, NOW.minusSeconds(1));
        assertThat(snapshot().members()).isEmpty();
        jdbc.update("UPDATE cal_calendar_access_grants SET valid_until = NULL, lifecycle_state = 'REVOKED' WHERE grant_id = ?", grant);
        assertThat(snapshot().members()).isEmpty();
    }

    @Test
    void explicitPersonShareRedactsPrivateAndFreeBusyTypesAndAllEventContent() throws Exception {
        UUID event = event(tenant, calendar, 102, MEMBER, "FOCUS", "PRIVATE", NOW.minusMinutes(40), NOW.plusMinutes(20));
        UUID grant = grant(tenant, calendar, "PERSON", VIEWER, "VIEW_DETAILS", false, null);
        assertThat(snapshot().members().getFirst().status()).isEqualTo(Status.BUSY);
        jdbc.update("UPDATE cal_calendar_access_grants SET can_view_private = TRUE WHERE grant_id = ?", grant);
        assertThat(snapshot().members().getFirst().status()).isEqualTo(Status.FOCUS);
        jdbc.update("UPDATE cal_calendar_access_grants SET can_view_private = FALSE, access_level = 'VIEW_FREE_BUSY' WHERE grant_id = ?", grant);
        Snapshot masked = snapshot();
        assertThat(masked.members().getFirst().status()).isEqualTo(Status.BUSY);
        assertThat(masked.members().getFirst().busyMinutes()).isEqualTo(60);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(masked);
        assertThat(json).doesNotContain("TOP_SECRET_TITLE", "SECRET_DESCRIPTION", "secret.example", "private@example", event.toString(), "organizer", "attendees", "location", "conferenceUrl", "online");
    }

    @Test
    void groupMembershipMustBeVerifiedAndRevocationReplacesPreviouslyVisibleMembers() {
        grant(tenant, calendar, "GROUP", GROUP, "VIEW_FREE_BUSY", false, null);
        assertThat(snapshot().members()).isEmpty();
        var groupActor = new CalendarTeamAvailabilityAccess.Actor(tenant, 101, VIEWER, Set.of(GROUP));
        assertThat(service.snapshot(groupActor, "Asia/Seoul").members()).extracting(Member::personPublicId).containsExactly(MEMBER);
        jdbc.update("UPDATE cal_calendar_access_grants SET lifecycle_state = 'REVOKED' WHERE tenant_id = ? AND calendar_id = ?", tenant, calendar);
        assertThat(service.snapshot(groupActor, "Asia/Seoul").members()).isEmpty();
        assertThat(repository.schedules(groupActor, NOW, List.of(MEMBER), NOW.minusHours(1), NOW.plusHours(1), 4001)).isEmpty();
    }

    @Test
    void identityMustMatchTheTenantAndAuthenticatedUserBeforeAnyAvailabilityIsReturned() {
        grant(tenant, calendar, "PERSON", VIEWER, "VIEW_DETAILS", false, null);
        for (var badActor : List.of(
                new CalendarTeamAvailabilityAccess.Actor(tenant + 100_000, 101, VIEWER, Set.of()),
                new CalendarTeamAvailabilityAccess.Actor(tenant, 102, VIEWER, Set.of()),
                new CalendarTeamAvailabilityAccess.Actor(tenant, 101, OTHER, Set.of()))) {
            assertThat(repository.members(badActor, NOW, 21)).isEmpty();
            assertThatThrownBy(() -> service.snapshot(badActor, "Asia/Seoul"))
                    .isInstanceOfSatisfying(BaseException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
        long neighbor = tenant + 100_000;
        identity(neighbor, 101, VIEWER);
        identity(neighbor, 102, MEMBER);
        UUID neighborCalendar = calendar(neighbor, 102, MEMBER);
        grant(neighbor, neighborCalendar, "PERSON", VIEWER, "VIEW_DETAILS", true, null);
        event(neighbor, neighborCalendar, 102, MEMBER, "FOCUS", "DEFAULT", NOW.minusMinutes(5), NOW.plusHours(2));
        assertThat(snapshot().members().getFirst().busyMinutes()).isZero();
        assertThat(snapshot().members().getFirst().status()).isEqualTo(Status.AVAILABLE);
    }

    @Test
    void cancelledTrashedArchivedAndDeclinedEventsDoNotMarkAMemberBusy() {
        grant(tenant, calendar, "PERSON", VIEWER, "VIEW_FREE_BUSY", false, null);
        UUID cancelled = event(tenant, calendar, 102, MEMBER, "MEETING", "DEFAULT", NOW.minusMinutes(10), NOW.plusMinutes(30));
        jdbc.update("UPDATE cal_events SET status = 'CANCELLED' WHERE event_id = ?", cancelled);
        UUID trashed = event(tenant, calendar, 102, MEMBER, "MEETING", "DEFAULT", NOW.minusMinutes(10), NOW.plusMinutes(30));
        jdbc.update("UPDATE cal_events SET deleted_at = ?, deleted_by = 102, purge_after = ? WHERE event_id = ?", NOW, NOW.plusDays(30), trashed);
        UUID archived = calendar(tenant, 102, MEMBER, "TEAM");
        event(tenant, archived, 102, MEMBER, "MEETING", "DEFAULT", NOW.minusMinutes(10), NOW.plusMinutes(30));
        jdbc.update("UPDATE cal_calendars SET lifecycle_state = 'ARCHIVED' WHERE calendar_id = ?", archived);
        UUID unrelatedCalendar = calendar(tenant, 103, OTHER);
        UUID invitation = event(tenant, unrelatedCalendar, 103, OTHER, "FOCUS", "PRIVATE", NOW.minusMinutes(10), NOW.plusMinutes(30));
        jdbc.update("""
                INSERT INTO cal_event_attendees (tenant_id, event_id, attendee_user_id, attendee_person_public_id,
                    attendee_email, attendee_name, attendee_type, response_status)
                VALUES (?, ?, 102, ?, 'member@example.test', 'Member', 'REQUIRED', 'DECLINED')
                """, tenant, invitation, MEMBER);
        assertThat(snapshot().members().getFirst().busyMinutes()).isZero();
        jdbc.update("UPDATE cal_event_attendees SET response_status = 'ACCEPTED' WHERE tenant_id = ? AND event_id = ?", tenant, invitation);
        assertThat(snapshot().members().getFirst().status()).isEqualTo(Status.BUSY);
        assertThat(snapshot().members().getFirst().busyMinutes()).isEqualTo(40);
    }

    @Test
    void shortLivedDetailGrantsCannotKeepARevealedFocusClassificationAfterExpiry() {
        event(tenant, calendar, 102, MEMBER, "FOCUS", "DEFAULT", NOW.minusMinutes(10), NOW.plusMinutes(30));
        grant(tenant, calendar, "PERSON", VIEWER, "VIEW_DETAILS", false, NOW.plusSeconds(5));
        Snapshot snapshot = snapshot();
        assertThat(snapshot.validUntil()).isEqualTo(NOW.plusSeconds(5));
        assertThat(snapshot.members().getFirst().status()).isEqualTo(Status.BUSY);
    }

    @Test
    void recurrenceOverridesMoveAndCancelBusyTimeWithoutLeakingTheirEventIds() {
        grant(tenant, calendar, "PERSON", VIEWER, "VIEW_FREE_BUSY", false, null);
        OffsetDateTime start = NOW.minusDays(2).minusMinutes(40);
        UUID event = event(tenant, calendar, 102, MEMBER, "MEETING", "DEFAULT", start, start.plusHours(1));
        jdbc.update("UPDATE cal_events SET recurrence_pattern = 'DAILY' WHERE event_id = ?", event);
        jdbc.update("""
                INSERT INTO cal_event_occurrence_overrides (tenant_id, event_id, original_starts_at, override_kind, created_by, updated_by)
                VALUES (?, ?, ?, 'CANCELLED', 102, 102)
                """, tenant, event, NOW.minusMinutes(40));
        jdbc.update("""
                INSERT INTO cal_event_occurrence_overrides (tenant_id, event_id, original_starts_at, override_kind,
                    starts_at, ends_at, created_by, updated_by)
                VALUES (?, ?, ?, 'MODIFIED', ?, ?, 102, 102)
                """, tenant, event, start, NOW.plusHours(2), NOW.plusHours(2).plusMinutes(30));
        Member member = snapshot().members().getFirst();
        assertThat(member.status()).isEqualTo(Status.AVAILABLE);
        assertThat(member.busyMinutes()).isEqualTo(30);
        assertThat(member.busyWindows()).hasSize(1);
    }

    private Snapshot snapshot() { return service.snapshot(actor, "Asia/Seoul"); }

    private void identity(long tenantId, long userId, UUID person) {
        jdbc.update("INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id) VALUES (?, ?, ?)", tenantId, userId, person);
    }

    private UUID calendar(long tenantId, long owner, UUID person) {
        return calendar(tenantId, owner, person, "PERSONAL");
    }

    private UUID calendar(long tenantId, long owner, UUID person, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_calendars (calendar_id, tenant_id, calendar_key, owner_user_id, owner_person_public_id,
                    owner_display_name, name_ko, name_en, calendar_type, visibility)
                VALUES (?, ?, ?, ?, ?, 'Shared member', '개인', 'Personal', ?, 'DETAILS')
                """, id, tenantId, "team-test-" + id, owner, person, type);
        return id;
    }

    private UUID grant(long tenantId, UUID calendarId, String principal, UUID target,
                       String level, boolean privateDetails, OffsetDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_calendar_access_grants (grant_id, tenant_id, calendar_id, principal_type,
                    principal_person_public_id, principal_group_ref, access_level, can_view_private, valid_until, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 102, 102)
                """, id, tenantId, calendarId, principal,
                "PERSON".equals(principal) ? target : null, "GROUP".equals(principal) ? target : null,
                level, privateDetails, expiresAt);
        return id;
    }

    private UUID event(long tenantId, UUID calendarId, long organizer, UUID person, String type,
                       String visibility, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_events (event_id, tenant_id, calendar_id, organizer_user_id, organizer_person_public_id,
                    organizer_name, organizer_email, title, description, conference_url, event_type, visibility, starts_at, ends_at)
                VALUES (?, ?, ?, ?, ?, 'Organizer', 'private@example.test', 'TOP_SECRET_TITLE', 'SECRET_DESCRIPTION',
                    'https://secret.example/meeting', ?, ?, ?, ?)
                """, id, tenantId, calendarId, organizer, person, type, visibility, startsAt, endsAt);
        return id;
    }
}
