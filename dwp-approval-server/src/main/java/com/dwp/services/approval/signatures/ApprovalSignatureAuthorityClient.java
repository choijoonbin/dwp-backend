package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Set;

/** Fixed-purpose, bounded, single-attempt source transport. No caller credentials or redirects. */
public class ApprovalSignatureAuthorityClient {
    private static final String ENDPOINT_PATH = "/internal/auth/v1/approval-signature-authority/evaluate";
    private static final String TOKEN_HEADER = "X-DWP-Approval-Signature-Source-Token";
    private final URI endpoint;
    private final ApprovalSignatureSourceKeys keys;
    private final ApprovalSignatureCanonical canonical;
    private final HttpClient http;
    ApprovalSignatureAuthorityClient(URI endpoint, ApprovalSignatureSourceKeys keys, ApprovalSignatureCanonical canonical) {
        if (!ENDPOINT_PATH.equals(ApprovalSignatureSourceExchange.PATH) || !TOKEN_HEADER.equals(ApprovalSignatureSourceExchange.HEADER)
                || endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !ENDPOINT_PATH.equals(endpoint.getRawPath()) || !("https".equals(endpoint.getScheme())
                    || "http".equals(endpoint.getScheme()) && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(endpoint.getHost()))) throw unavailable();
        this.endpoint = endpoint; this.keys = keys; this.canonical = canonical;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "authApprovalSignatureAuthority")
    @io.github.resilience4j.bulkhead.annotation.Bulkhead(name = "authApprovalSignatureAuthority")
    public String evaluate(ApprovalSignatureSourceExchange.Exchange exchange) {
        if (exchange == null || exchange.body().length > 65536 || exchange.token() == null || exchange.token().length() > 32768) throw denied();
        var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("X-DWP-Service-Identity", "dwp-approval-server").header(TOKEN_HEADER, exchange.token());
        var trace = new org.springframework.http.HttpHeaders(); com.dwp.core.http.OutboundHttpHeaders.propagateObservability(trace);
        trace.forEach((name, values) -> {
            if (Set.of("traceparent", "tracestate", "x-correlation-id").contains(name.toLowerCase(java.util.Locale.ROOT)))
                values.forEach(value -> builder.header(name, value));
        });
        var body = new ApprovalSignatureResponseBody();
        var pending = http.sendAsync(builder.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build(), info -> body);
        try {
            var response = pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (response.statusCode() == 401 || response.statusCode() == 403) throw denied();
                if (response.statusCode() == 409) throw conflict(); if (response.statusCode() != 200) throw unavailable();
                var type = response.headers().allValues("Content-Type");
                if (type.size() != 1 || !"application/json".equals(type.getFirst().split(";", 2)[0].strip())) throw denied();
                byte[] bytes = response.body();
                JsonNode result = canonical.read(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), JsonNode.class);
                if (!result.isObject() || !"SUCCESS".equals(result.path("status").asText()) || !result.path("data").isObject()
                        || result.path("data").size() != 1 || !result.path("data").path("assertion").isTextual()) throw denied();
                String assertion = result.path("data").path("assertion").textValue();
                SignedJWT jwt = SignedJWT.parse(assertion); var key = keys.authority.getKeyByKeyId(jwt.getHeader().getKeyID());
                if (!(key instanceof RSAKey rsa) || !jwt.verify(new RSASSAVerifier(rsa))) throw denied();
                var claims = jwt.getJWTClaimsSet(); var b = exchange.bindings();
                for (String field : Set.of("nonce", "contextKey", "contextScopeKey", "resourceSetKey", "decisionRevision", "registrySha256", "sourceSha256", "bodySha256"))
                    if (!java.util.Objects.equals(b.get(field), claims.getClaim(field))) throw denied();
                return assertion;
        } catch (com.dwp.core.exception.BaseException invalid) { throw invalid; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (java.util.concurrent.ExecutionException invalid) {
            if (invalid.getCause() instanceof com.dwp.core.exception.BaseException failure) throw failure;
            throw unavailable();
        }
        catch (Exception unavailable) { throw unavailable(); }
        finally { pending.cancel(true); body.cancel(); }
    }
}
