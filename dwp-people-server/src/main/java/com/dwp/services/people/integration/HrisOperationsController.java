package com.dwp.services.people.integration;

import com.dwp.core.common.ApiResponse;
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

    public HrisOperationsController(HrisImportService service) {
        this.service = service;
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
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {
        return ApiResponse.success(service.checkConnectorConfiguration(connectorId, correlationId));
    }

    @GetMapping("/mapping-profiles")
    public ApiResponse<List<HrisDtos.MappingProfile>> mappings() {
        return ApiResponse.success(service.mappings());
    }

    @GetMapping("/sync-runs")
    public ApiResponse<List<HrisDtos.SyncRun>> runs(
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.runs(size));
    }

    @PostMapping("/sample-import")
    public ApiResponse<HrisDtos.ImportResult> importSample(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {
        return ApiResponse.success(service.importSyntheticWorkdayFixture(
                idempotencyKey, correlationId));
    }
}
