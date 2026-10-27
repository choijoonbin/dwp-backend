package com.dwp.services.time.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal receipt-first endpoint. The ingress filter supplies the only trusted body. */
@RestController
@RequestMapping("/internal/hris-time/v1")
@ConditionalOnProperty(prefix = "dwp.time.hrm013-task-dispatch", name = "enabled",
        havingValue = "true")
public final class Hrm013TimeTaskDispatchController {
    public static final String RECEIPT_PROOF_HEADER = "X-DWP-Workload-Receipt-Proof";
    private final Hrm013TimeTaskDispatchService service;

    public Hrm013TimeTaskDispatchController(Hrm013TimeTaskDispatchService service) {
        this.service = Objects.requireNonNull(service);
    }

    @PostMapping(value = "/separation-task-dispatches",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> admit(HttpServletRequest request) {
        Object value = request.getAttribute(
                Hrm013TimeTaskDispatchIngressFilter.VERIFIED_ATTRIBUTE);
        if (!(value instanceof Hrm013TimeTaskDispatchService.VerifiedDispatch verified)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        var result = service.admit(verified);
        if (result.exactBody().length == 0
                || result.exactBody().length > Hrm013TaskDispatchV1.MAXIMUM_RESPONSE_BYTES) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl("no-store");
        headers.set(RECEIPT_PROOF_HEADER, result.receiptProof());
        return new ResponseEntity<>(result.exactBody(), headers, HttpStatus.ACCEPTED);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<byte[]> safeFailure(
            RuntimeException failure, HttpServletRequest request) {
        UUID correlation;
        try { correlation = UUID.fromString(request.getHeader("X-Correlation-ID")); }
        catch (RuntimeException invalid) { correlation = UUID.randomUUID(); }
        var safe = Hrm013SafeErrorV1.map(failure, correlation);
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.set("X-Correlation-ID", correlation.toString());
        if (safe.retryAfterSeconds() != null) {
            headers.set("Retry-After", safe.retryAfterSeconds().toString());
        }
        if (!safe.emptyBody()) headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(safe.bodyBytes(), headers,
                HttpStatus.valueOf(safe.httpStatus()));
    }
}
