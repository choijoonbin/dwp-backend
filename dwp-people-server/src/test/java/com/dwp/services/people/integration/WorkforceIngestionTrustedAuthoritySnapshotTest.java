package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class WorkforceIngestionTrustedAuthoritySnapshotTest {
    @Test
    void markerV1AloneNeverBecomesACompleteSnapshot() {
        Hrm012FailClosedWorkforceIngestionAdapters defaults =
                new Hrm012FailClosedWorkforceIngestionAdapters();
        Hrm012WorkforceIngestionAuthority authority =
                new Hrm012WorkforceIngestionAuthority(
                        defaults.trustedSnapshot(),
                        mock(Hrm012WorkforceIngestionPorts
                                .PostReceiptAuthorityPort.class),
                        java.time.Clock.fixed(Hrm012TestFixtures.NOW,
                                java.time.ZoneOffset.UTC));
        var identity = authority.verifyIdentity(
                Hrm012TestFixtures.actor(), Hrm012TestFixtures.marker(),
                Hrm012TestFixtures.headers());

        assertThatThrownBy(() -> authority.preReceipt(identity,
                Hrm012TestFixtures.command(), Hrm012TestFixtures.headers(),
                Hrm012TestFixtures.DIGEST_A))
                .isInstanceOf(Hrm012Problem.class)
                .satisfies(error -> {
                    assertThat(((Hrm012Problem) error).status()).isEqualTo(503);
                    assertThat(((Hrm012Problem) error).retryable()).isTrue();
                });
    }

    @Test
    void completeSnapshotIsBoundToMarkerIdentityAndCanonicalHash() {
        var snapshot = Hrm012TestFixtures.snapshot(Hrm012TestFixtures.DIGEST_A);
        Hrm012WorkforceIngestionAuthority authority =
                new Hrm012WorkforceIngestionAuthority(request -> snapshot,
                        mock(Hrm012WorkforceIngestionPorts
                                .PostReceiptAuthorityPort.class),
                        java.time.Clock.fixed(Hrm012TestFixtures.NOW,
                                java.time.ZoneOffset.UTC));
        var identity = authority.verifyIdentity(
                Hrm012TestFixtures.actor(), Hrm012TestFixtures.marker(),
                Hrm012TestFixtures.headers());

        assertThat(authority.preReceipt(identity, Hrm012TestFixtures.command(),
                Hrm012TestFixtures.headers(), Hrm012TestFixtures.DIGEST_A))
                .isEqualTo(snapshot);
        assertThat(snapshot.originatingContextDigest()).matches("[a-f0-9]{64}");
    }
}
