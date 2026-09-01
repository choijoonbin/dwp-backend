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
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** HTTPS-only, redirect-free adapter for a governed Egress/STT broker. */
final class GovernedHttpMeetingRecordingProvider implements MeetingRecordingProvider {

    private static final String CAPABILITY_PATH = "/internal/v1/meeting-recording/capability";
    private static final String START_PATH = "/internal/v1/meeting-recording/start";
    private static final String STOP_PATH = "/internal/v1/meeting-recording/stop";
    private static final String ACCESS_TICKET_PATH =
            "/internal/v1/meeting-recording/access-ticket";
    private static final String DELETE_PATH = "/internal/v1/meeting-recording/delete";
    private static final String CAPABILITY_SCHEMA = "meeting-recording-capability-v1";
    private static final String COMMAND_SCHEMA = "meeting-recording-command-v1";
    private static final String ACCESS_TICKET_SCHEMA =
            "meeting-recording-access-ticket-v1";
    private static final String DELETE_SCHEMA = "meeting-recording-delete-v1";

    private final URI origin;
    private final String token;
    private final String processingRegion;
    private final Duration requestTimeout;
    private final int maximumResponseBytes;
    private final Duration accessTicketTtl;
    private final Set<String> accessTicketAllowedHosts;
    private final String accessTicketPathPrefix;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final MeetingWorkloadAssertionSigner signer;

    GovernedHttpMeetingRecordingProvider(
            MeetingRecordingHttpProperties properties,
            ObjectMapper mapper,
            MeetingWorkloadAssertionSigner signer) {
        this(properties, mapper, signer, httpClient(properties));
    }

    GovernedHttpMeetingRecordingProvider(
            MeetingRecordingHttpProperties properties,
            ObjectMapper mapper,
            MeetingWorkloadAssertionSigner signer,
            HttpClient client) {
        this.origin = validatedOrigin(properties);
        this.token = requiredToken(properties.getServiceToken());
        this.processingRegion = requiredRegion(properties.getProcessingRegion());
        this.requestTimeout = bounded(
                properties.getRequestTimeout(), Duration.ofMillis(250), Duration.ofSeconds(30));
        bounded(properties.getConnectTimeout(), Duration.ofMillis(100), Duration.ofSeconds(5));
        if (properties.getMaximumResponseBytes() < 1_024
                || properties.getMaximumResponseBytes() > 1_000_000) {
            throw new IllegalArgumentException("Recording provider response size is invalid.");
        }
        bounded(properties.getCommandLease(), Duration.ofSeconds(30), Duration.ofMinutes(10));
        this.accessTicketTtl = bounded(
                properties.getAccessTicketTtl(), Duration.ofSeconds(30), Duration.ofMinutes(10));
        this.accessTicketAllowedHosts = properties.getAccessTicketAllowedHosts().stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.accessTicketPathPrefix = validatedAccessPathPrefix(
                properties.getAccessTicketPathPrefix());
        this.maximumResponseBytes = properties.getMaximumResponseBytes();
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.signer = Objects.requireNonNull(signer, "signer");
    }

    @Override
    public Capability capability() {
        HttpRequest request = HttpRequest.newBuilder(origin.resolve(CAPABILITY_PATH))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("X-DWP-Meeting-Recording-Token", token)
                .GET().build();
        try {
            CapabilityResponse response = send(request, 200, CapabilityResponse.class);
            if (!CAPABILITY_SCHEMA.equals(response.schemaVersion())
                    || response.providerCode() == null
                    || !response.providerCode().matches("^[A-Z][A-Z0-9_-]{2,47}$")) {
                return Capability.unavailable();
            }
            boolean ready = response.available()
                    && response.egressAvailable() && response.storageAvailable()
                    && response.deletionAvailable() && response.cryptoShredAvailable()
                    && response.customerManagedStorage()
                    && response.providerRetentionDisabled()
                    && processingRegion.equals(response.processingRegion());
            return new Capability(
                    ready,
                    response.egressAvailable(),
                    response.storageAvailable(),
                    response.speechToTextAvailable(),
                    response.deletionAvailable(),
                    response.cryptoShredAvailable(),
                    response.processingRegion(),
                    response.providerCode());
        } catch (RuntimeException exception) {
            return Capability.unavailable();
        }
    }

    @Override
    public Receipt start(Command command) {
        return command(START_PATH, "START", command);
    }

    @Override
    public Receipt stop(Command command) {
        return command(STOP_PATH, "STOP", command);
    }

    @Override
    public AccessTicket issueAccessTicket(AccessRequest command) {
        validate(command);
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(new AccessTicketRequest(
                    ACCESS_TICKET_SCHEMA, command.tenantId(), command.meetingId(),
                    command.artifactId(), command.requesterUserId(), command.storageProvider(),
                    command.objectKey(), command.contentType(), command.sourceSha256(),
                    command.artifactVersion(),
                    command.expiresNoLaterThan()));
        } catch (IOException exception) {
            throw unavailable();
        }
        MeetingIntelligenceProvider.ExecutionContext workload =
                new MeetingIntelligenceProvider.ExecutionContext(
                        command.tenantId(), command.meetingId(),
                        command.artifactId(), command.correlationId());
        HttpRequest request = HttpRequest.newBuilder(origin.resolve(ACCESS_TICKET_PATH))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-DWP-Meeting-Recording-Token", token)
                .header("X-DWP-Tenant-ID", Long.toString(command.tenantId()))
                .header("X-DWP-Meeting-ID", command.meetingId().toString())
                .header("X-DWP-Recording-Artifact-ID", command.artifactId().toString())
                .header("X-DWP-Requester-User-ID", Long.toString(command.requesterUserId()))
                .header("X-Correlation-ID", command.correlationId())
                .header("X-DWP-Meeting-Workload-Assertion",
                        signer.sign(workload, "POST", ACCESS_TICKET_PATH, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        AccessTicketResponse response = send(
                request, 200, AccessTicketResponse.class);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!ACCESS_TICKET_SCHEMA.equals(response.schemaVersion())
                || !command.artifactId().equals(response.artifactId())
                || response.requesterUserId() != command.requesterUserId()
                || response.artifactVersion() != command.artifactVersion()
                || response.sourceSha256() == null
                || !MessageDigest.isEqual(
                        command.sourceSha256().getBytes(
                                java.nio.charset.StandardCharsets.US_ASCII),
                        response.sourceSha256().getBytes(
                                java.nio.charset.StandardCharsets.US_ASCII))
                || response.expiresAt() == null
                || !response.expiresAt().isAfter(now)
                || response.expiresAt().isAfter(now.plus(accessTicketTtl))
                || response.expiresAt().isAfter(command.expiresNoLaterThan())) {
            throw unavailable();
        }
        return new AccessTicket(
                response.artifactId(), response.requesterUserId(),
                response.artifactVersion(), validatedAccessUri(response.accessUrl(), command),
                response.expiresAt());
    }

    @Override
    public DeletionReceipt delete(DeleteRequest command) {
        validate(command);
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(new DeleteRequestBody(
                    DELETE_SCHEMA, command.tenantId(), command.meetingId(),
                    command.artifactId(), command.storageProvider(), command.objectKey(),
                    command.deletionBindingSha256(), command.artifactVersion()));
        } catch (IOException exception) {
            throw unavailable();
        }
        MeetingIntelligenceProvider.ExecutionContext workload =
                new MeetingIntelligenceProvider.ExecutionContext(
                        command.tenantId(), command.meetingId(),
                        command.artifactId(), command.correlationId());
        HttpRequest request = HttpRequest.newBuilder(origin.resolve(DELETE_PATH))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-DWP-Meeting-Recording-Token", token)
                .header("X-DWP-Tenant-ID", Long.toString(command.tenantId()))
                .header("X-DWP-Meeting-ID", command.meetingId().toString())
                .header("X-DWP-Recording-Artifact-ID", command.artifactId().toString())
                .header("X-Correlation-ID", command.correlationId())
                .header("Idempotency-Key", "DELETE:" + command.artifactId())
                .header("X-DWP-Meeting-Workload-Assertion",
                        signer.sign(workload, "POST", DELETE_PATH, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        DeleteResponse response = send(request, 200, DeleteResponse.class);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!DELETE_SCHEMA.equals(response.schemaVersion())
                || !command.artifactId().equals(response.artifactId())
                || response.artifactVersion() != command.artifactVersion()
                || response.deletionBindingSha256() == null
                || !MessageDigest.isEqual(
                        command.deletionBindingSha256().getBytes(
                                java.nio.charset.StandardCharsets.US_ASCII),
                        response.deletionBindingSha256().getBytes(
                                java.nio.charset.StandardCharsets.US_ASCII))
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

    private Receipt command(String path, String commandType, Command command) {
        validate(command);
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(new CommandRequest(
                    COMMAND_SCHEMA, commandType, command.tenantId(), command.meetingId(),
                    command.recordingSessionId(), command.planVersion(), command.noticeId(),
                    command.providerRoomName()));
        } catch (IOException exception) {
            throw unavailable();
        }
        MeetingIntelligenceProvider.ExecutionContext workload =
                new MeetingIntelligenceProvider.ExecutionContext(
                        command.tenantId(), command.meetingId(),
                        command.recordingSessionId(), command.correlationId());
        HttpRequest request = HttpRequest.newBuilder(origin.resolve(path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-DWP-Meeting-Recording-Token", token)
                .header("X-DWP-Tenant-ID", Long.toString(command.tenantId()))
                .header("X-DWP-Meeting-ID", command.meetingId().toString())
                .header("X-DWP-Recording-Session-ID", command.recordingSessionId().toString())
                .header("X-Correlation-ID", command.correlationId())
                .header("Idempotency-Key", commandType + ":" + command.recordingSessionId())
                .header("X-DWP-Meeting-Workload-Assertion",
                        signer.sign(workload, "POST", path, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        CommandResponse response = send(request, 200, CommandResponse.class);
        String terminalState = "START".equals(commandType) ? "STARTED" : "STOPPED";
        if (!COMMAND_SCHEMA.equals(response.schemaVersion())
                || !command.recordingSessionId().equals(response.recordingSessionId())
                || !terminalState.equals(response.commandState())
                || response.providerCommandId() == null
                || !response.providerCommandId().matches("^[A-Za-z0-9][A-Za-z0-9._:-]{2,159}$")) {
            throw unavailable();
        }
        return new Receipt(
                response.recordingSessionId(), response.commandState(),
                response.providerCommandId());
    }

    private <T> T send(HttpRequest request, int expectedStatus, Class<T> type) {
        try {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != expectedStatus) throw unavailable();
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("application/json")) throw unavailable();
            return mapper.readValue(
                    BoundedHttpResponseReader.read(response, maximumResponseBytes), type);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | RuntimeException exception) {
            throw unavailable();
        }
    }

    private void validate(Command command) {
        if (command == null || command.tenantId() <= 0 || command.meetingId() == null
                || command.recordingSessionId() == null || command.planVersion() < 0
                || command.noticeId() == null || command.providerRoomName() == null
                || command.providerRoomName().isBlank()
                || command.providerRoomName().length() > 180
                || command.correlationId() == null || command.correlationId().isBlank()
                || command.correlationId().length() > 160) {
            throw new IllegalArgumentException("Recording command context is invalid.");
        }
    }

    private void validate(AccessRequest command) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (command == null || command.tenantId() <= 0 || command.meetingId() == null
                || command.artifactId() == null || command.requesterUserId() <= 0
                || !safeCode(command.storageProvider(), 32)
                || command.objectKey() == null || command.objectKey().isBlank()
                || command.objectKey().length() > 1_000
                || command.objectKey().chars().anyMatch(Character::isISOControl)
                || command.contentType() == null || command.contentType().isBlank()
                || command.contentType().length() > 120
                || command.sourceSha256() == null
                || !command.sourceSha256().matches("^[0-9a-f]{64}$")
                || command.artifactVersion() < 0
                || command.expiresNoLaterThan() == null
                || !command.expiresNoLaterThan().isAfter(now)
                || command.expiresNoLaterThan().isAfter(now.plus(accessTicketTtl))
                || command.correlationId() == null || command.correlationId().isBlank()
                || command.correlationId().length() > 160) {
            throw new IllegalArgumentException("Recording access context is invalid.");
        }
    }

    private void validate(DeleteRequest command) {
        if (command == null || command.tenantId() <= 0 || command.meetingId() == null
                || command.artifactId() == null
                || !safeCode(command.storageProvider(), 32)
                || command.objectKey() == null || command.objectKey().isBlank()
                || command.objectKey().length() > 1_000
                || command.objectKey().chars().anyMatch(Character::isISOControl)
                || command.objectKey().contains("://")
                || command.deletionBindingSha256() == null
                || !command.deletionBindingSha256().matches("^[0-9a-f]{64}$")
                || command.artifactVersion() < 0
                || command.correlationId() == null || command.correlationId().isBlank()
                || command.correlationId().length() > 160) {
            throw new IllegalArgumentException("Recording deletion context is invalid.");
        }
    }

    private URI validatedAccessUri(String value, AccessRequest command) {
        URI candidate;
        try {
            if (value == null || value.isBlank() || value.length() > 8_192) {
                throw unavailable();
            }
            candidate = URI.create(value);
        } catch (RuntimeException exception) {
            throw unavailable();
        }
        String host = candidate.getHost() == null
                ? "" : candidate.getHost().toLowerCase(Locale.ROOT);
        String rawQuery = candidate.getRawQuery();
        String decoded = candidate.getPath()
                + (candidate.getQuery() == null ? "" : "?" + candidate.getQuery());
        if (!"https".equalsIgnoreCase(candidate.getScheme()) || host.isBlank()
                || candidate.getUserInfo() != null || candidate.getFragment() != null
                || (candidate.getPort() != -1 && candidate.getPort() != 443)
                || candidate.getPath() == null
                || !candidate.getPath().startsWith(accessTicketPathPrefix)
                || candidate.getPath().length() <= accessTicketPathPrefix.length()
                || host.equals("localhost") || host.endsWith(".local")
                || host.matches("^[0-9a-f:.]+$")
                || !accessTicketAllowedHosts.contains(host)
                || (rawQuery != null && !rawQuery.matches(
                        "^(token|ticket)=[A-Za-z0-9._~-]{16,4096}$"))
                || candidate.toString().contains(command.objectKey())
                || decoded.contains(command.objectKey())
                || candidate.toString().contains(command.sourceSha256())
                || decoded.contains(command.sourceSha256())) {
            throw unavailable();
        }
        return candidate;
    }

    private String validatedAccessPathPrefix(String value) {
        String prefix = value == null ? "" : value.trim();
        if (!prefix.matches("^/[A-Za-z0-9._~/-]{1,200}/$")
                || prefix.contains("//") || prefix.contains("/../")
                || prefix.contains("/./")) {
            throw new IllegalArgumentException(
                    "Recording access ticket path prefix is invalid.");
        }
        return prefix;
    }

    private boolean safeCode(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength
                && value.matches("^[A-Za-z0-9][A-Za-z0-9._:-]*$");
    }

    private URI validatedOrigin(MeetingRecordingHttpProperties properties) {
        URI candidate;
        try {
            candidate = URI.create(properties.getBaseUrl().trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Recording provider base URL is invalid.");
        }
        if (!"https".equalsIgnoreCase(candidate.getScheme()) || candidate.getHost() == null
                || candidate.getUserInfo() != null || candidate.getQuery() != null
                || candidate.getFragment() != null
                || (candidate.getPort() != -1 && candidate.getPort() != 443)
                || (candidate.getPath() != null && !candidate.getPath().isBlank()
                    && !"/".equals(candidate.getPath()))) {
            throw new IllegalArgumentException("Recording provider URL must be an HTTPS origin.");
        }
        String host = candidate.getHost().toLowerCase(Locale.ROOT);
        Set<String> allowlist = properties.getAllowedHosts().stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (host.equals("localhost") || host.endsWith(".local")
                || host.matches("^[0-9a-f:.]+$") || !allowlist.contains(host)) {
            throw new IllegalArgumentException("Recording provider host is not allowlisted.");
        }
        return URI.create("https://" + host);
    }

    private String requiredToken(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < 32 || normalized.length() > 4_096
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Recording provider token is invalid.");
        }
        return normalized;
    }

    private String requiredRegion(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$")) {
            throw new IllegalArgumentException("Recording provider processing region is invalid.");
        }
        return normalized;
    }

    private Duration bounded(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Recording provider duration is invalid.");
        }
        return value;
    }

    private static HttpClient httpClient(MeetingRecordingHttpProperties properties) {
        Duration timeout = properties.getConnectTimeout();
        if (timeout == null || timeout.compareTo(Duration.ofMillis(100)) < 0
                || timeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Recording provider connect timeout is invalid.");
        }
        return HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("Meeting recording provider is unavailable.");
    }

    private record CapabilityResponse(
            String schemaVersion,
            boolean available,
            boolean egressAvailable,
            boolean storageAvailable,
            boolean speechToTextAvailable,
            boolean deletionAvailable,
            boolean cryptoShredAvailable,
            boolean customerManagedStorage,
            boolean providerRetentionDisabled,
            String processingRegion,
            String providerCode) {
    }

    private record CommandRequest(
            String schemaVersion,
            String commandType,
            long tenantId,
            UUID meetingId,
            UUID recordingSessionId,
            long planVersion,
            UUID noticeId,
            String providerRoomName) {
    }

    private record CommandResponse(
            String schemaVersion,
            UUID recordingSessionId,
            String commandState,
            String providerCommandId) {
    }

    private record AccessTicketRequest(
            String schemaVersion,
            long tenantId,
            UUID meetingId,
            UUID artifactId,
            long requesterUserId,
            String storageProvider,
            String objectKey,
            String contentType,
            String sourceSha256,
            long artifactVersion,
            OffsetDateTime expiresNoLaterThan) {
    }

    private record AccessTicketResponse(
            String schemaVersion,
            UUID artifactId,
            long requesterUserId,
            long artifactVersion,
            String sourceSha256,
            String accessUrl,
            OffsetDateTime expiresAt) {
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
