package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;

/** Disclosure-safe HTTP mapping shared by both internal HRM-013 Auth resolvers. */
public final class HrisServiceGrantAuthorityProblem {
    private HrisServiceGrantAuthorityProblem() {
    }

    public static void write(
            HttpServletResponse response, Throwable failure, UUID correlationId)
            throws IOException {
        Hrm013SafeErrorV1.SafeResponse safe = Hrm013SafeErrorV1.map(
                failure, correlationId == null ? new UUID(0L, 0L) : correlationId);
        response.resetBuffer();
        response.setStatus(safe.httpStatus());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Pragma", "no-cache");
        if (safe.retryAfterSeconds() != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER,
                    Long.toString(safe.retryAfterSeconds()));
        }
        byte[] body = safe.bodyBytes();
        if (body.length > 0) {
            response.setContentType("application/json");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
        } else {
            response.setContentLength(0);
        }
    }
}
