package com.dwp.services.approval.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.approval.domain.ApprovalFormUserCandidateService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApprovalFormUserCandidatesControllerTest {
    private final ApprovalFormUserCandidateService service = mock(ApprovalFormUserCandidateService.class);
    private final UUID form = UUID.randomUUID();
    private final UUID version = UUID.randomUUID();
    private MockMvc mvc;
    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ApprovalFormUserCandidatesController(service))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).build();
        when(service.search(eq(form), eq(version), eq("a".repeat(64)), any(), eq("reviewer"), eq("Kim"), anyInt(), anyBoolean(), any()))
                .thenReturn(new ApprovalFormUserCandidateService.Candidates(version, "a".repeat(64), "reviewer", "psr-" + "b".repeat(64),
                        OffsetDateTime.now().plusSeconds(30), List.of(new ApprovalFormUserCandidateService.Candidate(UUID.randomUUID(), "Kim")), false));
    }

    @Test void workAndAdminPathsDispatchTheirOwnModeAndExposeOnlyPublicProjection() throws Exception {
        for (String base : List.of("/v1/catalog/forms/", "/v1/admin/forms/")) {
            mvc.perform(get(base + form + "/versions/" + version + "/field-candidates")
                    .param("schemaSha256", "a".repeat(64)).param("fieldKey", "reviewer").param("query", "Kim"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.people[0].displayName").value("Kim"))
                    .andExpect(jsonPath("$.data.people[0].subjectId").doesNotExist());
        }
        verify(service).search(form, version, "a".repeat(64), null, "reviewer", "Kim", 10, false, null);
        verify(service).search(form, version, "a".repeat(64), null, "reviewer", "Kim", 10, true, null);
    }

    @Test void unknownAndDuplicateParametersNeverReachSourceService() throws Exception {
        String path = "/v1/catalog/forms/" + form + "/versions/" + version + "/field-candidates";
        mvc.perform(get(path).param("schemaSha256", "a".repeat(64)).param("fieldKey", "reviewer").param("query", "Kim").param("url", "https://invalid.test"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path).param("schemaSha256", "a".repeat(64)).param("fieldKey", "reviewer").param("query", "Kim", "Other"))
                .andExpect(status().isBadRequest());
        verify(service, never()).search(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), any());
    }

    @Test void abbreviatedUuidAliasCannotReachHandlerAuthority() throws Exception {
        mvc.perform(get("/v1/catalog/forms/1-1-1-1-1/versions/" + version + "/field-candidates")
                .param("schemaSha256", "a".repeat(64)).param("fieldKey", "reviewer").param("query", "Kim"))
                .andExpect(status().isBadRequest());
        verify(service, never()).search(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), any());
    }

    @Test void workPassesCanonicalRequestIdAndEchoesServerVersionZero() throws Exception {
        UUID request = UUID.randomUUID();
        when(service.search(form, version, "a".repeat(64), null, "reviewer", "Kim", 10, false, request))
                .thenReturn(new ApprovalFormUserCandidateService.Candidates(version, "a".repeat(64), "reviewer",
                        "psr-" + "b".repeat(64), OffsetDateTime.now().plusSeconds(30), List.of(), false, request, 0L));
        mvc.perform(get("/v1/catalog/forms/" + form + "/versions/" + version + "/field-candidates")
                .param("schemaSha256", "a".repeat(64)).param("fieldKey", "reviewer").param("query", "Kim")
                .param("requestId", request.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.requestId").value(request.toString()))
                .andExpect(jsonPath("$.data.requestVersion").value(0));
    }

    @Test void adminRequestIdAndWorkNonCanonicalRequestIdsNeverReachSource() throws Exception {
        for (String request : List.of("1-1-1-1-1", UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT))) {
            mvc.perform(get("/v1/catalog/forms/" + form + "/versions/" + version + "/field-candidates")
                    .param("schemaSha256", "a".repeat(64)).param("fieldKey", "reviewer").param("query", "Kim")
                    .param("requestId", request)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/v1/admin/forms/" + form + "/versions/" + version + "/field-candidates")
                .param("schemaSha256", "a".repeat(64)).param("fieldKey", "reviewer").param("query", "Kim")
                .param("requestId", UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        verify(service, never()).search(any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), any());
    }
}
