package com.dwp.services.approval.api;

import com.dwp.services.approval.domain.ApprovalDraftMigrationDtos;
import com.dwp.services.approval.domain.ApprovalDraftMigrationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalDraftMigrationControllerTest {
    private final ApprovalDraftMigrationService migrations = mock(ApprovalDraftMigrationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ApprovalDraftMigrationController(migrations)).build();

    @Test
    void previewRequiresExplicitFormAndWorkflowTarget() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID formId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();

        mvc.perform(get("/v1/requests/{requestId}/draft/migration-preview", requestId)
                        .queryParam("targetFormId", formId.toString())
                        .queryParam("targetWorkflowId", workflowId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(migrations).preview(requestId, formId, workflowId);
        mvc.perform(get("/v1/requests/{requestId}/draft/migration-preview", requestId)
                        .queryParam("targetFormId", formId.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void migrateBindsImmutablePreviewEvidenceAndIdempotencyHeader() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID formId = UUID.randomUUID();
        UUID formVersionId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID workflowVersionId = UUID.randomUUID();
        String hash = "a".repeat(64);
        var request = new ApprovalDraftMigrationDtos.MigrateRequest(
                7L, formId, formVersionId, hash, workflowId, workflowVersionId, hash,
                "Move to the current governed form");

        mvc.perform(post("/v1/requests/{requestId}/draft/migrate", requestId)
                        .header("Idempotency-Key", "migrate-7")
                        .header("X-Correlation-ID", "corr-7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion":7,
                                  "targetFormId":"%s",
                                  "targetFormVersionId":"%s",
                                  "targetFormSchemaSha256":"%s",
                                  "targetWorkflowId":"%s",
                                  "targetWorkflowVersionId":"%s",
                                  "targetWorkflowDefinitionSha256":"%s",
                                  "reason":"Move to the current governed form"
                                }
                                """.formatted(formId, formVersionId, hash, workflowId,
                                workflowVersionId, hash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(migrations).migrate(requestId, request, "migrate-7", "corr-7");
    }

    @Test
    void migrationRejectsMissingKeyAndMalformedImmutableEvidence() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        mvc.perform(post("/v1/requests/{requestId}/draft/migrate", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/requests/{requestId}/draft/migrate", requestId)
                        .header("Idempotency-Key", "migrate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion":1,
                                  "targetFormId":"%s",
                                  "targetFormVersionId":"%s",
                                  "targetFormSchemaSha256":"not-a-hash",
                                  "targetWorkflowId":"%s",
                                  "targetWorkflowVersionId":"%s",
                                  "targetWorkflowDefinitionSha256":"not-a-hash",
                                  "reason":"migrate"
                                }
                                """.formatted(id, id, id, id)))
                .andExpect(status().isBadRequest());
    }
}
