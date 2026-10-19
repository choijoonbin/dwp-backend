package com.dwp.services.people.hris.contracts.hrm011;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal-only transport consuming identity solely from the verified typed context. */
@RestController
@ConditionalOnProperty(prefix = "dwp.hris.contract-compensation-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class Hrm011WorkloadController {
    private final Hrm011WorkloadIngress ingress;

    Hrm011WorkloadController(Hrm011WorkloadIngress ingress) {
        this.ingress = ingress;
    }

    @GetMapping(value = Hrm011WorkloadIngressFilter.COMPENSATION_PATH,
            produces = MediaType.APPLICATION_JSON_VALUE)
    Hrm011WorkloadIngress.SnapshotPage compensation(HttpServletRequest request) {
        return ingress.compensationSnapshots(Hrm011WorkloadRequestContext.require(request));
    }

    @GetMapping(value = Hrm011WorkloadIngressFilter.PAYROLL_FACTS_PATH,
            produces = MediaType.APPLICATION_JSON_VALUE)
    Hrm011WorkloadIngress.SnapshotPage payrollFacts(HttpServletRequest request) {
        return ingress.payrollWorkerFacts(Hrm011WorkloadRequestContext.require(request));
    }
}
