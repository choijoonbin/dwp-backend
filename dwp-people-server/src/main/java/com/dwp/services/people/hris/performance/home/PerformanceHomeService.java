package com.dwp.services.people.hris.performance.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Owner-side exact materialization use case. */
@Service
@ConditionalOnProperty(prefix = "dwp.people.hris.performance-home", name = "enabled",
        havingValue = "true")
final class PerformanceHomeService {

    private final PerformanceHomeProjectionPort projections;
    private final PerformanceHomeMaterializer materializer;

    PerformanceHomeService(
            PerformanceHomeProjectionPort projections,
            PerformanceHomeMaterializer materializer) {
        this.projections = projections;
        this.materializer = materializer;
    }

    HrisHomeContributionMaterializationV1 materialize(
            String payloadRef,
            PerformanceHomeAdmission.Admission admission) {
        PerformanceHomeProjectionPort.Snapshot snapshot =
                projections.load(admission.authority());
        return materializer.materialize(payloadRef, admission.authority(), snapshot);
    }
}
