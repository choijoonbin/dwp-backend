package com.dwp.services.approval.hrisbridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.services.approval.domain.ApprovalTerminalDecisionContext;
import com.dwp.services.approval.hrisbridge.separation.HrisSeparationProtocolException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HrisApprovalDecisionRelayTest {
    @Test
    void retriesTooEarlyButTreatsOrdinaryConflictAsPermanent() {
        assertThat(HrisApprovalDecisionRelay.retryableClientStatus(425)).isTrue();
        assertThat(HrisApprovalDecisionRelay.retryableClientStatus(408)).isTrue();
        assertThat(HrisApprovalDecisionRelay.retryableClientStatus(429)).isTrue();
        assertThat(HrisApprovalDecisionRelay.retryableClientStatus(409)).isFalse();
        assertThat(HrisApprovalDecisionRelay.retryableClientStatus(403)).isFalse();
    }

    @Test
    void soleSinkHonorsAllFeatureFlagCombinationsWithoutCrossActivation() {
        var legacy = ApprovalTerminalDecisionContext.standard();
        var separation = ApprovalTerminalDecisionContext.hrisReject(
                UUID.randomUUID(), UUID.randomUUID());

        assertThat(HrisApprovalDecisionOutbox.selectHandler(
                0, 0, false, false, "APPROVED", legacy))
                .isEqualTo(HrisApprovalDecisionOutbox.Handler.NONE);
        assertThatThrownBy(() -> HrisApprovalDecisionOutbox.selectHandler(
                0, 0, true, true, "REJECTED", separation))
                .isInstanceOf(HrisSeparationProtocolException.class);

        assertThat(HrisApprovalDecisionOutbox.selectHandler(
                1, 0, true, false, "APPROVED", legacy))
                .isEqualTo(HrisApprovalDecisionOutbox.Handler.LEGACY);
        assertThat(HrisApprovalDecisionOutbox.selectHandler(
                1, 0, true, true, "APPROVED", legacy))
                .isEqualTo(HrisApprovalDecisionOutbox.Handler.LEGACY);
        assertThatThrownBy(() -> HrisApprovalDecisionOutbox.selectHandler(
                1, 0, false, false, "APPROVED", legacy))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> HrisApprovalDecisionOutbox.selectHandler(
                1, 0, false, true, "APPROVED", legacy))
                .isInstanceOf(IllegalStateException.class);

        assertThat(HrisApprovalDecisionOutbox.selectHandler(
                0, 1, false, true, "REJECTED", separation))
                .isEqualTo(HrisApprovalDecisionOutbox.Handler.SEPARATION);
        assertThat(HrisApprovalDecisionOutbox.selectHandler(
                0, 1, true, true, "REJECTED", separation))
                .isEqualTo(HrisApprovalDecisionOutbox.Handler.SEPARATION);
        assertThatThrownBy(() -> HrisApprovalDecisionOutbox.selectHandler(
                0, 1, false, false, "REJECTED", separation))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> HrisApprovalDecisionOutbox.selectHandler(
                0, 1, true, false, "REJECTED", separation))
                .isInstanceOf(IllegalStateException.class);
    }
}
