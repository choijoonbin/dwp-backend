package com.dwp.services.auth.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
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

/** Validates and consumes externally provisioned, non-exportable identity credentials. */
public final class Hrm013IdentityWorkloadCredentialProvider {
    private final LeaseSource source;
    private final ObjectMapper mapper;
    private final Clock clock;

    public Hrm013IdentityWorkloadCredentialProvider(
            LeaseSource source, ObjectMapper mapper, Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Auth is the caller for the HRM-008 identity receipt binding. */
    public AtomicGrantCredentialLease requireCurrent(
            HrisServiceGrantAuthorityV1.GrantAuthority authority) {
        return requireCurrent(authority, CredentialRole.CALLER);
    }

    /** Auth is the owner for the SVC-AUT-HRM-014 identity dispatch binding. */
    public AtomicGrantCredentialLease requireOwnerCurrent(
            HrisServiceGrantAuthorityV1.GrantAuthority authority) {
        return requireCurrent(authority, CredentialRole.OWNER);
    }

    private AtomicGrantCredentialLease requireCurrent(
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            CredentialRole requiredRole) {
        Objects.requireNonNull(authority, "authority");
        AtomicGrantCredentialLease lease = source.load(
                authority.tenantId(), authority.bindingId(), authority.purpose());
        Instant now = clock.instant();
        String workloadId = requiredRole == CredentialRole.CALLER
                ? authority.callerWorkloadId() : authority.ownerWorkloadId();
        String spiffeId = requiredRole == CredentialRole.CALLER
                ? authority.callerSpiffeId() : authority.ownerSpiffeId();
        String signingKeyId = requiredRole == CredentialRole.CALLER
                ? authority.callerSigningKeyId() : authority.ownerSigningKeyId();
        HrisServiceGrantAuthorityV1.RsaPublicJwk signingJwk =
                requiredRole == CredentialRole.CALLER
                        ? authority.callerSigningPublicJwk()
                        : authority.ownerSigningPublicJwk();
        if (lease == null || lease.credentialRole() != requiredRole
                || lease.tenantId() != authority.tenantId()
                || !lease.grantSetId().equals(authority.grantSetId())
                || lease.grantRevision() != authority.grantRevision()
                || lease.tenantGrantRevision() != authority.tenantGrantRevision()
                || lease.authorizationRevision() != authority.authorizationRevision()
                || !lease.bindingId().equals(authority.bindingId())
                || !lease.purpose().equals(authority.purpose())
                || !lease.callerWorkloadId().equals(authority.callerWorkloadId())
                || !lease.callerSpiffeId().equals(authority.callerSpiffeId())
                || !lease.ownerWorkloadId().equals(authority.ownerWorkloadId())
                || !lease.ownerSpiffeId().equals(authority.ownerSpiffeId())
                || !lease.serviceWorkloadId().equals(workloadId)
                || !lease.businessSigningKeyId().equals(signingKeyId)
                || !lease.businessPublicJwkThumbprint().equals(
                        publicJwkThumbprint(signingJwk))
                || !lease.clientSpiffeId().equals(spiffeId)
                || now.isBefore(lease.notBefore()) || !now.isBefore(lease.expiresAt())
                || !constantEquals(digest(lease), lease.leaseDigest())) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
        requirePrivateKeyAttestation(lease, signingJwk);
        return lease;
    }

    public void requireLive(AtomicGrantCredentialLease lease) {
        Instant now = clock.instant();
        if (now.isBefore(lease.notBefore()) || !now.isBefore(lease.expiresAt())) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
    }

    /** Reloads the external lease and proves it is still the exact signed/I/O lease. */
    public AtomicGrantCredentialLease recheckCurrent(
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            AtomicGrantCredentialLease expected) {
        return recheckCurrent(authority, expected, Duration.ZERO);
    }

    /**
     * Reloads the exact external lease and proves it remains valid for the whole
     * bounded operation that follows this check.
     */
    public AtomicGrantCredentialLease recheckCurrent(
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            AtomicGrantCredentialLease expected, Duration minimumRemainingValidity) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(minimumRemainingValidity, "minimumRemainingValidity");
        if (minimumRemainingValidity.isNegative()) {
            throw new IllegalArgumentException("minimumRemainingValidity");
        }
        AtomicGrantCredentialLease current = requireCurrent(
                authority, expected.credentialRole());
        if (!current.leaseId().equals(expected.leaseId())
                || current.leaseVersion() != expected.leaseVersion()
                || !constantEquals(current.leaseDigest(), expected.leaseDigest())
                || current.tenantId() != expected.tenantId()
                || !current.grantSetId().equals(expected.grantSetId())
                || current.grantRevision() != expected.grantRevision()
                || current.tenantGrantRevision() != expected.tenantGrantRevision()
                || current.authorizationRevision() != expected.authorizationRevision()
                || current.credentialRole() != expected.credentialRole()
                || !current.bindingId().equals(expected.bindingId())
                || !current.purpose().equals(expected.purpose())
                || !current.serviceWorkloadId().equals(expected.serviceWorkloadId())
                || !current.businessSigningKeyId()
                        .equals(expected.businessSigningKeyId())
                || !current.businessKeyVersionId()
                        .equals(expected.businessKeyVersionId())
                || !constantEquals(current.businessPublicJwkThumbprint(),
                        expected.businessPublicJwkThumbprint())
                || current.businessPrivateKeyHandle()
                        != expected.businessPrivateKeyHandle()
                || !current.clientSpiffeId().equals(expected.clientSpiffeId())
                || !constantEquals(current.clientCertificateSha256(),
                        expected.clientCertificateSha256())
                || current.tlsClientHandle() != expected.tlsClientHandle()
                || !current.notBefore().equals(expected.notBefore())
                || !current.expiresAt().equals(expected.expiresAt())) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
        if (current.expiresAt().isBefore(
                clock.instant().plus(minimumRemainingValidity))) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
        return current;
    }

    /** Owner-signed proof over the immutable 202 admission response. */
    public SignedProof admissionReceiptProof(
            AtomicGrantCredentialLease lease,
            Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1 receipt,
            byte[] exactReceiptBody, Instant authorityExpiresAt) {
        Objects.requireNonNull(lease); Objects.requireNonNull(receipt);
        Objects.requireNonNull(exactReceiptBody); Objects.requireNonNull(authorityExpiresAt);
        if (lease.credentialRole() != CredentialRole.OWNER
                || !Hrm013TaskDispatchV1.IDENTITY.bindingId().equals(lease.bindingId())
                || !Hrm013TaskDispatchV1.IDENTITY.purpose().equals(lease.purpose())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        Instant now = clock.instant();
        Instant expiresAt = earliest(now.plusSeconds(30), lease.expiresAt(),
                authorityExpiresAt);
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
        claims.put("dispatchEventId", receipt.dispatchEventId());
        claims.put("taskPublicId", receipt.taskPublicId());
        claims.put("payloadSha256", receipt.payloadSha256());
        claims.put("requestBodySha256", receipt.requestBodySha256());
        claims.put("responseBodySha256", Hrm013WorkloadProofV1.sha256(exactReceiptBody));
        claims.put("tenantGrantRevision", lease.tenantGrantRevision());
        claims.put("authorizationRevision", lease.authorizationRevision());
        claims.put("correlationId", receipt.correlationId());
        claims.put("authn", "MTLS_SPIFFE_RS256");
        claims.put("cnf", Map.of("x5t#S256", lease.clientCertificateSha256()));
        return new SignedProof(compact(lease, "DWP-HRM013-RECEIPT-JWT", claims),
                expiresAt);
    }

    /** Fresh caller proof for one exact HRM-008 callback attempt. */
    public SignedProof callbackProof(
            AtomicGrantCredentialLease lease,
            Hrm013IdentityReceiptProofContext receipt,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 body,
            Instant authorityExpiresAt) {
        Objects.requireNonNull(lease); Objects.requireNonNull(receipt);
        Objects.requireNonNull(body); Objects.requireNonNull(authorityExpiresAt);
        if (lease.credentialRole() != CredentialRole.CALLER) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        var profile = Hrm013WorkloadProofV1.hrm008(
                Hrm013WorkloadProofV1.TaskOwner.IDENTITY,
                body.casePublicId(), body.taskPublicId());
        Instant now = clock.instant();
        Instant expiresAt = earliest(now.plusSeconds(30), lease.expiresAt(),
                authorityExpiresAt);
        if (!expiresAt.isAfter(now)) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        UUID proofId = UUID.randomUUID();
        UUID nonce;
        do { nonce = UUID.randomUUID(); } while (nonce.equals(proofId));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", lease.callerWorkloadId());
        claims.put("aud", lease.ownerWorkloadId());
        claims.put("sub", lease.callerWorkloadId());
        claims.put("iat", now.getEpochSecond());
        claims.put("nbf", now.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("jti", proofId);
        claims.put("requestNonce", nonce);
        claims.put("bindingId", lease.bindingId());
        claims.put("purpose", lease.purpose());
        claims.put("method", "POST");
        claims.put("path", profile.exactPath());
        claims.put("tenantId", lease.tenantId());
        claims.put("correlationId", body.correlationId());
        claims.put("requestBodySha256", Hrm013WorkloadProofV1.sha256(
                receipt.exactCallbackBody()));
        claims.put("authn", profile.authentication());
        claims.put("cnf", Map.of("x5t#S256", lease.clientCertificateSha256()));
        claims.put("grantUseMode", receipt.authorityMode().grantUseMode().name());
        claims.put("tenantGrantRevision", lease.tenantGrantRevision());
        claims.put("authorizationRevision", lease.authorizationRevision());
        claims.put("callbackEventId", body.callbackEventId());
        claims.put("casePublicId", body.casePublicId());
        claims.put("taskPublicId", body.taskPublicId());
        claims.put("ownerRevision", body.ownerRevision());
        claims.put("originatingPayloadSha256", body.originatingPayloadSha256());
        putDrain(claims, receipt.drainClaims());
        return new SignedProof(compact(lease, "JWT", claims), expiresAt);
    }

    private static void requirePrivateKeyAttestation(
            AtomicGrantCredentialLease lease,
            HrisServiceGrantAuthorityV1.RsaPublicJwk signingJwk) {
        byte[] challenge = (lease.leaseId() + "\n" + UUID.randomUUID())
                .getBytes(StandardCharsets.UTF_8);
        try {
            byte[] signature = lease.businessPrivateKeyHandle().sign(challenge);
            var decoder = Base64.getUrlDecoder();
            var publicKey = KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(
                            new java.math.BigInteger(1, decoder.decode(signingJwk.n())),
                            new java.math.BigInteger(1, decoder.decode(signingJwk.e()))));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(challenge);
            if (!verifier.verify(signature)) throw Hrm013SafeErrorV1.authorityDenied();
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    public String digest(AtomicGrantCredentialLease lease) {
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("leaseId", lease.leaseId());
            values.put("leaseVersion", lease.leaseVersion());
            values.put("tenantId", lease.tenantId());
            values.put("grantSetId", lease.grantSetId());
            values.put("grantRevision", lease.grantRevision());
            values.put("tenantGrantRevision", lease.tenantGrantRevision());
            values.put("authorizationRevision", lease.authorizationRevision());
            values.put("credentialRole", lease.credentialRole().name());
            values.put("bindingId", lease.bindingId());
            values.put("purpose", lease.purpose());
            values.put("callerWorkloadId", lease.callerWorkloadId());
            values.put("callerSpiffeId", lease.callerSpiffeId());
            values.put("ownerWorkloadId", lease.ownerWorkloadId());
            values.put("ownerSpiffeId", lease.ownerSpiffeId());
            values.put("serviceWorkloadId", lease.serviceWorkloadId());
            values.put("businessSigningKeyId", lease.businessSigningKeyId());
            values.put("businessKeyVersionId", lease.businessKeyVersionId());
            values.put("businessPublicJwkThumbprint",
                    lease.businessPublicJwkThumbprint());
            values.put("clientSpiffeId", lease.clientSpiffeId());
            values.put("clientCertificateSha256", lease.clientCertificateSha256());
            values.put("notBefore", lease.notBefore());
            values.put("expiresAt", lease.expiresAt());
            return Hrm013WorkloadProofV1.rfc8785Sha256(
                    mapper, mapper.writeValueAsBytes(values));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("credential digest serialization failed", failure);
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

    private String compact(AtomicGrantCredentialLease lease, String type,
            Map<String, Object> claims) {
        try {
            String header = encode(Map.of("alg", "RS256", "typ", type,
                    "kid", lease.businessSigningKeyId()));
            String payload = encode(new TreeMap<>(claims));
            String signingInput = header + "." + payload;
            byte[] signature = lease.businessPrivateKeyHandle().sign(
                    signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signature);
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
    }

    private String encode(Object value) throws Exception {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsBytes(value));
    }

    private static void putDrain(Map<String, Object> claims,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        if (drain == null) return;
        claims.put("drainRootWorkItemId", drain.drainRootWorkItemId());
        claims.put("drainRootCreatedAt", drain.drainRootCreatedAt());
        claims.put("drainRootPayloadSha256", drain.drainRootPayloadSha256());
        claims.put("drainContinuationKind", drain.drainContinuationKind().name());
        claims.put("drainContinuationEventId", drain.drainContinuationEventId());
        claims.put("drainContinuationRevision", drain.drainContinuationRevision());
        claims.put("drainContinuationPayloadSha256",
                drain.drainContinuationPayloadSha256());
    }

    private static Instant earliest(Instant first, Instant second, Instant third) {
        Instant value = first.isBefore(second) ? first : second;
        return value.isBefore(third) ? value : third;
    }

    private static boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    @FunctionalInterface public interface LeaseSource {
        AtomicGrantCredentialLease load(
                long tenantId, String bindingId, String purpose);
    }

    @FunctionalInterface public interface NonExportableSigner {
        byte[] sign(byte[] exactInput) throws Exception;
    }

    /** Opaque client-certificate/private-key handle supplied by the workload runtime. */
    public interface NonExportableTlsClient {
        /**
         * Opens one same-channel TLS session. Implementations must finish the
         * TLS handshake and peer/client-certificate policy before returning;
         * no request body is provided until the caller validates evidence.
         */
        default VerifiedTlsChannel openVerified(URI endpoint,
                String expectedPeerSpiffe, String expectedClientCertificateSha256,
                Duration timeout) throws Exception {
            throw new IllegalStateException("verified mTLS channel is unavailable");
        }
    }

    public interface VerifiedTlsChannel extends AutoCloseable {
        /**
         * True only when the channel retains the deadline supplied to
         * {@code openVerified} and enforces it across handshake plus response.
         */
        default boolean totalDeadlineBound() { return false; }
        VerifiedTlsEvidence evidence();
        VerifiedTlsResponse post(List<Hrm013WorkloadProofV1.Header> headers,
                byte[] exactBody, int maximumResponseBytes) throws Exception;
        default VerifiedTlsResponse post(List<Hrm013WorkloadProofV1.Header> headers,
                byte[] exactBody, int maximumResponseBytes,
                Duration remainingTimeout) throws Exception {
            if (!totalDeadlineBound() || remainingTimeout == null
                    || remainingTimeout.isZero() || remainingTimeout.isNegative()) {
                throw new IllegalStateException("verified mTLS deadline is unavailable");
            }
            return post(headers, exactBody, maximumResponseBytes);
        }
        @Override default void close() throws Exception { }
    }

    public record VerifiedTlsEvidence(String protocol,
            boolean trustChainValidated, boolean revocationValidated,
            String singleSpiffeUri, String peerHost,
            String clientCertificateSha256) { }

    public record VerifiedTlsResponse(int statusCode,
            Map<String, List<String>> headers, byte[] exactBody,
            boolean redirected, URI responseUri) {
        public VerifiedTlsResponse {
            headers = Map.copyOf(headers);
            exactBody = exactBody.clone();
            Objects.requireNonNull(responseUri);
        }
        @Override public byte[] exactBody() { return exactBody.clone(); }
    }

    public enum CredentialRole { CALLER, OWNER }

    public record SignedProof(String compact, Instant expiresAt) {
        public SignedProof {
            if (compact == null || compact.isBlank()
                    || compact.length() > Hrm013WorkloadProofV1.MAXIMUM_JWS_BYTES) {
                throw new IllegalArgumentException("bounded proof required");
            }
            Objects.requireNonNull(expiresAt);
        }
    }

    public record AtomicGrantCredentialLease(
            UUID leaseId, long leaseVersion, long tenantId, UUID grantSetId,
            long grantRevision, long tenantGrantRevision, long authorizationRevision,
            CredentialRole credentialRole,
            String bindingId, String purpose, String callerWorkloadId,
            String callerSpiffeId, String ownerWorkloadId, String ownerSpiffeId,
            String serviceWorkloadId, String businessSigningKeyId,
            String businessKeyVersionId, String businessPublicJwkThumbprint,
            NonExportableSigner businessPrivateKeyHandle,
            String clientSpiffeId, String clientCertificateSha256,
            NonExportableTlsClient tlsClientHandle,
            Instant notBefore, Instant expiresAt, String leaseDigest) {
        public AtomicGrantCredentialLease {
            Objects.requireNonNull(leaseId); Objects.requireNonNull(grantSetId);
            Objects.requireNonNull(credentialRole);
            if (leaseVersion < 1 || tenantId < 1 || grantRevision < 1
                    || tenantGrantRevision < 1 || authorizationRevision < 1) {
                throw new IllegalArgumentException("lease revisions must be positive");
            }
            for (String value : new String[] {bindingId, purpose, callerWorkloadId,
                    callerSpiffeId, ownerWorkloadId, ownerSpiffeId,
                    serviceWorkloadId, businessSigningKeyId,
                    businessKeyVersionId, clientSpiffeId}) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("lease field");
                }
            }
            Objects.requireNonNull(businessPrivateKeyHandle);
            Objects.requireNonNull(tlsClientHandle);
            if (businessPublicJwkThumbprint == null
                    || !businessPublicJwkThumbprint.matches("[A-Za-z0-9_-]{43}")
                    || clientCertificateSha256 == null
                    || !clientCertificateSha256.matches("[A-Za-z0-9_-]{43}")) {
                throw new IllegalArgumentException("credential thumbprint");
            }
            Objects.requireNonNull(notBefore); Objects.requireNonNull(expiresAt);
            if (!notBefore.isBefore(expiresAt)) {
                throw new IllegalArgumentException("lease window");
            }
            if (leaseDigest == null || !leaseDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("leaseDigest");
            }
        }
    }
}
