package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.repository.FiDocumentScopeRepository;
import com.dwp.services.synapsex.scope.TenantScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * FI Document scope-filtered 조회 (Scope Enforcement 예시).
 * TenantScopeResolver 적용: scope 비어있으면 빈 결과.
 */
@RestController
@RequestMapping("/synapse/entities")
@RequiredArgsConstructor
public class FiDocumentScopeController {

    private final TenantScopeResolver scopeResolver;
    private final FiDocumentScopeRepository fiScopeRepo;

    /**
     * GET /api/synapse/entities/fi-doc-headers (scope 적용)
     */
    @GetMapping("/fi-doc-headers")
    public ApiResponse<List<Map<String, Object>>> getDocHeaders(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(defaultValue = "100") int limit) {
        Set<String> enabledBukrs = scopeResolver.resolveEnabledBukrs(tenantId);
        List<Object[]> rows = fiScopeRepo.findDocHeadersByScope(tenantId, enabledBukrs, limit);
        List<Map<String, Object>> result = rows.stream()
                .map(r -> Map.of(
                        "tenantId", r[0],
                        "bukrs", r[1],
                        "belnr", r[2],
                        "gjahr", r[3],
                        "budat", r[4] != null ? r[4].toString() : "",
                        "waers", r[5] != null ? r[5].toString() : "",
                        "xblnr", r[6] != null ? r[6].toString() : "",
                        "statusCode", r[7] != null ? r[7].toString() : ""))
                .collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    /**
     * GET /api/synapse/entities/fi-open-items (scope 적용)
     */
    @GetMapping("/fi-open-items")
    public ApiResponse<List<Map<String, Object>>> getOpenItems(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(defaultValue = "100") int limit) {
        Set<String> enabledBukrs = scopeResolver.resolveEnabledBukrs(tenantId);
        Set<String> enabledWaers = scopeResolver.resolveEnabledWaers(tenantId);
        List<Object[]> rows = fiScopeRepo.findOpenItemsByScope(tenantId, enabledBukrs, enabledWaers, limit);
        List<Map<String, Object>> result = rows.stream()
                .map(r -> Map.of(
                        "tenantId", r[0],
                        "bukrs", r[1],
                        "belnr", r[2],
                        "gjahr", r[3],
                        "buzei", r[4],
                        "itemType", r[5] != null ? r[5].toString() : "",
                        "openAmount", r[6] != null ? r[6] : 0,
                        "currency", r[7] != null ? r[7].toString() : "",
                        "dueDate", r[8] != null ? r[8].toString() : ""))
                .collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    /**
     * GET /api/synapse/entities/cases (scope 적용: tenant_id + bukrs IN scope)
     */
    @GetMapping("/cases")
    public ApiResponse<List<Map<String, Object>>> getCases(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(defaultValue = "100") int limit) {
        Set<String> enabledBukrs = scopeResolver.resolveEnabledBukrs(tenantId);
        List<Object[]> rows = fiScopeRepo.findCasesByScope(tenantId, enabledBukrs, limit);
        List<Map<String, Object>> result = rows.stream()
                .map(r -> Map.<String, Object>of(
                        "caseId", r[0],
                        "tenantId", r[1],
                        "bukrs", r[2] != null ? r[2].toString() : "",
                        "belnr", r[3] != null ? r[3].toString() : "",
                        "gjahr", r[4] != null ? r[4].toString() : "",
                        "buzei", r[5] != null ? r[5].toString() : "",
                        "caseType", r[6] != null ? r[6].toString() : "",
                        "severity", r[7] != null ? r[7].toString() : "",
                        "status", r[8] != null ? r[8].toString() : "",
                        "detectedAt", r[9] != null ? r[9].toString() : ""))
                .collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    /**
     * GET /api/synapse/entities/actions (scope 적용: case 조인, case bukrs IN scope)
     */
    @GetMapping("/actions")
    public ApiResponse<List<Map<String, Object>>> getActions(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(defaultValue = "100") int limit) {
        Set<String> enabledBukrs = scopeResolver.resolveEnabledBukrs(tenantId);
        List<Object[]> rows = fiScopeRepo.findActionsByScope(tenantId, enabledBukrs, limit);
        List<Map<String, Object>> result = rows.stream()
                .map(r -> Map.<String, Object>of(
                        "actionId", r[0],
                        "tenantId", r[1],
                        "caseId", r[2],
                        "actionType", r[3] != null ? r[3].toString() : "",
                        "status", r[4] != null ? r[4].toString() : "",
                        "plannedAt", r[5] != null ? r[5].toString() : "",
                        "bukrs", r[6] != null ? r[6].toString() : ""))
                .collect(Collectors.toList());
        return ApiResponse.success(result);
    }
}
