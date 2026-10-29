package com.dwp.services.notification.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fail-closed startup checks for environment-sensitive Notification controls. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class NotificationProductionConfigurationGuard implements ApplicationRunner {

    static final String EMBEDDED_PRODUCT_AUTHORIZATION_V4_STATUS = "DRAFT";
    private static final Set<String> KNOWN_ENVIRONMENTS = Set.of(
            "local", "development", "test", "staging", "prod", "production");

    private final Environment environment;

    public NotificationProductionConfigurationGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> failures = violations(environment);
        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Notification production configuration failed: "
                            + String.join("; ", failures));
        }
    }

    static List<String> violations(Environment environment) {
        List<String> failures = new ArrayList<>();
        String configured = environment.getProperty("dwp.environment", "");
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            failures.add("dwp.environment must be explicitly configured");
            return List.copyOf(failures);
        }
        if (!configured.equals(configured.trim()) || !KNOWN_ENVIRONMENTS.contains(normalized)) {
            failures.add("dwp.environment must be a canonical known environment");
            return List.copyOf(failures);
        }
        if (!Set.of("prod", "production").contains(normalized)) {
            return List.copyOf(failures);
        }

        requirePositiveDuration(
                environment.getProperty("dwp.notification.retention.default-ttl", ""),
                "production default notification retention TTL must be finite and positive",
                failures);
        if (!environment.getProperty(
                "dwp.notification.product-authorization-v4-enabled", Boolean.class, false)) {
            failures.add("product authorization v4 must be enabled in production");
        }
        if (!"ACTIVE".equals(environment.getProperty(
                "dwp.notification.product-authorization-v4-bundle-status", "").trim())) {
            failures.add("product authorization v4 bundle must be ACTIVE in production");
        }
        String revision = environment.getProperty(
                "dwp.notification.product-authorization-v4-rollout-revision", "");
        if (!revision.matches("rollout-[a-f0-9]{64}")) {
            failures.add("product authorization v4 rollout revision must be approved in production");
        }
        if (!"ACTIVE".equals(EMBEDDED_PRODUCT_AUTHORIZATION_V4_STATUS)) {
            failures.add(
                    "embedded product authorization v4 bundle remains DRAFT; production is blocked");
        }
        return List.copyOf(failures);
    }

    private static void requirePositiveDuration(
            String value,
            String message,
            List<String> failures) {
        try {
            Duration duration = Duration.parse(value);
            if (duration.isZero() || duration.isNegative()) failures.add(message);
        } catch (RuntimeException exception) {
            failures.add(message);
        }
    }
}
