package com.dwp.services.approval.incidents;

import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.approval.incidents.IncidentModels.*;

@Service
public class IncidentEndpointService {
    private static final String BASE = "/v1/admin/operations/incidents";

    private final IncidentService service;
    private final IncidentGovernanceService governance;
    private final IncidentEndpointAuthority authority;

    @Autowired
    public IncidentEndpointService(
            IncidentService service,
            IncidentGovernanceService governance,
            IncidentEndpointAuthority authority) {
        this.service = service;
        this.governance = governance;
        this.authority = authority;
    }

    IncidentEndpointService(
            IncidentService service,
            IncidentEndpointAuthority authority) {
        this(service, null, authority);
    }

    @Transactional(readOnly = true)
    public List<IncidentView> incidents() {
        authority.read();
        return service.incidents();
    }

    @Transactional(readOnly = true)
    public IncidentDetail incident(UUID incidentId) {
        authority.read();
        return service.incident(incidentId);
    }

    @Transactional(readOnly = true)
    public PlanView plan(UUID incidentId, UUID planId) {
        authority.read();
        return service.plan(incidentId, planId);
    }

    @Transactional(readOnly = true)
    public List<IncidentReport> reports(UUID incidentId) {
        authority.read();
        return governance.reports(incidentId);
    }

    @Transactional(readOnly = true)
    public IncidentReport report(UUID incidentId, UUID reportId) {
        authority.read();
        return governance.report(incidentId, reportId);
    }

    @Transactional(readOnly = true)
    public List<DeadLetterView> deadLetters(int limit) {
        authority.read();
        return governance.deadLetters(limit);
    }

    @Transactional
    public IncidentView open(OpenIncident input, ApprovalStepUpHeaders headers) {
        String path = BASE;
        var permit = authority.begin("INCIDENT", input.incidentId(), 0,
                "POST", path, input, headers);
        IncidentView result = service.open(headers.idempotencyKey(), input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public IncidentView status(
            UUID incidentId, StatusCommand input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/" + incidentId + "/status";
        var permit = authority.begin("INCIDENT", incidentId, input.expectedVersion(),
                "POST", path, input, headers);
        IncidentView result = service.changeStatus(headers.idempotencyKey(), incidentId, input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public DiagnosticView diagnostic(
            UUID incidentId, DiagnosticCommand input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/" + incidentId + "/diagnostics";
        var permit = authority.begin("INCIDENT", incidentId,
                input.expectedIncidentVersion(), "POST", path, input, headers);
        DiagnosticView result = service.addDiagnostic(
                headers.idempotencyKey(), incidentId, input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public PlanView createPlan(
            UUID incidentId, CreatePlan input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/" + incidentId + "/recovery-plans";
        var permit = authority.begin("INCIDENT", incidentId,
                input.expectedIncidentVersion(), "POST", path, input, headers);
        PlanView result = service.createPlan(headers.idempotencyKey(), incidentId, input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public PlanView dryRun(
            UUID incidentId,
            UUID planId,
            DryRunObservation input,
            ApprovalStepUpHeaders headers) {
        String path = BASE + "/" + incidentId + "/recovery-plans/" + planId + "/dry-run";
        var permit = authority.begin("INCIDENT_RECOVERY_PLAN", planId,
                input.expectedPlanVersion(), "POST", path, input, headers);
        PlanView result = service.recordDryRun(
                headers.idempotencyKey(), incidentId, planId, input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public PlanView startStage(
            UUID incidentId,
            UUID planId,
            StageStart input,
            ApprovalStepUpHeaders headers) {
        String path = BASE + "/" + incidentId + "/recovery-plans/" + planId
                + "/stages/" + input.stageNumber() + "/start";
        var permit = authority.begin("INCIDENT_RECOVERY_PLAN", planId,
                input.expectedPlanVersion(), "POST", path, input, headers);
        PlanView result = service.startStage(
                headers.idempotencyKey(), incidentId, planId, input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public PlanView completeStage(
            UUID incidentId,
            UUID planId,
            StageCompletion input,
            ApprovalStepUpHeaders headers) {
        String path = BASE + "/" + incidentId + "/recovery-plans/" + planId
                + "/stages/" + input.stageNumber() + "/complete";
        var permit = authority.begin("INCIDENT_RECOVERY_PLAN", planId,
                input.expectedPlanVersion(), "POST", path, input, headers);
        PlanView result = service.completeStage(
                headers.idempotencyKey(), incidentId, planId, input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public PlanView reconcile(
            UUID incidentId,
            UUID planId,
            ReconcileCommand input,
            ApprovalStepUpHeaders headers) {
        String path = BASE + "/" + incidentId + "/recovery-plans/" + planId + "/reconcile";
        var permit = authority.begin("INCIDENT_RECOVERY_PLAN", planId,
                input.expectedVersion(), "POST", path, input, headers);
        PlanView result = service.reconcilePlan(
                headers.idempotencyKey(), incidentId, planId, input.expectedVersion());
        authority.complete(permit);
        return result;
    }

    @Transactional
    public PostmortemView postmortem(
            UUID incidentId, PostmortemCommand input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/" + incidentId + "/postmortem";
        var permit = authority.begin("INCIDENT", incidentId,
                input.expectedIncidentVersion(), "POST", path, input, headers);
        PostmortemView result = service.recordPostmortem(
                headers.idempotencyKey(), incidentId, input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public IncidentReport createReport(
            UUID incidentId,
            ReportCommand input,
            ApprovalStepUpHeaders headers) {
        String path = BASE + "/" + incidentId + "/reports";
        var permit = authority.begin("INCIDENT", incidentId,
                input.expectedIncidentVersion(), "POST", path, input, headers);
        IncidentReport result = governance.createReport(
                headers.idempotencyKey(), incidentId, input);
        authority.complete(permit);
        return result;
    }

}
