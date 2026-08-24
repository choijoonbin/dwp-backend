package com.dwp.services.approval.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApprovalManagementScopeCompatibilityReadinessTest {

    private final ApplicationArguments arguments = mock(ApplicationArguments.class);

    @Test
    void localDefaultOffIsANoOp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        readiness(jdbc, "local", false, false, false).run(arguments);

        verifyNoInteractions(jdbc);
    }

    @Test
    void productionActivationRequiresGovernedAuthorizationAndClusterFence() {
        JdbcTemplate jdbc = compatibleFence(0, false);

        assertThatThrownBy(() -> readiness(
                jdbc, "production", false, true, true).run(arguments))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> readiness(
                jdbc, "production", true, true, false).run(arguments))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> readiness(
                jdbc, "production", true, true, true).run(arguments))
                .doesNotThrowAnyException();
    }

    @Test
    void activatedFenceRejectsRootOnlyStartupEvenWhenScopedRowsAreGone() {
        JdbcTemplate jdbc = compatibleFence(0, true);

        assertThatThrownBy(() -> readiness(
                jdbc, "production", true, false, true).run(arguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rollback is unsafe");
    }

    @Test
    void nonRootRowsRejectRootOnlyStartupWithoutDependingOnMarker() {
        JdbcTemplate jdbc = compatibleFence(1, false);

        assertThatThrownBy(() -> readiness(
                jdbc, "production", true, false, true).run(arguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rollback is unsafe");
    }

    @Test
    void capabilityMismatchAlwaysFailsClosedWhenReadinessIsActive() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class)))
                .thenReturn(0);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn("approval-management-scope-v0");
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Boolean.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> readiness(
                jdbc, "production", true, false, true).run(arguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible");
    }

    private JdbcTemplate compatibleFence(int nonRootObjects, boolean activated) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class)))
                .thenReturn(nonRootObjects);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn(ApprovalManagementScopeCompatibilityReadiness.CAPABILITY);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Boolean.class)))
                .thenReturn(activated);
        return jdbc;
    }

    private ApprovalManagementScopeCompatibilityReadiness readiness(
            JdbcTemplate jdbc,
            String environment,
            boolean productAuthorizationV2Enabled,
            boolean nonRootWritesEnabled,
            boolean clusterFenceConfirmed) {
        return new ApprovalManagementScopeCompatibilityReadiness(
                jdbc,
                environment,
                productAuthorizationV2Enabled,
                nonRootWritesEnabled,
                clusterFenceConfirmed);
    }
}
