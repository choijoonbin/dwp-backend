package com.dwp.services.approval.hrisbridge;

import static org.assertj.core.api.Assertions.assertThat;

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
}
