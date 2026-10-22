package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** Exact owner endpoint for PEP-HRM-015. */
@RestController
@RequestMapping(value = Hrm012WorkforceIngestionContract.OWNER_PATH,
        produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "dwp.hris.data-quality-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class Hrm012WorkforceIngestionController {
    private final Hrm012WorkforceIngestionService service;

    Hrm012WorkforceIngestionController(Hrm012WorkforceIngestionService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Hrm012WorkforceIngestionDtos.Receipt> initiate(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("X-Correlation-ID") String correlationId,
            @RequestHeader(value = "X-Purpose-Code", required = false) String purpose,
            @RequestBody Hrm012WorkforceIngestionDtos.Command request) {
        UUID correlation;
        try {
            correlation = UUID.fromString(correlationId);
            if (!correlation.toString().equalsIgnoreCase(correlationId)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw Hrm012Problem.invalid("X-Correlation-ID must be a canonical UUID.");
        }
        Hrm012WorkforceIngestionContract.CommandHeaders headers =
                new Hrm012WorkforceIngestionContract.CommandHeaders(
                        authorization, idempotencyKey, ifMatch, correlation, purpose);
        Hrm012WorkforceIngestionContract.RunReceipt result =
                service.initiate(request.contract(), headers);
        Hrm012WorkforceIngestionDtos.Receipt body =
                Hrm012WorkforceIngestionDtos.Receipt.from(result);
        return ResponseEntity.accepted()
                .location(URI.create("/v1/hris/command-receipts/"
                        + result.receiptId()))
                .header("X-Correlation-ID", correlation.toString())
                .body(body);
    }
}
