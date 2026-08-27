package com.dwp.core.autoconfig;

import com.dwp.core.crypto.KeyProviderException;
import com.dwp.core.crypto.LocalKeyProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyManagementAutoConfigurationTest {

    @Test
    void acceptsExplicitLocalInlineProviderAndCryptographicProbe() {
        DwpKeyManagementProperties properties = localProperties();
        LocalKeyProvider provider = new LocalKeyProvider(
                LocalKeyProvider.INLINE_PROVIDER,
                properties.getKeyReference(),
                properties.getActiveVersion(),
                Map.of(properties.getActiveVersion(), key(1)));

        KeyManagementAutoConfiguration.validate(
                new MockEnvironment().withProperty("dwp.environment", "local"),
                properties,
                provider);
    }

    @Test
    void rejectsEveryNonLocalAliasForLocalKeyProviders() {
        for (String environment : new String[] {
            "dev", "development", "qa", "staging", "prod", "production"
        }) {
            assertThatThrownBy(() -> KeyManagementAutoConfiguration.validate(
                    new MockEnvironment().withProperty("dwp.environment", environment),
                    localProperties(),
                    new LocalKeyProvider(
                            LocalKeyProvider.INLINE_PROVIDER,
                            "local://platform/productivity",
                            "v1",
                            Map.of("v1", key(2)))))
                    .isInstanceOf(KeyProviderException.class)
                    .hasMessageContaining("only in local");
        }
    }

    @Test
    void rejectsUnknownOrAmbiguousEnvironmentNames() {
        assertThatThrownBy(() -> KeyManagementAutoConfiguration.canonicalEnvironment(
                new MockEnvironment().withProperty("dwp.environment", "preprod")))
                .isInstanceOf(KeyProviderException.class)
                .hasMessageContaining("not recognized");

        MockEnvironment ambiguous = new MockEnvironment()
                .withProperty("dwp.environment", "local");
        ambiguous.setActiveProfiles("prod");
        assertThatThrownBy(() -> KeyManagementAutoConfiguration.canonicalEnvironment(ambiguous))
                .isInstanceOf(KeyProviderException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void rejectsUnavailableManagedAdapterWithoutLocalFallback() {
        DwpKeyManagementProperties properties = new DwpKeyManagementProperties();
        properties.setRequired(true);
        properties.setProvider("azure-key-vault");
        properties.setKeyReference(
                "https://dwp.vault.azure.net/keys/platform-productivity/immutable-version");
        properties.setService("dwp-platform-server");
        properties.setPurpose("productivity");

        assertThatThrownBy(() -> KeyManagementAutoConfiguration.validate(
                new MockEnvironment().withProperty("dwp.environment", "prod"),
                properties,
                null))
                .isInstanceOf(KeyProviderException.class)
                .hasMessageContaining("adapter is unavailable");
    }

    @Test
    void defaultsToLocalOnlyWhenNoEnvironmentIsConfigured() {
        assertThat(KeyManagementAutoConfiguration.canonicalEnvironment(new MockEnvironment()))
                .isEqualTo("local");
    }

    private DwpKeyManagementProperties localProperties() {
        DwpKeyManagementProperties properties = new DwpKeyManagementProperties();
        properties.setRequired(true);
        properties.setProvider(LocalKeyProvider.INLINE_PROVIDER);
        properties.setKeyReference("local://platform/productivity");
        properties.setActiveVersion("v1");
        properties.setInlineKey(Base64.getEncoder().encodeToString(key(1)));
        properties.setService("dwp-platform-server");
        properties.setPurpose("productivity");
        return properties;
    }

    private byte[] key(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return key;
    }
}
