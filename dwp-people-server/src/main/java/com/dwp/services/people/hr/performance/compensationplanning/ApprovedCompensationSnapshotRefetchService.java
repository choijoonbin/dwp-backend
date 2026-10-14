package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Executes owner grant revalidation and the exact immutable XCON-020 read. */
@Service
public class ApprovedCompensationSnapshotRefetchService {

    private final ApprovedCompensationSnapshotOwnerAuthority authority;
    private final ApprovedCompensationPlanSnapshotProvider provider;

    ApprovedCompensationSnapshotRefetchService(
            ApprovedCompensationSnapshotOwnerAuthority authority,
            ApprovedCompensationPlanSnapshotProvider provider) {
        this.authority = authority;
        this.provider = provider;
    }

    @Transactional
    public Xcon020ApprovedCompensationPlanSnapshotV1 get(
            ApprovedCompensationSnapshotRequestContext.VerifiedRequest request) {
        authority.requireActiveTenant(request);
        return provider.getExact(
                request.snapshotId(), request.snapshotRevision(), request.payloadDigest(),
                new ApprovedCompensationPlanSnapshotProvider.ServicePepEvidence(
                        true,
                        request.callerWorkloadId(),
                        request.audience(),
                        request.tenantId(),
                        request.tenantId(),
                        request.tenantGrantRevision(),
                        request.policyRevision(),
                        request.purposeCode(),
                        request.projectionId(),
                        request.correlationId()));
    }
}
