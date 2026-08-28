package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.calendar.CalendarService;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class WorkplaceServiceTest {

    @Mock
    private WorkplaceCatalogRepository catalog;

    @Mock
    private WorkplaceBookingRepository bookings;

    @Mock
    private CalendarService calendarService;

    @Mock
    private TenantMediaStorage mediaStorage;

    @Mock
    private WorkplaceFloorPlanValidator floorPlanValidator;

    @Mock
    private WorkplaceMediaCleanupRepository mediaCleanup;

    @Mock
    private WorkplaceSpatialGovernanceService spatialGovernance;

    @Mock
    private WorkplaceReleaseWindowRepository releaseWindows;

    @Mock
    private WorkplaceDomainEvents domainEvents;

    @Mock
    private WorkplaceRuntimeGovernance runtimeGovernance;

    private WorkplaceService service;

    @BeforeEach
    void setUp() {
        service = new WorkplaceService(
                catalog, bookings, calendarService, mediaStorage,
                floorPlanValidator, mediaCleanup, spatialGovernance,
                releaseWindows, domainEvents, runtimeGovernance);
    }

    @Test
    void exploreHidesColleagueIdentityWhenTenantPolicyDisablesIt() {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now().plusHours(1);
        OffsetDateTime to = from.plusHours(1);
        WorkplaceCatalogRepository.FloorRow floor = floor(siteId, floorId);
        when(catalog.sites(1L, true)).thenReturn(List.of(site(siteId)));
        when(catalog.floors(1L, null, true)).thenReturn(List.of(floor));
        when(catalog.policy(1L)).thenReturn(policy(false));
        when(catalog.resources(1L, floorId, true)).thenReturn(List.of(
                resource(resourceId, floorId, ResourceType.DESK, BookingMode.RESERVABLE, null)));
        when(bookings.occupancy(1L, 9L, floorId, from, to)).thenReturn(List.of(
                new WorkplaceBookingRepository.OccupancyRow(
                        resourceId, UUID.randomUUID(), BookingStatus.RESERVED,
                        from, to, "동료 이름", false)));

        WorkplaceDtos.ExploreResponse result = service.explore(
                1L, 9L, null, floorId, from, to, "ko-KR", null);

        assertThat(result.occupancy()).singleElement()
                .extracting(WorkplaceDtos.Occupancy::bookedByDisplayName)
                .isNull();
    }

    @Test
    void exploreDoesNotExposeAnotherMembersFixedSeatIdentifiers() {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now().plusHours(1);
        OffsetDateTime to = from.plusHours(1);
        when(catalog.sites(1L, false)).thenReturn(List.of(site(siteId)));
        when(catalog.floors(1L, null, false)).thenReturn(List.of(floor(siteId, floorId)));
        when(catalog.policy(1L)).thenReturn(policy(false));
        when(catalog.resources(1L, floorId, false)).thenReturn(List.of(
                resource(resourceId, floorId, ResourceType.DESK, BookingMode.ASSIGNED, 7L)));
        when(bookings.occupancy(1L, 9L, floorId, from, to)).thenReturn(List.of());

        WorkplaceDtos.Resource result = service.explore(
                1L, 9L, UUID.randomUUID(), floorId, from, to, "en-US", null)
                .resources().getFirst();

        assertThat(result.assignedToCurrentUser()).isFalse();
        assertThat(result.assignedUserId()).isNull();
        assertThat(result.assignedPersonPublicId()).isNull();
        assertThat(result.assignedDisplayName()).isNull();
    }

    @Test
    void exploreDoesNotExposeAnotherMembersFixedSeatNameWhenTenantShowsColleagueNames() {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now().plusHours(1);
        OffsetDateTime to = from.plusHours(1);
        when(catalog.sites(1L, false)).thenReturn(List.of(site(siteId)));
        when(catalog.floors(1L, null, false)).thenReturn(List.of(floor(siteId, floorId)));
        when(catalog.policy(1L)).thenReturn(policy(true));
        when(catalog.resources(1L, floorId, false)).thenReturn(List.of(
                resource(resourceId, floorId, ResourceType.DESK, BookingMode.ASSIGNED, 7L)));
        when(bookings.occupancy(1L, 9L, floorId, from, to)).thenReturn(List.of());

        WorkplaceDtos.Resource result = service.explore(
                1L, 9L, UUID.randomUUID(), floorId, from, to, "en-US", null)
                .resources().getFirst();

        assertThat(result.assignedToCurrentUser()).isFalse();
        assertThat(result.assignedDisplayName()).isNull();
    }

    @Test
    void exploreLetsTheAssigneeRecognizeTheirOwnFixedSeat() {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now().plusHours(1);
        OffsetDateTime to = from.plusHours(1);
        when(catalog.sites(1L, false)).thenReturn(List.of(site(siteId)));
        when(catalog.floors(1L, null, false)).thenReturn(List.of(floor(siteId, floorId)));
        when(catalog.policy(1L)).thenReturn(policy(false));
        when(catalog.resources(1L, floorId, false)).thenReturn(List.of(
                resource(resourceId, floorId, ResourceType.DESK, BookingMode.ASSIGNED, 9L)));
        when(bookings.occupancy(1L, 9L, floorId, from, to)).thenReturn(List.of());

        WorkplaceDtos.Resource result = service.explore(
                1L, 9L, null, floorId, from, to, "en-US", null)
                .resources().getFirst();

        assertThat(result.assignedToCurrentUser()).isTrue();
        assertThat(result.assignedDisplayName()).isEqualTo("고정 좌석");
    }

    @Test
    void assignedSeatCannotBeReservedByAnotherMember() {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime from = nextWorkingDayStart();
        when(catalog.resource(1L, resourceId, true)).thenReturn(Optional.of(
                resource(resourceId, floorId, ResourceType.DESK, BookingMode.ASSIGNED, 7L)));
        when(catalog.floor(1L, floorId, true)).thenReturn(Optional.of(floor(siteId, floorId)));
        when(catalog.site(1L, siteId, true)).thenReturn(Optional.of(site(siteId)));
        when(catalog.policy(1L)).thenReturn(policy(true));

        WorkplaceDtos.BookingRequest request = new WorkplaceDtos.BookingRequest(
                resourceId, from, from.plusHours(1), "집중 업무", true);

        assertThatThrownBy(() -> service.createBooking(
                1L, 9L, UUID.randomUUID(), "사용자", "ko-KR", "corr", null, request))
                .hasMessageContaining("assigned workspace");
    }

    @Test
    void assignedSeatCanBeReservedWhenTenantEnablesLending() {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        OffsetDateTime from = nextWorkingDayStart();
        WorkplaceDtos.BookingRequest request = new WorkplaceDtos.BookingRequest(
                resourceId, from, from.plusHours(1), "공유 고정석", true);
        when(catalog.resource(1L, resourceId, true)).thenReturn(Optional.of(
                resource(resourceId, floorId, ResourceType.DESK, BookingMode.ASSIGNED, 7L)));
        when(catalog.floor(1L, floorId, true)).thenReturn(Optional.of(floor(siteId, floorId)));
        when(catalog.site(1L, siteId, true)).thenReturn(Optional.of(site(siteId)));
        when(catalog.policy(1L)).thenReturn(policy(true, true));
        UUID releaseWindowId = UUID.randomUUID();
        when(releaseWindows.coveringWindowForBooking(
                1L, resourceId, from, from.plusHours(1)))
                .thenReturn(Optional.of(releaseWindowId));
        when(bookings.createBooking(
                eq(1L), eq(9L), any(), eq("사용자"), eq(request),
                any(WorkplaceCatalogRepository.PolicyRow.class), eq(releaseWindowId), eq(true)))
                .thenReturn(new WorkplaceBookingRepository.BookingRow(
                        bookingId, resourceId, "고정 좌석", ResourceType.DESK,
                        "판교", "12층", request.purpose(), from, from.plusHours(1),
                        BookingStatus.RESERVED, true, null, null, 0));

        WorkplaceDtos.Booking result = service.createBooking(
                1L, 9L, UUID.randomUUID(), "사용자", "ko-KR", "corr", null, request);

        assertThat(result.bookingId()).isEqualTo(bookingId);
        assertThat(result.status()).isEqualTo(BookingStatus.RESERVED);
        assertThat(result.canCancel()).isTrue();
        assertThat(result.canCheckIn()).isFalse();
    }

    @Test
    void exploreExcludesLocationsThatAreNotOperational() {
        UUID activeSiteId = UUID.randomUUID();
        UUID closedSiteId = UUID.randomUUID();
        UUID activeFloorId = UUID.randomUUID();
        UUID draftFloorId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now().plusHours(1);
        OffsetDateTime to = from.plusHours(1);
        when(catalog.sites(1L, true)).thenReturn(List.of(
                site(activeSiteId), site(closedSiteId, SiteState.CLOSED)));
        when(catalog.floors(1L, null, true)).thenReturn(List.of(
                floor(activeSiteId, activeFloorId),
                floor(activeSiteId, draftFloorId, FloorState.DRAFT),
                floor(closedSiteId, UUID.randomUUID())));
        when(catalog.policy(1L)).thenReturn(policy(true));
        when(catalog.resources(1L, activeFloorId, true)).thenReturn(List.of());
        when(bookings.occupancy(1L, 9L, activeFloorId, from, to)).thenReturn(List.of());

        WorkplaceDtos.ExploreResponse result = service.explore(
                1L, 9L, null, null, from, to, "ko-KR", null);

        assertThat(result.sites()).extracting(WorkplaceDtos.Site::siteId)
                .containsExactly(activeSiteId);
        assertThat(result.floors()).extracting(WorkplaceDtos.Floor::floorId)
                .containsExactly(activeFloorId);
    }

    @Test
    void myBookingsImmediatelyHidesReservationsWhoseSiteViewWasRevoked() {
        UUID allowedSiteId = UUID.randomUUID();
        UUID deniedSiteId = UUID.randomUUID();
        UUID allowedFloorId = UUID.randomUUID();
        UUID deniedFloorId = UUID.randomUUID();
        UUID allowedResourceId = UUID.randomUUID();
        UUID deniedResourceId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now().minusDays(1);
        OffsetDateTime to = from.plusDays(7);
        WorkplaceBookingRepository.BookingRow allowed = bookingRow(
                allowedResourceId, from.plusDays(2));
        WorkplaceBookingRepository.BookingRow denied = bookingRow(
                deniedResourceId, from.plusDays(3));
        when(bookings.bookings(1L, 9L, from, to, true)).thenReturn(List.of(allowed, denied));
        when(catalog.policy(1L)).thenReturn(policy(true));
        when(catalog.resource(1L, allowedResourceId, false)).thenReturn(Optional.of(
                resource(allowedResourceId, allowedFloorId,
                        ResourceType.DESK, BookingMode.RESERVABLE, null)));
        when(catalog.resource(1L, deniedResourceId, false)).thenReturn(Optional.of(
                resource(deniedResourceId, deniedFloorId,
                        ResourceType.DESK, BookingMode.RESERVABLE, null)));
        when(catalog.floor(1L, allowedFloorId, false))
                .thenReturn(Optional.of(floor(allowedSiteId, allowedFloorId)));
        when(catalog.floor(1L, deniedFloorId, false))
                .thenReturn(Optional.of(floor(deniedSiteId, deniedFloorId)));
        doNothing().when(runtimeGovernance)
                .requireViewAccess(1L, 9L, "group-a", allowedSiteId);
        doThrow(new BaseException(ErrorCode.FORBIDDEN))
                .when(runtimeGovernance)
                .requireViewAccess(1L, 9L, "group-a", deniedSiteId);

        List<WorkplaceDtos.Booking> result = service.myBookings(
                1L, 9L, from, to, "ko-KR", "group-a");

        assertThat(result).extracting(WorkplaceDtos.Booking::resourceId)
                .containsExactly(allowedResourceId);
    }

    @Test
    void revokedBookPermissionBlocksEveryMemberLifecycleMutation() {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        WorkplaceBookingRepository.BookingRow current = bookingRow(
                bookingId, resourceId, OffsetDateTime.now().plusHours(2));
        when(bookings.booking(1L, 9L, bookingId, true)).thenReturn(Optional.of(current));
        when(catalog.resource(1L, resourceId, false)).thenReturn(Optional.of(
                resource(resourceId, floorId,
                        ResourceType.DESK, BookingMode.RESERVABLE, null)));
        when(catalog.floor(1L, floorId, false)).thenReturn(Optional.of(floor(siteId, floorId)));
        doThrow(new BaseException(ErrorCode.FORBIDDEN))
                .when(runtimeGovernance)
                .requireBookAccess(1L, 9L, "group-a", siteId);
        WorkplaceDtos.VersionRequest version = new WorkplaceDtos.VersionRequest(0L);

        assertThatThrownBy(() -> service.checkIn(
                1L, 9L, bookingId, "ko-KR", "corr", "group-a", version))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.cancelBooking(
                1L, 9L, bookingId, "ko-KR", "corr", "group-a", version))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.releaseBooking(
                1L, 9L, bookingId, "ko-KR", "corr", "group-a", version))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(bookings, never()).checkIn(any(), any(), any(), any(), any());
        verify(bookings, never()).cancel(any(), any(), any(), any(), any());
        verify(bookings, never()).release(any(), any(), any(), any(), any());
    }

    @Test
    void checkInRejectsTheExactReservationEndWithoutWriting() {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        OffsetDateTime endsAt = OffsetDateTime.parse("2026-08-19T01:00:00Z");
        OffsetDateTime startsAt = endsAt.minusMinutes(15);
        WorkplaceBookingRepository.BookingRow current =
                new WorkplaceBookingRepository.BookingRow(
                        bookingId, resourceId, "좌석", ResourceType.DESK,
                        "판교", "12층", "집중 업무", startsAt, endsAt,
                        BookingStatus.RESERVED, true, null, null, 0L);
        OffsetDateTime opens = startsAt.minusMinutes(current.checkInLeadMinutes());
        OffsetDateTime closes = startsAt.plusMinutes(current.autoReleaseMinutes());
        assertThat(WorkplaceService.withinCheckInWindow(
                endsAt, opens, closes, endsAt)).isFalse();

        when(bookings.booking(1L, 9L, bookingId, true)).thenReturn(Optional.of(current));
        when(catalog.resource(1L, resourceId, false)).thenReturn(Optional.of(
                resource(resourceId, floorId,
                        ResourceType.DESK, BookingMode.RESERVABLE, null)));
        when(catalog.floor(1L, floorId, false)).thenReturn(Optional.of(floor(siteId, floorId)));

        try (MockedStatic<OffsetDateTime> time =
                     mockStatic(OffsetDateTime.class, CALLS_REAL_METHODS)) {
            time.when(OffsetDateTime::now).thenReturn(endsAt);

            assertThatThrownBy(() -> service.checkIn(
                    1L, 9L, bookingId, "ko-KR", "corr", "group-a",
                    new WorkplaceDtos.VersionRequest(0L)))
                    .hasMessageContaining("outside the allowed arrival window");
        }

        verify(bookings, never()).checkIn(any(), any(), any(), any(), any());
    }

    @Test
    void closedSiteCannotBeBookedEvenWhenResourceIsAvailable() {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now().plusHours(2);
        when(catalog.resource(1L, resourceId, true)).thenReturn(Optional.of(
                resource(resourceId, floorId, ResourceType.DESK, BookingMode.RESERVABLE, null)));
        when(catalog.floor(1L, floorId, true)).thenReturn(Optional.of(floor(siteId, floorId)));
        when(catalog.site(1L, siteId, true)).thenReturn(Optional.of(site(siteId, SiteState.CLOSED)));
        when(catalog.policy(1L)).thenReturn(policy(true));

        WorkplaceDtos.BookingRequest request = new WorkplaceDtos.BookingRequest(
                resourceId, from, from.plusHours(1), "집중 업무", true);

        assertThatThrownBy(() -> service.createBooking(
                1L, 9L, UUID.randomUUID(), "사용자", "ko-KR", "corr", null, request))
                .hasMessageContaining("not open");
    }

    @Test
    void siteRejectsUnknownTimeZoneBeforePersistence() {
        WorkplaceDtos.SiteRequest request = new WorkplaceDtos.SiteRequest(
                "SEOUL", "서울", "Seoul", SiteType.HEADQUARTERS,
                "서울", "Asia/Not-A-Zone", 20, SiteState.ACTIVE, null);

        assertThatThrownBy(() -> service.saveSite(
                1L, 9L, null, "ko-KR", "corr", request))
                .hasMessageContaining("IANA time zone");
    }

    @Test
    void updateRequiresAnOptimisticVersionAndCannotEnterTheInsertPath() {
        WorkplaceDtos.SiteRequest request = new WorkplaceDtos.SiteRequest(
                "SEOUL", "서울", "Seoul", SiteType.HEADQUARTERS,
                "서울", "Asia/Seoul", 20, SiteState.ACTIVE, null);

        assertThatThrownBy(() -> service.saveSite(
                1L, 9L, UUID.randomUUID(), "ko-KR", "corr", request))
                .hasMessageContaining("requires its version");
    }

    @Test
    void meetingRoomMustUseCalendarAwareReservationFlow() {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now().plusHours(2);
        when(catalog.resource(1L, resourceId, true)).thenReturn(Optional.of(
                resource(resourceId, floorId, ResourceType.ROOM, BookingMode.RESERVABLE, null)));
        when(catalog.floor(1L, floorId, true)).thenReturn(Optional.of(floor(siteId, floorId)));
        when(catalog.site(1L, siteId, true)).thenReturn(Optional.of(site(siteId)));
        when(catalog.policy(1L)).thenReturn(policy(true));

        WorkplaceDtos.BookingRequest request = new WorkplaceDtos.BookingRequest(
                resourceId, from, from.plusHours(1), "회의", true);

        assertThatThrownBy(() -> service.createBooking(
                1L, 9L, UUID.randomUUID(), "사용자", "ko-KR", "corr", null, request))
                .hasMessageContaining("calendar-aware room flow");
    }

    @Test
    void assignedResourceRequiresADirectoryIdentityRatherThanADisplayLabel() {
        UUID floorId = UUID.randomUUID();
        WorkplaceDtos.ResourceRequest request = new WorkplaceDtos.ResourceRequest(
                "DESK-101", "고정 좌석", "Assigned desk", ResourceType.DESK,
                BookingMode.ASSIGNED, ResourceState.AVAILABLE, "집중 업무 존", 1,
                List.of("MONITOR"), false, false,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                0, null, null, "임의 표시명", null);

        assertThatThrownBy(() -> service.saveResource(
                1L, 9L, floorId, null, "ko-KR", "corr", request))
                .hasMessageContaining("require an assignee");
    }

    @Test
    void floorBackgroundRequiresSiteViewBeforeLoadingTenantMedia() {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        WorkplaceCatalogRepository.FloorRow floor = new WorkplaceCatalogRepository.FloorRow(
                floorId, siteId, "판교", 12, "12층", "12층", "12F",
                1200, 760, "/api/platform/v1/workplace/floors/x/background",
                "workplace/floors/x/plan.png", "image/png", 128L, "a".repeat(64),
                FloorState.ACTIVE, 1, 0);
        when(catalog.floor(1L, floorId, false)).thenReturn(Optional.of(floor));
        doThrow(new BaseException(ErrorCode.FORBIDDEN))
                .when(runtimeGovernance)
                .requireViewAccess(1L, 9L, "group-a", siteId);

        assertThatThrownBy(() -> service.floorBackground(1L, 9L, "group-a", floorId))
                .isInstanceOf(RuntimeException.class);

        verify(runtimeGovernance).requireViewAccess(1L, 9L, "group-a", siteId);
        verify(mediaStorage, never()).load(any(), any());
    }

    @Test
    void legacyFloorMutationsFailBeforeStorageOrCatalogWrites() {
        UUID floorId = UUID.randomUUID();

        assertThatThrownBy(() -> service.uploadFloorBackground(
                1L, 9L, floorId, 0L, "ko-KR", "corr", null))
                .hasMessageContaining("governed draft revision");
        assertThatThrownBy(() -> service.updateLayout(
                1L, 9L, floorId, "ko-KR", "corr", null))
                .hasMessageContaining("governed draft revision");

        verify(mediaStorage, never()).store(any(), any(), any(), any());
        verify(catalog, never()).updatePlacement(any(), any(), any(), any());
    }

    @Test
    void governedDraftBackgroundIsValidatedStoredAndAttachedToTheObservedRevision() {
        UUID floorId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        String storageKey = "1/workplace/floor-plan-revisions/" + revisionId + "/plan.png";
        var revision = floorPlanRevision(revisionId, floorId, null, null, 3L);
        when(spatialGovernance.floorPlanRevisionSnapshot(1L, revisionId))
                .thenReturn(new WorkplaceSpatialGovernanceDtos.FloorPlanRevisionSnapshot(
                        revision, List.of()));
        byte[] content = new byte[]{1, 2, 3};
        MockMultipartFile file = new MockMultipartFile(
                "file", "plan.png", "image/png", content);
        when(floorPlanValidator.validate(file)).thenReturn(
                new WorkplaceFloorPlanValidator.ValidatedFloorPlan(
                        content, "image/png", "png", content.length,
                        "a".repeat(64), 1200, 760));
        when(mediaStorage.store(
                1L, "workplace/floor-plan-revisions/" + revisionId, "png", content))
                .thenReturn(storageKey);
        when(spatialGovernance.updateFloorPlanRevisionMedia(
                eq(1L), eq(9L), eq(revisionId), eq("corr"), any()))
                .thenReturn(floorPlanRevision(
                        revisionId, floorId, storageKey,
                        "/api/platform/v1/admin/workplace/governance/floor-plan-revisions/"
                                + revisionId + "/background",
                        4L));

        var result = service.uploadDraftFloorBackground(
                1L, 9L, revisionId, 3L, "Updated evacuation plan", "corr", file);

        assertThat(result.version()).isEqualTo(4L);
        verify(mediaCleanup).registerStaged(1L, storageKey);
        ArgumentCaptor<WorkplaceSpatialGovernanceDtos.FloorPlanSnapshotRequest> request =
                ArgumentCaptor.forClass(
                        WorkplaceSpatialGovernanceDtos.FloorPlanSnapshotRequest.class);
        verify(spatialGovernance).updateFloorPlanRevisionMedia(
                eq(1L), eq(9L), eq(revisionId), eq("corr"), request.capture());
        assertThat(request.getValue().backgroundAssetPath())
                .isEqualTo("/api/platform/v1/admin/workplace/governance/floor-plan-revisions/"
                        + revisionId + "/background");
        assertThat(request.getValue().backgroundAssetKey()).isEqualTo(storageKey);
        assertThat(request.getValue().version()).isEqualTo(3L);
    }

    private WorkplaceSpatialGovernanceDtos.FloorPlanRevision floorPlanRevision(
            UUID revisionId,
            UUID floorId,
            String backgroundAssetKey,
            String backgroundAssetPath,
            long version) {
        return new WorkplaceSpatialGovernanceDtos.FloorPlanRevision(
                revisionId, floorId, 2, null, null,
                WorkplaceSpatialGovernanceDtos.RevisionState.DRAFT,
                1200, 760, backgroundAssetPath, backgroundAssetKey,
                backgroundAssetKey == null ? null : "image/png",
                backgroundAssetKey == null ? null : 3L,
                backgroundAssetKey == null ? null : "a".repeat(64),
                "Layout", "b".repeat(64), 0,
                null, null, null, null, version);
    }

    private WorkplaceCatalogRepository.SiteRow site(UUID siteId) {
        return site(siteId, SiteState.ACTIVE);
    }

    private OffsetDateTime nextWorkingDayStart() {
        return OffsetDateTime.now(ZoneId.of("Asia/Seoul"))
                .plusDays(1)
                .withHour(10)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
    }

    private WorkplaceCatalogRepository.SiteRow site(UUID siteId, SiteState state) {
        return new WorkplaceCatalogRepository.SiteRow(
                siteId, UUID.randomUUID(), "PANGYO", "판교", "판교", "Pangyo",
                SiteType.HEADQUARTERS,
                "성남시", "Asia/Seoul", 15, 1, 1, state, 0);
    }

    private WorkplaceCatalogRepository.FloorRow floor(UUID siteId, UUID floorId) {
        return floor(siteId, floorId, FloorState.ACTIVE);
    }

    private WorkplaceCatalogRepository.FloorRow floor(
            UUID siteId, UUID floorId, FloorState state) {
        return new WorkplaceCatalogRepository.FloorRow(
                floorId, siteId, "판교", 12, "12층", "12층", "12F",
                1200, 760, null, null, null, null, null,
                state, 1, 0);
    }

    private WorkplaceCatalogRepository.ResourceRow resource(
            UUID resourceId,
            UUID floorId,
            ResourceType type,
            BookingMode mode,
            Long assignedUserId) {
        return new WorkplaceCatalogRepository.ResourceRow(
                resourceId, floorId, type == ResourceType.ROOM ? UUID.randomUUID() : null,
                "RESOURCE-01", "공간 01", "공간 01", "Resource 01", type, mode,
                ResourceState.AVAILABLE, "집중 업무 존", 1, List.of("MONITOR"),
                false, false, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, 0, assignedUserId, null,
                assignedUserId == null ? null : "고정 좌석", 0, 0L);
    }

    private WorkplaceBookingRepository.BookingRow bookingRow(
            UUID resourceId, OffsetDateTime startsAt) {
        return bookingRow(UUID.randomUUID(), resourceId, startsAt);
    }

    private WorkplaceBookingRepository.BookingRow bookingRow(
            UUID bookingId, UUID resourceId, OffsetDateTime startsAt) {
        return new WorkplaceBookingRepository.BookingRow(
                bookingId, resourceId, "좌석", ResourceType.DESK,
                "판교", "12층", "집중 업무", startsAt, startsAt.plusHours(1),
                BookingStatus.RESERVED, true, null, null, 0L);
    }

    private WorkplaceCatalogRepository.PolicyRow policy(boolean showNames) {
        return policy(showNames, false);
    }

    private WorkplaceCatalogRepository.PolicyRow policy(
            boolean showNames,
            boolean allowAssignedDeskLending) {
        return new WorkplaceCatalogRepository.PolicyRow(
                30, 20, 30, 720, 5, LocalTime.of(8, 0), LocalTime.of(20, 0),
                true, true, 30, 30, allowAssignedDeskLending, showNames, 365, 0);
    }
}
