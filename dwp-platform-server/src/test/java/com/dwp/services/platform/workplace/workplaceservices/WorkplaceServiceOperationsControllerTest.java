package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceFulfillmentController.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesController.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkplaceServiceOperationsControllerTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-17T00:00:00Z");

    private final WorkplaceServiceOperationsService service =
            mock(WorkplaceServiceOperationsService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new WorkplaceServiceOperationsController(service)).build();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void providerReadsAreNoStoreAndElevatedWritesReturnRecoverableReceipt() throws Exception {
        UUID providerId = UUID.randomUUID();
        ProviderProfile provider = provider(providerId);
        when(service.providers(42)).thenReturn(new ProviderProfiles(List.of(provider), NOW));
        String href = "/v1/admin/workplace/service-providers/" + providerId;
        when(service.createProvider(eq(42L), eq(99L), eq("provider-key"), any(), eq("corr-18")))
                .thenReturn(new ProviderCommandResult(provider,
                        receipt(CommandState.SUCCEEDED, href)));

        mvc.perform(get("/v1/admin/workplace/service-providers")
                        .header(TENANT, 42).header(PERMISSIONS, ADMIN_VIEW))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.items[0].providerProfileId")
                        .value(providerId.toString()))
                .andExpect(jsonPath("$.data.items[0].readiness").value("READY"));

        ProviderCreateRequest request = new ProviderCreateRequest("external.av",
                "외부 AV", "External AV", "HTTP_JSON", List.of(),
                List.of("WORKPLACE_SERVICE_FULFILLMENT"), mapper.createObjectNode(),
                "vault://provider/external-av", true, "Configure provider");
        mvc.perform(post("/v1/admin/workplace/service-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 99)
                        .header(PERMISSIONS, ADMIN_MANAGE).header(ACCESS_MODE, "ELEVATED")
                        .header(IDEMPOTENCY, "provider-key").header(CORRELATION, "corr-18")
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", href))
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.receipt.state").value("SUCCEEDED"));
    }

    @Test
    void capacityAndAssigneeContractsUseFrozenQueryAndResponseShapes() throws Exception {
        UUID itemId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        OffsetDateTime from = NOW.plusHours(1);
        OffsetDateTime to = from.plusHours(1);
        CapacityBucket bucket = new CapacityBucket(UUID.randomUUID(), itemId, "site-18",
                from, to, 10, 3, 2, 5, "capacity-v1", NOW, NOW,
                NOW.plusMinutes(5), true, 1);
        when(service.capacity(42, itemId, "site-18", from, to)).thenReturn(new CapacityRange(
                itemId, "site-18", from, to, CapacityMode.BUCKETED, List.of(bucket),
                true, List.of(), NOW));
        AssigneeProjection assignee = new AssigneeProjection("subject-18", "Alex Kim", true,
                List.of("WORKPLACE_SERVICE_FULFILLMENT"), "directory-v1", NOW,
                NOW.plusMinutes(30));
        when(service.searchAssignees(42, "FULFILLMENT", providerId,
                "site-18", "Alex", 20))
                .thenReturn(new AssigneeSearchResult(List.of(assignee), NOW));

        mvc.perform(get("/v1/workplace/service-catalog/{itemId}/capacity", itemId)
                        .param("siteReference", "site-18")
                        .param("from", from.toString()).param("to", to.toString())
                        .header(TENANT, 42).header(PERMISSIONS, VIEW))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.buckets[0].availableQuantity").value(5));

        mvc.perform(get("/v1/admin/workplace/service-assignees")
                        .param("purpose", "FULFILLMENT")
                        .param("providerId", providerId.toString())
                        .param("siteReference", "site-18").param("q", "Alex")
                        .param("limit", "20")
                        .header(TENANT, 42).header(PERMISSIONS, ADMIN_VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].directorySubjectId").value("subject-18"))
                .andExpect(jsonPath("$.data.items[0].contactAvailable").value(true));
    }

    @Test
    void requesterInspectionReturnsLatestDecisionAndAcceptedReceipt() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        InspectionAttempt attempt = new InspectionAttempt(UUID.randomUUID(), orderId, lineId,
                taskId, InspectionMode.REQUESTER, InspectionActorRole.REQUESTER,
                InspectionDecision.PASSED, mapper.createArrayNode(), mapper.createObjectNode(),
                List.of(), "Requester accepted", false, NOW);
        InspectionStatus status = new InspectionStatus(orderId, lineId,
                InspectionMode.REQUESTER, true, true, true, false, attempt, NOW);
        String href = "/v1/workplace/service-orders/" + orderId + "/lines/" + lineId
                + "/inspection";
        when(service.inspect(eq(42L), eq(99L), eq(orderId), eq(lineId), eq(false),
                eq("inspection-key"), any(), eq("corr-inspection")))
                .thenReturn(new InspectionCommandResult(status,
                        receipt(CommandState.SUCCEEDED, href)));
        InspectionAttemptRequest request = new InspectionAttemptRequest(
                InspectionDecision.PASSED, mapper.createObjectNode(), List.of(),
                5, 3, true, "Requester accepted");

        mvc.perform(post("/v1/workplace/service-orders/{orderId}/lines/{lineId}/inspection-attempts",
                        orderId, lineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 99).header(PERMISSIONS, UPDATE)
                        .header(IDEMPOTENCY, "inspection-key")
                        .header(CORRELATION, "corr-inspection")
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", href))
                .andExpect(jsonPath("$.data.inspection.accepted").value(true))
                .andExpect(jsonPath("$.data.inspection.latestAttempt.decision")
                        .value("PASSED"));
    }

    private static ProviderProfile provider(UUID providerId) {
        return new ProviderProfile(providerId, "external.av", "외부 AV", "External AV",
                "HTTP_JSON", ProviderLifecycleState.ACTIVE, List.of(),
                List.of("WORKPLACE_SERVICE_FULFILLMENT"),
                new ObjectMapper().createObjectNode(), true, 1, ProviderState.READY,
                1L, "evidence-18", NOW.minusMinutes(1), NOW.minusMinutes(1),
                null, 1, NOW);
    }

    private static OperationsCommandReceipt receipt(CommandState state, String href) {
        return new OperationsCommandReceipt(UUID.randomUUID(), state, href, false,
                "corr-18", NOW);
    }
}
