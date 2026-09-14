package com.dwp.services.auth.workflowruntime;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class WorkflowRuntimeController {
    private final WorkflowRuntimeAuthorityService service;
    public WorkflowRuntimeController(WorkflowRuntimeAuthorityService service) { this.service = service; }
    @PostMapping(value = WorkflowRuntimeProtocol.PATH, consumes = "application/json", produces = "application/json")
    public WorkflowRuntimeAttestationIssuer.Response resolve(
            @RequestHeader(WorkflowRuntimeProtocol.TOKEN_HEADER) String token, @RequestBody byte[] body) {
        return service.resolve(token, body);
    }
}
