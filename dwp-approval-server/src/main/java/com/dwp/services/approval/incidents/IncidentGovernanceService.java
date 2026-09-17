package com.dwp.services.approval.incidents;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.approval.incidents.IncidentModels.*;

@Service
class IncidentGovernanceService {
    private final IncidentRepository commands;
    private final IncidentProjectionRepository projections;
    private final IncidentReportRepository reports;
    private final Clock clock;

    IncidentGovernanceService(
            IncidentRepository commands,
            IncidentProjectionRepository projections,
            IncidentReportRepository reports,
            Clock clock) {
        this.commands = commands;
        this.projections = projections;
        this.reports = reports;
        this.clock = clock;
    }

    @Transactional
    IncidentReport createReport(
            String idempotencyKey,
            UUID incidentId,
            ReportCommand command) {
        validate(command);
        Context context = Context.current(idempotencyKey);
        IncidentReport prior = commands.prior(context, "CREATE_REPORT",
                command.reportId(), command, IncidentReport.class);
        if (prior != null) return prior;
        IncidentDetail detail = projections.detail(context, incidentId);
        if (detail.postmortem() == null) {
            throw IncidentRejected.conflict(
                    "A sealed postmortem is required before an incident report can be generated.");
        }
        List<DeadLetterView> deadLetters = command.deadLetterIds().isEmpty()
                ? List.of()
                : reports.deadLetters(
                        context, command.deadLetterIds(), command.deadLetterIds().size());
        long requested = command.deadLetterIds().stream().distinct().count();
        if (deadLetters.size() != requested) {
            throw IncidentRejected.conflict(
                    "Every selected dead letter must remain visible in the exact management scope.");
        }
        Instant generatedAt = clock.instant();
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "apr-incident-evidence-report.v1");
        payload.put("generatedAt", generatedAt);
        payload.put("incident", detail.incident());
        payload.put("timeline", detail.timeline());
        payload.put("diagnostics", detail.diagnostics());
        payload.put("recoveryPlans", detail.recoveryPlans());
        payload.put("postmortem", detail.postmortem());
        payload.put("deadLetters", deadLetters);
        payload.put("assuranceBoundaries", List.of(
                "Dead-letter payload bodies and raw provider errors are excluded.",
                "Replay remains governed by the canonical Approval operations endpoint.",
                "The report digest proves exact exported evidence bytes only."));
        IncidentReport report = reports.create(
                context, incidentId, command, payload, generatedAt);
        commands.complete(context, "CREATE_REPORT", command, report);
        return report;
    }

    @Transactional(readOnly = true)
    List<IncidentReport> reports(UUID incidentId) {
        return reports.reports(readContext(), incidentId);
    }

    @Transactional(readOnly = true)
    IncidentReport report(UUID incidentId, UUID reportId) {
        return reports.report(readContext(), incidentId, reportId);
    }

    @Transactional(readOnly = true)
    List<DeadLetterView> deadLetters(int limit) {
        return reports.deadLetters(readContext(), List.of(), limit);
    }

    private Context readContext() {
        return Context.current("read-" + UUID.randomUUID());
    }

    private void validate(ReportCommand command) {
        if (command == null || command.reportId() == null || command.format() == null
                || command.expectedIncidentVersion() < 1
                || command.deadLetterIds().size() > 100
                || command.deadLetterIds().stream().anyMatch(java.util.Objects::isNull)
                || command.deadLetterIds().stream().distinct().count()
                != command.deadLetterIds().size()) {
            throw IncidentRejected.invalid("Incident report command is invalid.");
        }
    }
}
