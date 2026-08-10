package com.dwp.services.platform.auditcontrol;

import com.dwp.core.common.ApiResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/audit-control")
public class AdminAuditControlController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String PERMISSIONS = "X-DWP-Permissions";

    private final AuditControlService service;
    private final AuditAccessGuard guard;

    public AdminAuditControlController(AuditControlService service, AuditAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/overview")
    public ApiResponse<AuditControlDtos.Overview> overview(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(defaultValue = "D7") AuditWindow window) {
        guard.view(permissions);
        return ApiResponse.success(service.overview(criteria(
                tenantId, window, "ALL", "ALL", "ALL", null, null, null)));
    }

    @GetMapping("/events")
    public ApiResponse<AuditControlDtos.EventPage> events(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(defaultValue = "D7") AuditWindow window,
            @RequestParam(defaultValue = "ALL") String category,
            @RequestParam(defaultValue = "ALL") String severity,
            @RequestParam(defaultValue = "ALL") String outcome,
            @RequestParam(required = false) String sourceService,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        guard.view(permissions);
        return ApiResponse.success(service.events(criteria(
                tenantId, window, category, severity, outcome, sourceService, actor, query), page, size));
    }

    @GetMapping("/events/{eventId}")
    public ApiResponse<AuditControlDtos.Event> event(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID eventId) {
        guard.view(permissions);
        return ApiResponse.success(service.event(tenantId, eventId));
    }

    @GetMapping("/saved-searches")
    public ApiResponse<List<AuditControlDtos.SavedSearch>> savedSearches(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) String actorId,
            @RequestHeader(PERMISSIONS) String permissions) {
        guard.view(permissions);
        return ApiResponse.success(service.savedSearches(tenantId, actorId));
    }

    @PostMapping("/saved-searches")
    public ApiResponse<AuditControlDtos.SavedSearch> saveSearch(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) String actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestBody AuditControlDtos.SavedSearchRequest request) {
        if (request.shared()) guard.configure(permissions);
        else guard.view(permissions);
        return ApiResponse.success(service.saveSearch(tenantId, actorId, request));
    }

    @DeleteMapping("/saved-searches/{savedSearchId}")
    public ApiResponse<Void> deleteSavedSearch(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) String actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID savedSearchId) {
        guard.view(permissions);
        service.deleteSavedSearch(tenantId, actorId, savedSearchId);
        return ApiResponse.success(null);
    }

    @GetMapping("/findings")
    public ApiResponse<List<AuditControlDtos.Finding>> findings(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(defaultValue = "ALL") String status) {
        guard.investigate(permissions);
        return ApiResponse.success(service.findings(tenantId, status));
    }

    @PatchMapping("/findings/{findingId}")
    public ApiResponse<AuditControlDtos.Finding> updateFinding(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) String actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID findingId,
            @RequestBody AuditControlDtos.FindingUpdate request) {
        guard.investigate(permissions);
        return ApiResponse.success(service.updateFinding(tenantId, actorId, findingId, request));
    }

    @GetMapping("/cases")
    public ApiResponse<List<AuditControlDtos.AuditCase>> cases(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions) {
        guard.investigate(permissions);
        return ApiResponse.success(service.cases(tenantId));
    }

    @PostMapping("/cases")
    public ApiResponse<AuditControlDtos.AuditCase> createCase(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) String actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestBody AuditControlDtos.CaseCreate request) {
        guard.investigate(permissions);
        return ApiResponse.success(service.createCase(tenantId, actorId, request));
    }

    @PatchMapping("/cases/{caseId}")
    public ApiResponse<AuditControlDtos.AuditCase> updateCase(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) String actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID caseId,
            @RequestBody AuditControlDtos.CaseUpdate request) {
        guard.investigate(permissions);
        return ApiResponse.success(service.updateCase(tenantId, actorId, caseId, request));
    }

    @PostMapping("/cases/{caseId}/events")
    public ApiResponse<AuditControlDtos.AuditCase> linkEvent(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) String actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID caseId,
            @RequestBody AuditControlDtos.CaseEventLink request) {
        guard.investigate(permissions);
        return ApiResponse.success(service.linkEvent(tenantId, actorId, caseId, request));
    }

    @GetMapping("/policy")
    public ApiResponse<AuditControlDtos.RetentionPolicy> policy(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions) {
        guard.configure(permissions);
        return ApiResponse.success(service.policy(tenantId));
    }

    @PutMapping("/policy")
    public ApiResponse<AuditControlDtos.RetentionPolicy> updatePolicy(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) String actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestBody AuditControlDtos.RetentionPolicyUpdate request) {
        guard.configure(permissions);
        return ApiResponse.success(service.updatePolicy(tenantId, actorId, request));
    }

    @GetMapping("/integrity")
    public ApiResponse<List<AuditControlDtos.IntegrityCheckpoint>> integrity(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions) {
        guard.configure(permissions);
        return ApiResponse.success(service.integrity(tenantId));
    }

    @PostMapping("/integrity/checkpoint")
    public ApiResponse<List<AuditControlDtos.IntegrityCheckpoint>> checkpoint(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) String actorId,
            @RequestHeader(PERMISSIONS) String permissions) {
        guard.configure(permissions);
        return ApiResponse.success(service.checkpoint(tenantId, actorId));
    }

    @PostMapping("/exports")
    public ApiResponse<AuditControlDtos.ExportJob> export(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) String actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestBody AuditControlDtos.ExportRequest request) {
        guard.export(permissions);
        return ApiResponse.success(service.export(tenantId, actorId, request));
    }

    @GetMapping("/exports/{exportId}/content")
    public ResponseEntity<byte[]> exportContent(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID exportId) {
        guard.export(permissions);
        AuditControlService.ExportArtifact artifact = service.exportContent(tenantId, exportId);
        boolean jsonLines = "JSONL".equals(artifact.format());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        jsonLines ? "application/x-ndjson" : "text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("dwp-audit-" + exportId + (jsonLines ? ".jsonl" : ".csv"),
                                StandardCharsets.UTF_8)
                        .build().toString())
                .body(artifact.content());
    }

    private AuditCriteria criteria(
            Long tenantId, AuditWindow window, String category, String severity, String outcome,
            String sourceService, String actor, String query) {
        return AuditCriteria.of(
                tenantId, window, category, severity, outcome, sourceService, actor, query, Instant.now());
    }
}
