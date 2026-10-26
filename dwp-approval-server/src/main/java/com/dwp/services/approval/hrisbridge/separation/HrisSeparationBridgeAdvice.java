package com.dwp.services.approval.hrisbridge.separation;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Disclosure-safe error boundary dedicated to the service-internal HRM-013 ingress. */
@RestControllerAdvice(assignableTypes = HrisSeparationApprovalController.class)
final class HrisSeparationBridgeAdvice {
    @ExceptionHandler(Throwable.class)
    ResponseEntity<byte[]> safe(Throwable thrown, HttpServletRequest request) {
        Throwable source = thrown instanceof HrisSeparationProtocolException protocol
                ? protocol.failure() : thrown;
        UUID correlation = correlation(request.getHeader("X-Correlation-ID"));
        Hrm013SafeErrorV1.SafeResponse safe = Hrm013SafeErrorV1.map(source, correlation);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(safe.httpStatus())
                .cacheControl(CacheControl.noStore());
        if (!safe.emptyBody()) response.contentType(MediaType.APPLICATION_JSON);
        if (safe.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER,
                    Long.toString(safe.retryAfterSeconds()));
        }
        return response.body(safe.bodyBytes());
    }

    private static UUID correlation(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? parsed : UUID.randomUUID();
        } catch (RuntimeException invalid) {
            return UUID.randomUUID();
        }
    }
}
