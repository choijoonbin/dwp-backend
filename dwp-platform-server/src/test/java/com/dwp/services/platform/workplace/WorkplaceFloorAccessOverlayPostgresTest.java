package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.calendar.CalendarService;
import com.dwp.services.platform.media.TenantMediaStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceExperienceCollaborationDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Testcontainers
class WorkplaceFloorAccessOverlayPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static ObjectMapper mapper;
    static WorkplaceSpatialGovernanceService governance;
    static WorkplaceExperienceCollaborationGovernanceService reviews;
    static WorkplaceRuntimeGovernance runtime;
    static WorkplaceService workplace;
    static WorkplaceRoomAccessAdapter rooms;
    static WorkplaceExperienceCollaborationService collaboration;
    static WorkplaceExperienceFacilitiesService facilities;
    static final long ACTOR = 9252001L, OTHER = 9252002L;

    @BeforeAll static void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl()); source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration",
                "filesystem:../dwp-core/src/main/resources/db/migration").load().migrate();
        jdbc = new JdbcTemplate(source); mapper = new ObjectMapper().findAndRegisterModules();
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var repository = new WorkplaceSpatialGovernanceRepository(jdbc, mapper);
        governance = new WorkplaceSpatialGovernanceService(repository, mapper);
        runtime = new WorkplaceRuntimeGovernance(governance);
        var catalog = new WorkplaceCatalogRepository(jdbc, mapper);
        var bookings = new WorkplaceBookingRepository(jdbc, mapper);
        workplace = new WorkplaceService(catalog, bookings, mock(CalendarService.class), mock(TenantMediaStorage.class),
                mock(WorkplaceFloorPlanValidator.class), mock(WorkplaceMediaCleanupRepository.class), governance,
                new WorkplaceReleaseWindowRepository(jdbc), mock(WorkplaceDomainEvents.class), runtime);
        rooms = new WorkplaceRoomAccessAdapter(new NamedParameterJdbcTemplate(source), runtime);
        var collaborationRepository = new WorkplaceExperienceCollaborationRepository(jdbc);
        collaboration = new WorkplaceExperienceCollaborationService(collaborationRepository, runtime, repository, mapper);
        reviews = new WorkplaceExperienceCollaborationGovernanceService(governance, collaboration, mapper);
        facilities = new WorkplaceExperienceFacilitiesService(new WorkplaceExperienceFacilitiesRepository(
                new NamedParameterJdbcTemplate(source)), bookings, catalog, runtime, workplace);
    }

    @Test void legacySiteRulesRemainExactAndFloorOnlyRulesNeverOpenSiteNavigation() {
        Fixture f = fixture(9252101);
        SiteAccessDecision empty = site(f, ACTOR, AccessPermission.VIEW);
        assertThat(empty.allowed()).isFalse(); assertThat(empty.decision()).isEqualTo("DENY_NOT_CONFIGURED");
        save(f, rule(ACTOR, f.floor(), AccessPermission.MANAGE, AccessEffect.ALLOW));
        assertThat(site(f, ACTOR, AccessPermission.VIEW)).satisfies(d -> {
            assertThat(d.allowed()).isFalse(); assertThat(d.decision()).isEqualTo(empty.decision());
            assertThat(d.matchedRuleIds()).isEmpty(); assertThat(d.floorId()).isNull();
        });
        assertThat(workplace.explore(f.tenant(), ACTOR, null, null, start(), start().plusHours(1), "en", null).sites()).isEmpty();
        var legacy = save(f, rule(ACTOR, null, AccessPermission.MANAGE, AccessEffect.ALLOW));
        for (AccessPermission permission : AccessPermission.values()) {
            assertThat(site(f, ACTOR, permission)).satisfies(d -> {
                assertThat(d.allowed()).isTrue(); assertThat(d.decision()).isEqualTo("ALLOW_EXPLICIT");
                assertThat(d.matchedRuleIds()).containsExactly(legacy.accessRuleId());
            });
        }
        assertThat(floor(f, ACTOR, f.second(), null, AccessPermission.BOOK)).satisfies(d -> {
            assertThat(d.allowed()).isTrue(); assertThat(d.decision()).isEqualTo("ALLOW_SITE_INHERITED");
        });
        var preview = governance.previewSiteAccess(f.tenant(), ACTOR, null, f.site(), AccessPermission.VIEW);
        assertThat(preview.availableFloors()).extracting(AccessRuleFloorOption::floorId).containsExactly(f.floor(), f.second());
        assertThat(preview.availableFloors()).allSatisfy(option -> assertThat(option.siteId()).isEqualTo(f.site()));
        assertThat(site(f, OTHER, AccessPermission.VIEW).decision()).isEqualTo("DENY_NO_MATCH");
    }

    @Test void configuredFloorRequiresMatchingAllowAndCannotOverrideSiteDeny() {
        Fixture f = fixture(9252102); allowSite(f, ACTOR); allowSite(f, OTHER);
        save(f, rule(OTHER, f.floor(), AccessPermission.MANAGE, AccessEffect.ALLOW));
        assertThat(floor(f, ACTOR, f.floor(), null, AccessPermission.VIEW)).satisfies(d -> {
            assertThat(d.allowed()).isFalse(); assertThat(d.decision()).isEqualTo("DENY_NO_MATCH");
        });
        UUID group = UUID.randomUUID();
        save(f, new SiteAccessRuleRequest(AccessSubjectType.GROUP_REF, null, group, AccessPermission.BOOK,
                AccessEffect.ALLOW, null, null, RuleState.ACTIVE, null, f.floor()));
        assertThat(floor(f, ACTOR, f.floor(), group.toString(), AccessPermission.BOOK).allowed()).isTrue();
        assertThat(floor(f, ACTOR, f.floor(), UUID.randomUUID().toString(), AccessPermission.BOOK).allowed()).isFalse();
        save(f, rule(ACTOR, f.floor(), AccessPermission.VIEW, AccessEffect.DENY));
        assertThat(floor(f, ACTOR, f.floor(), group.toString(), AccessPermission.VIEW).decision()).isEqualTo("DENY_EXPLICIT");
        assertThat(floor(f, ACTOR, f.floor(), group.toString(), AccessPermission.BOOK).allowed()).isTrue();
        save(f, rule(ACTOR, null, AccessPermission.VIEW, AccessEffect.DENY));
        assertThat(floor(f, ACTOR, f.floor(), group.toString(), AccessPermission.VIEW)).satisfies(d -> {
            assertThat(d.allowed()).isFalse(); assertThat(d.decision()).isEqualTo("DENY_EXPLICIT");
        });
    }

    @Test void inactiveExpiredAndFutureRulesDoNotChangeCurrentLegacyAccess() {
        Fixture f = fixture(9252103); allowSite(f, ACTOR);
        var inactive = new SiteAccessRuleRequest(AccessSubjectType.USER, ACTOR, null, AccessPermission.VIEW,
                AccessEffect.DENY, null, null, RuleState.INACTIVE, null, f.floor());
        var saved = save(f, inactive);
        assertThat(floor(f, ACTOR, f.floor(), null, AccessPermission.VIEW).decision()).isEqualTo("ALLOW_SITE_INHERITED");
        for (OffsetDateTime from : List.of(OffsetDateTime.now().plusDays(2), OffsetDateTime.now().minusDays(2))) {
            var proposed = new SiteAccessRuleRequest(AccessSubjectType.USER, ACTOR, null, AccessPermission.VIEW,
                    AccessEffect.DENY, from, from.plusHours(1), RuleState.ACTIVE, saved.version(), f.floor());
            var previous = saved;
            saved = write(() -> governance.saveAccessRule(f.tenant(), ACTOR, f.site(), previous.accessRuleId(), null, proposed));
            assertThat(floor(f, ACTOR, f.floor(), null, AccessPermission.VIEW).decision()).isEqualTo("ALLOW_SITE_INHERITED");
        }
    }

    @Test void tenantAndSiteMismatchImmutableScopeAndStaleVersionFailWithoutStoredPromotion() {
        Fixture f = fixture(9252104), foreign = fixture(9252194); allowSite(f, ACTOR);
        UUID otherSite = UUID.randomUUID(), otherFloor = UUID.randomUUID();
        jdbc.update("INSERT INTO wp_sites (site_id,tenant_id,site_code,name_ko,name_en) VALUES (?, ?, 'OTHER', '기타', 'Other')", otherSite, f.tenant());
        jdbc.update("INSERT INTO wp_floors (floor_id,tenant_id,site_id,floor_number,name_ko,name_en) VALUES (?, ?, ?, 1, '기타', 'Other')", otherFloor, f.tenant(), otherSite);
        for (UUID invalidFloor : List.of(foreign.floor(), otherFloor)) {
            assertThatThrownBy(() -> save(f, rule(ACTOR, invalidFloor, AccessPermission.BOOK, AccessEffect.ALLOW)))
                    .isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
            assertThatThrownBy(() -> jdbc.update("INSERT INTO wp_site_access_rules (tenant_id,site_id,floor_id,subject_type,subject_user_id,permission_code,effect) VALUES (?, ?, ?, 'USER', ?, 'VIEW', 'ALLOW')", f.tenant(), f.site(), invalidFloor, OTHER))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        var saved = save(f, rule(ACTOR, f.floor(), AccessPermission.BOOK, AccessEffect.ALLOW));
        var changedScope = new SiteAccessRuleRequest(AccessSubjectType.USER, ACTOR, null, AccessPermission.BOOK,
                AccessEffect.ALLOW, null, null, RuleState.ACTIVE, saved.version(), null);
        assertThatThrownBy(() -> write(() -> reviews.changeRule(f.tenant(), ACTOR, null, f.site(), saved.accessRuleId(),
                new AccessRuleChangeRequest(changedScope, "Do not promote floor to site", true), null))).isInstanceOf(BaseException.class);
        var stale = new SiteAccessRuleRequest(AccessSubjectType.USER, ACTOR, null, AccessPermission.BOOK,
                AccessEffect.DENY, null, null, RuleState.ACTIVE, saved.version() + 1, f.floor());
        assertThatThrownBy(() -> write(() -> reviews.changeRule(f.tenant(), ACTOR, null, f.site(), saved.accessRuleId(),
                new AccessRuleChangeRequest(stale, "Stale command", true), null)))
                .isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        assertThat(governance.accessRules(f.tenant(), f.site())).filteredOn(r -> r.accessRuleId().equals(saved.accessRuleId()))
                .singleElement().satisfies(r -> { assertThat(r.floorId()).isEqualTo(f.floor()); assertThat(r.effect()).isEqualTo(AccessEffect.ALLOW); });
    }

    @Test void reviewCurrentAndProposedActorDecisionIsActualAndSaveReasonRollsBackAtomically() {
        Fixture f = fixture(9252105); allowSite(f, ACTOR);
        var proposed = rule(OTHER, f.floor(), AccessPermission.MANAGE, AccessEffect.ALLOW);
        var request = new AccessRuleChangeRequest(proposed, "Restrict one floor", true);
        var before = reviews.reviewRule(f.tenant(), ACTOR, null, f.site(), null, request);
        assertThat(before.currentActorAccess().allowed()).isTrue(); assertThat(before.proposedActorAccess().allowed()).isFalse();
        assertThat(before.proposedActorAccess().floorId()).isEqualTo(f.floor());
        assertThat(before.proposed().get("floorId").asText()).isEqualTo(f.floor().toString());
        assertThat(governance.accessRules(f.tenant(), f.site())).hasSize(1);
        assertThatThrownBy(() -> write(() -> { reviews.changeRule(f.tenant(), ACTOR, null, f.site(), null, request, "rollback");
            throw new IllegalStateException("Rollback downstream owner command"); })).isInstanceOf(IllegalStateException.class);
        assertThat(governance.accessRules(f.tenant(), f.site())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wp_audit_events WHERE tenant_id = ? AND action = 'workplace.governance.access.rule.reviewed'", Long.class, f.tenant())).isZero();
        var saved = write(() -> reviews.changeRule(f.tenant(), ACTOR, null, f.site(), null, request, "commit"));
        assertThat(saved.floorId()).isEqualTo(f.floor());
        assertThat(jdbc.queryForObject("SELECT snapshot ->> 'reason' FROM wp_audit_events WHERE tenant_id = ? AND action = 'workplace.governance.access.rule.reviewed'", String.class, f.tenant())).isEqualTo("Restrict one floor");
        assertThat(site(f, ACTOR, AccessPermission.VIEW).allowed()).isTrue();
    }

    @Test void memberExploreBookingHistoryMutationBackgroundAndCalendarBridgeRespectActualFloor() {
        Fixture f = fixture(9252106); allowSite(f, ACTOR);
        var created = write(() -> workplace.createBooking(f.tenant(), ACTOR, null, "Member", "en", "create", null,
                new WorkplaceDtos.BookingRequest(f.resource(), start(), start().plusHours(1), "Focus", true)));
        save(f, rule(OTHER, f.floor(), AccessPermission.MANAGE, AccessEffect.ALLOW));
        var explored = workplace.explore(f.tenant(), ACTOR, null, null, start(), start().plusHours(1), "en", null);
        assertThat(explored.sites()).hasSize(1); assertThat(explored.floors()).extracting(WorkplaceDtos.Floor::floorId).containsExactly(f.second());
        assertThat(explored.resources()).extracting(WorkplaceDtos.Resource::resourceId).doesNotContain(f.resource(), f.room());
        assertThatThrownBy(() -> workplace.explore(f.tenant(), ACTOR, null, f.floor(), start(), start().plusHours(1), "en", null)).isInstanceOf(BaseException.class);
        assertThat(workplace.myBookings(f.tenant(), ACTOR, start().minusDays(1), start().plusDays(1), "en", null)).isEmpty();
        assertThatThrownBy(() -> write(() -> workplace.cancelBooking(f.tenant(), ACTOR, created.bookingId(), "en", "cancel", null,
                new WorkplaceDtos.VersionRequest(created.version())))).isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(jdbc.queryForObject("SELECT booking_status FROM wp_bookings WHERE tenant_id = ? AND booking_id = ?", String.class, f.tenant(), created.bookingId())).isEqualTo("RESERVED");
        assertThatThrownBy(() -> workplace.floorBackground(f.tenant(), ACTOR, null, f.floor())).isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(rooms.viewableResourceIds(f.tenant(), ACTOR, null, List.of(f.calendar()))).isEmpty();
        assertThatThrownBy(() -> write(() -> { rooms.requireBook(f.tenant(), ACTOR, null, f.calendar()); return true; })).isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> write(() -> workplace.createBooking(f.tenant(), ACTOR, null, "Member", "en", null, null,
                new WorkplaceDtos.BookingRequest(f.resource(), start().plusHours(2), start().plusHours(3), "Denied", true)))).isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wp_bookings WHERE tenant_id = ?", Long.class, f.tenant())).isEqualTo(1);
    }

    @Test void workPlansAndFacilityRequestCountsDoNotLeakDeniedFloor() {
        Fixture f = fixture(9252107); allowSite(f, ACTOR);
        var plan = write(() -> collaboration.savePlan(f.tenant(), ACTOR, null, null, new WorkPlanRequest(start().toLocalDate(),
                PlanMode.OFFICE, f.site(), f.floor(), f.resource(), null, Visibility.PRIVATE, null)));
        var facility = write(() -> facilities.createRequest(f.tenant(), ACTOR, f.resource(), "floor-request", null,
                new WorkplaceExperienceFacilitiesDtos.CreateRequest(WorkplaceExperienceFacilitiesDtos.Category.REPAIR, "Real description"), null));
        assertThat(facilities.ownRequests(f.tenant(), ACTOR, null, 0, 20).totalElements()).isEqualTo(1);
        save(f, rule(OTHER, f.floor(), AccessPermission.MANAGE, AccessEffect.ALLOW));
        assertThat(collaboration.overview(f.tenant(), ACTOR, null, plan.planDate(), plan.planDate(), null).ownPlans()).isEmpty();
        assertThat(facilities.ownRequests(f.tenant(), ACTOR, null, 0, 20)).satisfies(page -> {
            assertThat(page.content()).isEmpty(); assertThat(page.totalElements()).isZero(); assertThat(page.totalPages()).isZero();
        });
        assertThatThrownBy(() -> facilities.ownRequest(f.tenant(), ACTOR, null, facility.requestId())).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> write(() -> facilities.createRequest(f.tenant(), ACTOR, f.resource(), "denied-request", null,
                new WorkplaceExperienceFacilitiesDtos.CreateRequest(WorkplaceExperienceFacilitiesDtos.Category.ACCESS, "Denied"), null))).isInstanceOf(BaseException.class);
    }

    @Test void concurrentSiteRuleChangeSerializesBeforeNativeBookingAndRollbackKeepsOwnerState() throws Exception {
        Fixture f = fixture(9252108); allowSite(f, ACTOR);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), bookingStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> write(() -> {
                governance.lockSiteAccessScope(f.tenant(), f.site());
                save(f, rule(OTHER, f.floor(), AccessPermission.MANAGE, AccessEffect.ALLOW)); locked.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                return true;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var booking = executor.submit(() -> { bookingStarted.countDown(); return write(() -> workplace.createBooking(
                    f.tenant(), ACTOR, null, "Member", "en", "concurrent", null,
                    new WorkplaceDtos.BookingRequest(f.resource(), start(), start().plusHours(1), "Must be denied", true))); });
            assertThat(bookingStarted.await(10, TimeUnit.SECONDS)).isTrue();
            // The owner command cannot commit while the policy owner holds its exact tenant/site transaction lock.
            assertThatThrownBy(() -> booking.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); assertThat(writer.get(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> booking.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(BaseException.class);
        } finally { release.countDown(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wp_bookings WHERE tenant_id = ?", Long.class, f.tenant())).isZero();
    }

    static Fixture fixture(long tenant) {
        UUID site = UUID.randomUUID(), floor = UUID.randomUUID(), second = UUID.randomUUID(), resource = UUID.randomUUID(), room = UUID.randomUUID(), calendar = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_service_tenants (provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,data_region,isolation_model,created_by,updated_by) VALUES (?, ?, ?, 'Floor overlay', 'ACTIVE', 'kr', 'POOL', ?, ?)", UUID.randomUUID(), tenant, "floor-overlay-" + tenant, ACTOR, ACTOR);
        jdbc.update("INSERT INTO wp_tenant_policies (tenant_id,working_day_start,working_day_end) VALUES (?, '00:00', '23:59')", tenant);
        jdbc.update("INSERT INTO wp_sites (site_id,tenant_id,site_code,name_ko,name_en,time_zone) VALUES (?, ?, ?, '사이트', 'Site', 'UTC')", site, tenant, "SITE_" + tenant);
        jdbc.update("INSERT INTO wp_floors (floor_id,tenant_id,site_id,floor_number,name_ko,name_en) VALUES (?, ?, ?, 10, '10층', '10F'), (?, ?, ?, 11, '11층', '11F')", floor, tenant, site, second, tenant, site);
        jdbc.update("INSERT INTO cal_resources (resource_id,tenant_id,resource_code,name_ko,name_en,resource_type,site_name,floor_name,capacity) VALUES (?, ?, ?, '회의실', 'Room', 'ROOM', 'Site', '10F', 8)", calendar, tenant, "CAL_" + tenant);
        jdbc.update("INSERT INTO wp_resources (resource_id,tenant_id,floor_id,resource_code,name_ko,name_en,resource_type) VALUES (?, ?, ?, ?, '좌석', 'Desk', 'DESK')", resource, tenant, floor, "DESK_" + tenant);
        jdbc.update("INSERT INTO wp_resources (resource_id,tenant_id,floor_id,calendar_resource_id,resource_code,name_ko,name_en,resource_type) VALUES (?, ?, ?, ?, ?, '회의실', 'Room', 'ROOM')", room, tenant, floor, calendar, "ROOM_" + tenant);
        return new Fixture(tenant, site, floor, second, resource, room, calendar);
    }
    static OffsetDateTime start() { return OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0); }
    static void allowSite(Fixture f, long user) { save(f, rule(user, null, AccessPermission.MANAGE, AccessEffect.ALLOW)); }
    static SiteAccessRuleRequest rule(long user, UUID floor, AccessPermission permission, AccessEffect effect) {
        return new SiteAccessRuleRequest(AccessSubjectType.USER, user, null, permission, effect, null, null, RuleState.ACTIVE, null, floor);
    }
    static SiteAccessRule save(Fixture f, SiteAccessRuleRequest request) { return write(() -> governance.saveAccessRule(f.tenant(), ACTOR, f.site(), null, null, request)); }
    static SiteAccessDecision site(Fixture f, long user, AccessPermission permission) { return governance.evaluateSiteAccess(f.tenant(), user, null, f.site(), permission); }
    static SiteAccessDecision floor(Fixture f, long user, UUID floor, String groups, AccessPermission permission) { return governance.evaluateFloorAccess(f.tenant(), user, groups, f.site(), floor, permission); }
    static <T> T write(Supplier<T> action) { return tx.execute(status -> action.get()); }
    record Fixture(long tenant, UUID site, UUID floor, UUID second, UUID resource, UUID room, UUID calendar) { }
}
