package com.dwp.services.payroll.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exact internal receipt-first HRM-013 Payroll dispatch endpoint. */
@RestController
@RequestMapping("/internal/hris-payroll/v1")
@ConditionalOnProperty(prefix = "dwp.payroll.hrm013-task-dispatch", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public final class Hrm013PayrollTaskDispatchController {
    static final String RECEIPT_PROOF_HEADER = "X-DWP-Workload-Receipt-Proof";
    private final Hrm013PayrollTaskDispatchService service;

    Hrm013PayrollTaskDispatchController(Hrm013PayrollTaskDispatchService service) {
        this.service = Objects.requireNonNull(service);
    }

    @PostMapping(path = "/separation-task-dispatches",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> dispatch(HttpServletRequest request) {
        Object value = request.getAttribute(
                Hrm013PayrollTaskDispatchIngressFilter.VERIFIED_ATTRIBUTE);
        if (!(value instanceof Hrm013PayrollTaskDispatchIngressFilter.VerifiedDispatch verified)) {
            return safe(Hrm013SafeErrorV1.authenticationRequired(), correlation(request));
        }
        try {
            var admission = service.admit(verified);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .header(RECEIPT_PROOF_HEADER, admission.receiptProof())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(admission.exactReceiptBytes());
        } catch (RuntimeException failure) {
            return safe(failure, verified.proof().claims().correlationId());
        }
    }

    private static ResponseEntity<byte[]> safe(Throwable failure, UUID correlationId) {
        Hrm013SafeErrorV1.SafeResponse safe = Hrm013SafeErrorV1.map(
                failure, correlationId == null ? UUID.randomUUID() : correlationId);
        ResponseEntity.BodyBuilder result = ResponseEntity.status(safe.httpStatus())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff");
        if (safe.retryAfterSeconds() != null) {
            result.header(HttpHeaders.RETRY_AFTER, safe.retryAfterSeconds().toString());
        }
        if (!safe.emptyBody()) result.contentType(MediaType.APPLICATION_JSON);
        return result.body(safe.bodyBytes());
    }

    private static UUID correlation(HttpServletRequest request) {
        try {
            String raw = request.getHeader("X-Correlation-ID");
            UUID value = UUID.fromString(raw);
            return value.toString().equals(raw) ? value : null;
        } catch (RuntimeException failure) {
            return null;
        }
    }
}
