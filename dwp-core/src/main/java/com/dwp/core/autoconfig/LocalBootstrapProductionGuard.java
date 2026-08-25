package com.dwp.core.autoconfig;

import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class LocalBootstrapProductionGuard {

    private static final String LOCAL_ACTIVATION_PROPERTY =
            "dwp.product-authorization.local-pilot-activation.enabled";
    private static final String LOCAL_ACTIVATION_ENV =
            "DWP_PRODUCT_AUTHORIZATION_LOCAL_PILOT_ACTIVATION_ENABLED";

    private LocalBootstrapProductionGuard() {
    }

    static boolean production(Environment environment) {
        String value = environment.getProperty(
                "dwp.environment",
                environment.getProperty("DWP_ENVIRONMENT", "local"));
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("prod") || normalized.equals("production");
    }

    static boolean exactLocal(Environment environment) {
        return "local".equals(environment.getProperty("DWP_ENVIRONMENT", "")
                .trim().toLowerCase(Locale.ROOT));
    }

    static List<String> violations(Environment environment) {
        List<String> failures = new ArrayList<>();
        if (environment.getProperty(LOCAL_ACTIVATION_PROPERTY, Boolean.class, false)
                || environment.getProperty(LOCAL_ACTIVATION_ENV, Boolean.class, false)) {
            failures.add(LOCAL_ACTIVATION_PROPERTY + " must be false in production");
        }
        if (localSeedLocation(environment.getProperty("spring.flyway.locations", ""))
                || localSeedLocation(environment.getProperty(
                        "DWP_PROVIDER_FLYWAY_LOCATIONS", ""))) {
            failures.add("Flyway locations must not contain db/local-seed in production");
        }
        return List.copyOf(failures);
    }

    private static boolean localSeedLocation(String locations) {
        String normalized = locations.toLowerCase(Locale.ROOT).replace('\\', '/');
        return normalized.contains("db/local-seed");
    }
}
