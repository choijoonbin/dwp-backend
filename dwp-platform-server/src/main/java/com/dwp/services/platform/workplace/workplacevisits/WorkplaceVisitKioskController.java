package com.dwp.services.platform.workplace.workplacevisits;

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

import java.net.URI;
import java.util.UUID;

import static com.dwp.services.platform.security.PlatformDeviceIdentity.CREDENTIAL_HEADER;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitController.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.*;

@RestController
@RequestMapping("/v1/workplace/kiosk")
public class WorkplaceVisitKioskController {
    static final String DEVICE_IDENTITY = CREDENTIAL_HEADER;
    private final WorkplaceVisitKioskService service;

    public WorkplaceVisitKioskController(WorkplaceVisitKioskService service) {
        this.service = service;
    }

    @GetMapping("/session")
    public ApiResponse<KioskDevice> session(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(DEVICE_IDENTITY) String identity,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.session(tenantId, identity));
    }

    @GetMapping("/visits/{visitId}")
    public ApiResponse<KioskVisit> visit(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(DEVICE_IDENTITY) String identity,
            @PathVariable UUID visitId,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.visit(tenantId, identity, visitId));
    }

    @PostMapping("/visits/{visitId}:arrive")
    public ResponseEntity<ApiResponse<VisitCommandResult>> arrive(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(DEVICE_IDENTITY) String identity,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID visitId,
            @Valid @RequestBody VersionCommand request) {
        return accepted(service.arrive(tenantId, identity, visitId, key, request, correlationId));
    }

    @PostMapping("/visits/{visitId}:checkout")
    public ResponseEntity<ApiResponse<VisitCommandResult>> checkout(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(DEVICE_IDENTITY) String identity,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID visitId,
            @Valid @RequestBody VersionCommand request) {
        return accepted(service.checkout(tenantId, identity, visitId, key, request, correlationId));
    }

    @PostMapping("/devices/{deviceId}:heartbeat")
    public ResponseEntity<ApiResponse<KioskDevice>> heartbeat(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(DEVICE_IDENTITY) String identity,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody KioskHeartbeatRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .body(ApiResponse.success(service.heartbeat(
                        tenantId, identity, deviceId, key, request, correlationId)));
    }

    @PostMapping("/devices/{deviceId}:help")
    public ResponseEntity<ApiResponse<KioskDevice>> help(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(DEVICE_IDENTITY) String identity,
            @RequestHeader(IDEMPOTENCY) String key,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody VersionCommand request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .body(ApiResponse.success(service.requestHelp(
                        tenantId, identity, deviceId, key, request, correlationId)));
    }

    private static ResponseEntity<ApiResponse<VisitCommandResult>> accepted(
            VisitCommandResult result) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.receipt().statusHref()))
                .body(ApiResponse.success(result));
    }
}
