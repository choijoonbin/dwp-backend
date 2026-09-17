package com.dwp.services.approval.analytics;

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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalAnalyticsControllerTest {
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    private final ApprovalAnalyticsRepository repository = mock(ApprovalAnalyticsRepository.class);
    private final ApprovalWorkAuthority workAuthority = mock(ApprovalWorkAuthority.class);
    private final ApprovalAnalyticsService service = new ApprovalAnalyticsService(
            repository, Clock.fixed(NOW, ZoneOffset.UTC));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ApprovalAnalyticsController(
                        service, new ApprovalAnalyticsHttpAuthority(workAuthority)))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        when(workAuthority.requireCurrent("ADMIN.APPROVAL_OPERATIONS:VIEW"))
                .thenReturn(actor(Set.of("ADMIN.APPROVAL_OPERATIONS:VIEW"), Set.of()));
    }

    @AfterEach
    void clear() {
        ReflectionTestUtils.invokeMethod(ApprovalManagementScopeContext.class, "clear");
    }

    @Test
    void dashboardReturns200WithCoverageAndDefinitions() throws Exception {
        scope("RS_TEAM_A");
        when(repository.coverage(any(), any())).thenReturn(
                new ApprovalAnalyticsRepository.CoverageRow(5, 5, 0, 0, 0, NOW, NOW));
        when(repository.overall(any(), any(), any())).thenReturn(
                new ApprovalAnalyticsRepository.MetricRow(5, 60L, 120L, 5, 1, 1, 1, 0, 5));
        when(repository.cohorts(any(), any(), any())).thenReturn(List.of());
        when(repository.stageWaits(any(), any())).thenReturn(List.of());

        mvc.perform(get("/v1/admin/operations/analytics/dashboard")
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage.candidateRequests").value(5))
                .andExpect(jsonPath("$.data.definitions[0].key").value("cycle.p50"));
    }

    @Test
    void representativeDrilldownWithoutElevatedOrAuditorAuthorityReturns403() throws Exception {
        scope("RS_TEAM_A");

        mvc.perform(get("/v1/admin/operations/analytics/cohorts/{cohortKey}/representatives",
                        UUID.randomUUID())
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-02T00:00:00Z"))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingManagementScopeReturns503() throws Exception {
        mvc.perform(get("/v1/admin/operations/analytics/metric-definitions"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void repositoryNeverFallsBackAcrossSelectedScopes() throws Exception {
        scope("RS_TEAM_B");
        when(repository.coverage(any(), any())).thenAnswer(invocation -> {
            ApprovalAnalyticsModels.Scope selected = invocation.getArgument(0);
            if (!"RS_TEAM_A".equals(selected.resourceSetKey())) {
                throw new BaseException(ErrorCode.FORBIDDEN);
            }
            return new ApprovalAnalyticsRepository.CoverageRow(0, 0, 0, 0, 0, NOW, NOW);
        });

        mvc.perform(get("/v1/admin/operations/analytics/dashboard")
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-02T00:00:00Z"))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokedCurrentAuthorityStopsBeforeAnyAnalyticsRepositoryCall() throws Exception {
        scope("RS_TEAM_A");
        when(workAuthority.requireCurrent("ADMIN.APPROVAL_OPERATIONS:VIEW"))
                .thenThrow(new BaseException(ErrorCode.FORBIDDEN));

        mvc.perform(get("/v1/admin/operations/analytics/dashboard")
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-02T00:00:00Z"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(repository);
    }

    private ApprovalRequestContext.Actor actor(Set<String> permissions, Set<String> roles) {
        return new ApprovalRequestContext.Actor(
                17L, 42L, UUID.randomUUID(), "Analytics operator",
                roles, permissions);
    }

    private void scope(String resourceSet) {
        ReflectionTestUtils.invokeMethod(
                ApprovalManagementScopeContext.class, "set",
                "opaque-" + resourceSet.toLowerCase(), resourceSet);
    }
}
