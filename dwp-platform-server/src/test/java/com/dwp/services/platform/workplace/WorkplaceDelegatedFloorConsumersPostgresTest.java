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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceExperienceFacilitiesDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceExperienceReportDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class WorkplaceDelegatedFloorConsumersPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final AtomicLong TENANTS = new AtomicLong(9_753_000);
    static final long ACTOR = 75301;
    static final AtomicLong BOOKERS = new AtomicLong(8_753_000);
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static WorkplaceCatalogRepository catalog;
    static WorkplaceBookingRepository bookings;
    static WorkplaceDelegatedAdminScopeGuard guard;
    static WorkplaceService workplace;
    static WorkplaceRuntimeGovernance runtime;
    static WorkplaceSpatialGovernanceRepository spatial;
    static WorkplaceScopedCatalogAdminService catalogAdmin;
    static WorkplaceExperienceReportRepository reportRepo;
    static WorkplaceScopedExperienceReportService reports;
    static WorkplaceExperienceFacilitiesRepository facilityRepo;
    static WorkplaceScopedExperienceFacilitiesService facilities;
    static WorkplaceExperienceCollaborationRepository collaborationRepo;
    static ObjectMapper mapper;

    @BeforeAll static void migrate() {
        var ds = new PGSimpleDataSource(); ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername()); ds.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("filesystem:src/main/resources/db/migration",
                "filesystem:../dwp-core/src/main/resources/db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        mapper = new ObjectMapper().findAndRegisterModules();
        catalog = new WorkplaceCatalogRepository(jdbc, mapper); bookings = new WorkplaceBookingRepository(jdbc, mapper);
        spatial = new WorkplaceSpatialGovernanceRepository(jdbc, mapper);
        var governance = new WorkplaceSpatialGovernanceService(spatial, mapper);
        runtime = new WorkplaceRuntimeGovernance(governance);
        workplace = new WorkplaceService(catalog, bookings, mock(CalendarService.class), mock(TenantMediaStorage.class),
                mock(WorkplaceFloorPlanValidator.class), mock(WorkplaceMediaCleanupRepository.class), governance,
                new WorkplaceReleaseWindowRepository(jdbc), mock(WorkplaceDomainEvents.class), runtime);
        guard = new WorkplaceDelegatedAdminScopeGuard(new WorkplaceDelegatedAdminScopeRepository(new NamedParameterJdbcTemplate(jdbc)));
        catalogAdmin = new WorkplaceScopedCatalogAdminService(workplace, catalog, guard);
        collaborationRepo = new WorkplaceExperienceCollaborationRepository(jdbc);
        reportRepo = new WorkplaceExperienceReportRepository(new NamedParameterJdbcTemplate(jdbc));
        reports = new WorkplaceScopedExperienceReportService(new WorkplaceExperienceReportService(reportRepo, catalog,
                new WorkplaceExperienceReportPolicyPreview(catalog, runtime), collaborationRepo), reportRepo, guard);
        facilityRepo = new WorkplaceExperienceFacilitiesRepository(new NamedParameterJdbcTemplate(jdbc));
        facilities = new WorkplaceScopedExperienceFacilitiesService(new WorkplaceExperienceFacilitiesService(facilityRepo,
                bookings, catalog, runtime, workplace), facilityRepo, guard);
    }

    @Test void siteProjectionAndContentUseActualPermissionSpecificUserGroupFloorUnion() {
        var f = fixture(); var request = request(f, false);
        grant(f, "USER", null, f.first(), "CATALOG_MANAGE");
        grant(f, "GROUP_REF", f.group(), f.second(), "CATALOG_VIEW");
        var read = guard.scope(request, f.site(), DelegatedPermission.CATALOG_VIEW);
        var write = guard.scope(request, f.site(), DelegatedPermission.CATALOG_MANAGE);
        var result = tx.execute(status -> catalogAdmin.sites(f.tenant(), "en", List.of(read))).getFirst();
        assertThat(result.totalFloorCount()).isNull(); assertThat(result.countsScope()).isEqualTo("FLOORS");
        assertThat(result.allowedFloorIds()).containsExactlyInAnyOrder(f.first(), f.second());
        assertThat(result.configuredFloorCount()).isEqualTo(2); assertThat(result.resourceCount()).isEqualTo(2);
        List<WorkplaceDtos.Floor> floors = tx.execute(status -> catalogAdmin.floors(f.tenant(), "en", read));
        assertThat(floors).extracting(WorkplaceDtos.Floor::floorId).containsExactly(f.first(), f.second());
        assertThat(tx.execute(status -> guard.revalidate(write)).floorIds()).containsExactly(f.first());
        forbidden(() -> tx.execute(status -> catalogAdmin.resources(f.tenant(), f.third(), "en", read)));
        var full = guard.scope(request(f, true), f.site(), DelegatedPermission.CATALOG_VIEW);
        var fullSite = tx.execute(status -> catalogAdmin.sites(f.tenant(), "en", List.of(full))).getFirst();
        assertThat(fullSite.totalFloorCount()).isEqualTo(30); assertThat(fullSite.configuredFloorCount()).isEqualTo(3);
        assertThat(fullSite.resourceCount()).isEqualTo(3); assertThat(fullSite.allowedFloorIds()).isNull();
        var serialized = mapper.valueToTree(result);
        assertThat(serialized.get("totalFloorCount").isNull()).isTrue();
        assertThat(serialized.get("configuredFloorCount").asInt()).isEqualTo(2);
    }

    @Test void reportAllAggregatesPreviousCohortExceptionsAndPolicyDailyImpactExcludeForbiddenFloors() {
        var f = fixture(); grant(f, "USER", null, f.first(), "CATALOG_VIEW", "POLICY_MANAGE");
        var read = guard.scope(request(f, false), f.site(), DelegatedPermission.CATALOG_VIEW);
        LocalDate day = LocalDate.now(ZONE).minusDays(1); OffsetDateTime start = at(day);
        booking(f, f.r1(), start.plusHours(9), "COMPLETED");
        booking(f, f.r1(), start.plusHours(11), "NO_SHOW");
        UUID hidden = booking(f, f.r3(), start.plusHours(9), "NO_SHOW");
        booking(f, f.r3(), start.minusDays(1).plusHours(9), "COMPLETED");
        jdbc.update("UPDATE wp_resources SET updated_at = '2099-01-01T00:00:00Z' WHERE resource_id = ?", f.r3());
        var result = tx.execute(status -> reports.report(f.tenant(), null, day, day.plusDays(1), 0, 1, read));
        assertThat(result.scope().countsScope()).isEqualTo("FLOORS");
        assertThat(result.scope().allowedFloorIds()).containsExactly(f.first());
        assertThat(result.current().configuredFloors()).isOne(); assertThat(result.current().reservableResources()).isOne();
        assertThat(result.summary().bookingCount()).isEqualTo(2); assertThat(result.summary().bookedMinutes()).isEqualTo(60);
        assertThat(result.summary().denominatorResourceMinutes()).isEqualTo(1440);
        assertThat(result.summary().noShowPercent()).isEqualTo(50); assertThat(result.floors()).hasSize(1);
        assertThat(result.comparison().previous().bookingCount()).isZero();
        assertThat(result.hourlyHeatmap()).hasSize(24); assertThat(result.dailyTrend()).hasSize(1);
        assertThat(result.hourlyHeatmap().stream().mapToDouble(HeatmapCell::bookedMinutes).sum()).isEqualTo(60);
        assertThat(result.exceptions().totalElements()).isOne();
        assertThat(result.exceptions().content()).extracting(BookingDetail::bookingId).doesNotContain(hidden);
        assertThat(result.externalSources().getFirst().configurationStatus()).isNull();
        assertThat(result.metadata().sourceUpdatedAt()).isBefore(OffsetDateTime.parse("2099-01-01T00:00:00Z"));
        forbidden(() -> tx.execute(status -> reports.booking(f.tenant(), hidden, read)));
        forbidden(() -> tx.execute(status -> reports.report(f.tenant(), f.third(), day, day.plusDays(1), 0, 20, read)));
        OffsetDateTime future = at(LocalDate.now(ZONE).plusDays(2)).plusHours(9);
        booking(f, f.r1(), future, "RESERVED"); booking(f, f.r3(), future, "RESERVED");
        var policy = guard.scope(request(f, false), f.site(), DelegatedPermission.POLICY_MANAGE);
        var preview = tx.execute(status -> reports.policyImpact(f.tenant(), null, future, future.plusDays(1),
                new PolicyChanges(null, null, 120, null, null, null), 0, 1, policy));
        assertThat(preview.reviewedBookings()).isOne(); assertThat(preview.affectedBookings()).isOne();
        assertThat(preview.dailyImpact().stream().mapToLong(PolicyDailyImpact::reviewedBookings).sum()).isOne();
        forbidden(() -> tx.execute(status -> reports.futureImpact(f.tenant(), f.r3(), future, future.plusDays(1), 0, 20, read)));
    }

    @Test void facilityPagingCountDetailAndReplayUseCanonicalAuthorizedFloors() {
        var f = fixture(); grant(f, "USER", null, f.first(), "CATALOG_MANAGE");
        var read = guard.scope(request(f, false), f.site(), DelegatedPermission.CATALOG_VIEW);
        var write = guard.scope(request(f, false), f.site(), DelegatedPermission.CATALOG_MANAGE);
        OffsetDateTime start = OffsetDateTime.now().plusDays(3);
        UUID first = closure(f, f.r1(), "allowed-1", start); closure(f, f.r1(), "allowed-2", start.plusHours(2));
        UUID hidden = closure(f, f.r3(), "hidden-key", start);
        UUID visibleRequest = facilityRequest(f, f.r1(), "req1"); UUID secondRequest = facilityRequest(f, f.r1(), "req2");
        UUID hiddenRequest = facilityRequest(f, f.r3(), "req3");
        var page = tx.execute(status -> facilities.closures(f.tenant(), null, null, start.minusHours(1),
                start.plusDays(1), false, 1, 1, read));
        assertThat(page.totalElements()).isEqualTo(2); assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content()).hasSize(1).allSatisfy(row -> assertThat(row.floorId()).isEqualTo(f.first()));
        assertThat(page.countsScope()).isEqualTo("FLOORS");
        assertThat(tx.execute(status -> facilities.closure(f.tenant(), first, read)).closureId()).isEqualTo(first);
        var requests = tx.execute(status -> facilities.requests(f.tenant(), null, null, 0, 1, read));
        assertThat(requests.totalElements()).isEqualTo(2); assertThat(requests.totalPages()).isEqualTo(2);
        assertThat(requests.content()).hasSize(1).allSatisfy(row -> assertThat(row.requestId()).isIn(visibleRequest, secondRequest));
        var nextRequests = tx.execute(status -> facilities.requests(f.tenant(), null, null, 1, 1, read));
        assertThat(nextRequests.content()).hasSize(1);
        assertThat(nextRequests.content().getFirst().requestId()).isNotEqualTo(requests.content().getFirst().requestId());
        forbidden(() -> tx.execute(status -> facilities.closure(f.tenant(), hidden, read)));
        forbidden(() -> tx.execute(status -> facilities.requests(f.tenant(), f.third(), null, 0, 20, read)));
        forbidden(() -> tx.execute(status -> facilities.status(f.tenant(), ACTOR, hiddenRequest,
                new ChangeRequestStatus(RequestStatus.RESOLVED, 0L, "checked", true), null, write)));
        forbidden(() -> tx.execute(status -> facilities.createClosure(f.tenant(), ACTOR, f.r1(), "hidden-key",
                new CreateClosure(start, start.plusHours(1), 0L, "native reason", true), null, "ADMIN.WORKPLACE:CREATE", write)));
        assertThat(jdbc.queryForObject("SELECT request_status FROM wp_experience_facility_requests WHERE request_id = ?", String.class, hiddenRequest)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wp_experience_facility_closures WHERE tenant_id = ?", Long.class, f.tenant())).isEqualTo(3);
        var updated = tx.execute(status -> facilities.status(f.tenant(), ACTOR, visibleRequest,
                new ChangeRequestStatus(RequestStatus.RESOLVED, 0L, "native inspected", true), null, write));
        assertThat(updated.status()).isEqualTo(RequestStatus.RESOLVED); assertThat(updated.version()).isOne();
    }

    @Test void catalogSiteWideMutationsForeignTargetsAndWrongPermissionScopeRollback() {
        var f = fixture(); var foreign = fixture(); grant(f, "USER", null, f.first(), "CATALOG_MANAGE");
        var write = guard.scope(request(f, false), f.site(), DelegatedPermission.CATALOG_MANAGE);
        var read = guard.scope(request(f, false), f.site(), DelegatedPermission.CATALOG_VIEW);
        forbidden(() -> tx.execute(status -> catalogAdmin.saveSite(f.tenant(), ACTOR, f.site(), "en", null,
                new WorkplaceDtos.SiteRequest("SITE_CHANGED", "edited", "edited", SiteType.HEADQUARTERS, "", "Asia/Seoul", 30, SiteState.ACTIVE, 0L), write)));
        forbidden(() -> tx.execute(status -> catalogAdmin.saveFloor(f.tenant(), ACTOR, f.site(), null, "en", null, null, write)));
        forbidden(() -> tx.execute(status -> catalogAdmin.saveResource(f.tenant(), ACTOR, f.third(), f.r3(), "en", null, input(0L), write)));
        forbidden(() -> tx.execute(status -> catalogAdmin.saveResource(f.tenant(), ACTOR, foreign.first(), foreign.r1(), "en", null, input(0L), write)));
        forbidden(() -> tx.execute(status -> catalogAdmin.saveResource(f.tenant(), ACTOR, f.first(), f.r3(), "en", null, input(0L), write)));
        forbidden(() -> tx.execute(status -> catalogAdmin.saveResource(f.tenant(), ACTOR, f.first(), f.r1(), "en", null, input(0L), read)));
        assertThat(catalog.resource(f.tenant(), f.r3(), false).orElseThrow().version()).isZero();
        var saved = tx.execute(status -> catalogAdmin.saveResource(f.tenant(), ACTOR, f.first(), f.r1(), "en", null, input(0L), write));
        assertThat(saved.version()).isOne(); assertThat(saved.floorId()).isEqualTo(f.first());
        assertThat(catalog.site(f.tenant(), f.site(), false).orElseThrow().nameEn()).isEqualTo("Seoul");
    }

    @Test void mediaGuardRunsBeforeMetadataBytesStorageAndActualPhotoVersionMutation() {
        var f = fixture(); grant(f, "USER", null, f.first(), "CATALOG_MANAGE");
        var read = guard.scope(request(f, false), f.site(), DelegatedPermission.CATALOG_VIEW);
        var write = guard.scope(request(f, false), f.site(), DelegatedPermission.CATALOG_MANAGE);
        var storage = mock(TenantMediaStorage.class); var collaboration = new WorkplaceExperienceCollaborationService(collaborationRepo, runtime, spatial, mapper);
        var nativeMedia = new WorkplaceExperienceCollaborationMediaService(collaborationRepo, runtime, collaboration, storage,
                mock(WorkplaceMediaCleanupRepository.class), mock(WorkplaceFloorPlanValidator.class));
        var media = new WorkplaceScopedExperienceMediaService(nativeMedia, guard);
        String hash = "a".repeat(64);
        collaborationRepo.savePhoto(f.tenant(), ACTOR, new WorkplaceExperienceCollaborationRepository.PhotoRow(f.r1(), f.tenant() + "/native-photo", "real", "image/png", 3, hash, 0), 0);
        collaborationRepo.savePhoto(f.tenant(), ACTOR, new WorkplaceExperienceCollaborationRepository.PhotoRow(f.r3(), f.tenant() + "/hidden-photo", "private", "image/png", 3, hash, 0), 0);
        forbidden(() -> tx.execute(status -> media.adminMetadata(f.tenant(), f.r3(), read)));
        forbidden(() -> tx.execute(status -> media.adminContent(f.tenant(), f.r3(), read)));
        forbidden(() -> tx.execute(status -> media.upload(f.tenant(), ACTOR, f.r3(), 0, "inspect", "real", null, null, write)));
        forbidden(() -> tx.execute(status -> media.delete(f.tenant(), ACTOR, f.r3(), 0, "inspect", null, write)));
        verifyNoInteractions(storage);
        when(storage.load(f.tenant(), f.tenant() + "/native-photo")).thenReturn(new ByteArrayResource(new byte[]{1, 2, 3}));
        var content = tx.execute(status -> media.adminContent(f.tenant(), f.r1(), read));
        assertThat(content.metadata().sha256()).isEqualTo(hash); assertThat(content.resource()).isInstanceOf(ByteArrayResource.class);
        var deleted = tx.execute(status -> media.delete(f.tenant(), ACTOR, f.r1(), content.metadata().version(), "removed", null, write));
        assertThat(deleted.removed()).isTrue(); assertThat(collaborationRepo.photo(f.tenant(), f.r1())).isEmpty();
        assertThat(collaborationRepo.photo(f.tenant(), f.r3())).isPresent();
    }

    @Test void freshRevalidationNeverUsesStaleWhitelistAfterCommittedRevoke() {
        var f = fixture(); UUID id = grant(f, "USER", null, f.first(), "CATALOG_MANAGE");
        var scope = guard.scope(request(f, false), f.site(), DelegatedPermission.CATALOG_MANAGE);
        jdbc.update("UPDATE wp_delegated_admin_scopes SET lifecycle_state = 'REVOKED', version = version + 1 WHERE delegation_id = ?", id);
        forbidden(() -> tx.execute(status -> catalogAdmin.saveResource(f.tenant(), ACTOR, f.first(), f.r1(), "en", null, input(0L), scope)));
        assertThat(catalog.resource(f.tenant(), f.r1(), false).orElseThrow().version()).isZero();
    }

    @Test void catalogReadHoldsActualGrantShareLockUntilNativeTransactionCommit() throws Exception {
        var f = fixture(); UUID id = grant(f, "USER", null, f.first(), "CATALOG_VIEW");
        var scope = guard.scope(request(f, false), f.site(), DelegatedPermission.CATALOG_VIEW);
        var holding = new CountDownLatch(1); var finish = new CountDownLatch(1); var revoking = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var read = executor.submit(() -> tx.execute(status -> {
                assertThat(catalogAdmin.floors(f.tenant(), "en", scope)).hasSize(1); holding.countDown();
                await(finish); return true;
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            var revoke = executor.submit(() -> tx.execute(status -> {
                revoking.countDown(); jdbc.update("UPDATE wp_delegated_admin_scopes SET lifecycle_state = 'REVOKED', version = version + 1 WHERE delegation_id = ?", id); return true;
            }));
            assertThat(revoking.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> revoke.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            finish.countDown(); assertThat(read.get(10, TimeUnit.SECONDS)).isTrue(); assertThat(revoke.get(10, TimeUnit.SECONDS)).isTrue();
            forbidden(() -> tx.execute(status -> catalogAdmin.floors(f.tenant(), "en", scope)));
        } finally { finish.countDown(); }
    }

    private static Fixture fixture() {
        long tenant = TENANTS.incrementAndGet(); UUID site = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_service_tenants(provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,data_region,isolation_model,created_by,updated_by) VALUES (?, ?, ?, 'floor consumer','ACTIVE','kr','POOL',75301,75301)", UUID.randomUUID(), tenant, "floor_" + tenant);
        jdbc.update("INSERT INTO wp_tenant_policies(tenant_id) VALUES (?)", tenant);
        jdbc.update("INSERT INTO wp_sites(site_id,tenant_id,site_code,name_ko,name_en,time_zone,total_floor_count) VALUES (?, ?, ?, 'Seoul', 'Seoul', 'Asia/Seoul',30)", site, tenant, "SITE_" + tenant);
        UUID first = floor(tenant, site, 11), second = floor(tenant, site, 12), third = floor(tenant, site, 13);
        return new Fixture(tenant, site, first, second, third, resource(tenant, first), resource(tenant, second), resource(tenant, third), UUID.randomUUID());
    }
    private static UUID floor(long tenant, UUID site, int number) {
        UUID id = UUID.randomUUID(); jdbc.update("INSERT INTO wp_floors(floor_id,tenant_id,site_id,floor_number,name_ko,name_en) VALUES (?, ?, ?, ?, ?, ?)", id, tenant, site, number, number + "F", number + "F"); return id;
    }
    private static UUID resource(long tenant, UUID floor) {
        UUID id = UUID.randomUUID(); jdbc.update("INSERT INTO wp_resources(resource_id,tenant_id,floor_id,resource_code,name_ko,name_en,resource_type) VALUES (?, ?, ?, ?, 'Desk', 'Desk', 'DESK')", id, tenant, floor, "R_" + id); return id;
    }
    private static UUID grant(Fixture f, String type, UUID group, UUID floor, String... permissions) {
        UUID id = UUID.randomUUID(); tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO wp_delegated_admin_scopes(delegation_id,tenant_id,delegate_type,delegate_user_id,delegate_group_ref,scope_type,site_id,permission_codes,floor_scope_restricted) VALUES (?, ?, ?, ?, ?, 'SITE', ?, ?, TRUE)",
                    id, f.tenant(), type, "USER".equals(type) ? ACTOR : null, group, f.site(), permissions);
            jdbc.update("INSERT INTO wp_delegated_admin_scope_floors(tenant_id,delegation_id,site_id,floor_id) VALUES (?, ?, ?, ?)", f.tenant(), id, f.site(), floor);
        }); return id;
    }
    private static MockHttpServletRequest request(Fixture f, boolean global) {
        var request = new MockHttpServletRequest(); request.addHeader("X-DWP-Tenant-ID", f.tenant());
        request.addHeader("X-DWP-User-ID", ACTOR); request.addHeader("X-DWP-Roles", global ? "TENANT_ADMIN" : "USER");
        request.addHeader("X-DWP-Group-Refs", f.group()); return request;
    }
    private static UUID booking(Fixture f, UUID resource, OffsetDateTime start, String status) {
        UUID id = bookings.createBooking(f.tenant(), BOOKERS.incrementAndGet(), null, "Private identity", new WorkplaceDtos.BookingRequest(resource, start, start.plusHours(1), null, false), catalog.policy(f.tenant()), null, false).bookingId();
        jdbc.update("UPDATE wp_bookings SET booking_status = ? WHERE booking_id = ?", status, id); return id;
    }
    private static UUID closure(Fixture f, UUID resource, String key, OffsetDateTime start) {
        return facilityRepo.createClosure(f.tenant(), ACTOR, resource, key, "a".repeat(64), new CreateClosure(start, start.plusHours(1), 0L, "native reason", true));
    }
    private static UUID facilityRequest(Fixture f, UUID resource, String key) {
        return facilityRepo.createRequest(f.tenant(), ACTOR, resource, key, "a".repeat(64), new CreateRequest(Category.REPAIR, "actual request"));
    }
    private static WorkplaceDtos.ResourceRequest input(Long version) {
        return new WorkplaceDtos.ResourceRequest("DESK_UPDATED", "updated", "updated", ResourceType.DESK, BookingMode.RESERVABLE,
                ResourceState.AVAILABLE, null, 1, List.of(), false, false, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.TEN, BigDecimal.TEN, 0, null, null, null, version);
    }
    private static OffsetDateTime at(LocalDate date) { return date.atStartOfDay(ZONE).toOffsetDateTime(); }
    private static void forbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BaseException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private record Fixture(long tenant, UUID site, UUID first, UUID second, UUID third,
                           UUID r1, UUID r2, UUID r3, UUID group) { }
}
