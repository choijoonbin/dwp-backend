package com.dwp.services.platform.activity;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/v1/workspace/activity")
@ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Authorized observation"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Trusted tenant and user identity is required", content = @io.swagger.v3.oas.annotations.media.Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Personal Activity permission is required", content = @io.swagger.v3.oas.annotations.media.Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Evidence is unavailable or its source is no longer accessible", content = @io.swagger.v3.oas.annotations.media.Content)})
public class ActivityEvidenceController {
    private final ActivityEvidenceService service;

    public ActivityEvidenceController(ActivityEvidenceService service) { this.service = service; }

    @GetMapping("/events/{id}/evidence")
    @Operation(summary = "Read an authorized native activity audit receipt", description = "Rechecks current source ACL. Audit hash and checkpoint require ADMIN.AUDIT_VIEW:VIEW; other viewers receive RESTRICTED without audit data.")
    public ResponseEntity<ApiResponse<ActivityEvidenceDtos.Evidence>> evidence(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant, @RequestHeader("X-DWP-User-ID") Long user,
            @RequestHeader("X-DWP-Permissions") String permissions, @PathVariable UUID id) {
        return noStore(service.evidence(tenant, user, permissions, id));
    }

    @GetMapping("/sources/status")
    @Operation(summary = "Read personal source synchronization observations", description = "Requires APP.ACTIVITY:VIEW and the source's APP.MAIL:VIEW or APP.CALENDAR:VIEW. Empty coverage is not a healthy-rate assertion.")
    public ResponseEntity<ApiResponse<ActivityEvidenceDtos.SourceStatuses>> sources(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant, @RequestHeader("X-DWP-User-ID") Long user,
            @RequestHeader("X-DWP-Permissions") String permissions) {
        return noStore(service.sources(tenant, user, permissions));
    }

    @GetMapping("/audit/evidence/{auditRecordId}")
    @Operation(summary = "Read a self-owned Agent audit receipt", description = "Requires APP.ACTIVITY:VIEW and APP.ASK:VIEW plus exact tenant, actor, dwp-agent-runtime source, and AGENT_RUN target. Missing, pending ingestion and unreadable records are indistinguishable 404 responses.")
    public ResponseEntity<ApiResponse<ActivityEvidenceDtos.Evidence>> agentEvidence(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant, @RequestHeader("X-DWP-User-ID") Long user,
            @RequestHeader("X-DWP-Permissions") String permissions, @PathVariable UUID auditRecordId) {
        return noStore(service.agentEvidence(tenant, user, permissions, auditRecordId));
    }

    private <T> ResponseEntity<ApiResponse<T>> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(ApiResponse.success(value));
    }
}
