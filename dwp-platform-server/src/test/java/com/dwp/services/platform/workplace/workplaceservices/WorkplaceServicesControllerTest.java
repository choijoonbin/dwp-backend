package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceFulfillmentController.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesController.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkplaceServicesControllerTest {
    private final WorkplaceServicesService service = mock(WorkplaceServicesService.class);
    private final WorkplaceServiceOrderQueryService queries =
            mock(WorkplaceServiceOrderQueryService.class);
    private final WorkplaceServiceLineAdjustmentService lineAdjustments =
            mock(WorkplaceServiceLineAdjustmentService.class);
    private final WorkplaceServiceAttachmentService attachments =
            mock(WorkplaceServiceAttachmentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new WorkplaceServicesController(service, queries, lineAdjustments),
            new WorkplaceServiceFulfillmentController(service, queries, lineAdjustments),
            new WorkplaceServiceCatalogAdminController(service),
            new WorkplaceServiceOrderCollaborationController(service, queries, attachments))
            .build();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void userCatalogReadIsNoStoreAndSubmitReturnsRecoverableReceipt() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T00:00:00Z");
        when(service.catalog(42, 99, ReservationAuthority.WORKPLACE, reservationId))
                .thenReturn(new ServiceCatalog(ReservationAuthority.WORKPLACE, reservationId, 4,
                        now.plusDays(1), now.plusDays(1).plusHours(1), "site", "room", "ROOM",
                        List.of(), now));
        String statusHref = "/v1/workplace/service-orders/" + orderId;
        when(service.submit(eq(42L), eq(99L), eq(reservationId), eq("stable-key"), any(), eq("c-18")))
                .thenReturn(new ServiceOrderCommandResult(null,
                        new CommandReceipt(UUID.randomUUID(), orderId, CommandState.ACCEPTED,
                                statusHref, false, "c-18", now)));

        mvc.perform(get("/v1/workplace/service-catalog")
                        .param("reservationAuthority", "WORKPLACE")
                        .param("reservationId", reservationId.toString())
                        .header(TENANT, 42)
                        .header(USER, 99)
                        .header(PERMISSIONS, VIEW))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.reservationVersion").value(4));

        SubmitRequest request = new SubmitRequest(previewId, 4, true, "Meeting support");
        mvc.perform(post("/v1/workplace/reservations/{reservationId}/service-orders", reservationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42)
                        .header(USER, 99)
                        .header(PERMISSIONS, UPDATE)
                        .header(IDEMPOTENCY, "stable-key")
                        .header(CORRELATION, "c-18")
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", statusHref))
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.receipt.serviceOrderId").value(orderId.toString()));
    }

    @Test
    void adminFulfillmentAndCatalogWritesRequireElevatedManageAndReturnReceipts() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T00:00:00Z");
        String orderHref = "/v1/admin/workplace/service-orders/" + orderId;
        String catalogHref = "/v1/admin/workplace/service-catalog/" + itemId;
        when(service.updateFulfillment(eq(42L), eq(99L), eq(orderId), eq(taskId),
                eq("fulfillment-key"), any(), eq("c-18")))
                .thenReturn(new ServiceOrderCommandResult(null,
                        new CommandReceipt(UUID.randomUUID(), orderId, CommandState.RESULT_UNKNOWN,
                                orderHref, false, "c-18", now)));
        when(service.changeCatalogState(eq(42L), eq(99L), eq(itemId), eq("catalog-key"),
                any(), eq("c-18")))
                .thenReturn(new CatalogCommandResult(null,
                        new CatalogCommandReceipt(UUID.randomUUID(), itemId, CommandState.SUCCEEDED,
                                catalogHref, false, "c-18", now)));

        FulfillmentUpdateRequest update = new FulfillmentUpdateRequest(1,
                WorkState.RESULT_UNKNOWN, null, null, null, null,
                "Provider outcome must be recovered", null, "Provider timeout", true);
        mvc.perform(post("/v1/admin/workplace/service-orders/{orderId}/tasks/{taskId}:update",
                        orderId, taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42)
                        .header(USER, 99)
                        .header(PERMISSIONS, ADMIN_MANAGE)
                        .header(ACCESS_MODE, "ELEVATED")
                        .header(IDEMPOTENCY, "fulfillment-key")
                        .header(CORRELATION, "c-18")
                        .content(mapper.writeValueAsBytes(update)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", orderHref))
                .andExpect(jsonPath("$.data.receipt.state").value("RESULT_UNKNOWN"));

        CatalogStateRequest state = new CatalogStateRequest(1, false, true, "Maintenance");
        mvc.perform(post("/v1/admin/workplace/service-catalog/{itemId}:state", itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42)
                        .header(USER, 99)
                        .header(PERMISSIONS, ADMIN_MANAGE)
                        .header(ACCESS_MODE, "ELEVATED")
                        .header(IDEMPOTENCY, "catalog-key")
                        .header(CORRELATION, "c-18")
                        .content(mapper.writeValueAsBytes(state)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", catalogHref))
                .andExpect(jsonPath("$.data.receipt.catalogItemId").value(itemId.toString()));

        assertThatThrownBy(() -> requirePermission("ADMIN.WORKPLACE:VIEW", ADMIN_MANAGE))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> requireElevated("NORMAL"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_REQUIRED));
    }

    @Test
    void lineCancellationUsesUserStatusAndAdminReconciliationRoutes() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        UUID adjustmentId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T00:00:00Z");
        when(lineAdjustments.preview(eq(42L), eq(99L), eq(orderId), eq(lineId), any()))
                .thenReturn(new LineCancellationImpact(previewId, orderId, lineId,
                        1, 1, 1, 0, 0, 1, RefundScope.FULL,
                        java.math.BigDecimal.valueOf(12_500), "KRW", true,
                        "Cancel line", now.plusMinutes(10), now));
        String userHref = "/v1/workplace/service-orders/" + orderId
                + "/line-adjustments/" + adjustmentId;
        String adminHref = "/v1/admin/workplace/service-orders/" + orderId
                + "/line-adjustments/" + adjustmentId;
        LineAdjustment userAdjustment = new LineAdjustment(adjustmentId, orderId, lineId,
                previewId, 1, RefundScope.FULL, java.math.BigDecimal.valueOf(12_500),
                java.math.BigDecimal.ZERO, "KRW", LineAdjustmentState.RESULT_UNKNOWN,
                null, null, null, 1, now, now);
        when(lineAdjustments.cancel(eq(42L), eq(99L), eq(orderId), eq(lineId),
                eq("line-key"), any(), eq("c-line")))
                .thenReturn(new LineAdjustmentCommandResult(userAdjustment, null,
                        new CommandReceipt(UUID.randomUUID(), orderId,
                                CommandState.RESULT_UNKNOWN, userHref, false, "c-line", now)));
        LineAdjustment adminAdjustment = new LineAdjustment(adjustmentId, orderId, lineId,
                previewId, 1, RefundScope.FULL, java.math.BigDecimal.valueOf(12_500),
                java.math.BigDecimal.valueOf(12_500), "KRW", LineAdjustmentState.REFUNDED,
                "provider-op", "refund-receipt", "Confirmed", 2, now, now);
        when(lineAdjustments.reconcile(eq(42L), eq(777L), eq(orderId), eq(adjustmentId),
                eq("reconcile-key"), any(), eq("c-reconcile")))
                .thenReturn(new LineAdjustmentCommandResult(adminAdjustment, null,
                        new CommandReceipt(UUID.randomUUID(), orderId,
                                CommandState.SUCCEEDED, adminHref, false, "c-reconcile", now)));

        mvc.perform(post("/v1/workplace/service-orders/{orderId}/lines/{lineId}/cancellation-impact:preview",
                        orderId, lineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 99).header(PERMISSIONS, UPDATE)
                        .content(mapper.writeValueAsBytes(
                                new LineCancellationImpactRequest(1, 1, 1, "Cancel line"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.cancellationPreviewId").value(previewId.toString()));
        mvc.perform(post("/v1/workplace/service-orders/{orderId}/lines/{lineId}:cancel",
                        orderId, lineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 99).header(PERMISSIONS, UPDATE)
                        .header(IDEMPOTENCY, "line-key").header(CORRELATION, "c-line")
                        .content(mapper.writeValueAsBytes(new LineCancellationRequest(
                                previewId, 1, 1, true, "Cancel line"))))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", userHref))
                .andExpect(jsonPath("$.data.adjustment.providerOperationReference").isEmpty());
        mvc.perform(post("/v1/admin/workplace/service-orders/{orderId}/line-adjustments/{adjustmentId}:reconcile",
                        orderId, adjustmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 777)
                        .header(PERMISSIONS, ADMIN_MANAGE).header(ACCESS_MODE, "ELEVATED")
                        .header(IDEMPOTENCY, "reconcile-key").header(CORRELATION, "c-reconcile")
                        .content(mapper.writeValueAsBytes(
                                new LineAdjustmentReconcileRequest(1, true, "Reconcile"))))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", adminHref))
                .andExpect(jsonPath("$.data.adjustment.refundReceiptReference")
                        .value("refund-receipt"));
    }

    @Test
    void scannerEvidenceRegistrationIsElevatedAndReturnsAdminStatusRoute() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T00:00:00Z");
        String href = "/v1/admin/workplace/service-orders/" + orderId
                + "/attachments/" + attachmentId + "/scan-status";
        ServiceOrderAttachment attachment = new ServiceOrderAttachment(attachmentId,
                "evidence.pdf", "application/pdf", 128, AttachmentScanState.CLEAN, 2,
                "scanner-evidence-18", "Clean", now, now);
        when(attachments.recordScan(eq(42L), eq(777L), eq(orderId), eq(attachmentId),
                eq("scan-key"), any(), eq("c-scan")))
                .thenReturn(new AttachmentScanCommandResult(attachment,
                        new CommandReceipt(UUID.randomUUID(), orderId, CommandState.SUCCEEDED,
                                href, false, "c-scan", now)));

        AttachmentScanRequest request = new AttachmentScanRequest(1,
                AttachmentScanVerdict.CLEAN, "scanner-evidence-18", "Clean", true,
                "Register scanner evidence");
        mvc.perform(post("/v1/admin/workplace/service-orders/{orderId}/attachments/{attachmentId}/scan-result",
                        orderId, attachmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 777)
                        .header(PERMISSIONS, ADMIN_MANAGE).header(ACCESS_MODE, "ELEVATED")
                        .header(IDEMPOTENCY, "scan-key").header(CORRELATION, "c-scan")
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", href))
                .andExpect(jsonPath("$.data.attachment.scanState").value("CLEAN"));

        assertThatThrownBy(() -> requireElevated("NORMAL"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_REQUIRED));
    }
}
