package com.dwp.services.payroll.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class Hrm013PayrollWorkloadCredentialProviderTest {
    @Test
    void enabledRuntimeFailsClosedWithoutExternalCredentialSource() {
        @SuppressWarnings("unchecked")
        ObjectProvider<Hrm013PayrollWorkloadCredentialProvider.CredentialSource> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> new Hrm013PayrollWorkloadCredentialProvider(
                provider, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("external credential source");
    }

    @Test
    void requiresTheExactServiceRoleForEachAuthorityOperation() {
        Instant now = Instant.parse("2026-10-27T05:00:00Z");
        var provider = spy(new Hrm013PayrollWorkloadCredentialProvider(
                (tenant, binding, mode) -> {
                    throw new AssertionError("stubbed current call expected");
                }, new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC)));
        var snapshot = mock(Hrm013PayrollWorkloadCredentialProvider.Snapshot.class);
        var lease = mock(Hrm013PayrollWorkloadCredentialProvider.Lease.class);
        when(snapshot.lease()).thenReturn(lease);
        when(lease.serviceRole()).thenReturn(
                Hrm013PayrollWorkloadCredentialProvider.ServiceRole.OWNER);
        doReturn(snapshot).when(provider).current(41L,
                Hrm013TaskDispatchV1.PAYROLL.bindingId(),
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK);

        assertThat(provider.current(41L, Hrm013TaskDispatchV1.PAYROLL.bindingId(),
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK,
                Hrm013PayrollWorkloadCredentialProvider.ServiceRole.OWNER))
                .isSameAs(snapshot);
        assertThatThrownBy(() -> provider.current(41L,
                Hrm013TaskDispatchV1.PAYROLL.bindingId(),
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK,
                Hrm013PayrollWorkloadCredentialProvider.ServiceRole.CALLER))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(failure -> ((Hrm013SafeErrorV1.Failure) failure)
                        .errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.AUTHORITY_DENIED);
    }

    @Test
    void jwkThumbprintIsStableAndRfc7638Shaped() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey key = (RSAPublicKey) generator.generateKeyPair().getPublic();
        var jwk = new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                "RS256", encoded(key.getPublicExponent().toByteArray()), "pay-kid-001",
                "RSA", encoded(unsigned(key.getModulus().toByteArray())), "sig");
        String first = Hrm013PayrollWorkloadCredentialProvider.publicJwkThumbprint(jwk);
        assertThat(first).hasSize(43)
                .isEqualTo(Hrm013PayrollWorkloadCredentialProvider.publicJwkThumbprint(jwk));
    }

    @Test
    void admissionProofUsesTheClosedOwnerReceiptProfileAndExactBodyDigest()
            throws Exception {
        Instant now = Instant.parse("2026-10-27T05:00:00Z");
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn("payroll-leaf".getBytes(StandardCharsets.UTF_8));
        var lease = new Hrm013PayrollWorkloadCredentialProvider.Lease(
                UUID.randomUUID(), 1, 41, UUID.randomUUID(), 5, 7, 9,
                Hrm013TaskDispatchV1.PAYROLL.bindingId(),
                Hrm013TaskDispatchV1.PAYROLL.purpose(),
                "dwp-people-server", "spiffe://dwp/service/dwp-people-server",
                "dwp-payroll-server", "spiffe://dwp/service/dwp-payroll-server",
                Hrm013PayrollWorkloadCredentialProvider.ServiceRole.OWNER,
                "payroll-kid-001", "payroll-key-version-1", "A".repeat(43),
                now.minusSeconds(1), now.plusSeconds(60), "0".repeat(64));
        var snapshot = new Hrm013PayrollWorkloadCredentialProvider.Snapshot(
                lease, SSLContext.getDefault(), certificate,
                pair.getPrivate(), pair.getPrivate());
        ObjectMapper mapper = new ObjectMapper();
        var provider = new Hrm013PayrollWorkloadCredentialProvider(
                (tenant, binding, mode) -> snapshot, mapper,
                Clock.fixed(now, ZoneOffset.UTC));
        UUID dispatchId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        var receipt = Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1(
                        dispatchId, taskId, "a".repeat(64), "b".repeat(64), now,
                        "0".repeat(64), correlationId));
        byte[] exact = Hrm013TaskDispatchV1.encode(receipt);

        String proof = provider.admissionReceiptProof(snapshot,
                Hrm013TaskDispatchV1.PAYROLL, 41L, receipt, exact,
                now.plusSeconds(30));

        String[] parts = proof.split("\\.");
        assertThat(parts).hasSize(3);
        var header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        var claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.path("typ").asText())
                .isEqualTo("DWP-HRM013-RECEIPT-JWT");
        assertThat(claims.path("iss").asText()).isEqualTo("dwp-payroll-server");
        assertThat(claims.path("aud").asText()).isEqualTo("dwp-people-server");
        assertThat(claims.path("purpose").asText())
                .isEqualTo("HRIS_SEPARATION_PAYROLL_TASK_DISPATCH_ADMISSION_RECEIPT");
        assertThat(claims.path("status").asInt()).isEqualTo(202);
        assertThat(claims.path("responseBodySha256").asText())
                .isEqualTo(com.dwp.platform.contracts.hris.separation.v1
                        .Hrm013WorkloadProofV1.sha256(exact));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    private static byte[] unsigned(byte[] bytes) {
        if (bytes.length > 1 && bytes[0] == 0) {
            return java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    private static String encoded(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(bytes));
    }
}
