package com.dwp.services.platform.activity;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.platform.security.PlatformApprovalsPepRegistry;
import com.dwp.services.platform.security.PlatformCanaryPepRegistry;
import com.dwp.services.platform.security.PlatformSecurityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ActivityEvidenceControllerTest {
    private final ActivityEvidenceRepository repository = mock(ActivityEvidenceRepository.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var security = new PlatformSecurityFilter("trusted", "runtime", false, mapper,
                new PlatformCanaryPepRegistry(mapper), new PlatformApprovalsPepRegistry(mapper));
        mvc = standaloneSetup(new ActivityEvidenceController(new ActivityEvidenceService(repository)))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource())).addFilters(security).build();
        when(repository.sources(anyLong(), anyLong(), anySet(), any(), anyBoolean()))
                .thenReturn(List.of());
        when(repository.evidence(anyLong(), anyLong(), anySet(), any(), anyBoolean()))
                .thenReturn(Optional.empty());
        when(repository.agentEvidence(anyLong(), anyLong(), any())).thenReturn(Optional.empty());
    }

    @Test
    void sourcesArePrivateAndNeverCacheable() throws Exception {
        mvc.perform(request("/sources/status")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.sources").isEmpty());
    }

    @Test
    void allAdditivePathsRequireTrustedGatewayAndActivityView() throws Exception {
        for (String suffix : List.of("/sources/status", "/events/" + UUID.randomUUID() + "/evidence",
                "/audit/evidence/" + UUID.randomUUID())) {
            mvc.perform(request(suffix).with(r -> { r.removeHeader("X-DWP-Service-Token"); return r; }))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"));
            mvc.perform(request(suffix).with(r -> { r.removeHeader("X-DWP-Permissions");
                r.addHeader("X-DWP-Permissions", "APP.WORK:VIEW"); return r; })).andExpect(status().isForbidden());
        }
        verifyNoInteractions(repository);
    }

    @Test
    void agentAuditRequiresAskAndUnavailableEvidenceDoesNotLeakExistence() throws Exception {
        mvc.perform(request("/audit/evidence/" + UUID.randomUUID())).andExpect(status().isForbidden());
        mvc.perform(request("/audit/evidence/" + UUID.randomUUID()).with(r -> {
            r.removeHeader("X-DWP-Permissions");
            r.addHeader("X-DWP-Permissions", "APP.ACTIVITY:VIEW,APP.ASK:VIEW"); return r;
        })).andExpect(status().isNotFound());
        mvc.perform(request("/events/" + UUID.randomUUID() + "/evidence")).andExpect(status().isNotFound());
    }

    @Test
    void providerAndInvalidTenantCannotReadPersonalEvidence() throws Exception {
        mvc.perform(request("/sources/status").with(r -> { r.removeHeader("X-DWP-Identity-Plane");
            r.addHeader("X-DWP-Identity-Plane", "PROVIDER"); return r; })).andExpect(status().isForbidden());
        mvc.perform(request("/sources/status").with(r -> { r.removeHeader("X-DWP-Tenant-ID");
            r.addHeader("X-DWP-Tenant-ID", "0"); return r; })).andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }

    private MockHttpServletRequestBuilder request(String suffix) {
        return get("/v1/workspace/activity" + suffix).header("X-DWP-Service-Token", "trusted")
                .header("X-DWP-Tenant-ID", "7").header("X-DWP-User-ID", "8")
                .header("X-DWP-Identity-Plane", "TENANT").header("X-DWP-Roles", "WORKSPACE_MEMBER")
                .header("X-DWP-Permissions", "APP.ACTIVITY:VIEW,APP.WORK:VIEW");
    }
}
