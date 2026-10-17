package com.dwp.services.people.hris.organization;

import org.springframework.http.HttpStatus;

/** Safe transport failure for the HRIS organization graph boundary. */
final class OrganizationGraphProblem extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;

    OrganizationGraphProblem(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
