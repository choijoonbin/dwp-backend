package com.dwp.services.people.security;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.dwp.services.people.security.HrisNavigationLegalEntityContextDtos.*;

@RestController
@RequestMapping("/internal/people/v1/hris/navigation/legal-entity-context")
@ConditionalOnProperty(
        name = "dwp.hris.navigation-legal-entity-owner.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class HrisNavigationLegalEntityContextController {

    private static final String PLATFORM = "dwp-platform-server";
    private final HrisNavigationLegalEntityContextService service;

    public HrisNavigationLegalEntityContextController(
            HrisNavigationLegalEntityContextService service) {
        this.service = service;
    }

    @PostMapping("/verify")
    public VerifyResult verify(
            @RequestHeader("X-DWP-Service-Identity") String serviceIdentity,
            @Valid @RequestBody VerifyRequest request) {
        if (!PLATFORM.equals(serviceIdentity)) {
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.UNAUTHORIZED,
                    "Trusted Platform service identity is required.");
        }
        return service.verify(request);
    }
}
