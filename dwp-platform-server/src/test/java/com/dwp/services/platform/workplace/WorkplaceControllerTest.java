package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkplaceControllerTest {

    @Mock
    private WorkplaceService service;

    @Mock
    private WorkplaceOperationsService operationsService;

    @Test
    void floorBackgroundRequiresAuthorityRevalidationBeforeCacheReuse() {
        UUID floorId = UUID.randomUUID();
        when(service.floorBackground(1L, 9L, "group-a", floorId)).thenReturn(
                new WorkplaceService.FloorBackground(
                        new ByteArrayResource(new byte[] {1}),
                        "image/png",
                        1L,
                        "floor-revision"));

        var response = new WorkplaceController(service)
                .workplaceFloorBackground(1L, 9L, "group-a", floorId);

        assertThat(response.getHeaders().getCacheControl())
                .contains("private")
                .contains("no-cache")
                .contains("must-revalidate")
                .doesNotContain("max-age");
    }

    @Test
    void lifecycleControllersForwardTheRequiredIdempotencyKey() {
        UUID bookingId = UUID.randomUUID();
        WorkplaceDtos.VersionRequest request = new WorkplaceDtos.VersionRequest(4L);
        WorkplaceController controller = new WorkplaceController(service);

        controller.checkInWorkplaceBooking(
                1L, 9L, "group-a", "en-US", "corr", "check-in-key", bookingId, request);
        controller.cancelWorkplaceBooking(
                1L, 9L, "group-a", "en-US", "corr", "cancel-key", bookingId, request);
        controller.releaseWorkplaceBooking(
                1L, 9L, "group-a", "en-US", "corr", "release-key", bookingId, request);

        verify(service).checkIn(
                1L, 9L, bookingId, "en-US", "corr", "group-a", "check-in-key", request);
        verify(service).cancelBooking(
                1L, 9L, bookingId, "en-US", "corr", "group-a", "cancel-key", request);
        verify(service).releaseBooking(
                1L, 9L, bookingId, "en-US", "corr", "group-a", "release-key", request);
    }

    @Test
    void relocateControllerForwardsTheRequiredIdempotencyKey() {
        UUID bookingId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        WorkplaceOperationsDtos.RelocateBookingRequest request =
                new WorkplaceOperationsDtos.RelocateBookingRequest(
                        UUID.randomUUID(),
                        java.time.OffsetDateTime.parse("2026-09-20T01:00:00Z"),
                        java.time.OffsetDateTime.parse("2026-09-20T02:00:00Z"),
                        "Move", 4L);

        new WorkplaceOperationsController(operationsService).relocateWorkplaceBooking(
                1L, 9L, personId, "en-US", "corr", "relocate-key",
                "group-a", bookingId, request);

        verify(operationsService).relocateBooking(
                1L, 9L, personId, bookingId, "en-US", "corr",
                "group-a", "relocate-key", request);
    }
}
