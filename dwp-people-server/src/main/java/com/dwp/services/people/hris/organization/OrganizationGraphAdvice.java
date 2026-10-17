package com.dwp.services.people.hris.organization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Closed, non-disclosing failure boundary for PEP-HRM-010. */
@RestControllerAdvice(assignableTypes = OrganizationGraphController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class OrganizationGraphAdvice {

    @ExceptionHandler(OrganizationGraphProblem.class)
    ResponseEntity<Problem> graphFailure(OrganizationGraphProblem failure) {
        return response(failure.status(), failure.code(), failure.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Problem> missingQuery(MissingServletRequestParameterException ignored) {
        return response(HttpStatus.BAD_REQUEST, OrganizationGraphService.INVALID_QUERY,
                "The organization graph query is invalid.");
    }

    @ExceptionHandler(BaseException.class)
    ResponseEntity<Problem> ownerFailure(BaseException failure) {
        if (failure.getErrorCode() == ErrorCode.NOT_FOUND) {
            return response(HttpStatus.NOT_FOUND, OrganizationGraphService.GRAPH_NOT_FOUND,
                    "The requested organization graph is not available.");
        }
        return response(HttpStatus.FORBIDDEN, OrganizationGraphService.AUTHORIZATION_DENIED,
                "The requested organization graph is not available.");
    }

    private ResponseEntity<Problem> response(
            HttpStatus status,
            String code,
            String detail) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new Problem(
                        "urn:dwp:problem:" + code,
                        detail,
                        status.value(),
                        code,
                        detail));
    }

    record Problem(
            String type,
            String title,
            int status,
            String code,
            String detail) {
    }
}
