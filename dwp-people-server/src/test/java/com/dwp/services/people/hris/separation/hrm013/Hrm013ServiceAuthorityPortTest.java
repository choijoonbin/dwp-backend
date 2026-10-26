package com.dwp.services.people.hris.separation.hrm013;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm013ServiceAuthorityPortTest {
    private static final Hrm013ServiceAuthorityPort.Query QUERY =
            new Hrm013ServiceAuthorityPort.Query(
                    91L, "dwp-time-server", "SVC-PEP-HRM-008",
                    "HRIS_SEPARATION_TIME_TASK_RECEIPT", 7L, 11L);

    @Test
    void oneExactCurrentAdmitIsRequired() {
        Hrm013ServiceAuthorityPort port = query -> Hrm013ServiceAuthorityPort.Resolution.admit(
                new Hrm013ServiceAuthorityPort.CurrentGrant(
                        query.tenantId(), query.callerWorkloadId(), query.bindingId(),
                        query.purpose(), query.signedTenantGrantRevision(),
                        query.signedAuthorizationRevision()));

        assertThat(port.requireCurrentAdmit(QUERY).authorizationRevision()).isEqualTo(11L);
    }

    @Test
    void everyNonAdmitAndRevisionMismatchFailsClosed() {
        for (Hrm013ServiceAuthorityPort.Status status
                : Hrm013ServiceAuthorityPort.Status.values()) {
            if (status == Hrm013ServiceAuthorityPort.Status.ADMIT) continue;
            Hrm013ServiceAuthorityPort denied = ignored ->
                    Hrm013ServiceAuthorityPort.Resolution.deny(status);
            assertThatThrownBy(() -> denied.requireCurrentAdmit(QUERY))
                    .isInstanceOf(Hrm013ServiceAuthorityPort.AuthorityRejectedException.class);
        }

        Hrm013ServiceAuthorityPort stale = query -> Hrm013ServiceAuthorityPort.Resolution.admit(
                new Hrm013ServiceAuthorityPort.CurrentGrant(
                        query.tenantId(), query.callerWorkloadId(), query.bindingId(),
                        query.purpose(), query.signedTenantGrantRevision() + 1,
                        query.signedAuthorizationRevision()));
        assertThatThrownBy(() -> stale.requireCurrentAdmit(QUERY))
                .isInstanceOf(Hrm013ServiceAuthorityPort.AuthorityRejectedException.class)
                .extracting(value -> ((Hrm013ServiceAuthorityPort.AuthorityRejectedException)
                        value).status())
                .isEqualTo(Hrm013ServiceAuthorityPort.Status.REVISION_MISMATCH);
    }

    @Test
    void providerFailureHasNoConfigurationOrCacheFallback() {
        Hrm013ServiceAuthorityPort unavailable = ignored -> {
            throw new IllegalStateException("control plane unavailable");
        };

        assertThatThrownBy(() -> unavailable.requireCurrentAdmit(QUERY))
                .isInstanceOf(Hrm013ServiceAuthorityPort.AuthorityUnavailableException.class)
                .hasMessage("HRM-013 service authority is unavailable");
    }
}
