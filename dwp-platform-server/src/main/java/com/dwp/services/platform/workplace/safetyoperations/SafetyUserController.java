package com.dwp.services.platform.workplace.safetyoperations;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;

@RestController
@RequestMapping("/v1/workplace/safety")
public class SafetyUserController {
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String USER = "X-DWP-User-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String CORRELATION = "X-Correlation-ID";
    static final String VIEW = "APP.WORKPLACE:VIEW";
    static final String UPDATE = "APP.WORKPLACE:UPDATE";

    private final SafetyOperationsService service;

    public SafetyUserController(SafetyOperationsService service) {
        this.service = service;
    }

    @GetMapping("/incidents/active")
    public ApiResponse<List<SafetySheet>> active(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long userId,
            @RequestHeader(PERMISSIONS) String permissions,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.activeSheets(tenantId, userId));
    }

    @GetMapping("/incidents/{incidentId}")
    public ApiResponse<SafetySheet> incident(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long userId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID incidentId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.sheet(tenantId, userId, incidentId));
    }

    @PostMapping("/incidents/{incidentId}/responses")
    public ResponseEntity<ApiResponse<ResponseCommandResult>> respond(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long userId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID incidentId,
            @Valid @RequestBody SafetyResponseRequest request) {
        requirePermission(permissions, UPDATE);
        ResponseCommandResult result = service.respond(tenantId, userId, incidentId,
                idempotencyKey, request, correlationId);
        return accepted(result, result.receipt().statusHref());
    }

    @GetMapping("/incidents/{incidentId}/messages")
    public ApiResponse<List<IncidentMessage>> messages(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long userId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID incidentId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.messages(tenantId, userId, incidentId, false));
    }

    @PostMapping("/incidents/{incidentId}/messages")
    public ResponseEntity<ApiResponse<MessageCommandResult>> message(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long userId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID incidentId,
            @Valid @RequestBody MessageRequest request) {
        requirePermission(permissions, UPDATE);
        MessageCommandResult result = service.userMessage(tenantId, userId, incidentId,
                idempotencyKey, request, correlationId);
        return accepted(result, result.receipt().statusHref());
    }

    static void requirePermission(String values, String expected) {
        boolean allowed = values != null && Arrays.stream(values.split(","))
                .map(String::trim).map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(expected::equals);
        if (!allowed) throw new BaseException(ErrorCode.FORBIDDEN,
                "The required Workplace safety permission is missing.");
    }

    static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
    }

    private static <T> ResponseEntity<ApiResponse<T>> accepted(T body, String href) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(href)).body(ApiResponse.success(body));
    }
}
