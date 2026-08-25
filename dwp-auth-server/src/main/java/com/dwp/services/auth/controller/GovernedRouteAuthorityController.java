package com.dwp.services.auth.controller;

import com.dwp.services.auth.dto.GovernedRouteAuthorityDtos;
import com.dwp.services.auth.service.GovernedRouteAuthorityService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/auth/v1/governed-route-authority")
public class GovernedRouteAuthorityController {

    private final GovernedRouteAuthorityService service;

    public GovernedRouteAuthorityController(GovernedRouteAuthorityService service) {
        this.service = service;
    }

    @PostMapping("/evaluate")
    @Operation(operationId = "evaluateGovernedRouteAuthorityInternal")
    public GovernedRouteAuthorityDtos.AuthorityResult evaluate(
            @Valid @RequestBody GovernedRouteAuthorityDtos.EvaluateRequest request) {
        return service.evaluate(request);
    }
}
