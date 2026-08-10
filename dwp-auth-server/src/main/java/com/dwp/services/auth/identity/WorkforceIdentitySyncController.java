package com.dwp.services.auth.identity;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/identity/v1")
public class WorkforceIdentitySyncController {

    private final WorkforceIdentitySyncService service;

    public WorkforceIdentitySyncController(WorkforceIdentitySyncService service) {
        this.service = service;
    }

    @PostMapping("/workforce-events")
    public WorkforceIdentityDtos.SyncResult synchronize(
            @Valid @RequestBody WorkforceIdentityDtos.WorkforceIdentityEvent event) {
        return service.synchronize(event);
    }
}
