package com.dwp.services.approval.hrisbridge.separation;

import com.dwp.core.http.OutboundHttpHeaders;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Auth-only same-tenant requester identity resolution; no legacy directory fallback. */
@Component
@ConditionalOnProperty(prefix = "dwp.approval.hrm013-bridge", name = "enabled",
        havingValue = "true")
final class Hrm013RequesterIdentityClient {
    private static final String AUTH_SPIFFE = "spiffe://dwp/service/dwp-auth-server";
    private final ObjectMapper mapper;
    private final HrisSeparationWorkloadCredentialProvider credentials;
    private final URI endpoint;

    Hrm013RequesterIdentityClient(ObjectMapper mapper,
            HrisSeparationWorkloadCredentialProvider credentials,
            @Value("${dwp.approval.hrm013-bridge.auth-url:}") String authUrl) {
        this.mapper = mapper;
        this.credentials = credentials;
        this.endpoint = endpoint(authUrl, Hrm013RequesterIdentityAuthorityV1.PATH);
        HrisSeparationWorkloadCredentialProvider.requireEndpoint(
                endpoint, Hrm013RequesterIdentityAuthorityV1.PATH);
    }

    Hrm013RequesterIdentityAuthorityV1.ResolveResponse resolve(
            Hrm013RequesterIdentityAuthorityV1.ResolveRequest request,
            Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims drain,
            HrisSeparationWorkloadCredentialProvider.Snapshot snapshot,
            java.util.function.Supplier<java.time.Instant> authorityRecheck,
            UUID expectedGrantSetId,
            long expectedTenantGrantRevision, long expectedAuthorizationRevision,
            String expectedSigningKeyId) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(authorityRecheck);
        var profile = Hrm013WorkloadProofV1.requesterIdentity();
        if (request.tenantId() != snapshot.lease().tenantId()
                || !expectedGrantSetId.equals(snapshot.lease().grantSetId())
                || expectedTenantGrantRevision
                        != snapshot.lease().tenantGrantRevision()
                || expectedAuthorizationRevision
                        != snapshot.lease().authorizationRevision()
                || !expectedSigningKeyId.equals(
                        snapshot.lease().businessSigningKeyId())) {
            throw HrisSeparationProtocolException.unavailable();
        }
        byte[] body = Hrm013RequesterIdentityAuthorityV1.encodeRequest(mapper, request);
        java.time.Instant authorityExpiresAt = authorityRecheck.get();
        Objects.requireNonNull(authorityExpiresAt);
        String proof = credentials.businessProof(snapshot, profile, request.correlationId(),
                body, Map.of("approvalRequestId", request.approvalRequestId(),
                        "requestedByActorPublicId", request.requestedByActorPublicId()),
                mode, drain, authorityExpiresAt);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            for (var header : Hrm013WorkloadProofV1.headers(
                    profile, proof, request.correlationId())) {
                builder.header(header.name(), header.value());
            }
            OutboundHttpHeaders.propagateObservability(builder);
            credentials.requireLive(authorityExpiresAt);
            HttpResponse<InputStream> response = credentials.client(snapshot).send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try {
                HrisSeparationWorkloadCredentialProvider.requirePeer(
                        response, endpoint, AUTH_SPIFFE);
            } catch (RuntimeException untrustedPeer) {
                close(response.body());
                throw untrustedPeer;
            }
            if (!Set.of(200, 403, 503).contains(response.statusCode())
                    || !response.headers().allValues("Content-Type")
                    .equals(List.of("application/json"))) {
                close(response.body());
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    throw HrisSeparationProtocolException.denied();
                }
                throw HrisSeparationProtocolException.unavailable();
            }
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(
                        Hrm013RequesterIdentityAuthorityV1.MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length > Hrm013RequesterIdentityAuthorityV1.MAX_RESPONSE_BYTES) {
                throw HrisSeparationProtocolException.unavailable();
            }
            var decoded = Hrm013RequesterIdentityAuthorityV1.decodeResponse(mapper, bytes);
            int expectedStatus = switch (decoded.status()) {
                case ADMIT -> 200;
                case DENY -> 403;
                case UNAVAILABLE -> 503;
            };
            if (response.statusCode() != expectedStatus
                    || !request.correlationId().equals(decoded.correlationId())) {
                throw HrisSeparationProtocolException.unavailable();
            }
            credentials.requireLive(authorityExpiresAt);
            return decoded;
        } catch (HrisSeparationProtocolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw HrisSeparationProtocolException.unavailable();
        }
    }

    private static URI endpoint(String base, String path) {
        if (base == null || base.isBlank()) throw new IllegalStateException(
                "Enabled HRM-013 runtime requires the Auth HTTPS endpoint.");
        URI root = URI.create(base.endsWith("/") ? base : base + "/");
        return root.resolve(path.substring(1));
    }

    private static void close(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (java.io.IOException ignored) { }
    }
}
