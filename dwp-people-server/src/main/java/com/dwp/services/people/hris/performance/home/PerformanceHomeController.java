package com.dwp.services.people.hris.performance.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Service-only endpoint. No browser route, body or caller-selected tenant exists. */
@RestController
@ConditionalOnProperty(prefix = "dwp.people.hris.performance-home", name = "enabled",
        havingValue = "true")
final class PerformanceHomeController {

    private final PerformanceHomeAdmission admission;
    private final PerformanceHomeService service;

    PerformanceHomeController(
            PerformanceHomeAdmission admission,
            PerformanceHomeService service) {
        this.admission = admission;
        this.service = service;
    }

    @GetMapping(value = "/internal/hris-performance/v1/home-contributions/{payloadRef}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    HrisHomeContributionMaterializationV1 materialize(
            @PathVariable String payloadRef,
            HttpServletRequest request) {
        String exactPath = request.getRequestURI();
        if (request.getQueryString() != null) exactPath += '?' + request.getQueryString();
        PerformanceHomeAdmission.Admission admitted = admission.admit(
                exactPath, headers(request));
        return service.materialize(payloadRef, admitted);
    }

    private List<HomeMaterializationWorkloadProofV1.Header> headers(
            HttpServletRequest request) {
        List<HomeMaterializationWorkloadProofV1.Header> result = new ArrayList<>();
        if (request.getHeaderNames() == null) return List.of();
        for (String name : Collections.list(request.getHeaderNames())) {
            for (String value : Collections.list(request.getHeaders(name))) {
                result.add(new HomeMaterializationWorkloadProofV1.Header(name, value));
            }
        }
        return List.copyOf(result);
    }
}

@RestControllerAdvice(assignableTypes = PerformanceHomeController.class)
@ConditionalOnProperty(prefix = "dwp.people.hris.performance-home", name = "enabled",
        havingValue = "true")
final class PerformanceHomeExceptionHandler {

    @ExceptionHandler(HomeMaterializationWorkloadProofV1.AdmissionDenied.class)
    ResponseEntity<Problem> admissionDenied(
            HomeMaterializationWorkloadProofV1.AdmissionDenied denied) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new Problem("PERFORMANCE_HOME_ADMISSION_DENIED"));
    }

    @ExceptionHandler(PerformanceHomeReferenceCodec.ReferenceFailure.class)
    ResponseEntity<Problem> referenceUnavailable(
            PerformanceHomeReferenceCodec.ReferenceFailure failure) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new Problem("PERFORMANCE_HOME_REFERENCE_NOT_AVAILABLE"));
    }

    record Problem(String code) {
    }
}
