package com.dwp.services.auth.identity;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/identity/v1")
public class WorkforceIdentitySyncController {

    private final WorkforceIdentitySyncService service;
    private final IdentitySubjectLookupService subjects;

    public WorkforceIdentitySyncController(
            WorkforceIdentitySyncService service,
            IdentitySubjectLookupService subjects) {
        this.service = service;
        this.subjects = subjects;
    }

    @PostMapping("/workforce-events")
    public WorkforceIdentityDtos.SyncResult synchronize(
            @Valid @RequestBody WorkforceIdentityDtos.WorkforceIdentityEvent event) {
        return service.synchronize(event);
    }

    @GetMapping("/tenants/{tenantId}/users/{userId}")
    public IdentitySubjectLookupService.Subject subject(
            @PathVariable Long tenantId,
            @PathVariable Long userId) {
        return subjects.subject(tenantId, userId);
    }

    @GetMapping("/tenants/{tenantId}/users")
    public List<IdentitySubjectLookupService.DirectorySubject> searchSubjects(
            @PathVariable Long tenantId,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "10") int limit) {
        return subjects.search(tenantId, query, limit);
    }
}
