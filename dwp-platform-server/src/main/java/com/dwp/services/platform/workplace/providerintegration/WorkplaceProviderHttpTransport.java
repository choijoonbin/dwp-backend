package com.dwp.services.platform.workplace.providerintegration;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Allowlisted HTTPS relay shared by Workplace provider adapters. */
@Component
@ConditionalOnProperty(
        name = "dwp.workplace.provider-integration.http.enabled",
        havingValue = "true")
public class WorkplaceProviderHttpTransport {
    private static final Pattern PROVIDER = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final Pattern SECRET_REFERENCE =
            Pattern.compile("secret-manager://[A-Za-z0-9._/-]{1,143}");

    private final RestClient client;
    private final List<WorkplaceProviderSecretOwner> secretOwners;
    private final Set<String> allowedProviders;

    public WorkplaceProviderHttpTransport(
            RestClient.Builder builder,
            List<WorkplaceProviderSecretOwner> secretOwners,
            @Value("${dwp.workplace.provider-integration.http.base-url:}") String baseUrl,
            @Value("${dwp.workplace.provider-integration.http.allowed-hosts:}") String allowedHosts,
            @Value("${dwp.workplace.provider-integration.http.allowed-providers:}")
            String allowedProviders,
            @Value("${dwp.workplace.provider-integration.http.allow-insecure-localhost:false}")
            boolean allowInsecureLocalhost) {
        URI base = validateBaseUrl(baseUrl, csv(allowedHosts), allowInsecureLocalhost);
        this.client = builder.baseUrl(base.toString()).build();
        this.secretOwners = List.copyOf(secretOwners);
        this.allowedProviders = csv(allowedProviders);
        if (this.allowedProviders.isEmpty()) {
            throw new IllegalStateException("At least one Workplace provider must be allowlisted.");
        }
    }

    public boolean ready(String provider, String credentialReference) {
        return provider != null && PROVIDER.matcher(provider).matches()
                && allowedProviders.contains(provider.toLowerCase(Locale.ROOT))
                && credentialReference != null
                && SECRET_REFERENCE.matcher(credentialReference).matches()
                && secretOwners.stream().filter(owner -> owner.supports(credentialReference)).count() == 1;
    }

    public JsonNode post(
            String provider,
            String credentialReference,
            String path,
            long tenantId,
            String idempotencyKey,
            Object body) {
        requireReady(provider, credentialReference);
        WorkplaceProviderSecretOwner owner = owner(credentialReference);
        try (WorkplaceProviderSecretOwner.SecretLease lease = owner.lease(credentialReference)) {
            char[] token = lease.bearerToken();
            try {
                return client.post().uri(path)
                        .header("X-DWP-Tenant-ID", Long.toString(tenantId))
                        .header("Idempotency-Key", idempotencyKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + new String(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body).retrieve().body(JsonNode.class);
            } finally {
                Arrays.fill(token, '\0');
            }
        }
    }

    public JsonNode get(
            String provider,
            String credentialReference,
            String path,
            long tenantId) {
        requireReady(provider, credentialReference);
        WorkplaceProviderSecretOwner owner = owner(credentialReference);
        try (WorkplaceProviderSecretOwner.SecretLease lease = owner.lease(credentialReference)) {
            char[] token = lease.bearerToken();
            try {
                return client.get().uri(path)
                        .header("X-DWP-Tenant-ID", Long.toString(tenantId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + new String(token))
                        .retrieve().body(JsonNode.class);
            } finally {
                Arrays.fill(token, '\0');
            }
        }
    }

    private void requireReady(String provider, String credentialReference) {
        if (!ready(provider, credentialReference)) {
            throw new IllegalStateException("The Workplace provider endpoint or secret owner is unavailable.");
        }
    }

    private WorkplaceProviderSecretOwner owner(String reference) {
        return secretOwners.stream().filter(candidate -> candidate.supports(reference))
                .findFirst().orElseThrow();
    }

    private static URI validateBaseUrl(String value, Set<String> allowedHosts, boolean allowLocal) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("The Workplace provider base URL is invalid.", invalid);
        }
        String host = uri.getHost();
        boolean local = host != null && (host.equals("localhost") || host.equals("127.0.0.1"));
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || (!"https".equalsIgnoreCase(uri.getScheme())
                    && !(allowLocal && local && "http".equalsIgnoreCase(uri.getScheme())))
                || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("The Workplace provider base URL is not allowlisted HTTPS.");
        }
        return uri;
    }

    private static Set<String> csv(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value == null) return result;
        for (String item : value.split(",")) {
            String normalized = item.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return Set.copyOf(result);
    }
}
