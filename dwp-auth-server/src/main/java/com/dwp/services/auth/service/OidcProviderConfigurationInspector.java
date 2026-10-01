package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.IdentityProvider;
import com.dwp.services.auth.repository.IdentityProviderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Evaluates only locally knowable OIDC configuration. It never contacts the provider and never
 * returns endpoint values, environment-variable names, or secret material to callers.
 */
@Component
public class OidcProviderConfigurationInspector {

    public static final String PROVIDER_NOT_OBSERVED =
            "ENABLED_IDENTITY_PROVIDER_NOT_OBSERVED";
    public static final String CONFIGURATION_INCOMPLETE =
            "IDENTITY_PROVIDER_CONFIGURATION_INCOMPLETE";
    public static final String CLIENT_SECRET_UNAVAILABLE =
            "OIDC_CLIENT_SECRET_UNAVAILABLE";
    public static final String ENDPOINT_POLICY_INVALID =
            "OIDC_ENDPOINT_POLICY_INVALID";
    public static final String CALLBACK_POLICY_INVALID =
            "OIDC_CALLBACK_POLICY_INVALID";

    private final IdentityProviderRepository repository;
    private final Set<String> allowedHosts;
    private final Set<String> allowedCallbackHosts;
    private final boolean allowUnlistedHosts;
    private final String callbackUrl;
    private final Function<String, String> environmentReader;

    @Autowired
    public OidcProviderConfigurationInspector(
            IdentityProviderRepository repository,
            @Value("${dwp.auth.oidc.allowed-hosts:}") String allowedHosts,
            @Value("${dwp.auth.oidc.allowed-callback-hosts:}") String allowedCallbackHosts,
            @Value("${dwp.auth.oidc.allow-unlisted-hosts:false}") boolean allowUnlistedHosts,
            @Value("${sso.callback-url:http://localhost:4200/auth/oidc/callback}")
            String callbackUrl) {
        this(repository, allowedHosts, allowedCallbackHosts, allowUnlistedHosts,
                callbackUrl, System::getenv);
    }

    OidcProviderConfigurationInspector(
            IdentityProviderRepository repository,
            String allowedHosts,
            String allowedCallbackHosts,
            boolean allowUnlistedHosts,
            String callbackUrl,
            Function<String, String> environmentReader) {
        this.repository = repository;
        this.allowedHosts = parseHosts(allowedHosts);
        this.allowedCallbackHosts = parseHosts(allowedCallbackHosts);
        this.allowUnlistedHosts = allowUnlistedHosts;
        this.callbackUrl = callbackUrl == null ? "" : callbackUrl.trim();
        this.environmentReader = environmentReader;
    }

    public Assessment assess(Long tenantId, String providerKey) {
        if (isBlank(providerKey)) return Assessment.blocked(PROVIDER_NOT_OBSERVED);
        return repository.findByTenantIdAndProviderKey(tenantId, providerKey)
                .map(this::assess)
                .orElseGet(() -> Assessment.blocked(PROVIDER_NOT_OBSERVED));
    }

    public Assessment assess(IdentityProvider provider) {
        if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())
                || !"OIDC".equals(provider.getProviderType())) {
            return Assessment.blocked(PROVIDER_NOT_OBSERVED);
        }

        List<String> reasons = new ArrayList<>();
        if (isBlank(provider.getProviderKey()) || isBlank(provider.getAuthUrl())
                || isBlank(provider.getTokenUrl()) || isBlank(provider.getIssuerUri())
                || isBlank(provider.getMetadataUrl()) || isBlank(provider.getClientId())
                || isBlank(provider.getClientSecretEnv())) {
            reasons.add(CONFIGURATION_INCOMPLETE);
        }
        if (!isBlank(provider.getClientSecretEnv())
                && isBlank(environmentReader.apply(provider.getClientSecretEnv()))) {
            reasons.add(CLIENT_SECRET_UNAVAILABLE);
        }
        if (!configuredEndpointsAllowed(provider)) reasons.add(ENDPOINT_POLICY_INVALID);
        if (!callbackAllowed(callbackUrl)) reasons.add(CALLBACK_POLICY_INVALID);
        return reasons.isEmpty() ? Assessment.readyConfiguration() : Assessment.blocked(reasons);
    }

    public void requireOperational(IdentityProvider provider) {
        Assessment assessment = assess(provider);
        if (assessment.ready()) return;
        if (assessment.blockingReasons().contains(PROVIDER_NOT_OBSERVED)) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The OIDC provider is not enabled.");
        }
        if (assessment.blockingReasons().contains(CONFIGURATION_INCOMPLETE)) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The OIDC provider is incomplete.");
        }
        if (assessment.blockingReasons().contains(CLIENT_SECRET_UNAVAILABLE)) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC client secret is unavailable.");
        }
        if (assessment.blockingReasons().contains(ENDPOINT_POLICY_INVALID)) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC endpoint policy is invalid.");
        }
        throw new BaseException(ErrorCode.INVALID_STATE, "OIDC callback is not allowed.");
    }

    public String requireClientSecret(IdentityProvider provider) {
        String secret = isBlank(provider.getClientSecretEnv())
                ? null : environmentReader.apply(provider.getClientSecretEnv());
        if (isBlank(secret)) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC client secret is unavailable.");
        }
        return secret;
    }

    public void requireAllowedEndpoint(String value) {
        if (!endpointAllowed(value)) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC endpoint host is not allowed.");
        }
    }

    private boolean configuredEndpointsAllowed(IdentityProvider provider) {
        if (isBlank(provider.getIssuerUri()) || isBlank(provider.getMetadataUrl())
                || isBlank(provider.getAuthUrl()) || isBlank(provider.getTokenUrl())) {
            return false;
        }
        return endpointAllowed(provider.getIssuerUri())
                && endpointAllowed(provider.getMetadataUrl())
                && endpointAllowed(provider.getAuthUrl())
                && endpointAllowed(provider.getTokenUrl())
                && (isBlank(provider.getUserInfoUrl())
                || endpointAllowed(provider.getUserInfoUrl()));
    }

    private boolean endpointAllowed(String value) {
        URI uri = httpsUri(value);
        return uri != null && (allowUnlistedHosts
                || allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT)));
    }

    private boolean callbackAllowed(String value) {
        URI uri = httpsUri(value);
        return uri != null
                && allowedCallbackHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && "/auth/oidc/callback".equals(uri.getPath());
    }

    private URI httpsUri(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawFragment() != null) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static Set<String> parseHosts(String value) {
        if (value == null || value.isBlank()) return Set.of();
        String[] raw = value.split(",", -1);
        Set<String> result = new LinkedHashSet<>();
        for (String item : raw) {
            String host = item.trim().toLowerCase(Locale.ROOT);
            if (!host.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")
                    || host.contains("..") || !result.add(host)) {
                throw new IllegalArgumentException("OIDC host allowlist is invalid.");
            }
        }
        return Set.copyOf(result);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record Assessment(boolean ready, List<String> blockingReasons) {
        public Assessment {
            blockingReasons = List.copyOf(blockingReasons);
            if (ready == !blockingReasons.isEmpty()) {
                throw new IllegalArgumentException("OIDC readiness and blocking reasons disagree.");
            }
        }

        public static Assessment readyConfiguration() {
            return new Assessment(true, List.of());
        }

        public static Assessment blocked(String reason) {
            return new Assessment(false, List.of(reason));
        }

        public static Assessment blocked(List<String> reasons) {
            return new Assessment(false, reasons);
        }
    }
}
