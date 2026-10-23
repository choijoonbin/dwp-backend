package com.dwp.services.people.hris.performance.per008;

import org.springframework.http.HttpStatus;

import java.util.UUID;

final class Per008Problem extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final UUID correlationId;

    private Per008Problem(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
        this.correlationId = UUID.randomUUID();
    }

    static Per008Problem invalid(String code) {
        return new Per008Problem(HttpStatus.BAD_REQUEST, code);
    }
    static Per008Problem unauthorized(String code) {
        return new Per008Problem(HttpStatus.UNAUTHORIZED, code);
    }
    static Per008Problem forbidden(String code) {
        return new Per008Problem(HttpStatus.FORBIDDEN, code);
    }
    static Per008Problem notFound() {
        return new Per008Problem(HttpStatus.NOT_FOUND, "PER008_RESOURCE_NOT_FOUND");
    }
    static Per008Problem conflict(String code) {
        return new Per008Problem(HttpStatus.CONFLICT, code);
    }
    static Per008Problem suppressed(String code) {
        return new Per008Problem(HttpStatus.UNPROCESSABLE_ENTITY, code);
    }
    static Per008Problem stepUp() {
        return new Per008Problem(HttpStatus.PRECONDITION_REQUIRED, "PER008_STEP_UP_REQUIRED");
    }
    static Per008Problem unavailable(String code) {
        return new Per008Problem(HttpStatus.SERVICE_UNAVAILABLE, code);
    }

    HttpStatus status() { return status; }
    String code() { return code; }
    UUID correlationId() { return correlationId; }
}
