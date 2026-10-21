package com.dwp.services.people.hris.contracts.tim003;

import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/** Production-safe defaults until independently admitted grant, key and audit backings exist. */
public final class FailClosedTimeAbsenceOwnerAdapters {
    private FailClosedTimeAbsenceOwnerAdapters() {
    }

    @Component
    @ConditionalOnMissingBean(TimeAbsenceOwnerPorts.WorkloadProofVerifier.class)
    static final class Proofs implements TimeAbsenceOwnerPorts.WorkloadProofVerifier {
        @Override
        public TimeAbsenceOwnerContract.VerifiedRequest verify(
                HttpServletRequest request,
                TimeAbsenceOwnerContract.Request ownerRequest,
                String exactPath,
                String queryFingerprint) {
            throw TimeAbsenceOwnerContract.Failure.unavailable();
        }
    }

    @Component
    @ConditionalOnMissingBean(TimeAbsenceOwnerPorts.IngressDenialAudit.class)
    static final class IngressDenials
            implements TimeAbsenceOwnerPorts.IngressDenialAudit {
        @Override
        public boolean append(TimeAbsenceOwnerContract.IngressDenialEvidence evidence) {
            return false;
        }
    }

    @Component
    @ConditionalOnMissingBean(TimeAbsenceOwnerPorts.OwnerGrantVerifierAuthority.class)
    static final class Grants implements TimeAbsenceOwnerPorts.OwnerGrantVerifierAuthority {
        @Override
        public Optional<TimeAbsenceOwnerContract.OwnerGrant> current(long tenantId) {
            return Optional.empty();
        }
    }

    @Component
    @ConditionalOnMissingBean(TimeAbsenceOwnerPorts.ResponseSigner.class)
    static final class Signatures implements TimeAbsenceOwnerPorts.ResponseSigner {
        @Override
        public Optional<TimeAbsenceOwnerContract.WorkloadResponseEvidence> signSuccess(
                TimeAbsenceOwnerContract.VerifiedRequest request,
                Xcon027EmploymentAbsenceSnapshotV1 snapshot) {
            return Optional.empty();
        }

        @Override
        public Optional<TimeAbsenceOwnerContract.Problem> signProblem(
                TimeAbsenceOwnerContract.VerifiedRequest request,
                TimeAbsenceOwnerContract.Failure failure,
                Instant issuedAt) {
            return Optional.empty();
        }
    }

    @Component
    @ConditionalOnMissingBean(TimeAbsenceOwnerPorts.Audit.class)
    static final class Audit implements TimeAbsenceOwnerPorts.Audit {
        @Override
        public boolean appendAuthenticatedOutcome(
                TimeAbsenceOwnerContract.VerifiedRequest request,
                String outcome,
                String contentDigest) {
            return false;
        }
    }
}
