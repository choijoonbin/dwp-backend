package com.dwp.services.people.workforce;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkforceExportPolicyTest {

    @Test
    void rendersAnAccountableWatermarkAndNormalizesLimits() {
        WorkforceExportPolicy policy = new WorkforceExportPolicy(
                false, "WORKFORCE_MINIMUM",
                "tenant={{tenantId}}|user={{userId}}|recipient={{recipient}}|request={{requestId}}",
                500, 50, 9, "D-12,D-09,D-12");
        UUID requestId = UUID.randomUUID();

        assertThat(policy.artifactTtlHours()).isEqualTo(168);
        assertThat(policy.maximumAttempts()).isEqualTo(20);
        assertThat(policy.maximumManualRetries()).isEqualTo(3);
        assertThat(policy.blockers()).containsExactly("D-09", "D-12");
        assertThat(policy.watermark(7L, 41L, "case@example.com", requestId))
                .isEqualTo("tenant=7|user=41|recipient=case@example.com|request=" + requestId);
    }

    @Test
    void refusesToEnableExecutionWhileReleaseBlockersRemain() {
        assertThatThrownBy(() -> new WorkforceExportPolicy(
                true, "WORKFORCE_MINIMUM", "request={{requestId}}", 24, 5, 1, "D-12"))
                .isInstanceOf(IllegalStateException.class);
    }
}
