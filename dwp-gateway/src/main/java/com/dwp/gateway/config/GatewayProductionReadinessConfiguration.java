package com.dwp.gateway.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Configuration
public class GatewayProductionReadinessConfiguration {

    @Bean
    ApplicationRunner gatewayProductionReadinessGuard(Environment environment) {
        return ignored -> {
            if (!production(environment)) return;
            List<String> failures = new ArrayList<>();
            requireSecret(environment, failures, "spring.data.redis.password");
            requireSecret(environment, failures, "dwp.agent.service-token");
            requireSecret(environment, failures, "dwp.platform.service-token");
            requireSecret(environment, failures, "dwp.people.service-token");
            requireSecret(environment, failures, "dwp.provider.service-token");
            requireSecret(environment, failures, "dwp.provider.support-validation-token");
            requireSecret(environment, failures, "dwp.approval.service-token");
            requireSecret(environment, failures, "dwp.space.service-token");
            requireSecret(environment, failures, "dwp.observability.api-history.ingest-token");
            requireSecret(environment, failures, "dwp.observability.api-history.privacy-hash-secret");
            requireUrl(environment, failures, "dwp.observability.api-history.collector-url",
                    "http://localhost:8002/internal/observability/api-history");
            requireUrl(environment, failures, "otel.exporter.otlp.endpoint",
                    "http://localhost:4318");
            requireProductionOrigin(environment, failures, "CORS_ALLOWED_ORIGIN_PATTERN");
            requireProductionOrigin(environment, failures, "CORS_ALLOWED_LOOPBACK_PATTERN");
            if (!environment.getProperty("dwp.observability.api-history.enabled", Boolean.class, false)) {
                failures.add("dwp.observability.api-history.enabled must be true");
            }
            if (!environment.getProperty("spring.data.redis.ssl.enabled", Boolean.class, false)) {
                failures.add("spring.data.redis.ssl.enabled must be true");
            }
            if (environment.getProperty("otel.sdk.disabled", Boolean.class, true)) {
                failures.add("otel.sdk.disabled must be false");
            }
            if (environment.getProperty("springdoc.api-docs.enabled", Boolean.class, true)) {
                failures.add("springdoc.api-docs.enabled must be false");
            }
            if (!failures.isEmpty()) {
                throw new IllegalStateException(
                        "Production readiness checks failed for dwp-gateway: "
                                + String.join(", ", failures));
            }
        };
    }

    private boolean production(Environment environment) {
        String value = environment.getProperty("dwp.environment",
                        environment.getProperty("DWP_ENVIRONMENT", "local"))
                .trim().toLowerCase(Locale.ROOT);
        return value.equals("prod") || value.equals("production");
    }

    private void requireSecret(Environment environment, List<String> failures, String property) {
        if (environment.getProperty(property, "").trim().length() < 24) {
            failures.add(property + " must contain at least 24 characters");
        }
    }

    private void requireUrl(
            Environment environment,
            List<String> failures,
            String property,
            String forbiddenValue) {
        String value = environment.getProperty(property, "").trim();
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                failures.add(property + " must be a valid HTTP(S) URL");
                return;
            }
        } catch (IllegalArgumentException exception) {
            failures.add(property + " must be a valid HTTP(S) URL");
            return;
        }
        if (value.equals(forbiddenValue)) failures.add(property + " uses a local default");
    }

    private void requireProductionOrigin(
            Environment environment,
            List<String> failures,
            String property) {
        String value = environment.getProperty(property, "").trim().toLowerCase(Locale.ROOT);
        if (!value.startsWith("https://") || value.contains("localhost")
                || value.contains("127.0.0.1") || value.equals("https://*")) {
            failures.add(property + " must declare an explicit production HTTPS origin");
        }
    }
}
