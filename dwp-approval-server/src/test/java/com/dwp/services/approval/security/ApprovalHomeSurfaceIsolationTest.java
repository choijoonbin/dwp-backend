package com.dwp.services.approval.security;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.domain.ApprovalCommandRepository;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalQueryRepository;
import com.dwp.services.approval.domain.ApprovalService;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalHomeSurfaceIsolationTest {

    private final ApprovalQueryRepository queries = mock(ApprovalQueryRepository.class);
    private final ApprovalService service = new ApprovalService(
            queries,
            mock(ApprovalCommandRepository.class),
            mock(AuditOutboxRecorder.class),
            mock(ApprovalIdentityDirectory.class));

    @AfterEach
    void clearContexts() {
        ApprovalPilotAuthorizationContext.clear();
        ApprovalRequestContext.clear();
    }

    @Test
    void governedWorkHomeNeverMixesAdministrativePulseOrInsight() {
        ApprovalRequestContext.set(
                17L,
                42L,
                null,
                Set.of("APP_CONFIG_ADMIN"),
                Set.of(
                        "APP.APPROVALS:VIEW",
                        "ACTION.APPROVAL_TASK:VIEW",
                        "ACTION.APPROVAL_REQUEST:VIEW",
                        "ADMIN.APPROVAL_DESIGN:VIEW"));
        ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(
                "route.approvals.work.home.page",
                "PAGE",
                "full-work",
                true,
                Set.of(),
                null,
                null,
                null,
                false,
                "route.approvals.work.home.page.full-work.projection.v1",
                "route.approvals.work.home.page.response.v1")));
        ApprovalRequestContext.Actor actor = ApprovalRequestContext.require();
        ApprovalDtos.ApprovalMetrics metrics = new ApprovalDtos.ApprovalMetrics(
                0, 0, 0, 0, 1, 4.0, 100.0);
        when(queries.metrics(actor)).thenReturn(metrics);
        when(queries.tasks(actor, "INBOX", 6)).thenReturn(List.of());
        when(queries.requests(actor, "SUBMITTED", 5)).thenReturn(List.of());
        when(queries.flow(actor)).thenReturn(List.of());

        ApprovalDtos.HomeResponse home = service.home();

        assertThat(home.administrator()).isFalse();
        assertThat(home.adminPulse()).isNull();
        assertThat(home.insights())
                .extracting(ApprovalDtos.DecisionInsight::key)
                .doesNotContain("draft-workflow");
        assertThat(home.insights())
                .extracting(ApprovalDtos.DecisionInsight::route)
                .noneMatch(route -> route.startsWith("/approvals/admin/"));
        verify(queries, never()).adminPulse(42L);
    }

    @Test
    void legacyFlagOffHomeRetainsTheExistingAdministrativePulse() {
        ApprovalRequestContext.set(
                17L,
                42L,
                null,
                Set.of("APP_CONFIG_ADMIN"),
                Set.of("APP.APPROVALS:VIEW", "ADMIN.APPROVAL_DESIGN:VIEW"));
        ApprovalDtos.AdminPulse pulse = new ApprovalDtos.AdminPulse(
                2, 1, 0, 0, 0, List.of());
        when(queries.adminPulse(42L)).thenReturn(pulse);

        ApprovalDtos.HomeResponse home = service.home();

        assertThat(home.administrator()).isTrue();
        assertThat(home.adminPulse()).isEqualTo(pulse);
        assertThat(home.insights())
                .extracting(ApprovalDtos.DecisionInsight::key)
                .contains("draft-workflow");
        verify(queries).adminPulse(42L);
    }
}
