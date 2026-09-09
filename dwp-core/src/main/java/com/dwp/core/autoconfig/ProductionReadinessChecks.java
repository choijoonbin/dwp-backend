package com.dwp.core.autoconfig;

import org.springframework.core.env.Environment;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ProductionReadinessChecks {

    private static final String STEP_UP_FIXTURE_PUBLIC_KEY_SHA256 =
            "5b5a90532d7db5dc49d2f0db81acd3ec5a5582e04a8212796722a3da95abc8be";

    private ProductionReadinessChecks() {
    }

    static boolean production(Environment environment) {
        String value = environment.getProperty("dwp.environment",
                environment.getProperty("DWP_ENVIRONMENT", "local"));
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("prod") || normalized.equals("production");
    }

    static void requireSecret(
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

    static void requireProductionSecret(
            Environment environment,
            List<String> failures,
            String property) {
        String raw = environment.getProperty(property, "");
        if (!productionSecret(raw)) {
            failures.add(property
                    + " must be a strong non-placeholder dedicated secret (32..512 characters)");
        }
    }

    private static boolean productionSecret(String raw) {
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

    static void requireTrue(Environment environment, List<String> failures, String property) {
        if (!environment.getProperty(property, Boolean.class, false)) {
            failures.add(property + " must be true");
        }
    }

    static void requireFalse(Environment environment, List<String> failures, String property) {
        if (environment.getProperty(property, Boolean.class, true)) {
            failures.add(property + " must be false");
        }
    }

    static void requireTrueWhenEnabled(
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

    static void requireEventTransportWhenEnabled(
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

    static void requireExact(
            Environment environment,
            List<String> failures,
            String property,
            String expected) {
        if (!expected.equals(environment.getProperty(property, "").trim())) {
            failures.add(property + " must be " + expected);
        }
    }

    static void requireProductionKafka(Environment environment, List<String> failures) {
        String brokers = environment.getProperty("spring.kafka.bootstrap-servers", "").trim();
        if (brokers.isBlank() || brokers.equals("localhost:9092")) {
            failures.add("spring.kafka.bootstrap-servers must use an explicit production broker");
        }
    }

    static void requireProductionHost(
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

    static void requireNotificationAppViewBindings(
            Environment environment,
            List<String> failures) {
        String property = "dwp.notification.recipient-entitlements.app-view-bindings";
        String value = environment.getProperty(property, "").trim();
        Map<String, String> bindings = new HashMap<>();
        for (String entry : value.split(",", -1)) {
            String[] parts = entry.trim().split("=", 2);
            if (parts.length != 2
                    || !parts[0].trim().matches("[a-z][a-z0-9.-]{0,99}")
                    || !parts[1].trim().matches("APP\\.[A-Z0-9_.-]+:VIEW")
                    || bindings.putIfAbsent(parts[0].trim(), parts[1].trim()) != null) {
                failures.add(property + " must use unique owner=APP.*:VIEW entries");
                return;
            }
        }
        Map<String, String> required = Map.of(
                "approvals", "APP.APPROVALS:VIEW",
                "hcm", "APP.HCM:VIEW",
                "messaging", "APP.MESSAGING:VIEW",
                "space", "APP.SPACES:VIEW");
        if (!required.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(bindings.get(entry.getKey())))) {
            failures.add(property
                    + " must bind every active owner to its exact app VIEW permission");
        }
    }

    static void requireBoundServiceSecrets(
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

    static void requireUrl(
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
                failures.add(property + " must be a valid "
                        + (httpsOnly ? "HTTPS" : "HTTP(S)") + " URL");
                return;
            }
        } catch (IllegalArgumentException exception) {
            failures.add(property + " must be a valid "
                    + (httpsOnly ? "HTTPS" : "HTTP(S)") + " URL");
            return;
        }
        for (String forbidden : forbiddenValues) {
            if (value.equals(forbidden)) failures.add(property + " uses a local default");
        }
    }

    static void requireRsaPrivateKey(
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

    static void requireRsaPublicKey(
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

    static void requireProductionUri(
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

    static void requireKeyId(
            Environment environment,
            List<String> failures,
            String property) {
        String value = environment.getProperty(property, "").trim();
        if (!value.matches("[A-Za-z0-9._-]{8,128}") || fixtureLike(value)) {
            failures.add(property + " must be a non-fixture key identifier");
        }
    }

    static void requireAcr(
            Environment environment,
            List<String> failures,
            String property) {
        String value = environment.getProperty(property, "").trim();
        if (!value.matches("[A-Za-z0-9:._/+-]{1,200}") || fixtureLike(value)) {
            failures.add(property + " must be an exact non-fixture ACR value");
        }
    }

    static void requireAudiences(
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

    static void requireLongRange(
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

    static void requireDurationRange(
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

    static void requireCredential(
            Environment environment,
            List<String> failures,
            String property,
            int minimumLength) {
        String value = environment.getProperty(property, "").trim();
        if (value.length() < minimumLength || fixtureLike(value)) {
            failures.add(property + " must be a non-placeholder production credential");
        }
    }

    static void requireAssertionSecretBase64(
            Environment environment,
            List<String> failures,
            String property) {
        String raw = environment.getProperty(property, "");
        try {
            byte[] decoded = Base64.getDecoder().decode(raw);
            String decodedText = new String(decoded, StandardCharsets.ISO_8859_1);
            if (!raw.equals(raw.strip())
                    || !Base64.getEncoder().encodeToString(decoded).equals(raw)
                    || decoded.length < 32 || decoded.length > 64
                    || decodedText.chars().distinct().count() < 8
                    || fixtureLike(decodedText)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            failures.add(property
                    + " must be canonical Base64 for a dedicated non-placeholder 32..64 byte secret");
        }
    }

    static void requireProductionEndpoint(
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

    static Set<String> requireHostAllowlist(
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

    static void requireCallback(
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

    private static boolean fixtureLike(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("fixture") || normalized.contains("localhost")
                || normalized.contains("example.test") || normalized.contains("test-key")
                || normalized.startsWith("test-") || normalized.startsWith("dev-")
                || normalized.startsWith("local-");
    }
}
