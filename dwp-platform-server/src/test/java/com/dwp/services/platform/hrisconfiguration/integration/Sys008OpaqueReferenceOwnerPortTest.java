package com.dwp.services.platform.hrisconfiguration.integration;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ReferenceType.PAYLOAD_REF;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ReferenceType.PROVIDER_RECEIPT_REF;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ReferenceType.RESULT_REF;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ReferenceType.SAMPLE_REF;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ReferenceType.ERROR_REPORT_REF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Sys008OpaqueReferenceOwnerPortTest {

    private static final UUID CONNECTOR =
            UUID.fromString("00000000-0000-4000-8000-000000000821");
    private static final UUID OTHER_CONNECTOR =
            UUID.fromString("00000000-0000-4000-8000-000000000822");
    private static final UUID EXECUTION =
            UUID.fromString("00000000-0000-4000-8000-000000000823");
    private static final String REFERENCE = "ref:payload:008";
    private static final String DIGEST = "a".repeat(64);
    private static final Sys008OpaqueReferenceOwnerPort.ReferenceConstraint UNPINNED =
            Sys008OpaqueReferenceOwnerPort.UnpinnedReferenceConstraint.INSTANCE;

    @Test
    void resolvesOnlyAfterAuthenticationAndAllEightBindingAxesMatch() {
        var resolved = resolved();
        AtomicInteger calls = new AtomicInteger();
        Sys008OpaqueReferenceOwnerPort port = reference -> {
            calls.incrementAndGet();
            return REFERENCE.equals(reference) ? Optional.of(resolved) : Optional.empty();
        };

        assertThat(port.resolveAuthorized(request(
                REFERENCE, 42, CONNECTOR, "subject:84", "HRIS_INTEGRATION_ADMIN",
                PAYLOAD_REF, "hris.integration", DIGEST, 7, true)))
                .contains(resolved);
        assertThat(calls).hasValue(1);

        assertThat(port.resolveAuthorized(request(
                REFERENCE, 42, CONNECTOR, "subject:84", "HRIS_INTEGRATION_ADMIN",
                PAYLOAD_REF, "hris.integration", DIGEST, 7, false))).isEmpty();
        assertThat(port.resolveAuthorized(request(
                REFERENCE, 42, CONNECTOR, "subject:84", "WRONG_PURPOSE",
                PAYLOAD_REF, "hris.integration", DIGEST, 7, true))).isEmpty();
        assertThat(calls).hasValue(1);

        List<Sys008OpaqueReferenceOwnerPort.ResolutionRequest> substitutions = List.of(
                request(REFERENCE, 43, CONNECTOR, "subject:84",
                        "HRIS_INTEGRATION_ADMIN", PAYLOAD_REF,
                        "hris.integration", DIGEST, 7, true),
                request(REFERENCE, 42, OTHER_CONNECTOR, "subject:84",
                        "HRIS_INTEGRATION_ADMIN", PAYLOAD_REF,
                        "hris.integration", DIGEST, 7, true),
                request(REFERENCE, 42, CONNECTOR, "subject:85",
                        "HRIS_INTEGRATION_ADMIN", PAYLOAD_REF,
                        "hris.integration", DIGEST, 7, true),
                request(REFERENCE, 42, CONNECTOR, "subject:84",
                        "HRIS_INTEGRATION_ADMIN", RESULT_REF,
                        "hris.integration", DIGEST, 7, true),
                request(REFERENCE, 42, CONNECTOR, "subject:84",
                        "HRIS_INTEGRATION_ADMIN", PAYLOAD_REF,
                        "other.namespace", DIGEST, 7, true),
                request(REFERENCE, 42, CONNECTOR, "subject:84",
                        "HRIS_INTEGRATION_ADMIN", PAYLOAD_REF,
                        "hris.integration", "b".repeat(64), 7, true),
                request(REFERENCE, 42, CONNECTOR, "subject:84",
                        "HRIS_INTEGRATION_ADMIN", PAYLOAD_REF,
                        "hris.integration", DIGEST, 8, true));
        substitutions.forEach(substitution ->
                assertThat(port.resolveAuthorized(substitution)).isEmpty());
        assertThat(Sys008OpaqueReferenceOwnerPort.ReferenceType.values()).hasSize(8);
        assertThat(Sys008OpaqueReferenceOwnerPort.PRODUCTION_PROVIDER_BINDING_COUNT)
                .isZero();
    }

    @Test
    void providerFailureAndUnknownReferenceShareTheSameFailClosedSurface() {
        Sys008OpaqueReferenceOwnerPort unavailable = reference -> {
            throw new IllegalStateException("provider unavailable");
        };
        Sys008OpaqueReferenceOwnerPort absent = reference -> Optional.empty();

        assertThat(unavailable.resolveAuthorized(validRequest())).isEmpty();
        assertThat(absent.resolveAuthorized(validRequest())).isEmpty();
        assertThat(resolved().providerHandle().handleId())
                .isEqualTo("provider-handle:008");
    }

    @Test
    void resolvesCanonicalColonReferencesWithoutMakingThemLocations() {
        Map<Sys008OpaqueReferenceOwnerPort.ReferenceType, String> canonical = Map.of(
                SAMPLE_REF, "ref:sample:008",
                PAYLOAD_REF, "ref:payload:008",
                RESULT_REF, "ref:result:008",
                ERROR_REPORT_REF, "ref:error:008");

        canonical.forEach((type, reference) -> {
            var resolved = new Sys008OpaqueReferenceOwnerPort.ResolvedReference(
                    reference, 42, CONNECTOR, "subject:84",
                    "HRIS_INTEGRATION_ADMIN", type, "hris.integration",
                    DIGEST, 7, UNPINNED,
                    new Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle(
                            "provider-handle:008"));
            Sys008OpaqueReferenceOwnerPort port = candidate ->
                    reference.equals(candidate)
                            ? Optional.of(resolved) : Optional.empty();
            assertThat(port.resolveAuthorized(request(
                    reference, 42, CONNECTOR, "subject:84",
                    "HRIS_INTEGRATION_ADMIN", type, "hris.integration",
                    DIGEST, 7, true))).contains(resolved);
        });

        List.of("https://vault.example.test/object", "/var/run/secret",
                        "ref:sample:008?token=secret", "ref:sample:008#fragment")
                .forEach(location -> assertThatThrownBy(() -> request(
                        location, 42, CONNECTOR, "subject:84",
                        "HRIS_INTEGRATION_ADMIN", SAMPLE_REF,
                        "hris.integration", DIGEST, 7, true))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("opaque reference"));
    }

    @Test
    void enforcesTheSealedTransportAndCanonicalReferenceLengths() {
        assertThat(request("r".repeat(256), 42, CONNECTOR, "subject:84",
                "HRIS_INTEGRATION_ADMIN", SAMPLE_REF, "hris.integration",
                DIGEST, 7, true).opaqueReference()).hasSize(256);
        assertThat(request("r".repeat(512), 42, CONNECTOR, "subject:84",
                "HRIS_INTEGRATION_ADMIN", ERROR_REPORT_REF, "hris.integration",
                DIGEST, 7, true).opaqueReference()).hasSize(512);
        assertThatThrownBy(() -> request(
                "r".repeat(257), 42, CONNECTOR, "subject:84",
                "HRIS_INTEGRATION_ADMIN", PAYLOAD_REF, "hris.integration",
                DIGEST, 7, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opaque reference");
        assertThatThrownBy(() -> request(
                "r".repeat(513), 42, CONNECTOR, "subject:84",
                "HRIS_INTEGRATION_ADMIN", RESULT_REF, "hris.integration",
                DIGEST, 7, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opaque reference");
    }

    @Test
    void rejectsBrowserUrisAndPinsProviderReceiptsAndCompensationPolicies() {
        assertThatThrownBy(() -> request(
                "https://vault.example.test/secret", 42, CONNECTOR, "subject:84",
                "HRIS_INTEGRATION_ADMIN", PAYLOAD_REF,
                "hris.integration", DIGEST, 7, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opaque reference");

        var providerPin = new Sys008OpaqueReferenceOwnerPort.ProviderReceiptConstraint(
                EXECUTION, "workday", "provider-idempotency-008");
        var providerResolved = new Sys008OpaqueReferenceOwnerPort.ResolvedReference(
                "ref_providerreceipt008", 42, CONNECTOR, "subject:84",
                "HRIS_INTEGRATION_ADMIN", PROVIDER_RECEIPT_REF,
                "hris.integration", DIGEST, 7, providerPin,
                new Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle(
                        "provider-handle:008"));
        Sys008OpaqueReferenceOwnerPort providerPort =
                reference -> Optional.of(providerResolved);
        var exact = new Sys008OpaqueReferenceOwnerPort.ResolutionRequest(
                "ref_providerreceipt008", 42, CONNECTOR, "subject:84",
                "HRIS_INTEGRATION_ADMIN", PROVIDER_RECEIPT_REF,
                "hris.integration", DIGEST, 7, providerPin, true);
        var substituted = new Sys008OpaqueReferenceOwnerPort.ResolutionRequest(
                "ref_providerreceipt008", 42, CONNECTOR, "subject:84",
                "HRIS_INTEGRATION_ADMIN", PROVIDER_RECEIPT_REF,
                "hris.integration", DIGEST, 7,
                new Sys008OpaqueReferenceOwnerPort.ProviderReceiptConstraint(
                        EXECUTION, "workday", "different-idempotency-008"), true);
        assertThat(providerPort.resolveAuthorized(exact)).contains(providerResolved);
        assertThat(providerPort.resolveAuthorized(substituted)).isEmpty();
        assertThatThrownBy(() -> new Sys008OpaqueReferenceOwnerPort
                .CompensationPolicyConstraint(EXECUTION, 3, "IMPORT", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approved");
    }

    @Test
    void egressRejectsPrivateMetadataQuerySecretsRedirectAndDnsRebinding()
            throws Exception {
        var allowlist = new Sys008EgressBoundary.Allowlist(
                Set.of("https"), Set.of("connector.example.test"), Set.of(443));
        URI endpoint = URI.create("https://connector.example.test/v1/import");
        InetAddress publicOne = InetAddress.getByName("8.8.8.8");
        InetAddress publicTwo = InetAddress.getByName("8.8.4.4");
        var pin = Sys008EgressBoundary.requireAllowed(
                endpoint, allowlist, List.of(publicOne));

        assertThat(pin.host()).isEqualTo("connector.example.test");
        assertThat(Sys008EgressBoundary.requireSafeRedirectOrDnsRefresh(
                pin, endpoint, allowlist, List.of(publicOne))).isEqualTo(pin);
        assertThatThrownBy(() -> Sys008EgressBoundary.requireAllowed(
                URI.create("https://user:pass@connector.example.test/v1"),
                allowlist, List.of(publicOne))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sys008EgressBoundary.requireAllowed(
                URI.create("https://connector.example.test/v1?token=secret"),
                allowlist, List.of(publicOne))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sys008EgressBoundary.requireAllowed(
                endpoint, allowlist, List.of(InetAddress.getByName("127.0.0.1"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sys008EgressBoundary.requireAllowed(
                endpoint, allowlist,
                List.of(InetAddress.getByName("169.254.169.254"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sys008EgressBoundary.requireAllowed(
                endpoint, allowlist,
                List.of(InetAddress.getByName("100.64.0.1"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sys008EgressBoundary.requireAllowed(
                endpoint, allowlist,
                List.of(InetAddress.getByName("fc00::1"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sys008EgressBoundary
                .requireSafeRedirectOrDnsRefresh(
                        pin, endpoint, allowlist, List.of(publicTwo)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rebinding");
        assertThatThrownBy(() -> Sys008EgressBoundary.requireAllowed(
                URI.create("http://connector.example.test/v1"),
                allowlist, List.of(publicOne))).isInstanceOf(IllegalArgumentException.class);
    }

    private static Sys008OpaqueReferenceOwnerPort.ResolutionRequest validRequest() {
        return request(REFERENCE, 42, CONNECTOR, "subject:84",
                "HRIS_INTEGRATION_ADMIN", PAYLOAD_REF,
                "hris.integration", DIGEST, 7, true);
    }

    private static Sys008OpaqueReferenceOwnerPort.ResolutionRequest request(
            String reference,
            long tenant,
            UUID connector,
            String subject,
            String purpose,
            Sys008OpaqueReferenceOwnerPort.ReferenceType type,
            String namespace,
            String digest,
            long revision,
            boolean authorized) {
        return new Sys008OpaqueReferenceOwnerPort.ResolutionRequest(
                reference, tenant, connector, subject, purpose, type,
                namespace, digest, revision, UNPINNED, authorized);
    }

    private static Sys008OpaqueReferenceOwnerPort.ResolvedReference resolved() {
        return new Sys008OpaqueReferenceOwnerPort.ResolvedReference(
                REFERENCE, 42, CONNECTOR, "subject:84", "HRIS_INTEGRATION_ADMIN",
                PAYLOAD_REF, "hris.integration", DIGEST, 7, UNPINNED,
                new Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle(
                        "provider-handle:008"));
    }
}
