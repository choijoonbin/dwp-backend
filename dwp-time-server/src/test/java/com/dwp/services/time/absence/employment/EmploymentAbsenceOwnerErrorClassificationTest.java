package com.dwp.services.time.absence.employment;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EmploymentAbsenceOwnerErrorClassificationTest {

    @Test
    void signedDateRolledAndAuthorityStaleRemainExhaustionExempt() {
        assertRefresh(EmploymentAbsenceTestSupport.signedProblem(
                409, "DATE_ROLLED", true));
        assertRefresh(EmploymentAbsenceTestSupport.signedProblem(
                409, "OWNER_AUTHORITY_STALE", true));
    }

    @Test
    void signedPermanentProblemMaySelectPermanentClassification() {
        var signed = EmploymentAbsenceTestSupport.signedProblem(
                401, "INVALID_WORKLOAD_PROOF", false);

        var result = client(new EmploymentAbsenceContract.WireProblem(signed.problem()),
                signed.key()).recover(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP, 0,
                EmploymentAbsenceTestSupport.CORRELATION);

        var failure = ((EmploymentAbsenceContract.OwnerDenied) result).failure();
        assertThat(failure.kind())
                .isEqualTo(EmploymentAbsenceContract.OwnerFailureKind.PERMANENT);
        assertThat(failure.code()).isEqualTo("INVALID_WORKLOAD_PROOF");
    }

    @Test
    void unsignedOrTransportGeneratedErrorIsGenericRetryable() {
        var result = client(new EmploymentAbsenceContract.WireFailure("PROXY_503"),
                null).recover(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP, 0,
                EmploymentAbsenceTestSupport.CORRELATION);

        var failure = ((EmploymentAbsenceContract.OwnerDenied) result).failure();
        assertThat(failure.kind())
                .isEqualTo(EmploymentAbsenceContract.OwnerFailureKind.RETRYABLE);
        assertThat(failure.code()).isEqualTo("UNTRUSTED_OWNER_ERROR");
    }

    private static void assertRefresh(
            EmploymentAbsenceTestSupport.SignedProblem signed) {
        var result = client(new EmploymentAbsenceContract.WireProblem(signed.problem()),
                signed.key()).recover(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP, 0,
                EmploymentAbsenceTestSupport.CORRELATION);
        var failure = ((EmploymentAbsenceContract.OwnerDenied) result).failure();
        assertThat(failure.kind())
                .isEqualTo(EmploymentAbsenceContract.OwnerFailureKind.CONTEXT_REFRESH);
        assertThat(failure.exhaustionExempt()).isTrue();
    }

    private static EmploymentAbsenceOwnerClient client(
            EmploymentAbsenceContract.OwnerWireResult wire,
            EmploymentAbsenceContract.ResponseSigningKey key) {
        return new EmploymentAbsenceOwnerClient(
                tenant -> Optional.of(EmploymentAbsenceTestSupport.grant()),
                tenant -> Optional.of(EmploymentAbsenceTestSupport.context()),
                (grant, relationship, minimum, date, correlation) ->
                        Optional.of(EmploymentAbsenceTestSupport.proof(minimum)),
                proof -> wire,
                keyId -> Optional.ofNullable(key),
                (tenant, relationship, correlation, outcome, digest, evidence) -> true,
                EmploymentAbsenceTestSupport.mapper(), EmploymentAbsenceTestSupport.CLOCK);
    }
}
