package com.dwp.services.platform.workplace.safetyoperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyAdminController.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyEmergencyContactDtos.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.CommandState;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyUserController.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SafetyEmergencyContactControllerTest {
    private final SafetyEmergencyContactService service = mock(SafetyEmergencyContactService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new SafetyEmergencyContactUserController(service),
            new SafetyEmergencyContactAdminController(service)).build();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-17T03:00:00Z");

    @Test
    void memberDirectoryIsScopedToTheActiveIncidentAndNeverCacheable() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        when(service.contactsForUser(42L, 19L, incidentId)).thenReturn(List.of(
                new EmergencyContactView(contactId, EmergencyContactKind.PUBLIC_EMERGENCY,
                        "공공 긴급 서비스", "Public emergency service",
                        EmergencyContactActionMode.TEL_URI, "tel:+82123456789", true,
                        EmergencyContactProviderState.READY, null, null,
                        true, 10, 1, now)));

        mvc.perform(get("/v1/workplace/safety/incidents/{id}/emergency-contacts", incidentId)
                        .header(TENANT, 42).header(USER, 19).header(PERMISSIONS, VIEW))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data[0].contactId").value(contactId.toString()))
                .andExpect(jsonPath("$.data[0].telUri").value("tel:+82123456789"))
                .andExpect(jsonPath("$.data[0].providerCode").doesNotExist());
    }

    @Test
    void elevatedExternalHandoffReturnsNoStoreReceiptLocation() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        UUID handoffId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        String statusHref = "/v1/admin/workplace/safety/incidents/" + incidentId
                + "/emergency-handoffs/" + commandId;
        EmergencyHandoffReceipt receipt = new EmergencyHandoffReceipt(
                handoffId, commandId, previewId, incidentId, contactId,
                CommandState.RESULT_UNKNOWN, "PROVIDER_RESULT_UNKNOWN", "provider-operation",
                null, 2, statusHref, "screen-20-correlation", now, null, now, false);
        when(service.execute(eq(42L), eq(7L), eq(incidentId), eq("handoff-key"), any(),
                eq("screen-20-correlation"))).thenReturn(receipt);

        mvc.perform(post("/v1/admin/workplace/safety/incidents/{id}/emergency-handoffs",
                        incidentId).contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 7)
                        .header(PERMISSIONS, ADMIN_MANAGE).header(ACCESS_MODE, "ELEVATED")
                        .header(IDEMPOTENCY, "handoff-key")
                        .header(CORRELATION, "screen-20-correlation")
                        .content(mapper.writeValueAsBytes(new ConfirmEmergencyHandoffRequest(
                                previewId, 4, 2, "Confirm one external handoff", true))))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", statusHref))
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.state").value("RESULT_UNKNOWN"))
                .andExpect(jsonPath("$.data.providerOperationReference")
                        .value("provider-operation"));
    }
}
