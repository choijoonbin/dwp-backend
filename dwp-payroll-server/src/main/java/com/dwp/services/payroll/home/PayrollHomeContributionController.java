package com.dwp.services.payroll.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Hidden
@RestController
@ConditionalOnProperty(
        name = "dwp.hris.payroll.home-producer.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollHomeContributionController {

    private final PayrollHomeContributionService service;

    PayrollHomeContributionController(PayrollHomeContributionService service) {
        this.service = service;
    }

    @GetMapping("/internal/hris-payroll/v1/home-contributions/{payloadRef}")
    ResponseEntity<HrisHomeContributionMaterializationV1> materialize(
            @PathVariable String payloadRef,
            HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > 0
                || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
            throw new PayrollHomeContributionService.PayrollHomeNotFoundException();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.materialize(
                        payloadRef, request.getRequestURI(), headers(request)));
    }

    static List<HomeMaterializationWorkloadProofV1.Header> headers(
            HttpServletRequest request) {
        List<HomeMaterializationWorkloadProofV1.Header> result = new ArrayList<>();
        var headerNames = request.getHeaderNames();
        if (headerNames == null) {
            throw new PayrollHomeContributionService.PayrollHomeNotFoundException();
        }
        List<String> names = Collections.list(headerNames);
        for (String name : names) {
            for (String value : Collections.list(request.getHeaders(name))) {
                result.add(new HomeMaterializationWorkloadProofV1.Header(name, value));
            }
        }
        return List.copyOf(result);
    }
}

@RestControllerAdvice(assignableTypes = PayrollHomeContributionController.class)
@ConditionalOnProperty(
        name = "dwp.hris.payroll.home-producer.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollHomeContributionFailureHandler {

    @ExceptionHandler({
            HomeMaterializationWorkloadProofV1.AdmissionDenied.class,
            PayrollHomeContributionService.PayrollHomeNotFoundException.class,
            IllegalArgumentException.class,
            IllegalStateException.class
    })
    ResponseEntity<Failure> opaqueNotFound(RuntimeException ignored) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .body(new Failure("PAYROLL_HOME_CONTRIBUTION_NOT_FOUND"));
    }

    record Failure(String code) {
    }
}
