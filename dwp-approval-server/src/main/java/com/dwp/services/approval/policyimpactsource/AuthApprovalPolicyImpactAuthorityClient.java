package com.dwp.services.approval.policyimpactsource;

import static com.dwp.services.approval.policyimpactsource.PolicyImpactSourceJson.*;
import static com.dwp.services.approval.policyimpactsource.PolicyImpactSourceProtocol.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Dedicated single-attempt fixed-purpose transport; it never borrows Gateway/user credentials. */
public class AuthApprovalPolicyImpactAuthorityClient {
    private static final String ENDPOINT_PATH = "/internal/auth/v1/approval-policy-impact-authority/evaluate";
    private static final String TOKEN_HEADER = "X-DWP-Approval-Policy-Impact-Token";
    private final URI endpoint;
    private final HttpClient http;
    private final PolicyImpactSourceAttestationVerifier verifier;
    public AuthApprovalPolicyImpactAuthorityClient(URI endpoint, PolicyImpactSourceAttestationVerifier verifier) {
        if (!ENDPOINT_PATH.equals(PATH) || !TOKEN_HEADER.equals(HEADER) || endpoint == null || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null || !ENDPOINT_PATH.equals(endpoint.getRawPath())
                || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme())
                    && java.util.Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(endpoint.getHost()))) throw unavailable();
        this.endpoint = endpoint; this.verifier = verifier;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "authApprovalPolicyImpactAuthority")
    @io.github.resilience4j.bulkhead.annotation.Bulkhead(name = "authApprovalPolicyImpactAuthority")
    public PolicyImpactSourceAttestationVerifier.Verified evaluate(PolicyImpactSourceProofIssuer.Exchange exchange) {
        if (exchange == null || exchange.body().length > BODY_LIMIT || exchange.transport().length() > TRANSPORT_LIMIT) throw denied();
        var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("X-DWP-Service-Identity", "dwp-approval-server").header(TOKEN_HEADER, exchange.transport());
        var trace = new org.springframework.http.HttpHeaders(); com.dwp.core.http.OutboundHttpHeaders.propagateObservability(trace);
        trace.forEach((name, values) -> {
            if (java.util.Set.of("traceparent", "tracestate", "x-correlation-id").contains(name.toLowerCase(java.util.Locale.ROOT)))
                values.forEach(value -> builder.header(name, value));
        });
        var body = new PolicyImpactSourceResponseBody();
        var pending = http.sendAsync(builder.POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build(), info -> body);
        try {
            var response = pending.get(5, TimeUnit.SECONDS);
            if (response.statusCode() == 401 || response.statusCode() == 403) throw denied();
            if (response.statusCode() == 409) throw changed();
            if (response.statusCode() != 200) throw unavailable();
            var content = response.headers().allValues("Content-Type");
            if (content.size() != 1 || !content.getFirst().split(";", 2)[0].strip().equals("application/json")) throw denied();
            return verifier.verify(response.body(), exchange);
        } catch (com.dwp.core.exception.BaseException error) { throw error; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof com.dwp.core.exception.BaseException failure) throw failure; throw unavailable();
        } catch (java.util.concurrent.TimeoutException error) { throw unavailable(); }
        finally { pending.cancel(true); body.cancel(); }
    }
}
