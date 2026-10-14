package com.dwp.services.people.hr.performance.compensationplanning;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovedCompensationSnapshotAdmissionControlTest {

    @Test
    void boundsConcurrencyPerTenantAndReleasesExactlyOnce() {
        var control = new ApprovedCompensationSnapshotAdmissionControl(
                Clock.fixed(Instant.parse("2027-01-02T00:00:00Z"), ZoneOffset.UTC),
                1,
                10);

        var first = control.admit(71L);
        var denied = control.admit(71L);
        var otherTenant = control.admit(72L);

        assertThat(first.allowed()).isTrue();
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reason()).isEqualTo("SVC_PEP_PER_001_CONCURRENCY_LIMITED");
        assertThat(denied.retryAfterSeconds()).isEqualTo(1);
        assertThat(otherTenant.allowed()).isTrue();

        first.lease().close();
        first.lease().close();
        var afterRelease = control.admit(71L);
        assertThat(afterRelease.allowed()).isTrue();

        afterRelease.lease().close();
        otherTenant.lease().close();
    }
}
