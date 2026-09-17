package com.dwp.services.platform.mail;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.DeliveryAuditItem;
import static com.dwp.services.platform.mail.MailWorkspaceDtos.DeliveryExportRequest;
import static com.dwp.services.platform.mail.MailWorkspaceDtos.DeliveryRecoveryRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminMailCompletionControllerTest {

    @Test
    void destructiveMailAdministrationRequiresFreshElevatedAccess() {
        assertThatThrownBy(() -> AdminMailCompletionController.requireElevated(null))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> AdminMailCompletionController.requireElevated("NORMAL"))
                .isInstanceOf(BaseException.class);
        assertThatCode(() -> AdminMailCompletionController.requireElevated("ELEVATED"))
                .doesNotThrowAnyException();
    }

    @Test
    void everyRecoveryActionUsesTheSameFailClosedEvidenceProjection() {
        AdminMailCompletionService service = mock(AdminMailCompletionService.class);
        AdminMailCompletionController controller = new AdminMailCompletionController(service);
        DeliveryRecoveryRequest request = new DeliveryRecoveryRequest(UUID.randomUUID(), 4L);
        DeliveryAuditItem response = new DeliveryAuditItem(
                UUID.randomUUID(), "message:safe", "SEND", "REDACTED", "REDACTED",
                "REDACTED", "FAILED", "FAILED", "INELIGIBLE", "UNKNOWN",
                "UNKNOWN", false, false, null, "", List.of(), 4L);

        UUID reconcileId = UUID.randomUUID();
        when(service.recoverDelivery(
                7, 91, reconcileId, "RECONCILE", "corr-reconcile", request, false))
                .thenReturn(response);
        controller.reconcileDelivery(
                7, 91, "ELEVATED", "ADMIN.MAIL:DELIVERY_RECONCILE",
                "corr-reconcile", reconcileId, request);
        verify(service).recoverDelivery(
                7, 91, reconcileId, "RECONCILE", "corr-reconcile", request, false);

        UUID retryId = UUID.randomUUID();
        when(service.recoverDelivery(
                7, 91, retryId, "RETRY", "corr-retry", request, false))
                .thenReturn(response);
        controller.retryDelivery(
                7, 91, "ELEVATED", "ADMIN.MAIL:DELIVERY_RETRY",
                "corr-retry", retryId, request);
        verify(service).recoverDelivery(
                7, 91, retryId, "RETRY", "corr-retry", request, false);

        UUID cancelId = UUID.randomUUID();
        when(service.recoverDelivery(
                7, 91, cancelId, "CANCEL", "corr-cancel", request, false))
                .thenReturn(response);
        controller.cancelDelivery(
                7, 91, "ELEVATED", "ADMIN.MAIL:DELIVERY_CANCEL",
                "corr-cancel", cancelId, request);
        verify(service).recoverDelivery(
                7, 91, cancelId, "CANCEL", "corr-cancel", request, false);

        UUID revealedId = UUID.randomUUID();
        when(service.recoverDelivery(
                7, 91, revealedId, "RETRY", "corr-revealed", request, true))
                .thenReturn(response);
        controller.retryDelivery(
                7, 91, "ELEVATED",
                "ADMIN.MAIL:AUDIT_READ,ADMIN.MAIL:AUDIT_REVEAL",
                "corr-revealed", revealedId, request);
        verify(service).recoverDelivery(
                7, 91, revealedId, "RETRY", "corr-revealed", request, true);

        assertThat(AdminMailCompletionController.canRevealDeliveryEvidence(
                "ADMIN.MAIL:AUDIT_REVEAL")).isFalse();
        assertThat(AdminMailCompletionController.canRevealDeliveryEvidence(
                "ADMIN.MAIL:AUDIT_READ,ADMIN.MAIL:AUDIT_REVEAL")).isTrue();
    }

    @Test
    void deliveryExportRequiresReadAndRevealBeforeIncludingSensitiveEvidence() {
        AdminMailCompletionService service = mock(AdminMailCompletionService.class);
        AdminMailCompletionController controller = new AdminMailCompletionController(service);
        DeliveryExportRequest request = new DeliveryExportRequest(
                Map.of(), "Investigate delivery", UUID.randomUUID());

        controller.createDeliveryExport(
                7, 91, "ELEVATED", "ADMIN.MAIL:AUDIT_REVEAL", request);
        verify(service).createDeliveryExport(7, 91, request, false);

        controller.createDeliveryExport(
                7, 92, "ELEVATED",
                "ADMIN.MAIL:AUDIT_READ,ADMIN.MAIL:AUDIT_REVEAL", request);
        verify(service).createDeliveryExport(7, 92, request, true);
    }
}
