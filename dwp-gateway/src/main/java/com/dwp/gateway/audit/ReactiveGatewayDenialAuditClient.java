package com.dwp.gateway.audit;

import com.dwp.audit.AuditEvent;
import com.dwp.audit.HttpAuditEventPublisher;
import com.dwp.gateway.filter.SupportSessionContextFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Sends a single privacy-minimized denial event before the caller commits its 403 response. */
@Component
public final class ReactiveGatewayDenialAuditClient implements GatewayDenialAuditSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ReactiveGatewayDenialAuditClient.class);
    private static final String SERVICE_NAME = "dwp-gateway";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HASH_PREFIX = "hmac-sha256:";
    private static final String TRACE_PARENT_HEADER = "traceparent";
    private static final String TRACE_STATE_HEADER = "tracestate";

    private final WebClient webClient;
    private final URI collectorUri;
    private final String ingestToken;
    private final byte[] privacyKey;
    private final String environment;
    private final String serviceInstance;
    private final Duration timeout;

    public ReactiveGatewayDenialAuditClient(
            WebClient.Builder webClientBuilder,
            @Value("${dwp.audit.collector-url:}") String collectorUrl,
            @Value("${dwp.audit.ingest-token:}") String ingestToken,
            @Value("${dwp.audit.privacy-hash-secret:}") String privacyHashSecret,
            @Value("${dwp.audit.environment:local}") String environment,
            @Value("${dwp.audit.service-instance:local}") String serviceInstance,
            @Value("${dwp.gateway.denial-audit.timeout:1s}") Duration timeout) {
        this.webClient = webClientBuilder.clone().build();
        this.collectorUri = uri(collectorUrl);
        this.ingestToken = trim(ingestToken);
        this.privacyKey = trim(privacyHashSecret).getBytes(StandardCharsets.UTF_8);
        this.environment = normalized(environment, "local", 40);
        this.serviceInstance = normalized(serviceInstance, "unknown", 120);
        this.timeout = timeout;
    }

    @Override
    public Mono<Void> publish(ServerWebExchange exchange, Denial denial) {
        UUID eventId = UUID.randomUUID();
        return Mono.fromCallable(() -> event(exchange, denial, eventId))
                .flatMap(event -> send(event).timeout(timeout))
                .onErrorMap(error -> !(error instanceof AuditSinkUnavailableException),
                        AuditSinkUnavailableException::new)
                .doOnError(error -> LOGGER.warn(
                        "Gateway denial audit delivery failed; eventId={} error={}",
                        eventId, error.getClass().getSimpleName()));
    }

    AuditEvent event(ServerWebExchange exchange, Denial denial, UUID eventId) {
        requireConfigured();
        Long tenantId = positive(exchange.getRequest().getHeaders().getFirst(
                VerifiedIdentityFilter.TENANT_HEADER));
        if (tenantId == null) throw new AuditSinkUnavailableException();
        String actor = exchange.getRequest().getHeaders().getFirst(
                VerifiedIdentityFilter.USER_HEADER);
        String session = exchange.getRequest().getHeaders().getFirst(
                VerifiedIdentityFilter.AUTH_SESSION_ID_HEADER);
        String identityPlane = enumValue(exchange.getRequest().getHeaders().getFirst(
                VerifiedIdentityFilter.IDENTITY_PLANE_HEADER), "UNKNOWN");
        String method = exchange.getRequest().getMethod() == null
                ? "UNKNOWN" : exchange.getRequest().getMethod().name();
        String routeTemplate = routeTemplate(denial.routeTemplate(),
                exchange.getRequest().getURI().getPath());
        String denialCode = denialCode(denial.denialCode());
        Map<String, Object> metadata = Map.of(
                "schemaVersion", "gateway-denial.v1",
                "method", method,
                "routeTemplate", routeTemplate,
                "httpStatus", 403,
                "denialCode", denialCode,
                "identityPlane", identityPlane,
                "traceStatePresent", exchange.getRequest().getHeaders().containsKey(
                        TRACE_STATE_HEADER),
                "supportContextPresent", exchange.getRequest().getHeaders().containsKey(
                        SupportSessionContextFilter.SUPPORT_SESSION_HEADER));
        return AuditEvent.builder()
                .eventId(eventId)
                .tenantId(tenantId)
                .category("AUTHORIZATION")
                .action(normalized(denial.action(), "gateway.authorization.denied", 120))
                .outcome("DENIED")
                .severity("HIGH")
                .riskScore(Math.max(0, Math.min(100, denial.riskScore())))
                .actorType("USER")
                .actorId(hash("actor", actor))
                .sourceService(SERVICE_NAME)
                .sourceModule("provider-tenant-authorization-boundary")
                .sourceInstance(serviceInstance)
                .environment(environment)
                .targetType("GATEWAY_ROUTE")
                .targetId(routeTemplate)
                .reason(denialCode)
                .correlationId(correlationId(exchange))
                .traceId(traceId(exchange))
                .sessionIdHash(hash("session", session))
                .clientAddressHash(hash("address", clientAddress(exchange)))
                .authenticationMethod("SESSION")
                .policyId(normalized(denial.policyId(), "GATEWAY_AUTHORIZATION_BOUNDARY_V1", 120))
                .policyDecision("DENY")
                .metadata(metadata)
                .retentionClass("EXTENDED")
                .build();
    }

    static String routeTemplate(String declared, String path) {
        String value = trim(declared);
        if (value.startsWith("/api/") && value.length() <= 240
                && value.matches("[A-Za-z0-9_{}*/.:-]+")) return value;
        String[] segments = path == null ? new String[0] : path.split("/", -1);
        if (segments.length >= 3 && "api".equals(segments[1])
                && segments[2].matches("[a-z][a-z0-9-]{0,39}")) {
            return "/api/" + segments[2] + "/**";
        }
        return "/api/**";
    }

    private Mono<Void> send(AuditEvent event) {
        return webClient.post()
                .uri(collectorUri)
                .header(HttpAuditEventPublisher.INGEST_TOKEN_HEADER, ingestToken)
                .header(HttpAuditEventPublisher.SERVICE_NAME_HEADER, SERVICE_NAME)
                .bodyValue(List.of(event))
                .exchangeToMono(response -> accepted(response.statusCode())
                        ? response.releaseBody()
                        : response.releaseBody().then(Mono.error(
                                new AuditSinkUnavailableException())))
                .onErrorMap(error -> !(error instanceof AuditSinkUnavailableException),
                        AuditSinkUnavailableException::new);
    }

    private boolean accepted(HttpStatusCode status) {
        return status.is2xxSuccessful();
    }

    private void requireConfigured() {
        if (collectorUri == null || ingestToken.length() < 24 || privacyKey.length < 24
                || timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new AuditSinkUnavailableException();
        }
    }

    private String hash(String namespace, String value) {
        String normalized = trim(value);
        if (normalized.isEmpty()) return null;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(privacyKey, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal((namespace + ":" + normalized)
                    .getBytes(StandardCharsets.UTF_8));
            return HASH_PREFIX + HexFormat.of().formatHex(digest);
        } catch (java.security.GeneralSecurityException exception) {
            throw new AuditSinkUnavailableException(exception);
        }
    }

    private String clientAddress(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? null : remote.getAddress().getHostAddress();
    }

    private String correlationId(ServerWebExchange exchange) {
        String value = trim(exchange.getRequest().getHeaders().getFirst("X-Correlation-ID"));
        return value.matches("[A-Za-z0-9._:-]{1,128}") ? value : null;
    }

    private String traceId(ServerWebExchange exchange) {
        String value = trim(exchange.getRequest().getHeaders().getFirst(TRACE_PARENT_HEADER));
        return value.matches("[0-9a-fA-F]{2}-[0-9a-fA-F]{32}-[0-9a-fA-F]{16}-[0-9a-fA-F]{2}")
                ? value.substring(3, 35).toLowerCase(Locale.ROOT) : null;
    }

    private String denialCode(String value) {
        String normalized = trim(value).toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z][A-Z0-9_]{2,119}")
                ? normalized : "AUTHORIZATION_DENIED";
    }

    private String enumValue(String value, String fallback) {
        String normalized = trim(value).toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z][A-Z0-9_]{1,39}") ? normalized : fallback;
    }

    private Long positive(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static URI uri(String value) {
        try {
            URI parsed = URI.create(trim(value));
            if (!("http".equalsIgnoreCase(parsed.getScheme())
                    || "https".equalsIgnoreCase(parsed.getScheme()))
                    || parsed.getHost() == null || parsed.getUserInfo() != null
                    || !"/internal/audit/events".equals(parsed.getRawPath())
                    || parsed.getRawQuery() != null || parsed.getRawFragment() != null) return null;
            return parsed;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String normalized(String value, String fallback, int maxLength) {
        String normalized = trim(value);
        if (normalized.isEmpty()) return fallback;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class AuditSinkUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public AuditSinkUnavailableException() {
        }

        AuditSinkUnavailableException(Throwable cause) {
            super(cause);
        }
    }
}
