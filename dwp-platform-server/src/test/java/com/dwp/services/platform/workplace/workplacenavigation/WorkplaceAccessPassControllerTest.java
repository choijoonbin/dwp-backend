package com.dwp.services.platform.workplace.workplacenavigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceAccessPassController.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceAccessPassDtos.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationController.*;
import static com.dwp.services.platform.security.PlatformDeviceIdentity.CREDENTIAL_HEADER;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkplaceAccessPassControllerTest {
    private final WorkplaceAccessPassService service = mock(WorkplaceAccessPassService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new WorkplaceAccessPassController(service)).build();

    @Test
    void firstIssueResponseIsNoStoreAndStatusRecoveryNeverReturnsSecrets() throws Exception {
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID poiId = UUID.randomUUID();
        UUID passId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T02:00:00Z");
        AccessPassView pass = new AccessPassView(passId, UUID.randomUUID(), siteId, floorId,
                resourceId, poiId, AccessPassState.ACTIVE, "7C2A", true, true, true,
                "VERIFIED_NFC", 8L, "evidence:nfc-v8",
                "VERIFIED_GATE", 9L, "evidence:gate-v9", 1,
                now, now.plusMinutes(20), null, now);
        AccessPassCommandReceipt receipt = new AccessPassCommandReceipt(
                commandId, passId, AccessPassCommandType.ISSUE,
                AccessPassCommandState.SUCCEEDED, "Enter reserved room", null, 1,
                false, "/v1/workplace/navigation/access-pass/commands/" + commandId,
                "corr-pass", now, now, now);
        AccessPassCommandResult first = new AccessPassCommandResult(
                receipt, pass, "DWP1.one-time-secret", "8N5KQ7RM2W4C", false);
        AccessPassCommandResult recovered = new AccessPassCommandResult(
                receipt, pass, null, null, true);
        when(service.execute(eq(42L), eq(7L), eq("issue-key"), any(), eq("corr-pass")))
                .thenReturn(first);
        when(service.command(42L, 7L, commandId)).thenReturn(recovered);

        mvc.perform(post("/v1/workplace/navigation/access-pass:execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 7)
                        .header(PERMISSIONS, UPDATE).header(ACCESS_MODE, "ELEVATED")
                        .header(IDEMPOTENCY, "issue-key").header(CORRELATION, "corr-pass")
                        .content(mapper.writeValueAsBytes(new ConfirmAccessPassCommandRequest(
                                previewId, 0, "Enter reserved room", true))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(header().string("Location", receipt.statusHref()))
                .andExpect(jsonPath("$.data.oneTimeCredential").value("DWP1.one-time-secret"))
                .andExpect(jsonPath("$.data.pairingCode").value("8N5KQ7RM2W4C"));

        mvc.perform(get("/v1/workplace/navigation/access-pass/commands/{commandId}", commandId)
                        .header(TENANT, 42).header(USER, 7).header(PERMISSIONS, VIEW))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.oneTimeCredential").isEmpty())
                .andExpect(jsonPath("$.data.pairingCode").isEmpty());
    }

    @Test
    void contextAndAuditAreOwnerScopedAndNoStore() throws Exception {
        UUID siteId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID passId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T02:00:00Z");
        when(service.context(42L, 7L, siteId, resourceId))
                .thenReturn(new AccessPassContext(null, List.of(), false, null, now));
        when(service.auditEvents(42L, 7L, passId, 20)).thenReturn(List.of(
                new AccessPassAuditEvent(UUID.randomUUID(), "navigation.access-pass.issue",
                        passId, "corr", now)));

        mvc.perform(get("/v1/workplace/navigation/access-pass")
                        .header(TENANT, 42).header(USER, 7).header(PERMISSIONS, VIEW)
                        .param("siteId", siteId.toString())
                        .param("resourceId", resourceId.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.bookingEligible").value(false));
        mvc.perform(get("/v1/workplace/navigation/access-pass/audit-events")
                        .header(TENANT, 42).header(USER, 7).header(PERMISSIONS, VIEW)
                        .param("passId", passId.toString()).param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].passId").value(passId.toString()));
    }

    @Test
    void boundDevicePairingIsNoStoreAndNeverEchoesThePairingCode() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID passId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-17T02:00:00Z");
        when(service.pair(eq(42L), eq(deviceId), eq("device-credential-material-123456789"),
                eq("pair-key"), eq("corr-pair"), any())).thenReturn(new AccessPassPairingReceipt(
                receiptId, passId, deviceId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 2, "corr-pair", now.plusMinutes(20), now, false));
        MockMvc deviceMvc = MockMvcBuilders.standaloneSetup(
                new WorkplaceAccessPassDeviceController(service)).build();

        deviceMvc.perform(post(
                        "/v1/device/workplace/devices/{deviceId}/access-pass:pair", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42)
                        .header(CREDENTIAL_HEADER, "device-credential-material-123456789")
                        .header(IDEMPOTENCY, "pair-key")
                        .header(CORRELATION, "corr-pair")
                        .content("""
                                {"passId":"%s","pairingCode":"8N5KQ7RM2W4C"}
                                """.formatted(passId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.pairingReceiptId").value(receiptId.toString()))
                .andExpect(jsonPath("$.data.correlationId").value("corr-pair"))
                .andExpect(jsonPath("$.data.replayed").value(false))
                .andExpect(jsonPath("$.data.pairingCode").doesNotExist());
    }
}
