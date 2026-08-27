package com.dwp.core.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "E1000", "An internal server error occurred."),
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "E1001", "The input is invalid."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "E1004", "The requested resource was not found."),
    RESOURCE_NOT_AVAILABLE(
            HttpStatus.NOT_FOUND,
            "RESOURCE_NOT_AVAILABLE",
            "The requested resource is not available."),
    RESOURCE_CONFLICT(HttpStatus.CONFLICT, "E1009", "The resource conflicts with its current state."),
    SAVED_VIEW_CUSTODY_STALE(
            HttpStatus.CONFLICT,
            "SAVED_VIEW_CUSTODY_STALE",
            "Saved-view custody changed. Refresh the plan and retry."),
    SAVED_VIEW_TARGET_INELIGIBLE(
            HttpStatus.BAD_REQUEST,
            "SAVED_VIEW_TARGET_INELIGIBLE",
            "The selected target is not eligible for the affected saved views."),
    SAVED_VIEW_PERSONAL_NAME_CONFLICT(
            HttpStatus.CONFLICT,
            "SAVED_VIEW_PERSONAL_NAME_CONFLICT",
            "The selected target already has an active personal saved view with the same name and surface."),
    SAVED_VIEW_SHARED_NAME_CONFLICT(
            HttpStatus.CONFLICT,
            "SAVED_VIEW_SHARED_NAME_CONFLICT",
            "An active shared saved view already uses the same scope, name, and surface. Rename or archive the conflicting view and retry."),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "E2000", "Authentication is required."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "E2001", "You do not have permission to perform this action."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "E2003", "The authentication token is invalid."),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "E2004", "The credentials are invalid."),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "E2005", "Authentication is required."),
    TENANT_MISSING(HttpStatus.BAD_REQUEST, "E2006", "Tenant information is required."),
    TENANT_MISMATCH(HttpStatus.FORBIDDEN, "E2007", "Tenant information does not match."),
    STEP_UP_REQUIRED(
            HttpStatus.FORBIDDEN,
            "STEP_UP_REQUIRED",
            "Fresh step-up authentication is required."),
    STEP_UP_CHALLENGE_MISMATCH(
            HttpStatus.CONFLICT,
            "STEP_UP_CHALLENGE_MISMATCH",
            "The step-up challenge does not match this command."),
    STEP_UP_CHALLENGE_REPLAY(
            HttpStatus.CONFLICT,
            "STEP_UP_CHALLENGE_REPLAY",
            "The step-up challenge has already been consumed."),
    DECISION_REVISION_CONFLICT(
            HttpStatus.CONFLICT,
            "DECISION_REVISION_CONFLICT",
            "The authorization decision revision changed."),
    OBJECT_VERSION_CONFLICT(
            HttpStatus.CONFLICT,
            "OBJECT_VERSION_CONFLICT",
            "The governed object version changed."),
    SOD_CONFLICT(
            HttpStatus.FORBIDDEN,
            "SOD_CONFLICT",
            "Separation-of-duties policy prevents this action."),

    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "E3000", "The requested entity was not found."),
    INVALID_STATE(HttpStatus.BAD_REQUEST, "E3002", "The resource state is invalid."),

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "E4000", "Input validation failed."),
    INVALID_FORMAT(HttpStatus.BAD_REQUEST, "E4002", "The format is invalid."),

    EXTERNAL_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "E5000", "An external service error occurred."),
    AUTHORITY_RESOLUTION_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "AUTHORITY_RESOLUTION_UNAVAILABLE",
            "Authority resolution is temporarily unavailable.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}
