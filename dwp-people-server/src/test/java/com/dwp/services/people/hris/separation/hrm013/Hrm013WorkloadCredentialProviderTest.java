package com.dwp.services.people.hris.separation.hrm013;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Signature;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm013WorkloadCredentialProviderTest {
    @Test
    void atomicLeaseDigestJwkThumbprintAndPrivateKeyChallengeAreAllAttested() {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var keys = Hrm013RuntimeTestSupport.keys("people-caller", "approval-owner");
        var query = new Hrm013ServiceAuthorityPort.Query(
                41L, "dwp-people-server", "SVC-PEP-APR-002",
                "HRIS_SEPARATION_TASK_WAIVER_REQUEST", 7L, 11L);
        var authority = Hrm013RuntimeTestSupport.authority(
                query, "dwp-approval-server", keys, null);
        Hrm013WorkloadCredentialProvider provider = new Hrm013WorkloadCredentialProvider(
                (tenant, binding, purpose, role) -> null, mapper,
                Clock.fixed(Hrm013RuntimeTestSupport.NOW, ZoneOffset.UTC));
        var prototype = lease(keys, "0".repeat(64));
        var lease = lease(keys, provider.digest(prototype));
        provider = new Hrm013WorkloadCredentialProvider(
                (tenant, binding, purpose, role) -> lease, mapper,
                Clock.fixed(Hrm013RuntimeTestSupport.NOW, ZoneOffset.UTC));

        assertThat(provider.requireCurrent(
                41L, "SVC-PEP-APR-002", "HRIS_SEPARATION_TASK_WAIVER_REQUEST",
                "dwp-people-server", "dwp-approval-server",
                Hrm013WorkloadCredentialProvider.ServiceRole.CALLER)).isEqualTo(lease);
        provider.requireAttested(lease, authority);
    }

    @Test
    void aMixedOrTamperedLeaseFailsBeforeBusinessSigning() {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var keys = Hrm013RuntimeTestSupport.keys("people-caller", "approval-owner");
        var prototype = lease(keys, "0".repeat(64));
        var sourceProvider = new Hrm013WorkloadCredentialProvider(
                (tenant, binding, purpose, role) -> null, mapper,
                Clock.fixed(Hrm013RuntimeTestSupport.NOW, ZoneOffset.UTC));
        var valid = lease(keys, sourceProvider.digest(prototype));
        var provider = new Hrm013WorkloadCredentialProvider(
                (tenant, binding, purpose, role) -> new Hrm013WorkloadCredentialProvider
                        .AtomicGrantCredentialLease(
                        valid.leaseId(), valid.leaseVersion(), valid.tenantId(),
                        valid.grantSetId(), valid.grantRevision(),
                        valid.tenantGrantRevision(), valid.authorizationRevision(),
                        valid.bindingId(), valid.purpose(), valid.callerWorkloadId(),
                        valid.callerSpiffeId(), valid.ownerWorkloadId(),
                        valid.ownerSpiffeId(), valid.serviceRole(),
                        valid.businessSigningKeyId(), "mixed-key-version",
                        valid.businessPublicJwkThumbprint(),
                        valid.businessPrivateKeyHandle(), valid.notBefore(),
                        valid.expiresAt(), valid.leaseDigest()), mapper,
                Clock.fixed(Hrm013RuntimeTestSupport.NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> provider.requireCurrent(
                41L, valid.bindingId(), valid.purpose(), valid.callerWorkloadId(),
                valid.ownerWorkloadId(), valid.serviceRole()))
                .isInstanceOf(RuntimeException.class);
    }

    private static Hrm013WorkloadCredentialProvider.AtomicGrantCredentialLease lease(
            Hrm013RuntimeTestSupport.KeyMaterial keys, String digest) {
        return new Hrm013WorkloadCredentialProvider.AtomicGrantCredentialLease(
                UUID.fromString("00000000-0000-0000-0000-000000000041"), 1L, 41L,
                UUID.fromString("00000000-0000-0000-0000-000000000099"),
                3L, 7L, 11L, "SVC-PEP-APR-002",
                "HRIS_SEPARATION_TASK_WAIVER_REQUEST", "dwp-people-server",
                "spiffe://dwp/service/dwp-people-server", "dwp-approval-server",
                "spiffe://dwp/service/dwp-approval-server",
                Hrm013WorkloadCredentialProvider.ServiceRole.CALLER,
                keys.callerJwk().kid(), "people-caller-v1",
                Hrm013WorkloadCredentialProvider.publicJwkThumbprint(keys.callerJwk()),
                input -> {
                    Signature signer = Signature.getInstance("SHA256withRSA");
                    signer.initSign(keys.caller().getPrivate());
                    signer.update(input);
                    return signer.sign();
                }, Hrm013RuntimeTestSupport.NOW.minusSeconds(1),
                Hrm013RuntimeTestSupport.NOW.plusSeconds(60), digest);
    }
}
