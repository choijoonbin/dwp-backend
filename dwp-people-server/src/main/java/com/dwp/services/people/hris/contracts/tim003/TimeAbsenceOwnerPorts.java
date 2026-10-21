package com.dwp.services.people.hris.contracts.tim003;

import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.Optional;

public final class TimeAbsenceOwnerPorts {
    private TimeAbsenceOwnerPorts() {
    }

    public interface WorkloadProofVerifier {
        TimeAbsenceOwnerContract.VerifiedRequest verify(
                HttpServletRequest request,
                TimeAbsenceOwnerContract.Request ownerRequest,
                String exactPath,
                String queryFingerprint);
    }

    public interface IngressDenialAudit {
        boolean append(TimeAbsenceOwnerContract.IngressDenialEvidence evidence);
    }

    public interface OwnerGrantVerifierAuthority {
        Optional<TimeAbsenceOwnerContract.OwnerGrant> current(long tenantId);
    }

    public interface ReplayReservation {
        boolean reserve(TimeAbsenceOwnerContract.VerifiedRequest request);
    }

    public interface OwnerFactProvider {
        Optional<TimeAbsenceOwnerContract.OwnerFacts> readCurrent(
                TimeAbsenceOwnerContract.VerifiedRequest request,
                TimeAbsenceOwnerContract.OwnerGrant grant);
    }

    public interface ResponseSigner {
        Optional<TimeAbsenceOwnerContract.WorkloadResponseEvidence> signSuccess(
                TimeAbsenceOwnerContract.VerifiedRequest request,
                Xcon027EmploymentAbsenceSnapshotV1 snapshot);

        Optional<TimeAbsenceOwnerContract.Problem> signProblem(
                TimeAbsenceOwnerContract.VerifiedRequest request,
                TimeAbsenceOwnerContract.Failure failure,
                Instant issuedAt);
    }

    public interface Audit {
        boolean appendAuthenticatedOutcome(
                TimeAbsenceOwnerContract.VerifiedRequest request,
                String outcome,
                String contentDigest);
    }
}
