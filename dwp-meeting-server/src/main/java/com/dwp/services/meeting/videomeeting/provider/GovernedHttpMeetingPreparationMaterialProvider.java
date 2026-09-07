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
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** HTTPS-only, redirect-free adapter to an owner service that revalidates source ACLs. */
final class GovernedHttpMeetingPreparationMaterialProvider
        implements MeetingPreparationMaterialProvider {

    private static final String ACCESS_PATH =
            "/internal/v1/meeting-preparation-material/access-ticket";
    private static final String SCHEMA = "meeting-preparation-material-access-ticket-v1";

    private final URI origin;
    private final String token;
    private final Duration requestTimeout;
    private final Duration accessTicketTtl;
    private final int maximumResponseBytes;
    private final Set<String> accessTicketAllowedHosts;
    private final String accessTicketPathPrefix;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final MeetingWorkloadAssertionSigner signer;

    GovernedHttpMeetingPreparationMaterialProvider(
            MeetingPreparationMaterialHttpProperties properties,
            ObjectMapper mapper,
            MeetingWorkloadAssertionSigner signer) {
        this(properties, mapper, signer, httpClient(properties));
    }

    GovernedHttpMeetingPreparationMaterialProvider(
            MeetingPreparationMaterialHttpProperties properties,
            ObjectMapper mapper,
            MeetingWorkloadAssertionSigner signer,
            HttpClient client) {
        origin = validatedOrigin(properties);
        token = requiredToken(properties.getServiceToken());
        requestTimeout = bounded(
                properties.getRequestTimeout(), Duration.ofMillis(250), Duration.ofSeconds(30));
        bounded(properties.getConnectTimeout(), Duration.ofMillis(100), Duration.ofSeconds(5));
        accessTicketTtl = bounded(
                properties.getAccessTicketTtl(), Duration.ofSeconds(30), Duration.ofMinutes(10));
        if (properties.getMaximumResponseBytes() < 1_024
                || properties.getMaximumResponseBytes() > 1_000_000) {
            throw new IllegalArgumentException("Material provider response size is invalid.");
        }
        maximumResponseBytes = properties.getMaximumResponseBytes();
        accessTicketAllowedHosts = properties.getAccessTicketAllowedHosts().stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        accessTicketPathPrefix = validatedAccessPathPrefix(
                properties.getAccessTicketPathPrefix());
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.signer = Objects.requireNonNull(signer, "signer");
    }

    @Override
    public AccessTicket issueAccessTicket(AccessRequest command) {
        validate(command);
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(new AccessTicketRequest(
                    SCHEMA, command.tenantId(), command.meetingId(), command.materialId(),
                    command.requesterUserId(), command.referenceProvider(),
                    command.opaqueReference(), command.sourceVersion(),
                    command.classification(), command.contentType(), command.contentSha256(),
                    command.referenceBindingSha256(), command.materialVersion(),
                    command.expiresNoLaterThan()));
        } catch (IOException exception) {
            throw unavailable();
        }
        MeetingIntelligenceProvider.ExecutionContext workload =
                new MeetingIntelligenceProvider.ExecutionContext(
                        command.tenantId(), command.meetingId(),
                        command.materialId(), command.correlationId());
        HttpRequest request = HttpRequest.newBuilder(origin.resolve(ACCESS_PATH))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-DWP-Meeting-Material-Token", token)
                .header("X-DWP-Tenant-ID", Long.toString(command.tenantId()))
                .header("X-DWP-Meeting-ID", command.meetingId().toString())
                .header("X-DWP-Meeting-Material-ID", command.materialId().toString())
                .header("X-DWP-Requester-User-ID", Long.toString(command.requesterUserId()))
                .header("X-Correlation-ID", command.correlationId())
                .header("X-DWP-Meeting-Workload-Assertion",
                        signer.sign(workload, "POST", ACCESS_PATH, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        AccessTicketResponse response = send(request);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!SCHEMA.equals(response.schemaVersion())
                || !command.materialId().equals(response.materialId())
                || response.requesterUserId() != command.requesterUserId()
                || response.materialVersion() != command.materialVersion()
                || !constantEquals(
                        command.referenceBindingSha256(), response.referenceBindingSha256())
                || response.expiresAt() == null || !response.expiresAt().isAfter(now)
                || response.expiresAt().isAfter(now.plus(accessTicketTtl))
                || response.expiresAt().isAfter(command.expiresNoLaterThan())) {
            throw unavailable();
        }
        return new AccessTicket(
                response.materialId(), response.requesterUserId(), response.materialVersion(),
                response.referenceBindingSha256(), validatedAccessUri(response.accessUrl(), command),
                response.expiresAt());
    }

    private AccessTicketResponse send(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) throw unavailable();
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("application/json")) throw unavailable();
            return mapper.readValue(
                    BoundedHttpResponseReader.read(response, maximumResponseBytes),
                    AccessTicketResponse.class);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | RuntimeException exception) {
            throw unavailable();
        }
    }

    private void validate(AccessRequest command) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (command == null || command.tenantId() <= 0 || command.meetingId() == null
                || command.materialId() == null || command.requesterUserId() <= 0
                || !Set.of("DWP_FILES", "SHAREPOINT", "CONFLUENCE")
                        .contains(command.referenceProvider())
                || command.opaqueReference() == null
                || !command.opaqueReference().matches("^[A-Za-z0-9][A-Za-z0-9._/-]{0,159}$")
                || command.sourceVersion() != null
                        && !command.sourceVersion().matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$")
                || !Set.of("INTERNAL", "CONFIDENTIAL", "RESTRICTED")
                        .contains(command.classification())
                || command.contentType() == null || command.contentType().length() > 120
                || !command.contentType().matches(
                        "^[a-z0-9][a-z0-9.+-]{0,63}/[a-z0-9][a-z0-9.+-]{0,63}$")
                || command.contentSha256() != null
                        && !command.contentSha256().matches("^[0-9a-f]{64}$")
                || command.referenceBindingSha256() == null
                || !command.referenceBindingSha256().matches("^[0-9a-f]{64}$")
                || command.materialVersion() < 0 || command.expiresNoLaterThan() == null
                || !command.expiresNoLaterThan().isAfter(now)
                || command.expiresNoLaterThan().isAfter(now.plus(accessTicketTtl))
                || command.correlationId() == null || command.correlationId().isBlank()
                || command.correlationId().length() > 160) {
            throw new IllegalArgumentException("Material access context is invalid.");
        }
    }

    private URI validatedAccessUri(String value, AccessRequest command) {
        URI candidate;
        try {
            if (value == null || value.isBlank() || value.length() > 8_192) throw unavailable();
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
                || host.matches("^[0-9a-f:.]+$") || !accessTicketAllowedHosts.contains(host)
                || rawQuery != null && !rawQuery.matches(
                        "^(token|ticket)=[A-Za-z0-9._~-]{16,4096}$")
                || candidate.toString().contains(command.opaqueReference())
                || decoded.contains(command.opaqueReference())
                || candidate.toString().contains(command.referenceBindingSha256())
                || command.contentSha256() != null
                        && candidate.toString().contains(command.contentSha256())) {
            throw unavailable();
        }
        return candidate;
    }

    private URI validatedOrigin(MeetingPreparationMaterialHttpProperties properties) {
        URI candidate;
        try {
            candidate = URI.create(properties.getBaseUrl().trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Material provider base URL is invalid.");
        }
        if (!"https".equalsIgnoreCase(candidate.getScheme()) || candidate.getHost() == null
                || candidate.getUserInfo() != null || candidate.getQuery() != null
                || candidate.getFragment() != null
                || (candidate.getPort() != -1 && candidate.getPort() != 443)
                || candidate.getPath() != null && !candidate.getPath().isBlank()
                        && !"/".equals(candidate.getPath())) {
            throw new IllegalArgumentException("Material provider URL must be an HTTPS origin.");
        }
        String host = candidate.getHost().toLowerCase(Locale.ROOT);
        Set<String> allowlist = properties.getAllowedHosts().stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (host.equals("localhost") || host.endsWith(".local")
                || host.matches("^[0-9a-f:.]+$") || !allowlist.contains(host)) {
            throw new IllegalArgumentException("Material provider host is not allowlisted.");
        }
        return URI.create("https://" + host);
    }

    private String validatedAccessPathPrefix(String value) {
        String prefix = value == null ? "" : value.trim();
        if (!prefix.matches("^/[A-Za-z0-9._~/-]{1,200}/$")
                || prefix.contains("//") || prefix.contains("/../")
                || prefix.contains("/./")) {
            throw new IllegalArgumentException("Material access path prefix is invalid.");
        }
        return prefix;
    }

    private String requiredToken(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < 32 || normalized.length() > 4_096
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Material provider token is invalid.");
        }
        return normalized;
    }

    private boolean constantEquals(String first, String second) {
        return second != null && MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    private Duration bounded(Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Material provider duration is invalid.");
        }
        return value;
    }

    private static HttpClient httpClient(MeetingPreparationMaterialHttpProperties properties) {
        Duration timeout = properties.getConnectTimeout();
        if (timeout == null || timeout.compareTo(Duration.ofMillis(100)) < 0
                || timeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Material provider connect timeout is invalid.");
        }
        return HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("Meeting preparation material provider is unavailable.");
    }

    private record AccessTicketRequest(
            String schemaVersion,
            long tenantId,
            UUID meetingId,
            UUID materialId,
            long requesterUserId,
            String referenceProvider,
            String opaqueReference,
            String sourceVersion,
            String classification,
            String contentType,
            String contentSha256,
            String referenceBindingSha256,
            long materialVersion,
            OffsetDateTime expiresNoLaterThan) {
    }

    private record AccessTicketResponse(
            String schemaVersion,
            UUID materialId,
            long requesterUserId,
            long materialVersion,
            String referenceBindingSha256,
            String accessUrl,
            OffsetDateTime expiresAt) {
    }
}
