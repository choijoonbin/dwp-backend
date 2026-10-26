package com.dwp.services.approval.hrisbridge.separation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Hrm013RequesterIdentityClientTest {
    @Test
    void dependentReadProofUsesTheEarlierRootAndServiceAuthorityExpiry() {
        Instant root = Instant.parse("2026-10-27T01:00:05Z");
        Instant service = root.plusSeconds(20);

        assertThat(HrisSeparationApprovalAuthorityResolver
                .minimumAuthorityExpiry(root, service)).isEqualTo(root);
        assertThat(HrisSeparationApprovalAuthorityResolver
                .minimumAuthorityExpiry(service, root)).isEqualTo(root);
    }

    @Test
    void refusesNonHttpsConfigurationAndAuthorityTupleDriftBeforeNetworkIo()
            throws Exception {
        var credentials = mock(HrisSeparationWorkloadCredentialProvider.class);
        assertThatThrownBy(() -> new Hrm013RequesterIdentityClient(
                new ObjectMapper(), credentials, "http://auth.internal"))
                .isInstanceOf(IllegalArgumentException.class);

        var original = HrisSeparationWorkloadCredentialProviderTest.snapshot(
                Instant.parse("2026-10-27T00:00:00Z"),
                Instant.parse("2026-10-27T02:00:00Z"));
        var lease = original.lease();
        var requesterLease = new HrisSeparationWorkloadCredentialProvider.Lease(
                lease.leaseId(), lease.leaseVersion(), lease.tenantId(),
                lease.grantSetId(), lease.grantRevision(),
                lease.tenantGrantRevision(), lease.authorizationRevision(),
                Hrm013RequesterIdentityAuthorityV1.BINDING_ID,
                "HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE",
                "dwp-approval-server", "spiffe://dwp/service/dwp-approval-server",
                "dwp-auth-server", "spiffe://dwp/service/dwp-auth-server",
                HrisSeparationWorkloadCredentialProvider.ServiceRole.CALLER,
                lease.businessSigningKeyId(), lease.businessKeyVersionId(),
                lease.businessPublicJwkThumbprint(), lease.notBefore(),
                lease.expiresAt(), lease.leaseDigest());
        var requester = new HrisSeparationWorkloadCredentialProvider.Snapshot(
                requesterLease, original.sslContext(), original.leafCertificate(),
                original.resolverPrivateKey(), original.businessPrivateKey());
        when(credentials.current(requesterLease.tenantId(),
                Hrm013RequesterIdentityAuthorityV1.BINDING_ID,
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK)).thenReturn(requester);
        var client = new Hrm013RequesterIdentityClient(
                new ObjectMapper().findAndRegisterModules(), credentials,
                "https://auth.internal");
        var request = new Hrm013RequesterIdentityAuthorityV1.ResolveRequest(
                42, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> client.resolve(request,
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null,
                requester, () -> requesterLease.expiresAt(),
                UUID.randomUUID(), requesterLease.tenantGrantRevision(),
                requesterLease.authorizationRevision(),
                requesterLease.businessSigningKeyId()))
                .isInstanceOf(HrisSeparationProtocolException.class);

        assertThatThrownBy(() -> client.resolve(request,
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null,
                requester, () -> {
                    throw HrisSeparationProtocolException.denied();
                }, requesterLease.grantSetId(),
                requesterLease.tenantGrantRevision(),
                requesterLease.authorizationRevision(),
                requesterLease.businessSigningKeyId()))
                .isInstanceOf(HrisSeparationProtocolException.class);
        verify(credentials, never()).businessProof(any(), any(), any(), any(),
                any(), any(), any(), any());
        verify(credentials, never()).client(any());
    }

    @Test
    void authorityMustRemainLiveThroughTheFullyValidatedResponse()
            throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var credentials = mock(HrisSeparationWorkloadCredentialProvider.class);
        var original = HrisSeparationWorkloadCredentialProviderTest.snapshot(
                Instant.parse("2026-10-27T00:00:00Z"),
                Instant.parse("2026-10-27T02:00:00Z"));
        var lease = original.lease();
        var requesterLease = new HrisSeparationWorkloadCredentialProvider.Lease(
                lease.leaseId(), lease.leaseVersion(), lease.tenantId(),
                lease.grantSetId(), lease.grantRevision(),
                lease.tenantGrantRevision(), lease.authorizationRevision(),
                Hrm013RequesterIdentityAuthorityV1.BINDING_ID,
                Hrm013RequesterIdentityAuthorityV1.PURPOSE,
                "dwp-approval-server", "spiffe://dwp/service/dwp-approval-server",
                "dwp-auth-server", "spiffe://dwp/service/dwp-auth-server",
                HrisSeparationWorkloadCredentialProvider.ServiceRole.CALLER,
                lease.businessSigningKeyId(), lease.businessKeyVersionId(),
                lease.businessPublicJwkThumbprint(), lease.notBefore(),
                lease.expiresAt(), lease.leaseDigest());
        var snapshot = new HrisSeparationWorkloadCredentialProvider.Snapshot(
                requesterLease, original.sslContext(), original.leafCertificate(),
                original.resolverPrivateKey(), original.businessPrivateKey());
        var request = new Hrm013RequesterIdentityAuthorityV1.ResolveRequest(
                42, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var admitted = new Hrm013RequesterIdentityAuthorityV1.ResolveResponse(
                Hrm013RequesterIdentityAuthorityV1.Status.ADMIT, 7L,
                request.requestedByActorPublicId(), 3L, 4L,
                requesterLease.tenantGrantRevision(),
                requesterLease.authorizationRevision(), request.correlationId());
        byte[] exact = Hrm013RequesterIdentityAuthorityV1.encodeResponse(
                mapper, admitted);

        var http = mock(java.net.http.HttpClient.class);
        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<java.io.InputStream> response =
                mock(java.net.http.HttpResponse.class);
        var session = mock(javax.net.ssl.SSLSession.class);
        var peer = mock(java.security.cert.X509Certificate.class);
        when(peer.getSubjectAlternativeNames()).thenReturn(java.util.List.of(
                java.util.List.of(6, "spiffe://dwp/service/dwp-auth-server"),
                java.util.List.of(2, "auth.internal")));
        when(session.getProtocol()).thenReturn("TLSv1.3");
        when(session.getPeerCertificates()).thenReturn(
                new java.security.cert.Certificate[] {peer});
        when(response.sslSession()).thenReturn(java.util.Optional.of(session));
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(
                java.util.Map.of("Content-Type", java.util.List.of("application/json")),
                (name, value) -> true));
        when(response.body()).thenReturn(new java.io.ByteArrayInputStream(exact));
        when(credentials.businessProof(any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn("proof");
        when(credentials.client(snapshot)).thenReturn(http);
        when(http.send(any(java.net.http.HttpRequest.class),
                any(java.net.http.HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        Instant authorityExpiresAt = requesterLease.expiresAt().minusSeconds(5);
        var client = new Hrm013RequesterIdentityClient(
                mapper, credentials, "https://auth.internal");

        assertThat(client.resolve(request,
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null,
                snapshot, () -> authorityExpiresAt,
                requesterLease.grantSetId(),
                requesterLease.tenantGrantRevision(),
                requesterLease.authorizationRevision(),
                requesterLease.businessSigningKeyId())).isEqualTo(admitted);
        verify(credentials, times(2)).requireLive(authorityExpiresAt);
    }
}
