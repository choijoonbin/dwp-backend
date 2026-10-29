package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** HTTPS-only scanner owner. Callback bodies, service tokens, and file names are never logged. */
@Component
@ConditionalOnProperty(
        name = "dwp.platform.mail.attachments.trusted-scanner.enabled",
        havingValue = "true")
class TrustedHttpMailAttachmentScanner implements MailAttachmentScanner {

    private static final Set<String> VERDICTS = Set.of("CLEAN", "REJECTED");

    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final MailMetrics metrics;
    private final URI scanEndpoint;
    private final URI readinessEndpoint;
    private final String serviceToken;
    private final Duration timeout;
    private final int maximumResponseBytes;
    private final long maximumRequestBytes;

    TrustedHttpMailAttachmentScanner(
            ObjectMapper objectMapper,
            MailMetrics metrics,
            @Value("${dwp.platform.mail.attachments.trusted-scanner.base-url:}")
            String baseUrl,
            @Value("${dwp.platform.mail.attachments.trusted-scanner.service-token:}")
            String serviceToken,
            @Value("${dwp.platform.mail.attachments.trusted-scanner.timeout:PT5S}")
            Duration timeout,
            @Value("${dwp.platform.mail.attachments.trusted-scanner.maximum-response-bytes:32768}")
            int maximumResponseBytes,
            @Value("${dwp.platform.mail.attachments.trusted-scanner.maximum-request-bytes:157286400}")
            long maximumRequestBytes) {
        this(objectMapper, metrics, validatedOrigin(baseUrl), serviceToken, timeout,
                maximumResponseBytes, maximumRequestBytes,
                HttpClient.newBuilder()
                        .connectTimeout(requirePositive(timeout, "timeout"))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    TrustedHttpMailAttachmentScanner(
            ObjectMapper objectMapper,
            MailMetrics metrics,
            URI base,
            String serviceToken,
            Duration timeout,
            int maximumResponseBytes,
            long maximumRequestBytes,
            HttpClient client) {
        if (!safeSecret(serviceToken)) {
            throw new IllegalArgumentException("Trusted scanner service token is required");
        }
        if (maximumResponseBytes <= 0 || maximumRequestBytes <= 0) {
            throw new IllegalArgumentException("Trusted scanner body limits must be positive");
        }
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        URI validatedBase = validatedOrigin(base == null ? "" : base.toString());
        this.scanEndpoint = validatedBase.resolve("./v1/scan");
        this.readinessEndpoint = validatedBase.resolve("./health/ready");
        this.serviceToken = serviceToken;
        this.timeout = requirePositive(timeout, "timeout");
        this.maximumResponseBytes = maximumResponseBytes;
        this.maximumRequestBytes = maximumRequestBytes;
        this.client = client;
    }

    @Override
    public ScanResult scan(ScanRequest request) {
        long startedAt = System.nanoTime();
        if (request.content().length > maximumRequestBytes) {
            metrics.scanner("rejected", elapsed(startedAt));
            return new ScanResult(Verdict.REJECTED, "SCANNER_REQUEST_LIMIT_EXCEEDED");
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(scanEndpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + serviceToken)
                .header("Content-Type", "application/octet-stream")
                .header("X-DWP-Tenant-ID", Long.toString(request.tenantId()))
                .header("X-DWP-Attachment-ID", request.attachmentId().toString())
                .header("X-Content-SHA256", request.checksumSha256())
                .header("X-File-Name-SHA256", sha256(request.fileName()))
                .header("X-Declared-Content-Type", safeContentType(request.contentType()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.content()))
                .build();
        try {
            HttpResponse<InputStream> response = client.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                close(response.body());
                metrics.scanner("unavailable", elapsed(startedAt));
                return new ScanResult(Verdict.REJECTED, "SCANNER_UNAVAILABLE");
            }
            byte[] responseBody = limited(response.body());
            JsonNode payload = objectMapper.readTree(responseBody);
            String verdict = text(payload, "verdict").toUpperCase(Locale.ROOT);
            String evidence = text(payload, "evidence");
            if (!VERDICTS.contains(verdict)
                    || !evidence.matches("[A-Za-z0-9._:-]{1,160}")) {
                metrics.scanner("invalid", elapsed(startedAt));
                return new ScanResult(Verdict.REJECTED, "SCANNER_INVALID_RESPONSE");
            }
            Verdict parsed = Verdict.valueOf(verdict);
            metrics.scanner(parsed == Verdict.CLEAN ? "clean" : "rejected", elapsed(startedAt));
            return new ScanResult(parsed, evidence);
        } catch (HttpTimeoutException timeoutFailure) {
            metrics.scanner("timeout", elapsed(startedAt));
            return new ScanResult(Verdict.REJECTED, "SCANNER_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            metrics.scanner("error", elapsed(startedAt));
            return new ScanResult(Verdict.REJECTED, "SCANNER_INTERRUPTED");
        } catch (RuntimeException | java.io.IOException failure) {
            metrics.scanner("error", elapsed(startedAt));
            return new ScanResult(Verdict.REJECTED, "SCANNER_INVALID_RESPONSE");
        }
    }

    @Override
    public Readiness readiness() {
        Instant checkedAt = Instant.now();
        HttpRequest request = HttpRequest.newBuilder(readinessEndpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + serviceToken)
                .GET().build();
        try {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body = limited(response.body());
            if (response.statusCode() / 100 != 2 || body.length == 0) {
                return new Readiness(false, "TRUSTED_HTTP_SCANNER", checkedAt,
                        "SCANNER_READINESS_UNAVAILABLE");
            }
            JsonNode receipt = objectMapper.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
            if (receipt == null || !receipt.isObject() || receipt.size() != 1
                    || !receipt.path("ready").isBoolean()
                    || !receipt.path("ready").booleanValue()) {
                return new Readiness(false, "TRUSTED_HTTP_SCANNER", checkedAt,
                        "SCANNER_READINESS_INVALID_RESPONSE");
            }
            return new Readiness(true, "TRUSTED_HTTP_SCANNER", checkedAt, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Readiness(false, "TRUSTED_HTTP_SCANNER", checkedAt,
                    "SCANNER_READINESS_INTERRUPTED");
        } catch (RuntimeException | java.io.IOException failure) {
            return new Readiness(false, "TRUSTED_HTTP_SCANNER", checkedAt,
                    "SCANNER_READINESS_UNAVAILABLE");
        }
    }

    private byte[] limited(InputStream input) throws java.io.IOException {
        try (input) {
            byte[] body = input.readNBytes(maximumResponseBytes + 1);
            if (body.length > maximumResponseBytes) {
                throw new java.io.IOException("Scanner response exceeded the configured cap");
            }
            return body;
        }
    }

    private void close(InputStream input) {
        try (input) {
            // Closing the body also makes the HTTP connection reusable.
        } catch (java.io.IOException ignored) {
            // The response is already rejected.
        }
    }

    private String text(JsonNode payload, String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        return value == null || !value.isTextual() ? "" : value.textValue().trim();
    }

    private Duration elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    private static URI validatedOrigin(String raw) {
        URI base;
        try {
            base = URI.create(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Trusted scanner base URL is invalid", invalid);
        }
        if (!"https".equalsIgnoreCase(base.getScheme())
                || base.getHost() == null || base.getHost().isBlank()
                || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null
                || !(base.getRawPath() == null || base.getRawPath().isEmpty()
                     || "/".equals(base.getRawPath()))) {
            throw new IllegalArgumentException(
                    "Trusted scanner base URL must be an HTTPS origin");
        }
        String normalized = base.toString();
        return URI.create(normalized.endsWith("/") ? normalized : normalized + '/');
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static boolean safeSecret(String value) {
        if (value == null || value.isBlank() || value.length() > 4_096) return false;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return false;
        }
        return true;
    }

    private static String safeContentType(String value) {
        return value != null && value.matches("[A-Za-z0-9!#$&^_.+\\-/;= ]{1,160}")
                ? value : "application/octet-stream";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
