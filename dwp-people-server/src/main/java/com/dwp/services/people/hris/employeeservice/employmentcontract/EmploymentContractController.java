package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.dwp.services.people.security.HcmStepUpHeaders;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Exact owner POST transport for PEP-HRM-011. */
@RestController
@RequestMapping(value = "/v1/hris", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = EmploymentContractRolloutGate.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class EmploymentContractController {
    private final EmploymentContractService service;

    EmploymentContractController(EmploymentContractService service) {
        this.service = service;
    }

    @PostMapping(value = "/employment-contracts",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CommandReceiptV1> issue(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("X-Correlation-ID") String correlationId,
            @RequestHeader(value = "X-Purpose-Code", required = false) String purpose,
            @RequestHeader(HcmStepUpHeaders.CHALLENGE) String challenge,
            @RequestHeader(HcmStepUpHeaders.DECISION_REVISION) String decisionRevision,
            @RequestHeader(HcmStepUpHeaders.EXPECTED_OBJECT_VERSION)
            String expectedVersionHeader,
            @Valid @RequestBody EmploymentContractDtos.IssueRequest request) {
        if (authorization == null
                || !authorization.matches("Bearer [A-Za-z0-9._~+/=-]+")) {
            throw EmploymentContractException.denied(
                    "A bearer access token is required.");
        }
        UUID correlation;
        try {
            correlation = UUID.fromString(correlationId);
            if (!correlation.toString().equalsIgnoreCase(correlationId)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw EmploymentContractException.invalid(
                    "X-Correlation-ID must be a canonical UUID.");
        }
        long expectedVersion = expectedVersion(expectedVersionHeader);
        EmploymentContractService.CommandResult result = service.issue(
                request, idempotencyKey, ifMatch, expectedVersion, correlation,
                purpose, new HcmStepUpHeaders(
                        challenge, idempotencyKey, decisionRevision, expectedVersion));
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        headers.set("X-Correlation-ID", correlation.toString());
        headers.set("X-Authorization-Decision-Revision", decisionRevision);
        headers.setETag('"' + Long.toString(result.entityVersion()) + '"');
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .headers(headers).body(result.receipt());
    }

    private long expectedVersion(String value) {
        if (value == null || !value.matches("^(?:0|[1-9][0-9]*)$")) {
            throw EmploymentContractException.invalid(
                    "X-DWP-Expected-Object-Version must be a canonical nonnegative integer.");
        }
        try {
            return Long.parseLong(value);
        } catch (RuntimeException exception) {
            throw EmploymentContractException.invalid(
                    "X-DWP-Expected-Object-Version exceeds the supported range.");
        }
    }
}
