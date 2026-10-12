package com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisWorkloadProofV2;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Raw-byte ingress. Browser identity is forbidden by the shared workload contract. */
@RestController
@ConditionalOnProperty(prefix = "dwp.hris.employee-change", name = "enabled",
        havingValue = "true")
final class EmployeeChangeApprovalBridgeController {
    static final String PATH =
            "/internal/hris-people/v1/employee-change-requests/{requestPublicId}"
                    + "/approval-decisions";
    private final EmployeeChangeApprovalBridgeService service;

    EmployeeChangeApprovalBridgeController(EmployeeChangeApprovalBridgeService service) {
        this.service = service;
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> record(
            HttpServletRequest request,
            @PathVariable UUID requestPublicId,
            @RequestBody byte[] exactBody) {
        String exactPath = HrisApprovalBridgeV2.DECISION_CALLBACK.path(requestPublicId);
        if (!exactPath.equals(request.getRequestURI()) || request.getQueryString() != null) {
            throw EmployeeChangeApprovalProtocolException.badRequest(
                    "EXACT_INTERNAL_PATH_REQUIRED");
        }
        var response = service.dispatch(requestPublicId, headers(request), exactBody);
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
