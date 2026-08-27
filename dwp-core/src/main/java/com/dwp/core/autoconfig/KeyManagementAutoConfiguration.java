package com.dwp.core.autoconfig;

import com.dwp.core.crypto.EnvelopeEncryptionService;
import com.dwp.core.crypto.KeyContext;
import com.dwp.core.crypto.KeyProvider;
import com.dwp.core.crypto.KeyProviderException;
import com.dwp.core.crypto.LocalKeyProvider;
import com.dwp.core.crypto.LocalKeyProviderFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@AutoConfiguration
@EnableConfigurationProperties(DwpKeyManagementProperties.class)
public class KeyManagementAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(KeyProvider.class)
    @ConditionalOnProperty(
            prefix = "dwp.security.key-management",
            name = "provider",
            havingValue = LocalKeyProvider.INLINE_PROVIDER)
    LocalKeyProvider dwpLocalInlineKeyProvider(DwpKeyManagementProperties properties) {
        return LocalKeyProviderFactory.inline(
                properties.getKeyReference(),
                properties.getActiveVersion(),
                properties.getInlineKey(),
                properties.getPreviousInlineKeys());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(KeyProvider.class)
    @ConditionalOnProperty(
            prefix = "dwp.security.key-management",
            name = "provider",
            havingValue = LocalKeyProvider.FILE_PROVIDER)
    LocalKeyProvider dwpLocalFileKeyProvider(DwpKeyManagementProperties properties) {
        return LocalKeyProviderFactory.files(
                properties.getLocalRoot(),
                properties.getService(),
                properties.getPurpose(),
                properties.getKeyReference(),
                properties.getActiveVersion(),
                properties.getPreviousVersions());
    }

    @Bean
    @ConditionalOnBean(KeyProvider.class)
    @ConditionalOnMissingBean
    EnvelopeEncryptionService dwpEnvelopeEncryptionService(KeyProvider keyProvider) {
        return new EnvelopeEncryptionService(keyProvider);
    }

    @Bean
    ApplicationRunner dwpKeyManagementConfigurationGuard(
            Environment environment,
            DwpKeyManagementProperties properties,
            ObjectProvider<KeyProvider> provider) {
        return arguments -> validate(environment, properties, provider.getIfAvailable());
    }

    static void validate(
            Environment environment,
            DwpKeyManagementProperties properties,
            KeyProvider keyProvider) {
        String runtimeEnvironment = canonicalEnvironment(environment);
        String configuredProvider = properties.getProvider().toLowerCase(Locale.ROOT);
        boolean localProvider = LocalKeyProvider.INLINE_PROVIDER.equals(configuredProvider)
                || LocalKeyProvider.FILE_PROVIDER.equals(configuredProvider);
        boolean localRuntime = "local".equals(runtimeEnvironment);
        boolean hasInlineMaterial = !properties.getInlineKey().isBlank()
                || !properties.getPreviousInlineKeys().isEmpty();

        if (configuredProvider.isBlank()) {
            if (properties.isRequired() || hasInlineMaterial || keyProvider != null) {
                throw new KeyProviderException("Key provider configuration is incomplete.");
            }
            return;
        }
        if (localProvider && !localRuntime) {
            throw new KeyProviderException("Local key providers are allowed only in local runtime.");
        }
        if (!localRuntime && hasInlineMaterial) {
            throw new KeyProviderException("Inline key material is forbidden outside local runtime.");
        }
        if (LocalKeyProvider.FILE_PROVIDER.equals(configuredProvider) && hasInlineMaterial) {
            throw new KeyProviderException("Local file key provider cannot use inline fallback.");
        }
        if (LocalKeyProvider.INLINE_PROVIDER.equals(configuredProvider)
                && !properties.getPreviousVersions().isEmpty()) {
            throw new KeyProviderException("Local inline key provider cannot use file fallback.");
        }
        require(properties.getKeyReference(), "key reference");
        require(properties.getService(), "service");
        require(properties.getPurpose(), "purpose");
        if (localProvider) require(properties.getActiveVersion(), "active key version");
        if (keyProvider == null) {
            throw new KeyProviderException(
                    "Configured key provider adapter is unavailable; local fallback is disabled.");
        }
        if (!configuredProvider.equals(keyProvider.providerId())) {
            throw new KeyProviderException("Configured key provider does not match the active adapter.");
        }
        KeyContext probe = new KeyContext(
                runtimeEnvironment,
                properties.getService(),
                properties.getPurpose(),
                0,
                "startup-probe",
                properties.getService(),
                "readiness");
        keyProvider.probe(probe);
    }

    static String canonicalEnvironment(Environment environment) {
        String explicit = environment.getProperty("dwp.environment", "").trim();
        Set<String> candidates = new LinkedHashSet<>();
        if (!explicit.isBlank()) candidates.add(normalizeEnvironment(explicit));
        Arrays.stream(environment.getActiveProfiles())
                .map(KeyManagementAutoConfiguration::normalizeEnvironment)
                .forEach(candidates::add);
        candidates.remove("test");
        if (candidates.isEmpty()) return "local";
        if (candidates.size() != 1) {
            throw new KeyProviderException("Runtime environment profiles are ambiguous.");
        }
        return candidates.iterator().next();
    }

    private static String normalizeEnvironment(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "local" -> "local";
            case "dev", "development" -> "dev";
            case "qa", "staging" -> "qa";
            case "prod", "production" -> "prod";
            case "test" -> "test";
            default -> throw new KeyProviderException("Runtime environment is not recognized.");
        };
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new KeyProviderException("Key management " + field + " is required.");
        }
    }
}
