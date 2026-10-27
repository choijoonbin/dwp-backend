package com.dwp.services.auth.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class Hrm013IdentityWorkloadCredentialProviderTest {
    @Test
    void requiresOneAtomicLeaseAndAttestsItsNonExportablePrivateHandle()
            throws Exception {
        Instant now = Instant.parse("2026-10-27T03:00:00Z");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAPublicKey rsa = (RSAPublicKey) pair.getPublic();
        String kid = "identity-key-v4";
        var jwk = new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                "RS256", unsigned(rsa.getPublicExponent().toByteArray()), kid,
                "RSA", unsigned(rsa.getModulus().toByteArray()), "sig");
        UUID grantSet = UUID.randomUUID();
        var authority = new HrisServiceGrantAuthorityV1.GrantAuthority(
                grantSet, 4, 91, 7, 11, "ADMIT", "dwp-auth-server",
                "spiffe://dwp/service/dwp-auth-server", kid, jwk,
                "dwp-people-server", "spiffe://dwp/service/dwp-people-server",
                "people-key-v4", new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                        "RS256", unsigned(rsa.getPublicExponent().toByteArray()),
                        "people-key-v4", "RSA",
                        unsigned(rsa.getModulus().toByteArray()), "sig"),
                "SVC-PEP-HRM-008", "HRIS_SEPARATION_IDENTITY_TASK_RECEIPT",
                null, "c".repeat(64), now.minusSeconds(10));
        Hrm013IdentityWorkloadCredentialProvider.NonExportableSigner signer = input -> {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(pair.getPrivate());
            signature.update(input);
            return signature.sign();
        };
        var tls = new Hrm013IdentityWorkloadCredentialProvider
                .NonExportableTlsClient() { };
        AtomicReference<Hrm013IdentityWorkloadCredentialProvider
                .AtomicGrantCredentialLease> source = new AtomicReference<>();
        var provider = new Hrm013IdentityWorkloadCredentialProvider(
                (tenant, binding, purpose) -> source.get(),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(now, ZoneOffset.UTC));
        var provisional = lease(authority, signer, tls, jwk, "0".repeat(64), now);
        var sealed = lease(authority, signer, tls, jwk,
                provider.digest(provisional), now);
        source.set(sealed);

        assertThat(provider.requireCurrent(authority)).isSameAs(sealed);
    }

    @Test
    void ownerAdmissionReceiptProofBindsExactResponseAndAuthorityWindow()
            throws Exception {
        Instant now = Instant.parse("2026-10-27T03:00:00Z");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAPublicKey rsa = (RSAPublicKey) pair.getPublic();
        String ownerKid = "identity-owner-key-v4";
        var ownerJwk = new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                "RS256", unsigned(rsa.getPublicExponent().toByteArray()), ownerKid,
                "RSA", unsigned(rsa.getModulus().toByteArray()), "sig");
        var callerJwk = new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                "RS256", unsigned(rsa.getPublicExponent().toByteArray()),
                "people-caller-key-v4", "RSA",
                unsigned(rsa.getModulus().toByteArray()), "sig");
        var authority = new HrisServiceGrantAuthorityV1.GrantAuthority(
                UUID.randomUUID(), 4, 91, 7, 11, "ADMIT",
                "dwp-people-server", "spiffe://dwp/service/dwp-people-server",
                callerJwk.kid(), callerJwk,
                "dwp-auth-server", "spiffe://dwp/service/dwp-auth-server",
                ownerKid, ownerJwk, Hrm013TaskDispatchV1.IDENTITY.bindingId(),
                Hrm013TaskDispatchV1.IDENTITY.purpose(), null,
                "c".repeat(64), now.minusSeconds(10));
        Hrm013IdentityWorkloadCredentialProvider.NonExportableSigner signer = input -> {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(pair.getPrivate());
            signature.update(input);
            return signature.sign();
        };
        var tls = new Hrm013IdentityWorkloadCredentialProvider
                .NonExportableTlsClient() { };
        var mapper = new ObjectMapper().findAndRegisterModules();
        AtomicReference<Hrm013IdentityWorkloadCredentialProvider
                .AtomicGrantCredentialLease> source = new AtomicReference<>();
        var provider = new Hrm013IdentityWorkloadCredentialProvider(
                (tenant, binding, purpose) -> source.get(), mapper,
                Clock.fixed(now, ZoneOffset.UTC));
        var provisional = lease(authority, signer, tls, ownerJwk,
                "0".repeat(64), now,
                Hrm013IdentityWorkloadCredentialProvider.CredentialRole.OWNER);
        var sealed = lease(authority, signer, tls, ownerJwk,
                provider.digest(provisional), now,
                Hrm013IdentityWorkloadCredentialProvider.CredentialRole.OWNER);
        source.set(sealed);

        UUID dispatchEventId = UUID.randomUUID();
        UUID taskPublicId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        var receipt = Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1(
                        dispatchEventId, taskPublicId, "a".repeat(64),
                        "b".repeat(64), now, "0".repeat(64), correlationId));
        byte[] exactBody = Hrm013TaskDispatchV1.encode(receipt);
        Instant authorityExpiry = now.plusSeconds(17);

        var proof = provider.admissionReceiptProof(
                provider.requireOwnerCurrent(authority), receipt, exactBody,
                authorityExpiry);
        String[] segments = proof.compact().split("\\.", -1);
        var decoder = Base64.getUrlDecoder();
        var header = mapper.readTree(decoder.decode(segments[0]));
        var claims = mapper.readTree(decoder.decode(segments[1]));
        assertThat(segments).hasSize(3);
        assertThat(header.path("typ").asText()).isEqualTo("DWP-HRM013-RECEIPT-JWT");
        assertThat(header.path("kid").asText()).isEqualTo(ownerKid);
        assertThat(claims.path("iss").asText()).isEqualTo("dwp-auth-server");
        assertThat(claims.path("aud").asText()).isEqualTo("dwp-people-server");
        assertThat(claims.path("status").asInt()).isEqualTo(202);
        assertThat(claims.path("dispatchEventId").asText())
                .isEqualTo(dispatchEventId.toString());
        assertThat(claims.path("responseBodySha256").asText())
                .isEqualTo(Hrm013WorkloadProofV1.sha256(exactBody));
        assertThat(claims.path("exp").asLong())
                .isEqualTo(authorityExpiry.getEpochSecond());
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update((segments[0] + "." + segments[1])
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(verifier.verify(decoder.decode(segments[2]))).isTrue();
        assertThat(proof.expiresAt()).isEqualTo(authorityExpiry);
    }

    @Test
    void finalReloadRejectsRevokedRotatedOrReboundLeaseBeforeIo()
            throws Exception {
        Instant now = Instant.parse("2026-10-27T03:00:00Z");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAPublicKey rsa = (RSAPublicKey) pair.getPublic();
        String kid = "identity-key-v4";
        var jwk = new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                "RS256", unsigned(rsa.getPublicExponent().toByteArray()), kid,
                "RSA", unsigned(rsa.getModulus().toByteArray()), "sig");
        var authority = new HrisServiceGrantAuthorityV1.GrantAuthority(
                UUID.randomUUID(), 4, 91, 7, 11, "ADMIT", "dwp-auth-server",
                "spiffe://dwp/service/dwp-auth-server", kid, jwk,
                "dwp-people-server", "spiffe://dwp/service/dwp-people-server",
                "people-key-v4", new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                        "RS256", unsigned(rsa.getPublicExponent().toByteArray()),
                        "people-key-v4", "RSA",
                        unsigned(rsa.getModulus().toByteArray()), "sig"),
                "SVC-PEP-HRM-008", "HRIS_SEPARATION_IDENTITY_TASK_RECEIPT",
                null, "c".repeat(64), now.minusSeconds(10));
        Hrm013IdentityWorkloadCredentialProvider.NonExportableSigner signer = input -> {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(pair.getPrivate());
            signature.update(input);
            return signature.sign();
        };
        var tls = new Hrm013IdentityWorkloadCredentialProvider
                .NonExportableTlsClient() { };
        AtomicReference<Hrm013IdentityWorkloadCredentialProvider
                .AtomicGrantCredentialLease> source = new AtomicReference<>();
        var provider = new Hrm013IdentityWorkloadCredentialProvider(
                (tenant, binding, purpose) -> source.get(),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(now, ZoneOffset.UTC));
        var provisional = lease(authority, signer, tls, jwk, "0".repeat(64), now);
        var expected = lease(authority, signer, tls, jwk,
                provider.digest(provisional), now);
        source.set(expected);
        assertThat(provider.recheckCurrent(authority, expected)).isSameAs(expected);

        List<Hrm013IdentityWorkloadCredentialProvider.AtomicGrantCredentialLease>
                invalidReloads = List.of(
                        variant(provider, expected, UUID.randomUUID(),
                                expected.leaseVersion(), expected.credentialRole(),
                                expected.businessKeyVersionId(),
                                expected.clientCertificateSha256(), signer, tls),
                        variant(provider, expected, expected.leaseId(),
                                expected.leaseVersion() + 1, expected.credentialRole(),
                                expected.businessKeyVersionId(),
                                expected.clientCertificateSha256(), signer, tls),
                        variant(provider, expected, expected.leaseId(),
                                expected.leaseVersion(), expected.credentialRole(),
                                "kms-version-5", expected.clientCertificateSha256(),
                                signer, tls),
                        variant(provider, expected, expected.leaseId(),
                                expected.leaseVersion(), expected.credentialRole(),
                                expected.businessKeyVersionId(), "B".repeat(43),
                                signer, tls),
                        variant(provider, expected, expected.leaseId(),
                                expected.leaseVersion(),
                                Hrm013IdentityWorkloadCredentialProvider
                                        .CredentialRole.OWNER,
                                expected.businessKeyVersionId(),
                                expected.clientCertificateSha256(), signer, tls),
                        variant(provider, expected, expected.leaseId(),
                                expected.leaseVersion(), expected.credentialRole(),
                                expected.businessKeyVersionId(),
                                expected.clientCertificateSha256(), signer,
                                new Hrm013IdentityWorkloadCredentialProvider
                                        .NonExportableTlsClient() { }));
        for (var invalid : invalidReloads) {
            source.set(invalid);
            assertThatThrownBy(() -> provider.recheckCurrent(authority, expected))
                    .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                    .extracting(failure -> ((Hrm013SafeErrorV1.Failure) failure)
                            .errorCode())
                    .isEqualTo(Hrm013SafeErrorV1.ErrorCode.AUTHORITY_UNAVAILABLE);
        }
        source.set(null);
        assertThatThrownBy(() -> provider.recheckCurrent(authority, expected))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    private static Hrm013IdentityWorkloadCredentialProvider.AtomicGrantCredentialLease
            variant(Hrm013IdentityWorkloadCredentialProvider provider,
                    Hrm013IdentityWorkloadCredentialProvider.AtomicGrantCredentialLease base,
                    UUID leaseId, long leaseVersion,
                    Hrm013IdentityWorkloadCredentialProvider.CredentialRole role,
                    String keyVersion, String certificate,
                    Hrm013IdentityWorkloadCredentialProvider.NonExportableSigner signer,
                    Hrm013IdentityWorkloadCredentialProvider.NonExportableTlsClient tls) {
        var provisional = new Hrm013IdentityWorkloadCredentialProvider
                .AtomicGrantCredentialLease(
                        leaseId, leaseVersion, base.tenantId(), base.grantSetId(),
                        base.grantRevision(), base.tenantGrantRevision(),
                        base.authorizationRevision(), role, base.bindingId(),
                        base.purpose(), base.callerWorkloadId(), base.callerSpiffeId(),
                        base.ownerWorkloadId(), base.ownerSpiffeId(),
                        base.serviceWorkloadId(), base.businessSigningKeyId(),
                        keyVersion, base.businessPublicJwkThumbprint(), signer,
                        base.clientSpiffeId(), certificate, tls, base.notBefore(),
                        base.expiresAt(), "0".repeat(64));
        return new Hrm013IdentityWorkloadCredentialProvider.AtomicGrantCredentialLease(
                provisional.leaseId(), provisional.leaseVersion(),
                provisional.tenantId(), provisional.grantSetId(),
                provisional.grantRevision(), provisional.tenantGrantRevision(),
                provisional.authorizationRevision(), provisional.credentialRole(),
                provisional.bindingId(), provisional.purpose(),
                provisional.callerWorkloadId(), provisional.callerSpiffeId(),
                provisional.ownerWorkloadId(), provisional.ownerSpiffeId(),
                provisional.serviceWorkloadId(), provisional.businessSigningKeyId(),
                provisional.businessKeyVersionId(),
                provisional.businessPublicJwkThumbprint(),
                provisional.businessPrivateKeyHandle(), provisional.clientSpiffeId(),
                provisional.clientCertificateSha256(), provisional.tlsClientHandle(),
                provisional.notBefore(), provisional.expiresAt(),
                provider.digest(provisional));
    }

    private static Hrm013IdentityWorkloadCredentialProvider.AtomicGrantCredentialLease
            lease(HrisServiceGrantAuthorityV1.GrantAuthority authority,
                    Hrm013IdentityWorkloadCredentialProvider.NonExportableSigner signer,
                    Hrm013IdentityWorkloadCredentialProvider.NonExportableTlsClient tls,
                    HrisServiceGrantAuthorityV1.RsaPublicJwk jwk,
                    String digest, Instant now) {
        return lease(authority, signer, tls, jwk, digest, now,
                Hrm013IdentityWorkloadCredentialProvider.CredentialRole.CALLER);
    }

    private static Hrm013IdentityWorkloadCredentialProvider.AtomicGrantCredentialLease
            lease(HrisServiceGrantAuthorityV1.GrantAuthority authority,
                    Hrm013IdentityWorkloadCredentialProvider.NonExportableSigner signer,
                    Hrm013IdentityWorkloadCredentialProvider.NonExportableTlsClient tls,
                    HrisServiceGrantAuthorityV1.RsaPublicJwk jwk,
                    String digest, Instant now,
                    Hrm013IdentityWorkloadCredentialProvider.CredentialRole role) {
        boolean caller = role
                == Hrm013IdentityWorkloadCredentialProvider.CredentialRole.CALLER;
        return new Hrm013IdentityWorkloadCredentialProvider.AtomicGrantCredentialLease(
                UUID.fromString("fbe65447-b9ef-4737-9532-a44460690711"), 1,
                authority.tenantId(), authority.grantSetId(),
                authority.grantRevision(), authority.tenantGrantRevision(),
                authority.authorizationRevision(),
                role,
                authority.bindingId(),
                authority.purpose(), authority.callerWorkloadId(),
                authority.callerSpiffeId(), authority.ownerWorkloadId(),
                authority.ownerSpiffeId(),
                caller ? authority.callerWorkloadId() : authority.ownerWorkloadId(),
                caller ? authority.callerSigningKeyId() : authority.ownerSigningKeyId(),
                "kms-version-4",
                Hrm013IdentityWorkloadCredentialProvider.publicJwkThumbprint(jwk),
                signer,
                caller ? authority.callerSpiffeId() : authority.ownerSpiffeId(),
                "A".repeat(43), tls,
                now.minusSeconds(5), now.plusSeconds(25), digest);
    }

    private static String unsigned(byte[] value) {
        int offset = value.length > 1 && value[0] == 0 ? 1 : 0;
        byte[] unsigned = java.util.Arrays.copyOfRange(value, offset, value.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned);
    }
}
