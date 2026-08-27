package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/mail/organization")
public class MailOrganizationController {

    private final MailOrganizationService service;

    public MailOrganizationController(MailOrganizationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<MailOrganizationDtos.OrganizationResponse> organization(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId) {
        return ApiResponse.success(service.organization(tenantId, userId));
    }

    @PostMapping("/folders")
    public ApiResponse<MailOrganizationDtos.FolderSummary> createFolder(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody MailOrganizationDtos.FolderCreateRequest request) {
        return ApiResponse.success(
                service.createFolder(tenantId, userId, correlationId, request));
    }

    @PutMapping("/folders/{folderId}")
    public ApiResponse<MailOrganizationDtos.FolderSummary> updateFolder(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID folderId,
            @Valid @RequestBody MailOrganizationDtos.FolderUpdateRequest request) {
        return ApiResponse.success(
                service.updateFolder(tenantId, userId, folderId, correlationId, request));
    }

    @PostMapping("/folders/{folderId}/archive")
    public ApiResponse<Void> archiveFolder(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID folderId,
            @Valid @RequestBody MailOrganizationDtos.VersionRequest request) {
        service.archiveFolder(tenantId, userId, folderId, correlationId, request);
        return ApiResponse.success(null);
    }

    @PostMapping("/rules")
    public ApiResponse<MailOrganizationDtos.RuleSummary> createRule(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody MailOrganizationDtos.RuleCreateRequest request) {
        return ApiResponse.success(
                service.createRule(tenantId, userId, correlationId, request));
    }

    @PutMapping("/rules/{ruleId}")
    public ApiResponse<MailOrganizationDtos.RuleSummary> updateRule(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID ruleId,
            @Valid @RequestBody MailOrganizationDtos.RuleUpdateRequest request) {
        return ApiResponse.success(
                service.updateRule(tenantId, userId, ruleId, correlationId, request));
    }

    @PostMapping("/rules/{ruleId}/archive")
    public ApiResponse<Void> archiveRule(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID ruleId,
            @Valid @RequestBody MailOrganizationDtos.VersionRequest request) {
        service.archiveRule(tenantId, userId, ruleId, correlationId, request);
        return ApiResponse.success(null);
    }

    @PostMapping("/rules/{ruleId}/run")
    public ApiResponse<MailOrganizationDtos.RuleRunSummary> runRule(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID ruleId) {
        return ApiResponse.success(service.runRule(tenantId, userId, ruleId, correlationId));
    }
}
