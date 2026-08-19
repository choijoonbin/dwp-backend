package com.dwp.services.platform.workplace;

import com.dwp.services.platform.calendar.CalendarService;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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

    private WorkplaceService service;

    @BeforeEach
    void setUp() {
        service = new WorkplaceService(
                catalog, bookings, calendarService, mediaStorage, floorPlanValidator);
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
                1L, 9L, null, floorId, from, to, "ko-KR");

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
                1L, 9L, UUID.randomUUID(), floorId, from, to, "en-US")
                .resources().getFirst();

        assertThat(result.assignedToCurrentUser()).isFalse();
        assertThat(result.assignedUserId()).isNull();
        assertThat(result.assignedPersonPublicId()).isNull();
        assertThat(result.assignedDisplayName()).isNull();
    }

    @Test
    void assignedSeatCannotBeReservedByAnotherMember() {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now().plusHours(2);
        when(catalog.resource(1L, resourceId, true)).thenReturn(Optional.of(
                resource(resourceId, floorId, ResourceType.DESK, BookingMode.ASSIGNED, 7L)));
        when(catalog.floor(1L, floorId, true)).thenReturn(Optional.of(floor(siteId, floorId)));
        when(catalog.site(1L, siteId, true)).thenReturn(Optional.of(site(siteId)));
        when(catalog.policy(1L)).thenReturn(policy(true));

        WorkplaceDtos.BookingRequest request = new WorkplaceDtos.BookingRequest(
                resourceId, from, from.plusHours(1), "집중 업무", true);

        assertThatThrownBy(() -> service.createBooking(
                1L, 9L, UUID.randomUUID(), "사용자", "ko-KR", "corr", request))
                .hasMessageContaining("assigned workspace");
    }

    @Test
    void assignedSeatCanBeReservedWhenTenantEnablesLending() {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.now().plusHours(2);
        WorkplaceDtos.BookingRequest request = new WorkplaceDtos.BookingRequest(
                resourceId, from, from.plusHours(1), "공유 고정석", true);
        when(catalog.resource(1L, resourceId, true)).thenReturn(Optional.of(
                resource(resourceId, floorId, ResourceType.DESK, BookingMode.ASSIGNED, 7L)));
        when(catalog.floor(1L, floorId, true)).thenReturn(Optional.of(floor(siteId, floorId)));
        when(catalog.site(1L, siteId, true)).thenReturn(Optional.of(site(siteId)));
        when(catalog.policy(1L)).thenReturn(policy(true, true));
        when(bookings.createBooking(eq(1L), eq(9L), any(), eq("사용자"), eq(request), eq(true)))
                .thenReturn(new WorkplaceBookingRepository.BookingRow(
                        bookingId, resourceId, "고정 좌석", ResourceType.DESK,
                        "판교", "12층", request.purpose(), from, from.plusHours(1),
                        BookingStatus.RESERVED, true, null, null, 0));

        WorkplaceDtos.Booking result = service.createBooking(
                1L, 9L, UUID.randomUUID(), "사용자", "ko-KR", "corr", request);

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
                1L, 9L, null, null, from, to, "ko-KR");

        assertThat(result.sites()).extracting(WorkplaceDtos.Site::siteId)
                .containsExactly(activeSiteId);
        assertThat(result.floors()).extracting(WorkplaceDtos.Floor::floorId)
                .containsExactly(activeFloorId);
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
                1L, 9L, UUID.randomUUID(), "사용자", "ko-KR", "corr", request))
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
                1L, 9L, UUID.randomUUID(), "사용자", "ko-KR", "corr", request))
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

    private WorkplaceCatalogRepository.SiteRow site(UUID siteId) {
        return site(siteId, SiteState.ACTIVE);
    }

    private WorkplaceCatalogRepository.SiteRow site(UUID siteId, SiteState state) {
        return new WorkplaceCatalogRepository.SiteRow(
                siteId, "PANGYO", "판교", "판교", "Pangyo", SiteType.HEADQUARTERS,
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

    private WorkplaceCatalogRepository.PolicyRow policy(boolean showNames) {
        return policy(showNames, false);
    }

    private WorkplaceCatalogRepository.PolicyRow policy(
            boolean showNames,
            boolean allowAssignedDeskLending) {
        return new WorkplaceCatalogRepository.PolicyRow(
                30, 20, 30, 720, 5, LocalTime.of(8, 0), LocalTime.of(20, 0),
                true, true, 30, 30, allowAssignedDeskLending, showNames, 0);
    }
}
