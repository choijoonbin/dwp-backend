package com.dwp.services.platform.mail;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Prevents production Mail routes from starting with owner-service authorization bypassed. */
@Component
class MailProductSurfacePepReadiness {

    private final String environment;
    private final boolean enabled;

    MailProductSurfacePepReadiness(
            @Value("${DWP_ENVIRONMENT:local}") String environment,
            @Value("${dwp.platform.product-authorization-mail-v4-enabled:false}")
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
                    "Production Mail requires "
                            + "dwp.platform.product-authorization-mail-v4-enabled=true.");
        }
    }
}
