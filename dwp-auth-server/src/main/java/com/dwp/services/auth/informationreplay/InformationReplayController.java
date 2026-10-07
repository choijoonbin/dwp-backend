package com.dwp.services.auth.informationreplay;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class InformationReplayController {
    private final InformationReplayAuthorityService service;
    public InformationReplayController(InformationReplayAuthorityService service) { this.service = service; }
    @PostMapping(value = InformationReplayProtocol.PATH, consumes = "application/json", produces = "application/json")
    @Operation(operationId = "evaluate_1")
    public Map<String, String> evaluate(@RequestBody byte[] body, @RequestHeader(InformationReplayProtocol.HEADER) String token) {
        return Map.of("attestation", service.evaluate(body, token));
    }
}
