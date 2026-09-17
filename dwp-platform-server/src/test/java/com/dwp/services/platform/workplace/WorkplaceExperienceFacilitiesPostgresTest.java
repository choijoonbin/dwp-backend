package com.dwp.services.platform.workplace;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.calendar.CalendarService;
import com.dwp.services.platform.calendar.CalendarRepository;
import com.dwp.services.platform.media.TenantMediaStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
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
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import static com.dwp.services.platform.workplace.WorkplaceExperienceFacilitiesDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceExperienceFacilitiesPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final AtomicLong TENANTS = new AtomicLong(9_900_000L);
    static JdbcTemplate jdbc;
    static TransactionTemplate transaction;
    static WorkplaceCatalogRepository catalog;
    static WorkplaceBookingRepository bookings;
    static WorkplaceService workplace;
    static WorkplaceOperationsService operations;
    static WorkplaceExperienceFacilitiesService facilities;
    static WorkplaceExperienceFacilitiesRepository repository;
    static CalendarService actualCalendar;
    static final String ROOM_WRITE = "ADMIN.WORKPLACE:CREATE,ADMIN.WORKPLACE:UPDATE,ADMIN.ROOMS:UPDATE";

    @BeforeAll static void actualSchemaAndServices() {
        var ds = new PGSimpleDataSource(); ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername()); ds.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("filesystem:src/main/resources/db/migration",
                "filesystem:../dwp-core/src/main/resources/db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds); transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var mapper = new ObjectMapper().findAndRegisterModules();
        catalog = new WorkplaceCatalogRepository(jdbc, mapper); bookings = new WorkplaceBookingRepository(jdbc, mapper);
        var spatial = new WorkplaceSpatialGovernanceService(new WorkplaceSpatialGovernanceRepository(jdbc, mapper), mapper);
        var runtime = new WorkplaceRuntimeGovernance(spatial);
        actualCalendar = new CalendarService(new CalendarRepository(jdbc,mapper),
                new WorkplaceRoomAccessAdapter(new NamedParameterJdbcTemplate(jdbc),runtime),
                null,null);
        var events = mock(WorkplaceDomainEvents.class);
        workplace = new WorkplaceService(catalog, bookings, mock(CalendarService.class), mock(TenantMediaStorage.class),
                mock(WorkplaceFloorPlanValidator.class), mock(WorkplaceMediaCleanupRepository.class), spatial,
                new WorkplaceReleaseWindowRepository(jdbc), events, runtime);
        operations = new WorkplaceOperationsService(catalog, bookings,
                new WorkplaceOperationsRepository(new NamedParameterJdbcTemplate(jdbc), mapper), workplace, events);
        repository = new WorkplaceExperienceFacilitiesRepository(new NamedParameterJdbcTemplate(jdbc));
        facilities = new WorkplaceExperienceFacilitiesService(repository, bookings, catalog, runtime, workplace);
    }

    @Test void actualCreateRelocatePreflightAndExploreRespectPeriodAndScope() {
        var f = fixture("DESK"); var target = resource(f, "DESK"); OffsetDateTime start = future();
        Closure closure = tx(() -> createClosure(f, target, start, start.plusHours(2), "period"));
        assertThat(tx(() -> facilities.bookingAvailability(f.tenant,7L,null,null,target,start,start.plusHours(1))).reason())
                .isEqualTo("FACILITY_CLOSURE");
        assertThatThrownBy(() -> tx(() -> workplace.createBooking(f.tenant,7L,null,"Member","en",null,null,
                request(target,start,start.plusHours(1))))).isInstanceOf(BaseException.class);
        var original = tx(() -> workplace.createBooking(f.tenant,7L,null,"Member","en",null,null,
                request(f.resource,start,start.plusHours(1))));
        assertThatThrownBy(() -> tx(() -> operations.relocateBooking(f.tenant,7L,null,original.bookingId(),"en",null,null,
                new WorkplaceOperationsDtos.RelocateBookingRequest(target,start,start.plusHours(1),"Move",0L))))
                .isInstanceOf(BaseException.class);
        var explore = tx(() -> workplace.explore(f.tenant,7L,null,f.floor,start,start.plusHours(2),"en",null));
        assertThat(explore.closures()).extracting(PublicClosure::resourceId).containsExactly(target);
        var hidden = fixture("DESK");
        assertThatThrownBy(() -> tx(() -> workplace.explore(f.tenant,7L,null,hidden.floor,start,start.plusHours(2),"en",null)))
                .isInstanceOf(BaseException.class);
        assertThat(repository.closure(hidden.tenant, hidden.site, closure.closureId())).isEmpty();
        assertThat(catalog.resource(f.tenant,target,false).orElseThrow().state().name()).isEqualTo("AVAILABLE");
        assertThat(tx(() -> workplace.createBooking(f.tenant,8L,null,"Other","en",null,null,
                request(target,start.plusHours(2),start.plusHours(3))))).isNotNull();
    }

    @Test void closurePreservesBookingsIdempotencyAndOptimisticCancellationAudit() {
        var f = fixture("DESK"); OffsetDateTime start = future(); UUID booking = tx(() -> rawBooking(f,f.resource,start,start.plusHours(1)));
        var first = tx(() -> createClosure(f,f.resource,start,start.plusHours(1),"same"));
        var replay = tx(() -> createClosure(f,f.resource,start,start.plusHours(1),"same"));
        assertThat(replay.closureId()).isEqualTo(first.closureId());
        assertThat(jdbc.queryForObject("SELECT booking_status FROM wp_bookings WHERE booking_id=?",String.class,booking)).isEqualTo("RESERVED");
        assertThatThrownBy(() -> tx(() -> createClosure(f,f.resource,start,start.plusHours(2),"same"))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> tx(() -> facilities.cancelClosure(f.tenant,7L,f.site,first.closureId(),
                new CancelClosure(1L,"Wrong version",true),null,ROOM_WRITE))).isInstanceOf(BaseException.class);
        var cancelled = tx(() -> facilities.cancelClosure(f.tenant,7L,f.site,first.closureId(),new CancelClosure(0L,"Work complete",true),null,ROOM_WRITE));
        assertThat(cancelled.status()).isEqualTo(ClosureStatus.CANCELLED); assertThat(cancelled.version()).isOne();
        assertThat(bookings.overlapsFacilityClosure(f.tenant,f.resource,start,start.plusHours(1))).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wp_audit_events WHERE tenant_id=? AND aggregate_id=?",Integer.class,
                f.tenant,first.closureId())).isEqualTo(2);
    }

    @Test void facilityRequestsEnforceCurrentOwnerTenantSiteAndStatusVersion() {
        var f = fixture("DESK");
        var created = tx(() -> facilities.createRequest(f.tenant,7L,f.resource,"request-key",null,new CreateRequest(Category.REPAIR,"Broken desk"),null));
        assertThat(tx(() -> facilities.createRequest(f.tenant,7L,f.resource,"request-key",null,new CreateRequest(Category.REPAIR,"Broken desk"),null)).requestId()).isEqualTo(created.requestId());
        assertThat(tx(() -> facilities.ownRequests(f.tenant,8L,null,0,20)).content()).isEmpty();
        assertThatThrownBy(() -> tx(() -> facilities.ownRequest(f.tenant,8L,null,created.requestId()))).isInstanceOf(BaseException.class);
        var other = fixture("DESK");
        assertThatThrownBy(() -> tx(() -> facilities.adminRequests(other.tenant,f.site,null,null,0,20))).isInstanceOf(BaseException.class);
    }

    @Test void facilityStatusRejectsStaleVersionAndRevokedMemberScope() {
        var f = fixture("DESK");
        var created = tx(() -> facilities.createRequest(f.tenant,7L,f.resource,"status",null,new CreateRequest(Category.CLEANING,"Clean desk"),null));
        OffsetDateTime sla = OffsetDateTime.now().plusHours(4).withNano(0);
        var changed = tx(() -> facilities.changeRequestStatus(f.tenant,9L,f.site,created.requestId(),
                new ChangeRequestStatus(RequestStatus.IN_PROGRESS,0L,"Assigned internally",true,
                        RequestPriority.HIGH,"Facilities A","Approved Vendor","WO-1202",sla,false),null));
        assertThat(changed.version()).isOne();
        assertThat(changed.priority()).isEqualTo(RequestPriority.HIGH);
        assertThat(changed.assignedTo()).isEqualTo("Facilities A");
        assertThat(changed.serviceProvider()).isEqualTo("Approved Vendor");
        assertThat(changed.externalWorkOrderReference()).isEqualTo("WO-1202");
        assertThat(changed.slaDueAt()).isEqualTo(sla);
        var reassigned = tx(() -> facilities.changeRequestStatus(f.tenant,9L,f.site,created.requestId(),
                new ChangeRequestStatus(RequestStatus.IN_PROGRESS,1L,"Reassigned",true,
                        RequestPriority.CRITICAL,"Facilities B","Approved Vendor","WO-1202",null,true),null));
        assertThat(reassigned.status()).isEqualTo(RequestStatus.IN_PROGRESS);
        assertThat(reassigned.priority()).isEqualTo(RequestPriority.CRITICAL);
        assertThat(reassigned.assignedTo()).isEqualTo("Facilities B");
        assertThat(reassigned.slaDueAt()).isNull();
        assertThatThrownBy(() -> tx(() -> facilities.changeRequestStatus(f.tenant,9L,f.site,created.requestId(),
                new ChangeRequestStatus(RequestStatus.RESOLVED,0L,"Stale",true),null))).isInstanceOf(BaseException.class);
        jdbc.update("UPDATE wp_site_access_rules SET lifecycle_state='INACTIVE',version=version+1 WHERE tenant_id=? AND subject_user_id=7",f.tenant);
        assertThat(tx(() -> facilities.ownRequests(f.tenant,7L,null,0,20)).content()).isEmpty();
        assertThatThrownBy(() -> tx(() -> facilities.ownRequest(f.tenant,7L,null,created.requestId()))).isInstanceOf(BaseException.class);
        assertThat(tx(() -> facilities.adminRequests(f.tenant,f.site,null,RequestStatus.IN_PROGRESS,0,1)).totalElements()).isOne();
    }

    @Test void zeroAutoReleaseIsValidDtoSnapshotAndActualLifecycleBoundary() {
        var f = fixture("DESK");
        var policy = new WorkplaceDtos.PolicyRequest(30,10,30,720,10,LocalTime.of(0,0),LocalTime.of(23,59),
                false,true,30,0,false,true,365,0L);
        try(var validator = Validation.buildDefaultValidatorFactory()) { assertThat(validator.getValidator().validate(policy)).isEmpty(); }
        assertThat(tx(() -> workplace.updatePolicy(f.tenant,7L,null,policy)).autoReleaseMinutes()).isZero();
        OffsetDateTime start = OffsetDateTime.now().minusMinutes(1).withNano(0);
        UUID booking = tx(() -> rawBooking(f,f.resource,start,start.plusHours(1)));
        assertThat(jdbc.queryForObject("SELECT auto_release_minutes_snapshot FROM wp_bookings WHERE booking_id=?",Integer.class,booking)).isZero();
        assertThat(tx(() -> bookings.releaseNoShows(f.tenant,start))).isEmpty();
        assertThat(tx(() -> bookings.releaseNoShows(f.tenant,start.plusSeconds(1)))).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT booking_status FROM wp_bookings WHERE booking_id=?",String.class,booking)).isEqualTo("NO_SHOW");
        assertThatThrownBy(() -> jdbc.update("UPDATE wp_bookings SET auto_release_minutes_snapshot=-1 WHERE booking_id=?",booking))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void closureFirstBookingWaitsThenNativeDatabaseRejects() throws Exception {
        var f = fixture("DESK"); OffsetDateTime start = future();
        race(() -> createClosure(f,f.resource,start,start.plusHours(1),"race-closure"),
                () -> rawBooking(f,f.resource,start,start.plusHours(1)), true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wp_bookings WHERE tenant_id=?",Integer.class,f.tenant)).isZero();
    }

    @Test void bookingFirstClosureWaitsThenPreservesActualReservation() throws Exception {
        var f = fixture("DESK"); OffsetDateTime start = future();
        race(() -> rawBooking(f,f.resource,start,start.plusHours(1)),
                () -> createClosure(f,f.resource,start,start.plusHours(1),"booking-first"), false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wp_bookings WHERE tenant_id=? AND booking_status='RESERVED'",Integer.class,f.tenant)).isOne();
    }

    @Test void canonicalRoomClosureRejectsCalendarOccurrencesButKeepsExistingAndScopePrivate() {
        var f = fixture("ROOM"); UUID calendarResource = mapRoom(f); OffsetDateTime start = future();
        UUID existing = tx(() -> calendarBooking(f,calendarResource,start,start.plusHours(1),"DAILY",start.plusDays(2).toLocalDate()));
        assertThatThrownBy(() -> tx(() -> facilities.createClosure(f.tenant,7L,f.site,f.resource,"no-room-permission",
                new CreateClosure(start.plusDays(1),start.plusDays(1).plusHours(1),0L,"Repair",true),null,"ADMIN.WORKPLACE:CREATE")))
                .isInstanceOf(BaseException.class);
        tx(() -> createClosure(f,f.resource,start.plusDays(1),start.plusDays(1).plusHours(1),"room"));
        assertThat(WorkplaceExperienceCalendarClosureBridge.conflict(jdbc,f.tenant,calendarResource,start.plusDays(1),start.plusDays(1).plusHours(1))).isTrue();
        assertThatThrownBy(() -> tx(() -> calendarBooking(f,calendarResource,start,start.plusHours(1),"DAILY",start.plusDays(2).toLocalDate())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var impact = tx(() -> facilities.roomImpact(f.tenant,f.site,f.resource,start.plusDays(1),start.plusDays(1).plusHours(1),0,1,"ADMIN.ROOMS:VIEW"));
        assertThat(impact.totalElements()).isOne(); assertThat(impact.content().getFirst().bookingId()).isEqualTo(existing);
        assertThat(impact.content().getFirst().startsAt().toInstant()).isEqualTo(start.plusDays(1).toInstant());
        assertThat(impact.owner()).isEqualTo("ROOMS"); assertThat(impact.existingBookingsMutated()).isFalse();
        assertThat(tx(() -> actualCalendar.resources(f.tenant,7L,null,start.plusDays(1),start.plusDays(1).plusHours(1),"en")))
                .singleElement().satisfies(room -> assertThat(room.available()).isFalse());
        assertThat(tx(() -> actualCalendar.resources(f.tenant,77L,null,start.plusDays(1),start.plusDays(1).plusHours(1),"en"))).isEmpty();
        assertThatThrownBy(() -> tx(() -> facilities.roomImpact(f.tenant,UUID.randomUUID(),f.resource,start,start.plusDays(3),0,1,"ADMIN.ROOMS:VIEW"))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> tx(() -> facilities.roomImpact(f.tenant,f.site,f.resource,start,start.plusDays(3),0,1,"ADMIN.WORKPLACE:VIEW"))).isInstanceOf(BaseException.class);
    }

    @Test void roomClosureAndCalendarBookingUseTheSameConcurrencyLocks() throws Exception {
        var f = fixture("ROOM"); UUID calendar = mapRoom(f); OffsetDateTime start = future();
        race(() -> createClosure(f,f.resource,start,start.plusHours(1),"room-race"),
                () -> calendarBooking(f,calendar,start,start.plusHours(1),"NONE",null),true);
        var second = fixture("ROOM"); UUID otherCalendar = mapRoom(second);
        race(() -> calendarBooking(second,otherCalendar,start,start.plusHours(1),"NONE",null),
                () -> createClosure(second,second.resource,start,start.plusHours(1),"room-booking-first"),false);
    }

    @Test void nativeRecurrenceRetainsMonthClampAndDstFoldOffset() {
        var anchor = OffsetDateTime.parse("2026-01-31T09:00:00+09:00");
        var months = jdbc.query("SELECT occurrence_starts_at FROM wp_facility_calendar_occurrences(?,?,?,?,?,?)",
                (rs,i) -> rs.getObject(1,OffsetDateTime.class).atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate(),
                anchor,anchor.plusHours(1),"Asia/Seoul","MONTHLY",1,LocalDate.of(2026,3,31));
        assertThat(months).containsExactly(LocalDate.of(2026,1,31),LocalDate.of(2026,2,28),LocalDate.of(2026,3,28));
        var fold = OffsetDateTime.parse("2026-10-31T01:30:00-04:00");
        var days = jdbc.query("SELECT occurrence_starts_at FROM wp_facility_calendar_occurrences(?,?,?,?,?,?)",
                (rs,i) -> rs.getObject(1,OffsetDateTime.class).toInstant(),fold,fold.plusHours(1),"America/New_York","DAILY",1,LocalDate.of(2026,11,2));
        assertThat(days).containsExactly(fold.toInstant(),Instant.parse("2026-11-01T05:30:00Z"),Instant.parse("2026-11-02T06:30:00Z"));
    }

    @Test void deferredCalendarRecurrenceUpdateCannotEnterClosureAndRelocationCanLeaveIt() {
        var f = fixture("ROOM"); UUID calendar = mapRoom(f); OffsetDateTime start = future();
        UUID booking = tx(() -> calendarBooking(f,calendar,start,start.plusHours(1),"NONE",null));
        UUID event = jdbc.queryForObject("SELECT event_id FROM cal_resource_bookings WHERE booking_id=?",UUID.class,booking);
        tx(() -> createClosure(f,f.resource,start.plusDays(1),start.plusDays(1).plusHours(1),"recurrence-change"));
        assertThatThrownBy(() -> tx(() -> jdbc.update("UPDATE cal_events SET recurrence_pattern='DAILY',recurrence_until=? WHERE tenant_id=? AND event_id=?",
                start.plusDays(2).toLocalDate(),f.tenant,event))).isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThat(jdbc.queryForObject("SELECT recurrence_pattern FROM cal_events WHERE event_id=?",String.class,event)).isEqualTo("NONE");
        tx(() -> { jdbc.update("UPDATE cal_events SET recurrence_pattern='DAILY',recurrence_until=? WHERE tenant_id=? AND event_id=?",
                start.plusDays(2).toLocalDate(),f.tenant,event);
            return jdbc.update("UPDATE cal_resource_bookings SET booking_status='CANCELLED' WHERE tenant_id=? AND booking_id=?",f.tenant,booking); });
        assertThat(jdbc.queryForObject("SELECT booking_status FROM cal_resource_bookings WHERE booking_id=?",String.class,booking)).isEqualTo("CANCELLED");
    }

    private static <T> T tx(Supplier<T> action) { return transaction.execute(s -> action.get()); }
    private static OffsetDateTime future() { return LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(2).atTime(10,0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(); }
    private static WorkplaceDtos.BookingRequest request(UUID resource,OffsetDateTime from,OffsetDateTime to) { return new WorkplaceDtos.BookingRequest(resource,from,to,null,false); }
    private static UUID rawBooking(Fixture f,UUID resource,OffsetDateTime from,OffsetDateTime to) {
        return bookings.createBooking(f.tenant,7L,null,"Private requester",request(resource,from,to),catalog.policy(f.tenant),null,false).bookingId();
    }
    private static Closure createClosure(Fixture f,UUID resource,OffsetDateTime from,OffsetDateTime to,String key) {
        return facilities.createClosure(f.tenant,7L,f.site,resource,key,new CreateClosure(from,to,0L,"Native repair",true),null,ROOM_WRITE);
    }
    private static Fixture fixture(String type) {
        long tenant = TENANTS.incrementAndGet(); UUID site=UUID.randomUUID(),floor=UUID.randomUUID();
        jdbc.update("INSERT INTO sys_service_tenants(provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,data_region,isolation_model,created_by,updated_by) VALUES(?,?,?,'Facilities test','ACTIVE','kr','POOL',7,7)",UUID.randomUUID(),tenant,"facilities_"+tenant);
        jdbc.update("INSERT INTO wp_tenant_policies(tenant_id) VALUES(?)",tenant);
        jdbc.update("INSERT INTO wp_sites(site_id,tenant_id,site_code,name_ko,name_en,time_zone) VALUES(?,?,?,'Seoul','Seoul','Asia/Seoul')",site,tenant,"SITE_"+site);
        jdbc.update("INSERT INTO wp_floors(floor_id,tenant_id,site_id,floor_number,name_ko,name_en,lifecycle_state) VALUES(?,?,?,10,'10F','10F','ACTIVE')",floor,tenant,site);
        for(long user: new long[]{7,8}) jdbc.update("INSERT INTO wp_site_access_rules(tenant_id,site_id,subject_type,subject_user_id,permission_code,effect,lifecycle_state,created_by,updated_by) VALUES(?,?,'USER',?,'MANAGE','ALLOW','ACTIVE',7,7)",tenant,site,user);
        Fixture base = new Fixture(tenant,site,floor,null); return new Fixture(tenant,site,floor,resource(base,type));
    }
    private static UUID resource(Fixture f,String type) { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO wp_resources(resource_id,tenant_id,floor_id,resource_code,name_ko,name_en,resource_type) VALUES(?,?,?,?,'Workspace','Workspace',?)",id,f.tenant,f.floor,"R_"+id,type); return id; }
    private static UUID mapRoom(Fixture f) { UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO cal_resources(resource_id,tenant_id,resource_code,name_ko,name_en,resource_type,site_name,floor_name) VALUES(?,?,?,'Room','Room','ROOM','Seoul','10F')",id,f.tenant,"ROOM_"+id); jdbc.update("UPDATE wp_resources SET calendar_resource_id=? WHERE tenant_id=? AND resource_id=?",id,f.tenant,f.resource); return id; }
    private static UUID calendarBooking(Fixture f,UUID resource,OffsetDateTime from,OffsetDateTime to,String recurrence,LocalDate until) {
        UUID calendar=UUID.randomUUID(),event=UUID.randomUUID();
        jdbc.update("INSERT INTO cal_calendars(calendar_id,tenant_id,calendar_key,owner_user_id,name_ko,name_en,calendar_type) VALUES(?,?,?,7,'Personal','Personal','PERSONAL')",calendar,f.tenant,"CAL_"+calendar);
        jdbc.update("INSERT INTO cal_events(event_id,tenant_id,calendar_id,organizer_user_id,organizer_name,title,starts_at,ends_at,time_zone,recurrence_pattern,recurrence_until) VALUES(?,?,?,7,'Private name','Private event',?,?,'Asia/Seoul',?,?)",event,f.tenant,calendar,from,to,recurrence,until);
        return jdbc.queryForObject("INSERT INTO cal_resource_bookings(tenant_id,event_id,resource_id,starts_at,ends_at,created_by,updated_by,requested_by) VALUES(?,?,?,?,?,7,7,7) RETURNING booking_id",UUID.class,f.tenant,event,resource,from,to);
    }
    private static void race(Supplier<?> first,Supplier<?> second,boolean rejectsSecond) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch written=new CountDownLatch(1),release=new CountDownLatch(1),started=new CountDownLatch(1);
        try {
            Future<?> holding=pool.submit(() -> tx(() -> { Object result=first.get(); written.countDown(); await(release); return result; }));
            assertThat(written.await(10,TimeUnit.SECONDS)).isTrue();
            Future<?> waiting=pool.submit(() -> { started.countDown(); return tx(second::get); });
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            try { assertThatThrownBy(() -> waiting.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            holding.get(10,TimeUnit.SECONDS);
            if(rejectsSecond) assertThatThrownBy(() -> waiting.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            else assertThat(waiting.get(10,TimeUnit.SECONDS)).isNotNull();
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    private static void await(CountDownLatch latch) { try { if(!latch.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Race test release timed out"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
    private record Fixture(long tenant,UUID site,UUID floor,UUID resource) { }
}
