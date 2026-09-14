package com.dwp.services.approval.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.domain.ApprovalDraftService;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApprovalWorkflowQuorumInformationControllerTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final ApprovalService service = mock(ApprovalService.class);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApprovalController(service, mock(ApprovalDraftService.class)))
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();
    final UUID target = UUID.randomUUID();
    final ApprovalDtos.WorkflowRuntimePins pins = new ApprovalDtos.WorkflowRuntimePins(UUID.randomUUID(), 1, "a".repeat(64), "b".repeat(64), 1, "c".repeat(64));

    ApprovalDtos.DecisionRequest request(Long version) {
        return new ApprovalDtos.DecisionRequest("REQUEST_INFO", "Please attach evidence", 0L,
                new ApprovalDtos.QuorumVotePrecondition(1, 1, pins, 1, "d".repeat(64), version));
    }
    @Test void typedInformationWithoutRequestVersionIs409BeforeServiceOrOwnerQueries() throws Exception {
        mvc.perform(post("/v1/tasks/{taskId}/decisions", target).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request(null)))).andExpect(status().isConflict());
        verifyNoInteractions(service);
    }
    @Test void validTypedPinsWithoutInstalledSignedAdmissionAre503BeforeServiceQueries() throws Exception {
        mvc.perform(post("/v1/tasks/{taskId}/decisions", target).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "original-info-1").content(mapper.writeValueAsString(request(0L))))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(service);
    }
    @Test void typedReplyWithoutInstalledSignedAdmissionIs503BeforeAnyServiceCall() throws Exception {
        mvc.perform(post("/v1/requests/{requestId}/information-response", target).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "original-reply-1")
                .content(mapper.writeValueAsString(new ApprovalDtos.InformationResponseRequest("Evidence", Map.of(), 1L, 1L))))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(service);
    }
    @Test void negativeRequestVersionAndZeroSourceGenerationAreRejectedByActualBeanValidation() throws Exception {
        mvc.perform(post("/v1/tasks/{taskId}/decisions", target).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request(-1L)))).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/requests/{requestId}/information-response", target).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new ApprovalDtos.InformationResponseRequest("Evidence", Map.of(), 0L, 0L))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void oldConstructorsAndLegacyInformationHttpContractRemainValid() throws Exception {
        var legacy = new ApprovalDtos.DecisionRequest("REQUEST_INFO", "Evidence", 0L);
        mvc.perform(post("/v1/tasks/{taskId}/decisions", target).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(legacy))).andExpect(status().isOk());
        verify(service).decide(eq(target), eq(legacy), isNull(), isNull());
        var reply = new ApprovalDtos.InformationResponseRequest("Evidence", Map.of(), 0L);
        mvc.perform(post("/v1/requests/{requestId}/information-response", target).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(reply))).andExpect(status().isOk());
        verify(service).respondToInformationRequest(eq(target), eq(reply), isNull());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(legacy).isEmpty()); assertTrue(factory.getValidator().validate(reply).isEmpty());
        }
    }
    @Test void publicQuorumSnapshotIncludesRequiredZeroBasedRequestVersionAndOmitsTenantIds() throws Exception {
        var value = mapper.readTree(mapper.writeValueAsString(new ApprovalDtos.QuorumTaskSnapshot(1, 1, pins, 1, "d".repeat(64), UUID.randomUUID(), 0)));
        assertTrue(value.get("requestVersion").isIntegralNumber()); assertEquals(0, value.get("requestVersion").longValue());
        assertFalse(value.get("pins").has("tenantId"));
        var round = new ApprovalDtos.QuorumInformationSnapshot(UUID.randomUUID(), 1, 2, pins, 1, "d".repeat(64));
        assertEquals(2, mapper.readTree(mapper.writeValueAsString(round)).get("targetGeneration").longValue());
    }
}
