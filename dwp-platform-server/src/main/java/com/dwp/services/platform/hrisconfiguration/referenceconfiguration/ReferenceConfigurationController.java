package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.AccessContext;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.CommandContext;

@Validated
@RestController
@ConditionalOnProperty(
        name = "dwp.hris.reference-configuration.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class ReferenceConfigurationController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String SUBJECT = "X-DWP-User-ID";
    private static final String PERMISSIONS = "X-DWP-Permissions";
    private static final String ROLES = "X-DWP-Roles";

    private final ReferenceConfigurationService service;

    public ReferenceConfigurationController(ReferenceConfigurationService service) {
        this.service = service;
    }

    @GetMapping("/v1/hris/workbench/settings-reference")
    public ApiResponse<WorkbenchResponse> workbench(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(SUBJECT) long subjectId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ROLES, defaultValue = "") String roles,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) OffsetDateTime asOf,
            @RequestParam(required = false) String policyVersion,
            @RequestParam(required = false) String referenceData,
            @RequestParam(required = false) String effectiveStatus,
            @RequestParam(required = false) LocalDate effectiveOn,
            @RequestParam(defaultValue = "itemPublicId:ASC") List<String> sort,
            @RequestParam(defaultValue = "en") String locale,
            @RequestParam(defaultValue = "UTC") String timeZone,
            @RequestHeader(value = "X-Correlation-ID", required = false) UUID correlationId,
            HttpServletResponse response) {
        noStore(response);
        WorkbenchFilter filter = new WorkbenchFilter(
                values(policyVersion), null, values(referenceData), null,
                values(effectiveStatus), null, effectiveOn);
        WorkbenchQuery query = new WorkbenchQuery(
                asOf,
                cursor,
                limit,
                filter,
                sort,
                locale,
                timeZone,
                correlationId == null ? UUID.randomUUID() : correlationId);
        return ApiResponse.success(service.workbench(
                access(tenantId, subjectId, permissions, roles), query));
    }

    @GetMapping("/v1/admin/hris/configurations")
    public ApiResponse<CatalogResponse> configurations(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(SUBJECT) long subjectId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ROLES, defaultValue = "") String roles,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.configurations(
                access(tenantId, subjectId, permissions, roles), limit));
    }

    @GetMapping("/v1/admin/hris/configurations/{configId}/versions/{version}")
    public ApiResponse<VersionResponse> version(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(SUBJECT) long subjectId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ROLES, defaultValue = "") String roles,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.version(
                access(tenantId, subjectId, permissions, roles), configId, version));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions")
    public ResponseEntity<ApiResponse<CommandReceipt>> createVersion(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(SUBJECT) long subjectId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ROLES, defaultValue = "") String roles,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) UUID correlationId,
            @RequestHeader(value = "X-Purpose-Code", defaultValue = "HRIS_CONFIGURATION") String purpose,
            @PathVariable UUID configId,
            @Valid @RequestBody CreateVersionCommand body) {
        return accepted(service.createVersion(
                access(tenantId, subjectId, permissions, roles),
                command(idempotencyKey, correlationId, purpose, null, null, null),
                configId,
                body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/validate")
    public ResponseEntity<ApiResponse<CommandReceipt>> validate(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(SUBJECT) long subjectId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ROLES, defaultValue = "") String roles,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) UUID correlationId,
            @RequestHeader(value = "X-Purpose-Code", defaultValue = "HRIS_CONFIGURATION") String purpose,
            @RequestHeader("X-DWP-Expected-Object-Version") long expectedVersion,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody ValidationCommand body) {
        return accepted(service.validate(
                access(tenantId, subjectId, permissions, roles),
                command(idempotencyKey, correlationId, purpose, expectedVersion, null, null),
                configId,
                version,
                body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/simulate")
    public ResponseEntity<ApiResponse<CommandReceipt>> simulate(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(SUBJECT) long subjectId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ROLES, defaultValue = "") String roles,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) UUID correlationId,
            @RequestHeader(value = "X-Purpose-Code", defaultValue = "HRIS_CONFIGURATION") String purpose,
            @RequestHeader("X-DWP-Expected-Object-Version") long expectedVersion,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody SimulationCommand body) {
        return accepted(service.simulate(
                access(tenantId, subjectId, permissions, roles),
                command(idempotencyKey, correlationId, purpose, expectedVersion, null, null),
                configId,
                version,
                body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/submit")
    public ResponseEntity<ApiResponse<CommandReceipt>> submit(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(SUBJECT) long subjectId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ROLES, defaultValue = "") String roles,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) UUID correlationId,
            @RequestHeader(value = "X-Purpose-Code", defaultValue = "HRIS_CONFIGURATION") String purpose,
            @RequestHeader("X-DWP-Expected-Object-Version") long expectedVersion,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody SubmitCommand body) {
        return accepted(service.submit(
                access(tenantId, subjectId, permissions, roles),
                command(idempotencyKey, correlationId, purpose, expectedVersion, null, null),
                configId,
                version,
                body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/approve")
    public ResponseEntity<ApiResponse<CommandReceipt>> approve(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(SUBJECT) long subjectId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ROLES, defaultValue = "") String roles,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) UUID correlationId,
            @RequestHeader(value = "X-Purpose-Code", defaultValue = "HRIS_CONFIGURATION") String purpose,
            @RequestHeader("X-DWP-Expected-Object-Version") long expectedVersion,
            @RequestHeader("X-DWP-Expected-Decision-Revision") String decisionRevision,
            @RequestHeader("X-DWP-Step-Up-Challenge") String stepUpChallenge,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody ApprovalCommand body) {
        return accepted(service.approve(
                access(tenantId, subjectId, permissions, roles),
                command(idempotencyKey, correlationId, purpose, expectedVersion,
                        decisionRevision, stepUpChallenge),
                configId,
                version,
                body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/schedule")
    public ResponseEntity<ApiResponse<CommandReceipt>> schedule(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(SUBJECT) long subjectId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ROLES, defaultValue = "") String roles,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) UUID correlationId,
            @RequestHeader(value = "X-Purpose-Code", defaultValue = "HRIS_CONFIGURATION") String purpose,
            @RequestHeader("X-DWP-Expected-Object-Version") long expectedVersion,
            @RequestHeader("X-DWP-Expected-Decision-Revision") String decisionRevision,
            @RequestHeader("X-DWP-Step-Up-Challenge") String stepUpChallenge,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody ScheduleCommand body) {
        return accepted(service.schedule(
                access(tenantId, subjectId, permissions, roles),
                command(idempotencyKey, correlationId, purpose, expectedVersion,
                        decisionRevision, stepUpChallenge),
                configId,
                version,
                body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/publish")
    public ResponseEntity<ApiResponse<CommandReceipt>> publish(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(SUBJECT) long subjectId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ROLES, defaultValue = "") String roles,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) UUID correlationId,
            @RequestHeader(value = "X-Purpose-Code", defaultValue = "HRIS_CONFIGURATION") String purpose,
            @RequestHeader("X-DWP-Expected-Object-Version") long expectedVersion,
            @RequestHeader("X-DWP-Expected-Decision-Revision") String decisionRevision,
            @RequestHeader("X-DWP-Step-Up-Challenge") String stepUpChallenge,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody PublishCommand body) {
        return accepted(service.publish(
                access(tenantId, subjectId, permissions, roles),
                command(idempotencyKey, correlationId, purpose, expectedVersion,
                        decisionRevision, stepUpChallenge),
                configId,
                version,
                body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/rollback")
    public ResponseEntity<ApiResponse<CommandReceipt>> rollback(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(SUBJECT) long subjectId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ROLES, defaultValue = "") String roles,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) UUID correlationId,
            @RequestHeader(value = "X-Purpose-Code", defaultValue = "HRIS_CONFIGURATION") String purpose,
            @RequestHeader("X-DWP-Expected-Object-Version") long expectedVersion,
            @RequestHeader("X-DWP-Expected-Decision-Revision") String decisionRevision,
            @RequestHeader("X-DWP-Step-Up-Challenge") String stepUpChallenge,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody RollbackCommand body) {
        return accepted(service.rollback(
                access(tenantId, subjectId, permissions, roles),
                command(idempotencyKey, correlationId, purpose, expectedVersion,
                        decisionRevision, stepUpChallenge),
                configId,
                version,
                body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/retire")
    public ResponseEntity<ApiResponse<CommandReceipt>> retire(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(SUBJECT) long subjectId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = ROLES, defaultValue = "") String roles,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) UUID correlationId,
            @RequestHeader(value = "X-Purpose-Code", defaultValue = "HRIS_CONFIGURATION") String purpose,
            @RequestHeader("X-DWP-Expected-Object-Version") long expectedVersion,
            @RequestHeader("X-DWP-Expected-Decision-Revision") String decisionRevision,
            @RequestHeader("X-DWP-Step-Up-Challenge") String stepUpChallenge,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody RetireCommand body) {
        return accepted(service.retire(
                access(tenantId, subjectId, permissions, roles),
                command(idempotencyKey, correlationId, purpose, expectedVersion,
                        decisionRevision, stepUpChallenge),
                configId,
                version,
                body));
    }

    private AccessContext access(long tenantId, long subjectId, String permissions, String roles) {
        return new AccessContext(tenantId, subjectId, permissions, roles);
    }

    private CommandContext command(
            String idempotencyKey,
            UUID correlationId,
            String purpose,
            Long expectedVersion,
            String decisionRevision,
            String stepUpChallenge) {
        return new CommandContext(
                idempotencyKey,
                correlationId == null ? UUID.randomUUID() : correlationId,
                purpose,
                expectedVersion,
                decisionRevision,
                stepUpChallenge);
    }

    private ResponseEntity<ApiResponse<CommandReceipt>> accepted(CommandReceipt receipt) {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(receipt));
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
    }

    private List<String> values(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",", -1)).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
}
