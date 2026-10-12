package com.dwp.services.approval.hrisbridge;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisWorkloadProofV2;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Dedicated raw-byte ingress; ordinary Gateway/browser authentication is not reused. */
@RestController
@ConditionalOnProperty(prefix = "dwp.approval.hris-bridge", name = "enabled", havingValue = "true")
final class HrisApprovalBridgeController {
    static final String PATH = "/internal/hris-approval/v1/approval-requests";
    private final HrisApprovalBridgeService service;

    HrisApprovalBridgeController(HrisApprovalBridgeService service) {
        this.service = service;
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> create(HttpServletRequest request, @RequestBody byte[] exactBody) {
        if (!PATH.equals(request.getRequestURI()) || request.getQueryString() != null) {
            throw HrisApprovalProtocolException.badRequest("EXACT_INTERNAL_PATH_REQUIRED");
        }
        var response = service.dispatch(headers(request), exactBody);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(response.exactResponseBytes());
    }

    static List<HrisWorkloadProofV2.Header> headers(HttpServletRequest request) {
        var result = new ArrayList<HrisWorkloadProofV2.Header>();
        var names = request.getHeaderNames();
        if (names == null) return List.of();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            var values = request.getHeaders(name);
            if (values == null) continue;
            for (String value : Collections.list(values)) {
                result.add(new HrisWorkloadProofV2.Header(name, value));
            }
        }
        return List.copyOf(result);
    }
}
