package com.dwp.services.auth.config;

import com.dwp.services.auth.service.OidcService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public final class OidcStepUpProductionReadiness implements ApplicationRunner {

    private final OidcService oidcService;
    private final String environment;
    private final String requiredAcr;

    public OidcStepUpProductionReadiness(
            OidcService oidcService,
            @Value("${dwp.environment:${DWP_ENVIRONMENT:local}}") String environment,
            @Value("${dwp.auth.step-up.required-acr:}") String requiredAcr) {
        this.oidcService = oidcService;
        this.environment = environment == null ? "local" : environment;
        this.requiredAcr = requiredAcr == null ? "" : requiredAcr.trim();
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String normalized = environment.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("prod") && !normalized.equals("production")) return;
        List<String> incomplete = oidcService.incompleteConfiguredStepUpProviderKeys(requiredAcr);
        if (!incomplete.isEmpty()) {
            throw new IllegalStateException(
                    "Production OIDC step-up provider configuration is incomplete: "
                            + String.join(",", incomplete));
        }
    }
}
