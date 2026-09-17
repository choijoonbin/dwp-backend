package com.dwp.services.platform.workplace.workplacevisits;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitAdminController.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitController.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitKioskController.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkplaceVisitControllerTest {
    private final WorkplaceVisitService visits = mock(WorkplaceVisitService.class);
    private final WorkplaceVisitManagementService management =
            mock(WorkplaceVisitManagementService.class);
    private final WorkplaceVisitKioskService kiosk = mock(WorkplaceVisitKioskService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new WorkplaceVisitController(visits),
            new WorkplaceVisitAdminController(visits),
            new WorkplaceVisitManagementController(management),
            new WorkplaceVisitKioskController(kiosk)).build();

    @Test
    void previewRequiresIdempotencyAndCorrelationHeaders() throws Exception {
        OffsetDateTime start = OffsetDateTime.parse("2026-09-17T01:00:00Z");
        VisitPreviewRequest request = new VisitPreviewRequest(
                new ReservationReference(ReservationAuthority.WORKPLACE, UUID.randomUUID(), 2),
                "BUSINESS", UUID.randomUUID(), start, start.plusHours(1),
                List.of(UUID.randomUUID()), List.of(new GuestRefInput("vault:guest-123",
                "K**", "Meeting", Map.of("name", start.plusDays(30)))),
                "Verify governed visit", true);

        mvc.perform(post("/v1/workplace/visits:preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 99).header(PERMISSIONS, UPDATE)
                        .header(IDEMPOTENCY, "preview-key").header(CORRELATION, "corr-preview")
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"));
        verify(visits).preview(42, 99, "preview-key", request, "corr-preview");

        mvc.perform(post("/v1/workplace/visits:preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 99).header(PERMISSIONS, UPDATE)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requesterRoutesReturnRecoverableNoStoreReceiptAndMaskedProjection() throws Exception {
        UUID visitId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-16T12:00:00Z");
        RequesterVisit visit = new RequesterVisit(visitId,
                new ReservationReference(ReservationAuthority.WORKPLACE, reservationId, 3),
                "BUSINESS", UUID.randomUUID(), now.plusDays(1), now.plusDays(1).plusHours(1),
                List.of(UUID.randomUUID()), List.of(new GuestRefView(null, "K**", "Meeting",
                Map.of("name", now.plusDays(30)))), VisitState.RESULT_UNKNOWN, 4, true,
                "/v1/workplace/visits/" + visitId, List.of(), now);
        CommandReceipt receipt = new CommandReceipt(UUID.randomUUID(), visitId,
                CommandState.RESULT_UNKNOWN, "/v1/workplace/visits/" + visitId,
                false, "corr-17", now);
        when(visits.requestAccess(eq(42L), eq(99L), eq(visitId), eq("access-key"),
                any(), eq("corr-17"))).thenReturn(new VisitCommandResult(visit, receipt));

        mvc.perform(post("/v1/workplace/visits/{visitId}/access-requests", visitId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 99).header(PERMISSIONS, UPDATE)
                        .header(IDEMPOTENCY, "access-key").header(CORRELATION, "corr-17")
                        .content(mapper.writeValueAsBytes(
                                new VersionCommand(3, "Request minimum access", true))))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(header().string("Location", "/v1/workplace/visits/" + visitId))
                .andExpect(jsonPath("$.data.visit.recoveryByGetOnly").value(true))
                .andExpect(jsonPath("$.data.visit.guests[0].opaqueRef").isEmpty())
                .andExpect(jsonPath("$.data.receipt.state").value("RESULT_UNKNOWN"));
    }

    @Test
    void adminMutationRequiresManageAndElevatedAccess() throws Exception {
        UUID visitId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-16T12:00:00Z");
        assertThatThrownBy(() -> authorizeMutation(ADMIN_VIEW, "ELEVATED"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> authorizeMutation(ADMIN_MANAGE, "NORMAL"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_REQUIRED));
        when(visits.approve(eq(42L), eq(7L), eq(visitId), eq("approve-key"),
                any(), isNull())).thenReturn(new AdminVisitCommandResult(null,
                new CommandReceipt(UUID.randomUUID(), visitId, CommandState.SUCCEEDED,
                        "/v1/workplace/visits/" + visitId, false, null, now)));

        mvc.perform(post("/v1/admin/workplace/visits/{visitId}:approve", visitId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 7)
                        .header(PERMISSIONS, ADMIN_MANAGE).header(ACCESS_MODE, "ELEVATED")
                        .header(IDEMPOTENCY, "approve-key")
                        .content(mapper.writeValueAsBytes(
                                new ApprovalCommand(2, true, "Approved", true))))
                .andExpect(status().isAccepted());
        verify(visits).approve(eq(42L), eq(7L), eq(visitId), eq("approve-key"),
                any(), isNull());
    }

    @Test
    void kioskRoutesUseDeviceIdentityAndNeverExposeOpaqueGuestReference() throws Exception {
        UUID visitId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-16T12:00:00Z");
        String identity = "a".repeat(64);
        when(kiosk.visit(42, identity, visitId)).thenReturn(new KioskVisit(
                visitId, "J**", "Vendor briefing", siteId, now, now.plusHours(1),
                VisitState.READY, 6));

        mvc.perform(get("/v1/workplace/kiosk/visits/{visitId}", visitId)
                        .header(TENANT, 42).header(DEVICE_IDENTITY, identity))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.maskedLabel").value("J**"))
                .andExpect(jsonPath("$.data.opaqueRef").doesNotExist())
                .andExpect(jsonPath("$.data.credential").doesNotExist());
    }

    @Test
    void managementWriteCarriesVersionedReceiptAndNeverAcceptsProviderSecrets() throws Exception {
        UUID bindingId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-16T12:00:00Z");
        ProviderBinding item = new ProviderBinding(bindingId, ProviderKind.ACCESS, "ACS",
                1, null, ProviderTruthState.CONFIGURED_UNVERIFIED, null, null, null, null,
                "Security operations", "Issue a manual badge", true, 1, now);
        ManagementReceipt receipt = new ManagementReceipt(UUID.randomUUID(), "PROVIDER",
                bindingId, 1, false, "corr-provider", now);
        when(management.createProvider(eq(42L), eq(7L), eq("provider-key"), any(),
                eq("corr-provider"))).thenReturn(new ManagementResult<>(item, receipt));
        ProviderBindingRequest request = new ProviderBindingRequest(ProviderKind.ACCESS,
                "ACS", 1, "Security operations", "Issue a manual badge", 0,
                true, "Connect access provider", true);

        mvc.perform(post("/v1/admin/workplace/provider-bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 7)
                        .header(PERMISSIONS, ADMIN_MANAGE).header(ACCESS_MODE, "ELEVATED")
                        .header(IDEMPOTENCY, "provider-key").header(CORRELATION, "corr-provider")
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.receipt.resourceVersion").value(1))
                .andExpect(jsonPath("$.data.item.secret").doesNotExist())
                .andExpect(jsonPath("$.data.item.credential").doesNotExist())
                .andExpect(jsonPath("$.data.item.configurationReference").doesNotExist());

                mvc.perform(post("/v1/admin/workplace/visits/{visitId}:export", UUID.randomUUID())
                        .header(TENANT, 42).header(USER, 7)
                        .header(PERMISSIONS, ADMIN_MANAGE).header(ACCESS_MODE, "ELEVATED"))
                .andExpect(status().isMethodNotAllowed());
    }
}
