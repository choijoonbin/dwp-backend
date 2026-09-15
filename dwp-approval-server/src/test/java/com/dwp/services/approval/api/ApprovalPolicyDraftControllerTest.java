package com.dwp.services.approval.api;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.policy.ApprovalPolicyDraftDtos;
import com.dwp.services.approval.policy.ApprovalPolicyDraftService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalPolicyDraftControllerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ApprovalPolicyDraftService service = mock(ApprovalPolicyDraftService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new ApprovalPolicyDraftController(service))
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
            .build();

    @Test
    void createRequiresAValidExactOnceKeyAndValidDraft() throws Exception {
        mvc.perform(post("/v1/admin/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request())))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void createBindsThePostBodyAndCommandHeaders() throws Exception {
        ApprovalPolicyDraftDtos.Create request = request();
        mvc.perform(post("/v1/admin/policies")
                        .header("Idempotency-Key", "policy-create-2026-09")
                        .header("X-Correlation-ID", "correlation-15")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(service).create(request, "policy-create-2026-09", "correlation-15");
    }

    private ApprovalPolicyDraftDtos.Create request() {
        return new ApprovalPolicyDraftDtos.Create(
                "SOD.FINANCE.REQUESTER_MAKER",
                "재무 기안자-게시자 분리",
                "Finance requester and publisher separation",
                "SEGREGATION_OF_DUTIES", "BLOCK", "CRITICAL", "ACTIVE",
                Map.of("requesterCannotPublish", true),
                "재무 결재의 기안자와 게시자를 분리합니다");
    }
}
