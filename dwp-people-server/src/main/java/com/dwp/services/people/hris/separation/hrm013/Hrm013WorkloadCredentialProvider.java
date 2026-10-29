package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Injected, non-exportable atomic workload credential leases. */
public final class Hrm013WorkloadCredentialProvider {
    private final LeaseSource source;
    private final ObjectMapper mapper;
    private final Clock clock;

    public Hrm013WorkloadCredentialProvider(
            LeaseSource source, ObjectMapper mapper, Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AtomicGrantCredentialLease requireCurrent(
            long tenantId, String bindingId, String purpose,
            String callerWorkloadId, String ownerWorkloadId,
            Hrm013CredentialRole serviceRole) {
        AtomicGrantCredentialLease lease = source.load(
                tenantId, bindingId, purpose, serviceRole);
        if (lease == null || lease.tenantId() != tenantId
                || !lease.bindingId().equals(bindingId)
                || !lease.purpose().equals(purpose)
                || !lease.callerWorkloadId().equals(callerWorkloadId)
                || !lease.ownerWorkloadId().equals(ownerWorkloadId)
                || lease.serviceRole() != serviceRole
                || clock.instant().isBefore(lease.notBefore())
                || !clock.instant().isBefore(lease.expiresAt())
                || !MessageDigest.isEqual(
                digest(lease).getBytes(StandardCharsets.US_ASCII),
                lease.leaseDigest().getBytes(StandardCharsets.US_ASCII))) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
        return lease;
    }

    public void requireAttested(
            AtomicGrantCredentialLease lease,
            Hrm013ServiceAuthorityPort.SigningAuthority authority) {
        if (!lease.grantSetId().equals(authority.grantSetId())
                || lease.grantRevision() != authority.grantRevision()
                || lease.tenantGrantRevision()
                != authority.grant().tenantGrantRevision()
                || lease.authorizationRevision()
                != authority.grant().authorizationRevision()
                || lease.tenantId() != authority.grant().tenantId()
                || !lease.bindingId().equals(authority.grant().bindingId())
                || !lease.purpose().equals(authority.grant().purpose())
                || !lease.callerWorkloadId().equals(
                authority.grant().callerWorkloadId())
                || !lease.callerSpiffeId().equals(authority.callerSpiffeId())
                || !lease.ownerWorkloadId().equals(authority.ownerWorkloadId())
                || !lease.ownerSpiffeId().equals(authority.ownerSpiffeId())
                || authority.query().tenantId() != lease.tenantId()
                || !authority.query().callerWorkloadId().equals(
                lease.callerWorkloadId())
                || !authority.query().bindingId().equals(lease.bindingId())
                || !authority.query().purpose().equals(lease.purpose())
                || authority.query().signedTenantGrantRevision()
                != lease.tenantGrantRevision()
                || authority.query().signedAuthorizationRevision()
                != lease.authorizationRevision()
                || !lease.businessSigningKeyId()
                .equals(authority.signingKeyId(lease.serviceRole()))
                || !lease.businessPublicJwkThumbprint().equals(
                publicJwkThumbprint(authority.signingPublicJwk(
                        lease.serviceRole())))) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        byte[] challenge = (lease.leaseId() + "\n" + UUID.randomUUID())
                .getBytes(StandardCharsets.UTF_8);
        try {
            byte[] signature = lease.businessPrivateKeyHandle().sign(challenge);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(authority.signingPublicKey(lease.serviceRole()));
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
            byte[] json = mapper.writeValueAsBytes(values);
            return Hrm013WorkloadProofV1.rfc8785Sha256(mapper, json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("lease digest serialization failed", failure);
        }
    }

    static String publicJwkThumbprint(
            com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1
                    .RsaPublicJwk jwk) {
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

    @FunctionalInterface public interface LeaseSource {
        AtomicGrantCredentialLease load(
                long tenantId, String bindingId, String purpose, Hrm013CredentialRole role);
    }

    @FunctionalInterface public interface NonExportableSigner {
        byte[] sign(byte[] exactInput) throws Exception;
    }

    public record AtomicGrantCredentialLease(
            UUID leaseId, long leaseVersion, long tenantId, UUID grantSetId,
            long grantRevision, long tenantGrantRevision, long authorizationRevision,
            String bindingId, String purpose, String callerWorkloadId,
            String callerSpiffeId, String ownerWorkloadId, String ownerSpiffeId,
            Hrm013CredentialRole serviceRole, String businessSigningKeyId,
            String businessKeyVersionId, String businessPublicJwkThumbprint,
            NonExportableSigner businessPrivateKeyHandle,
            Instant notBefore, Instant expiresAt, String leaseDigest) {
        public AtomicGrantCredentialLease {
            Objects.requireNonNull(leaseId); Objects.requireNonNull(grantSetId);
            if (leaseVersion < 1 || tenantId < 1 || grantRevision < 1
                    || tenantGrantRevision < 1 || authorizationRevision < 1) {
                throw new IllegalArgumentException("lease revisions must be positive");
            }
            for (String value : new String[] {bindingId, purpose, callerWorkloadId,
                    callerSpiffeId, ownerWorkloadId, ownerSpiffeId,
                    businessSigningKeyId, businessKeyVersionId}) {
                if (value == null || value.isBlank()) throw new IllegalArgumentException("lease field");
            }
            Objects.requireNonNull(serviceRole); Objects.requireNonNull(businessPrivateKeyHandle);
            if (businessPublicJwkThumbprint == null
                    || !businessPublicJwkThumbprint.matches("[A-Za-z0-9_-]{43}")) {
                throw new IllegalArgumentException("businessPublicJwkThumbprint");
            }
            Objects.requireNonNull(notBefore); Objects.requireNonNull(expiresAt);
            if (!notBefore.isBefore(expiresAt)) throw new IllegalArgumentException("lease window");
            if (leaseDigest == null || !leaseDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("leaseDigest");
            }
        }
    }
}
