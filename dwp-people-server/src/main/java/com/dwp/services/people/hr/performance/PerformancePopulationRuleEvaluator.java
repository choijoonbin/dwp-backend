package com.dwp.services.people.hr.performance;

import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotV1;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Performance-owned, version-bound population policy seam. Implementations must not read HRM tables. */
public interface PerformancePopulationRuleEvaluator {

    PopulationRuleEvaluation evaluate(PopulationRuleRequest request);

    record PopulationRuleRequest(
            long tenantId,
            UUID populationRuleVersionId,
            Instant asOf,
            List<WorkforceSnapshotV1> workforceSnapshots) {

        public PopulationRuleRequest {
            workforceSnapshots = List.copyOf(workforceSnapshots);
        }
    }

    record PopulationRuleEvaluation(
            UUID populationRuleVersionId,
            Instant effectiveFrom,
            Instant effectiveTo,
            List<PopulationDecision> decisions) {

        public PopulationRuleEvaluation {
            decisions = List.copyOf(decisions);
        }
    }

    record PopulationDecision(
            UUID workerPublicId,
            UUID assignmentPublicId,
            String eligibilityCode) {
    }
}
