package com.dwp.services.time.close;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.TimeCloseModels.VerifiedCloseRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Local renderer for the already-central HrisTimeProblemV1 wire shape. */
@RestControllerAdvice(assignableTypes = TimeCloseOwnerController.class)
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(
        name = "dwp.hris.time.close.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class TimeCloseOwnerProblemAdvice {

    @ExceptionHandler(TimeCloseBoundaryFailure.class)
    ResponseEntity<Map<String, Object>> boundary(
            TimeCloseBoundaryFailure failure, HttpServletRequest request) {
        return render(failure.status(), failure.code(), failure.retryable(),
                failure.currentVersion(), request);
    }

    @ExceptionHandler(BaseException.class)
    ResponseEntity<Map<String, Object>> domain(
            BaseException failure, HttpServletRequest request) {
        ErrorCode source = failure.getErrorCode();
        if (source == ErrorCode.OBJECT_VERSION_CONFLICT) {
            return render(412, "PRECONDITION_FAILED", false, null, request);
        }
        if (source == ErrorCode.NOT_FOUND || source == ErrorCode.ENTITY_NOT_FOUND) {
            return render(404, "TARGET_NOT_DISCLOSED", false, null, request);
        }
        if (source == ErrorCode.INVALID_INPUT_VALUE) {
            return render(400, "SCHEMA_INVALID", false, null, request);
        }
        if (source == ErrorCode.INVALID_STATE) {
            return render(422, "DOMAIN_RULE_REJECTED", false, null, request);
        }
        if (source == ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE) {
            return render(503, "AUTHORITY_UNAVAILABLE", true, null, request);
        }
        if (source == ErrorCode.FORBIDDEN || source == ErrorCode.SOD_CONFLICT) {
            return render(403, "AUTHORIZATION_DENIED", false, null, request);
        }
        if (source == ErrorCode.SERVICE_RATE_LIMITED) {
            return render(429, "RATE_LIMITED", true, null, request);
        }
        return render(409, "STALE_OR_STATE_CONFLICT", false, null, request);
    }

    private static ResponseEntity<Map<String, Object>> render(
            int status,
            String code,
            boolean retryable,
            Long currentVersion,
            HttpServletRequest request) {
        UUID correlation = correlation(request);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:dwp:hris:time:problem:" + code.toLowerCase());
        body.put("title", title(status));
        body.put("status", status);
        body.put("code", code);
        body.put("correlationId", correlation);
        body.put("retryable", retryable);
        if (currentVersion != null) body.put("currentVersion", currentVersion);
        var response = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Correlation-ID", correlation.toString());
        if (status == 412 && currentVersion != null) {
            response.eTag(Long.toString(currentVersion));
        }
        if (status == 429 || status == 503) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(body);
    }

    private static UUID correlation(HttpServletRequest request) {
        Object value = request.getAttribute(
                TimeCloseOwnerAccessFilter.VERIFIED_REQUEST_ATTRIBUTE);
        if (value instanceof VerifiedCloseRequest verified) {
            return verified.correlationId();
        }
        try {
            String raw = request.getHeader("X-Correlation-ID");
            UUID parsed = raw == null ? null : UUID.fromString(raw);
            return parsed != null && parsed.toString().equals(raw)
                    ? parsed : UUID.randomUUID();
        } catch (IllegalArgumentException invalid) {
            return UUID.randomUUID();
        }
    }

    private static String title(int status) {
        return switch (status) {
            case 400 -> "Schema invalid";
            case 401 -> "Authentication required";
            case 403 -> "Authorization denied";
            case 404 -> "Target not disclosed";
            case 409 -> "Stale or state conflict";
            case 412 -> "Precondition failed";
            case 422 -> "Domain rule rejected";
            case 428 -> "Precondition required";
            case 429 -> "Rate limited";
            default -> "Authority unavailable";
        };
    }
}
