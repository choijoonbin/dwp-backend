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
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ActivityControllerTest {
    private ActivityRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = mock(ActivityRepository.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ActivityService service = new ActivityService(repository, new ActivityCursor(mapper));
        var security = new PlatformSecurityFilter("trusted", "runtime", false, mapper,
                new PlatformCanaryPepRegistry(mapper), new PlatformApprovalsPepRegistry(mapper));
        mvc = standaloneSetup(new ActivityController(service))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .addFilters(security).build();
        when(repository.list(anyLong(), anyLong(), anySet(), anyBoolean(), any(), any(), anyBoolean()))
                .thenReturn(List.of());
        when(repository.executionCounts(anyLong(), anyLong(), anySet())).thenReturn(new long[7]);
    }

    @Test
    void legacyUrlUsesVerifiedPaginationWithoutCachingSensitiveMetadata() throws Exception {
        mvc.perform(request("")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.events").isEmpty())
                .andExpect(jsonPath("$.data.coverage.sourceScope").value("WORKSPACE"))
                .andExpect(jsonPath("$.data.coverage.supportedObjectTypes[0]").value("WORK_ITEM"))
                .andExpect(jsonPath("$.data.coverage.supportedObjectTypes.length()").value(1))
                .andExpect(jsonPath("$.data.startCursor").isString());
        verify(repository).list(eq(7L), eq(8L), anySet(), eq(false),
                argThat(q -> q.limit() == 50 && !q.includeUsage()), any(), eq(false));
    }

    @Test
    void invalidCursorOrBoundsNeverReachAQuery() throws Exception {
        mvc.perform(request("").param("limit", "101")).andExpect(status().isBadRequest());
        mvc.perform(request("").param("cursor", "broken")).andExpect(status().isBadRequest());
        mvc.perform(request("").param("from", "yesterday")).andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsUntrustedGatewayAndMissingActivityPermission() throws Exception {
        mvc.perform(request("").with(r -> {r.removeHeader("X-DWP-Service-Token"); return r;}))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"));
        mvc.perform(request("").with(r -> {r.removeHeader("X-DWP-Permissions");
            r.addHeader("X-DWP-Permissions", "APP.WORK:VIEW"); return r;})).andExpect(status().isForbidden());
        verifyNoInteractions(repository);
    }

    @Test
    void unavailableDeletedCrossTenantAndForbiddenDetailsHaveTheSameNotFoundShape() throws Exception {
        mvc.perform(request("/events/" + UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"));
    }

    @Test
    void activityOnlyPermissionNeverClaimsUnreadableSourceCoverage() throws Exception {
        MockHttpServletRequestBuilder list = request("").with(r -> {
            r.removeHeader("X-DWP-Permissions");
            r.addHeader("X-DWP-Permissions", "APP.ACTIVITY:VIEW");
            return r;
        });
        MockHttpServletRequestBuilder summary = request("/executions/summary").with(r -> {
            r.removeHeader("X-DWP-Permissions");
            r.addHeader("X-DWP-Permissions", "APP.ACTIVITY:VIEW");
            return r;
        });

        mvc.perform(list).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events").isEmpty())
                .andExpect(jsonPath("$.data.coverage.supportedObjectTypes").isEmpty());
        mvc.perform(summary).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.coverage.supportedObjectTypes").isEmpty());
    }

    private MockHttpServletRequestBuilder request(String suffix) {
        return get("/v1/workspace/activity" + suffix).header("X-DWP-Service-Token", "trusted")
                .header("X-DWP-Tenant-ID", "7").header("X-DWP-User-ID", "8")
                .header("X-DWP-Identity-Plane", "TENANT").header("X-DWP-Roles", "WORKSPACE_MEMBER")
                .header("X-DWP-Permissions", "APP.ACTIVITY:VIEW,APP.WORK:VIEW");
    }
}
