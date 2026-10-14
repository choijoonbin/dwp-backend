package com.dwp.services.platform.auditcontrol;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.UUID;

import static com.dwp.services.platform.auditcontrol.HcmAuditEvidenceCursorCodec.AccessContext;

@Validated
@RestController
@RequestMapping("/v1/admin/audit-control")
public class HcmAuditEvidenceController {

    static final String PAGE_ROUTE = "route.hcm.audit.evidence.page";
    static final String DETAIL_ROUTE = "route.hcm.audit.evidence-detail.data";

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String ROUTE = "X-DWP-Route-Contract-Key";
    private static final String CONTEXT = "X-DWP-Context-Key";
    private static final String SCOPE = "X-DWP-Context-Scope-Key";
    private static final String SUBJECT_PUBLIC_ID = "X-DWP-Subject-Principal-Public-ID";
    private static final String FIELD_POLICY_REVISION = "X-DWP-Field-Policy-Revision";
    private static final String AUTHORIZATION_REVISION = "X-DWP-Authorization-Revision";

    private final HcmAuditEvidenceService service;

    public HcmAuditEvidenceController(HcmAuditEvidenceService service) {
        this.service = service;
    }

    @GetMapping(value = "/events", params = "product=HCM")
    @Operation(operationId = "getHcmAuditEvidencePage")
    public ApiResponse<HcmAuditEvidenceDtos.Page> page(
            HttpServletRequest request,
            @RequestParam String product,
            @RequestParam(defaultValue = "D7") AuditWindow window,
            @RequestParam(defaultValue = "ALL") String category,
            @RequestParam(defaultValue = "ALL") String severity,
            @RequestParam(defaultValue = "ALL") String outcome,
            @RequestParam(required = false) String sourceService,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "50") @Min(10) @Max(100) int size,
            @RequestParam(required = false) String cursor,
            HttpServletResponse response) {
        exactProduct(product);
        noStore(response);
        return ApiResponse.success(service.page(
                access(request, PAGE_ROUTE), window, category, severity, outcome,
                sourceService, query, size, cursor));
    }

    @GetMapping(value = "/events/{eventId}", params = "product=HCM")
    @Operation(operationId = "getHcmAuditEvidence")
    public ApiResponse<HcmAuditEvidenceDtos.Evidence> detail(
            HttpServletRequest request,
            @PathVariable UUID eventId,
            @RequestParam String product,
            HttpServletResponse response) {
        exactProduct(product);
        noStore(response);
        return ApiResponse.success(service.detail(access(request, DETAIL_ROUTE), eventId));
    }

    private AccessContext access(HttpServletRequest request, String expectedRoute) {
        String route = single(request, ROUTE);
        if (!expectedRoute.equals(route)) {
            throw forbidden("Trusted HCM audit route evidence is invalid.");
        }
        return new AccessContext(
                positiveLong(single(request, TENANT), TENANT),
                route,
                single(request, CONTEXT),
                single(request, SCOPE),
                uuid(single(request, SUBJECT_PUBLIC_ID), SUBJECT_PUBLIC_ID),
                positiveLong(single(request, FIELD_POLICY_REVISION), FIELD_POLICY_REVISION),
                positiveLong(single(request, AUTHORIZATION_REVISION), AUTHORIZATION_REVISION));
    }

    private String single(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null) values = Collections.emptyEnumeration();
        if (!values.hasMoreElements()) throw forbidden("Trusted " + name + " is required.");
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank()
                || value.length() > 512 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf(',') >= 0) {
            throw forbidden("Trusted " + name + " is invalid.");
        }
        return value.trim();
    }

    private long positiveLong(String value, String field) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) {
            // handled below
        }
        throw forbidden("Trusted " + field + " is invalid.");
    }

    private UUID uuid(String value, String field) {
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.toString().equals(value.toLowerCase(Locale.ROOT))) return parsed;
        } catch (IllegalArgumentException ignored) {
            // handled below
        }
        throw forbidden("Trusted " + field + " is invalid.");
    }

    private void exactProduct(String product) {
        if (!"HCM".equals(product)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The HCM audit projection requires product=HCM.");
        }
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
