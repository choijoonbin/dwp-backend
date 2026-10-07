package com.dwp.services.people.integration;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
        name = "dwp.hris.people-workforce.synthetic-bootstrap.enabled",
        havingValue = "true")
final class LocalSyntheticPeopleWorkforceBootstrapController {

    private final LocalSyntheticPeopleWorkforceBootstrapService service;

    LocalSyntheticPeopleWorkforceBootstrapController(
            LocalSyntheticPeopleWorkforceBootstrapService service) {
        this.service = service;
    }

    @PostMapping(LocalSyntheticPeopleWorkforceBootstrapFilter.PATH)
    LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapResponse bootstrap(
            @Valid @RequestBody
                    LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapRequest request) {
        return service.bootstrap(request);
    }
}
