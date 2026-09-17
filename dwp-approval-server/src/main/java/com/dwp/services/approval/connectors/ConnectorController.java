package com.dwp.services.approval.connectors;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.approval.connectors.ConnectorModels.*;

@RestController
@RequestMapping("/v1/admin/operations/connectors")
@Tag(name = "Approval governed connectors")
public class ConnectorController {
    private static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    private static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String DECISION = "X-DWP-Expected-Decision-Revision";
    private static final String VERSION = "X-DWP-Expected-Object-Version";

    private final ConnectorEndpointService endpoints;

    public ConnectorController(ConnectorEndpointService endpoints) {
        this.endpoints = endpoints;
    }

    @GetMapping
    @Operation(summary = "List governed connectors in the selected management scope")
    public ApiResponse<List<ConnectorView>> connectors() {
        return ApiResponse.success(endpoints.connectors());
    }

    @GetMapping("/{connectorId}")
    @Operation(summary = "Read connector configuration and observed probe truth")
    public ApiResponse<ConnectorDetail> connector(@PathVariable UUID connectorId) {
        return ApiResponse.success(endpoints.connector(connectorId));
    }

    @GetMapping("/{connectorId}/probes/{probeId}")
    @Operation(summary = "Read a connector readiness or synthetic-test probe")
    public ApiResponse<ProbeView> probe(
            @PathVariable UUID connectorId,
            @PathVariable UUID probeId) {
        return ApiResponse.success(endpoints.probe(connectorId, probeId));
    }

    @PutMapping("/{connectorId}/draft")
    @Operation(summary = "Save a Vault-reference-only connector draft")
    public ApiResponse<ConnectorView> save(
            @PathVariable UUID connectorId,
            @RequestBody @Valid ConnectorDraft input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireTarget(connectorId, input == null ? null : input.connectorId(),
                input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.save(input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/{connectorId}/probes")
    @Operation(summary = "Start a probe without inferring remote success")
    public ApiResponse<ProbeView> startProbe(
            @PathVariable UUID connectorId,
            @RequestBody @Valid ProbeStart input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedConnectorVersion(), expectedVersion);
        if (input.probeId() == null || input.revisionId() == null) {
            throw ConnectorRejected.invalid("Connector probe identifiers are required.");
        }
        return ApiResponse.success(endpoints.startProbe(connectorId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/{connectorId}/probes/{probeId}/complete")
    @Operation(summary = "Record terminal provider probe evidence")
    public ApiResponse<ProbeView> completeProbe(
            @PathVariable UUID connectorId,
            @PathVariable UUID probeId,
            @RequestBody @Valid ProbeCompletion input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedProbeVersion(), expectedVersion);
        return ApiResponse.success(endpoints.completeProbe(connectorId, probeId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/{connectorId}/publish")
    @Operation(summary = "Publish a reviewed connector with a current verified probe")
    public ApiResponse<ConnectorView> publish(
            @PathVariable UUID connectorId,
            @RequestBody @Valid PublishCommand input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.publish(connectorId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/{connectorId}/lifecycle")
    @Operation(summary = "Disable, re-enable, or retire a published connector")
    public ApiResponse<ConnectorView> lifecycle(
            @PathVariable UUID connectorId,
            @RequestBody @Valid LifecycleCommand input,
            @RequestHeader(VERSION) long expectedVersion,
            @RequestHeader(STEP_UP) String stepUp,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION) String decisionRevision) {
        requireVersion(input == null ? -1 : input.expectedVersion(), expectedVersion);
        return ApiResponse.success(endpoints.lifecycle(connectorId, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    private ApprovalStepUpHeaders headers(
            String stepUp, String idempotencyKey, String decisionRevision, long version) {
        return ApprovalStepUpHeaders.of(stepUp, idempotencyKey, decisionRevision, version);
    }

    private void requireTarget(UUID pathId, UUID bodyId, long bodyVersion, long headerVersion) {
        if (bodyId == null || !pathId.equals(bodyId)) {
            throw ConnectorRejected.invalid("Path and payload targets must match.");
        }
        requireVersion(bodyVersion, headerVersion);
    }

    private void requireVersion(long bodyVersion, long headerVersion) {
        if (bodyVersion < 0 || bodyVersion != headerVersion) {
            throw ConnectorRejected.conflict(
                    "Payload and expected-object versions must match.");
        }
    }
}
