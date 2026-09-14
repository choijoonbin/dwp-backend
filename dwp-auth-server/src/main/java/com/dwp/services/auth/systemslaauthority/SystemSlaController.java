package com.dwp.services.auth.systemslaauthority;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
public final class SystemSlaController {
    public static final String PROOF_ATTRIBUTE = "com.dwp.services.auth.systemslaauthority.SystemSlaController.proof";
    private final SystemSlaAuthorityService service;
    public SystemSlaController(SystemSlaAuthorityService service) { this.service = service; }
    @PostMapping(value = SystemSlaProtocol.PATH, produces = "application/json")
    public Response evaluate(@RequestAttribute(PROOF_ATTRIBUTE) SystemSlaProofVerifier.Verified proof) { return new Response(service.evaluate(proof)); }
    public record Response(String sourceAttestation) { }
}
