package com.dwp.services.time.absence.employment;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EmploymentAbsenceOwnerRecoveryTest {

    @Test
    void verifiesTypedSnapshotDigestsSignatureAndCurrentAuthority() {
        EmploymentAbsenceOwnerClient client =
                EmploymentAbsenceTestSupport.successfulOwnerClient(7, 5);

        var result = client.recover(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                5,
                EmploymentAbsenceTestSupport.CORRELATION);

        assertThat(result)
                .isInstanceOf(EmploymentAbsenceContract.OwnerRecovered.class);
        var recovered = (EmploymentAbsenceContract.OwnerRecovered) result;
        assertThat(recovered.snapshot().relationshipRevision()).isEqualTo(7);
        assertThat(recovered.snapshot().ownerRevision()).isEqualTo(7);
        assertThat(recovered.evidence().businessTimeZone()).isEqualTo("UTC");
    }

    @Test
    void unavailableIndependentGrantReturnsZeroFactsAndMakesNoOwnerCall() {
        AtomicInteger calls = new AtomicInteger();
        EmploymentAbsenceOwnerClient client = new EmploymentAbsenceOwnerClient(
                tenant -> Optional.empty(),
                tenant -> Optional.of(EmploymentAbsenceTestSupport.context()),
                (grant, relationship, minimum, date, correlation) -> Optional.empty(),
                proof -> {
                    calls.incrementAndGet();
                    return new EmploymentAbsenceContract.WireFailure("UNAVAILABLE");
                },
                keyId -> Optional.empty(),
                (tenant, relationship, correlation, outcome, digest, evidence) -> true,
                EmploymentAbsenceTestSupport.mapper(), EmploymentAbsenceTestSupport.CLOCK);

        var result = client.recover(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                0,
                EmploymentAbsenceTestSupport.CORRELATION);

        assertThat(result).isInstanceOf(EmploymentAbsenceContract.OwnerDenied.class);
        assertThat(calls).hasValue(0);
        assertThat(((EmploymentAbsenceContract.OwnerDenied) result).failure().code())
                .isEqualTo("OWNER_AUTHORITY_UNAVAILABLE");
    }

    @Test
    void futureOwnerAsOfAndIssuedAtAreRejectedAsInvalidOwnerResponse() {
        var signed = EmploymentAbsenceTestSupport.signedOwnerAt(
                7, 5, EmploymentAbsenceTestSupport.NOW.plusSeconds(6));
        EmploymentAbsenceOwnerClient client = new EmploymentAbsenceOwnerClient(
                tenant -> Optional.of(EmploymentAbsenceTestSupport.grant()),
                tenant -> Optional.of(EmploymentAbsenceTestSupport.context()),
                (grant, relationship, minimum, date, correlation) ->
                        Optional.of(EmploymentAbsenceTestSupport.proof(minimum)),
                proof -> new EmploymentAbsenceContract.WireSuccess(signed.success()),
                keyId -> Optional.of(signed.key()),
                (tenant, relationship, correlation, outcome, digest, evidence) -> true,
                EmploymentAbsenceTestSupport.mapper(), EmploymentAbsenceTestSupport.CLOCK);

        var result = client.recover(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                5,
                EmploymentAbsenceTestSupport.CORRELATION);

        assertThat(result).isInstanceOf(EmploymentAbsenceContract.OwnerDenied.class);
        assertThat(((EmploymentAbsenceContract.OwnerDenied) result).failure().code())
                .isEqualTo("OWNER_RESPONSE_INVALID");
    }

    @Test
    void mismatchedRequestSigningKeyIsRejectedBeforeOwnerTransport() {
        AtomicInteger calls = new AtomicInteger();
        EmploymentAbsenceOwnerClient client = new EmploymentAbsenceOwnerClient(
                tenant -> Optional.of(EmploymentAbsenceTestSupport.grant()),
                tenant -> Optional.of(EmploymentAbsenceTestSupport.context()),
                (grant, relationship, minimum, date, correlation) -> Optional.of(
                        EmploymentAbsenceTestSupport.proof(minimum, "other-key")),
                proof -> {
                    calls.incrementAndGet();
                    return new EmploymentAbsenceContract.WireFailure("UNAVAILABLE");
                },
                keyId -> Optional.empty(),
                (tenant, relationship, correlation, outcome, digest, evidence) -> true,
                EmploymentAbsenceTestSupport.mapper(), EmploymentAbsenceTestSupport.CLOCK);

        var result = client.recover(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                0,
                EmploymentAbsenceTestSupport.CORRELATION);

        assertThat(result).isInstanceOf(EmploymentAbsenceContract.OwnerDenied.class);
        assertThat(((EmploymentAbsenceContract.OwnerDenied) result).failure().code())
                .isEqualTo("OWNER_AUTHORITY_UNAVAILABLE");
        assertThat(calls).hasValue(0);
    }
}
