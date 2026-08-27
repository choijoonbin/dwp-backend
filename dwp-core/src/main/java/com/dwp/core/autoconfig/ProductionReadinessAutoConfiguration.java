package com.dwp.core.autoconfig;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@AutoConfiguration
public class ProductionReadinessAutoConfiguration {

    private static final String LOCAL_JWT_SECRET =
            "your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256";
    private static final String PRODUCT_SURFACE_ROLLOUT_TOPIC =
            "dwp.feature-rollout.decision.changed.v1";
    private static final String STEP_UP_FIXTURE_PUBLIC_KEY_SHA256 =
            "5b5a90532d7db5dc49d2f0db81acd3ec5a5582e04a8212796722a3da95abc8be";

    @Bean
    ApplicationRunner dwpProductionReadinessGuard(Environment environment) {
        return ignored -> {
            if (!production(environment)) return;
            String service = environment.getProperty("spring.application.name", "unknown");
            List<String> failures = new ArrayList<>();
            failures.addAll(LocalBootstrapProductionGuard.violations(environment));
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
                    requireSecret(environment, failures, "dwp.auth.product-surface-token");
                    requireProductionSecret(
                            environment, failures, "dwp.auth.approval-recovery-token");
                    requireTrue(environment, failures, "dwp.security.session.cookie-secure");
                    requireSecret(environment, failures, "dwp.identity-sync.token");
                    requireSecret(environment, failures, "dwp.provider.provisioning-token");
                    requireSecret(environment, failures, "dwp.scim.cursor-secret",
                            "local-development-scim-cursor-secret-change-me");
                    requireFalse(environment, failures, "dwp.auth.oidc.allow-unlisted-hosts");
                    requireRsaPrivateKey(environment, failures,
                            "dwp.auth.step-up.private-key-pem");
                    requireProductionUri(environment, failures, "dwp.auth.step-up.issuer");
                    requireKeyId(environment, failures, "dwp.auth.step-up.key-id");
                    requireAcr(environment, failures, "dwp.auth.step-up.required-acr");
                    requireAudiences(environment, failures,
                            "dwp.auth.step-up.allowed-audiences");
                    requireLongRange(environment, failures,
                            "dwp.auth.step-up.maximum-authentication-age-seconds", 60, 3600);
                    requireLongRange(environment, failures,
                            "dwp.auth.step-up.challenge-ttl-seconds", 1, 900);
                    requireLongRange(environment, failures,
                            "dwp.auth.step-up.assurance-clock-skew-seconds", 0, 60);
                    requireHostAllowlist(environment, failures, "dwp.auth.oidc.allowed-hosts");
                    Set<String> callbackHosts = requireHostAllowlist(
                            environment, failures, "dwp.auth.oidc.allowed-callback-hosts");
                    requireUrl(environment, failures, "dwp.scim.base-url", true,
                            "http://localhost:8080/scim/v2");
                    requireUrl(environment, failures, "sso.callback-url", true,
                            "http://localhost:4200/auth/oidc/callback");
                    requireCallback(environment, failures, "sso.callback-url", callbackHosts);
                }
                case "dwp-platform-server" -> {
                    requireSecret(environment, failures, "dwp.platform.service-token");
                    requireSecret(environment, failures, "dwp.platform.runtime-service-token");
                    requireSecret(environment, failures, "dwp.identity-sync.token");
                    requireSecret(environment, failures, "dwp.platform.api-history.cursor-secret");
                    requireSecret(environment, failures, "dwp.platform.audit.integrity-secret");
                    requireSecret(environment, failures, "dwp.platform.productivity.data-key");
                    requireTrue(environment, failures,
                            "dwp.platform.product-authorization-approvals-v2-enabled");
                    requireTrueWhenEnabled(
                            environment,
                            failures,
                            "dwp.platform.product-surface-telemetry.collection-enabled",
                            "dwp.platform.product-surface-telemetry.maintenance-enabled");
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
                    requireFalse(environment, failures,
                            "dwp.provider.local-approval-fixtures-enabled");
                    requireTrue(environment, failures,
                            "dwp.provider.product-surface-rollout.relay-enabled");
                    requireTrue(environment, failures,
                            "dwp.provider.product-surface-rollout.publisher-enabled");
                    requireExact(environment, failures,
                            "dwp.provider.product-surface-rollout.topic",
                            PRODUCT_SURFACE_ROLLOUT_TOPIC);
                    requireProductionKafka(environment, failures);
                }
                case "dwp-approval-server" -> {
                    requireSecret(environment, failures, "dwp.approval.service-token");
                    requireSecret(environment, failures, "dwp.approval.runtime-service-token");
                    requireTrue(environment, failures,
                            "dwp.approval.product-authorization-v2-enabled");
                    requireRsaPublicKey(environment, failures,
                            "dwp.approval.step-up.public-key-pem");
                    requireProductionUri(environment, failures,
                            "dwp.approval.step-up.issuer");
                    requireExact(environment, failures,
                            "dwp.approval.step-up.audience", "dwp-approval-server");
                    requireKeyId(environment, failures, "dwp.approval.step-up.key-id");
                    requireAcr(environment, failures, "dwp.approval.step-up.required-acr");
                    requireLongRange(environment, failures,
                            "dwp.approval.step-up.maximum-authentication-age-seconds", 60, 3600);
                    requireLongRange(environment, failures,
                            "dwp.approval.step-up.maximum-challenge-ttl-seconds", 1, 900);
                }
                case "dwp-messaging-server" ->
                    requireSecret(environment, failures, "dwp.messaging.service-token");
                case "dwp-space-server" -> {
                    requireProductionSecret(
                            environment, failures, "dwp.space.service-token");
                    requireProductionSecret(
                            environment, failures, "dwp.space.identity-sync-token");
                    requireTrue(
                            environment, failures, "dwp.space.entitlement-sync-enabled");
                    requireProductionEndpoint(
                            environment, failures, "dwp.services.auth-url", "https");
                }
                case "dwp-notification-server" -> {
                    requireProductionSecret(
                            environment, failures, "dwp.notification.service-token");
                    requireProductionSecret(
                            environment, failures, "dwp.notification.cursor-secret");
                    requireExact(
                            environment, failures,
                            "dwp.notification.gateway-source", "dwp-gateway");
                    requireBoundServiceSecrets(
                            environment,
                            failures,
                            "dwp.notification.allowed-producers",
                            "dwp.notification.producer-tokens",
                            "dwp.notification.service-token");
                    requireTrue(
                            environment, failures,
                            "dwp.notification.realtime.redis-enabled");
                    requireProductionHost(environment, failures, "spring.data.redis.host");
                    requireProductionSecret(
                            environment, failures, "spring.data.redis.password");
                    requireTrue(environment, failures, "spring.data.redis.ssl.enabled");
                    requireTrue(environment, failures, "dwp.notification.outbox.enabled");
                    requireFalse(
                            environment, failures,
                            "dwp.notification.outbox.provision-topic");
                    requireTrue(
                            environment, failures,
                            "dwp.notification.domain-events.enabled");
                    requireTrue(environment, failures, "dwp.notification.retention.enabled");
                    requireTrue(
                            environment, failures,
                            "dwp.notification.reconciliation.enabled");
                    requireProductionKafka(environment, failures);
                }
                case "dwp-meeting-server" -> {
                    requireSecret(environment, failures, "dwp.meeting.service-token");
                    requireExact(environment, failures, "dwp.meeting.provider", "livekit");
                    requireProductionEndpoint(
                            environment, failures, "dwp.meeting.livekit.client-url", "wss");
                    requireProductionEndpoint(
                            environment, failures, "dwp.meeting.livekit.api-url", "https");
                    requireCredential(environment, failures, "dwp.meeting.livekit.api-key", 8);
                    requireSecret(environment, failures, "dwp.meeting.livekit.api-secret");
                    requireDurationRange(
                            environment, failures, "dwp.meeting.token-ttl", 60, 600);
                    requireLongRange(
                            environment, failures, "dwp.meeting.join-code-length", 10, 16);
                    requireExact(
                            environment, failures, "dwp.meeting.recording-policy", "NEVER");
                }
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

    private void requireProductionSecret(
            Environment environment,
            List<String> failures,
            String property) {
        String raw = environment.getProperty(property, "");
        if (!productionSecret(raw)) {
            failures.add(property
                    + " must be a strong non-placeholder dedicated secret (32..512 characters)");
        }
    }

    private boolean productionSecret(String raw) {
        String value = raw.strip();
        String normalized = value.toLowerCase(Locale.ROOT);
        boolean placeholder = normalized.contains("placeholder")
                || normalized.contains("change-me")
                || normalized.contains("changeme")
                || normalized.contains("replace-me")
                || normalized.contains("dummy")
                || normalized.contains("fixture")
                || normalized.contains("example")
                || normalized.startsWith("test-")
                || normalized.startsWith("dev-")
                || normalized.startsWith("local-")
                || normalized.equals("recovery-secret");
        boolean invalidCharacter = raw.chars().anyMatch(Character::isWhitespace)
                || raw.chars().anyMatch(Character::isISOControl);
        return value.length() >= 32 && value.length() <= 512
                && value.chars().distinct().count() >= 8
                && !invalidCharacter && !placeholder && value.equals(raw);
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

    private void requireTrueWhenEnabled(
            Environment environment,
            List<String> failures,
            String enablingProperty,
            String requiredProperty) {
        if (environment.getProperty(enablingProperty, Boolean.class, false)
                && !environment.getProperty(requiredProperty, Boolean.class, false)) {
            failures.add(requiredProperty + " must be true when "
                    + enablingProperty + " is true");
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

    private void requireExact(
            Environment environment,
            List<String> failures,
            String property,
            String expected) {
        if (!expected.equals(environment.getProperty(property, "").trim())) {
            failures.add(property + " must be " + expected);
        }
    }

    private void requireProductionKafka(Environment environment, List<String> failures) {
        String brokers = environment.getProperty("spring.kafka.bootstrap-servers", "").trim();
        if (brokers.isBlank() || brokers.equals("localhost:9092")) {
            failures.add("spring.kafka.bootstrap-servers must use an explicit production broker");
        }
    }

    private void requireProductionHost(
            Environment environment,
            List<String> failures,
            String property) {
        String host = environment.getProperty(property, "").trim().toLowerCase(Locale.ROOT);
        if (!host.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")
                || host.contains("..") || host.equals("localhost")
                || host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")
                || host.endsWith(".local") || host.endsWith(".test")) {
            failures.add(property + " must be an explicit production DNS host");
        }
    }

    private void requireBoundServiceSecrets(
            Environment environment,
            List<String> failures,
            String allowedProperty,
            String bindingsProperty,
            String peerSecretProperty) {
        Set<String> allowed = new HashSet<>();
        String allowedValue = environment.getProperty(allowedProperty, "").trim();
        for (String entry : allowedValue.split(",", -1)) {
            String service = entry.trim();
            if (!service.matches("dwp-[a-z0-9-]+-server") || !allowed.add(service)) {
                failures.add(allowedProperty
                        + " must contain unique DWP service identities");
                return;
            }
        }

        Map<String, String> bindings = new HashMap<>();
        String bindingsValue = environment.getProperty(bindingsProperty, "").trim();
        for (String entry : bindingsValue.split(",", -1)) {
            String[] parts = entry.trim().split("=", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()
                    || bindings.put(parts[0].trim(), parts[1].trim()) != null) {
                failures.add(bindingsProperty
                        + " must use unique service=secret entries");
                return;
            }
        }
        if (!bindings.keySet().equals(allowed)) {
            failures.add(bindingsProperty
                    + " must bind every allowlisted producer exactly once");
            return;
        }

        String peerSecret = environment.getProperty(peerSecretProperty, "");
        Set<String> distinctSecrets = new HashSet<>();
        if (bindings.values().stream().anyMatch(secret -> !productionSecret(secret))
                || bindings.values().stream().anyMatch(peerSecret::equals)
                || !bindings.values().stream().allMatch(distinctSecrets::add)) {
            failures.add(bindingsProperty
                    + " must use distinct strong production secrets per producer");
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

    private void requireRsaPrivateKey(
            Environment environment,
            List<String> failures,
            String property) {
        String pem = environment.getProperty(property, "").trim();
        try {
            if (!pem.startsWith("-----BEGIN PRIVATE KEY-----")
                    || !pem.endsWith("-----END PRIVATE KEY-----")) {
                throw new IllegalArgumentException();
            }
            String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if (privateKey.getModulus().bitLength() < 2048) {
                failures.add(property + " must use RSA with at least 2048 bits");
                return;
            }
            byte[] publicKey = KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(
                            privateKey.getModulus(), privateKey.getPublicExponent())).getEncoded();
            String fingerprint = java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(publicKey));
            if (STEP_UP_FIXTURE_PUBLIC_KEY_SHA256.equals(fingerprint)) {
                failures.add(property + " uses the contract-test fixture key");
            }
        } catch (Exception exception) {
            failures.add(property + " must be a valid PKCS#8 RSA private key");
        }
    }

    private void requireRsaPublicKey(
            Environment environment,
            List<String> failures,
            String property) {
        String pem = environment.getProperty(property, "").trim();
        try {
            if (!pem.startsWith("-----BEGIN PUBLIC KEY-----")
                    || !pem.endsWith("-----END PUBLIC KEY-----")) {
                throw new IllegalArgumentException();
            }
            String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if (publicKey.getModulus().bitLength() < 2048) {
                failures.add(property + " must use RSA with at least 2048 bits");
                return;
            }
            String fingerprint = java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));
            if (STEP_UP_FIXTURE_PUBLIC_KEY_SHA256.equals(fingerprint)) {
                failures.add(property + " uses the contract-test fixture key");
            }
        } catch (Exception exception) {
            failures.add(property + " must be a valid X.509 RSA public key");
        }
    }

    private void requireProductionUri(
            Environment environment,
            List<String> failures,
            String property) {
        String value = environment.getProperty(property, "").trim();
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || fixtureLike(value)) {
                failures.add(property + " must be a non-fixture HTTPS issuer URI");
            }
        } catch (IllegalArgumentException exception) {
            failures.add(property + " must be a non-fixture HTTPS issuer URI");
        }
    }

    private void requireKeyId(
            Environment environment,
            List<String> failures,
            String property) {
        String value = environment.getProperty(property, "").trim();
        if (!value.matches("[A-Za-z0-9._-]{8,128}") || fixtureLike(value)) {
            failures.add(property + " must be a non-fixture key identifier");
        }
    }

    private void requireAcr(
            Environment environment,
            List<String> failures,
            String property) {
        String value = environment.getProperty(property, "").trim();
        if (!value.matches("[A-Za-z0-9:._/+-]{1,200}") || fixtureLike(value)) {
            failures.add(property + " must be an exact non-fixture ACR value");
        }
    }

    private void requireAudiences(
            Environment environment,
            List<String> failures,
            String property) {
        String value = environment.getProperty(property, "").trim();
        String[] entries = value.split(",", -1);
        Set<String> unique = new HashSet<>();
        if (value.isBlank()) {
            failures.add(property + " must contain at least one audience");
            return;
        }
        for (String entry : entries) {
            String audience = entry.trim();
            if (!audience.matches("[a-z][a-z0-9-]{2,99}")
                    || fixtureLike(audience) || !unique.add(audience)) {
                failures.add(property + " must contain unique non-fixture service audiences");
                return;
            }
        }
    }

    private void requireLongRange(
            Environment environment,
            List<String> failures,
            String property,
            long minimum,
            long maximum) {
        String value = environment.getProperty(property, "").trim();
        try {
            long number = Long.parseLong(value);
            if (number < minimum || number > maximum) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            failures.add(property + " must be between " + minimum + " and " + maximum);
        }
    }

    private void requireDurationRange(
            Environment environment,
            List<String> failures,
            String property,
            long minimumSeconds,
            long maximumSeconds) {
        String value = environment.getProperty(property, "").trim();
        try {
            long seconds = Duration.parse(value).toSeconds();
            if (seconds < minimumSeconds || seconds > maximumSeconds) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            failures.add(property + " must be an ISO-8601 duration between "
                    + minimumSeconds + " and " + maximumSeconds + " seconds");
        }
    }

    private void requireCredential(
            Environment environment,
            List<String> failures,
            String property,
            int minimumLength) {
        String value = environment.getProperty(property, "").trim();
        if (value.length() < minimumLength || fixtureLike(value)) {
            failures.add(property + " must be a non-placeholder production credential");
        }
    }

    private void requireProductionEndpoint(
            Environment environment,
            List<String> failures,
            String property,
            String requiredScheme) {
        String value = environment.getProperty(property, "").trim();
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            boolean localHost = host == null || host.equalsIgnoreCase("localhost")
                    || host.equals("127.0.0.1") || host.equals("::1")
                    || host.endsWith(".local");
            if (!requiredScheme.equalsIgnoreCase(uri.getScheme()) || localHost
                    || uri.getUserInfo() != null || uri.getRawFragment() != null
                    || fixtureLike(value)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            failures.add(property + " must be a non-local "
                    + requiredScheme.toUpperCase(Locale.ROOT) + " endpoint");
        }
    }

    private Set<String> requireHostAllowlist(
            Environment environment,
            List<String> failures,
            String property) {
        String value = environment.getProperty(property, "").trim();
        Set<String> result = new HashSet<>();
        if (value.isBlank()) {
            failures.add(property + " must contain at least one production host");
            return Set.of();
        }
        for (String entry : value.split(",", -1)) {
            String host = entry.trim().toLowerCase(Locale.ROOT);
            if (!host.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")
                    || host.contains("..") || host.equals("localhost")
                    || host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")
                    || host.endsWith(".test") || !result.add(host)) {
                failures.add(property + " must contain unique production DNS hosts");
                return Set.of();
            }
        }
        return Set.copyOf(result);
    }

    private void requireCallback(
            Environment environment,
            List<String> failures,
            String property,
            Set<String> callbackHosts) {
        try {
            URI uri = URI.create(environment.getProperty(property, "").trim());
            String host = uri.getHost() == null
                    ? ""
                    : uri.getHost().toLowerCase(Locale.ROOT);
            if (!callbackHosts.contains(host)
                    || !"/auth/oidc/callback".equals(uri.getPath())
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                failures.add(property + " must use an allowed exact OIDC callback host and path");
            }
        } catch (IllegalArgumentException exception) {
            failures.add(property + " must use an allowed exact OIDC callback host and path");
        }
    }

    private boolean fixtureLike(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("fixture") || normalized.contains("localhost")
                || normalized.contains("example.test") || normalized.contains("test-key")
                || normalized.startsWith("test-") || normalized.startsWith("dev-")
                || normalized.startsWith("local-");
    }
}
