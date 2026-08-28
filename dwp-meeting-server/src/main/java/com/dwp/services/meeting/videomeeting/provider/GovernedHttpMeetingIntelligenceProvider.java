package com.dwp.services.meeting.videomeeting.provider;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;

/** HTTPS-only, no-redirect, no-retry adapter for the internal DWP agent service. */
final class GovernedHttpMeetingIntelligenceProvider implements MeetingIntelligenceProvider {

    private static final String CAPABILITY_PATH =
            "/internal/v1/meeting-intelligence/capabilities";
    private static final String ANALYZE_PATH = "/internal/v1/meeting-intelligence/analyze";

    private final URI origin;
    private final String token;
    private final Duration requestTimeout;
    private final int maximumResponseBytes;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final MeetingWorkloadAssertionSigner signer;

    GovernedHttpMeetingIntelligenceProvider(
            MeetingIntelligenceHttpProperties properties,
            ObjectMapper objectMapper,
            MeetingWorkloadAssertionSigner signer) {
        this(properties, objectMapper, signer, httpClient(properties));
    }

    GovernedHttpMeetingIntelligenceProvider(
            MeetingIntelligenceHttpProperties properties,
            ObjectMapper objectMapper,
            MeetingWorkloadAssertionSigner signer,
            HttpClient client) {
        this.origin = validatedOrigin(properties);
        this.token = requiredToken(properties.getServiceToken());
        this.requestTimeout = bounded(
                properties.getRequestTimeout(), Duration.ofMillis(250), Duration.ofSeconds(30),
                "request timeout");
        bounded(
                properties.getConnectTimeout(), Duration.ofMillis(100), Duration.ofSeconds(5),
                "connect timeout");
        if (properties.getMaximumResponseBytes() < 1_024
                || properties.getMaximumResponseBytes() > 5_000_000) {
            throw new IllegalArgumentException(
                    "Meeting intelligence maximum response size is invalid.");
        }
        this.maximumResponseBytes = properties.getMaximumResponseBytes();
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.signer = signer;
    }

    @Override
    public Capability capability(ExecutionContext context) {
        return exchange(context, "GET", CAPABILITY_PATH, null, Capability.class);
    }

    @Override
    public Analysis analyze(ExecutionContext context, Request request) {
        if (request == null) throw new IllegalArgumentException("Analysis request is required.");
        try {
            return exchange(context,
                    "POST", ANALYZE_PATH, mapper.writeValueAsBytes(request), Analysis.class);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    private <T> T exchange(
            ExecutionContext context,
            String method,
            String path,
            byte[] body,
            Class<T> responseType) {
        if (context == null || context.tenantId() <= 0 || context.meetingId() == null
                || context.runId() == null || context.correlationId() == null
                || context.correlationId().isBlank() || context.correlationId().length() > 160) {
            throw new IllegalArgumentException(
                    "Meeting intelligence execution context is invalid.");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(origin.resolve(path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("X-DWP-Meeting-Intelligence-Token", token)
                .header("X-DWP-Tenant-ID", Long.toString(context.tenantId()))
                .header("X-DWP-Meeting-ID", context.meetingId().toString())
                .header("X-DWP-Intelligence-Run-ID", context.runId().toString())
                .header("X-Correlation-ID", context.correlationId())
                .header("X-DWP-Meeting-Workload-Assertion",
                        signer.sign(context, method, path, body));
        if (body == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        try {
            HttpResponse<InputStream> response = client.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) throw unavailable(null);
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("application/json")) throw unavailable(null);
            byte[] payload = BoundedHttpResponseReader.read(response, maximumResponseBytes);
            return mapper.readValue(payload, responseType);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw unavailable(exception);
        }
    }

    private URI validatedOrigin(MeetingIntelligenceHttpProperties properties) {
        URI candidate;
        try {
            candidate = URI.create(properties.getBaseUrl().trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Meeting intelligence base URL is invalid.", exception);
        }
        if (!"https".equalsIgnoreCase(candidate.getScheme())
                || candidate.getHost() == null
                || candidate.getUserInfo() != null
                || candidate.getQuery() != null
                || candidate.getFragment() != null
                || (candidate.getPort() != -1 && candidate.getPort() != 443)) {
            throw new IllegalArgumentException(
                    "Meeting intelligence base URL must be an HTTPS origin.");
        }
        String host = candidate.getHost().toLowerCase(Locale.ROOT);
        Set<String> allowlist = properties.getAllowedHosts().stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (host.equals("localhost") || host.endsWith(".local")
                || host.matches("^[0-9a-f:.]+$") || !allowlist.contains(host)) {
            throw new IllegalArgumentException(
                    "Meeting intelligence host is not in the exact allowlist.");
        }
        String path = candidate.getPath();
        if (path != null && !path.isBlank() && !"/".equals(path)) {
            throw new IllegalArgumentException(
                    "Meeting intelligence base URL must not contain a path.");
        }
        return URI.create("https://" + host);
    }

    private String requiredToken(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < 32 || normalized.length() > 4_096
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "Meeting intelligence service token is invalid.");
        }
        return normalized;
    }

    private Duration bounded(
            Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Meeting intelligence " + name + " is invalid.");
        }
        return value;
    }

    private static HttpClient httpClient(MeetingIntelligenceHttpProperties properties) {
        Duration timeout = properties.getConnectTimeout();
        if (timeout == null || timeout.compareTo(Duration.ofMillis(100)) < 0
                || timeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException(
                    "Meeting intelligence connect timeout is invalid.");
        }
        return HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private IllegalStateException unavailable(Throwable cause) {
        return new IllegalStateException("Meeting intelligence provider is unavailable.");
    }
}
