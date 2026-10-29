package com.dwp.services.messaging.retention;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagingRetentionPropertiesTest {

    @Test
    void enforcementFailsClosedUntilBothSafetyAuthoritiesAreReady() {
        var missingHoldAuthority = properties(true, false, true);
        var missingDownstreamLifecycle = properties(true, true, false);

        assertThat(missingHoldAuthority.runtimeState()).isEqualTo("BLOCKED");
        assertThat(missingHoldAuthority.blockedReason())
                .isEqualTo("LEGAL_HOLD_AUTHORITY_UNAVAILABLE");
        assertThat(missingDownstreamLifecycle.runtimeState()).isEqualTo("BLOCKED");
        assertThat(missingDownstreamLifecycle.blockedReason())
                .isEqualTo("DOWNSTREAM_LIFECYCLE_UNVERIFIED");
    }

    @Test
    void dryRunDoesNotRequireDestructiveExecutionAuthorities() {
        MessagingRetentionProperties properties = new MessagingRetentionProperties(
                true, MessagingRetentionProperties.ExecutionMode.DRY_RUN,
                false, false, false, 100, 10, 1_000, Duration.ofMinutes(15));

        assertThat(properties.runtimeState()).isEqualTo("DRY_RUN");
        assertThat(properties.blockedReason()).isNull();
    }

    @Test
    void rejectsUnsafeUnboundedExecutionLimits() {
        assertThatThrownBy(() -> new MessagingRetentionProperties(
                true, MessagingRetentionProperties.ExecutionMode.ENFORCING,
                false, true, true, 501, 10, 1_000, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessagingRetentionProperties(
                true, MessagingRetentionProperties.ExecutionMode.ENFORCING,
                false, true, true, 100, 10, 5_001, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MessagingRetentionProperties properties(
            boolean enabled, boolean holdAuthorityReady, boolean downstreamReady) {
        return new MessagingRetentionProperties(
                enabled, MessagingRetentionProperties.ExecutionMode.ENFORCING,
                false, holdAuthorityReady, downstreamReady,
                100, 10, 1_000, Duration.ofMinutes(15));
    }
}
