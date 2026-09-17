package com.dwp.services.platform.workplace.workplacevisits;

import java.time.Duration;
import java.time.OffsetDateTime;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitRepository.ProviderRow;

final class WorkplaceVisitProviderTruth {
    private static final Duration FRESHNESS = Duration.ofMinutes(15);

    private WorkplaceVisitProviderTruth() { }

    static ProviderTruth missing(ProviderKind kind) {
        return new ProviderTruth(kind, ProviderTruthState.NOT_CONFIGURED, 0, null, null,
                null, null, null, "PROVIDER_NOT_CONFIGURED", "Workplace administrator",
                "Use the governed manual visitor process.");
    }

    static ProviderTruth evaluate(ProviderRow row, OffsetDateTime now) {
        ProviderTruthState state;
        String limitation = null;
        if (!row.active()) {
            state = ProviderTruthState.NOT_CONFIGURED;
            limitation = "PROVIDER_NOT_CONFIGURED";
        } else if (row.reportedState() == null || row.observedConfigurationVersion() == null
                || row.observedConfigurationVersion() != row.configurationVersion()) {
            state = ProviderTruthState.CONFIGURED_UNVERIFIED;
            limitation = "PROVIDER_CONFIGURATION_UNVERIFIED";
        } else if (row.sourceAt() == null || row.receivedAt() == null
                || row.sourceAt().isAfter(row.receivedAt())
                || row.receivedAt().isBefore(now.minus(FRESHNESS))) {
            state = ProviderTruthState.STALE;
            limitation = "PROVIDER_EVIDENCE_STALE";
        } else if (ProviderTruthState.STALE.name().equals(row.reportedState())) {
            state = ProviderTruthState.STALE;
            limitation = "PROVIDER_EVIDENCE_STALE";
        } else if (ProviderTruthState.DEGRADED.name().equals(row.reportedState())) {
            state = ProviderTruthState.DEGRADED;
            limitation = "PROVIDER_DEGRADED";
        } else if (ProviderTruthState.READY.name().equals(row.reportedState())
                && row.lastSuccessAt() != null
                && !row.lastSuccessAt().isBefore(now.minus(FRESHNESS))
                && !row.lastSuccessAt().isAfter(row.receivedAt())) {
            state = ProviderTruthState.READY;
        } else if (ProviderTruthState.READY.name().equals(row.reportedState())
                && row.lastSuccessAt() != null
                && row.lastSuccessAt().isBefore(now.minus(FRESHNESS))) {
            state = ProviderTruthState.STALE;
            limitation = "PROVIDER_EVIDENCE_STALE";
        } else {
            state = ProviderTruthState.CONFIGURED_UNVERIFIED;
            limitation = "PROVIDER_CONFIGURATION_UNVERIFIED";
        }
        return new ProviderTruth(row.kind(), state, row.configurationVersion(),
                row.observedConfigurationVersion(), row.evidenceReference(), row.lastSuccessAt(),
                row.sourceAt(), row.receivedAt(), limitation, row.manualOwner(), row.manualProcedure());
    }
}
