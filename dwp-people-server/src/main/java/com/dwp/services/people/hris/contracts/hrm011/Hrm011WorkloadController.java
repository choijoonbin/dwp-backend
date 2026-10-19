package com.dwp.services.people.hris.contracts.hrm011;

import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Internal-only exact transport. PeopleSecurityFilter admission is intentionally deferred. */
@RestController
@RequestMapping(value = "/internal/hris-people/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "dwp.hris.contract-compensation-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class Hrm011WorkloadController {
    private static final Set<String> QUERY = Set.of(
            "purposeCode", "asOf", "projection", "limit", "cursor");
    private final Hrm011WorkloadIngress ingress;

    Hrm011WorkloadController(Hrm011WorkloadIngress ingress) {
        this.ingress = ingress;
    }

    @GetMapping("/compensation-basis-snapshots")
    Hrm011WorkloadIngress.SnapshotPage compensation(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("X-Correlation-ID") String correlation,
            @RequestHeader("X-DWP-Workload-SPIFFE-ID") String spiffe,
            @RequestHeader("X-DWP-TLS-Protocol") String tls,
            @RequestHeader(value = "X-Tenant-ID", required = false) String forbiddenTenant,
            @RequestParam String purposeCode,
            @RequestParam Instant asOf,
            @RequestParam String projection,
            @RequestParam int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam MultiValueMap<String, String> parameters) {
        requireTransport(parameters, forbiddenTenant);
        return ingress.compensationSnapshots(
                evidence(authorization, correlation, spiffe, tls),
                new Hrm011WorkloadIngress.SnapshotRequest(
                        purposeCode, asOf, projection, limit, cursor));
    }

    @GetMapping("/payroll-worker-fact-snapshots")
    Hrm011WorkloadIngress.SnapshotPage payrollFacts(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("X-Correlation-ID") String correlation,
            @RequestHeader("X-DWP-Workload-SPIFFE-ID") String spiffe,
            @RequestHeader("X-DWP-TLS-Protocol") String tls,
            @RequestHeader(value = "X-Tenant-ID", required = false) String forbiddenTenant,
            @RequestParam String purposeCode,
            @RequestParam Instant asOf,
            @RequestParam String projection,
            @RequestParam int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam MultiValueMap<String, String> parameters) {
        requireTransport(parameters, forbiddenTenant);
        return ingress.payrollWorkerFacts(
                evidence(authorization, correlation, spiffe, tls),
                new Hrm011WorkloadIngress.SnapshotRequest(
                        purposeCode, asOf, projection, limit, cursor));
    }

    private void requireTransport(
            MultiValueMap<String, String> parameters, String forbiddenTenant) {
        if (forbiddenTenant != null || !QUERY.containsAll(parameters.keySet())) {
            throw EmploymentContractException.invalid(
                    "Caller tenant override and unknown workload fields are forbidden.");
        }
        for (String required : Set.of("purposeCode", "asOf", "projection", "limit")) {
            if (parameters.getOrDefault(required, List.of()).size() != 1) {
                throw EmploymentContractException.invalid(
                        required + " must occur exactly once.");
            }
        }
        if (parameters.getOrDefault("cursor", List.of()).size() > 1) {
            throw EmploymentContractException.invalid("cursor must occur at most once.");
        }
    }

    private Hrm011WorkloadIngress.WorkloadEvidence evidence(
            String authorization, String correlation, String spiffe, String tls) {
        if (authorization == null
                || !authorization.matches("Bearer [A-Za-z0-9._~+/=-]+")) {
            throw EmploymentContractException.denied(
                    "A workload bearer token is required.");
        }
        try {
            return new Hrm011WorkloadIngress.WorkloadEvidence(
                    authorization.substring(7), spiffe, tls, UUID.fromString(correlation));
        } catch (RuntimeException exception) {
            throw EmploymentContractException.invalid(
                    "X-Correlation-ID must be a canonical UUID.");
        }
    }
}
