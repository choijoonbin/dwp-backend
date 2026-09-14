package com.dwp.services.approval.documentretention.management.receipt;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes=ApprovalRetentionReceiptController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class ApprovalRetentionReceiptAdvice {
    @Hidden @ExceptionHandler(ApprovalRetentionReceiptErrors.MetadataUnavailable.class)
    public ResponseEntity<ApiResponse<Object>> unavailable(ApprovalRetentionReceiptErrors.MetadataUnavailable failure,HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.<Object>builder().status("ERROR").success(false)
                .errorCode(ApprovalRetentionReceiptErrors.METADATA_UNAVAILABLE).message(failure.getMessage())
                .timestamp(LocalDateTime.now()).correlationId(request.getHeader(HeaderConstants.X_CORRELATION_ID)).build());
    }
}
