package com.dwp.services.platform.workplace.safetyoperations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyDispatchProvider.ProviderContext;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.DeliveryChannel;

/** Parses deploy-time provider identities while retaining only opaque secret-manager references. */
@Component
class SafetyProviderRelayBindings {
    private static final Pattern PROVIDER = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final Pattern SECRET_REFERENCE =
            Pattern.compile("secret-manager://[A-Za-z0-9._/-]{1,143}");

    private final Map<ProviderVersion, String> credentialReferences;
    private final Map<DeliveryChannel, ProviderSelection> channelBindings;

    SafetyProviderRelayBindings(
            @Value("${dwp.workplace.safety.provider-relay.credential-references:}")
            String credentialReferences,
            @Value("${dwp.workplace.safety.provider-relay.channel-bindings:}")
            String channelBindings) {
        this.credentialReferences = parseCredentials(credentialReferences);
        this.channelBindings = parseChannels(channelBindings, this.credentialReferences);
    }

    Optional<ProviderContext> resolve(DeliveryChannel channel) {
        ProviderSelection configured = channelBindings.get(channel);
        return configured == null ? Optional.empty() : resolve(channel, configured.code(),
                configured.version());
    }

    Optional<ProviderContext> resolve(
            DeliveryChannel channel, String providerCode, long configurationVersion) {
        if (channel == null || providerCode == null || configurationVersion < 1) {
            return Optional.empty();
        }
        String credential = credentialReferences.get(
                new ProviderVersion(canonical(providerCode), configurationVersion));
        return credential == null ? Optional.empty() : Optional.of(new ProviderContext(
                channel, providerCode, configurationVersion, credential));
    }

    private static Map<ProviderVersion, String> parseCredentials(String value) {
        Map<ProviderVersion, String> result = new LinkedHashMap<>();
        for (String item : csv(value)) {
            int equals = item.indexOf('=');
            String identity = equals < 1 ? "" : item.substring(0, equals).trim();
            String reference = equals < 1 ? "" : item.substring(equals + 1).trim();
            ProviderVersion key = providerVersion(identity);
            if (key == null || !SECRET_REFERENCE.matcher(reference).matches()
                    || result.putIfAbsent(key, reference) != null) {
                throw new IllegalStateException(
                        "Workplace safety provider credential references are invalid.");
            }
        }
        return Map.copyOf(result);
    }

    private static Map<DeliveryChannel, ProviderSelection> parseChannels(
            String value, Map<ProviderVersion, String> credentials) {
        Map<DeliveryChannel, ProviderSelection> result = new LinkedHashMap<>();
        for (String item : csv(value)) {
            int equals = item.indexOf('=');
            String channelValue = equals < 1 ? "" : item.substring(0, equals).trim();
            String identity = equals < 1 ? "" : item.substring(equals + 1).trim();
            DeliveryChannel channel;
            try {
                channel = DeliveryChannel.valueOf(channelValue);
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("Workplace safety provider channel binding is invalid.");
            }
            ProviderVersion key = providerVersion(identity);
            int at = identity.lastIndexOf('@');
            String provider = at < 1 ? "" : identity.substring(0, at).trim();
            ProviderSelection selection = key == null ? null
                    : new ProviderSelection(provider, key.version());
            if (key == null || !credentials.containsKey(key)
                    || result.putIfAbsent(channel, selection) != null) {
                throw new IllegalStateException("Workplace safety provider channel binding is invalid.");
            }
        }
        return Map.copyOf(result);
    }

    private static ProviderVersion providerVersion(String identity) {
        int at = identity.lastIndexOf('@');
        if (at < 1) return null;
        String provider = identity.substring(0, at).trim();
        long version;
        try {
            version = Long.parseLong(identity.substring(at + 1));
        } catch (NumberFormatException invalid) {
            return null;
        }
        return !PROVIDER.matcher(provider).matches() || version < 1
                ? null : new ProviderVersion(canonical(provider), version);
    }

    private static Iterable<String> csv(String value) {
        return value == null || value.isBlank() ? java.util.List.of()
                : java.util.Arrays.stream(value.split(",")).map(String::trim)
                        .filter(item -> !item.isEmpty()).toList();
    }

    private static String canonical(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private record ProviderVersion(String code, long version) { }
    private record ProviderSelection(String code, long version) { }
}
