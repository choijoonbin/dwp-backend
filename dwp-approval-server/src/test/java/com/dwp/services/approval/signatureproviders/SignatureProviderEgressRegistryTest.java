package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.SignatureProviderModel.*;
import static org.assertj.core.api.Assertions.*;

import com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.SourcePin;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SignatureProviderEgressRegistryTest {
    private static final URI ORIGIN = URI.create("https://demo.docusign.net");
    private static final String SHA = "a".repeat(64);
    private final long tenant = 42;
    private final UUID provider = UUID.randomUUID();
    private final SourcePin configuration = new SourcePin(UUID.randomUUID(), 2, SHA);

    @Test void requiresAnExplicitServerApprovedOriginAndFreezesIt() {
        assertThatThrownBy(() -> new SignatureProviderEgressRegistry(List.of(config()), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var binding = binding(); assertThat(binding.origin()).isEqualTo(ORIGIN);
        assertThat(binding.environment()).isEqualTo(Environment.SANDBOX);
        assertThat(SignatureProviderEgressRegistry.Binding.class.getDeclaredConstructors()).allMatch(c -> java.lang.reflect.Modifier.isPrivate(c.getModifiers()));
    }

    @Test void rejectsNoncanonicalInsecureAndCredentialBearingOrigins() {
        for (String value : List.of("http://demo.docusign.net", "https://127.0.0.1", "https://[::1]", "https://localhost",
                "https://DEMO.docusign.net", "https://demo.docusign.net:443", "https://demo.docusign.net/",
                "https://user:password@demo.docusign.net", "https://demo.docusign.net?next=localhost", "https://demo.docusign.net#fragment"))
            assertThatThrownBy(() -> SignatureProviderEgressRegistry.requireOrigin(URI.create(value)))
                    .as(value).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsStaleConfigurationOtherTenantAccountAndEnvironment() {
        var registry = new SignatureProviderEgressRegistry(List.of(config()), Set.of(ORIGIN));
        assertThatThrownBy(() -> registry.require(43, provider, ORIGIN, configuration, Environment.SANDBOX, SHA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.require(tenant, provider, ORIGIN, new SourcePin(configuration.sourceId(), 3, SHA), Environment.SANDBOX, SHA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.require(tenant, provider, ORIGIN, configuration, Environment.PRODUCTION, SHA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.require(tenant, provider, ORIGIN, configuration, Environment.SANDBOX, "b".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsEveryPrivateReservedAndMappedDnsAddressWithoutTruncating() throws Exception {
        var publicAddress = InetAddress.getByName("8.8.8.8");
        for (String value : List.of("0.0.0.0", "10.1.2.3", "127.0.0.1", "100.64.0.1", "169.254.169.254",
                "172.16.0.1", "192.168.0.1", "192.0.0.1", "192.0.2.1", "192.88.99.1", "198.18.0.1",
                "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "::", "::1", "fc00::1", "fe80::1",
                "ff02::1", "2001:db8::1", "2001::1", "2002:7f00:1::", "::ffff:127.0.0.1")) {
            var address = InetAddress.getByName(value);
            assertThat(SignatureProviderPinnedDnsResolver.isPublic(address)).as(value).isFalse();
            assertThatThrownBy(() -> SignatureProviderPinnedDnsResolver.snapshot(ORIGIN.getHost(), new InetAddress[]{publicAddress, address}))
                    .as(value).isInstanceOf(java.net.UnknownHostException.class);
        }
        assertThat(SignatureProviderPinnedDnsResolver.isPublic(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
        assertThat(SignatureProviderPinnedDnsResolver.isPublic(InetAddress.getByName("192.0.33.1"))).isTrue();
        assertThat(SignatureProviderPinnedDnsResolver.isPublic(InetAddress.getByName("192.2.0.1"))).isTrue();
    }

    @Test void returnsOnlyTheSameDefensiveIpSnapshotAtPort443WithoutAnotherResolution() throws Exception {
        var address = InetAddress.getByName("8.8.8.8"); var answers = new InetAddress[]{address};
        var resolver = SignatureProviderPinnedDnsResolver.snapshot(ORIGIN.getHost(), answers);
        answers[0] = InetAddress.getByName("127.0.0.1"); resolver.resolve(ORIGIN.getHost())[0] = answers[0];
        assertThat(resolver.resolve(ORIGIN.getHost())).containsExactly(address);
        assertThat(resolver.resolve(ORIGIN.getHost(), 443)).singleElement().satisfies(socket -> {
            assertThat(socket.getAddress()).isEqualTo(address); assertThat(socket.getPort()).isEqualTo(443);
        });
        assertThat(resolver.resolveCanonicalHostname(ORIGIN.getHost())).isEqualTo(ORIGIN.getHost());
        assertThatThrownBy(() -> resolver.resolve("other.docusign.net")).isInstanceOf(java.net.UnknownHostException.class);
        assertThatThrownBy(() -> resolver.resolve(ORIGIN.getHost(), 8443)).isInstanceOf(java.net.UnknownHostException.class);
        assertThatThrownBy(() -> SignatureProviderPinnedDnsResolver.snapshot(ORIGIN.getHost(), new InetAddress[9]))
                .isInstanceOf(java.net.UnknownHostException.class);
    }

    @Test void rejectsAbsoluteEncodedCrossOriginAndAmbiguousNativePaths() {
        var binding = binding();
        assertThat(SignatureProviderHttpTransport.target(binding, "GET", "/restapi/v2.1/accounts/account/envelopes?transaction_ids=original"))
                .isEqualTo(URI.create(ORIGIN + "/restapi/v2.1/accounts/account/envelopes?transaction_ids=original"));
        for (String value : List.of("https://other.example.com/", "//other.example.com/", "/../escape", "/a/./b",
                "/a//b", "/%2e%2e/escape", "/a\\b", "/a#fragment", "/a\r\nAuthorization: injected"))
            assertThatThrownBy(() -> SignatureProviderHttpTransport.target(binding, "POST", value))
                    .as(value).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignatureProviderHttpTransport.target(binding, "HEAD", "/restapi/v2.1/accounts/account"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void neverExposesTokensThroughToStringOrAcceptsInjectedAuthorization() {
        var token = SignatureProviderHttpTransport.AccessToken.fromCredentialExchange("native.oauth-token");
        assertThat(token.toString()).doesNotContain("native.oauth-token");
        assertThatThrownBy(() -> SignatureProviderHttpTransport.AccessToken.fromCredentialExchange("token\r\nX-Injected: true"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SignatureProviderEgressRegistry.ConfiguredOrigin config() {
        return new SignatureProviderEgressRegistry.ConfiguredOrigin(tenant, provider, ProviderKind.DOCUSIGN,
                Environment.SANDBOX, configuration, SHA, ORIGIN);
    }
    private SignatureProviderEgressRegistry.Binding binding() {
        return new SignatureProviderEgressRegistry(List.of(config()), Set.of(ORIGIN))
                .require(tenant, provider, ORIGIN, configuration, Environment.SANDBOX, SHA);
    }
}
