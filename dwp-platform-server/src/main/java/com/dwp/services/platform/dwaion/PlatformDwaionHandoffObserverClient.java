package com.dwp.services.platform.dwaion;

import com.dwp.services.platform.dwaion.PlatformDwaionHandoffOutboxRepository.Delivery;
import com.dwp.services.platform.dwaion.PlatformDwaionHandoffOutboxRepository.ObservationSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class PlatformDwaionHandoffObserverClient {
    static final String OBSERVATION_PATH = "/internal/v1/proposal-handoffs/%s/observations";
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI agentBaseUri;
    private final String serviceToken;
    private final String workerToken;
    private final byte[] identitySecret;
    private final String identityKeyId;
    private final Clock clock;
    private final Supplier<UUID> nonce;

    @Autowired
    public PlatformDwaionHandoffObserverClient(
            ObjectMapper json,
            @Value("${dwp.platform.dwaion-handoff.agent-url:http://localhost:8010}") String agentUrl,
            @Value("${dwp.platform.dwaion-handoff.service-token:}") String serviceToken,
            @Value("${dwp.platform.dwaion-handoff.worker-token:}") String workerToken,
            @Value("${dwp.platform.dwaion-handoff.identity-signing-secret:}") String identitySecret,
            @Value("${dwp.platform.dwaion-handoff.identity-key-id:gateway-agent-v1}") String identityKeyId) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), json,
                agentUrl, serviceToken, workerToken, identitySecret, identityKeyId,
                Clock.systemUTC(), UUID::randomUUID);
    }

    PlatformDwaionHandoffObserverClient(
            HttpClient http,
            ObjectMapper json,
            String agentUrl,
            String serviceToken,
            String workerToken,
            String identitySecret,
            String identityKeyId,
            Clock clock,
            Supplier<UUID> nonce) {
        this.http = http;
        this.json = json;
        this.agentBaseUri = baseUri(agentUrl);
        this.serviceToken = cleanSecret(serviceToken);
        this.workerToken = cleanSecret(workerToken);
        this.identitySecret = cleanSecret(identitySecret).getBytes(StandardCharsets.UTF_8);
        this.identityKeyId = identityKeyId == null || identityKeyId.isBlank()
                ? "gateway-agent-v1" : identityKeyId.strip();
        this.clock = clock;
        this.nonce = nonce;
    }

    public ObservationSnapshot observe(Delivery delivery) {
        requireConfigured();
        String path = OBSERVATION_PATH.formatted(delivery.handoffId());
        String state = delivery.nextObservation();
        Map<String, Object> body = new TreeMap<>();
        body.put("commandId", commandId(delivery.handoffId(), state).toString());
        body.put("expectedVersion", delivery.handoffVersion());
        body.put("state", state);
        if ("COMPLETED".equals(state)) body.put("receipt", receipt(delivery));
        try {
            byte[] encoded = json.writeValueAsBytes(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder(agentBaseUri.resolve(path))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-DWP-Service-Token", serviceToken)
                    .header("X-DWP-Workflow-Worker-Token", workerToken)
                    .header("X-DWP-Tenant-ID", Long.toString(delivery.tenantId()))
                    .header("X-DWP-User-ID", Long.toString(delivery.ownerUserId()))
                    .header("X-Correlation-ID", delivery.correlationId())
                    .header("X-DWP-Auth-Session-ID", delivery.authSessionId())
                    .header("X-DWP-Identity-Plane", "TENANT")
                    .header("X-DWP-Access-Mode", "NORMAL")
                    .header("X-DWP-Roles", delivery.roles())
                    .header("X-DWP-Permissions", delivery.permissions());
            if (delivery.personPublicId() != null) {
                builder.header("X-DWP-Person-Public-ID", delivery.personPublicId().toString());
            }
            builder.header("X-DWP-Delegated-Identity", assertion(delivery, path));
            HttpResponse<String> response = http.send(
                    builder.POST(HttpRequest.BodyPublishers.ofByteArray(encoded)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().length() > 65_536) {
                throw new IllegalStateException(
                        "Agent observation returned HTTP " + response.statusCode());
            }
            JsonNode data = json.readTree(response.body()).path("data");
            ObservationSnapshot snapshot = new ObservationSnapshot(
                    UUID.fromString(requiredText(data, "handoffId")),
                    UUID.fromString(requiredText(data, "proposalId")),
                    requiredText(data, "actionKey"), requiredText(data, "state"),
                    data.path("version").longValue(),
                    data.path("receiptId").isTextual()
                            ? UUID.fromString(data.path("receiptId").textValue()) : null);
            if (snapshot.version() < 1) throw new IllegalStateException("Invalid Agent handoff version");
            return snapshot;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent observation was interrupted", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException failure) throw failure;
            throw new IllegalStateException("Agent observation failed", exception);
        }
    }

    static UUID commandId(UUID handoffId, String state) {
        return UUID.nameUUIDFromBytes(
                ("dwaion-platform:" + handoffId + ":" + state)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> receipt(Delivery delivery) {
        Map<String, Object> result = new TreeMap<>();
        result.put("domain", delivery.domain());
        result.put("operation", delivery.operation());
        result.put("handoffId", delivery.handoffId().toString());
        result.put("proposalId", delivery.proposalId().toString());
        result.put("actionKey", delivery.actionKey());
        result.put("handoffVersion", delivery.handoffVersion());
        result.put("status", delivery.domainStatus());
        result.put("committedAt", delivery.domainCommittedAt().toString());
        result.put("correlationId", delivery.correlationId());
        switch (delivery.domain()) {
            case "CALENDAR" -> {
                result.put("eventId", delivery.resourceId().toString());
                result.put("eventVersion", delivery.domainVersion());
            }
            case "MAIL" -> {
                result.put("threadId", delivery.resourceId().toString());
                result.put("threadVersion", delivery.domainVersion());
            }
            case "SERVICE" -> {
                result.put("requestId", delivery.resourceId().toString());
                result.put("requestVersion", delivery.domainVersion());
            }
            default -> throw new IllegalStateException("Unsupported DWAI-ON owner domain");
        }
        return result;
    }

    private String assertion(Delivery delivery, String path) throws Exception {
        long issuedAt = Instant.now(clock).getEpochSecond();
        Map<String, Object> header = new TreeMap<>();
        header.put("alg", "HS256");
        header.put("kid", identityKeyId);
        header.put("typ", "dwp-identity+jwt");
        Map<String, Object> claims = new TreeMap<>();
        claims.put("aud", "dwp-agent");
        claims.put("cid", delivery.correlationId());
        claims.put("exp", issuedAt + 15);
        claims.put("htm", "POST");
        claims.put("htu", path);
        claims.put("iat", issuedAt);
        claims.put("ip", "TENANT");
        claims.put("iss", "dwp-gateway");
        claims.put("jti", nonce.get().toString());
        claims.put("nbf", issuedAt - 1);
        claims.put("permissions", tokenList(delivery.permissions()));
        if (delivery.personPublicId() != null) {
            claims.put("pid", delivery.personPublicId().toString());
        }
        claims.put("roles", tokenList(delivery.roles()));
        claims.put("sid", delivery.authSessionId());
        claims.put("sub", Long.toString(delivery.ownerUserId()));
        claims.put("tid", Long.toString(delivery.tenantId()));
        String protectedHeader = encode(json.writeValueAsBytes(header));
        String payload = encode(json.writeValueAsBytes(claims));
        String signingInput = protectedHeader + "." + payload;
        return signingInput + "." + encode(sign(signingInput));
    }

    private byte[] sign(String input) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(identitySecret, "HmacSHA256"));
        return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
    }

    private void requireConfigured() {
        if (serviceToken.isBlank() || workerToken.isBlank() || identitySecret.length < 24
                || identityKeyId.isBlank() || identityKeyId.length() > 80) {
            throw new IllegalStateException("DWAI-ON Agent observation identity is not configured");
        }
    }

    private static List<String> tokenList(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::strip)
                .filter(item -> !item.isBlank()).toList();
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("Agent observation response is missing " + field);
        }
        return value.textValue();
    }

    private static URI baseUri(String value) {
        String clean = value == null ? "" : value.strip();
        if (!clean.endsWith("/")) clean += "/";
        URI uri = URI.create(clean);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("DWAI-ON Agent URL is invalid");
        }
        return uri;
    }

    private static String cleanSecret(String value) {
        return value == null ? "" : value.strip();
    }

    private static String encode(byte[] value) {
        return BASE64_URL.encodeToString(value);
    }
}
