package com.dwp.services.platform.widgetregistry;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.core.exception.BaseException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WidgetRuntimeControlExpiryTest {
    private static final Instant NOW = Instant.parse("2026-09-15T04:00:00Z");

    @Test
    void mutationGuardUsesItsClockAndOnlyRepositoryQualifiedActiveControls() {
        WidgetRuntimeControlRepository controls = mock(WidgetRuntimeControlRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        OffsetDateTime expectedNow = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(controls.findActiveDisabled(expectedNow)).thenReturn(List.of());

        new WidgetRegistryMutationGuard(controls, clock)
                .requireAllowed(null, "core.work", UUID.randomUUID(), null);

        verify(controls).findActiveDisabled(expectedNow);
    }

    @Test
    void repositoryQualifiedActiveControlStillFailsClosed() {
        WidgetRuntimeControlRepository controls = mock(WidgetRuntimeControlRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        OffsetDateTime expectedNow = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(controls.findActiveDisabled(expectedNow)).thenReturn(List.of(
                WidgetRuntimeControl.builder()
                        .controlScope("CATALOG_MUTATIONS")
                        .targetType("GLOBAL")
                        .controlState("DISABLED")
                        .build()));

        assertThatThrownBy(() -> new WidgetRegistryMutationGuard(controls, clock)
                .requireAllowed(null, "core.work", UUID.randomUUID(), null))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void modeAndExactActionControlsApplyWithConsumedApproval() {
        WidgetRuntimeControlRepository controls = mock(WidgetRuntimeControlRepository.class);
        WidgetRuntimeEnableApprovalRepository approvals =
                mock(WidgetRuntimeEnableApprovalRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        OffsetDateTime expectedNow = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        String contractId = "core.workspace|core.workspace.daily-brief|1.0.0|"
                + "dismiss-recommendation|home.recommendation.dismiss";
        WidgetRuntimeControl mode = WidgetRuntimeControl.builder()
                .controlScope("RUNTIME_RENDER").targetType("MODE").targetId("FLOW_V1")
                .controlState("DISABLED").build();
        UUID actionControlId = UUID.randomUUID();
        WidgetRuntimeControl actionDeny = WidgetRuntimeControl.builder()
                .controlScope("RUNTIME_ACTION").targetType("ACTION")
                .targetId(contractId).providerProductKey("core.workspace")
                .controlState("DISABLED").build();
        WidgetRuntimeControl actionPermit = WidgetRuntimeControl.builder()
                .controlId(actionControlId).controlScope("RUNTIME_ACTION").targetType("ACTION")
                .targetId(contractId).providerProductKey("core.workspace")
                .controlState("ENABLED").expiresAt(expectedNow.plusMinutes(10)).build();
        when(controls.findActiveDisabled(expectedNow)).thenReturn(List.of(mode, actionDeny));
        when(controls.findEnabledActionApprovals(expectedNow)).thenReturn(List.of(actionPermit));
        when(approvals.existsByControlIdAndApprovalState(actionControlId, "CONSUMED"))
                .thenReturn(true);
        WidgetRegistryMutationGuard guard = new WidgetRegistryMutationGuard(
                controls, approvals, clock);

        assertThat(guard.runtimeDenied(
                "RUNTIME_RENDER", 71L, null, null, null, "FLOW_V1", null)).isTrue();
        assertThat(guard.runtimeDenied(
                "RUNTIME_RENDER", 71L, null, null, null, "CLASSIC", null)).isFalse();
        assertThat(guard.runtimeDenied(
                "RUNTIME_ACTION", 71L, "core.workspace", null, null,
                "CLASSIC", contractId)).isTrue();
        assertThat(guard.runtimeActionApproved(71L, "core.workspace", contractId)).isTrue();
        assertThat(guard.runtimeActionApproved(71L, "core.workspace", contractId + "-other"))
                .isFalse();
    }
}
