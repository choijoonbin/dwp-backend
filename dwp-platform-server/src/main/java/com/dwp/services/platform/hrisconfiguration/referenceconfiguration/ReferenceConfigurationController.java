package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.AccessContext;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.CommandContext;

@Validated
@RestController
@RequestMapping
@ConditionalOnProperty(
        name = "dwp.hris.reference-configuration.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class ReferenceConfigurationController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String SUBJECT = "X-DWP-User-ID";
    private static final String SUBJECT_PUBLIC_ID = "X-DWP-Subject-Principal-Public-ID";
    private static final String PERMISSIONS = "X-DWP-Permissions";
    private static final String ROLES = "X-DWP-Roles";
    private static final String CONTEXT_SCOPE = "X-DWP-Context-Scope-Key";
    private static final String CONTEXT = "X-DWP-Context-Key";
    private static final String SCOPE_TYPE = "X-DWP-Reference-Configuration-Scope-Type";
    private static final String SCOPE_ID = "X-DWP-Reference-Configuration-Scope-ID";
    private static final String SCOPE_DIGEST = "X-DWP-Population-Scope-Digest";
    private static final String DECISION_REVISION = "X-DWP-Current-Decision-Revision";
    private static final String REVALIDATE_AT = "X-DWP-Current-Revalidate-At";
    private static final String FIELD_POLICY_REVISION = "X-DWP-Field-Policy-Revision";
    private static final String AUTHORIZATION_REVISION = "X-DWP-Authorization-Revision";
    private static final String MASKED_GROUPS = "X-DWP-Masked-Field-Groups";
    private static final String OMITTED_GROUPS = "X-DWP-Omitted-Field-Groups";
    private static final String PURPOSE = "X-Purpose-Code";
    private static final String CORRELATION = "X-Correlation-ID";
    private static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String EXPECTED_OBJECT_VERSION = "X-DWP-Expected-Object-Version";
    private static final String EXPECTED_DECISION_REVISION = "X-DWP-Expected-Decision-Revision";
    private static final String STEP_UP = "X-DWP-Step-Up-Challenge";

    private final ReferenceConfigurationService service;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public ReferenceConfigurationController(ReferenceConfigurationService service, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/v1/hris/workbench/settings-reference")
    public ApiResponse<WorkbenchResponse> workbench(
            HttpServletRequest request,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) OffsetDateTime asOf,
            @RequestParam(name = "policyVersion.policyKeys", required = false) String policyKeys,
            @RequestParam(name = "policyVersion.minimumRevision", required = false) Long minimumPolicyRevision,
            @RequestParam(name = "referenceData.publicIds", required = false) String referencePublicIds,
            @RequestParam(name = "referenceData.codes", required = false) String referenceCodes,
            @RequestParam(name = "effectiveStatus.codes", required = false) String effectiveStatuses,
            @RequestParam(name = "effectiveStatus.minimumRevision", required = false) Long minimumStatusRevision,
            @RequestParam(required = false) LocalDate effectiveOn,
            @RequestParam(defaultValue = "itemPublicId:ASC") List<String> sort,
            @RequestParam(defaultValue = "POLICY_VERSION,REFERENCE_DATA,EFFECTIVE_STATUS") String fields,
            @RequestParam(defaultValue = "FRESH_ONLY") FreshnessPreference freshness,
            @RequestParam(defaultValue = "en") String locale,
            @RequestParam(defaultValue = "UTC") String timeZone,
            HttpServletResponse response) {
        noStore(response);
        WorkbenchFilter filter = new WorkbenchFilter(
                values(policyKeys), minimumPolicyRevision,
                values(referencePublicIds), values(referenceCodes),
                values(effectiveStatuses), minimumStatusRevision, effectiveOn);
        WorkbenchQuery query = new WorkbenchQuery(
                asOf, cursor, limit, filter, sort, locale, timeZone,
                uuidOrRandom(single(request, CORRELATION, false)),
                values(fields), freshness);
        String purpose = single(request, PURPOSE, true);
        return ApiResponse.success(service.workbench(access(request, purpose), query));
    }

    @GetMapping("/v1/admin/hris/configurations")
    public ApiResponse<CatalogResponse> configurations(
            HttpServletRequest request,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) OffsetDateTime asOf,
            @RequestParam(required = false) String filter,
            @RequestParam(defaultValue = "publicId,key,type,state,version,updatedAt") String fields,
            @RequestParam(defaultValue = "FRESH_ONLY") FreshnessPreference freshness,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            HttpServletResponse response) {
        noStore(response);
        String purpose = single(request, PURPOSE, true);
        return ApiResponse.success(service.configurations(
                access(request, purpose),
                new CatalogQuery(asOf, cursor, filter, values(fields), freshness, limit)));
    }

    @GetMapping("/v1/admin/hris/configurations/{configId}/versions/{version}")
    public ApiResponse<VersionResponse> version(
            HttpServletRequest request,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            HttpServletResponse response) {
        noStore(response);
        String purpose = single(request, PURPOSE, true);
        return ApiResponse.success(service.version(access(request, purpose), configId, version));
    }

    @PostMapping(path = "/v1/admin/hris/configurations/{configId}/versions", consumes = "application/json")
    public ResponseEntity<ApiResponse<CommandReceipt>> createVersion(
            HttpServletRequest request,
            @PathVariable UUID configId) {
        CreateVersionCommand body = Per004CreateCommandReader.read(request, mapper);
        String purpose = single(request, PURPOSE, true);
        return accepted(service.createVersion(
                access(request, purpose), command(request, purpose, false, false), configId, body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/validate")
    public ResponseEntity<ApiResponse<CommandReceipt>> validate(
            HttpServletRequest request,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody ValidationCommand body) {
        String purpose = single(request, PURPOSE, true);
        return accepted(service.validate(
                access(request, purpose), command(request, purpose, true, false),
                configId, version, body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/simulate")
    public ResponseEntity<ApiResponse<CommandReceipt>> simulate(
            HttpServletRequest request,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody SimulationCommand body) {
        String purpose = single(request, PURPOSE, true);
        return accepted(service.simulate(
                access(request, purpose), command(request, purpose, true, false),
                configId, version, body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/submit")
    public ResponseEntity<ApiResponse<CommandReceipt>> submit(
            HttpServletRequest request,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody SubmitCommand body) {
        String purpose = single(request, PURPOSE, true);
        return accepted(service.submit(
                access(request, purpose), command(request, purpose, true, false),
                configId, version, body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/approve")
    public ResponseEntity<ApiResponse<CommandReceipt>> approve(
            HttpServletRequest request,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody ApprovalCommand body) {
        String purpose = single(request, PURPOSE, true);
        return accepted(service.approve(
                access(request, purpose), command(request, purpose, true, true),
                configId, version, body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/schedule")
    public ResponseEntity<ApiResponse<CommandReceipt>> schedule(
            HttpServletRequest request,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody ScheduleCommand body) {
        String purpose = single(request, PURPOSE, true);
        return accepted(service.schedule(
                access(request, purpose), command(request, purpose, true, true),
                configId, version, body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/publish")
    public ResponseEntity<ApiResponse<CommandReceipt>> publish(
            HttpServletRequest request,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody PublishCommand body) {
        String purpose = single(request, PURPOSE, true);
        return accepted(service.publish(
                access(request, purpose), command(request, purpose, true, true),
                configId, version, body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/rollback")
    public ResponseEntity<ApiResponse<CommandReceipt>> rollback(
            HttpServletRequest request,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody RollbackCommand body) {
        String purpose = single(request, PURPOSE, true);
        return accepted(service.rollback(
                access(request, purpose), command(request, purpose, true, true),
                configId, version, body));
    }

    @PostMapping("/v1/admin/hris/configurations/{configId}/versions/{version}/retire")
    public ResponseEntity<ApiResponse<CommandReceipt>> retire(
            HttpServletRequest request,
            @PathVariable UUID configId,
            @PathVariable @Min(1) long version,
            @Valid @RequestBody RetireCommand body) {
        String purpose = single(request, PURPOSE, true);
        return accepted(service.retire(
                access(request, purpose), command(request, purpose, true, true),
                configId, version, body));
    }

    private AccessContext access(HttpServletRequest request, String purpose) {
        String gatewayScope = single(request, CONTEXT_SCOPE, true);
        if (!gatewayScope.matches("^hcm-scope-[a-f0-9]{40}$")) {
            throw new IllegalArgumentException("REFERENCE_CONFIGURATION_GATEWAY_SCOPE_INVALID");
        }
        return new AccessContext(
                positiveLong(single(request, TENANT, true)),
                positiveLong(single(request, SUBJECT, true)),
                UUID.fromString(single(request, SUBJECT_PUBLIC_ID, true)),
                single(request, PERMISSIONS, true),
                single(request, ROLES, false),
                single(request, CONTEXT, true),
                gatewayScope,
                new PopulationScope(
                        ScopeType.valueOf(single(request, SCOPE_TYPE, true)),
                        UUID.fromString(single(request, SCOPE_ID, true)),
                        single(request, SCOPE_DIGEST, true),
                        single(request, DECISION_REVISION, true)),
                positiveLong(single(request, FIELD_POLICY_REVISION, true)),
                positiveLong(single(request, AUTHORIZATION_REVISION, true)),
                OffsetDateTime.parse(single(request, REVALIDATE_AT, true)),
                Set.copyOf(values(single(request, MASKED_GROUPS, false))),
                Set.copyOf(values(single(request, OMITTED_GROUPS, false))),
                purpose);
    }

    private CommandContext command(
            HttpServletRequest request,
            String purpose,
            boolean requireVersion,
            boolean highRisk) {
        String version = single(request, EXPECTED_OBJECT_VERSION, requireVersion);
        Object authority = request.getAttribute(
                HcmPlatformPepRegistry.class.getName() + ".authority");
        return new CommandContext(
                single(request, IDEMPOTENCY, true),
                uuidOrRandom(single(request, CORRELATION, false)),
                purpose,
                version == null ? null : nonNegativeLong(version),
                single(request, EXPECTED_DECISION_REVISION, highRisk),
                single(request, STEP_UP, highRisk),
                authority instanceof HcmPlatformPepRegistry.RouteAuthority route
                        ? route.routeContractKey() : null,
                single(request, CONTEXT, true),
                single(request, CONTEXT_SCOPE, true),
                single(request, DECISION_REVISION, true));
    }

    private String single(HttpServletRequest request, String name, boolean required) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.isEmpty() && !required) return null;
        if (values.size() != 1) throw new IllegalArgumentException(name + " must occur exactly once");
        String value = values.getFirst();
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > 16_384 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private long positiveLong(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 1) throw new IllegalArgumentException("positive identifier required");
        return parsed;
    }

    private long nonNegativeLong(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 0) throw new IllegalArgumentException("non-negative version required");
        return parsed;
    }

    private UUID uuidOrRandom(String value) {
        return value == null ? UUID.randomUUID() : UUID.fromString(value);
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
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Arrays.stream(value.split(",", -1))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .forEach(token -> {
                    if (!result.add(token)) throw new IllegalArgumentException("duplicate query value");
                });
        return List.copyOf(result);
    }
}
