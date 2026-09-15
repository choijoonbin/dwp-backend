package com.dwp.services.approval.api;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.domain.ApprovalDelegationUpdateRequest;
import com.dwp.services.approval.domain.ApprovalDraftService;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalDelegationControllerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ApprovalService service = mock(ApprovalService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new ApprovalController(service, mock(ApprovalDraftService.class)))
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
            .build();

    @Test
    void updateRequiresIdempotencyHeaderAndNonNegativeVersion() throws Exception {
        UUID delegationId = UUID.randomUUID();
        ApprovalDelegationUpdateRequest valid = request(4L);

        mvc.perform(put("/v1/delegations/{delegationId}", delegationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(valid)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/v1/delegations/{delegationId}", delegationId)
                        .header("Idempotency-Key", "delegation-update-negative")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request(-1L))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void updateBindsExactPutPathBodyVersionAndIdempotencyKey() throws Exception {
        UUID delegationId = UUID.randomUUID();
        ApprovalDelegationUpdateRequest request = request(4L);
        String idempotencyKey = "delegation-update-4";
        org.mockito.Mockito.when(service.updateDelegation(
                        delegationId, request, idempotencyKey, null))
                .thenReturn(List.of());

        mvc.perform(put("/v1/delegations/{delegationId}", delegationId)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(service).updateDelegation(
                eq(delegationId), eq(request), eq(idempotencyKey), isNull());
    }

    private ApprovalDelegationUpdateRequest request(long expectedVersion) {
        Instant startsAt = Instant.now().plus(Duration.ofHours(1));
        return new ApprovalDelegationUpdateRequest(
                23L, "ALL", null, startsAt, startsAt.plus(Duration.ofDays(30)),
                "Cover approvals during the finance close", expectedVersion);
    }
}
