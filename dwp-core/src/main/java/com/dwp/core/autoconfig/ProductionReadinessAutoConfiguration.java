package com.dwp.core.autoconfig;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@AutoConfiguration
public class ProductionReadinessAutoConfiguration {

    private static final String LOCAL_JWT_SECRET =
            "your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256";

    @Bean
    ApplicationRunner dwpProductionReadinessGuard(Environment environment) {
        return ignored -> {
            if (!production(environment)) return;
            String service = environment.getProperty("spring.application.name", "unknown");
            List<String> failures = new ArrayList<>();
            requireFalse(environment, failures, "otel.sdk.disabled");
            requireFalse(environment, failures, "springdoc.api-docs.enabled");
            requireUrl(environment, failures, "otel.exporter.otlp.endpoint", false,
                    "http://localhost:4318");
            requireUrl(environment, failures, "dwp.audit.collector-url", false);
            requireSecret(environment, failures, "dwp.audit.ingest-token");
            requireTrue(environment, failures, "dwp.observability.api-history.enabled");
            requireUrl(environment, failures, "dwp.observability.api-history.collector-url", false);
            requireSecret(environment, failures, "dwp.observability.api-history.ingest-token");
            requireSecret(environment, failures, "dwp.observability.api-history.privacy-hash-secret");
            requireEventTransportWhenEnabled(environment, failures);
            switch (service) {
                case "dwp-auth-server" -> {
                    requireSecret(environment, failures, "jwt.secret", LOCAL_JWT_SECRET);
                    requireTrue(environment, failures, "dwp.security.session.cookie-secure");
                    requireSecret(environment, failures, "dwp.identity-sync.token");
                    requireSecret(environment, failures, "dwp.provider.provisioning-token");
                    requireSecret(environment, failures, "dwp.scim.cursor-secret",
                            "local-development-scim-cursor-secret-change-me");
                    requireFalse(environment, failures, "dwp.auth.oidc.allow-unlisted-hosts");
                    requireUrl(environment, failures, "dwp.scim.base-url", true,
                            "http://localhost:8080/scim/v2");
                    requireUrl(environment, failures, "sso.callback-url", true,
                            "http://localhost:4200/auth/oidc/callback");
                }
                case "dwp-platform-server" -> {
                    requireSecret(environment, failures, "dwp.platform.service-token");
                    requireSecret(environment, failures, "dwp.platform.runtime-service-token");
                    requireSecret(environment, failures, "dwp.identity-sync.token");
                    requireSecret(environment, failures, "dwp.platform.api-history.cursor-secret");
                    requireSecret(environment, failures, "dwp.platform.audit.integrity-secret");
                    requireSecret(environment, failures, "dwp.platform.productivity.data-key");
                }
                case "dwp-people-server" -> {
                        requireSecret(environment, failures, "dwp.people.service-token");
                    requireSecret(environment, failures, "dwp.people.cursor-secret");
                    requireFalse(environment, failures, "dwp.people.hris.allow-unlisted-hosts");
                }
                case "dwp-provider-server" -> {
                    requireSecret(environment, failures, "dwp.provider.service-token");
                    requireSecret(environment, failures, "dwp.provider.provisioning-token");
                    requireSecret(environment, failures, "dwp.provider.support-validation-token");
                    requireTrue(environment, failures, "dwp.provider.support-cookie-secure");
                }
                case "dwp-approval-server" ->
                        requireSecret(environment, failures, "dwp.approval.service-token");
                default -> failures.add("unsupported production service identity: " + service);
            }
            if (!failures.isEmpty()) {
                throw new IllegalStateException(
                        "Production readiness checks failed for " + service + ": "
                                + String.join(", ", failures));
            }
        };
    }

    private boolean production(Environment environment) {
        String value = environment.getProperty("dwp.environment",
                environment.getProperty("DWP_ENVIRONMENT", "local"));
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("prod") || normalized.equals("production");
    }

    private void requireSecret(
            Environment environment,
            List<String> failures,
            String property,
            String... forbiddenValues) {
        String value = environment.getProperty(property, "").trim();
        if (value.length() < 24) {
            failures.add(property + " must contain at least 24 characters");
            return;
        }
        for (String forbidden : forbiddenValues) {
            if (value.equals(forbidden)) failures.add(property + " uses a local default");
        }
    }

    private void requireTrue(Environment environment, List<String> failures, String property) {
        if (!environment.getProperty(property, Boolean.class, false)) {
            failures.add(property + " must be true");
        }
    }

    private void requireFalse(Environment environment, List<String> failures, String property) {
        if (environment.getProperty(property, Boolean.class, true)) {
            failures.add(property + " must be false");
        }
    }

    private void requireEventTransportWhenEnabled(
            Environment environment,
            List<String> failures) {
        if (!environment.getProperty("dwp.events.transport-enabled", Boolean.class, false)) return;
        String transport = environment.getProperty("dwp.events.transport", "").trim();
        if (!transport.equals("kafka")) {
            failures.add("dwp.events.transport must be kafka when transport is enabled");
        }
        String brokers = environment.getProperty("spring.kafka.bootstrap-servers", "").trim();
        if (brokers.isBlank() || brokers.equals("localhost:9092")) {
            failures.add("spring.kafka.bootstrap-servers must use an explicit production broker");
        }
    }

    private void requireUrl(
            Environment environment,
            List<String> failures,
            String property,
            boolean httpsOnly,
            String... forbiddenValues) {
        String value = environment.getProperty(property, "").trim();
        try {
            URI uri = URI.create(value);
            boolean schemeAllowed = httpsOnly
                    ? "https".equalsIgnoreCase(uri.getScheme())
                    : "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            if (!schemeAllowed || uri.getHost() == null || uri.getUserInfo() != null) {
                failures.add(property + " must be a valid " + (httpsOnly ? "HTTPS" : "HTTP(S)") + " URL");
                return;
            }
        } catch (IllegalArgumentException exception) {
            failures.add(property + " must be a valid " + (httpsOnly ? "HTTPS" : "HTTP(S)") + " URL");
            return;
        }
        for (String forbidden : forbiddenValues) {
            if (value.equals(forbidden)) failures.add(property + " uses a local default");
        }
    }
}
