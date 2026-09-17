package com.dwp.services.approval.auditrecords;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.auditrecords.ApprovalAuditModels.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalAuditControllerTest {
    private final ApprovalAuditService service = mock(ApprovalAuditService.class);
    private final ApprovalAuditCommandFacade commands =
            mock(ApprovalAuditCommandFacade.class);
    private final ApprovalWorkAuthority workAuthority = mock(ApprovalWorkAuthority.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ApprovalAuditController(
                        service, commands,
                        new ApprovalAuditHttpAuthority(workAuthority)))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        when(workAuthority.requireCurrent(anyString())).thenReturn(actor(Set.of(
                ApprovalAuditHttpAuthority.VIEW_PERMISSION,
                ApprovalAuditHttpAuthority.EXECUTE_PERMISSION)));
    }

    @AfterEach
    void clear() {
        ReflectionTestUtils.invokeMethod(ApprovalManagementScopeContext.class, "clear");
    }

    @Test
    void searchUsesTheServerSelectedScopeAndReturns200() throws Exception {
        scope("RS_TEAM_A");
        when(service.search(any(), any(), any())).thenAnswer(invocation -> {
            Scope selected = invocation.getArgument(0);
            if (!"RS_TEAM_A".equals(selected.resourceSetKey())) {
                throw new BaseException(ErrorCode.FORBIDDEN);
            }
            return new SearchPage(
                    Instant.parse("2026-09-16T00:00:00Z"),
                    AccessLevel.METADATA, List.of(), null);
        });

        mvc.perform(get("/v1/admin/operations/audit-records/events")
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessLevel").value("METADATA"));
    }

    @Test
    void aDifferentSelectedScopeCannotFallBackToAnotherScopesRows() throws Exception {
        scope("RS_TEAM_B");
        when(service.event(any(), any(), any())).thenAnswer(invocation -> {
            Scope selected = invocation.getArgument(0);
            if (!"RS_TEAM_A".equals(selected.resourceSetKey())) {
                throw new BaseException(ErrorCode.FORBIDDEN);
            }
            return mock(EventProjection.class);
        });

        mvc.perform(get("/v1/admin/operations/audit-records/events/{eventId}",
                        UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    void staleAttestationVersionReturns409() throws Exception {
        scope("RS_TEAM_A");
        when(commands.linkExternalAttestation(any(), any(), anyLong(), any()))
                .thenThrow(new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT));

        mvc.perform(post("/v1/admin/operations/audit-records/exports/{exportId}/external-attestations",
                        UUID.randomUUID())
                        .header("X-DWP-Expected-Object-Version", 7)
                        .header("Idempotency-Key", "attestation-100")
                        .header("X-DWP-Expected-Decision-Revision", revision())
                        .header("X-DWP-Step-Up-Challenge", "signed.challenge.value")
                        .contentType("application/json")
                        .content("""
                                {
                                  "type":"WORM",
                                  "reference":"archive:case-100",
                                  "attestedAt":"2026-09-16T00:00:00Z",
                                  "evidencePayloadBase64Url":"payload-proof-100",
                                  "evidenceSignatureBase64Url":"%s"
                                }
                                """.formatted("a".repeat(86))))
                .andExpect(status().isConflict());
    }

    @Test
    void callerAssertedVerifiedStringIsRejectedBeforeTheCommand() throws Exception {
        scope("RS_TEAM_A");

        mvc.perform(post("/v1/admin/operations/audit-records/exports/{exportId}/external-attestations",
                        UUID.randomUUID())
                        .header("X-DWP-Expected-Object-Version", 7)
                        .header("Idempotency-Key", "attestation-100")
                        .header("X-DWP-Expected-Decision-Revision", revision())
                        .header("X-DWP-Step-Up-Challenge", "signed.challenge.value")
                        .contentType("application/json")
                        .content("""
                                {
                                  "type":"WORM",
                                  "reference":"archive:case-100",
                                  "attestedAt":"2026-09-16T00:00:00Z",
                                  "verificationReference":"verified:caller-asserted-proof"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(commands, never()).linkExternalAttestation(
                any(), any(), anyLong(), any());
    }

    @Test
    void savedViewCreateRequiresZeroCasAndIdempotencyAndReturns200() throws Exception {
        scope("RS_TEAM_A");
        UUID savedViewId = UUID.randomUUID();
        when(commands.createSavedView(any(), anyLong(), any()))
                .thenReturn(new SavedView(
                        savedViewId, "Denied this month", Visibility.PERSONAL,
                        new SearchFilter(
                                Instant.parse("2026-09-01T00:00:00Z"),
                                Instant.parse("2026-09-02T00:00:00Z"),
                                Set.of(), Set.of("DENIED"), null, null, 5_000, null),
                        17L, 0, Instant.parse("2026-09-16T00:00:00Z")));

        mvc.perform(post("/v1/admin/operations/audit-records/saved-views")
                        .header("X-DWP-Expected-Object-Version", 0)
                        .header("Idempotency-Key", "saved-view-100")
                        .header("X-DWP-Expected-Decision-Revision", revision())
                        .header("X-DWP-Step-Up-Challenge", "signed.challenge.value")
                        .contentType("application/json")
                        .content("""
                                {
                                  "savedViewId":"%s",
                                  "name":"Denied this month",
                                  "visibility":"PERSONAL",
                                  "filter":{
                                    "from":"2026-09-01T00:00:00Z",
                                    "to":"2026-09-02T00:00:00Z",
                                    "eventTypes":[],
                                    "outcomes":["DENIED"]
                                  }
                                }
                                """.formatted(savedViewId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.savedViewId").value(savedViewId.toString()));
    }

    @Test
    void nonZeroCreateVersionReturns409() throws Exception {
        scope("RS_TEAM_A");
        UUID exportId = UUID.randomUUID();
        when(commands.createExport(any(), anyLong(), any()))
                .thenThrow(new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT));
        mvc.perform(post("/v1/admin/operations/audit-records/exports")
                        .header("X-DWP-Expected-Object-Version", 1)
                        .header("Idempotency-Key", "export-100")
                        .header("X-DWP-Expected-Decision-Revision", revision())
                        .header("X-DWP-Step-Up-Challenge", "signed.challenge.value")
                        .contentType("application/json")
                        .content("""
                                {
                                  "exportId":"%s",
                                  "accessLevel":"METADATA",
                                  "filter":{
                                    "from":"2026-09-01T00:00:00Z",
                                    "to":"2026-09-02T00:00:00Z",
                                    "eventTypes":[],
                                    "outcomes":[]
                                  }
                                }
                                """.formatted(exportId)))
                .andExpect(status().isConflict());
    }

    @Test
    void unsupportedDeleteIsClosedByTheHttpMethodMatrix() throws Exception {
        scope("RS_TEAM_A");
        mvc.perform(delete("/v1/admin/operations/audit-records/saved-views"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void missingManagementScopeFailsClosedWith503() throws Exception {
        mvc.perform(get("/v1/admin/operations/audit-records/events")
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-02T00:00:00Z"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void revokedCurrentAuthorityReturns403BeforeTheServiceIsCalled() throws Exception {
        scope("RS_TEAM_A");
        when(workAuthority.requireCurrent(anyString()))
                .thenThrow(new BaseException(ErrorCode.FORBIDDEN));

        mvc.perform(get("/v1/admin/operations/audit-records/saved-views"))
                .andExpect(status().isForbidden());
        verify(workAuthority).requireCurrent(ApprovalAuditHttpAuthority.VIEW_PERMISSION);
        verifyNoInteractions(service, commands);
    }

    private ApprovalRequestContext.Actor actor(Set<String> permissions) {
        return new ApprovalRequestContext.Actor(
                17L, 42L, UUID.randomUUID(), "Auditor",
                Set.of("APPROVAL_AUDITOR"), permissions);
    }

    private void scope(String resourceSet) {
        ReflectionTestUtils.invokeMethod(
                ApprovalManagementScopeContext.class, "set",
                "opaque-" + resourceSet.toLowerCase(), resourceSet);
    }

    private String revision() {
        return "psr-" + "a".repeat(64);
    }
}
