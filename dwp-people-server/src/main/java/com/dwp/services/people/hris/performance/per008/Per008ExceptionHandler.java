package com.dwp.services.people.hris.performance.per008;

import com.dwp.core.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@RestControllerAdvice(assignableTypes = Per008Controller.class)
@ConditionalOnProperty(name = "dwp.hris.performance.feedback.enabled", havingValue = "true")
final class Per008ExceptionHandler {
    @ExceptionHandler(Per008Problem.class)
    ResponseEntity<ApiResponse<Per008Contracts.Problem>> problem(Per008Problem problem) {
        Per008Contracts.Problem detail = new Per008Contracts.Problem(
                "urn:dwp:problem:" + problem.code().toLowerCase().replace('_', '-'),
                "PER-008 request could not be completed",
                problem.status().value(), problem.code(), problem.correlationId(),
                problem.status().is5xxServerError());
        ApiResponse<Per008Contracts.Problem> envelope = ApiResponse
                .<Per008Contracts.Problem>builder()
                .status("ERROR")
                .message("PER-008 request could not be completed")
                .data(detail)
                .errorCode(problem.code())
                .timestamp(LocalDateTime.now())
                .success(false)
                .correlationId(problem.correlationId().toString())
                .build();
        return ResponseEntity.status(problem.status()).body(envelope);
    }
}
