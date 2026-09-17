package com.dwp.services.platform.workplace.workplacenavigation;

import com.dwp.core.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.dwp.services.platform.security.PlatformDeviceIdentity.CREDENTIAL_HEADER;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceAccessPassController.IDEMPOTENCY;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceAccessPassController.CORRELATION;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceAccessPassDtos.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationController.TENANT;

/** Bound-device endpoint for one-time kiosk pairing. Raw codes are accepted but never returned. */
@RestController
@RequestMapping("/v1/device/workplace")
public class WorkplaceAccessPassDeviceController {
    private final WorkplaceAccessPassService service;

    public WorkplaceAccessPassDeviceController(WorkplaceAccessPassService service) {
        this.service = service;
    }

    @PostMapping("/devices/{deviceId}/access-pass:pair")
    public ApiResponse<AccessPassPairingReceipt> pair(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(CREDENTIAL_HEADER) String deviceIdentity,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody AccessPassPairingRequest request,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        return ApiResponse.success(
                service.pair(tenantId, deviceId, deviceIdentity, idempotencyKey,
                        correlationId, request));
    }
}
