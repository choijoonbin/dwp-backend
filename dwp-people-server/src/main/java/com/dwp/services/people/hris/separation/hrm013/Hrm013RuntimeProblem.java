package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/** Single no-store, correlation-only error boundary for the internal runtime. */
public final class Hrm013RuntimeProblem {
    private Hrm013RuntimeProblem() { }

    public static void write(
            HttpServletResponse response, Throwable failure, UUID correlationId)
            throws IOException {
        Hrm013SafeErrorV1.SafeResponse safe = Hrm013SafeErrorV1.map(
                failure, correlationId == null ? UUID.randomUUID() : correlationId);
        byte[] body = Hrm013SafeErrorV1.encode(safe);
        response.resetBuffer();
        response.setStatus(safe.httpStatus());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        if (safe.retryAfterSeconds() != null) {
            response.setHeader("Retry-After", Long.toString(safe.retryAfterSeconds()));
        }
        response.setContentType("application/json");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
