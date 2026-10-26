package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** Closed default-off adapter for the exact twenty SYS-008 service operations. */
@RestController
@RequestMapping("/v1")
@ConditionalOnProperty(
        name = "dwp.hris.integration-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class IntegrationController {

    private static final Set<String> CATALOG_QUERY = Set.of(
            "asOf", "fields", "freshness", "limit", "cursor", "filter");
    private static final Set<String> COMMON_PROJECTION_QUERY = Set.of(
            "asOf", "sort", "limit", "cursor");

    private final IntegrationService service;
    private final IntegrationRuntimeService runtime;
    private final IntegrationOwnerQueryService ownerQuery;

    IntegrationController(
            IntegrationService service,
            IntegrationRuntimeService runtime,
            IntegrationOwnerQueryService ownerQuery) {
        this.service = service;
        this.runtime = runtime;
        this.ownerQuery = ownerQuery;
    }

    @GetMapping("/hris/workbench/settings-integrations")
    ResponseEntity<ApiResponse<ProjectionResponse>> settingsProjection(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant asOf,
            @RequestParam(defaultValue = DEFAULT_SORT) String sort,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest request) {
        return projection(Projection.PRIMARY_HRIS_SETTINGS, asOf, sort, limit, cursor, request);
    }

    @GetMapping("/hris/workbench/dwp-integrations")
    ResponseEntity<ApiResponse<ProjectionResponse>> dwpProjection(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant asOf,
            @RequestParam(defaultValue = DEFAULT_SORT) String sort,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest request) {
        return projection(Projection.PRIMARY_DWP_CONTROL, asOf, sort, limit, cursor, request);
    }

    @GetMapping("/admin/hris/connectors")
    ResponseEntity<ApiResponse<CatalogResponse>> catalog(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime asOf,
            @RequestParam List<String> fields,
            @RequestParam String freshness,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String filter,
            HttpServletRequest request) {
        requireQueryKeys(request, CATALOG_QUERY);
        validateCatalogContract(fields, freshness, filter);
        return ok(service.catalog(authority(request), asOf, fields, freshness,
                limit, cursor, filter));
    }

    @PostMapping("/admin/hris/connectors")
    ResponseEntity<ApiResponse<CommandReceipt>> createConnector(
            @Valid @RequestBody ConnectorCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.createConnector(authority(request), command));
    }

    @PostMapping("/admin/hris/connectors/{connectorId}/test")
    ResponseEntity<ApiResponse<CommandReceipt>> testConnector(
            @PathVariable UUID connectorId,
            @Valid @RequestBody ConnectorTestCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.testConnector(authority(request), connectorId, command));
    }

    @PostMapping("/admin/hris/connectors/{connectorId}/mappings/{version}/dry-runs")
    ResponseEntity<ApiResponse<CommandReceipt>> dryRunMapping(
            @PathVariable UUID connectorId,
            @PathVariable long version,
            @Valid @RequestBody MappingDryRunCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.dryRunMapping(
                authority(request), connectorId, positiveVersion(version), command));
    }

    @PostMapping("/admin/hris/connectors/{connectorId}/executions")
    ResponseEntity<ApiResponse<CommandReceipt>> createExecution(
            @PathVariable UUID connectorId,
            @Valid @RequestBody ExecutionCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.createExecution(authority(request), connectorId, command));
    }

    @GetMapping("/admin/hris/connector-executions/{executionId}")
    ResponseEntity<ApiResponse<ExecutionResponse>> execution(
            @PathVariable UUID executionId,
            HttpServletRequest request) {
        requireNoQuery(request);
        return ok(service.execution(authority(request), executionId));
    }

    @PostMapping("/admin/hris/connectors/{connectorId}/validate")
    ResponseEntity<ApiResponse<CommandReceipt>> validateDefinition(
            @PathVariable UUID connectorId,
            @Valid @RequestBody DefinitionValidationCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.validateDefinition(
                authority(request), connectorId, command));
    }

    @PostMapping("/admin/hris/connectors/{connectorId}/submit")
    ResponseEntity<ApiResponse<CommandReceipt>> submitDefinition(
            @PathVariable UUID connectorId,
            @Valid @RequestBody ApprovalRequestCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.submitDefinition(
                authority(request), connectorId, command));
    }

    @PostMapping("/admin/hris/connectors/{connectorId}/approve")
    ResponseEntity<ApiResponse<CommandReceipt>> approveDefinition(
            @PathVariable UUID connectorId,
            @Valid @RequestBody ApprovalDecisionCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.approveDefinition(
                authority(request), connectorId, command));
    }

    @PostMapping("/admin/hris/connectors/{connectorId}/activate")
    ResponseEntity<ApiResponse<CommandReceipt>> activateDefinition(
            @PathVariable UUID connectorId,
            @Valid @RequestBody ActivationCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.activateDefinition(
                authority(request), connectorId, command));
    }

    @PostMapping("/admin/hris/connectors/{connectorId}/mappings")
    ResponseEntity<ApiResponse<CommandReceipt>> createMapping(
            @PathVariable UUID connectorId,
            @Valid @RequestBody MappingCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.createMapping(authority(request), connectorId, command));
    }

    @PostMapping("/admin/hris/connectors/{connectorId}/mappings/{version}/validate")
    ResponseEntity<ApiResponse<CommandReceipt>> validateMapping(
            @PathVariable UUID connectorId,
            @PathVariable long version,
            @Valid @RequestBody MappingValidationCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.validateMapping(authority(request), connectorId,
                positiveVersion(version), command));
    }

    @PostMapping("/admin/hris/connectors/{connectorId}/mappings/{version}/submit")
    ResponseEntity<ApiResponse<CommandReceipt>> submitMapping(
            @PathVariable UUID connectorId,
            @PathVariable long version,
            @Valid @RequestBody ApprovalRequestCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.submitMapping(authority(request), connectorId,
                positiveVersion(version), command));
    }

    @PostMapping("/admin/hris/connectors/{connectorId}/mappings/{version}/approve")
    ResponseEntity<ApiResponse<CommandReceipt>> approveMapping(
            @PathVariable UUID connectorId,
            @PathVariable long version,
            @Valid @RequestBody ApprovalDecisionCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.approveMapping(authority(request), connectorId,
                positiveVersion(version), command));
    }

    @PostMapping("/admin/hris/connectors/{connectorId}/mappings/{version}/activate")
    ResponseEntity<ApiResponse<CommandReceipt>> activateMapping(
            @PathVariable UUID connectorId,
            @PathVariable long version,
            @Valid @RequestBody ActivationCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(service.activateMapping(authority(request), connectorId,
                positiveVersion(version), command));
    }

    @PostMapping("/admin/hris/connector-executions/{executionId}/reconcile")
    ResponseEntity<ApiResponse<CommandReceipt>> reconcile(
            @PathVariable UUID executionId,
            @Valid @RequestBody ReconcileCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(runtime.reconcile(authority(request), executionId, command));
    }

    @PostMapping("/admin/hris/connector-executions/{executionId}/retry")
    ResponseEntity<ApiResponse<CommandReceipt>> retry(
            @PathVariable UUID executionId,
            @Valid @RequestBody RetryCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(runtime.retry(authority(request), executionId, command));
    }

    @PostMapping("/admin/hris/connector-executions/{executionId}/cancel")
    ResponseEntity<ApiResponse<CommandReceipt>> cancel(
            @PathVariable UUID executionId,
            @Valid @RequestBody CancelCommand command,
            HttpServletRequest request) {
        requireNoQuery(request);
        return accepted(runtime.cancel(authority(request), executionId, command));
    }

    private ResponseEntity<ApiResponse<ProjectionResponse>> projection(
            Projection projection,
            Instant asOf,
            String sort,
            int limit,
            String cursor,
            HttpServletRequest request) {
        ProjectionSpec spec = PROJECTIONS.get(projection);
        Set<String> allowed = new java.util.HashSet<>(COMMON_PROJECTION_QUERY);
        allowed.addAll(spec.filters());
        requireQueryKeys(request, allowed);
        Map<String, String> filters = new LinkedHashMap<>();
        for (String name : spec.filters()) {
            String value = request.getParameter(name);
            if (value != null) {
                if (request.getParameterValues(name).length != 1
                        || value.isBlank() || value.length() > 1000
                        || !value.equals(value.strip())) {
                    throw invalid("SYS008_PROJECTION_FILTER_INVALID");
                }
                filters.put(name, value);
            }
        }
        String locale = request.getLocale() == null
                ? "en" : request.getLocale().toLanguageTag();
        String timeZone = headerOrDefault(request, "X-DWP-Time-Zone", "UTC");
        IntegrationOwnerQueryService.ProjectionPage page = ownerQuery.queryPage(
                authority(request), projection, filters, sort, asOf, locale,
                timeZone, limit, cursor);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore());
        if (page.nextCursor() != null) {
            response.header("X-DWP-Next-Cursor", page.nextCursor());
        }
        return response.body(ApiResponse.success(page.response()));
    }

    private static void validateCatalogContract(
            List<String> fields, String freshness, String filter) {
        Set<String> supported = Set.of(
                "publicId", "key", "type", "state", "version", "updatedAt");
        if (fields == null || fields.isEmpty()
                || fields.stream().distinct().count() != fields.size()
                || !Set.copyOf(fields).equals(supported)
                || !Set.of("FRESH_ONLY", "ALLOW_STALE").contains(freshness)
                || filter != null && (filter.isBlank() || filter.length() > 1000)) {
            throw invalid("SYS008_CATALOG_QUERY_INVALID");
        }
    }

    private static String headerOrDefault(
            HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        if (value == null) return fallback;
        if (value.isBlank() || value.length() > 80
                || !value.equals(value.strip())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw invalid("SYS008_QUERY_CONTEXT_INVALID");
        }
        return value;
    }

    private static Authority authority(HttpServletRequest request) {
        Object value = request.getAttribute(
                IntegrationOwnerAuthorityFilter.AUTHORITY_ATTRIBUTE);
        if (!(value instanceof Authority authority)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "SYS008_OWNER_AUTHORITY_REQUIRED");
        }
        return authority;
    }

    private static long positiveVersion(long version) {
        if (version < 1 || version > Sys008TypedArtifactContract.MAX_JSON_SAFE_INTEGER) {
            throw invalid("SYS008_MAPPING_VERSION_INVALID");
        }
        return version;
    }

    private static void requireNoQuery(HttpServletRequest request) {
        requireQueryKeys(request, Set.of());
    }

    private static void requireQueryKeys(
            HttpServletRequest request, Set<String> allowed) {
        List<String> names = Collections.list(request.getParameterNames());
        if (!allowed.containsAll(names)) {
            throw invalid("SYS008_UNKNOWN_QUERY_PARAMETER");
        }
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(value));
    }

    private static <T> ResponseEntity<ApiResponse<T>> accepted(T value) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(value));
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
