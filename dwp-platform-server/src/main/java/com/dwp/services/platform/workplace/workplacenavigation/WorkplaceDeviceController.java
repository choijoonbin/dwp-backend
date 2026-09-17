package com.dwp.services.platform.workplace.workplacenavigation;

import com.dwp.core.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.dwp.services.platform.security.PlatformDeviceIdentity.CREDENTIAL_HEADER;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationController.TENANT;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;

@RestController
@RequestMapping("/v1/device/workplace")
public class WorkplaceDeviceController {
    static final String DEVICE_IDENTITY = CREDENTIAL_HEADER;

    private final WorkplaceDeviceService service;

    public WorkplaceDeviceController(WorkplaceDeviceService service) {
        this.service = service;
    }

    @PostMapping("/devices:register")
    public ResponseEntity<ApiResponse<DeviceView>> register(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(DEVICE_IDENTITY) String deviceIdentity,
            @Valid @RequestBody DeviceRegistrationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .body(ApiResponse.success(service.register(tenantId, deviceIdentity, request)));
    }

    @PostMapping("/devices/{deviceId}/heartbeat")
    public ApiResponse<DeviceView> heartbeat(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(DEVICE_IDENTITY) String deviceIdentity,
            @PathVariable UUID deviceId,
            @Valid @RequestBody DeviceHeartbeatRequest request,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.heartbeat(
                tenantId, deviceId, deviceIdentity, request));
    }

    @GetMapping("/devices/{deviceId}/projection")
    public ApiResponse<DeviceProjection> projection(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(DEVICE_IDENTITY) String deviceIdentity,
            @PathVariable UUID deviceId,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.projection(tenantId, deviceId, deviceIdentity));
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
    }
}
