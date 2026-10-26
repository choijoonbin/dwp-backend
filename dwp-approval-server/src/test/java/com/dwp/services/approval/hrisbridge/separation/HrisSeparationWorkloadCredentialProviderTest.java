package com.dwp.services.approval.hrisbridge.separation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class HrisSeparationWorkloadCredentialProviderTest {
    private static final Instant NOW = Instant.parse("2026-10-27T01:00:00Z");

    @Test
    void returnsOnlyAnExactLiveLeaseAndBuildsANoRedirectTls13Client() throws Exception {
        var snapshot = snapshot(NOW.minusSeconds(1), NOW.plusSeconds(30));
        var provider = new HrisSeparationWorkloadCredentialProvider(
                (tenant, binding, mode) -> snapshot,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(provider.current(42, "SVC-PEP-HRM-010",
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK)).isSameAs(snapshot);
        assertThat(provider.client(snapshot).followRedirects().name())
                .isEqualTo("NEVER");
        assertThat(provider.client(snapshot).sslParameters().getProtocols())
                .containsExactly("TLSv1.3");
        assertThatThrownBy(() -> provider.current(42, "SVC-PEP-APR-002",
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK))
                .isInstanceOf(HrisSeparationProtocolException.class);
        assertThatThrownBy(() -> provider.current(43, "SVC-PEP-HRM-010",
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK))
                .isInstanceOf(HrisSeparationProtocolException.class);
    }

    @Test
    void rejectsExpiredRuntimeDeliveredCredentials() throws Exception {
        var snapshot = snapshot(NOW.minusSeconds(30), NOW);
        var provider = new HrisSeparationWorkloadCredentialProvider(
                (tenant, binding, mode) -> snapshot,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> provider.current(42, "SVC-PEP-HRM-010",
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING))
                .isInstanceOf(HrisSeparationProtocolException.class);
    }

    static HrisSeparationWorkloadCredentialProvider.Snapshot snapshot(
            Instant notBefore, Instant expiresAt) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var resolver = generator.generateKeyPair();
        var business = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) business.getPublic();
        var jwk = new com.dwp.platform.contracts.hris.separation.v1
                .HrisServiceGrantAuthorityV1.RsaPublicJwk(
                "RS256", Base64.getUrlEncoder().withoutPadding().encodeToString(
                        publicKey.getPublicExponent().toByteArray()),
                "approval-key-1", "RSA",
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                        unsigned(publicKey.getModulus().toByteArray())), "sig");
        String thumbprint = HrisSeparationWorkloadCredentialProvider
                .publicJwkThumbprint(jwk);
        var draft = new HrisSeparationWorkloadCredentialProvider.Lease(
                UUID.randomUUID(), 1, 42, UUID.randomUUID(), 1, 11, 12,
                "SVC-PEP-HRM-010", "HRIS_SEPARATION_TASK_WAIVER_DECISION",
                "dwp-approval-server", "spiffe://dwp/service/dwp-approval-server",
                "dwp-people-server", "spiffe://dwp/service/dwp-people-server",
                HrisSeparationWorkloadCredentialProvider.ServiceRole.CALLER,
                "approval-key-1", "approval-key-version-1", thumbprint,
                notBefore, expiresAt, "0".repeat(64));
        var mapper = new ObjectMapper().findAndRegisterModules();
        var digestProvider = new HrisSeparationWorkloadCredentialProvider(
                (tenant, binding, mode) -> null, mapper,
                Clock.fixed(notBefore, ZoneOffset.UTC));
        var lease = new HrisSeparationWorkloadCredentialProvider.Lease(
                draft.leaseId(), draft.leaseVersion(), draft.tenantId(),
                draft.grantSetId(), draft.grantRevision(),
                draft.tenantGrantRevision(), draft.authorizationRevision(),
                draft.bindingId(), draft.purpose(), draft.callerWorkloadId(),
                draft.callerSpiffeId(), draft.ownerWorkloadId(),
                draft.ownerSpiffeId(), draft.serviceRole(),
                draft.businessSigningKeyId(), draft.businessKeyVersionId(),
                draft.businessPublicJwkThumbprint(), draft.notBefore(),
                draft.expiresAt(), digestProvider.leaseDigest(draft));
        return new HrisSeparationWorkloadCredentialProvider.Snapshot(lease,
                javax.net.ssl.SSLContext.getDefault(), mock(X509Certificate.class),
                resolver.getPrivate(), business.getPrivate());
    }

    private static byte[] unsigned(byte[] value) {
        return value.length > 1 && value[0] == 0
                ? java.util.Arrays.copyOfRange(value, 1, value.length) : value;
    }
}
