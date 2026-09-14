package com.dwp.services.auth.workflowplanning;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@Hidden @RestController
public final class PlanningController {
    public static final String PROOF_ATTRIBUTE="com.dwp.services.auth.workflowplanning.PlanningController.proof";
    private final PlanningAuthorityService service;
    public PlanningController(PlanningAuthorityService service) {this.service=service;}
    @PostMapping(value=PlanningProtocol.PATH,produces="application/json")
    public Response evaluate(@RequestAttribute(PROOF_ATTRIBUTE) PlanningProofVerifier.Verified proof) {return new Response(service.evaluate(proof));}
    public record Response(String attestation) { }
}
