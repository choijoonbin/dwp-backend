package com.dwp.services.platform.home.personalization;

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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/home-templates")
public class HomeTemplateController {
    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String PERMISSIONS = "X-DWP-Permissions";
    private static final String ROLES = "X-DWP-Roles";
    private static final String CORRELATION = "X-Correlation-ID";
    private static final String IDEMPOTENCY = "Idempotency-Key";

    private final HomeTemplateService service;

    public HomeTemplateController(HomeTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<HomeTemplateDtos.HomeTemplateResponse>> list(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = ROLES, required = false) String roles) {
        return ApiResponse.success(service.list(tenantId, permissions, roles));
    }

    @GetMapping("/{templateId}")
    public ApiResponse<HomeTemplateDtos.HomeTemplateResponse> get(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = ROLES, required = false) String roles,
            @PathVariable UUID templateId) {
        return ApiResponse.success(service.get(
                tenantId, templateId, permissions, roles));
    }

    @GetMapping("/{templateId}/revisions")
    public ApiResponse<List<HomeTemplateDtos.HomeTemplateRevisionResponse>> revisions(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID templateId) {
        return ApiResponse.success(service.revisions(tenantId, templateId, permissions));
    }

    @PostMapping
    public ApiResponse<HomeTemplateDtos.HomeTemplateResponse> create(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody HomeTemplateDtos.CreateHomeTemplateRequest request) {
        return ApiResponse.success(service.create(
                tenantId, actorId, permissions, commandId, correlationId, request));
    }

    @PutMapping("/{templateId}")
    public ApiResponse<HomeTemplateDtos.HomeTemplateResponse> update(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID templateId,
            @Valid @RequestBody HomeTemplateDtos.UpdateHomeTemplateRequest request) {
        return ApiResponse.success(service.update(
                tenantId, actorId, permissions, templateId, commandId,
                correlationId, request));
    }

    @PostMapping("/{templateId}/publish")
    public ApiResponse<HomeTemplateDtos.HomeTemplateResponse> publish(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID templateId,
            @Valid @RequestBody HomeTemplateDtos.VersionRequest request) {
        return ApiResponse.success(service.publish(
                tenantId, actorId, permissions, templateId,
                commandId, correlationId, request.version()));
    }

    @PostMapping("/{templateId}/revoke")
    public ApiResponse<HomeTemplateDtos.HomeTemplateResponse> revoke(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID templateId,
            @Valid @RequestBody HomeTemplateDtos.VersionRequest request) {
        return ApiResponse.success(service.revoke(
                tenantId, actorId, permissions, templateId,
                commandId, correlationId, request.version()));
    }

    @PostMapping("/{templateId}/apply")
    public ApiResponse<HomeViewDtos.HomeViewResponse> apply(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(value = ROLES, required = false) String roles,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID templateId,
            @Valid @RequestBody HomeTemplateDtos.ApplyHomeTemplateRequest request) {
        return ApiResponse.success(service.apply(
                tenantId, userId, roles, templateId,
                commandId, correlationId, request));
    }
}
