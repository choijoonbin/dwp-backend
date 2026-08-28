package com.dwp.services.meeting.videomeeting.provider;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Objects;

/** HTTPS-only transcript broker. It never receives or returns a storage object key. */
final class GovernedHttpMeetingTranscriptSource implements MeetingTranscriptSource {

    private static final String READ_PATH = "/internal/v1/meeting-transcripts/read";
    private static final String SCHEMA = "meeting-transcript-v1";

    private final URI origin;
    private final String token;
    private final Duration requestTimeout;
    private final int maximumResponseBytes;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final MeetingWorkloadAssertionSigner signer;

    GovernedHttpMeetingTranscriptSource(
            MeetingTranscriptHttpProperties properties,
            ObjectMapper objectMapper,
            MeetingWorkloadAssertionSigner signer) {
        this(properties, objectMapper, signer, httpClient(properties));
    }

    GovernedHttpMeetingTranscriptSource(
            MeetingTranscriptHttpProperties properties,
            ObjectMapper objectMapper,
            MeetingWorkloadAssertionSigner signer,
            HttpClient client) {
        this.origin = validatedOrigin(properties);
        this.token = requiredToken(properties.getServiceToken());
        this.requestTimeout = bounded(
                properties.getRequestTimeout(), Duration.ofMillis(250), Duration.ofSeconds(30));
        bounded(
                properties.getConnectTimeout(), Duration.ofMillis(100), Duration.ofSeconds(5));
        if (properties.getMaximumResponseBytes() < 1_024
                || properties.getMaximumResponseBytes() > 20_000_000) {
            throw new IllegalArgumentException("Transcript response size is invalid.");
        }
        this.maximumResponseBytes = properties.getMaximumResponseBytes();
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.signer = signer;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<MeetingIntelligenceProvider.TranscriptSegment> read(ReadContext context) {
        validateContext(context);
        MeetingIntelligenceProvider.ExecutionContext workload =
                new MeetingIntelligenceProvider.ExecutionContext(
                        context.tenantId(), context.meetingId(),
                        context.runId(), context.correlationId());
        HttpRequest request = HttpRequest.newBuilder(origin.resolve(READ_PATH))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("X-DWP-Meeting-Transcript-Token", token)
                .header("X-DWP-Tenant-ID", Long.toString(context.tenantId()))
                .header("X-DWP-Meeting-ID", context.meetingId().toString())
                .header("X-DWP-Intelligence-Run-ID", context.runId().toString())
                .header("X-DWP-Transcript-Artifact-ID", context.artifactId().toString())
                .header("X-DWP-Source-SHA256", context.expectedSha256())
                .header("X-Correlation-ID", context.correlationId())
                .header("X-DWP-Meeting-Workload-Assertion",
                        signer.sign(workload, "GET", READ_PATH, null))
                .GET().build();
        try {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) throw unavailable();
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("application/json")) throw unavailable();
            byte[] payload = BoundedHttpResponseReader.read(
                    response, maximumResponseBytes);
            BrokerResponse decoded = mapper.readValue(payload, BrokerResponse.class);
            if (!SCHEMA.equals(decoded.schemaVersion())
                    || !constantTimeEquals(context.expectedSha256(), decoded.sourceSha256())
                    || decoded.segments() == null) {
                throw unavailable();
            }
            return List.copyOf(decoded.segments());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | RuntimeException exception) {
            throw unavailable();
        }
    }

    private void validateContext(ReadContext context) {
        if (context == null || context.tenantId() <= 0 || context.meetingId() == null
                || context.runId() == null || context.artifactId() == null
                || context.expectedSha256() == null
                || !context.expectedSha256().matches("^[0-9a-f]{64}$")
                || context.correlationId() == null || context.correlationId().isBlank()
                || context.correlationId().length() > 160) {
            throw new IllegalArgumentException("Transcript read context is invalid.");
        }
    }

    private URI validatedOrigin(MeetingTranscriptHttpProperties properties) {
        URI candidate;
        try {
            candidate = URI.create(properties.getBaseUrl().trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Transcript broker base URL is invalid.");
        }
        if (!"https".equalsIgnoreCase(candidate.getScheme()) || candidate.getHost() == null
                || candidate.getUserInfo() != null || candidate.getQuery() != null
                || candidate.getFragment() != null
                || (candidate.getPort() != -1 && candidate.getPort() != 443)
                || (candidate.getPath() != null && !candidate.getPath().isBlank()
                    && !"/".equals(candidate.getPath()))) {
            throw new IllegalArgumentException("Transcript broker URL must be an HTTPS origin.");
        }
        String host = candidate.getHost().toLowerCase(Locale.ROOT);
        Set<String> allowlist = properties.getAllowedHosts().stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (host.equals("localhost") || host.endsWith(".local")
                || host.matches("^[0-9a-f:.]+$") || !allowlist.contains(host)) {
            throw new IllegalArgumentException("Transcript broker host is not allowlisted.");
        }
        return URI.create("https://" + host);
    }

    private String requiredToken(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < 32 || normalized.length() > 4_096
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Transcript broker token is invalid.");
        }
        return normalized;
    }

    private Duration bounded(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Transcript broker timeout is invalid.");
        }
        return value;
    }

    private static HttpClient httpClient(MeetingTranscriptHttpProperties properties) {
        Duration timeout = properties.getConnectTimeout();
        if (timeout == null || timeout.compareTo(Duration.ofMillis(100)) < 0
                || timeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Transcript broker connect timeout is invalid.");
        }
        return HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private boolean constantTimeEquals(String left, String right) {
        return right != null && MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("Meeting transcript broker is unavailable.");
    }

    private record BrokerResponse(
            String schemaVersion,
            String sourceSha256,
            List<MeetingIntelligenceProvider.TranscriptSegment> segments) {
    }
}
