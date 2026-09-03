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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** HTTPS-only broker; locators cross this boundary only for signed crypto-shred requests. */
final class GovernedHttpMeetingTranscriptSource implements MeetingTranscriptSource {

    private static final String READ_PATH = "/internal/v1/meeting-transcripts/read";
    private static final String RETENTION_CAPABILITY_PATH =
            "/internal/v1/meeting-transcripts/retention-capability";
    private static final String DELETE_PATH = "/internal/v1/meeting-transcripts/delete";
    private static final String SCHEMA = "meeting-transcript-v1";
    private static final String READ_REQUEST_SCHEMA = "meeting-transcript-read-v1";
    private static final String RETENTION_SCHEMA =
            "meeting-transcript-retention-capability-v1";
    private static final String DELETE_SCHEMA = "meeting-transcript-delete-v1";

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
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(new ReadRequestBody(
                    READ_REQUEST_SCHEMA, context.tenantId(), context.meetingId(),
                    context.runId(), context.artifactId(), context.expectedSha256()));
        } catch (IOException exception) {
            throw unavailable();
        }
        MeetingIntelligenceProvider.ExecutionContext workload =
                new MeetingIntelligenceProvider.ExecutionContext(
                        context.tenantId(), context.meetingId(),
                        context.runId(), context.correlationId());
        HttpRequest request = HttpRequest.newBuilder(origin.resolve(READ_PATH))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-DWP-Meeting-Transcript-Token", token)
                .header("X-DWP-Tenant-ID", Long.toString(context.tenantId()))
                .header("X-DWP-Meeting-ID", context.meetingId().toString())
                .header("X-DWP-Intelligence-Run-ID", context.runId().toString())
                .header("X-DWP-Transcript-Artifact-ID", context.artifactId().toString())
                .header("X-DWP-Source-SHA256", context.expectedSha256())
                .header("X-Correlation-ID", context.correlationId())
                .header("X-DWP-Meeting-Workload-Assertion",
                        signer.sign(workload, "POST", READ_PATH, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
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

    @Override
    public RetentionCapability retentionCapability() {
        HttpRequest request = HttpRequest.newBuilder(origin.resolve(RETENTION_CAPABILITY_PATH))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("X-DWP-Meeting-Transcript-Token", token)
                .header("X-DWP-Meeting-Workload-Assertion",
                        signer.signService("GET", RETENTION_CAPABILITY_PATH, null))
                .GET().build();
        try {
            RetentionCapabilityResponse response = send(
                    request, RetentionCapabilityResponse.class);
            if (!RETENTION_SCHEMA.equals(response.schemaVersion())
                    || !safeCode(response.providerCode(), 48)
                    || !safeCode(response.storageProviderCode(), 32)) {
                return RetentionCapability.unavailable();
            }
            boolean ready = response.available()
                    && response.deletionAvailable()
                    && response.cryptoShredAvailable()
                    && response.customerManagedStorage()
                    && response.providerRetentionDisabled()
                    && response.orphanCleanupAvailable()
                    && response.maximumOrphanTtlSeconds() >= 30
                    && response.maximumOrphanTtlSeconds() <= 3_600;
            return new RetentionCapability(
                    ready, response.deletionAvailable(), response.cryptoShredAvailable(),
                    response.customerManagedStorage(), response.providerRetentionDisabled(),
                    response.orphanCleanupAvailable(), response.maximumOrphanTtlSeconds(),
                    response.legacyLocatorDeletionAvailable(),
                    response.providerCode(), response.storageProviderCode());
        } catch (RuntimeException exception) {
            return RetentionCapability.unavailable();
        }
    }

    @Override
    public DeletionReceipt delete(DeleteRequest context) {
        validateDeleteContext(context);
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(new DeleteRequestBody(
                    DELETE_SCHEMA, context.tenantId(), context.meetingId(),
                    context.artifactId(), context.storageProvider(), context.objectKey(),
                    context.deletionBindingSha256(), context.artifactVersion()));
        } catch (IOException exception) {
            throw unavailable();
        }
        MeetingIntelligenceProvider.ExecutionContext workload =
                new MeetingIntelligenceProvider.ExecutionContext(
                        context.tenantId(), context.meetingId(),
                        context.artifactId(), context.correlationId());
        HttpRequest request = HttpRequest.newBuilder(origin.resolve(DELETE_PATH))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-DWP-Meeting-Transcript-Token", token)
                .header("X-DWP-Tenant-ID", Long.toString(context.tenantId()))
                .header("X-DWP-Meeting-ID", context.meetingId().toString())
                .header("X-DWP-Transcript-Artifact-ID", context.artifactId().toString())
                .header("X-Correlation-ID", context.correlationId())
                .header("Idempotency-Key", "DELETE:" + context.artifactId())
                .header("X-DWP-Meeting-Workload-Assertion",
                        signer.sign(workload, "POST", DELETE_PATH, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        DeleteResponse response = send(request, DeleteResponse.class);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!DELETE_SCHEMA.equals(response.schemaVersion())
                || !context.artifactId().equals(response.artifactId())
                || response.artifactVersion() != context.artifactVersion()
                || !constantTimeEquals(
                        context.deletionBindingSha256(), response.deletionBindingSha256())
                || !"DELETED".equals(response.deletionState())
                || !response.cryptoShredded()
                || !safeCode(response.providerDeletionId(), 160)
                || response.deletedAt() == null
                || response.deletedAt().isAfter(now.plusMinutes(5))) {
            throw unavailable();
        }
        return new DeletionReceipt(
                response.artifactId(), response.artifactVersion(),
                response.providerDeletionId(), response.deletedAt());
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

    private void validateDeleteContext(DeleteRequest context) {
        if (context == null || context.tenantId() <= 0 || context.meetingId() == null
                || context.artifactId() == null
                || !safeCode(context.storageProvider(), 32)
                || context.objectKey() == null || context.objectKey().isBlank()
                || context.objectKey().length() > 1_000
                || context.objectKey().chars().anyMatch(Character::isISOControl)
                || context.objectKey().contains("://")
                || context.objectKey().contains("?")
                || context.objectKey().contains("#")
                || context.deletionBindingSha256() == null
                || !context.deletionBindingSha256().matches("^[0-9a-f]{64}$")
                || context.artifactVersion() < 0
                || context.correlationId() == null || context.correlationId().isBlank()
                || context.correlationId().length() > 160) {
            throw new IllegalArgumentException("Transcript deletion context is invalid.");
        }
    }

    private <T> T send(HttpRequest request, Class<T> responseType) {
        try {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) throw unavailable();
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("application/json")) throw unavailable();
            byte[] payload = BoundedHttpResponseReader.read(response, maximumResponseBytes);
            return mapper.readValue(payload, responseType);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | RuntimeException exception) {
            throw unavailable();
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

    private boolean safeCode(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength
                && value.matches("^[A-Za-z0-9][A-Za-z0-9._:-]*$");
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("Meeting transcript broker is unavailable.");
    }

    private record BrokerResponse(
            String schemaVersion,
            String sourceSha256,
            List<MeetingIntelligenceProvider.TranscriptSegment> segments) {
    }

    private record ReadRequestBody(
            String schemaVersion,
            long tenantId,
            UUID meetingId,
            UUID runId,
            UUID artifactId,
            String sourceSha256) {
    }

    private record RetentionCapabilityResponse(
            String schemaVersion,
            boolean available,
            boolean deletionAvailable,
            boolean cryptoShredAvailable,
            boolean customerManagedStorage,
            boolean providerRetentionDisabled,
            boolean orphanCleanupAvailable,
            int maximumOrphanTtlSeconds,
            boolean legacyLocatorDeletionAvailable,
            String providerCode,
            String storageProviderCode) {
    }

    private record DeleteRequestBody(
            String schemaVersion,
            long tenantId,
            UUID meetingId,
            UUID artifactId,
            String storageProvider,
            String objectKey,
            String deletionBindingSha256,
            long artifactVersion) {
    }

    private record DeleteResponse(
            String schemaVersion,
            UUID artifactId,
            long artifactVersion,
            String deletionBindingSha256,
            String deletionState,
            boolean cryptoShredded,
            String providerDeletionId,
            OffsetDateTime deletedAt) {
    }
}
