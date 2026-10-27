package com.dwp.services.auth.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter; all authentication evidence must have been attached by the ingress gate. */
@RestController
@ConditionalOnProperty(name = "dwp.auth.hrm013-identity-task-dispatch.enabled",
        havingValue = "true")
@RequestMapping(Hrm013IdentityTaskDispatchController.PATH)
public final class Hrm013IdentityTaskDispatchController {
    static final String PATH = "/internal/auth/v1/separation-task-dispatches";
    static final String RECEIPT_PROOF_HEADER = "X-DWP-Workload-Receipt-Proof";
    private final Hrm013IdentityTaskDispatchService service;

    public Hrm013IdentityTaskDispatchController(
            Hrm013IdentityTaskDispatchService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> admit(HttpServletRequest servletRequest) {
        Hrm013IdentityTaskDispatchIngressFilter.VerifiedTransportRequest context = null;
        try {
            context = context(servletRequest);
            var admission = service.admit(context);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(RECEIPT_PROOF_HEADER, admission.receiptProof())
                    .header("X-Correlation-ID",
                            admission.receipt().correlationId().toString())
                    .header("X-DWP-Idempotent-Replay",
                            Boolean.toString(admission.replayed()))
                    .body(admission.exactReceiptBody());
        } catch (RuntimeException failure) {
            Hrm013SafeErrorV1.SafeResponse safe = Hrm013SafeErrorV1.map(
                    failure, context == null ? UUID.randomUUID()
                            : context.correlationId());
            ResponseEntity.BodyBuilder response = ResponseEntity
                    .status(safe.httpStatus()).cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache");
            if (safe.retryAfterSeconds() != null) {
                response.header(HttpHeaders.RETRY_AFTER,
                        safe.retryAfterSeconds().toString());
            }
            if (!safe.emptyBody()) response.contentType(MediaType.APPLICATION_PROBLEM_JSON);
            return response.body(safe.bodyBytes());
        }
    }

    private static Hrm013IdentityTaskDispatchIngressFilter.VerifiedTransportRequest
            context(HttpServletRequest request) {
        Object raw = request.getAttribute(
                Hrm013IdentityTaskDispatchIngressFilter.ATTRIBUTE);
        if (raw instanceof Hrm013IdentityTaskDispatchIngressFilter
                .VerifiedTransportRequest value) return value;
        throw Hrm013SafeErrorV1.authenticationRequired();
    }
}
