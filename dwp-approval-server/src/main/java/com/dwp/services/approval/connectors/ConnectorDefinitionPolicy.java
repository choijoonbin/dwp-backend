package com.dwp.services.approval.connectors;

import static com.dwp.services.approval.connectors.ConnectorModels.*;

import java.net.InetAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ConnectorDefinitionPolicy {
    private static final Set<String> PROHIBITED_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "host",
            "connection", "content-length", "transfer-encoding", "forwarded",
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto");
    private static final Set<String> DIAGNOSTIC_KEYS = Set.of(
            "code", "status", "category", "latencyMs", "remoteRevision",
            "message", "errorClass", "attempt", "region");

    private ConnectorDefinitionPolicy() {
    }

    static void validate(ConnectorDraft input) {
        if (input == null || input.connectorId() == null || input.connectorType() == null
                || blank(input.connectorKey())
                || !input.connectorKey().trim().toUpperCase(Locale.ROOT)
                .matches("[A-Z][A-Z0-9_.-]{2,99}")
                || blank(input.displayName()) || input.displayName().trim().length() > 200
                || input.credentialReference() == null
                || !input.credentialReference().matches("vault://[A-Za-z0-9/_:.@-]{3,500}")
                || input.headerAllowlist() == null || input.headerAllowlist().size() > 32
                || input.requestMapping() == null || input.responseMapping() == null
                || input.timeoutMillis() < 100 || input.timeoutMillis() > 120_000
                || input.rateLimitPerMinute() < 1 || input.rateLimitPerMinute() > 10_000
                || input.maxAttempts() < 1 || input.maxAttempts() > 10
                || input.initialBackoffMillis() < 10 || input.initialBackoffMillis() > 60_000
                || input.maxBackoffMillis() < input.initialBackoffMillis()
                || input.maxBackoffMillis() > 3_600_000
                || input.idempotencyMode() == null || input.signingMode() == null
                || input.expectedVersion() < 0) {
            throw ConnectorRejected.invalid("Connector definition is invalid.");
        }
        validateEndpoint(input.endpointUri());
        if (input.headerAllowlist().stream().distinct().count() != input.headerAllowlist().size()) {
            throw ConnectorRejected.invalid("Connector header allowlist contains duplicates.");
        }
        for (String header : input.headerAllowlist()) validateHeader(header);
        validateStructure(input.requestMapping(), 0);
        validateStructure(input.responseMapping(), 0);
    }

    static Map<String, Object> sanitizeDiagnostics(Map<String, Object> input) {
        if (input == null || input.size() > 32) {
            throw ConnectorRejected.invalid("Connector diagnostics are invalid.");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (DIAGNOSTIC_KEYS.contains(key) && scalar(value)) {
                result.put(key, sanitizeScalar(value));
            }
        });
        return Map.copyOf(result);
    }

    private static void validateEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() < -1) {
                throw ConnectorRejected.invalid("Connector endpoint must be a canonical HTTPS URI.");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal")) {
                throw ConnectorRejected.invalid("Connector endpoint host is not allowed.");
            }
            if (host.matches("[0-9.]+") || host.contains(":")) {
                InetAddress address = InetAddress.getByName(host);
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw ConnectorRejected.invalid("Connector endpoint address is not allowed.");
                }
            }
        } catch (ConnectorRejected exception) {
            throw exception;
        } catch (RuntimeException | java.net.UnknownHostException exception) {
            throw ConnectorRejected.invalid("Connector endpoint is invalid.");
        }
    }

    private static void validateHeader(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9-]{0,63}")
                || PROHIBITED_HEADERS.contains(value.toLowerCase(Locale.ROOT))
                || value.toLowerCase(Locale.ROOT).startsWith("x-forwarded-")) {
            throw ConnectorRejected.invalid("Connector header is not allowlist eligible.");
        }
    }

    private static void validateStructure(Object value, int depth) {
        if (depth > 8) throw ConnectorRejected.invalid("Connector mapping is too deeply nested.");
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 128) throw ConnectorRejected.invalid("Connector mapping is too large.");
            map.forEach((key, item) -> {
                if (!(key instanceof String text)
                        || !text.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")
                        || sensitive(text)) {
                    throw ConnectorRejected.invalid("Connector mapping contains a prohibited key.");
                }
                validateStructure(item, depth + 1);
            });
        } else if (value instanceof List<?> list) {
            if (list.size() > 256) throw ConnectorRejected.invalid("Connector mapping is too large.");
            list.forEach(item -> validateStructure(item, depth + 1));
        } else if (!scalar(value)) {
            throw ConnectorRejected.invalid("Connector mapping contains an unsupported value.");
        } else if (value instanceof String text && text.length() > 4_096) {
            throw ConnectorRejected.invalid("Connector mapping value is too large.");
        }
    }

    private static boolean sensitive(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("secret")
                || lower.contains("credential") || lower.contains("authorization")
                || lower.contains("cookie") || lower.contains("privatekey")
                || lower.equals("token") || lower.endsWith("token");
    }

    private static Object sanitizeScalar(Object value) {
        if (!(value instanceof String text)) return value;
        if (text.length() > 500) return text.substring(0, 500);
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("bearer ") || lower.contains("basic ")
                || lower.contains("vault://") || lower.matches(".*[a-z0-9_-]{32,}.*")) {
            return "[REDACTED]";
        }
        return text;
    }

    private static boolean scalar(Object value) {
        return value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long
                || value instanceof Double || value instanceof java.math.BigDecimal;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
