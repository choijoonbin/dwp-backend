package com.dwp.services.approval.hrisbridge.separation;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Immutable, per-attempt view of runtime-delivered HRM-013 workload credentials. */
@Component
@ConditionalOnProperty(prefix = "dwp.approval.hrm013-bridge", name = "enabled",
        havingValue = "true")
public final class HrisSeparationWorkloadCredentialProvider {
    private static final String INTERNAL_PATH_PREFIX = "/internal/";
    public enum ServiceRole { CALLER, OWNER }

    @FunctionalInterface
    public interface CredentialSource {
        Snapshot current(long tenantId, String bindingId,
                Hrm013WorkloadProofV1.GrantUseMode mode);
    }

    public record Lease(
            UUID leaseId, long leaseVersion, long tenantId, UUID grantSetId,
            long grantRevision, long tenantGrantRevision, long authorizationRevision,
            String bindingId, String purpose, String callerWorkloadId,
            String callerSpiffeId, String ownerWorkloadId, String ownerSpiffeId,
            ServiceRole serviceRole, String businessSigningKeyId,
            String businessKeyVersionId, String businessPublicJwkThumbprint,
            Instant notBefore, Instant expiresAt, String leaseDigest) {
        public Lease {
            Objects.requireNonNull(leaseId); Objects.requireNonNull(grantSetId);
            Objects.requireNonNull(serviceRole); Objects.requireNonNull(notBefore);
            Objects.requireNonNull(expiresAt);
            if (leaseVersion < 1 || tenantId < 1 || grantRevision < 1
                    || tenantGrantRevision < 1 || authorizationRevision < 1
                    || !expiresAt.isAfter(notBefore)) throw invalid();
            for (String value : List.of(bindingId, purpose, callerWorkloadId,
                    callerSpiffeId, ownerWorkloadId, ownerSpiffeId,
                    businessSigningKeyId, businessKeyVersionId)) requireText(value);
            if (businessPublicJwkThumbprint == null
                    || !businessPublicJwkThumbprint.matches("[A-Za-z0-9_-]{43}")) {
                throw invalid();
            }
            requireDigest(leaseDigest);
        }
    }

    public record Snapshot(
            Lease lease, SSLContext sslContext, X509Certificate leafCertificate,
            PrivateKey resolverPrivateKey, PrivateKey businessPrivateKey) {
        public Snapshot {
            Objects.requireNonNull(lease); Objects.requireNonNull(sslContext);
            Objects.requireNonNull(leafCertificate); Objects.requireNonNull(resolverPrivateKey);
            Objects.requireNonNull(businessPrivateKey);
            if (!"RSA".equals(resolverPrivateKey.getAlgorithm())
                    || !"RSA".equals(businessPrivateKey.getAlgorithm())) throw invalid();
        }

        public String leafThumbprint() {
            try {
                return Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(leafCertificate.getEncoded()));
            } catch (Exception failure) {
                throw new IllegalStateException("HRM-013 leaf credential is invalid.", failure);
            }
        }
    }

    private final CredentialSource source;
    private final ObjectMapper mapper;
    private final Clock clock;

    public HrisSeparationWorkloadCredentialProvider(
            ObjectProvider<CredentialSource> sources, ObjectMapper mapper) {
        this(required(sources.getIfAvailable()), mapper, Clock.systemUTC());
    }

    HrisSeparationWorkloadCredentialProvider(
            CredentialSource source, ObjectMapper mapper, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
    }

    public Snapshot current(long tenantId, String bindingId,
            Hrm013WorkloadProofV1.GrantUseMode mode) {
        Snapshot result = source.current(tenantId, bindingId, mode);
        Instant now = clock.instant();
        if (result == null || tenantId != result.lease().tenantId()
                || !bindingId.equals(result.lease().bindingId())
                || now.isBefore(result.lease().notBefore())
                || !now.isBefore(result.lease().expiresAt())
                || !MessageDigest.isEqual(
                        leaseDigest(result.lease()).getBytes(StandardCharsets.US_ASCII),
                        result.lease().leaseDigest().getBytes(StandardCharsets.US_ASCII))) {
            throw HrisSeparationProtocolException.unavailable();
        }
        return result;
    }

    Instant now() {
        return clock.instant();
    }

    /**
     * Binds an immutable runtime lease to the exact public key returned by Auth.
     * A matching kid is deliberately insufficient: both the RFC 7638 thumbprint
     * and a fresh private-key possession challenge must verify before the key is
     * allowed to create a business proof.
     */
    void requireAttested(Snapshot snapshot,
            HrisServiceGrantAuthorityV1.GrantAuthority authority) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(authority);
        Lease lease = snapshot.lease();
        boolean caller = lease.serviceRole() == ServiceRole.CALLER;
        String signingKeyId = caller ? authority.callerSigningKeyId()
                : authority.ownerSigningKeyId();
        HrisServiceGrantAuthorityV1.RsaPublicJwk signingJwk = caller
                ? authority.callerSigningPublicJwk()
                : authority.ownerSigningPublicJwk();
        String serviceWorkload = caller ? authority.callerWorkloadId()
                : authority.ownerWorkloadId();
        String serviceSpiffe = caller ? authority.callerSpiffeId()
                : authority.ownerSpiffeId();
        if (!lease.grantSetId().equals(authority.grantSetId())
                || lease.grantRevision() != authority.grantRevision()
                || lease.tenantId() != authority.tenantId()
                || lease.tenantGrantRevision() != authority.tenantGrantRevision()
                || lease.authorizationRevision() != authority.authorizationRevision()
                || !lease.bindingId().equals(authority.bindingId())
                || !lease.purpose().equals(authority.purpose())
                || !lease.callerWorkloadId().equals(authority.callerWorkloadId())
                || !lease.callerSpiffeId().equals(authority.callerSpiffeId())
                || !lease.ownerWorkloadId().equals(authority.ownerWorkloadId())
                || !lease.ownerSpiffeId().equals(authority.ownerSpiffeId())
                || !serviceWorkload.equals(caller
                        ? lease.callerWorkloadId() : lease.ownerWorkloadId())
                || !serviceSpiffe.equals(caller
                        ? lease.callerSpiffeId() : lease.ownerSpiffeId())
                || !lease.businessSigningKeyId().equals(signingKeyId)
                || !lease.businessPublicJwkThumbprint().equals(
                        publicJwkThumbprint(signingJwk))) {
            throw HrisSeparationProtocolException.denied();
        }
        byte[] challenge = (lease.leaseId() + "\n" + UUID.randomUUID())
                .getBytes(StandardCharsets.UTF_8);
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(snapshot.businessPrivateKey());
            signer.update(challenge);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(new RSAKey.Builder(new Base64URL(signingJwk.n()),
                    new Base64URL(signingJwk.e())).build().toRSAPublicKey());
            verifier.update(challenge);
            if (!verifier.verify(signer.sign())) {
                throw HrisSeparationProtocolException.denied();
            }
        } catch (HrisSeparationProtocolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw HrisSeparationProtocolException.denied();
        }
    }

    String leaseDigest(Lease lease) {
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("leaseId", lease.leaseId());
            values.put("leaseVersion", lease.leaseVersion());
            values.put("tenantId", lease.tenantId());
            values.put("grantSetId", lease.grantSetId());
            values.put("grantRevision", lease.grantRevision());
            values.put("tenantGrantRevision", lease.tenantGrantRevision());
            values.put("authorizationRevision", lease.authorizationRevision());
            values.put("bindingId", lease.bindingId());
            values.put("purpose", lease.purpose());
            values.put("callerWorkloadId", lease.callerWorkloadId());
            values.put("callerSpiffeId", lease.callerSpiffeId());
            values.put("ownerWorkloadId", lease.ownerWorkloadId());
            values.put("ownerSpiffeId", lease.ownerSpiffeId());
            values.put("serviceRole", lease.serviceRole().name());
            values.put("businessSigningKeyId", lease.businessSigningKeyId());
            values.put("businessKeyVersionId", lease.businessKeyVersionId());
            values.put("businessPublicJwkThumbprint",
                    lease.businessPublicJwkThumbprint());
            values.put("notBefore", lease.notBefore());
            values.put("expiresAt", lease.expiresAt());
            return Hrm013WorkloadProofV1.rfc8785Sha256(
                    mapper, mapper.writeValueAsBytes(values));
        } catch (Exception failure) {
            throw HrisSeparationProtocolException.unavailable();
        }
    }

    static String publicJwkThumbprint(
            HrisServiceGrantAuthorityV1.RsaPublicJwk jwk) {
        try {
            String canonical = "{\"e\":\"" + jwk.e()
                    + "\",\"kty\":\"RSA\",\"n\":\"" + jwk.n() + "\"}";
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw HrisSeparationProtocolException.unavailable();
        }
    }

    HttpClient client(Snapshot snapshot) {
        SSLParameters tls = new SSLParameters();
        tls.setProtocols(new String[] {"TLSv1.3"});
        tls.setEndpointIdentificationAlgorithm("HTTPS");
        return HttpClient.newBuilder().sslContext(snapshot.sslContext())
                .sslParameters(tls).connectTimeout(java.time.Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    String resolverProof(Snapshot snapshot,
            HrisServiceGrantAuthorityV1.ResolveRequest request, byte[] body) {
        var profile = Hrm013WorkloadProofV1.resolverBootstrap(
                Hrm013WorkloadProofV1.ResolverCaller.APPROVAL);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("resolutionMode", request.resolutionMode().name());
        extra.put("signedTenantGrantRevision", request.signedTenantGrantRevision());
        extra.put("signedAuthorizationRevision", request.signedAuthorizationRevision());
        extra.put("requestingWorkloadId", request.requestingWorkloadId());
        extra.put("targetCallerWorkloadId", request.targetCallerWorkloadId());
        extra.put("targetOwnerWorkloadId", request.targetOwnerWorkloadId());
        extra.put("targetBindingId", request.targetBindingId());
        extra.put("targetPurpose", request.targetPurpose());
        if (request.drainFields() != null) putDrain(extra, request.drainFields());
        return sign(profile, snapshot.leafThumbprint(), snapshot.resolverPrivateKey(),
                request.tenantId(), request.correlationId(), body, extra,
                snapshot.leafThumbprint(), snapshot.lease().expiresAt());
    }

    String businessProof(Snapshot snapshot, Hrm013WorkloadProofV1.Profile profile,
            UUID correlationId, byte[] body, Map<String, Object> profileClaims,
            Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        return businessProof(snapshot, profile, correlationId, body, profileClaims,
                mode, drain, snapshot.lease().expiresAt());
    }

    String businessProof(Snapshot snapshot, Hrm013WorkloadProofV1.Profile profile,
            UUID correlationId, byte[] body, Map<String, Object> profileClaims,
            Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims drain,
            Instant authorityExpiresAt) {
        Lease lease = snapshot.lease();
        if (!profile.bindingId().equals(lease.bindingId())
                || !profile.purpose().equals(lease.purpose())
                || !profile.callerWorkloadId().equals(lease.callerWorkloadId())
                || !profile.ownerWorkloadId().equals(lease.ownerWorkloadId())) {
            throw HrisSeparationProtocolException.unavailable();
        }
        Map<String, Object> extra = new LinkedHashMap<>(profileClaims);
        extra.put("grantUseMode", mode.name());
        extra.put("tenantGrantRevision", lease.tenantGrantRevision());
        extra.put("authorizationRevision", lease.authorizationRevision());
        if (drain != null) putDrain(extra, drain);
        return sign(profile, lease.businessSigningKeyId(), snapshot.businessPrivateKey(),
                lease.tenantId(), correlationId, body, extra,
                snapshot.leafThumbprint(), earlier(lease.expiresAt(), authorityExpiresAt));
    }

    void requireLive(Instant expiresAt) {
        if (expiresAt == null || !clock.instant().isBefore(expiresAt)) {
            throw HrisSeparationProtocolException.unavailable();
        }
    }

    static void requireEndpoint(URI endpoint, String path) {
        if (path == null || !path.startsWith(INTERNAL_PATH_PREFIX)) {
            throw HrisSeparationProtocolException.unavailable();
        }
        if (endpoint == null || !"https".equals(endpoint.getScheme())
                || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !path.equals(endpoint.getPath())) throw invalid();
    }

    static void requirePeer(HttpResponse<?> response, URI endpoint, String expectedSpiffe) {
        try {
            var session = response.sslSession().orElseThrow();
            if (!"TLSv1.3".equals(session.getProtocol())) throw invalid();
            X509Certificate leaf = (X509Certificate) session.getPeerCertificates()[0];
            leaf.checkValidity();
            if (!List.of(expectedSpiffe).equals(sans(leaf, 6))
                    || !sans(leaf, 2).contains(endpoint.getHost())) throw invalid();
        } catch (Exception failure) {
            throw HrisSeparationProtocolException.unavailable();
        }
    }

    private String sign(Hrm013WorkloadProofV1.Profile profile, String keyId,
            PrivateKey key, long tenantId, UUID correlationId, byte[] body,
            Map<String, Object> extra, String certificateThumbprint) {
        return sign(profile, keyId, key, tenantId, correlationId, body, extra,
                certificateThumbprint, clock.instant().plusSeconds(30));
    }

    private String sign(Hrm013WorkloadProofV1.Profile profile, String keyId,
            PrivateKey key, long tenantId, UUID correlationId, byte[] body,
            Map<String, Object> extra, String certificateThumbprint,
            Instant authorizationExpiresAt) {
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(30).isBefore(authorizationExpiresAt)
                ? now.plusSeconds(30) : authorizationExpiresAt;
        if (!expiresAt.isAfter(now)) throw HrisSeparationProtocolException.unavailable();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", profile.callerWorkloadId());
        claims.put("aud", profile.ownerWorkloadId());
        claims.put("sub", profile.callerWorkloadId());
        claims.put("iat", now.getEpochSecond()); claims.put("nbf", now.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        UUID jti = UUID.randomUUID(); UUID nonce;
        do { nonce = UUID.randomUUID(); } while (jti.equals(nonce));
        claims.put("jti", jti.toString()); claims.put("requestNonce", nonce.toString());
        claims.put("bindingId", profile.bindingId()); claims.put("purpose", profile.purpose());
        claims.put("method", profile.method()); claims.put("path", profile.exactPath());
        claims.put("tenantId", tenantId); claims.put("correlationId", correlationId.toString());
        claims.put("requestBodySha256", Hrm013WorkloadProofV1.sha256(body));
        claims.put("authn", profile.authentication());
        claims.put("cnf", Map.of("x5t#S256", certificateThumbprint));
        claims.putAll(extra);
        try {
            String encodedHeader = encode(Map.of("alg", "RS256", "typ", "JWT", "kid", keyId));
            String encodedClaims = encode(new TreeMap<>(claims));
            String material = encodedHeader + "." + encodedClaims;
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key); signer.update(material.getBytes(StandardCharsets.US_ASCII));
            return material + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signer.sign());
        } catch (Exception failure) {
            throw HrisSeparationProtocolException.unavailable();
        }
    }

    private String encode(Object value) throws Exception {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(value));
    }

    private static void putDrain(Map<String, Object> target,
            HrisServiceGrantAuthorityV1.DrainFields value) {
        drain(target, value.drainRootWorkItemId(), value.drainRootCreatedAt(),
                value.drainRootPayloadSha256(), value.drainContinuationKind().name(),
                value.drainContinuationEventId(), value.drainContinuationRevision(),
                value.drainContinuationPayloadSha256());
    }

    private static void putDrain(Map<String, Object> target,
            Hrm013WorkloadProofV1.DrainClaims value) {
        drain(target, value.drainRootWorkItemId(), value.drainRootCreatedAt(),
                value.drainRootPayloadSha256(), value.drainContinuationKind().name(),
                value.drainContinuationEventId(), value.drainContinuationRevision(),
                value.drainContinuationPayloadSha256());
    }

    private static void drain(Map<String, Object> target, UUID root, Instant created,
            String rootDigest, String kind, UUID event, long revision, String eventDigest) {
        target.put("drainRootWorkItemId", root.toString());
        target.put("drainRootCreatedAt", created.toString());
        target.put("drainRootPayloadSha256", rootDigest);
        target.put("drainContinuationKind", kind);
        target.put("drainContinuationEventId", event.toString());
        target.put("drainContinuationRevision", revision);
        target.put("drainContinuationPayloadSha256", eventDigest);
    }

    private static List<String> sans(X509Certificate certificate, int type) throws Exception {
        Collection<List<?>> values = certificate.getSubjectAlternativeNames();
        if (values == null) return List.of();
        List<String> result = new ArrayList<>();
        for (List<?> value : values) if (value != null && value.size() >= 2
                && Integer.valueOf(type).equals(value.get(0))
                && value.get(1) instanceof String text) result.add(text);
        return List.copyOf(result);
    }

    private static CredentialSource required(CredentialSource value) {
        if (value == null) throw new IllegalStateException(
                "Enabled HRM-013 runtime requires an injected credential source.");
        return value;
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank() || value.length() > 512) throw invalid();
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("HRM-013 workload credential is invalid.");
    }

    private static Instant earlier(Instant left, Instant right) {
        Objects.requireNonNull(left); Objects.requireNonNull(right);
        return left.isBefore(right) ? left : right;
    }
}
