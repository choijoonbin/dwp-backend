package com.dwp.services.time.api;

import com.dwp.core.common.ErrorCode;
import org.springframework.http.HttpStatus;

/** Sealed public Time error codes and their exact HTTP semantics. */
public enum HrisTimeProblemCode {
    SCHEMA_INVALID(HttpStatus.BAD_REQUEST, false),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, false),
    AUTHORIZATION_DENIED(HttpStatus.FORBIDDEN, false),
    TARGET_NOT_DISCLOSED(HttpStatus.NOT_FOUND, false),
    STALE_OR_STATE_CONFLICT(HttpStatus.CONFLICT, false),
    PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED, false),
    DOMAIN_RULE_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, false),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true),
    AUTHORITY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true);

    private final HttpStatus status;
    private final boolean retryable;

    HrisTimeProblemCode(HttpStatus status, boolean retryable) {
        this.status = status;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return status.getReasonPhrase();
    }

    public boolean retryable() {
        return retryable;
    }

    public String type() {
        return "urn:dwp:hris-time:problem:"
                + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    /** Maps legacy service exceptions into the bounded Time problem vocabulary. */
    public static HrisTimeProblemCode from(ErrorCode code) {
        if (code == null) {
            return AUTHORITY_UNAVAILABLE;
        }
        return switch (code) {
            case UNAUTHORIZED, TOKEN_INVALID, AUTH_INVALID_CREDENTIALS, AUTH_REQUIRED ->
                    AUTHENTICATION_REQUIRED;
            case FORBIDDEN, TENANT_MISMATCH, STEP_UP_REQUIRED,
                    STEP_UP_CHALLENGE_MISMATCH, STEP_UP_CHALLENGE_REPLAY, SOD_CONFLICT ->
                    AUTHORIZATION_DENIED;
            case NOT_FOUND, ENTITY_NOT_FOUND, RESOURCE_NOT_AVAILABLE ->
                    TARGET_NOT_DISCLOSED;
            case RESOURCE_CONFLICT, HOME_VIEW_VERSION_CONFLICT,
                    SAVED_VIEW_PERSONAL_NAME_CONFLICT, SAVED_VIEW_SHARED_NAME_CONFLICT,
                    DECISION_REVISION_CONFLICT, OBJECT_VERSION_CONFLICT ->
                    STALE_OR_STATE_CONFLICT;
            case INVALID_STATE -> DOMAIN_RULE_REJECTED;
            case SERVICE_RATE_LIMITED -> RATE_LIMITED;
            case AUTHORITY_RESOLUTION_UNAVAILABLE, EXTERNAL_SERVICE_ERROR,
                    INTERNAL_SERVER_ERROR -> AUTHORITY_UNAVAILABLE;
            case INVALID_INPUT_VALUE, TENANT_MISSING, VALIDATION_ERROR, INVALID_FORMAT,
                    SAVED_VIEW_CUSTODY_STALE, SAVED_VIEW_TARGET_INELIGIBLE ->
                    SCHEMA_INVALID;
        };
    }
}
