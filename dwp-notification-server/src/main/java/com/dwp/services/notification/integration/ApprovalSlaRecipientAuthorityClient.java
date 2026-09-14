package com.dwp.services.notification.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class ApprovalSlaRecipientAuthorityClient {
    private static final String ENDPOINT_PATH = "/internal/approval/v1/quorum-sla/recipient-authority/evaluate";
    private static final String TOKEN_HEADER = "X-DWP-Notification-Approval-System-Sla-Token";
    private final URI endpoint;
    private final HttpClient http;
    private final ApprovalSlaTransportProof issuer;
    private final ApprovalSlaRecipientAuthority verifier;

    public ApprovalSlaRecipientAuthorityClient(URI endpoint, ApprovalSlaTransportProof issuer,
                                               ApprovalSlaRecipientAuthority verifier) {
        if (issuer == null || verifier == null || endpoint == null || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !ENDPOINT_PATH.equals(endpoint.getRawPath())
                || !ENDPOINT_PATH.equals(ApprovalSlaTransportProof.PATH)
                || !TOKEN_HEADER.equals(ApprovalSlaTransportProof.HEADER)
                || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme())
                && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(endpoint.getHost()))) throw unavailable();
        this.endpoint = endpoint; this.issuer = issuer; this.verifier = verifier;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public ApprovalSlaRecipientAuthority.Verified evaluate(ApprovalSlaNotificationPlan plan) {
        ApprovalSlaTransportProof.Exchange exchange = issuer.issue(plan);
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-DWP-Service-Identity", "dwp-notification-server").header(TOKEN_HEADER, exchange.token());
        var trace = new org.springframework.http.HttpHeaders();
        com.dwp.core.http.OutboundHttpHeaders.propagateObservability(trace);
        trace.forEach((name, values) -> {
            if (Set.of("traceparent", "tracestate", "x-correlation-id").contains(name.toLowerCase(Locale.ROOT)))
                values.forEach(value -> request.header(name, value));
        });
        var body = new ApprovalSlaAuthorityResponseBody();
        var pending = http.sendAsync(request.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build(), info -> body);
        try {
            var response = pending.get(5, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw unavailable();
            var types = response.headers().allValues("Content-Type");
            if (types.size() != 1 || !utf8Json(types.getFirst())) throw unavailable();
            return verifier.verify(response.body(), plan, exchange.nonce(), exchange.bodySha256(), exchange.expiresAt());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            throw unavailable();
        } finally { pending.cancel(true); body.cancel(); }
    }
    private static boolean utf8Json(String value) {
        if (value == null || value.length() > 160 || value.split(";", -1).length > 2
                || value.chars().anyMatch(character -> character < 32 || character == 127)) return false;
        try {
            var type = org.springframework.http.MediaType.parseMediaType(value);
            return "application".equals(type.getType()) && "json".equals(type.getSubtype())
                    && (type.getParameters().isEmpty() || type.getParameters().size() == 1
                    && type.getParameters().containsKey("charset")
                    && java.nio.charset.StandardCharsets.UTF_8.equals(type.getCharset()));
        } catch (IllegalArgumentException error) { return false; }
    }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("Current Approval SLA authority is unavailable or rejected.");
    }
}
