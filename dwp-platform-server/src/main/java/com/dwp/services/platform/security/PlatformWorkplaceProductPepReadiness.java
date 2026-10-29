package com.dwp.services.platform.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Prevents production Workplace routes from starting with exact owner authorization bypassed. */
@Component
final class PlatformWorkplaceProductPepReadiness {

    private final String environment;
    private final boolean enabled;

    PlatformWorkplaceProductPepReadiness(
            @Value("${DWP_ENVIRONMENT:local}") String environment,
            @Value("${dwp.platform.product-authorization-workplace-v4-enabled:false}")
            boolean enabled) {
        this.environment = environment;
        this.enabled = enabled;
    }

    @PostConstruct
    void validate() {
        String normalized = environment == null
                ? "" : environment.trim().toLowerCase(Locale.ROOT);
        if (("production".equals(normalized) || "prod".equals(normalized)) && !enabled) {
            throw new IllegalStateException(
                    "Production Workplace requires "
                            + "dwp.platform.product-authorization-workplace-v4-enabled=true.");
        }
    }
}
