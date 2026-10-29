package com.dwp.services.time.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Validates and uses an atomic, non-exportable Time workload credential lease. */
@Component
@ConditionalOnProperty(prefix = "dwp.time.hrm013-task-dispatch", name = "enabled",
        havingValue = "true")
public final class Hrm013TimeWorkloadCredentialProvider {
    public enum ServiceRole { CALLER, OWNER }

    @FunctionalInterface
    public interface NonExportableSigner {
        byte[] sign(byte[] exactInput) throws Exception;
    }

    @FunctionalInterface
    public interface LeaseSource {
        Lease current(long tenantId, String bindingId, ServiceRole role);
    }

    public record Lease(
            UUID leaseId, long leaseVersion, long tenantId, UUID grantSetId,
            long grantRevision, long tenantGrantRevision, long authorizationRevision,
            String bindingId, String purpose, String callerWorkloadId,
            String callerSpiffeId, String ownerWorkloadId, String ownerSpiffeId,
            ServiceRole serviceRole, String signingKeyId, String keyVersionId,
            String publicJwkThumbprint, SSLContext sslContext,
            X509Certificate leafCertificate, NonExportableSigner resolverSigner,
            NonExportableSigner businessSigner, Instant notBefore, Instant expiresAt,
            String leaseDigest) {
        public Lease {
            Objects.requireNonNull(leaseId); Objects.requireNonNull(grantSetId);
            Objects.requireNonNull(serviceRole); Objects.requireNonNull(sslContext);
            Objects.requireNonNull(leafCertificate); Objects.requireNonNull(resolverSigner);
            Objects.requireNonNull(businessSigner); Objects.requireNonNull(notBefore);
            Objects.requireNonNull(expiresAt);
            if (leaseVersion < 1 || tenantId < 1 || grantRevision < 1
                    || tenantGrantRevision < 1 || authorizationRevision < 1
                    || !expiresAt.isAfter(notBefore)) throw invalid();
            for (String value : new String[] {bindingId, purpose, callerWorkloadId,
                    callerSpiffeId, ownerWorkloadId, ownerSpiffeId, signingKeyId,
                    keyVersionId}) requireText(value);
            requireThumbprint(publicJwkThumbprint);
            requireDigest(leaseDigest);
        }
    }

    private final LeaseSource source;
    private final ObjectMapper mapper;
    private final Clock clock;

    public Hrm013TimeWorkloadCredentialProvider(
            ObjectProvider<LeaseSource> sources, ObjectMapper mapper) {
        this(onlySource(sources), mapper, Clock.systemUTC());
    }

    Hrm013TimeWorkloadCredentialProvider(
            LeaseSource source, ObjectMapper mapper, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
    }

    public Lease current(long tenantId, String bindingId, ServiceRole role) {
        Lease lease = source.current(tenantId, bindingId, role);
        Instant now = clock.instant();
        if (lease == null || lease.tenantId() != tenantId
                || !bindingId.equals(lease.bindingId()) || role != lease.serviceRole()
                || now.isBefore(lease.notBefore()) || !now.isBefore(lease.expiresAt())
                || !MessageDigest.isEqual(digest(lease).getBytes(StandardCharsets.US_ASCII),
                lease.leaseDigest().getBytes(StandardCharsets.US_ASCII))) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
        return lease;
    }

    public void requireAttested(
            Lease lease, HrisServiceGrantAuthorityV1.GrantAuthority authority) {
        Objects.requireNonNull(lease); Objects.requireNonNull(authority);
        boolean caller = lease.serviceRole() == ServiceRole.CALLER;
        var jwk = caller ? authority.callerSigningPublicJwk()
                : authority.ownerSigningPublicJwk();
        String keyId = caller ? authority.callerSigningKeyId()
                : authority.ownerSigningKeyId();
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
                || !lease.signingKeyId().equals(keyId)
                || !lease.publicJwkThumbprint().equals(publicJwkThumbprint(jwk))) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        try {
            byte[] challenge = (lease.leaseId() + "\n" + UUID.randomUUID())
                    .getBytes(StandardCharsets.UTF_8);
            byte[] signature = lease.businessSigner().sign(challenge);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(Hrm013RsaPublicKeyDecoder.decode(jwk));
            verifier.update(challenge);
            if (!verifier.verify(signature)) throw Hrm013SafeErrorV1.authorityDenied();
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    public HttpClient client(Lease lease) {
        SSLParameters tls = new SSLParameters();
        tls.setProtocols(new String[] {"TLSv1.3"});
        tls.setEndpointIdentificationAlgorithm("HTTPS");
        return HttpClient.newBuilder().sslContext(lease.sslContext())
                .sslParameters(tls).connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String resolverProof(
            Lease lease, HrisServiceGrantAuthorityV1.ResolveRequest request,
            byte[] exactBody) {
        var profile = Hrm013WorkloadProofV1.resolverBootstrap(
                Hrm013WorkloadProofV1.ResolverCaller.TIME);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("resolutionMode", request.resolutionMode().name());
        claims.put("signedTenantGrantRevision", request.signedTenantGrantRevision());
        claims.put("signedAuthorizationRevision", request.signedAuthorizationRevision());
        claims.put("requestingWorkloadId", request.requestingWorkloadId());
        claims.put("targetCallerWorkloadId", request.targetCallerWorkloadId());
        claims.put("targetOwnerWorkloadId", request.targetOwnerWorkloadId());
        claims.put("targetBindingId", request.targetBindingId());
        claims.put("targetPurpose", request.targetPurpose());
        putDrain(claims, request.drainFields());
        return sign(profile, lease, leafThumbprint(lease.leafCertificate()),
                lease.resolverSigner(), request.correlationId(),
                exactBody, claims, "JWT", lease.expiresAt());
    }

    public String businessProof(
            Lease lease, Hrm013WorkloadProofV1.Profile profile, UUID correlationId,
            byte[] exactBody, Map<String, Object> profileClaims,
            Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims drain, Instant authorityExpiresAt) {
        if (!profile.bindingId().equals(lease.bindingId())
                || !profile.purpose().equals(lease.purpose())
                || !profile.callerWorkloadId().equals(lease.callerWorkloadId())
                || !profile.ownerWorkloadId().equals(lease.ownerWorkloadId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        Map<String, Object> claims = new LinkedHashMap<>(profileClaims);
        claims.put("grantUseMode", mode.name());
        claims.put("tenantGrantRevision", lease.tenantGrantRevision());
        claims.put("authorizationRevision", lease.authorizationRevision());
        putDrain(claims, drain);
        return sign(profile, lease, lease.signingKeyId(), lease.businessSigner(),
                correlationId, exactBody,
                claims, "JWT", earlier(lease.expiresAt(), authorityExpiresAt));
    }

    /** Owner-signed proof over the immutable admission response. */
    public String admissionReceiptProof(
            Lease lease,
            com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1
                    .Hrm013TaskDispatchAdmissionReceiptV1 receipt,
            byte[] exactReceiptBody,
            Instant authorityExpiresAt) {
        Objects.requireNonNull(receipt); Objects.requireNonNull(exactReceiptBody);
        Instant now = clock.instant();
        Instant expiresAt = earlier(now.plusSeconds(30),
                earlier(lease.expiresAt(), authorityExpiresAt));
        if (!expiresAt.isAfter(now)) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", lease.ownerWorkloadId());
        claims.put("aud", lease.callerWorkloadId());
        claims.put("sub", lease.ownerWorkloadId());
        claims.put("iat", now.getEpochSecond());
        claims.put("nbf", now.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("bindingId", lease.bindingId());
        claims.put("purpose", lease.purpose() + "_ADMISSION_RECEIPT");
        claims.put("status", 202);
        claims.put("tenantId", lease.tenantId());
        claims.put("dispatchEventId", receipt.dispatchEventId().toString());
        claims.put("taskPublicId", receipt.taskPublicId().toString());
        claims.put("payloadSha256", receipt.payloadSha256());
        claims.put("requestBodySha256", receipt.requestBodySha256());
        claims.put("responseBodySha256", Hrm013WorkloadProofV1.sha256(exactReceiptBody));
        claims.put("tenantGrantRevision", lease.tenantGrantRevision());
        claims.put("authorizationRevision", lease.authorizationRevision());
        claims.put("correlationId", receipt.correlationId().toString());
        claims.put("authn", "MTLS_SPIFFE_RS256");
        claims.put("cnf", Map.of("x5t#S256", leafThumbprint(lease.leafCertificate())));
        return compact(lease.signingKeyId(), "DWP-HRM013-RECEIPT-JWT", claims,
                lease.businessSigner());
    }

    public Instant now() { return clock.instant(); }

    String digest(Lease lease) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("leaseId", lease.leaseId()); value.put("leaseVersion", lease.leaseVersion());
            value.put("tenantId", lease.tenantId()); value.put("grantSetId", lease.grantSetId());
            value.put("grantRevision", lease.grantRevision());
            value.put("tenantGrantRevision", lease.tenantGrantRevision());
            value.put("authorizationRevision", lease.authorizationRevision());
            value.put("bindingId", lease.bindingId()); value.put("purpose", lease.purpose());
            value.put("callerWorkloadId", lease.callerWorkloadId());
            value.put("callerSpiffeId", lease.callerSpiffeId());
            value.put("ownerWorkloadId", lease.ownerWorkloadId());
            value.put("ownerSpiffeId", lease.ownerSpiffeId());
            value.put("serviceRole", lease.serviceRole().name());
            value.put("signingKeyId", lease.signingKeyId());
            value.put("keyVersionId", lease.keyVersionId());
            value.put("publicJwkThumbprint", lease.publicJwkThumbprint());
            value.put("notBefore", lease.notBefore()); value.put("expiresAt", lease.expiresAt());
            return Hrm013WorkloadProofV1.rfc8785Sha256(mapper, mapper.writeValueAsBytes(value));
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
    }

    private String sign(Hrm013WorkloadProofV1.Profile profile, Lease lease, String keyId,
            NonExportableSigner signer, UUID correlationId, byte[] exactBody,
            Map<String, Object> extra, String typ, Instant authorityExpiresAt) {
        Map<String, Object> claims = commonClaims(lease, correlationId,
                profile.purpose(), profile.exactPath(),
                Hrm013WorkloadProofV1.sha256(exactBody), profile.authentication(),
                authorityExpiresAt);
        claims.putAll(extra);
        return compact(keyId, typ, claims, signer);
    }

    private Map<String, Object> commonClaims(Lease lease, UUID correlationId,
            String purpose, String path, String bodySha256, String authentication,
            Instant authorityExpiresAt) {
        Instant now = clock.instant();
        Instant expiresAt = earlier(now.plusSeconds(30), authorityExpiresAt);
        if (!expiresAt.isAfter(now)) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", lease.serviceRole() == ServiceRole.CALLER
                ? lease.callerWorkloadId() : lease.ownerWorkloadId());
        claims.put("aud", lease.serviceRole() == ServiceRole.CALLER
                ? lease.ownerWorkloadId() : lease.callerWorkloadId());
        claims.put("sub", claims.get("iss"));
        claims.put("iat", now.getEpochSecond()); claims.put("nbf", now.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        UUID jti = UUID.randomUUID(); UUID nonce;
        do { nonce = UUID.randomUUID(); } while (jti.equals(nonce));
        claims.put("jti", jti.toString()); claims.put("requestNonce", nonce.toString());
        claims.put("bindingId", lease.bindingId()); claims.put("purpose", purpose);
        claims.put("method", "POST"); claims.put("path", path);
        claims.put("tenantId", lease.tenantId());
        claims.put("correlationId", correlationId.toString());
        claims.put("requestBodySha256", bodySha256);
        claims.put("authn", authentication);
        claims.put("cnf", Map.of("x5t#S256", leafThumbprint(lease.leafCertificate())));
        return claims;
    }

    private String compact(String keyId, String typ, Map<String, Object> claims,
            NonExportableSigner signer) {
        try {
            String header = encode(Map.of("alg", "RS256", "typ", typ, "kid", keyId));
            String body = encode(new TreeMap<>(claims));
            String signingInput = header + "." + body;
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signer.sign(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
    }

    private String encode(Object value) throws Exception {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(value));
    }

    private static void putDrain(Map<String, Object> target, Object value) {
        if (value == null) return;
        if (value instanceof HrisServiceGrantAuthorityV1.DrainFields drain) {
            target.put("drainRootWorkItemId", drain.drainRootWorkItemId());
            target.put("drainRootCreatedAt", drain.drainRootCreatedAt());
            target.put("drainRootPayloadSha256", drain.drainRootPayloadSha256());
            target.put("drainContinuationKind", drain.drainContinuationKind().name());
            target.put("drainContinuationEventId", drain.drainContinuationEventId());
            target.put("drainContinuationRevision", drain.drainContinuationRevision());
            target.put("drainContinuationPayloadSha256", drain.drainContinuationPayloadSha256());
        } else if (value instanceof Hrm013WorkloadProofV1.DrainClaims drain) {
            target.put("drainRootWorkItemId", drain.drainRootWorkItemId());
            target.put("drainRootCreatedAt", drain.drainRootCreatedAt());
            target.put("drainRootPayloadSha256", drain.drainRootPayloadSha256());
            target.put("drainContinuationKind", drain.drainContinuationKind().name());
            target.put("drainContinuationEventId", drain.drainContinuationEventId());
            target.put("drainContinuationRevision", drain.drainContinuationRevision());
            target.put("drainContinuationPayloadSha256", drain.drainContinuationPayloadSha256());
        }
    }

    static String publicJwkThumbprint(HrisServiceGrantAuthorityV1.RsaPublicJwk jwk) {
        try {
            String canonical = "{\"e\":\"" + jwk.e()
                    + "\",\"kty\":\"RSA\",\"n\":\"" + jwk.n() + "\"}";
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("public JWK thumbprint unavailable", failure);
        }
    }

    private static String leafThumbprint(X509Certificate certificate) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
    }

    private static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw invalid();
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid();
    }

    private static void requireThumbprint(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid HRM-013 Time credential lease");
    }

    private static LeaseSource onlySource(ObjectProvider<LeaseSource> sources) {
        List<LeaseSource> candidates = sources.orderedStream().toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "exactly one HRM-013 Time workload credential source is required");
        }
        return candidates.getFirst();
    }

}
