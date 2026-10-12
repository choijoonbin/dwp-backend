package com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = EmployeeChangeApprovalBridgeController.class)
final class EmployeeChangeApprovalBridgeAdvice {
    @ExceptionHandler(HrisApprovalBridgeV2.BridgeFailure.class)
    ResponseEntity<Map<String, String>> workloadFailure(
            HrisApprovalBridgeV2.BridgeFailure failure) {
        return ResponseEntity.status(failure.httpStatus())
                .cacheControl(CacheControl.noStore())
                .body(Map.of("reason", failure.reason()));
    }

    @ExceptionHandler(EmployeeChangeApprovalProtocolException.class)
    ResponseEntity<Map<String, String>> protocolFailure(
            EmployeeChangeApprovalProtocolException failure) {
        return ResponseEntity.status(failure.status())
                .cacheControl(CacheControl.noStore())
                .body(Map.of("reason", failure.reason()));
    }
}
