package com.dwp.services.payroll.foundation;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
        name = "dwp.hris.payroll-foundation.synthetic-bootstrap.enabled",
        havingValue = "true")
final class LocalSyntheticPayrollFoundationBootstrapController {
    private final LocalSyntheticPayrollFoundationBootstrapService service;

    LocalSyntheticPayrollFoundationBootstrapController(
            LocalSyntheticPayrollFoundationBootstrapService service) {
        this.service = service;
    }

    @PostMapping(LocalSyntheticPayrollFoundationBootstrapFilter.PATH)
    LocalSyntheticPayrollFoundationBootstrapDtos.BootstrapResponse bootstrap(
            @Valid @RequestBody
                    LocalSyntheticPayrollFoundationBootstrapDtos.BootstrapRequest request) {
        return service.bootstrap(request);
    }
}
