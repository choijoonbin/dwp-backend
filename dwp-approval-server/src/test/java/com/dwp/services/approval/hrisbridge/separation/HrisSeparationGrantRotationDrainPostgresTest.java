package com.dwp.services.approval.hrisbridge.separation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class HrisSeparationGrantRotationDrainPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void callbackCallerReceiptRequiresZeroPendingAndSignsExactDetachedClaims()
            throws Exception {
        var fixture = new Hrm013ApprovalPostgresFixture(POSTGRES, 42603);
        Instant now = Instant.now();
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var resolverKey = generator.generateKeyPair();
        var businessKey = generator.generateKeyPair();
        var lease = new HrisSeparationWorkloadCredentialProvider.Lease(
                UUID.randomUUID(), 1, fixture.tenant, fixture.grant.grantId(),
                fixture.grant.grantRevision() + 37,
                fixture.grant.tenantGrantRevision(),
                fixture.grant.authorizationRevision(),
                HrisSeparationPersistenceModels.CALLBACK_BINDING,
                HrisSeparationPersistenceModels.CALLBACK_PURPOSE,
                HrisSeparationPersistenceModels.APPROVAL_WORKLOAD,
                HrisSeparationPersistenceModels.APPROVAL_SPIFFE,
                HrisSeparationPersistenceModels.PEOPLE_WORKLOAD,
                HrisSeparationPersistenceModels.PEOPLE_SPIFFE,
                HrisSeparationWorkloadCredentialProvider.ServiceRole.CALLER,
                fixture.grant.approvalSigningKeyId(), "approval-key-version-1",
                "A".repeat(43), now.minusSeconds(30), now.plusSeconds(300),
                "b".repeat(64));
        var snapshot = new HrisSeparationWorkloadCredentialProvider.Snapshot(
                lease, javax.net.ssl.SSLContext.getDefault(),
                mock(X509Certificate.class), resolverKey.getPrivate(),
                businessKey.getPrivate());
        var credentials = mock(HrisSeparationWorkloadCredentialProvider.class);
        when(credentials.current(lease.tenantId(), lease.bindingId(),
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING))
                .thenReturn(snapshot);
        UUID fence = UUID.randomUUID();
        Instant quiet = now.minusSeconds(66);
        UUID correlation = UUID.randomUUID();
        var observation = new HrisServiceGrantAuthorityV1.ReadinessObservation(
                fixture.tenant, fixture.grant.grantId(),
                fixture.grant.tenantGrantRevision(),
                fixture.grant.authorizationRevision(), lease.callerWorkloadId(),
                lease.ownerWorkloadId(), lease.bindingId(), lease.purpose(), fence,
                1, quiet.minusSeconds(1),
                HrisServiceGrantAuthorityV1.ServiceRole.CALLER, 0, quiet,
                quiet.plusSeconds(65), "c".repeat(64));
        var authority = mock(AuthHrm013ServiceAuthorityClient.class);
        when(authority.resolve(any(), any())).thenReturn(
                HrisServiceGrantAuthorityV1.ResolveResponse.readiness(
                        observation, correlation));
        var mapper = new ObjectMapper().findAndRegisterModules();
        var barrier = new HrisSeparationGrantRotationBarrier(fixture.named,
                fixture.manager, credentials, authority, mapper);

        var binding = fixture.binding(3);
        fixture.bindings.saveOrReplay(binding);
        var decision = fixture.decision(binding, 3, false);
        fixture.decisions.append(decision);
        assertThatThrownBy(() -> barrier.attest(
                lease.tenantId(), lease.bindingId(), correlation))
                .isInstanceOf(HrisSeparationProtocolException.class);

        var claim = fixture.decisions.claim(fixture.tenant, "rotation-test",
                now.plusSeconds(30)).orElseThrow();
        fixture.decisions.delivered(claim, "rotation-test",
                "{\"accepted\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                now);
        var item = barrier.attest(lease.tenantId(), lease.bindingId(), correlation);
        var receipt = mapper.readTree(item.receiptBytes());
        assertThat(receipt.path("pendingCount").asLong()).isZero();
        assertThat(receipt.path("oldestPendingAt").isNull()).isTrue();
        assertThat(receipt.path("attestingServiceRole").asText())
                .isEqualTo("CALLER");
        assertThat(receipt.path("observedGrantRevision").asLong())
                .isEqualTo(fixture.grant.grantRevision() + 37);

        String[] parts = item.readinessProof().split("\\.", -1);
        assertThat(parts).hasSize(3);
        assertThat(parts[1]).isEmpty();
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(businessKey.getPublic());
        verifier.update((parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(item.proofClaimBytes()))
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }
}
