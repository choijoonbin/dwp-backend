package com.dwp.services.platform.registry;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.platform.reference.ReferenceLifecycle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/dwaion/agents")
public class DwaionAgentRegistryController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final RegistryService service;

    public DwaionAgentRegistryController(RegistryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<RegistryDtos.PageResult<RegistryDtos.RegistryEntryResponse>> list(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestParam(required = false) ReferenceLifecycle lifecycle,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.list(
                tenantId, RegistryType.AGENT, lifecycle, query, page, size));
    }

    @GetMapping("/{entryKey}")
    public ApiResponse<RegistryDtos.RegistryEntryDetail> detail(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @PathVariable String entryKey) {
        return ApiResponse.success(service.get(tenantId, RegistryType.AGENT, entryKey));
    }

    @PostMapping
    public ApiResponse<RegistryDtos.RegistryEntryResponse> create(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody CreateDwaionAgentRequest request) {
        return ApiResponse.success(service.create(
                tenantId,
                userId,
                correlationId,
                new RegistryDtos.CreateRegistryEntryRequest(
                        RegistryType.AGENT,
                        request.entryKey(),
                        request.name(),
                        request.description(),
                        request.ownerRef(),
                        request.riskTier(),
                        request.artifactVersion())));
    }

    @PostMapping("/{entryKey}/revisions")
    public ApiResponse<RegistryDtos.RegistryEntryResponse> createRevision(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable String entryKey,
            @Valid @RequestBody RegistryDtos.CreateRegistryRevisionRequest request) {
        return ApiResponse.success(service.createRevision(
                tenantId, userId, correlationId, RegistryType.AGENT, entryKey, request));
    }

    @PatchMapping("/{entryKey}/revisions/{revision}")
    public ApiResponse<RegistryDtos.RegistryEntryResponse> updateRevision(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable String entryKey,
            @PathVariable Integer revision,
            @Valid @RequestBody RegistryDtos.UpdateRegistryRevisionRequest request) {
        return ApiResponse.success(service.updateRevision(
                tenantId, userId, correlationId, RegistryType.AGENT, entryKey, revision, request));
    }

    @PostMapping("/{entryKey}/revisions/{revision}/activate")
    public ApiResponse<RegistryDtos.RegistryEntryResponse> activateRevision(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable String entryKey,
            @PathVariable Integer revision,
            @Valid @RequestBody RegistryDtos.VersionRequest request) {
        return ApiResponse.success(service.activateRevision(
                tenantId, userId, correlationId, RegistryType.AGENT, entryKey,
                revision, request.version()));
    }

    @PostMapping("/{entryKey}/revisions/{revision}/retire")
    public ApiResponse<RegistryDtos.RegistryEntryResponse> retireRevision(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable String entryKey,
            @PathVariable Integer revision,
            @Valid @RequestBody RegistryDtos.VersionRequest request) {
        return ApiResponse.success(service.retireRevision(
                tenantId, userId, correlationId, RegistryType.AGENT, entryKey,
                revision, request.version()));
    }

    public record CreateDwaionAgentRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,99}") String entryKey,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 1000) String description,
            @NotBlank @Size(max = 160) String ownerRef,
            @NotNull RiskTier riskTier,
            @NotBlank @Size(max = 64) String artifactVersion) {
    }
}
