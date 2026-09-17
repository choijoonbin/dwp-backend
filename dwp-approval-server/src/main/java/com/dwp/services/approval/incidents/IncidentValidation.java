package com.dwp.services.approval.incidents;

import java.time.Instant;
import java.util.List;

import static com.dwp.services.approval.incidents.IncidentModels.*;

final class IncidentValidation {
    private IncidentValidation() {
    }

    static void validateOpen(OpenIncident input) {
        if (input == null || input.incidentId() == null || input.severity() == null
                || input.sourceKind() == null || blank(input.incidentKey())
                || !input.incidentKey().trim().toUpperCase()
                .matches("[A-Z][A-Z0-9_.-]{2,99}")
                || blank(input.title()) || input.title().length() > 240
                || blank(input.sourceReference()) || input.sourceReference().length() > 240) {
            throw IncidentRejected.invalid("Incident definition is invalid.");
        }
    }

    static void validateDiagnostic(DiagnosticCommand input, Instant now) {
        if (input == null || input.diagnosticId() == null
                || !List.of("SUMMARY", "DELIVERY", "CONNECTOR", "TRACE", "METRIC")
                .contains(input.diagnosticKind())
                || input.payload() == null || blank(input.sourceRevision())
                || input.observedAt() == null || input.observedAt().isAfter(now.plusSeconds(30))
                || input.expectedIncidentVersion() < 1) {
            throw IncidentRejected.invalid("Incident diagnostic command is invalid.");
        }
    }

    static void validatePlan(CreatePlan input) {
        if (input == null || input.planId() == null || input.planKind() == null
                || input.targetSnapshot() == null || input.targetSnapshot().isEmpty()
                || input.stages() == null || input.stages().isEmpty() || input.stages().size() > 100
                || input.expectedIncidentVersion() < 1) {
            throw IncidentRejected.invalid("Recovery plan definition is invalid.");
        }
        for (int index = 0; index < input.stages().size(); index++) {
            StageDraft stage = input.stages().get(index);
            if (stage == null || stage.stageNumber() != index + 1
                    || stage.actionKind() == null || stage.targetType() == null
                    || stage.targetId() == null || stage.expectedTargetVersion() < 0) {
                throw IncidentRejected.invalid(
                        "Recovery stages must be contiguous and version-bound.");
            }
        }
    }

    static void validateStageCompletion(VerifiedStageCompletion input, Instant now) {
        boolean signed = input != null
                && input.receiptIssuer() != null
                && input.receiptKeyId() != null
                && input.receiptVerificationReference() != null;
        if (input == null || input.stageNumber() < 1 || input.stageNumber() > 100
                || input.expectedPlanVersion() < 1 || input.expectedStageVersion() < 1
                || !List.of(StageState.SUCCEEDED, StageState.FAILED,
                StageState.UNKNOWN_REMOTE_OUTCOME).contains(input.state())
                || input.result() == null || input.result().isEmpty()
                || !digest(input.evidenceSha256()) || input.completedAt() == null
                || input.completedAt().isAfter(now.plusSeconds(30))
                || input.state() != StageState.UNKNOWN_REMOTE_OUTCOME && !signed
                || !signed && (input.receiptIssuer() != null || input.receiptKeyId() != null
                || input.receiptVerificationReference() != null)
                || signed && (!bounded(input.receiptIssuer(), 160)
                || !bounded(input.receiptKeyId(), 120)
                || !input.receiptVerificationReference().matches("verified:[a-f0-9]{64}"))) {
            throw IncidentRejected.invalid("Recovery stage completion is invalid.");
        }
    }

    static void validatePostmortem(PostmortemCommand input) {
        if (input == null || input.postmortemId() == null || blank(input.summary())
                || input.summary().length() > 4_000 || input.contributingFactors() == null
                || input.contributingFactors().isEmpty() || input.contributingFactors().size() > 100
                || input.correctiveActions() == null || input.correctiveActions().isEmpty()
                || input.correctiveActions().size() > 100 || !digest(input.evidenceSha256())
                || input.expectedIncidentVersion() < 1
                || input.contributingFactors().stream().anyMatch(IncidentValidation::invalidNarrative)
                || input.correctiveActions().stream().anyMatch(IncidentValidation::invalidNarrative)) {
            throw IncidentRejected.invalid("Incident postmortem is invalid.");
        }
    }

    static boolean transitionAllowed(IncidentStatus from, IncidentStatus to) {
        return switch (from) {
            case OPEN -> List.of(IncidentStatus.INVESTIGATING, IncidentStatus.CLOSED).contains(to);
            case INVESTIGATING -> List.of(IncidentStatus.MITIGATING,
                    IncidentStatus.MONITORING, IncidentStatus.RESOLVED).contains(to);
            case MITIGATING -> List.of(IncidentStatus.INVESTIGATING,
                    IncidentStatus.MONITORING, IncidentStatus.RESOLVED).contains(to);
            case MONITORING -> List.of(IncidentStatus.MITIGATING,
                    IncidentStatus.RESOLVED).contains(to);
            case RESOLVED -> List.of(IncidentStatus.INVESTIGATING, IncidentStatus.CLOSED).contains(to);
            case CLOSED -> false;
        };
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean bounded(String value, int maximum) {
        return value != null && value.equals(value.strip())
                && !value.isBlank() && value.length() <= maximum;
    }

    private static boolean invalidNarrative(String value) {
        return blank(value) || value.length() > 1_000;
    }
}
