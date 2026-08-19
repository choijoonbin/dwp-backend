package com.dwp.services.platform.media;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
class TenantMediaStorageReadiness {

    private final String environment;
    private final String storage;

    TenantMediaStorageReadiness(
            @Value("${DWP_ENVIRONMENT:local}") String environment,
            @Value("${dwp.platform.assets.storage:local}") String storage) {
        this.environment = environment;
        this.storage = storage;
    }

    @PostConstruct
    void validate() {
        String normalizedEnvironment = environment.toLowerCase(Locale.ROOT);
        if (("production".equals(normalizedEnvironment) || "prod".equals(normalizedEnvironment))
                && !"s3".equalsIgnoreCase(storage)) {
            throw new IllegalStateException(
                    "Production tenant media requires dwp.platform.assets.storage=s3.");
        }
    }
}
