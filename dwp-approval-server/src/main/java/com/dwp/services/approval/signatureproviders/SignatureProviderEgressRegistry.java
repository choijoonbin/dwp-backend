package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.SignatureProviderModel.*;

import com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.SourcePin;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-owned configuration only; diagnostics inputs cannot register an origin or a credential. */
public final class SignatureProviderEgressRegistry {
    public record ConfiguredOrigin(long tenantId, UUID providerId, ProviderKind providerKind,
                                   Environment environment, SourcePin configuration, String accountBindingSha256,
                                   URI origin) {
        public ConfiguredOrigin {
            version(tenantId); if (tenantId < 1) throw invalid("Current tenant is required");
            required(providerId); required(providerKind); required(environment); required(configuration);
            sha(accountBindingSha256); requireOrigin(origin);
            if (providerKind == ProviderKind.INTERNAL || environment == Environment.UNCONFIGURED || environment == Environment.INTERNAL)
                throw invalid("External transport requires an explicitly configured provider and environment");
        }
    }

    public static final class Binding {
        private final ConfiguredOrigin configured;
        private Binding(ConfiguredOrigin configured) { this.configured = configured; }
        public long tenantId() { return configured.tenantId(); }
        public UUID providerId() { return configured.providerId(); }
        public ProviderKind providerKind() { return configured.providerKind(); }
        public Environment environment() { return configured.environment(); }
        public SourcePin configuration() { return configured.configuration(); }
        public String accountBindingSha256() { return configured.accountBindingSha256(); }
        public URI origin() { return configured.origin(); }
    }

    private record Key(long tenantId, UUID providerId, URI origin) { }
    private final Map<Key, Binding> bindings;

    public SignatureProviderEgressRegistry(List<ConfiguredOrigin> serverConfiguration, Set<URI> approvedOrigins) {
        required(approvedOrigins); if (approvedOrigins.size() > 40) throw invalid("Too many registered origins");
        approvedOrigins.forEach(SignatureProviderEgressRegistry::requireOrigin);
        var prepared = new HashMap<Key, Binding>();
        for (var configuration : bounded(serverConfiguration, 40)) {
            if (!approvedOrigins.contains(configuration.origin())) throw invalid("Origin is not explicitly approved");
            if (prepared.putIfAbsent(new Key(configuration.tenantId(), configuration.providerId(), configuration.origin()),
                    new Binding(configuration)) != null) throw invalid("Duplicate provider origin");
        }
        bindings = Map.copyOf(prepared);
    }

    public Binding require(long tenantId, UUID providerId, URI origin, SourcePin configuration,
                           Environment environment, String accountBindingSha256) {
        version(tenantId); if (tenantId < 1) throw invalid("Current tenant is required");
        var binding = bindings.get(new Key(tenantId, required(providerId), required(origin)));
        if (binding == null || !binding.configuration().equals(configuration) || binding.environment() != environment
                || !binding.accountBindingSha256().equals(accountBindingSha256))
            throw invalid("Provider configuration, account, environment or origin changed");
        return binding;
    }

    static void requireOrigin(URI origin) {
        required(origin); String host = origin.getHost();
        if (!"https".equals(origin.getScheme()) || host == null || !host.matches("[a-z0-9]+(?:[.-][a-z0-9]+)*")
                || host.length() > 253 || !host.contains(".") || host.matches("[0-9.]+")
                || origin.getPort() != -1 || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                || origin.getRawFragment() != null || !origin.toASCIIString().equals("https://" + host))
            throw invalid("Canonical HTTPS origin on port 443 required");
    }
}
