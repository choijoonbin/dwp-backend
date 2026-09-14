package com.dwp.services.approval.systemslaauthority;

import static com.dwp.services.approval.systemslaauthority.SystemSlaJson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;

/** Single attempt, fixed purpose, bounded whole exchange; no ambient user/Gateway credentials. */
public class AuthApprovalSystemSlaAuthorityClient {
    private static final String ENDPOINT_PATH = "/internal/auth/v1/approval-system-sla-authority/evaluate";
    private static final String TOKEN_HEADER = "X-DWP-Approval-System-Sla-Token";
    private final URI endpoint;
    private final HttpClient http;
    private final SystemSlaSourceAttestationVerifier verifier;
    public AuthApprovalSystemSlaAuthorityClient(URI endpoint, SystemSlaSourceAttestationVerifier verifier) {
        if (!ENDPOINT_PATH.equals(SystemSlaSourceProtocol.PATH) || !TOKEN_HEADER.equals(SystemSlaSourceProtocol.HEADER) || endpoint == null
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !ENDPOINT_PATH.equals(endpoint.getRawPath()) || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme())
                    && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(endpoint.getHost()))) throw unavailable();
        this.endpoint = endpoint; this.verifier = verifier;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "authApprovalSystemSlaAuthority")
    @io.github.resilience4j.bulkhead.annotation.Bulkhead(name = "authApprovalSystemSlaAuthority")
    public SystemSlaSourceAttestationVerifier.Verified evaluate(SystemSlaSourceProofIssuer.Exchange exchange) {
        if (exchange == null || exchange.body().length > SystemSlaSourceProtocol.BODY_LIMIT || exchange.transport().length() > 2048) throw denied();
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("X-DWP-Service-Identity", "dwp-approval-server").header(TOKEN_HEADER, exchange.transport());
        var trace = new org.springframework.http.HttpHeaders(); com.dwp.core.http.OutboundHttpHeaders.propagateObservability(trace);
        trace.forEach((name, values) -> {
            if (Set.of("traceparent", "tracestate", "x-correlation-id").contains(name.toLowerCase(java.util.Locale.ROOT))) values.forEach(value -> request.header(name, value));
        });
        var body = new SystemSlaSourceResponseBody();
        var pending = http.sendAsync(request.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build(), info -> body);
        try {
            var response = pending.get(5, TimeUnit.SECONDS);
            if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 404) throw denied();
            if (response.statusCode() == 409) throw changed(); if (response.statusCode() != 200) throw unavailable();
            var types = response.headers().allValues("Content-Type"); if (types.size() != 1) throw denied();
            var type = MediaType.parseMediaType(types.getFirst());
            if (!MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type) || type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset())) throw denied();
            return verifier.verify(response.body(), exchange);
        } catch (com.dwp.core.exception.BaseException failure) { throw failure; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof com.dwp.core.exception.BaseException error) throw error; throw unavailable();
        } catch (Exception unavailable) { throw unavailable(); }
        finally { pending.cancel(true); body.cancel(); }
    }
}
