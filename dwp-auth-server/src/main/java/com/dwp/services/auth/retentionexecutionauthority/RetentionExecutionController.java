package com.dwp.services.auth.retentionexecutionauthority;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.*;

@Hidden @RestController
public final class RetentionExecutionController {
    public static final String PROOF_ATTRIBUTE="com.dwp.services.auth.retentionexecutionauthority.verified";
    private final RetentionExecutionAuthorityService service;
    public RetentionExecutionController(RetentionExecutionAuthorityService service) {this.service=service;}
    @PostMapping(value=RetentionExecutionProtocol.PATH,produces="application/json")
    public RetentionExecutionProtocol.SignedAuthorization evaluate(@RequestAttribute(PROOF_ATTRIBUTE) RetentionExecutionProofVerifier.Verified proof) {
        return service.evaluate(proof);
    }
}
