package com.dwp.services.payroll.preparation;

import com.dwp.core.common.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.UUID;

final class PayrollPreparationProblems {

    private PayrollPreparationProblems() {
    }

    static Definition define(boolean query, ErrorCode error, String title,
                             UUID correlationId) {
        int status = status(error);
        if (query && status == 422) status = 409;
        String code = code(status);
        String safeTitle = title == null || title.isBlank()
                ? "The request could not be completed."
                : title.substring(0, Math.min(title.length(), 256));
        boolean retryable = status == 429 || status == 503;
        Object body = query
                ? new IaqProblem(type(code), safeTitle, status, code,
                correlationId, retryable)
                : new CommandProblem(type(code), safeTitle, status, code,
                correlationId, retryable, null, null);
        return new Definition(status, body);
    }

    static int status(ErrorCode error) {
        if (error == ErrorCode.UNAUTHORIZED || error == ErrorCode.TOKEN_INVALID
                || error == ErrorCode.AUTH_INVALID_CREDENTIALS
                || error == ErrorCode.AUTH_REQUIRED) return 401;
        if (error == ErrorCode.FORBIDDEN || error == ErrorCode.TENANT_MISMATCH
                || error == ErrorCode.STEP_UP_REQUIRED
                || error == ErrorCode.SOD_CONFLICT) return 403;
        if (error == ErrorCode.NOT_FOUND || error == ErrorCode.ENTITY_NOT_FOUND
                || error == ErrorCode.RESOURCE_NOT_AVAILABLE) return 404;
        if (error == ErrorCode.RESOURCE_CONFLICT
                || error == ErrorCode.STEP_UP_CHALLENGE_MISMATCH
                || error == ErrorCode.STEP_UP_CHALLENGE_REPLAY
                || error == ErrorCode.DECISION_REVISION_CONFLICT
                || error == ErrorCode.OBJECT_VERSION_CONFLICT) return 409;
        if (error == ErrorCode.INVALID_STATE) return 422;
        if (error == ErrorCode.SERVICE_RATE_LIMITED) return 429;
        if (error == ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE
                || error == ErrorCode.EXTERNAL_SERVICE_ERROR
                || error == ErrorCode.INTERNAL_SERVER_ERROR) return 503;
        return 400;
    }

    private static String code(int status) {
        return switch (status) {
            case 400 -> "SCHEMA_INVALID";
            case 401 -> "AUTHENTICATION_REQUIRED";
            case 403 -> "AUTHORIZATION_DENIED";
            case 404 -> "TARGET_NOT_DISCLOSED";
            case 409 -> "STALE_OR_STATE_CONFLICT";
            case 422 -> "DOMAIN_RULE_REJECTED";
            case 429 -> "RATE_LIMITED";
            default -> "RESULT_UNKNOWN";
        };
    }

    private static String type(String code) {
        return "urn:dwp:problem:" + code;
    }

    record Definition(int status, Object body) {
    }

    @JsonPropertyOrder({
            "type", "title", "status", "code", "correlationId", "retryable"})
    record IaqProblem(
            String type,
            String title,
            int status,
            String code,
            UUID correlationId,
            boolean retryable) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({
            "type", "title", "status", "code", "correlationId", "retryable",
            "currentVersion", "receiptId"})
    record CommandProblem(
            String type,
            String title,
            int status,
            String code,
            UUID correlationId,
            boolean retryable,
            Long currentVersion,
            UUID receiptId) {
    }
}
