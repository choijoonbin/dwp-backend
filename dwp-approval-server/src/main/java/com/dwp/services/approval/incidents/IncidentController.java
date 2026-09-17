package com.dwp.services.approval.incidents;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.approval.incidents.IncidentModels.*;

@RestController
@RequestMapping("/v1/admin/operations/incidents")
@Tag(name = "Approval incidents and recovery")
public class IncidentController {
    private static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    private static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String DECISION = "X-DWP-Expected-Decision-Revision";
    private static final String VERSION = "X-DWP-Expected-Object-Version";

    private final IncidentEndpointService endpoints;

    public IncidentController(IncidentEndpointService endpoints) {
        this.endpoints = endpoints;
    }

    @GetMapping
    @Operation(summary = "List incidents in the selected management scope")
    public ApiResponse<List<IncidentView>> incidents() {
        return ApiResponse.success(endpoints.incidents());
    }

    @GetMapping("/dead-letters")
    @Operation(summary = "List scoped dead-letter metadata without exposing raw payloads")
    public ApiResponse<List<DeadLetterView>> deadLetters(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(endpoints.deadLetters(limit));
    }

    @GetMapping("/{incidentId}")
    @Operation(summary = "Read incident timeline, diagnostics, recovery plans, and postmortem")
    public ApiResponse<IncidentDetail> incident(@PathVariable UUID incidentId) {
        return ApiResponse.success(endpoints.incident(incidentId));
    }

    @GetMapping("/{incidentId}/recovery-plans/{planId}")
    @Operation(summary = "Read a staged recovery plan and every stored stage outcome")
    public ApiResponse<PlanView> plan(
            @PathVariable UUID incidentId,
            @PathVariable UUID planId) {
        return ApiResponse.success(endpoints.plan(incidentId, planId));
    }

    @GetMapping("/{incidentId}/reports")
    @Operation(summary = "List immutable evidence reports for an incident")
    public ApiResponse<List<IncidentReport>> reports(@PathVariable UUID incidentId) {
        return ApiResponse.success(endpoints.reports(incidentId));
    }

    @GetMapping("/{incidentId}/reports/{reportId}")
    @Operation(summary = "Read one immutable incident evidence report")
    public ApiResponse<IncidentReport> report(
            @PathVariable UUID incidentId,
            @PathVariable UUID reportId) {
        return ApiResponse.success(endpoints.report(incidentId, reportId));
    }

    @PostMapping
    @Operation(summary = "Open an incident from an authoritative source reference")
    public ApiResponse<IncidentView> open(
            @RequestBody @Valid OpenIncident input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        if (input == null || input.incidentId() == null || expectedVersion != 0) {
            throw IncidentRejected.invalid(
                    "New incidents require an identifier and expected object version zero.");
        }
        return ApiResponse.success(endpoints.open(input,
                headers(stepUp, idempotencyKey, decisionRevision, 0)));
    }

    @PostMapping("/{incidentId}/status")
    @Operation(summary = "Advance incident status with immutable timeline evidence")
    public ApiResponse<IncidentView> status(
            @PathVariable UUID incidentId,
            @RequestBody @Valid StatusCommand input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.status(incidentId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/{incidentId}/diagnostics")
    @Operation(summary = "Append redacted incident diagnostics")
    public ApiResponse<DiagnosticView> diagnostic(
            @PathVariable UUID incidentId,
            @RequestBody @Valid DiagnosticCommand input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedIncidentVersion(), expectedVersion);
        return ApiResponse.success(endpoints.diagnostic(incidentId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/{incidentId}/recovery-plans")
    @Operation(summary = "Create a dry-run-first staged recovery plan")
    public ApiResponse<PlanView> createPlan(
            @PathVariable UUID incidentId,
            @RequestBody @Valid CreatePlan input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedIncidentVersion(), expectedVersion);
        if (input.planId() == null) {
            throw IncidentRejected.invalid("Recovery plan identifier is required.");
        }
        return ApiResponse.success(endpoints.createPlan(incidentId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/{incidentId}/recovery-plans/{planId}/dry-run")
    @Operation(summary = "Record a recovery-plan dry-run observation")
    public ApiResponse<PlanView> dryRun(
            @PathVariable UUID incidentId,
            @PathVariable UUID planId,
            @RequestBody @Valid DryRunObservation input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedPlanVersion(), expectedVersion);
        return ApiResponse.success(endpoints.dryRun(incidentId, planId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/{incidentId}/recovery-plans/{planId}/stages/{stageNumber}/start")
    @Operation(summary = "Start the next eligible idempotent recovery stage")
    public ApiResponse<PlanView> startStage(
            @PathVariable UUID incidentId,
            @PathVariable UUID planId,
            @PathVariable int stageNumber,
            @RequestBody @Valid StageStart input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireStage(stageNumber, input == null ? -1 : input.stageNumber());
        requireVersion(input.expectedPlanVersion(), expectedVersion);
        return ApiResponse.success(endpoints.startStage(incidentId, planId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/{incidentId}/recovery-plans/{planId}/stages/{stageNumber}/complete")
    @Operation(summary = "Record an explicit terminal recovery-stage outcome")
    public ApiResponse<PlanView> completeStage(
            @PathVariable UUID incidentId,
            @PathVariable UUID planId,
            @PathVariable int stageNumber,
            @RequestBody @Valid StageCompletion input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireStage(stageNumber, input == null ? -1 : input.stageNumber());
        requireVersion(input.expectedPlanVersion(), expectedVersion);
        return ApiResponse.success(endpoints.completeStage(incidentId, planId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/{incidentId}/recovery-plans/{planId}/reconcile")
    @Operation(summary = "Reconcile a plan strictly from stored stage evidence")
    public ApiResponse<PlanView> reconcile(
            @PathVariable UUID incidentId,
            @PathVariable UUID planId,
            @RequestBody @Valid ReconcileCommand input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.reconcile(incidentId, planId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/{incidentId}/postmortem")
    @Operation(summary = "Record the immutable postmortem for a resolved incident")
    public ApiResponse<PostmortemView> postmortem(
            @PathVariable UUID incidentId,
            @RequestBody @Valid PostmortemCommand input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedIncidentVersion(), expectedVersion);
        return ApiResponse.success(endpoints.postmortem(incidentId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/{incidentId}/reports")
    @Operation(summary = "Seal a version-bound incident evidence and dead-letter report")
    public ApiResponse<IncidentReport> createReport(
            @PathVariable UUID incidentId,
            @RequestBody @Valid ReportCommand input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedIncidentVersion(), expectedVersion);
        return ApiResponse.success(endpoints.createReport(incidentId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    private ApprovalStepUpHeaders headers(
            String stepUp, String idempotencyKey, String decisionRevision, long version) {
        return ApprovalStepUpHeaders.of(stepUp, idempotencyKey, decisionRevision, version);
    }

    private void requireStage(int pathStage, int bodyStage) {
        if (pathStage < 1 || pathStage > 100 || pathStage != bodyStage) {
            throw IncidentRejected.invalid("Path and payload recovery stages must match.");
        }
    }

    private void requireVersion(long bodyVersion, long headerVersion) {
        if (bodyVersion < 0 || bodyVersion != headerVersion) {
            throw IncidentRejected.conflict(
                    "Payload and expected-object versions must match.");
        }
    }
}
