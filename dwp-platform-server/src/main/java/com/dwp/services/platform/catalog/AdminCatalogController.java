package com.dwp.services.platform.catalog;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/v1/admin/catalog")
public class AdminCatalogController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final CatalogService service;

    public AdminCatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<CatalogDtos.Overview> overview(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String lifecycle) {
        return ApiResponse.success(service.overview(tenantId, query, kind, lifecycle));
    }

    @GetMapping("/graph")
    public ApiResponse<CatalogDtos.Graph> graph(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestParam(required = false) String focusRef,
            @RequestParam(defaultValue = "2") @Min(1) @Max(4) Integer depth) {
        return ApiResponse.success(service.graph(tenantId, focusRef, depth));
    }

    @GetMapping("/impact")
    public ApiResponse<CatalogDtos.ImpactAnalysis> impact(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestParam String ref,
            @RequestParam(defaultValue = "CHANGE") String operation) {
        return ApiResponse.success(service.impact(tenantId, ref, operation));
    }

    @GetMapping("/assurance")
    public ApiResponse<CatalogDtos.AssuranceSummary> assurance(
            @RequestHeader(TENANT_HEADER) Long tenantId) {
        return ApiResponse.success(service.assurance(tenantId));
    }

    @PostMapping("/assurance/evaluate")
    public ApiResponse<CatalogDtos.AssuranceSummary> evaluateAssurance(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {
        return ApiResponse.success(service.evaluateAssurance(tenantId, actorId, correlationId));
    }

    @PostMapping("/assurance/findings/{findingId}/disposition")
    public ApiResponse<CatalogDtos.AssuranceFinding> dispositionFinding(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID findingId,
            @Valid @RequestBody CatalogDtos.DispositionFindingRequest request) {
        return ApiResponse.success(service.dispositionFinding(
                tenantId, actorId, correlationId, findingId, request));
    }

    @PostMapping("/relations")
    public ApiResponse<CatalogDtos.Relation> declare(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody CatalogDtos.DeclareRelationRequest request) {
        return ApiResponse.success(service.declare(tenantId, actorId, correlationId, request));
    }

    @PostMapping("/relations/{relationId}/retire")
    public ApiResponse<CatalogDtos.Relation> retire(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID relationId,
            @Valid @RequestBody CatalogDtos.RelationVersionRequest request) {
        return ApiResponse.success(service.retire(
                tenantId, actorId, correlationId, relationId, request.version()));
    }
}
