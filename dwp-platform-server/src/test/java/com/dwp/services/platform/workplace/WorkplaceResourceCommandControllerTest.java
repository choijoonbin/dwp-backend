package com.dwp.services.platform.workplace;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceResourceCommandDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkplaceResourceCommandControllerTest {

    @Test
    void contextAndPreviewAreNoStoreAndBoundToTheCurrentActor() {
        WorkplaceResourceCommandService service = mock(WorkplaceResourceCommandService.class);
        WorkplaceResourceCommandController controller =
                new WorkplaceResourceCommandController(service);
        UUID booking = UUID.randomUUID();
        UUID preview = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T00:00:00Z");
        CommandContext context = new CommandContext(booking, UUID.randomUUID(),
                WorkplaceTypes.ResourceType.DESK, 7L, List.of(), now);
        PreviewRequest request = new PreviewRequest(
                ResourceCommandType.NFC_KEY_RESEND, 7L, Map.of());
        CommandPreview value = new CommandPreview(preview, booking, UUID.randomUUID(),
                ResourceCommandType.NFC_KEY_RESEND, 7L, Map.of(),
                ResourceCommandProviderCapability.NFC, ResourceCommandProviderState.READY,
                "nfc", 3L, true, List.of("CURRENT_NFC_KEY_WILL_BE_REPLACED"),
                List.of(), now.plusMinutes(5), now);
        when(service.context(42L, 99L, booking)).thenReturn(context);
        when(service.preview(42L, 99L, booking, request)).thenReturn(value);
        MockHttpServletResponse contextResponse = new MockHttpServletResponse();
        MockHttpServletResponse previewResponse = new MockHttpServletResponse();

        assertThat(controller.context(
                42L, 99L, "APP.WORKPLACE:VIEW", booking, contextResponse).getData())
                .isEqualTo(context);
        assertThat(controller.preview(
                42L, 99L, "APP.WORKPLACE:UPDATE", booking, request, previewResponse).getData())
                .isEqualTo(value);
        assertThat(contextResponse.getHeader("Cache-Control"))
                .isEqualTo("private, no-store, max-age=0");
        assertThat(previewResponse.getHeader("Cache-Control"))
                .isEqualTo("private, no-store, max-age=0");
    }

    @Test
    void executeAndReconcileRequireElevatedAccessAndForwardIdempotency() {
        WorkplaceResourceCommandService service = mock(WorkplaceResourceCommandService.class);
        WorkplaceResourceCommandController controller =
                new WorkplaceResourceCommandController(service);
        UUID booking = UUID.randomUUID();
        UUID preview = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        ExecuteRequest execute = new ExecuteRequest(preview, 7L, "Replace lost key", true);
        ReconcileRequest reconcile = new ReconcileRequest("Confirm provider outcome", true);
        CommandReceipt unknown = receipt(command, preview, booking,
                ResourceCommandState.RESULT_UNKNOWN, true);
        CommandReceipt succeeded = receipt(command, preview, booking,
                ResourceCommandState.SUCCEEDED, false);
        when(service.execute(42L, 99L, booking, "execute-key", "corr", execute))
                .thenReturn(unknown);
        when(service.reconcile(
                42L, 99L, booking, command, "reconcile-key", "corr", reconcile))
                .thenReturn(succeeded);

        var executeResponse = controller.execute(
                42L, 99L, "APP.WORKPLACE:UPDATE", "ELEVATED", "execute-key", "corr",
                booking, execute);
        var reconcileResponse = controller.reconcile(
                42L, 99L, "APP.WORKPLACE:UPDATE", "ELEVATED", "reconcile-key", "corr",
                booking, command, reconcile);

        assertThat(executeResponse.getBody().getData()).isEqualTo(unknown);
        assertThat(reconcileResponse.getBody().getData()).isEqualTo(succeeded);
        assertThat(executeResponse.getHeaders().getCacheControl())
                .isEqualTo("private, no-store, max-age=0");
        verify(service).execute(42L, 99L, booking, "execute-key", "corr", execute);
        verify(service).reconcile(
                42L, 99L, booking, command, "reconcile-key", "corr", reconcile);
    }

    @Test
    void physicalCommandsFailClosedWithoutPermissionOrStepUp() {
        WorkplaceResourceCommandController controller =
                new WorkplaceResourceCommandController(mock(WorkplaceResourceCommandService.class));
        UUID booking = UUID.randomUUID();
        ExecuteRequest request = new ExecuteRequest(
                UUID.randomUUID(), 1L, "Physical action", true);

        assertThatThrownBy(() -> controller.execute(
                42L, 99L, "APP.WORKPLACE:VIEW", "ELEVATED", "key", null,
                booking, request)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> controller.execute(
                42L, 99L, "APP.WORKPLACE:UPDATE", "NORMAL", "key", null,
                booking, request)).isInstanceOf(BaseException.class);
    }

    private static CommandReceipt receipt(
            UUID commandId,
            UUID previewId,
            UUID bookingId,
            ResourceCommandState state,
            boolean requery) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T00:00:00Z");
        return new CommandReceipt(commandId, previewId, bookingId, UUID.randomUUID(),
                ResourceCommandType.NFC_KEY_RESEND, state, "PROVIDER_TEST", "provider-op", 2L,
                "/v1/workplace/bookings/" + bookingId + "/resource-commands/" + commandId,
                "corr", now, requery ? null : now, now, requery, false);
    }
}
