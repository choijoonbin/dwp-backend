package com.dwp.services.platform.workplace;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventOutboxRepository;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactDtos.ClosureCommand;
import com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactDtos.CommandReceipt;
import com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactDtos.ExecuteImpactCommand;
import com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactDtos.ImpactAction;
import com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactDtos.ImpactSelection;
import com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactDtos.NotificationState;
import com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactDtos.ReconcileNotifications;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactDtos.CreateImpactPreview;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceFacilityClosureImpactPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicLong TENANTS = new AtomicLong(11_200_000L);

    static JdbcTemplate jdbc;
    static TransactionTemplate transactions;
    static ObjectMapper json;
    static WorkplaceCatalogRepository catalog;
    static WorkplaceBookingRepository bookings;
    static WorkplaceFacilityClosureImpactService service;

    @BeforeAll
    static void actualSchemaAndService() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(source)
                .locations("filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        json = new ObjectMapper().findAndRegisterModules();
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(source);
        catalog = new WorkplaceCatalogRepository(jdbc, json);
        bookings = new WorkplaceBookingRepository(jdbc, json);
        WorkplaceExperienceFacilitiesRepository facilities =
                new WorkplaceExperienceFacilitiesRepository(named);
        DomainEventOutboxRepository outbox = new DomainEventOutboxRepository(named, json);
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        WorkplaceFacilityClosureNotificationEvents events =
                new WorkplaceFacilityClosureNotificationEvents(
                        new DomainEventRecorder(outbox, contracts, json), contracts, json);
        service = new WorkplaceFacilityClosureImpactService(
                new WorkplaceFacilityClosureImpactRepository(named, json),
                facilities,
                bookings,
                catalog,
                events,
                outbox,
                true);
    }

    @Test
    void zeroImpactClosureExecutesWithAnEmptySelectionAndTruthfulReceipt() {
        Fixture fixture = fixture("DESK");
        OffsetDateTime starts = future();
        var preview = tx(() -> service.preview(
                fixture.tenant(), 7L, fixture.site(), fixture.resource(), "zero-preview",
                new CreateImpactPreview(starts, starts.plusHours(1), 0L),
                "ADMIN.WORKPLACE:UPDATE"));

        assertThat(preview.items()).isEmpty();
        ClosureCommand first = tx(() -> service.execute(
                fixture.tenant(), 7L, fixture.site(), preview.previewId(), "zero-command",
                new ExecuteImpactCommand(preview.previewVersion(), preview.confirmationToken(),
                        "Emergency maintenance", true, List.of()),
                "corr-zero", "ADMIN.WORKPLACE:UPDATE"));
        ClosureCommand replay = tx(() -> service.execute(
                fixture.tenant(), 7L, fixture.site(), preview.previewId(), "zero-command",
                new ExecuteImpactCommand(preview.previewVersion(), preview.confirmationToken(),
                        "Emergency maintenance", true, List.of()),
                "corr-zero", "ADMIN.WORKPLACE:UPDATE"));
        CommandReceipt receipt = tx(() -> service.receipt(
                fixture.tenant(), fixture.site(), first.commandId()));

        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(first.state()).isEqualTo(WorkplaceFacilityClosureImpactDtos.CommandState.SUCCEEDED);
        assertThat(first.notifications().state()).isEqualTo(NotificationState.NOT_REQUIRED);
        assertThat(first.notifications().eventCount()).isZero();
        assertThat(receipt.bookingsMutated()).isFalse();
        assertThat(receipt.notificationScheduled()).isFalse();
        assertThat(receipt.notificationDispatchPublished()).isFalse();
        assertThat(receipt.externalDeliveryProven()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM wp_experience_facility_closures WHERE tenant_id=?",
                Integer.class, fixture.tenant())).isOne();
    }

    @Test
    void nativeBookingsExecuteKeepCancelReplaceAndRecoverNotificationOutboxExactlyOnce()
            throws Exception {
        Fixture fixture = fixture("DESK");
        UUID replacement = resource(fixture, "DESK", "Replacement");
        OffsetDateTime starts = future();
        UUID kept = booking(fixture, 11L, fixture.resource(), starts, starts.plusMinutes(30));
        UUID cancelled = booking(fixture, 12L, fixture.resource(),
                starts.plusMinutes(30), starts.plusHours(1));
        UUID replaced = booking(fixture, 13L, fixture.resource(),
                starts.plusHours(1), starts.plusHours(2));
        var preview = tx(() -> service.preview(
                fixture.tenant(), 7L, fixture.site(), fixture.resource(), "native-preview",
                new CreateImpactPreview(starts, starts.plusHours(2), 0L),
                "ADMIN.WORKPLACE:UPDATE"));
        assertThat(preview.items()).hasSize(3);
        var byBooking = preview.items().stream()
                .collect(java.util.stream.Collectors.toMap(
                        WorkplaceFacilityClosureImpactDtos.ImpactItem::bookingId,
                        java.util.function.Function.identity()));
        var replacementCandidate = byBooking.get(replaced).replacementCandidates().stream()
                .filter(candidate -> candidate.workplaceResourceId().equals(replacement))
                .findFirst().orElseThrow();
        List<ImpactSelection> selections = List.of(
                selection(byBooking.get(kept), ImpactAction.KEEP, null, null),
                selection(byBooking.get(cancelled), ImpactAction.CANCEL, null, null),
                selection(byBooking.get(replaced), ImpactAction.REPLACE,
                        replacement, replacementCandidate.resourceVersion()));

        ClosureCommand command = tx(() -> service.execute(
                fixture.tenant(), 7L, fixture.site(), preview.previewId(), "native-command",
                new ExecuteImpactCommand(preview.previewVersion(), preview.confirmationToken(),
                        "Close source area", true, selections),
                "corr-native", "ADMIN.WORKPLACE:UPDATE"));

        assertThat(command.keptCount()).isOne();
        assertThat(command.cancelledCount()).isOne();
        assertThat(command.replacedCount()).isOne();
        assertThat(status(kept)).isEqualTo("RESERVED");
        assertThat(status(cancelled)).isEqualTo("CANCELLED");
        assertThat(resourceOf(replaced)).isEqualTo(replacement);
        assertThat(command.notifications().state()).isEqualTo(NotificationState.PENDING);
        assertThat(command.notifications().recipientCount()).isEqualTo(3);
        assertThat(command.notifications().eventCount()).isEqualTo(3);
        List<String> payloads = jdbc.queryForList("""
                SELECT payload::text FROM sys_domain_event_outbox
                 WHERE tenant_id=? AND event_source='urn:dwp:platform:workplace'
                 ORDER BY aggregate_sequence
                """, String.class, fixture.tenant());
        assertThat(payloads).hasSize(3);
        for (String payload : payloads) {
            JsonNode event = json.readTree(payload);
            JsonNode intent = event.path("data").path("notificationIntents").get(0);
            String bookingId = intent.path("variables").path("bookingId").asText();
            assertThat(intent.path("typeKey").asText())
                    .isEqualTo("WORKPLACE.FACILITY_CLOSURE_IMPACT");
            assertThat(intent.path("targetReference").asText())
                    .isEqualTo("/workplace/reservations?booking=" + bookingId);
            assertThat(intent.path("variables").path("impactAction").asText())
                    .isIn("KEEP", "CANCEL", "REPLACE");
        }

        jdbc.update("""
                UPDATE sys_domain_event_outbox
                   SET status='SENDING', locked_by='lost-worker', lock_token='lost-token',
                       locked_until=CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE tenant_id=?
                """, fixture.tenant());
        ClosureCommand unknown = tx(() -> service.command(
                fixture.tenant(), fixture.site(), command.commandId()));
        assertThat(unknown.notifications().state()).isEqualTo(NotificationState.RESULT_UNKNOWN);
        ClosureCommand reconciled = tx(() -> service.reconcile(
                fixture.tenant(), 7L, fixture.site(), command.commandId(), "reconcile-key",
                new ReconcileNotifications(unknown.version(), "Release expired lease", true)));
        assertThat(reconciled.version()).isEqualTo(unknown.version() + 1);
        assertThat(reconciled.notifications().state()).isEqualTo(NotificationState.RETRY_SCHEDULED);
        ClosureCommand replay = tx(() -> service.reconcile(
                fixture.tenant(), 7L, fixture.site(), command.commandId(), "reconcile-key",
                new ReconcileNotifications(unknown.version(), "Release expired lease", true)));
        assertThat(replay.version()).isEqualTo(reconciled.version());
        assertThatThrownBy(() -> tx(() -> service.reconcile(
                fixture.tenant(), 7L, fixture.site(), command.commandId(), "reconcile-key",
                new ReconcileNotifications(reconciled.version(), "Different command", true))))
                .hasMessageContaining("different input");

        jdbc.update("""
                UPDATE sys_domain_event_outbox
                   SET status='DEAD', dead_lettered_at=CURRENT_TIMESTAMP,
                       locked_by=NULL, lock_token=NULL, locked_until=NULL
                 WHERE tenant_id=?
                """, fixture.tenant());
        ClosureCommand dead = tx(() -> service.command(
                fixture.tenant(), fixture.site(), command.commandId()));
        assertThat(dead.notifications().state()).isEqualTo(NotificationState.DEAD);
        ClosureCommand retried = tx(() -> service.retry(
                fixture.tenant(), 7L, fixture.site(), command.commandId(), "retry-key",
                new ReconcileNotifications(dead.version(), "Retry dead events", true)));
        assertThat(retried.notifications().state()).isEqualTo(NotificationState.PENDING);
        assertThat(retried.version()).isEqualTo(dead.version() + 1);
        CommandReceipt receipt = tx(() -> service.receipt(
                fixture.tenant(), fixture.site(), command.commandId()));
        assertThat(receipt.bookingsMutated()).isTrue();
        assertThat(receipt.notificationScheduled()).isTrue();
        assertThat(receipt.externalDeliveryProven()).isFalse();
        assertThat(receipt.auditTrail()).extracting(
                WorkplaceFacilityClosureImpactDtos.AuditEntry::eventType)
                .containsExactly("COMMAND_SUCCEEDED", "NOTIFICATIONS_RECONCILED",
                        "NOTIFICATIONS_RETRY_REQUESTED");
    }

    @Test
    void canonicalRoomBookingIsCancelledThroughCalendarOwnerAndNotified() {
        Fixture fixture = fixture("ROOM");
        UUID calendarResource = mapRoom(fixture);
        OffsetDateTime starts = future();
        UUID booking = calendarBooking(fixture, 21L, calendarResource,
                starts, starts.plusHours(1));
        String permissions = "ADMIN.WORKPLACE:UPDATE,ADMIN.ROOMS:VIEW,ADMIN.ROOMS:UPDATE";
        var preview = tx(() -> service.preview(
                fixture.tenant(), 7L, fixture.site(), fixture.resource(), "room-preview",
                new CreateImpactPreview(starts, starts.plusHours(1), 0L), permissions));
        assertThat(preview.items()).singleElement().satisfies(item -> {
            assertThat(item.bookingId()).isEqualTo(booking);
            assertThat(item.reservationOwner())
                    .isEqualTo(WorkplaceFacilityClosureImpactDtos.ReservationOwner.CALENDAR);
            assertThat(item.recipientUserIds()).containsExactly(21L);
        });
        var item = preview.items().getFirst();
        ClosureCommand command = tx(() -> service.execute(
                fixture.tenant(), 7L, fixture.site(), preview.previewId(), "room-command",
                new ExecuteImpactCommand(preview.previewVersion(), preview.confirmationToken(),
                        "Room maintenance", true,
                        List.of(selection(item, ImpactAction.CANCEL, null, null))),
                "corr-room", permissions));

        assertThat(jdbc.queryForObject(
                "SELECT booking_status FROM cal_resource_bookings WHERE booking_id=?",
                String.class, booking)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cal_audit_events WHERE tenant_id=? AND event_id=?",
                Integer.class, fixture.tenant(), item.eventId())).isOne();
        assertThat(command.cancelledCount()).isOne();
        assertThat(command.notifications().recipientCount()).isOne();
    }

    private static ImpactSelection selection(
            WorkplaceFacilityClosureImpactDtos.ImpactItem item,
            ImpactAction action,
            UUID replacement,
            Long replacementVersion) {
        return new ImpactSelection(item.previewItemId(), action, item.bookingVersion(),
                replacement, replacementVersion);
    }

    private static String status(UUID booking) {
        return jdbc.queryForObject(
                "SELECT booking_status FROM wp_bookings WHERE booking_id=?",
                String.class, booking);
    }

    private static UUID resourceOf(UUID booking) {
        return jdbc.queryForObject(
                "SELECT resource_id FROM wp_bookings WHERE booking_id=?",
                UUID.class, booking);
    }

    private static UUID booking(Fixture fixture, long user, UUID resource,
                                OffsetDateTime starts, OffsetDateTime ends) {
        return tx(() -> bookings.createBooking(
                fixture.tenant(), user, null, "Private member",
                new WorkplaceDtos.BookingRequest(resource, starts, ends, null, false),
                catalog.policy(fixture.tenant()), null, false).bookingId());
    }

    private static Fixture fixture(String type) {
        long tenant = TENANTS.incrementAndGet();
        UUID site = UUID.randomUUID();
        UUID floor = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,
                    data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,?,'Closure impact test','ACTIVE','kr','POOL',7,7)
                """, UUID.randomUUID(), tenant, "closure_impact_" + tenant);
        jdbc.update("INSERT INTO wp_tenant_policies(tenant_id) VALUES(?)", tenant);
        jdbc.update("""
                INSERT INTO wp_sites(site_id,tenant_id,site_code,name_ko,name_en,time_zone)
                VALUES(?,?,?,'Seoul','Seoul','Asia/Seoul')
                """, site, tenant, "SITE_" + site);
        jdbc.update("""
                INSERT INTO wp_floors(
                    floor_id,tenant_id,site_id,floor_number,name_ko,name_en,lifecycle_state)
                VALUES(?,?,?,12,'12F','12F','ACTIVE')
                """, floor, tenant, site);
        Fixture base = new Fixture(tenant, site, floor, null);
        return new Fixture(tenant, site, floor, resource(base, type, "Source"));
    }

    private static UUID resource(Fixture fixture, String type, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_resources(
                    resource_id,tenant_id,floor_id,resource_code,name_ko,name_en,resource_type)
                VALUES(?,?,?,?,?,?,?)
                """, id, fixture.tenant(), fixture.floor(), "R_" + id, name, name, type);
        return id;
    }

    private static UUID mapRoom(Fixture fixture) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_resources(
                    resource_id,tenant_id,resource_code,name_ko,name_en,resource_type,
                    site_name,floor_name)
                VALUES(?,?,?,'Room','Room','ROOM','Seoul','12F')
                """, id, fixture.tenant(), "ROOM_" + id);
        jdbc.update("UPDATE wp_resources SET calendar_resource_id=? WHERE tenant_id=? AND resource_id=?",
                id, fixture.tenant(), fixture.resource());
        return id;
    }

    private static UUID calendarBooking(Fixture fixture, long organizer, UUID resource,
                                        OffsetDateTime starts, OffsetDateTime ends) {
        UUID calendar = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_calendars(
                    calendar_id,tenant_id,calendar_key,owner_user_id,name_ko,name_en,calendar_type)
                VALUES(?,?,?,?, 'Personal','Personal','PERSONAL')
                """, calendar, fixture.tenant(), "CAL_" + calendar, organizer);
        jdbc.update("""
                INSERT INTO cal_events(
                    event_id,tenant_id,calendar_id,organizer_user_id,organizer_name,title,
                    starts_at,ends_at,time_zone,recurrence_pattern)
                VALUES(?,?,?,?, 'Private member','Private event',?,?,'Asia/Seoul','NONE')
                """, event, fixture.tenant(), calendar, organizer, starts, ends);
        return jdbc.queryForObject("""
                INSERT INTO cal_resource_bookings(
                    tenant_id,event_id,resource_id,starts_at,ends_at,
                    created_by,updated_by,requested_by)
                VALUES(?,?,?,?,?,?,?,?) RETURNING booking_id
                """, UUID.class, fixture.tenant(), event, resource, starts, ends,
                organizer, organizer, organizer);
    }

    private static OffsetDateTime future() {
        return LocalDate.now(ZoneId.of("Asia/Seoul"))
                .plusDays(3)
                .atTime(9, 0)
                .atZone(ZoneId.of("Asia/Seoul"))
                .toOffsetDateTime();
    }

    private static <T> T tx(Supplier<T> action) {
        return transactions.execute(ignored -> action.get());
    }

    private record Fixture(long tenant, UUID site, UUID floor, UUID resource) { }
}
