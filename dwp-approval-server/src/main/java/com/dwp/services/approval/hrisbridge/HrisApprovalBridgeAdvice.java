package com.dwp.services.approval.hrisbridge;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = HrisApprovalBridgeController.class)
final class HrisApprovalBridgeAdvice {
    @ExceptionHandler(HrisApprovalBridgeV2.BridgeFailure.class)
    ResponseEntity<Map<String, String>> workloadFailure(
            HrisApprovalBridgeV2.BridgeFailure failure) {
        return ResponseEntity.status(failure.httpStatus())
                .body(Map.of("reason", failure.reason()));
    }

    @ExceptionHandler(HrisApprovalProtocolException.class)
    ResponseEntity<Map<String, String>> protocolFailure(
            HrisApprovalProtocolException failure) {
        return ResponseEntity.status(failure.status())
                .body(Map.of("reason", failure.reason()));
    }
}
