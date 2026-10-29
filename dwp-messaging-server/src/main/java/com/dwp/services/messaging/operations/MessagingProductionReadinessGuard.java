package com.dwp.services.messaging.operations;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rejects insecure Messaging provider configuration in production environments. */
@Component
public final class MessagingProductionReadinessGuard implements ApplicationRunner {

    private final Environment environment;

    public MessagingProductionReadinessGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!production()) return;

        List<String> failures = new ArrayList<>();
        requireExact(failures, "dwp.messaging.attachments.storage", "s3");
        requireExact(failures, "dwp.messaging.attachments.scanner", "clamav");
        requireNonBlank(failures, "dwp.messaging.attachments.s3.bucket");
        requireProductionRegion(failures, "dwp.messaging.attachments.s3.region");
        requireProductionEndpoint(failures, "dwp.messaging.attachments.s3.endpoint");
        requireFalse(failures, "dwp.messaging.attachments.s3.path-style-access");
        requireProductionHost(failures, "dwp.messaging.attachments.clamav-host");
        requireTrue(failures, "dwp.messaging.presence.enabled");
        requireTrue(failures, "dwp.messaging.realtime.redis-enabled");
        requireProductionHost(failures, "spring.data.redis.host");
        requireProductionSecret(failures, "spring.data.redis.password");
        requireTrue(failures, "spring.data.redis.ssl.enabled");

        if ("ENFORCING".equals(normalized("dwp.messaging.retention.mode"))) {
            requireTrue(failures, "dwp.messaging.retention.hold-authority-ready");
            requireTrue(failures, "dwp.messaging.retention.downstream-lifecycle-ready");
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Messaging production readiness validation failed: "
                            + String.join("; ", failures));
        }
    }

    private boolean production() {
        for (String profile : environment.getActiveProfiles()) {
            String normalizedProfile = profile.trim().toLowerCase(Locale.ROOT);
            if ("prod".equals(normalizedProfile) || "production".equals(normalizedProfile)) {
                return true;
            }
        }
        return productionValue(environment.getProperty("dwp.environment", ""))
                || productionValue(environment.getProperty("DWP_ENVIRONMENT", ""));
    }

    private boolean productionValue(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return "prod".equals(normalized) || "production".equals(normalized);
    }

    private void requireExact(List<String> failures, String property, String expected) {
        if (!expected.equals(environment.getProperty(property, "").trim())) {
            failures.add(property + " must be " + expected);
        }
    }

    private void requireNonBlank(List<String> failures, String property) {
        if (environment.getProperty(property, "").isBlank()) {
            failures.add(property + " must be configured");
        }
    }

    private void requireTrue(List<String> failures, String property) {
        if (!environment.getProperty(property, Boolean.class, false)) {
            failures.add(property + " must be true");
        }
    }

    private void requireProductionSecret(List<String> failures, String property) {
        String raw;
        try {
            raw = environment.getProperty(property, "");
        } catch (RuntimeException exception) {
            failures.add(property
                    + " must be a strong non-placeholder production secret"
                    + " (32..512 characters)");
            return;
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        boolean placeholder = raw.contains("${")
                || normalized.contains("placeholder")
                || normalized.contains("change-me")
                || normalized.contains("changeme")
                || normalized.contains("changeit")
                || normalized.contains("replace-me")
                || normalized.contains("dummy")
                || normalized.contains("fixture")
                || normalized.contains("example")
                || normalized.startsWith("test-")
                || normalized.startsWith("dev-")
                || normalized.startsWith("local-")
                || List.of(
                        "password", "redis-password", "your-redis-password",
                        "provided-by-secret-manager", "secret", "redis-secret",
                        "your-redis-secret", "default", "local", "test", "example")
                .contains(normalized);
        boolean invalidCharacter = raw.chars().anyMatch(Character::isWhitespace)
                || raw.chars().anyMatch(Character::isISOControl);
        if (raw.length() < 32
                || raw.length() > 512
                || raw.chars().distinct().count() < 8
                || invalidCharacter
                || !raw.equals(raw.strip())
                || placeholder) {
            failures.add(property
                    + " must be a strong non-placeholder production secret"
                    + " (32..512 characters)");
        }
    }

    private void requireFalse(List<String> failures, String property) {
        if (environment.getProperty(property, Boolean.class, false)) {
            failures.add(property + " must be false");
        }
    }

    private void requireProductionRegion(List<String> failures, String property) {
        String region = environment.getProperty(property, "").trim();
        if (!region.matches("[a-z]{2,}(?:-[a-z0-9]+)+-[1-9]\\d*")) {
            failures.add(property + " must be an explicit production AWS region");
        }
    }

    private void requireProductionEndpoint(List<String> failures, String property) {
        String endpoint = environment.getProperty(property, "").trim();
        if (endpoint.isBlank()) return;
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || unsafeEndpointHost(uri.getHost())) {
                failures.add(property + " must be a public HTTPS production endpoint");
            }
        } catch (IllegalArgumentException exception) {
            failures.add(property + " must be a public HTTPS production endpoint");
        }
    }

    private boolean unsafeEndpointHost(String rawHost) {
        String host = rawHost.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isBlank()
                || "local".equals(host)
                || "localhost".equals(host)
                || host.endsWith(".local")
                || host.endsWith(".localhost")
                || host.endsWith(".test")
                || host.endsWith(".internal")
                || host.endsWith(".corp")
                || host.endsWith(".mail")
                || host.endsWith(".lan")
                || host.endsWith(".home")
                || host.endsWith(".arpa")
                || host.endsWith(".invalid")
                || host.endsWith(".example")
                || host.endsWith(".localdomain")
                || host.endsWith(".onion")
                || host.endsWith(".alt")
                || host.endsWith(".svc")
                || documentationDomain(host)) {
            return true;
        }
        return !publicDnsName(host);
    }

    private boolean documentationDomain(String host) {
        return host.equals("example.com")
                || host.endsWith(".example.com")
                || host.equals("example.net")
                || host.endsWith(".example.net")
                || host.equals("example.org")
                || host.endsWith(".example.org");
    }

    private boolean publicDnsName(String host) {
        if (host.contains(":")) return false;
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) return false;
        for (String label : labels) {
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                return false;
            }
        }
        String suffix = labels[labels.length - 1];
        return suffix.matches("[a-z]{2,63}")
                || suffix.matches("xn--[a-z0-9](?:[a-z0-9-]{0,57}[a-z0-9])");
    }

    private void requireProductionHost(List<String> failures, String property) {
        String host = environment.getProperty(property, "").trim().toLowerCase(Locale.ROOT);
        if (host.isBlank()
                || "local".equals(host)
                || "localhost".equals(host)
                || host.endsWith(".local")
                || host.endsWith(".localhost")
                || host.endsWith(".test")
                || "::1".equals(host)
                || "[::1]".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host)
                || host.contains(":")
                || host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            failures.add(property + " must be a non-local production DNS host");
        }
    }

    private String normalized(String property) {
        return environment.getProperty(property, "").trim().toUpperCase(Locale.ROOT);
    }
}
