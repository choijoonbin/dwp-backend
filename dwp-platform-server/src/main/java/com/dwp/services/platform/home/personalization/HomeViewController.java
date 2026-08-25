package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/v1/home-views")
public class HomeViewController {
    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String CORRELATION = "X-Correlation-ID";
    private static final String IDEMPOTENCY = "Idempotency-Key";

    private final HomeViewService service;

    public HomeViewController(HomeViewService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<HomeViewDtos.HomeViewResponse>> list(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestParam(defaultValue = "workspace-home")
            @Pattern(regexp = "[a-z][a-z0-9-]{1,79}") String surfaceKey) {
        return ApiResponse.success(service.list(tenantId, userId, surfaceKey));
    }

    @PostMapping
    public ApiResponse<HomeViewDtos.HomeViewResponse> create(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody HomeViewDtos.CreateHomeViewRequest request) {
        return ApiResponse.success(
                service.create(tenantId, userId, commandId, correlationId, request));
    }

    @GetMapping("/{viewId}")
    public ApiResponse<HomeViewDtos.HomeViewResponse> get(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @PathVariable UUID viewId) {
        return ApiResponse.success(service.get(tenantId, userId, viewId));
    }

    @PutMapping("/{viewId}")
    public ApiResponse<HomeViewDtos.HomeViewResponse> update(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID viewId,
            @Valid @RequestBody HomeViewDtos.UpdateHomeViewRequest request) {
        return ApiResponse.success(service.update(
                tenantId, userId, viewId, commandId, correlationId, request));
    }

    @PostMapping("/{viewId}/reset")
    public ApiResponse<HomeViewDtos.HomeViewResponse> reset(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID viewId,
            @Valid @RequestBody HomeViewDtos.VersionRequest request) {
        return ApiResponse.success(service.reset(
                tenantId, userId, viewId, commandId, correlationId, request.version()));
    }

    @DeleteMapping("/{viewId}")
    public ApiResponse<HomeViewDtos.DeleteHomeViewResponse> delete(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID viewId,
            @RequestParam @Min(0) Long version) {
        return ApiResponse.success(
                service.delete(tenantId, userId, viewId, commandId, version, correlationId));
    }

    @PostMapping("/{viewId}/activate")
    public ApiResponse<HomeViewDtos.HomeViewResponse> activate(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID viewId,
            @Valid @RequestBody HomeViewDtos.VersionRequest request) {
        return ApiResponse.success(service.activate(
                tenantId, userId, viewId, commandId, correlationId, request.version()));
    }

    @PutMapping("/{viewId}/widgets/{widgetKey}/configuration")
    public ApiResponse<HomeViewDtos.HomeViewResponse> configureWidget(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID viewId,
            @PathVariable @Pattern(regexp = "[a-z][a-z0-9-]{0,39}") String widgetKey,
            @Valid @RequestBody HomeViewDtos.UpdateWidgetConfigurationRequest request) {
        return ApiResponse.success(service.putWidgetConfiguration(
                tenantId, userId, viewId, widgetKey, commandId, correlationId, request));
    }

    @GetMapping("/{viewId}/device-layouts")
    public ApiResponse<List<HomeViewDtos.DeviceLayoutResponse>> deviceLayouts(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @PathVariable UUID viewId) {
        return ApiResponse.success(service.deviceLayouts(tenantId, userId, viewId));
    }

    @PutMapping("/{viewId}/device-layouts/{deviceClass}")
    public ApiResponse<HomeViewDtos.DeviceLayoutResponse> putDeviceLayout(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID viewId,
            @PathVariable @Pattern(regexp = "DESKTOP|MOBILE")
            @Schema(allowableValues = {"DESKTOP", "MOBILE"}) String deviceClass,
            @Valid @RequestBody HomeViewDtos.UpdateDeviceLayoutRequest request) {
        return ApiResponse.success(service.putDeviceLayout(
                tenantId, userId, viewId, deviceClass, commandId, correlationId, request));
    }

    @GetMapping("/{viewId}/revisions")
    public ApiResponse<List<HomeViewDtos.HomeViewRevisionResponse>> revisions(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @PathVariable UUID viewId) {
        return ApiResponse.success(service.revisions(tenantId, userId, viewId));
    }

    @PostMapping("/{viewId}/revisions/{revisionId}/restore")
    public ApiResponse<HomeViewDtos.HomeViewResponse> restore(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID viewId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody HomeViewDtos.VersionRequest request) {
        return ApiResponse.success(service.restore(
                tenantId, userId, viewId, revisionId, commandId,
                correlationId, request.version()));
    }
}
