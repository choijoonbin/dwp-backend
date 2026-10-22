package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class WorkforceIngestionAuthorizationTest {
    @Test
    void canonicalBoundaryUsesOnlyNarrowCapabilityDutyPurposeAndHighStepUp() {
        assertThat(com.dwp.services.people.hris.contracts.hrm012
                .Hrm012WorkforceIngestionContract.CAPABILITY)
                .isEqualTo("hcm.integration.workforce-ingestion.execute")
                .isNotEqualTo("hcm.integration.execute");
        assertThat(com.dwp.services.people.hris.contracts.hrm012
                .Hrm012WorkforceIngestionContract.DUTY)
                .isEqualTo("HRIS_DUTY_INTEGRATION_EXECUTOR");
        assertThat(com.dwp.services.people.hris.contracts.hrm012
                .Hrm012WorkforceIngestionContract.PURPOSE)
                .isEqualTo("HRIS_INTEGRATION");
        assertThat(com.dwp.services.people.hris.contracts.hrm012
                .Hrm012WorkforceIngestionContract.STEP_UP_POLICY)
                .isEqualTo("STEPUP-MGMT-HIGH-V1");
    }

    @Test
    void markerIdentityMismatchFailsBeforeSnapshotResolution() {
        Hrm012WorkforceIngestionPorts.TrustedAuthoritySnapshotPort snapshots =
                mock(Hrm012WorkforceIngestionPorts.TrustedAuthoritySnapshotPort.class);
        Hrm012WorkforceIngestionAuthority authority =
                new Hrm012WorkforceIngestionAuthority(
                        snapshots, mock(Hrm012WorkforceIngestionPorts
                                .PostReceiptAuthorityPort.class),
                        java.time.Clock.fixed(Hrm012TestFixtures.NOW,
                                java.time.ZoneOffset.UTC));
        var wrong = new com.dwp.core.security.Hrm012OwnerReceiptAuthorityVerifier.Evidence(
                Hrm012TestFixtures.TENANT, "9999", "jti", Hrm012TestFixtures.NOW.plusSeconds(10),
                "APP.HCM", "feature-v1", Hrm012TestFixtures.CORRELATION.toString(),
                Hrm012TestFixtures.marker().idempotencyKeySha256(), "challenge",
                Hrm012TestFixtures.DIGEST_A);

        assertThatThrownBy(() -> authority.verifyIdentity(
                Hrm012TestFixtures.actor(), wrong, Hrm012TestFixtures.headers()))
                .isInstanceOf(Hrm012Problem.class)
                .satisfies(error -> assertThat(((Hrm012Problem) error).status())
                        .isEqualTo(403));
        org.mockito.Mockito.verifyNoInteractions(snapshots);
    }
}
