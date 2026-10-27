package com.dwp.services.auth.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class Hrm013IdentityOutboundTlsBoundaryTest {
    @Test
    void endpointCannotDowngradeOrEscapeTheConfiguredPeopleOrigin() {
        String path = "/internal/hris-people/v1/separation-cases/"
                + "e1713405-f2ae-42a5-a1f5-f3f7e167e855/tasks/"
                + "da2f48af-944f-439c-9ee3-ee94c5914e46/receipts";
        assertThat(Hrm013IdentityTaskReceiptRelay.endpoint(
                URI.create("https://people.internal:8443/"), path).toString())
                .isEqualTo("https://people.internal:8443" + path);
        assertThatThrownBy(() -> Hrm013IdentityTaskReceiptRelay.endpoint(
                URI.create("http://people.internal:8080/"), path))
                .isInstanceOf(Hrm013IdentityTaskReceiptRelay.DeliveryFailure.class);
    }

    @Test
    void postSendEvidenceRequiresTls13TrustRevocationSpiffeAndHost() {
        URI endpoint = URI.create("https://people.internal/callback");
        var accepted = response(new Hrm013IdentityTaskReceiptRelay.TlsEvidence(
                "TLSv1.3", true, true,
                "spiffe://dwp/service/dwp-people-server", "people.internal"));
        Hrm013IdentityTaskReceiptRelay.requireTrustedPeerAfterSend(
                accepted, endpoint, "spiffe://dwp/service/dwp-people-server");

        var rejected = response(new Hrm013IdentityTaskReceiptRelay.TlsEvidence(
                "TLSv1.2", true, true,
                "spiffe://dwp/service/dwp-people-server", "people.internal"));
        assertThatThrownBy(() ->
                Hrm013IdentityTaskReceiptRelay.requireTrustedPeerAfterSend(
                        rejected, endpoint,
                        "spiffe://dwp/service/dwp-people-server"))
                .isInstanceOf(Hrm013IdentityTaskReceiptRelay.DeliveryFailure.class)
                .satisfies(failure -> assertThat(
                        ((Hrm013IdentityTaskReceiptRelay.DeliveryFailure) failure)
                                .ambiguous()).isTrue());
    }

    @Test
    void invalidHandshakeEvidencePublishesZeroCallbackBodyBytes() {
        URI endpoint = URI.create("https://people.internal/callback");
        String peopleSpiffe = "spiffe://dwp/service/dwp-people-server";
        String clientCertificate = "C".repeat(43);
        List<Hrm013IdentityWorkloadCredentialProvider.VerifiedTlsEvidence> rejected =
                List.of(
                        evidence("TLSv1.2", true, true, peopleSpiffe,
                                "people.internal", clientCertificate),
                        evidence("TLSv1.3", true, true,
                                "spiffe://dwp/service/wrong-service",
                                "people.internal", clientCertificate),
                        evidence("TLSv1.3", true, true, peopleSpiffe,
                                "wrong.internal", clientCertificate),
                        evidence("TLSv1.3", true, false, peopleSpiffe,
                                "people.internal", clientCertificate),
                        evidence("TLSv1.3", true, true, peopleSpiffe,
                                "people.internal", "D".repeat(43)));

        for (var handshake : rejected) {
            AtomicInteger publishedBytes = new AtomicInteger();
            var request = new Hrm013IdentityTaskReceiptRelay.OutboundRequest(
                    endpoint, "/callback", "{}".getBytes(),
                    List.<Hrm013WorkloadProofV1.Header>of(),
                    lease(client(handshake, publishedBytes)), Duration.ofSeconds(1),
                    peopleSpiffe);

            assertThatThrownBy(() ->
                    new Hrm013IdentityTaskReceiptRelay.JavaHttpTransport().send(request))
                    .isInstanceOf(Hrm013IdentityTaskReceiptRelay.DeliveryFailure.class)
                    .satisfies(failure -> assertThat(
                            ((Hrm013IdentityTaskReceiptRelay.DeliveryFailure) failure)
                                    .getMessage())
                            .isEqualTo("PEER_IDENTITY_UNTRUSTED"));
            assertThat(publishedBytes).hasValue(0);
        }
    }

    @Test
    void validChannelReceivesTheBoundedRemainingDeadlineBeforeBodyPublish()
            throws Exception {
        URI endpoint = URI.create("https://people.internal/callback");
        String peopleSpiffe = "spiffe://dwp/service/dwp-people-server";
        String clientCertificate = "C".repeat(43);
        byte[] body = "{\"status\":\"COMPLETED\"}".getBytes();
        AtomicInteger publishedBytes = new AtomicInteger();
        AtomicReference<Duration> remaining = new AtomicReference<>();
        var tls = new Hrm013IdentityWorkloadCredentialProvider
                .NonExportableTlsClient() {
            @Override public Hrm013IdentityWorkloadCredentialProvider
                    .VerifiedTlsChannel openVerified(
                            URI selectedEndpoint, String expectedPeerSpiffe,
                            String expectedClientCertificate, Duration timeout) {
                return new Hrm013IdentityWorkloadCredentialProvider
                        .VerifiedTlsChannel() {
                    @Override public boolean totalDeadlineBound() { return true; }

                    @Override public Hrm013IdentityWorkloadCredentialProvider
                            .VerifiedTlsEvidence evidence() {
                        return Hrm013IdentityOutboundTlsBoundaryTest.evidence(
                                "TLSv1.3", true, true, peopleSpiffe,
                                "people.internal", clientCertificate);
                    }

                    @Override public Hrm013IdentityWorkloadCredentialProvider
                            .VerifiedTlsResponse post(
                                    List<Hrm013WorkloadProofV1.Header> headers,
                                    byte[] exactBody, int maximumResponseBytes) {
                        throw new AssertionError("unbounded post overload used");
                    }

                    @Override public Hrm013IdentityWorkloadCredentialProvider
                            .VerifiedTlsResponse post(
                                    List<Hrm013WorkloadProofV1.Header> headers,
                                    byte[] exactBody, int maximumResponseBytes,
                                    Duration remainingTimeout) {
                        remaining.set(remainingTimeout);
                        publishedBytes.addAndGet(exactBody.length);
                        return new Hrm013IdentityWorkloadCredentialProvider
                                .VerifiedTlsResponse(200, Map.of(), new byte[] {1},
                                        false, selectedEndpoint);
                    }
                };
            }
        };
        var request = new Hrm013IdentityTaskReceiptRelay.OutboundRequest(
                endpoint, "/callback", body,
                List.<Hrm013WorkloadProofV1.Header>of(), lease(tls),
                Duration.ofSeconds(1), peopleSpiffe);

        Hrm013IdentityTaskReceiptRelay.OutboundResponse response =
                new Hrm013IdentityTaskReceiptRelay.JavaHttpTransport().send(request);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(publishedBytes).hasValue(body.length);
        assertThat(remaining.get()).isPositive().isLessThanOrEqualTo(
                Duration.ofSeconds(1));
    }

    private static Hrm013IdentityWorkloadCredentialProvider.VerifiedTlsEvidence evidence(
            String protocol, boolean trusted, boolean revocation, String spiffe,
            String host, String clientCertificate) {
        return new Hrm013IdentityWorkloadCredentialProvider.VerifiedTlsEvidence(
                protocol, trusted, revocation, spiffe, host, clientCertificate);
    }

    private static Hrm013IdentityWorkloadCredentialProvider.NonExportableTlsClient
            client(Hrm013IdentityWorkloadCredentialProvider.VerifiedTlsEvidence evidence,
                    AtomicInteger publishedBytes) {
        return new Hrm013IdentityWorkloadCredentialProvider.NonExportableTlsClient() {
            @Override public Hrm013IdentityWorkloadCredentialProvider
                    .VerifiedTlsChannel openVerified(
                            URI endpoint, String expectedPeerSpiffe,
                            String expectedClientCertificate, Duration timeout) {
                return new Hrm013IdentityWorkloadCredentialProvider.VerifiedTlsChannel() {
                    @Override public Hrm013IdentityWorkloadCredentialProvider
                            .VerifiedTlsEvidence evidence() {
                        return evidence;
                    }

                    @Override public Hrm013IdentityWorkloadCredentialProvider
                            .VerifiedTlsResponse post(
                                    List<Hrm013WorkloadProofV1.Header> headers,
                                    byte[] exactBody, int maximumResponseBytes) {
                        publishedBytes.addAndGet(exactBody.length);
                        return new Hrm013IdentityWorkloadCredentialProvider
                                .VerifiedTlsResponse(200, Map.of(), new byte[] {1},
                                        false, endpoint);
                    }
                };
            }
        };
    }

    private static Hrm013IdentityWorkloadCredentialProvider.AtomicGrantCredentialLease
            lease(Hrm013IdentityWorkloadCredentialProvider.NonExportableTlsClient tls) {
        return new Hrm013IdentityWorkloadCredentialProvider.AtomicGrantCredentialLease(
                UUID.randomUUID(), 1, 91, UUID.randomUUID(), 4, 7, 11,
                Hrm013IdentityWorkloadCredentialProvider.CredentialRole.CALLER,
                "SVC-PEP-HRM-008", "HRIS_SEPARATION_IDENTITY_TASK_RECEIPT",
                "dwp-auth-server", "spiffe://dwp/service/dwp-auth-server",
                "dwp-people-server", "spiffe://dwp/service/dwp-people-server",
                "dwp-auth-server", "identity-key-v4", "kms-version-4",
                "A".repeat(43), input -> new byte[] {1},
                "spiffe://dwp/service/dwp-auth-server", "C".repeat(43), tls,
                Instant.parse("2026-10-27T02:59:55Z"),
                Instant.parse("2026-10-27T03:00:25Z"), "0".repeat(64));
    }

    private static Hrm013IdentityTaskReceiptRelay.OutboundResponse response(
            Hrm013IdentityTaskReceiptRelay.TlsEvidence evidence) {
        return new Hrm013IdentityTaskReceiptRelay.OutboundResponse(
                200, Map.of("Content-Type", List.of("application/json")),
                new byte[] {1}, evidence);
    }
}
