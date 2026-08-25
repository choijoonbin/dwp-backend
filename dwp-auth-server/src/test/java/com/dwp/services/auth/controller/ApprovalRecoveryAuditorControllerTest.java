package com.dwp.services.auth.controller;

import com.dwp.services.auth.dto.ApprovalRecoveryAuditorDtos;
import com.dwp.services.auth.service.ApprovalRecoveryAuditorRequestParser;
import com.dwp.services.auth.service.ApprovalRecoveryAuditorService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalRecoveryAuditorControllerTest {

    private final ApprovalRecoveryAuditorService service =
            mock(ApprovalRecoveryAuditorService.class);
    private final ApprovalRecoveryAuditorRequestParser parser =
            mock(ApprovalRecoveryAuditorRequestParser.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ApprovalRecoveryAuditorController(service, parser)).build();

    @Test
    void exposesOnlyTheExactInternalPostContract() throws Exception {
        when(service.resolve(any())).thenReturn(
                new ApprovalRecoveryAuditorDtos.ResolveResponse(
                        43L, "RS_TEAM_A", "recovery-v2-opaque"));
        var parsed = new ApprovalRecoveryAuditorDtos.ResolveRequest(
                7L, "outbox-001", 41L, "RS_TEAM_A");
        String request = """
                {"tenantId":7,"outboxId":"outbox-001","originatorUserId":41,
                 "resourceSetKey":"RS_TEAM_A"}
                """;
        when(parser.parse(request)).thenReturn(parsed);

        mvc.perform(post("/internal/auth/v1/approval-recovery-auditor/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedUserId").value(43))
                .andExpect(jsonPath("$.resourceSetKey").value("RS_TEAM_A"))
                .andExpect(jsonPath("$.assignmentRevision").value("recovery-v2-opaque"));
        verify(service).resolve(parsed);

        mvc.perform(post("/api/auth/v1/approval-recovery-auditor/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isNotFound());
        mvc.perform(post("/auth/v1/approval-recovery-auditor/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isNotFound());
    }
}
