package com.dwp.services.people.integration;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.people.security.HcmStepUpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/workforce/data-operations/hris")
public class HrisOperationsController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final HrisImportService service;
    private final HrisConnectorExecutionService execution;

    public HrisOperationsController(
            HrisImportService service,
            HrisConnectorExecutionService execution) {
        this.service = service;
        this.execution = execution;
    }

    @GetMapping("/sources")
    public ApiResponse<List<HrisDtos.SourceSystem>> sources() {
        return ApiResponse.success(service.sources());
    }

    @GetMapping("/connectors")
    public ApiResponse<List<HrisDtos.ConnectorInstance>> connectors() {
        return ApiResponse.success(service.connectors());
    }

    @PostMapping("/connectors")
    public ApiResponse<HrisDtos.ConnectorInstance> createConnector(
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody HrisDtos.CreateConnectorRequest request) {
        return ApiResponse.success(service.createConnector(request, correlationId));
    }

    @PutMapping("/connectors/{connectorId}")
    public ApiResponse<HrisDtos.ConnectorInstance> updateConnector(
            @PathVariable UUID connectorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody HrisDtos.UpdateConnectorRequest request) {
        return ApiResponse.success(service.updateConnector(connectorId, request, correlationId));
    }

    @PostMapping("/connectors/{connectorId}/configuration-check")
    public ApiResponse<HrisDtos.ConfigurationCheck> checkConnector(
            @PathVariable UUID connectorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @RequestHeader(value = HcmStepUpHeaders.CHALLENGE, required = false) String challenge,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = HcmStepUpHeaders.DECISION_REVISION, required = false)
            String decisionRevision,
            @RequestHeader(value = HcmStepUpHeaders.EXPECTED_OBJECT_VERSION, required = false)
            Long expectedObjectVersion) {
        return ApiResponse.success(execution.checkConnector(
                connectorId, correlationId, headers(challenge, idempotencyKey,
                        decisionRevision, expectedObjectVersion)));
    }

    @GetMapping("/mapping-profiles")
    public ApiResponse<List<HrisDtos.MappingProfile>> mappings() {
        return ApiResponse.success(service.mappings());
    }

    @PostMapping("/mapping-profiles")
    public ApiResponse<HrisDtos.MappingProfile> createMapping(
            @Valid @RequestBody HrisDtos.CreateMappingProfileRequest request) {
        return ApiResponse.success(execution.createMapping(request));
    }

    @PostMapping("/mapping-profiles/{mappingId}/activate")
    public ApiResponse<HrisDtos.MappingProfile> activateMapping(
            @PathVariable UUID mappingId,
            @Valid @RequestBody HrisDtos.ActivateMappingRequest request) {
        return ApiResponse.success(execution.activateMapping(mappingId, request));
    }

    @GetMapping("/sync-runs")
    public ApiResponse<List<HrisDtos.SyncRun>> runs(
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.runs(size));
    }

    @PostMapping("/connectors/{connectorId}/executions")
    public ApiResponse<HrisDtos.ImportResult> execute(
            @PathVariable UUID connectorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @RequestHeader(value = HcmStepUpHeaders.CHALLENGE, required = false) String challenge,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = HcmStepUpHeaders.DECISION_REVISION, required = false)
            String decisionRevision,
            @RequestHeader(value = HcmStepUpHeaders.EXPECTED_OBJECT_VERSION, required = false)
            Long expectedObjectVersion,
            @Valid @RequestBody HrisDtos.ExecuteConnectorRequest request) {
        return ApiResponse.success(execution.execute(
                connectorId, request.syncMode(), null, correlationId,
                headers(challenge, idempotencyKey, decisionRevision, expectedObjectVersion)));
    }

    @PostMapping("/sync-runs/{syncRunId}/retry")
    public ApiResponse<HrisDtos.ImportResult> retry(
            @PathVariable UUID syncRunId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @RequestHeader(value = HcmStepUpHeaders.CHALLENGE, required = false) String challenge,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = HcmStepUpHeaders.DECISION_REVISION, required = false)
            String decisionRevision,
            @RequestHeader(value = HcmStepUpHeaders.EXPECTED_OBJECT_VERSION, required = false)
            Long expectedObjectVersion) {
        return ApiResponse.success(execution.retry(syncRunId, correlationId,
                headers(challenge, idempotencyKey, decisionRevision, expectedObjectVersion)));
    }

    @GetMapping("/reconciliations")
    public ApiResponse<List<HrisDtos.ReconciliationRun>> reconciliations(
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(execution.reconciliations(size));
    }

    @PostMapping("/connectors/{connectorId}/reconciliations")
    public ApiResponse<HrisDtos.ReconciliationRun> reconcile(
            @PathVariable UUID connectorId,
            @RequestParam UUID syncRunId,
            @RequestHeader(value = HcmStepUpHeaders.CHALLENGE, required = false) String challenge,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = HcmStepUpHeaders.DECISION_REVISION, required = false)
            String decisionRevision,
            @RequestHeader(value = HcmStepUpHeaders.EXPECTED_OBJECT_VERSION, required = false)
            Long expectedObjectVersion) {
        return ApiResponse.success(execution.reconcile(connectorId, syncRunId,
                headers(challenge, idempotencyKey, decisionRevision, expectedObjectVersion)));
    }

    @GetMapping("/reconciliation-issues")
    public ApiResponse<List<HrisDtos.ReconciliationIssue>> reconciliationIssues(
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.success(execution.issues(state, size));
    }

    @PutMapping("/reconciliation-issues/{issueId}")
    public ApiResponse<Void> resolveIssue(
            @PathVariable UUID issueId,
            @Valid @RequestBody HrisDtos.ResolveIssueRequest request) {
        execution.resolveIssue(issueId, request);
        return ApiResponse.success();
    }

    @PostMapping("/sample-import")
    public ApiResponse<HrisDtos.ImportResult> importSample(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {
        return ApiResponse.success(service.importSyntheticWorkdayFixture(
                idempotencyKey, correlationId));
    }

    private HcmStepUpHeaders headers(
            String challenge,
            String idempotencyKey,
            String decisionRevision,
            Long expectedObjectVersion) {
        return new HcmStepUpHeaders(
                challenge, idempotencyKey, decisionRevision, expectedObjectVersion);
    }
}
