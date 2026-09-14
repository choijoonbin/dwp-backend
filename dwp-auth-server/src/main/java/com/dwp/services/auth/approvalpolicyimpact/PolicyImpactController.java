package com.dwp.services.auth.approvalpolicyimpact;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
public final class PolicyImpactController {
    public static final String PROOF_ATTRIBUTE = "com.dwp.services.auth.approvalpolicyimpact.PolicyImpactController.proof";
    private final PolicyImpactAuthorityService service;
    public PolicyImpactController(PolicyImpactAuthorityService service) { this.service = service; }
    @PostMapping(value = PolicyImpactProtocol.PATH, produces = "application/json")
    public Response evaluate(@RequestAttribute(PROOF_ATTRIBUTE) PolicyImpactProofVerifier.Verified proof) {
        return new Response(service.evaluate(proof));
    }
    public record Response(String sourceAttestation) { }
}
