package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class WorkforceIngestionReceiptIdentityBindingTest {
    @Test
    void verifiedNumericSubjectAndTrustedPersonUuidRemainOneIdentity() {
        Hrm012WorkforceIngestionAuthority authority =
                new Hrm012WorkforceIngestionAuthority(
                        mock(Hrm012WorkforceIngestionPorts.TrustedAuthoritySnapshotPort.class),
                        mock(Hrm012WorkforceIngestionPorts
                                .PostReceiptAuthorityPort.class),
                        java.time.Clock.fixed(Hrm012TestFixtures.NOW,
                                java.time.ZoneOffset.UTC));
        var verified = authority.verifyIdentity(
                Hrm012TestFixtures.actor(), Hrm012TestFixtures.marker(),
                Hrm012TestFixtures.headers());

        assertThat(verified.actor().userId()).isEqualTo(Hrm012TestFixtures.USER);
        assertThat(verified.actor().personPublicId()).isEqualTo(Hrm012TestFixtures.PERSON);
        assertThat(verified.marker().onBehalfOfSubject())
                .isEqualTo(Long.toString(Hrm012TestFixtures.USER));
    }

    @Test
    void callerCannotSubstitutePersonUuidFromNumericSubject() {
        var actor = new com.dwp.services.people.security.PeopleRequestContext.Actor(
                Hrm012TestFixtures.USER, Hrm012TestFixtures.TENANT,
                UUID.nameUUIDFromBytes(Long.toString(Hrm012TestFixtures.USER).getBytes()),
                java.util.Set.of(), java.util.Set.of());
        var snapshot = Hrm012TestFixtures.snapshot(Hrm012TestFixtures.DIGEST_A);
        Hrm012WorkforceIngestionAuthority authority =
                new Hrm012WorkforceIngestionAuthority(request -> snapshot,
                        mock(Hrm012WorkforceIngestionPorts
                                .PostReceiptAuthorityPort.class),
                        java.time.Clock.fixed(Hrm012TestFixtures.NOW,
                                java.time.ZoneOffset.UTC));
        var verified = authority.verifyIdentity(actor, Hrm012TestFixtures.marker(),
                Hrm012TestFixtures.headers());

        assertThatThrownBy(() -> authority.preReceipt(
                verified, Hrm012TestFixtures.command(), Hrm012TestFixtures.headers(),
                Hrm012TestFixtures.DIGEST_A))
                .isInstanceOf(Hrm012Problem.class);
    }
}
