package com.dwp.services.approval.hrisbridge.separation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Raw-byte APR-002 ingress isolated from browser and Gateway authentication. */
@RestController
@ConditionalOnProperty(prefix = "dwp.approval.hrm013-bridge", name = "enabled",
        havingValue = "true")
final class HrisSeparationApprovalController {
    static final String PATH =
            "/internal/hris-approval/v1/separation-task-waiver-requests";
    private final HrisSeparationApprovalBridgeService service;
    private final HrisSeparationTrustProfile trust;

    HrisSeparationApprovalController(HrisSeparationApprovalBridgeService service,
            HrisSeparationTrustProfile trust) {
        this.service = service;
        this.trust = trust;
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> create(HttpServletRequest request,
            @RequestBody byte[] exactBody) {
        if (!"POST".equals(request.getMethod()) || !PATH.equals(request.getRequestURI())
                || request.getQueryString() != null) {
            throw HrisSeparationProtocolException.malformed();
        }
        var transportIdentity = trust.authenticate(request);
        var applicationHeaders = trust.applicationHeaders(request);
        var result = service.dispatch("POST", PATH,
                applicationHeaders, exactBody, transportIdentity);
        return ResponseEntity.status(202).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(result.exactResponseBody());
    }
}
